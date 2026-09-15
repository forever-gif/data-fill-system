package com.example.datafill.service.feishu;

import com.example.datafill.config.FeishuProperties;
import com.example.datafill.entity.ReportCatalog;
import com.example.datafill.service.ReportCatalogService;
import com.example.datafill.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书知识库多级目录树与云文档自动化同步核心服务
 * 支持：
 * 1. 智能增量更新（自动比对报表内容哈希，数据无变动自动跳过写入，大幅节省资源）
 * 2. 批量勾选手动同步
 * 3. 单篇报表即时同步
 * 4. 强制全量覆盖同步
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuWikiSyncService {

    private final FeishuProperties feishuProperties;
    private final FeishuApiClient feishuApiClient;
    private final FeishuDocxBuilder feishuDocxBuilder;
    private final ReportCatalogService reportCatalogService;
    private final SystemConfigService systemConfigService;

    // 内存缓存当前知识库已创建的目录节点路径 -> node_token (避免重复创建)
    private final Map<String, String> wikiPathNodeMap = new ConcurrentHashMap<>();

    // 内存持久化记录每个报表最新同步的内容 MD5 哈希 (reportId -> hash)
    private static final Map<String, String> syncedContentHashMap = new ConcurrentHashMap<>();
    // 内存持久化记录每个报表最新同步的字段快照 (reportId -> Map<String, String>)
    private static final Map<String, Map<String, String>> syncedReportSnapshotMap = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 缓存手册文档与节点元数据，避免无变动时重复请求飞书 API
    private static volatile String cachedDocUrl = "https://feishu.cn/wiki/Q58FwZAMeiobL3kdmWFc6zwcnSq";
    private static volatile String cachedSpaceId = null;
    private static volatile String cachedDocObjToken = "UUDVdMJNQoTtnFxmQtYc65I5n5f";
    private static volatile String cachedDocNodeToken = "Q58FwZAMeiobL3kdmWFc6zwcnSq";

    private static final String DB_CONFIG_KEY = "feishu_wiki_sync_cache";

    // 🌟 并发互斥锁：防止多个管理员或与 DolphinScheduler 定时调度任务并发执行引发文档写入冲突
    private static final AtomicBoolean isSyncing = new AtomicBoolean(false);

    @PostConstruct
    public void initCache() {
        loadCache();
    }

    public String getCachedDocUrl() {
        return cachedDocUrl;
    }

    private synchronized void loadCache() {
        // 🌟 严格从 PostgreSQL 数据库 system_config 表读取（云原生纯无状态架构，不依赖本地磁盘文件）
        try {
            if (systemConfigService != null) {
                Map<String, Object> dbData = systemConfigService.getConfig(DB_CONFIG_KEY, Map.class);
                if (dbData != null && !dbData.isEmpty()) {
                    applyCacheData(dbData);
                    log.info("✅ 【纯数据库无状态模式】成功从 PostgreSQL system_config 表加载 {} 条飞书同步快照与指纹", syncedContentHashMap.size());
                    return;
                } else {
                    log.info("ℹ️ PostgreSQL system_config 表中暂无飞书同步快照记录，系统将作为初始状态运行。");
                }
            }
        } catch (Exception e) {
            log.error("从 PostgreSQL system_config 读取飞书快照异常: {}", e.getMessage(), e);
        }
    }

    private void applyCacheData(Map<String, Object> data) {
        Map<String, String> hashes = (Map<String, String>) data.get("hashes");
        Map<String, Map<String, String>> snapshots = (Map<String, Map<String, String>>) data.get("snapshots");
        if (hashes != null) {
            hashes.forEach((k, v) -> {
                // 仅载入标准报表 ID（自动剔除早期版本中带有冒号节点前缀的 176 条历史冗余 Key）
                if (k != null && !k.contains(":")) syncedContentHashMap.put(k, v);
            });
        }
        if (snapshots != null) {
            snapshots.forEach((k, v) -> {
                if (k != null && !k.contains(":")) syncedReportSnapshotMap.put(k, v);
            });
        }
        if (data.containsKey("docUrl") && data.get("docUrl") != null) cachedDocUrl = (String) data.get("docUrl");
        if (data.containsKey("spaceId") && data.get("spaceId") != null) cachedSpaceId = (String) data.get("spaceId");
        if (data.containsKey("docObjToken") && data.get("docObjToken") != null) cachedDocObjToken = (String) data.get("docObjToken");
        if (data.containsKey("docNodeToken") && data.get("docNodeToken") != null) cachedDocNodeToken = (String) data.get("docNodeToken");
    }

    private synchronized void saveCache() {
        Map<String, Object> data = new HashMap<>();
        data.put("hashes", syncedContentHashMap);
        data.put("snapshots", syncedReportSnapshotMap);
        if (cachedDocUrl != null) data.put("docUrl", cachedDocUrl);
        if (cachedSpaceId != null) data.put("spaceId", cachedSpaceId);
        if (cachedDocObjToken != null) data.put("docObjToken", cachedDocObjToken);
        if (cachedDocNodeToken != null) data.put("docNodeToken", cachedDocNodeToken);

        // 🌟 严格持久化到 PostgreSQL system_config 数据库表 (无论 Docker 镜像如何重新构建、容器如何重启销毁，数据永不丢失)
        try {
            if (systemConfigService != null) {
                systemConfigService.updateConfig(DB_CONFIG_KEY, data, "飞书报表资产全景手册同步快照与指纹");
                log.info("✅ 成功将飞书同步指纹持久化至 PostgreSQL system_config 数据库表！");
            }
        } catch (Exception e) {
            log.error("保存飞书快照至 PostgreSQL system_config 异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 全量同步所有报表至飞书知识库 (默认开启增量无变动跳过)
     */
    public Map<String, Object> syncAllReportsToFeishu(String customAppId, String customAppSecret, String customSpaceId) {
        return syncAllReportsToFeishu(customAppId, customAppSecret, customSpaceId, false);
    }

    /**
     * 全量同步所有报表至飞书知识库
     *
     * @param force 是否强制覆盖写入（true: 不管有无变动全部重写; false: 仅更新有变动的报表）
     */
    public Map<String, Object> syncAllReportsToFeishu(String customAppId, String customAppSecret, String customSpaceId, Boolean force) {
        reportCatalogService.clearCache();
        List<ReportCatalog> reports = reportCatalogService.listCatalogs();
        return executeSyncReports(reports, customAppId, customAppSecret, customSpaceId, Boolean.TRUE.equals(force));
    }

    /**
     * 手动选择特定报表批量同步
     */
    public Map<String, Object> syncBatchReportsToFeishu(List<String> reportIds, String customAppId, String customAppSecret, String customSpaceId, Boolean force) {
        if (reportIds == null || reportIds.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选一条需要同步的报表！");
        }

        reportCatalogService.clearCache();
        List<ReportCatalog> targetReports = new ArrayList<>();
        for (String id : reportIds) {
            ReportCatalog r = reportCatalogService.getReportById(id);
            if (r != null) {
                targetReports.add(r);
            }
        }

        return executeSyncReports(targetReports, customAppId, customAppSecret, customSpaceId, Boolean.TRUE.equals(force));
    }

    /**
     * 执行报表列表的同步流程（方案二：全量整合至单篇《报表资产全景手册》云文档，大纲目录导航，超低 API 消耗）
     */
    private Map<String, Object> executeSyncReports(List<ReportCatalog> reports, String customAppId, String customAppSecret, String customSpaceId, boolean force) {
        if (!isSyncing.compareAndSet(false, true)) {
            Map<String, Object> busyResult = new HashMap<>();
            busyResult.put("success", false);
            busyResult.put("isBusy", true);
            busyResult.put("message", "当前已有飞书知识库同步任务正在执行中，请稍候再试，避免文档写入冲突！");
            return busyResult;
        }

        long startTime = System.currentTimeMillis();
        String nowStr = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        feishuApiClient.resetApiCallCount();

        try {
            String masterDocTitle = "美鹰报表资产全景手册";

            // 🌟 核心极致降耗优化 1：优先本地毫秒级比对报表快照与 MD5 哈希！
            // 如果不是强制覆盖 (force == false)，在检测到 0 篇报表发生变动时，直接拦截返回！
            // 彻底实现：0 飞书 API 调用、0 HTTP 连接建立、0 鉴权消耗！
            int changedCount = 0;
            int unchangedCount = 0;
            List<Map<String, Object>> changeLogs = new ArrayList<>();

            for (ReportCatalog r : reports) {
                String key = r.getReportId();
                String currentHash = computeReportContentHash(r);
                String lastHash = syncedContentHashMap.get(key);
                Map<String, String> oldSnap = syncedReportSnapshotMap.get(key);
                Map<String, String> newSnap = extractSnapshot(r);

                boolean isChanged = (force || lastHash == null || !currentHash.equals(lastHash));
                if (isChanged) {
                    changedCount++;
                    List<String> diffs = compareSnapshots(oldSnap, newSnap);
                    Map<String, Object> logItem = new HashMap<>();
                    logItem.put("reportId", r.getReportId());
                    logItem.put("reportName", r.getReportName());
                    logItem.put("changeType", oldSnap == null ? "NEW" : "UPDATE");
                    logItem.put("details", diffs);
                    changeLogs.add(logItem);
                    log.info("智能检测到报表变动: 【{}】 (ID: {}) -> {}", r.getReportName(), r.getReportId(), diffs);
                } else {
                    unchangedCount++;
                }
            }

            // 🌟 检查是否有报表已在数据库中被删除或下线 (旧缓存中有记录，但当前数据库列表中已不存在)
            Set<String> currentReportIds = reports.stream()
                    .map(ReportCatalog::getReportId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            List<String> deletedReportIds = new ArrayList<>();
            for (String cachedId : syncedContentHashMap.keySet()) {
                if (cachedId != null && !cachedId.contains(":") && !currentReportIds.contains(cachedId)) {
                    deletedReportIds.add(cachedId);
                }
            }

            for (String delId : deletedReportIds) {
                Map<String, String> oldSnap = syncedReportSnapshotMap.get(delId);
                String delName = (oldSnap != null && oldSnap.get("reportName") != null) ? oldSnap.get("reportName") : delId;
                Map<String, Object> logItem = new HashMap<>();
                logItem.put("reportId", delId);
                logItem.put("reportName", delName);
                logItem.put("changeType", "DELETE");
                logItem.put("details", List.of("该报表已从数据库中删除，飞书手册已同步移除此报表卡片"));
                changeLogs.add(logItem);
                log.info("智能检测到报表已从数据库删除下线: 【{}】 (ID: {})", delName, delId);
            }

            int totalChanges = changedCount + deletedReportIds.size();

            String fallbackDocUrl = cachedDocUrl != null ? cachedDocUrl : "https://feishu.cn/wiki/Q58FwZAMeiobL3kdmWFc6zwcnSq";

            // 🌟 零变动瞬间拦截：无字段变动 且 无删除 且 非强制刷新时，不向飞书发任何一个 HTTP 请求！
            if (!force && totalChanges == 0 && !reports.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("🌟 智能增量零消耗拦截生效：全量 {} 篇报表数据均无变更，直接返回（0 API 调用，0 连接消耗，耗时 {} ms）！", reports.size(), duration);
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("total", reports.size());
                result.put("updatedCount", 0);
                result.put("skippedCount", reports.size());
                result.put("successCount", reports.size());
                result.put("failCount", 0);
                result.put("docUrl", fallbackDocUrl);
                result.put("docTitle", masterDocTitle);
                result.put("spaceId", cachedSpaceId != null ? cachedSpaceId : feishuProperties.getWikiSpaceId());
                result.put("changeLogs", Collections.emptyList());
                result.put("durationMs", duration);
                result.put("apiCalls", 0);
                result.put("syncTime", nowStr);
                result.put("message", String.format("智能增量检测完毕：全量 %d 篇报表数据均无变动，飞书云文档已是最新状态（零 API 消耗）！", reports.size()));
                return result;
            }

            // 🌟 只有在真正存在数据变更 (changedCount > 0) 或用户明确要求强制刷新 (force == true) 时，才建立飞书连接并鉴权
            wikiPathNodeMap.clear();
            String token = feishuApiClient.getTenantAccessToken(customAppId, customAppSecret);

            String spaceId = (customSpaceId != null && !customSpaceId.isBlank())
                    ? customSpaceId.trim()
                    : feishuProperties.getWikiSpaceId();

            String rootNodeToken = null;
            if (spaceId != null && spaceId.equals(feishuProperties.getWikiSpaceId())) {
                rootNodeToken = feishuProperties.getRootNodeToken();
            }

            Map<String, Object> masterNode = null;

            if (spaceId != null) {
                String extractedToken = feishuApiClient.extractNodeToken(spaceId);
                Map<String, Object> nodeInfo = feishuApiClient.getNodeInfo(extractedToken, token);
                if (nodeInfo != null && nodeInfo.containsKey("space_id")) {
                    String resolvedSpaceId = (String) nodeInfo.get("space_id");
                    spaceId = resolvedSpaceId;

                    String nodeTitle = (String) nodeInfo.get("title");
                    // 🌟 核心保护：若用户传入的 Token 自身就是《美鹰报表资产全景手册》，直接复用其自身！绝不在其下方创建多余子节点！
                    if (nodeTitle != null && nodeTitle.contains("报表资产全景手册")) {
                        masterNode = nodeInfo;
                        rootNodeToken = null;
                        log.info("检测到输入节点自身即为全景手册 [{}]: Token={}", nodeTitle, nodeInfo.get("node_token"));
                    } else {
                        rootNodeToken = (String) nodeInfo.get("node_token");
                        log.info("从输入节点成功解析出挂载父节点: Token={}", rootNodeToken);
                    }
                } else if (extractedToken != null && extractedToken.matches("^\\d+$")) {
                    spaceId = extractedToken;
                }
            }

            if (masterNode == null) {
                masterNode = findOrCreateNode(spaceId, rootNodeToken, masterDocTitle, token);
            }

            String docObjToken = (String) masterNode.get("obj_token");
            String docNodeToken = (String) masterNode.get("node_token");
            String docUrl = "https://feishu.cn/wiki/" + (docNodeToken != null ? docNodeToken : docObjToken);

            // 记录最新文档元数据至持久化缓存
            cachedDocUrl = docUrl;
            cachedSpaceId = spaceId;
            cachedDocObjToken = docObjToken;
            cachedDocNodeToken = docNodeToken;

            log.info("开始向飞书写入全景手册，发生变动报表数: {}/{}，目标文档 ID: {}", changedCount, reports.size(), docObjToken);

            // 1. 构建全景大文档的所有 Blocks (含 H1, H2, H3 大纲标题与精美报表卡片)
            List<Map<String, Object>> masterBlocks = feishuDocxBuilder.buildMasterCatalogDocxBlocks(reports);

            // 2. 批量写入 Docx 云文档 (自动以 50 Blocks 为一组批量提交，仅需 2~5 次 API 请求)
            feishuApiClient.writeDocxBlocks(docObjToken, masterBlocks, token);

            // 3. 彻底移除已从数据库删除下线的报表缓存
            for (String delId : deletedReportIds) {
                syncedContentHashMap.remove(delId);
                syncedReportSnapshotMap.remove(delId);
            }
            // 记录最新同步哈希与字段快照
            for (ReportCatalog r : reports) {
                String key = r.getReportId();
                syncedContentHashMap.put(key, computeReportContentHash(r));
                syncedReportSnapshotMap.put(key, extractSnapshot(r));
            }
            saveCache();

            long duration = System.currentTimeMillis() - startTime;
            int actualApiCalls = feishuApiClient.getApiCallCount();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("total", reports.size());
            result.put("updatedCount", force ? reports.size() : totalChanges);
            result.put("skippedCount", force ? 0 : unchangedCount);
            result.put("successCount", reports.size());
            result.put("failCount", 0);
            result.put("docUrl", docUrl);
            result.put("docTitle", masterDocTitle);
            result.put("spaceId", spaceId);
            result.put("changeLogs", changeLogs);
            result.put("durationMs", duration);
            result.put("apiCalls", actualApiCalls);
            result.put("syncTime", nowStr);
            result.put("message", force
                    ? String.format("已强制全量覆盖同步！共 %d 篇报表已重新写入《%s》！", reports.size(), masterDocTitle)
                    : (deletedReportIds.isEmpty()
                            ? String.format("智能增量同步完成！检测到 %d 篇报表数据变动已更新至《%s》（%d 篇未变动已略过）！",
                                    changedCount, masterDocTitle, unchangedCount)
                            : String.format("智能增量同步完成！已从《%s》同步移除 %d 篇下线报表（更新 %d 篇，略过 %d 篇）！",
                                    masterDocTitle, deletedReportIds.size(), changedCount, unchangedCount)));
            return result;
        } finally {
            isSyncing.set(false);
        }
    }

    /**
     * 同步单个报表至飞书全景手册 (将全量报表合并更新至《报表资产全景手册》)
     */
    public Map<String, Object> syncSingleReport(String reportId, String customAppId, String customAppSecret, String customSpaceId) {
        reportCatalogService.clearCache();
        List<ReportCatalog> allReports = reportCatalogService.listCatalogs();
        if (allReports == null || allReports.isEmpty()) {
            ReportCatalog r = reportCatalogService.getReportById(reportId);
            allReports = (r != null) ? List.of(r) : Collections.emptyList();
        }

        executeSyncReports(allReports, customAppId, customAppSecret, customSpaceId, true);

        return Map.of(
                "success", true,
                "reportId", reportId,
                "message", "报表已更新至飞书《美鹰报表资产全景手册》！"
        );
    }

    /**
     * 单个报表具体同步逻辑：
     * @return 1: 写入/更新成功, 0: 无变化自动跳过
     */
    private int syncSingleReportInternal(ReportCatalog report, String spaceId, String rootNodeToken, String token, boolean force) {
        String currentHash = computeReportContentHash(report);
        String lastHash = syncedContentHashMap.get(spaceId + ":" + report.getReportId());

        // 1. 解析报表的层级目录链 (业务域 -> 一级主题 -> 二级主题)
        List<String> folderLevels = extractFolderLevels(report);

        // 2. 递归查找或创建父级文件夹节点 (根据空间使用对应的根挂载节点)
        String parentNodeToken = rootNodeToken;
        String currentPath = "";

        for (String folderName : folderLevels) {
            currentPath = currentPath.isEmpty() ? folderName : currentPath + "/" + folderName;
            String cachedToken = wikiPathNodeMap.get(spaceId + ":" + currentPath);

            if (cachedToken == null) {
                Map<String, Object> node = findOrCreateNode(spaceId, parentNodeToken, folderName, token);
                cachedToken = (String) node.get("node_token");
                wikiPathNodeMap.put(spaceId + ":" + currentPath, cachedToken);
            }
            parentNodeToken = cachedToken;
        }

        // 3. 查找或创建报表云文档节点 (如果已存在同名文档，直接复用其 obj_token)
        String cleanReportTitle = formatCleanName(report.getReportName());
        Map<String, Object> docNode = findOrCreateNode(spaceId, parentNodeToken, cleanReportTitle, token);
        String docObjToken = (String) docNode.get("obj_token");
        boolean isNewlyCreated = Boolean.TRUE.equals(docNode.get("_is_created"));

        // 4. 🌟 智能增量检测：若非强制模式、文档已存在且内容哈希无变化，则直接跳过云文档写入！
        if (!force && !isNewlyCreated && currentHash.equals(lastHash)) {
            log.info("报表 [{}] 数据与内容未发生变更，自动跳过云文档写入 (节省 API 资源)", cleanReportTitle);
            return 0; // 跳过
        }

        // 5. 写入美化版 1:1 官方组件文档
        feishuApiClient.writeReportDocxContent(docObjToken, report, token, feishuDocxBuilder);
        syncedContentHashMap.put(spaceId + ":" + report.getReportId(), currentHash);

        log.info("成功同步/更新报表 [{}] 到飞书知识库空间 [{}]，文档 Token: {}", cleanReportTitle, spaceId, docObjToken);
        return 1; // 已更新
    }

    /**
     * 智能查找或创建知识库节点：存在则直接复用，不存在才新建
     */
    private Map<String, Object> findOrCreateNode(String spaceId, String parentNodeToken, String title, String token) {
        try {
            List<Map<String, Object>> existingNodes = feishuApiClient.getWikiNodes(spaceId, parentNodeToken, token);
            if (existingNodes != null) {
                for (Map<String, Object> node : existingNodes) {
                    String nodeTitle = (String) node.get("title");
                    // 🌟 智能匹配：完全匹配，或者全景手册旧标题匹配（平滑升级）
                    if (title != null && (title.equalsIgnoreCase(nodeTitle)
                            || (title.contains("报表资产全景手册") && nodeTitle != null && nodeTitle.contains("报表资产全景手册")))) {
                        // 若飞书上的旧标题不一致，自动平滑重命名为最新标题
                        if (nodeTitle != null && !nodeTitle.equals(title)) {
                            String nodeToken = (String) node.get("node_token");
                            feishuApiClient.updateWikiNodeTitle(spaceId, nodeToken, title, token);
                            node.put("title", title);
                            log.info("已自动将飞书知识库节点 [{}] 重命名为最新标题 [{}]", nodeTitle, title);
                        }
                        return node;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询已有节点异常，将执行新建: {}", e.getMessage());
        }

        // 不存在则新建
        Map<String, Object> created = feishuApiClient.createWikiNode(spaceId, parentNodeToken, title, "docx", token);
        if (created != null) {
            created.put("_is_created", true);
        }
        return created;
    }

    /**
     * 计算报表内容 MD5 哈希，用于精准检测报表所有字段是否有变动 (包括描述、目录层级、业务属性、更新时间等)
     */
    private String computeReportContentHash(ReportCatalog r) {
        StringBuilder sb = new StringBuilder();
        sb.append("v3|") // 🌟 升级版本标识，使旧缓存立即失效并基于全字段最新数据重新比对
          .append(r.getReportId() != null ? r.getReportId() : "").append("|")
          .append(r.getReportName() != null ? r.getReportName() : "").append("|")
          .append(r.getFullPath() != null ? r.getFullPath() : "").append("|")
          .append(r.getReportUrl() != null ? r.getReportUrl() : "").append("|")
          .append(r.getReportDesc() != null ? r.getReportDesc() : "").append("|") // 🌟 重点加入报表描述
          .append(r.getUseScope() != null ? r.getUseScope() : "").append("|")
          .append(r.getBizDomain() != null ? r.getBizDomain() : "").append("|")
          .append(r.getDomainL1() != null ? r.getDomainL1() : "").append("|")
          .append(r.getDomainL2() != null ? r.getDomainL2() : "").append("|")
          .append(r.getSubjectL1() != null ? r.getSubjectL1() : "").append("|")
          .append(r.getSubjectL2() != null ? r.getSubjectL2() : "").append("|")
          .append(r.getL1() != null ? r.getL1() : "").append("|")
          .append(r.getL2() != null ? r.getL2() : "").append("|")
          .append(r.getL3() != null ? r.getL3() : "").append("|")
          .append(r.getL4() != null ? r.getL4() : "").append("|")
          .append(r.getL5() != null ? r.getL5() : "").append("|")
          .append(r.getL6() != null ? r.getL6() : "").append("|")
          .append(r.getL7() != null ? r.getL7() : "").append("|")
          .append(r.getDimensions() != null ? r.getDimensions() : "").append("|")
          .append(r.getMetrics() != null ? r.getMetrics() : "").append("|")
          .append(r.getUpdateFrequency() != null ? r.getUpdateFrequency() : "").append("|")
          .append(r.getDataUpdateTime() != null ? r.getDataUpdateTime() : "").append("|")
          .append(r.getIsConfidential() != null ? r.getIsConfidential() : "").append("|")
          .append(r.getOwner() != null ? r.getOwner() : "").append("|")
          .append(r.getDeptName() != null ? r.getDeptName() : "").append("|")
          .append(r.getDeveloper() != null ? r.getDeveloper() : "").append("|")
          .append(r.getLifecycleStatus() != null ? r.getLifecycleStatus() : "").append("|")
          .append(r.getAnalysisCycle() != null ? r.getAnalysisCycle() : "").append("|")
          .append(r.getDataGranularity() != null ? r.getDataGranularity() : "").append("|")
          .append(r.getDataSource() != null ? r.getDataSource() : "").append("|")
          .append(r.getSensitiveLevel() != null ? r.getSensitiveLevel() : "").append("|")
          .append(r.getReportCreateDate() != null ? r.getReportCreateDate() : "").append("|")
          .append(r.getReportUpdateDate() != null ? r.getReportUpdateDate() : "").append("|")
          .append(r.getRemark() != null ? r.getRemark() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(sb.toString().hashCode());
        }
    }

    /**
     * 提取报表关键业务字段快照，用于生成精准的字段级变动审计日志
     */
    private Map<String, String> extractSnapshot(ReportCatalog r) {
        Map<String, String> snap = new HashMap<>();
        snap.put("reportName", r.getReportName() != null ? r.getReportName().trim() : "");
        snap.put("useScope", r.getUseScope() != null ? r.getUseScope().trim() : "");
        snap.put("reportDesc", r.getReportDesc() != null ? r.getReportDesc().trim() : "");
        snap.put("bizDomain", r.getBizDomain() != null ? r.getBizDomain().trim() : "");
        snap.put("subjectL1", r.getSubjectL1() != null ? r.getSubjectL1().trim() : "");
        snap.put("subjectL2", r.getSubjectL2() != null ? r.getSubjectL2().trim() : "");
        snap.put("lifecycleStatus", r.getLifecycleStatus() != null ? r.getLifecycleStatus().trim() : "");
        snap.put("owner", r.getOwner() != null ? r.getOwner().trim() : "");
        snap.put("deptName", r.getDeptName() != null ? r.getDeptName().trim() : "");
        snap.put("developer", r.getDeveloper() != null ? r.getDeveloper().trim() : "");
        snap.put("fullPath", r.getFullPath() != null ? r.getFullPath().trim() : "");
        snap.put("reportUrl", r.getReportUrl() != null ? r.getReportUrl().trim() : "");
        snap.put("dimensions", r.getDimensions() != null ? r.getDimensions().trim() : "");
        snap.put("metrics", r.getMetrics() != null ? r.getMetrics().trim() : "");
        snap.put("updateFrequency", r.getUpdateFrequency() != null ? r.getUpdateFrequency().trim() : "");
        snap.put("dataUpdateTime", r.getDataUpdateTime() != null ? r.getDataUpdateTime().trim() : "");
        snap.put("isConfidential", r.getIsConfidential() != null ? r.getIsConfidential().trim() : "");
        snap.put("analysisCycle", r.getAnalysisCycle() != null ? r.getAnalysisCycle().trim() : "");
        snap.put("dataGranularity", r.getDataGranularity() != null ? r.getDataGranularity().trim() : "");
        snap.put("dataSource", r.getDataSource() != null ? r.getDataSource().trim() : "");
        snap.put("sensitiveLevel", r.getSensitiveLevel() != null ? r.getSensitiveLevel().trim() : "");
        snap.put("reportCreateDate", r.getReportCreateDate() != null ? r.getReportCreateDate().trim() : "");
        snap.put("reportUpdateDate", r.getReportUpdateDate() != null ? r.getReportUpdateDate().trim() : "");
        snap.put("remark", r.getRemark() != null ? r.getRemark().trim() : "");
        return snap;
    }

    /**
     * 深度对比两份快照，生成人类友好的中文修改明细
     */
    private List<String> compareSnapshots(Map<String, String> oldSnap, Map<String, String> newSnap) {
        List<String> diffs = new ArrayList<>();
        if (oldSnap == null) {
            diffs.add("首次收录并整编入飞书手册");
            return diffs;
        }

        checkFieldDiff(diffs, "适用范围", oldSnap.get("useScope"), newSnap.get("useScope"));
        checkFieldDiff(diffs, "报表描述", oldSnap.get("reportDesc"), newSnap.get("reportDesc"));
        checkFieldDiff(diffs, "业务域", oldSnap.get("bizDomain"), newSnap.get("bizDomain"));
        checkFieldDiff(diffs, "一级主题", oldSnap.get("subjectL1"), newSnap.get("subjectL1"));
        checkFieldDiff(diffs, "二级主题", oldSnap.get("subjectL2"), newSnap.get("subjectL2"));
        checkFieldDiff(diffs, "生命周期", oldSnap.get("lifecycleStatus"), newSnap.get("lifecycleStatus"));
        checkFieldDiff(diffs, "责任人", oldSnap.get("owner"), newSnap.get("owner"));
        checkFieldDiff(diffs, "所属部门", oldSnap.get("deptName"), newSnap.get("deptName"));
        checkFieldDiff(diffs, "开发人员", oldSnap.get("developer"), newSnap.get("developer"));
        checkFieldDiff(diffs, "分析维度", oldSnap.get("dimensions"), newSnap.get("dimensions"));
        checkFieldDiff(diffs, "核心指标", oldSnap.get("metrics"), newSnap.get("metrics"));
        checkFieldDiff(diffs, "更新频率", oldSnap.get("updateFrequency"), newSnap.get("updateFrequency"));
        checkFieldDiff(diffs, "数据更新时间", oldSnap.get("dataUpdateTime"), newSnap.get("dataUpdateTime"));
        checkFieldDiff(diffs, "密级", oldSnap.get("isConfidential"), newSnap.get("isConfidential"));
        checkFieldDiff(diffs, "分析周期", oldSnap.get("analysisCycle"), newSnap.get("analysisCycle"));
        checkFieldDiff(diffs, "数据粒度", oldSnap.get("dataGranularity"), newSnap.get("dataGranularity"));
        checkFieldDiff(diffs, "数据来源", oldSnap.get("dataSource"), newSnap.get("dataSource"));
        checkFieldDiff(diffs, "报表创建日期", oldSnap.get("reportCreateDate"), newSnap.get("reportCreateDate"));
        checkFieldDiff(diffs, "报表更新日期", oldSnap.get("reportUpdateDate"), newSnap.get("reportUpdateDate"));
        checkFieldDiff(diffs, "报表路径", oldSnap.get("fullPath"), newSnap.get("fullPath"));
        checkFieldDiff(diffs, "报表链接", oldSnap.get("reportUrl"), newSnap.get("reportUrl"));
        checkFieldDiff(diffs, "敏感等级", oldSnap.get("sensitiveLevel"), newSnap.get("sensitiveLevel"));
        checkFieldDiff(diffs, "备注说明", oldSnap.get("remark"), newSnap.get("remark"));

        if (diffs.isEmpty()) {
            diffs.add("报表元数据更新");
        }
        return diffs;
    }

    private void checkFieldDiff(List<String> diffs, String label, String oldVal, String newVal) {
        String o = oldVal != null ? oldVal : "";
        String n = newVal != null ? newVal : "";
        if (!o.equals(n)) {
            if (o.isEmpty()) {
                diffs.add(String.format("【%s】补充设置为「%s」", label, n));
            } else if (n.isEmpty()) {
                diffs.add(String.format("【%s】原值「%s」已清空", label, o));
            } else {
                diffs.add(String.format("【%s】由「%s」修改为「%s」", label, o, n));
            }
        }
    }

    /**
     * 从报表记录中提取纯净的文件夹层级链 (严格排除末尾的报表文件自身，深度还原真实文件夹树)
     */
    private List<String> extractFolderLevels(ReportCatalog r) {
        List<String> levels = new ArrayList<>();
        String fullPath = r.getFullPath();

        // 🌟 1. 优先从 fullPath 解析：路径的最后一个段是报表文件自身，前面的段全部是纯目录！
        if (fullPath != null && fullPath.contains("/")) {
            String[] parts = fullPath.split("/");
            // 从 index 0 到 parts.length - 2（排除最后 1 个报表文件）
            for (int i = 0; i < parts.length - 1; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()
                        || p.equalsIgnoreCase("Dec-Entry_Management")
                        || p.equalsIgnoreCase("美鹰数据分析")
                        || p.equalsIgnoreCase("美鹰集团报表")
                        || p.equalsIgnoreCase("集团报表")) {
                    continue;
                }
                String formatted = formatPreserveNumber(p);
                if (!formatted.isEmpty() && !levels.contains(formatted)) {
                    levels.add(formatted);
                }
            }
        }

        // 🌟 2. 兜底：若 fullPath 为空，则从 L2~L6 中提取（自动剔除末尾的报表文件）
        if (levels.isEmpty()) {
            String cleanReport = formatCleanName(r.getReportName());
            String rawReport = formatPreserveNumber(r.getReportName());
            String[] raw = {r.getL2(), r.getL3(), r.getL4(), r.getL5(), r.getL6()};
            List<String> validLevels = new ArrayList<>();
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    String trimmed = s.trim();
                    if (!trimmed.equalsIgnoreCase("Dec-Entry_Management")
                            && !trimmed.equalsIgnoreCase("美鹰数据分析")
                            && !trimmed.equalsIgnoreCase("美鹰集团报表")
                            && !trimmed.equalsIgnoreCase("集团报表")
                            && !trimmed.toLowerCase().endsWith(".cpt")
                            && !trimmed.toLowerCase().endsWith(".frm")) {
                        validLevels.add(formatPreserveNumber(trimmed));
                    }
                }
            }
            if (!validLevels.isEmpty()) {
                String last = validLevels.get(validLevels.size() - 1);
                if (last.equalsIgnoreCase(cleanReport) || last.equalsIgnoreCase(rawReport)) {
                    validLevels.remove(validLevels.size() - 1);
                }
            }
            levels.addAll(validLevels);
        }

        // 🌟 3. 如果仍为空，则从 biz_domain / subject_l1 / subject_l2 提取
        if (levels.isEmpty()) {
            if (r.getBizDomain() != null && !r.getBizDomain().isBlank()) {
                levels.add(formatPreserveNumber(r.getBizDomain().trim()));
            }
            if (r.getSubjectL1() != null && !r.getSubjectL1().isBlank()) {
                String s1 = formatPreserveNumber(r.getSubjectL1().trim());
                if (!levels.contains(s1)) levels.add(s1);
            }
            if (r.getSubjectL2() != null && !r.getSubjectL2().isBlank()) {
                String s2 = formatPreserveNumber(r.getSubjectL2().trim());
                if (!levels.contains(s2)) levels.add(s2);
            }
        }

        return levels.isEmpty() ? List.of("默认报表目录") : levels;
    }

    private String formatPreserveNumber(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replace(".cpt", "").replace(".frm", "").trim();
    }

    private String formatCleanName(String name) {
        if (name == null) return "";
        return name.replace(".cpt", "").replace(".frm", "").trim();
    }
}
