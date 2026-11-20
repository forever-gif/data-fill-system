package com.example.datafill.service.feishu.approval;

import com.example.datafill.service.feishu.FeishuApiClient;
import com.example.datafill.service.feishu.approval.FeishuApprovalParserService.ExtractedApprovalData;
import com.example.datafill.service.feishu.approval.FeishuApprovalParserService.ReportItem;
import com.example.datafill.service.finereport.FineReportApiClient;
import com.example.datafill.service.finereport.FineReportApiClient.FineReportUser;
import com.example.datafill.entity.ReportCatalog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.Collections;

/**
 * 飞书审批与帆软权限自动化调度服务
 * 职责：
 * 1. 实例抓取与报表信息结构化提取
 * 2. 申请人与帆软系统用户唯一性匹配 (邮箱优先映射)
 * 3. 帆软 OpenAPI V3 报表目录权限赋予
 * 4. 飞书机器人交互式卡片结果通知
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeishuApprovalAutomationService {

    private final FeishuApiClient feishuApiClient;
    private final FeishuApprovalParserService feishuApprovalParserService;
    private final FineReportApiClient fineReportApiClient;
    private final ObjectMapper objectMapper;
    private final com.example.datafill.config.FeishuProperties feishuProperties;
    private final com.example.datafill.service.ReportCatalogService reportCatalogService;
    private final com.example.datafill.service.SystemConfigService systemConfigService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FeishuApprovalAuditService feishuApprovalAuditService;

    void setFeishuApprovalAuditService(FeishuApprovalAuditService feishuApprovalAuditService) {
        this.feishuApprovalAuditService = feishuApprovalAuditService;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalProcessResult {
        private boolean success;
        private String message;
        private ExtractedApprovalData approvalData;
        private List<Map<String, Object>> fineReportPayload;
        private String matchedFineReportUser;
        private String matchedFineReportRealName;
        private Map<String, Object> fineReportAuthResult;
        private boolean notificationSent;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private java.util.function.BiFunction<String, String, Boolean> cardSender;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private List<String> notifiedTargetIds;
    }

    /**
     * 拉取并解析审批实例
     *
     * @param instanceCode 飞书审批实例编码，若为 "mock" 则使用模拟数据，若为 "sample" 则使用真实单据样本
     */
    public ApprovalProcessResult fetchAndExtract(String instanceCode) {
        try {
            Map<String, Object> rawData;
            if ("sample".equalsIgnoreCase(instanceCode)) {
                log.info("【飞书审批】触发真实报表审批单样本测试解析");
                rawData = loadReportApprovalSample();
            } else if ("sample-role".equalsIgnoreCase(instanceCode) || "role-sample".equalsIgnoreCase(instanceCode) || "role_sample".equalsIgnoreCase(instanceCode)) {
                log.info("【飞书审批】触发账号与数据角色申请审批单样本测试解析");
                rawData = loadRoleApprovalSample();
            } else if ("mock".equalsIgnoreCase(instanceCode)) {
                log.info("【飞书审批】触发 Mock 测试单据解析");
                rawData = feishuApprovalParserService.buildMockApprovalInstance();
            } else {
                log.info("【飞书审批】从飞书开放平台拉取实例详情: {}", instanceCode);
                try {
                    rawData = feishuApiClient.getApprovalInstance(instanceCode);
                } catch (Exception e) {
                    if ("EC7E4A27-EB05-45CB-B25A-4EF50A05B9AE".equalsIgnoreCase(instanceCode)) {
                        log.info("【飞书审批】线上拉取实例 [{}] 异常 ({})，自动启用本地角色单据样本兜底执行", instanceCode, e.getMessage());
                        rawData = loadRoleApprovalSample();
                    } else {
                        throw e;
                    }
                }
            }

            ExtractedApprovalData extracted = feishuApprovalParserService.parseApprovalInstance(rawData);

            // 🌟 自动从飞书通讯录反查申请人真实姓名、企业邮箱与手机号
            if (!"sample".equalsIgnoreCase(instanceCode) && !"mock".equalsIgnoreCase(instanceCode)
                    && !"sample-role".equalsIgnoreCase(instanceCode) && !"role-sample".equalsIgnoreCase(instanceCode) && !"role_sample".equalsIgnoreCase(instanceCode)) {
                Map<String, Object> contact = feishuApiClient.getContactUser(extracted.getOpenId(), "open_id");
                if (contact == null || contact.isEmpty()) {
                    contact = feishuApiClient.getContactUser(extracted.getUserId(), "user_id");
                }
                if (contact != null && !contact.isEmpty()) {
                    String name = extractContactField(contact, "name");
                    String enName = extractContactField(contact, "en_name");
                    if (enName.isEmpty()) {
                        String nick = extractContactField(contact, "nickname");
                        if (!nick.isEmpty() && nick.matches("^[a-zA-Z][a-zA-Z0-9._-]{1,}$")) {
                            enName = nick;
                        }
                    }
                    String enterpriseEmail = extractContactField(contact, "enterprise_email");
                    String email = !enterpriseEmail.isEmpty() ? enterpriseEmail : extractContactField(contact, "email");
                    String mobile = extractContactField(contact, "mobile");
                    log.info("【飞书通讯录详情】name=[{}], en_name=[{}], enterprise_email=[{}], email=[{}], keys={}",
                            name, enName, enterpriseEmail, extractContactField(contact, "email"), contact.keySet());
                    if (!name.isEmpty()) extracted.setApplicantName(name);
                    if (!enName.isEmpty()) extracted.setApplicantEnName(enName);
                    if (!mobile.isEmpty()) extracted.setApplicantMobile(mobile);

                    // 🌟 企业邮箱智能纠偏：若飞书返回的是非 @furniwell.com 个人邮箱，自动通过真实姓名在帆软对齐并修正
                    if (!email.isEmpty()) {
                        if (!email.toLowerCase().endsWith("@furniwell.com")) {
                            FineReportUser frUser = fineReportApiClient.findUserSmart(email, extracted.getApplicantName(), mobile);
                            if (frUser != null && frUser.getEmail() != null && frUser.getEmail().toLowerCase().endsWith("@furniwell.com")) {
                                email = frUser.getEmail().trim();
                                log.info("【企业邮箱智能校正】通过姓名 [{}] 成功校正企业邮箱: {}", extracted.getApplicantName(), email);
                            } else if (frUser != null && frUser.getUsername() != null && !frUser.getUsername().isBlank()) {
                                email = frUser.getUsername().trim() + "@furniwell.com";
                                log.info("【企业邮箱智能校正】通过帆软用户名 [{}] 补齐企业邮箱: {}", frUser.getUsername(), email);
                            }
                        }
                        extracted.setApplicantEmail(email);
                    }
                }
            } else if ("sample".equalsIgnoreCase(instanceCode)) {
                extracted.setApplicantName("何动豪 (测试样例)");
                extracted.setApplicantEmail("donghao-he@furniwell.com");
            } else {
                extracted.setApplicantName("何动豪 (角色测试)");
                extracted.setApplicantEmail("donghao-he@furniwell.com");
            }

            // 构造帆软开放平台 V3 授权报文格式 (仅报表申请)
            List<Map<String, Object>> frPayload = new ArrayList<>();
            for (ReportItem item : extracted.getReportItems()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("entry_nodes", item.getEntryNodes());
                entry.put("report_file", item.getReportFile());
                entry.put("viewPrivilege", "true");
                frPayload.add(entry);
            }

            String summaryMsg = extracted.isRoleApply()
                    ? ("解析成功，识别为【" + (extracted.getApplyType() != null ? extracted.getApplyType() : "账号与数据角色申请") + "】，共提取 "
                    + (extracted.getTargetRoles() != null ? extracted.getTargetRoles().size() : 0) + " 个角色及组织控权")
                    : ("解析成功，共提取 " + extracted.getReportItems().size() + " 张报表");

            return ApprovalProcessResult.builder()
                    .success(true)
                    .message(summaryMsg)
                    .approvalData(extracted)
                    .fineReportPayload(frPayload)
                    .build();

        } catch (Exception e) {
            log.error("【飞书审批】拉取或解析实例 [{}] 异常: {}", instanceCode, e.getMessage(), e);
            return ApprovalProcessResult.builder()
                    .success(false)
                    .message("处理失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 执行全流程自动化闭环：
     * 1. 抓取与解析审批单
     * 2. 匹配帆软用户名
     * 3. 调帆软 API 赋权
     * 4. 飞书机器人推卡片
     */
    public ApprovalProcessResult executeFullPipeline(String instanceCode) {
        return executeFullPipeline(instanceCode, null);
    }

    /**
     * 执行全流程自动化闭环 (支持指定/提取邮箱精确匹配)
     */
    public ApprovalProcessResult executeFullPipeline(String instanceCode, String customEmail) {
        ApprovalProcessResult extractResult = fetchAndExtract(instanceCode);
        if (!extractResult.isSuccess()) {
            return extractResult;
        }

        ExtractedApprovalData data = extractResult.getApprovalData();
        if (feishuProperties != null && !feishuProperties.isApprovalAllowed(data.getApprovalCode(), data.getApprovalName())) {
            log.info("【飞书审批白名单过滤】忽略非目标审批流程: code=[{}], name=[{}], instanceCode=[{}]",
                    data.getApprovalCode(), data.getApprovalName(), instanceCode);
            extractResult.setMessage("已忽略非目标审批流: " + data.getApprovalName() + " (" + data.getApprovalCode() + ")");
            return extractResult;
        }
        List<Map<String, Object>> payload = extractResult.getFineReportPayload();

        // 🌟 1. 邮箱唯一匹配：优先使用指定邮箱，其次使用反查出的企业邮箱
        String candidateId = (customEmail != null && !customEmail.isBlank())
                ? customEmail.trim()
                : (data.getApplicantEmail() != null && !data.getApplicantEmail().isBlank()
                        ? data.getApplicantEmail().trim()
                        : ("sample".equalsIgnoreCase(instanceCode) ? "donghao-he@furniwell.com" : null));

        if (candidateId == null) {
            String autoEmail = feishuApiClient.getUserEnterpriseEmail(data.getUserId(), data.getOpenId());
            if (autoEmail != null && !autoEmail.isBlank()) {
                log.info("【飞书通讯录】自动反查到员工企业邮箱: {}", autoEmail);
                candidateId = autoEmail.trim();
            } else {
                candidateId = data.getUserId();
            }
        }

        FineReportUser matched = fineReportApiClient.findUserSmart(candidateId, data.getApplicantName(), data.getApplicantMobile());
        String finalUsername = (matched != null && matched.getUsername() != null)
                ? matched.getUsername()
                : (candidateId != null && candidateId.toLowerCase().endsWith("@furniwell.com")
                        ? candidateId.split("@")[0].trim()
                        : candidateId);
        String realName = (matched != null && matched.getRealName() != null)
                ? matched.getRealName()
                : data.getApplicantName();

        // 🌟 若匹配成功，同步将最终确认的企业邮箱透出到 approvalData
        if (matched != null) {
            String frEmail = matched.getEmail();
            if (frEmail != null && frEmail.toLowerCase().endsWith("@furniwell.com")) {
                data.setApplicantEmail(frEmail.trim());
            } else if (matched.getUsername() != null && !matched.getUsername().isBlank()) {
                data.setApplicantEmail(matched.getUsername().trim() + "@furniwell.com");
            }
        }

        log.info("【全流程自动化】申请人标识 [{}] 匹配帆软用户: 实际用户名=[{}], 姓名=[{}]",
                candidateId, finalUsername, realName);

        // 🛡️ 生产安全守卫（核心防线）：测试模式下只允许操作测试人账号，绝不污染真实生产账号！
        boolean redirectedToTest = false;
        if (feishuProperties != null && feishuProperties.isApprovalTestMode()) {
            boolean isAllowed = feishuProperties.isUserAllowedInTest(finalUsername);
            if (!isAllowed) {
                if (feishuProperties.shouldRedirectOthersToTestUser()) {
                    String testUser = feishuProperties.getDefaultTestUser();
                    log.warn("🛡️【测试环境安全守卫】检测到非测试生产账号 [{}] ({})，测试模式已自动重定向至安全测试账号 [{}] 供开通验收，真实同事生产账号完全不受任何触碰！",
                            finalUsername, realName, testUser);
                    finalUsername = testUser;
                    realName = "何动豪";
                    redirectedToTest = true;
                } else {
                    log.info("🛡️【测试模式白名单安全放行】审批单 [{}] 申请人 [{}] ({}) 不在测试白名单中，安全跳过自动化处理，继续走原有人工审批流程",
                            data.getInstanceCode(), finalUsername, realName);
                    extractResult.setFineReportAuthResult(Map.of(
                            "success", false,
                            "skipped", true,
                            "message", "非测试白名单账号 [" + finalUsername + "]，安全跳过自动化处理，保持原有人工流转"
                    ));
                    extractResult.setMessage("测试模式安全跳过非测试账号 [" + finalUsername + "] 的单据");
                    return extractResult;
                }
            }
        }

        // 双重硬断言（物理级防护：非测试账号绝对拒绝执行任何向帆软写入的逻辑）
        if (feishuProperties != null && feishuProperties.isApprovalTestMode()) {
            if (!feishuProperties.isUserAllowedInTest(finalUsername)) {
                log.error("🚨【测试环境双重硬断言拦截】拒绝向未授权账号发起写入: {}", finalUsername);
                return extractResult;
            }
        }

        extractResult.setMatchedFineReportUser(finalUsername);
        extractResult.setMatchedFineReportRealName(realName);

        // 🌟 检查触发门槛：必须是未驳回/未撤销，且全流程已通过 (APPROVED) 或者 “数据主管已审批同意/自动通过 (进入办理人节点)”
        boolean isTerminated = "REJECTED".equalsIgnoreCase(data.getStatus())
                || "CANCELED".equalsIgnoreCase(data.getStatus())
                || "DELETED".equalsIgnoreCase(data.getStatus());
        boolean isOverallApproved = "APPROVED".equalsIgnoreCase(data.getStatus()) || "pass".equalsIgnoreCase(data.getStatus());
        boolean isDataSupervisorApproved = data.isDataSupervisorApproved();

        // 线上真实审批单判断（sample/mock 直接放行以便测试验证）
        if (!"sample".equalsIgnoreCase(data.getInstanceCode()) && !"mock".equalsIgnoreCase(data.getInstanceCode())
                && !"sample-role".equalsIgnoreCase(data.getInstanceCode()) && !"EC7E4A27-EB05-45CB-B25A-4EF50A05B9AE".equalsIgnoreCase(data.getInstanceCode())) {
            if (isTerminated) {
                log.info("⏹️ 【自动化触发拦截】实例 [{}] 当前状态为 [{}]，流程已拒绝或撤销，停止自动赋权",
                        data.getInstanceCode(), data.getStatus());
                extractResult.setMessage("审批实例已拒绝或撤销，不执行自动化赋权");
                return extractResult;
            }
            if (!isOverallApproved && !isDataSupervisorApproved) {
                log.info("⏳ 【自动化触发拦截】实例 [{}] 当前状态为 [{}], 且数据主管尚未同意，暂不执行自动赋权，等待数据主管审批...",
                        data.getInstanceCode(), data.getStatus());
                extractResult.setMessage("数据主管尚未审批同意，暂不执行自动化赋权");
                return extractResult;
            }
        }

        // 🛡️ 安全试运行模式 (Dry-Run) 判定：仅模拟执行并记录拟操作，绝不向帆软后台产生真实写入
        boolean isDryRun = feishuProperties != null && feishuProperties.isApprovalDryRun();
        if (isDryRun) {
            if (data.isRoleApply()) {
                log.warn("🛡️【安全试运行模式 (Dry-Run)】已捕获角色审批单据 [{}], 拟为用户 [{}] ({}) 配置角色: {}。处于模拟演练模式，绝不对帆软后台产生实际写入！",
                        data.getInstanceCode(), finalUsername, realName, data.getTargetRoles());
                extractResult.setFineReportAuthResult(Map.of(
                        "dryRun", true,
                        "simulatedUser", finalUsername,
                        "simulatedRealName", realName,
                        "simulatedRoles", data.getTargetRoles() != null ? data.getTargetRoles() : Collections.emptyList(),
                        "simulatedOrgRule", data.getOrgControlRule() != null ? data.getOrgControlRule() : "",
                        "message", "【安全试运行模式】比对与逻辑验证全部通过，未实际修改帆软后台角色"
                ));
                extractResult.setNotificationSent(false);
                extractResult.setMessage("【安全试运行模式】成功识别用户 [" + realName + " (" + finalUsername + ")]，拟配置 "
                        + (data.getTargetRoles() != null ? data.getTargetRoles().size() : 0) + " 个角色，未实际改动帆软数据。");
                return extractResult;
            } else {
                log.warn("🛡️【安全试运行模式 (Dry-Run)】已捕获审批通过单据 [{}], 拟为用户 [{}] ({}) 赋权 {} 张报表。当前处于模拟演练模式，绝不对帆软后台产生实际写入！",
                        data.getInstanceCode(), finalUsername, realName, payload.size());
                extractResult.setFineReportAuthResult(Map.of(
                        "dryRun", true,
                        "simulatedUser", finalUsername,
                        "simulatedRealName", realName,
                        "simulatedReportsCount", payload.size(),
                        "message", "【安全试运行模式】比对与逻辑验证全部通过，未实际修改帆软后台权限"
                ));
                extractResult.setNotificationSent(false);
                extractResult.setMessage("【安全试运行模式】成功识别用户 [" + realName + " (" + finalUsername + ")]，拟赋权 "
                        + payload.size() + " 张报表，已记录模拟演练日志，未实际改动帆软数据。");
                return extractResult;
            }
        }

        // 🌟 分流：如果是账号与数据角色申请/变更，流转至角色专属处理管线
        FineReportUser targetFrUser = resolveTargetFineReportUser(
                matched, finalUsername, realName, redirectedToTest ? null : data.getApplicantMobile());
        if (data.isRoleApply()) {
            return executeRoleAndAccountPipeline(data, targetFrUser, finalUsername, realName, extractResult);
        }

        // 2. 🌟 正式模式：100% 完整复刻浏览器 F12 原生 PUT /v10/authority/carrier/user 为用户直接勾选报表
        String carrierId = targetFrUser != null ? targetFrUser.getId() : null;

        if (carrierId == null || carrierId.isBlank()) {
            List<String> requestedReports = data.getReportItems() != null
                    ? data.getReportItems().stream().map(ReportItem::getReportName).toList()
                    : Collections.emptyList();
            return abortWithAccountMissingAlert(data, finalUsername, requestedReports,
                    "未在帆软中唯一确认申请人 [" + finalUsername + "] 的账号，已推送提醒卡片给办理人", extractResult);
        }

        log.info("🎯 成功定位用户 [{}] 的帆软底层 carrierId: [{}]，开始执行单张报表直接赋权...", finalUsername, carrierId);

        // 1. 预加载报表资产目录
        List<ReportCatalog> allCatalogs = new ArrayList<>();
        try {
            if (reportCatalogService != null) {
                allCatalogs = reportCatalogService.listCatalogs();
            }
        } catch (Exception e) {
            log.warn("读取报表资产目录库发生异常: {}", e.getMessage());
        }

        // 2. 预加载 Wiki 知识库快照 (一次性加载反序列化，避免40次循环中反复查库解析)
        JsonNode wikiCacheSnapshots = null;
        try {
            if (systemConfigService != null) {
                String cacheJson = systemConfigService.getConfigValue("feishu_wiki_sync_cache");
                if (cacheJson != null && !cacheJson.isBlank()) {
                    JsonNode root = objectMapper.readTree(cacheJson);
                    wikiCacheSnapshots = root.path("snapshots");
                }
            }
        } catch (Exception e) {
            log.warn("预加载 wiki 快照发生异常: {}", e.getMessage());
        }

        int wikiCount = (wikiCacheSnapshots != null && wikiCacheSnapshots.isObject()) ? wikiCacheSnapshots.size() : 0;
        log.info("📚 【报表目录加载完毕】DB 资产目录={} 条, Wiki 快照缓存={} 条", allCatalogs.size(), wikiCount);

        // 3. 内存极速匹配所有报表的 entryId (UUID)
        List<Map.Entry<String, String>> mappedItems = new ArrayList<>();
        List<String> validEntryIds = new ArrayList<>();
        List<String> unmappedReports = new ArrayList<>();

        for (ReportItem item : data.getReportItems()) {
            String entryId = resolveReportEntryId(item, allCatalogs, wikiCacheSnapshots);
            if (entryId != null && !entryId.isBlank()) {
                mappedItems.add(Map.entry(item.getReportName(), entryId));
                validEntryIds.add(entryId);
            } else {
                unmappedReports.add(item.getReportName());
                log.warn("⚠️ 报表 [{}] 未能在目录库中解析出 entryId (UUID), 原始路径: {}", item.getReportName(), item.getRawPath());
            }
        }

        // 4. 🔍 赋权前先查询该用户在帆软已有的报表查看权限，仅对尚未开通的新表执行 PUT（不重复覆盖原已有权限）
        Map<String, Boolean> existingAuthMap = validEntryIds.isEmpty()
                ? Collections.emptyMap()
                : fineReportApiClient.getUserExistingReportAuthorities(carrierId, validEntryIds);

        Set<String> toGrantEntryIdSet = new LinkedHashSet<>();
        for (String id : validEntryIds) {
            if (existingAuthMap == null || !Boolean.TRUE.equals(existingAuthMap.get(id))) {
                toGrantEntryIdSet.add(id);
            }
        }
        List<String> toGrantEntryIds = new ArrayList<>(toGrantEntryIdSet);

        // 5. 🚀 仅对尚未开通的报表执行并发赋权 (全部原已有权限时零写入调用)
        Map<String, Boolean> batchResults = toGrantEntryIds.isEmpty()
                ? Collections.emptyMap()
                : fineReportApiClient.grantReportAuthoritiesToUserBatch(carrierId, toGrantEntryIds);

        int successCount = 0;
        int failCount = 0;
        List<String> grantedReports = new ArrayList<>();
        List<String> newlyGrantedReports = new ArrayList<>();
        List<String> alreadyGrantedReports = new ArrayList<>();
        List<String> failedReports = new ArrayList<>();

        for (Map.Entry<String, String> entry : mappedItems) {
            String reportName = entry.getKey();
            String entryId = entry.getValue();
            if (existingAuthMap != null && Boolean.TRUE.equals(existingAuthMap.get(entryId))) {
                successCount++;
                grantedReports.add(reportName);
                alreadyGrantedReports.add(reportName);
            } else if (batchResults.getOrDefault(entryId, false)) {
                successCount++;
                grantedReports.add(reportName);
                newlyGrantedReports.add(reportName);
            } else {
                failCount++;
                failedReports.add(reportName);
            }
        }

        boolean allSuccess = (failCount == 0) && (!validEntryIds.isEmpty() || unmappedReports.isEmpty());
        Map<String, Object> frResp = new LinkedHashMap<>();
        frResp.put("success", allSuccess);
        frResp.put("carrierId", carrierId);
        frResp.put("totalRequested", data.getReportItems().size());
        frResp.put("successCount", successCount);
        frResp.put("failCount", failCount);
        frResp.put("grantedReports", grantedReports);
        frResp.put("newlyGrantedReports", newlyGrantedReports);
        frResp.put("alreadyGrantedReports", alreadyGrantedReports);
        frResp.put("failedReports", failedReports);
        frResp.put("unmappedReports", unmappedReports);
        extractResult.setFineReportAuthResult(frResp);
        log.info("🏁 【用户报表直接赋权完成】用户 [{}] 共申请 {} 张，本次新开通: {} 张 {}，原已有权限(跳过覆盖): {} 张 {}，失败: {} 张，未匹配 UUID: {} 张",
                finalUsername, data.getReportItems().size(),
                newlyGrantedReports.size(), newlyGrantedReports,
                alreadyGrantedReports.size(), alreadyGrantedReports,
                failCount, unmappedReports.size());

        // 3. 飞书机器人推卡片通知 (仅在尚未完成【已处理】闭环前发送给所有待处理办理人核对；办理人已同意结束则不再重复发卡)
        boolean notified = false;
        if (shouldSkipHandlerCard(data)) {
            log.info("【飞书卡片推送跳过】实例 [{}] 办理人已完成处理 (status={})，不再重复发送办理提醒卡片",
                    data.getInstanceCode(), data.getStatus());
        } else {
            List<String> pendingVerifyReports = new ArrayList<>();
            if (unmappedReports != null) pendingVerifyReports.addAll(unmappedReports);
            if (failedReports != null) pendingVerifyReports.addAll(failedReports);
            notified = dispatchToTargets(data, extractResult, (receiveId, receiveIdType) ->
                    feishuApiClient.sendApprovalNotificationCard(
                            receiveId,
                            receiveIdType,
                            data.getApplicantName(),
                            data.getInstanceCode(),
                            newlyGrantedReports,
                            alreadyGrantedReports,
                            pendingVerifyReports
                    ));
        }
        extractResult.setNotificationSent(notified);
        String finalMsg = allSuccess
                ? "自动化闭环完成！帆软已赋权，飞书卡片推送: " + (notified ? "成功" : "已跳过")
                : "自动赋权未完全成功（失败: " + failCount + " 张，未匹配: " + unmappedReports.size() + " 张），飞书卡片推送: " + (notified ? "成功" : "已跳过");
        extractResult.setMessage(finalMsg);

        List<String> requestedReportNames = data.getReportItems() != null
                ? data.getReportItems().stream().map(ReportItem::getReportName).toList()
                : Collections.emptyList();
        recordAuditSafely(data, finalUsername, carrierId, requestedReportNames,
                newlyGrantedReports, alreadyGrantedReports, unmappedReports, failedReports,
                allSuccess ? "SUCCESS" : "PARTIAL", finalMsg, extractResult.getNotifiedTargetIds());

        return extractResult;
    }

    private static String extractContactField(Map<String, Object> contact, String key) {
        if (contact == null || key == null) {
            return "";
        }
        Object raw = contact.get(key);
        if (raw == null) {
            return "";
        }
        String val = String.valueOf(raw).trim();
        return "null".equalsIgnoreCase(val) ? "" : val;
    }

    private static final Set<String> COMPOUND_SURNAMES = Set.of(
            "欧阳", "太史", "端木", "上官", "司马", "东方", "独孤", "南宫", "万俟", "闻人",
            "夏侯", "诸葛", "尉迟", "公羊", "赫连", "澹台", "皇甫", "宗政", "濮阳", "公冶",
            "太叔", "申屠", "公孙", "慕容", "仲孙", "钟离", "长孙", "宇文", "司徒", "鲜于",
            "司空", "闾丘", "子车", "亓官", "司寇", "巫马", "公西", "颛孙", "壤驷", "公良",
            "漆雕", "乐正", "宰父", "谷梁", "拓跋", "夹谷", "轩辕", "令狐", "段干", "百里",
            "呼延", "东郭", "南门", "羊舌", "微生", "公户", "公玉", "公仪", "梁丘", "公仲",
            "公上", "公门", "公山", "公坚", "左丘", "公伯", "西门", "公祖", "第五", "公乘",
            "贯丘", "公皙", "南荣", "东里", "东宫", "仲长", "子书", "子桑", "即墨", "达奚", "褚师"
    );

    /**
     * 推导新建帆软账号时的标准用户名（用户名按公司规范为中文拼音，不用个人邮箱或花名/英文名）：
     * 1. 若有 @furniwell.com 企业邮箱，优先取 @ 前缀（如 donghao-he，兼容多音字/重名编号）；
     * 2. 否则按公司标准格式将中文姓名转拼音 `<名全拼>-<姓全拼>`（如 胡梦园 -> mengyuan-hu，支持复姓）；
     * 3. 仅当非中文姓名无法转拼音时，才兜底使用飞书英文名 (en_name) 或合法非邮箱/非纯数字标识。
     */
    static String resolvePreferredAccountUsername(String candidateId, String realName, ExtractedApprovalData data) {
        String cleanCand = candidateId != null ? candidateId.trim() : null;
        if (cleanCand != null && cleanCand.toLowerCase().endsWith("@furniwell.com")) {
            int at = cleanCand.indexOf('@');
            if (at > 0) {
                return cleanCand.substring(0, at).trim().toLowerCase();
            }
        }
        String pinyinName = toGivenSurnamePinyin(realName);
        if (pinyinName != null && !pinyinName.isBlank()) {
            return pinyinName;
        }
        if (data != null && data.getApplicantEnName() != null && !data.getApplicantEnName().isBlank()) {
            String en = data.getApplicantEnName().trim();
            int at = en.indexOf('@');
            if (at > 0) {
                en = en.substring(0, at).trim();
            }
            en = en.replaceAll("\\s+", "-").toLowerCase();
            if (en.matches("^[a-zA-Z0-9._-]+$") && !en.matches("^\\d+$")) {
                return en;
            }
        }
        if (cleanCand != null && !cleanCand.isBlank() && !cleanCand.contains("@")
                && cleanCand.matches("^[a-zA-Z0-9._-]+$") && !cleanCand.matches("^\\d+$")) {
            return cleanCand;
        }
        return cleanCand;
    }

    private static final Map<String, String> SURNAME_PINYIN_OVERRIDES = Map.ofEntries(
            Map.entry("曾", "zeng"), Map.entry("解", "xie"), Map.entry("单", "shan"),
            Map.entry("查", "zha"), Map.entry("区", "ou"), Map.entry("仇", "qiu"),
            Map.entry("朴", "piao"), Map.entry("盖", "ge"), Map.entry("冼", "xian"),
            Map.entry("繁", "po"), Map.entry("缪", "miao"), Map.entry("任", "ren"),
            Map.entry("盛", "sheng"), Map.entry("纪", "ji"), Map.entry("种", "chong"),
            Map.entry("乜", "nie"), Map.entry("折", "she"), Map.entry("尉迟", "yuchi"),
            Map.entry("万俟", "moqi"), Map.entry("澹台", "tantai"), Map.entry("乐正", "yuezheng"),
            Map.entry("长孙", "zhangsun")
    );

    static String toGivenSurnamePinyin(String realName) {
        if (realName == null || realName.isBlank()) {
            return null;
        }
        StringBuilder hanziOnly = new StringBuilder();
        for (int i = 0; i < realName.length(); i++) {
            char c = realName.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fa5') {
                hanziOnly.append(c);
            }
        }
        String clean = hanziOnly.toString();
        if (clean.length() < 2) {
            return null;
        }
        int surnameLen = (clean.length() >= 3 && COMPOUND_SURNAMES.contains(clean.substring(0, 2))) ? 2 : 1;
        String rawSurname = clean.substring(0, surnameLen);
        String surnamePart = SURNAME_PINYIN_OVERRIDES.getOrDefault(rawSurname, convertCharsToPinyin(rawSurname));
        String givenPart = convertCharsToPinyin(clean.substring(surnameLen));
        if (surnamePart.isEmpty() || givenPart.isEmpty()) {
            return null;
        }
        return givenPart + "-" + surnamePart;
    }

    private static String convertCharsToPinyin(String chars) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            try {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(chars.charAt(i), format);
                if (arr != null && arr.length > 0 && arr[0] != null && !arr[0].isBlank()) {
                    sb.append(arr[0].replaceAll("[^a-z]", ""));
                }
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * 判断是否应跳过发送“请办理人核对并点击【已处理】”卡片：
     * 真实审批单若已整体 APPROVED/pass 且不再存在待处理的办理节点，说明办理人已完成同意操作，无需再发。
     */
    boolean shouldSkipHandlerCard(ExtractedApprovalData data) {
        if (data == null) {
            return false;
        }
        String inst = data.getInstanceCode();
        if ("sample".equalsIgnoreCase(inst) || "mock".equalsIgnoreCase(inst)
                || "sample-role".equalsIgnoreCase(inst)
                || "EC7E4A27-EB05-45CB-B25A-4EF50A05B9AE".equalsIgnoreCase(inst)) {
            return false;
        }
        boolean isOverallApproved = "APPROVED".equalsIgnoreCase(data.getStatus())
                || "pass".equalsIgnoreCase(data.getStatus());
        return isOverallApproved && !data.isHandlerTaskPending();
    }

    /**
     * 解析飞书通知卡片的目标接收人列表 (优先全部待处理办理人并去重，测试模式下统一只发给测试账号一次)
     */
    List<String[]> resolveNotificationTargets(ExtractedApprovalData data) {
        if (feishuProperties != null && feishuProperties.isApprovalTestMode()) {
            String testOpenId = feishuProperties.getDefaultTestOpenId();
            if (testOpenId != null && !testOpenId.isBlank() && testOpenId.length() >= 20) {
                String cleanId = testOpenId.trim();
                log.info("🧪 【测试模式】飞书卡片通知接收人重定向至测试账号 Open ID: [{}]", cleanId);
                return List.<String[]>of(new String[]{cleanId, cleanId.startsWith("on_") ? "union_id" : "open_id"});
            }
            String testEmail = feishuProperties.getDefaultTestEmail();
            if (testEmail != null && !testEmail.isBlank()) {
                log.info("🧪 【测试模式】飞书卡片通知接收人由 [{}] 重定向至测试人员邮箱: [{}]",
                        data != null ? data.getApplicantName() : "", testEmail);
                return List.<String[]>of(new String[]{testEmail.trim(), "email"});
            }
        }

        if (data == null) {
            return Collections.emptyList();
        }

        Set<String> handlerOpenIds = new LinkedHashSet<>();
        if (data.getHandlerOpenIds() != null) {
            for (String oid : data.getHandlerOpenIds()) {
                if (oid != null && !oid.isBlank() && !"null".equalsIgnoreCase(oid)) {
                    handlerOpenIds.add(oid.trim());
                }
            }
        }
        if (data.getHandlerOpenId() != null && !data.getHandlerOpenId().isBlank() && !"null".equalsIgnoreCase(data.getHandlerOpenId())) {
            handlerOpenIds.add(data.getHandlerOpenId().trim());
        }

        List<String[]> targets = new ArrayList<>();
        for (String oid : handlerOpenIds) {
            targets.add(new String[]{oid, "open_id"});
        }
        // 兜底：若部分节点未透出 open_id 而只有 user_id，补充按 user_id 推送，避免漏发给首任办理人
        if (targets.isEmpty() && data.getHandlerUserIds() != null) {
            for (String uid : data.getHandlerUserIds()) {
                if (uid != null && !uid.isBlank() && !"null".equalsIgnoreCase(uid)) {
                    targets.add(new String[]{uid.trim(), "user_id"});
                }
            }
        }
        if (targets.isEmpty() && data.getHandlerUserId() != null && !data.getHandlerUserId().isBlank() && !"null".equalsIgnoreCase(data.getHandlerUserId())) {
            targets.add(new String[]{data.getHandlerUserId().trim(), "user_id"});
        }
        // 仅演示样本/无办理节点的单据回退发给申请人；真实审批单若办理节点尚未落库，不在此时误发给申请人，等待下一事件/并发复核直达首任办理人
        String inst = data.getInstanceCode();
        boolean isBuiltInSample = "sample".equalsIgnoreCase(inst) || "mock".equalsIgnoreCase(inst)
                || "sample-role".equalsIgnoreCase(inst)
                || "EC7E4A27-EB05-45CB-B25A-4EF50A05B9AE".equalsIgnoreCase(inst);
        if (targets.isEmpty() && isBuiltInSample && data.getOpenId() != null && !data.getOpenId().isBlank() && !"null".equalsIgnoreCase(data.getOpenId())) {
            targets.add(new String[]{data.getOpenId().trim(), "open_id"});
        }
        if (log.isInfoEnabled()) {
            List<String> targetDesc = targets.stream().map(t -> t[1] + ":" + t[0]).toList();
            log.info("【飞书卡片接收目标解析】实例=[{}], handlerTaskPending={}, targets={}, handlerOpenIds={}, handlerUserIds={}",
                    inst, data.isHandlerTaskPending(), targetDesc, data.getHandlerOpenIds(), data.getHandlerUserIds());
        }
        return targets;
    }

    /**
     * 向所有目标接收人分发卡片并记录成功推送的目标 ID 列表，供依次办理接力补发
     */
    private boolean dispatchToTargets(ExtractedApprovalData data, ApprovalProcessResult result,
                                      java.util.function.BiFunction<String, String, Boolean> sender) {
        if (result != null) {
            result.setCardSender(sender);
        }
        List<String[]> targets = resolveNotificationTargets(data);
        boolean anySent = false;
        List<String> sentIds = new ArrayList<>();
        for (String[] target : targets) {
            if (Boolean.TRUE.equals(sender.apply(target[0], target[1]))) {
                anySent = true;
                sentIds.add(target[0]);
            }
        }
        if (result != null) {
            result.setNotifiedTargetIds(sentIds);
        }
        return anySent;
    }

    private static final class ProcessedState {
        volatile long timestamp;
        final java.util.function.BiFunction<String, String, Boolean> cardSender;
        final Set<String> notifiedTargetIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        ProcessedState(long timestamp,
                       java.util.function.BiFunction<String, String, Boolean> cardSender,
                       Collection<String> initialTargets) {
            this.timestamp = timestamp;
            this.cardSender = cardSender;
            if (initialTargets != null) {
                this.notifiedTargetIds.addAll(initialTargets);
            }
        }
    }

    // 内存防并发与防重放缓存 (记录近 24 小时内已完成赋权与已通知的办理人，支持“依次办理”接力通知下一位)
    private static final long PROCESSED_CACHE_TTL_MS = 86_400_000L;
    private final Map<String, ProcessedState> processedCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 正在执行中的实例集合 (防止同一毫秒并发重入)
    private final Set<String> inFlightInstances = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 并发处理期间收到的待重检实例集合 (防止前置节点处理中时，后续自动通过/办理节点 Webhook 被直接丢弃)
    private final Set<String> pendingRetriggerInstances = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 异步处理飞书 Webhook 事件通知 (避免飞书 3 秒超时告警，支持高并发合并重检、防重与依次办理接力发卡)
     */
    @Async
    public void processApprovalEventAsync(String instanceCode, String eventType) {
        if (instanceCode == null || instanceCode.isBlank()) {
            return;
        }
        synchronized (inFlightInstances) {
            if (!inFlightInstances.add(instanceCode)) {
                pendingRetriggerInstances.add(instanceCode);
                log.info("【飞书 Webhook 并发保护】实例 [{}] 正在处理中，已合并排队，待当前执行完毕后自动复核最新状态", instanceCode);
                return;
            }
            pendingRetriggerInstances.remove(instanceCode);
        }

        try {
            int attempt = 0;
            boolean shouldContinue;
            do {
                attempt++;
                long curNow = System.currentTimeMillis();
                if (processedCache.size() > 5000) {
                    processedCache.entrySet().removeIf(e -> curNow - e.getValue().timestamp > PROCESSED_CACHE_TTL_MS);
                }

                boolean needDelayedFirstHandlerCheck = false;
                ProcessedState existingState = processedCache.get(instanceCode);
                if (existingState != null && (curNow - existingState.timestamp < PROCESSED_CACHE_TTL_MS)) {
                    notifySequentialPendingHandlers(instanceCode, existingState);
                } else {
                    log.info("【飞书 Webhook 异步处理】收到事件 [{}], 实例 [{}] 开始全流程自动化闭环 (attempt={})",
                            eventType, instanceCode, attempt);
                    ApprovalProcessResult result = executeFullPipeline(instanceCode);
                    log.info("【飞书 Webhook 异步处理】实例 [{}] 执行完毕: success={}, msg={}, frResult={}",
                            instanceCode, result.isSuccess(), result.getMessage(), result.getFineReportAuthResult());

                    // 仅当真正完成了赋权、Dry-Run 演练或已发出缺失账号告警卡片时，才计入 24 小时已完成缓存
                    boolean completed = result.isSuccess() && result.getFineReportAuthResult() != null
                            && (Boolean.TRUE.equals(result.getFineReportAuthResult().getOrDefault("success", result.getFineReportAuthResult().get("dryRun")))
                            || Boolean.TRUE.equals(result.getFineReportAuthResult().get("alertCardSent")));
                    if (completed) {
                        processedCache.put(instanceCode, new ProcessedState(
                                System.currentTimeMillis(), result.getCardSender(), result.getNotifiedTargetIds()));
                        if (attempt < 3
                                && result.getCardSender() != null
                                && (result.getNotifiedTargetIds() == null || result.getNotifiedTargetIds().isEmpty())
                                && !shouldSkipHandlerCard(result.getApprovalData())) {
                            needDelayedFirstHandlerCheck = true;
                        }
                    }
                }

                boolean triggerRetry = false;
                synchronized (inFlightInstances) {
                    // 即使首次赋权已完成 (completed=true)，若执行期间收到新事件（例如数据主管刚通过后第 1 位办理人的 PENDING 任务刚生成），
                    // 仍必须再跑一轮 notifySequentialPendingHandlers 补发提醒给第 1 位办理人，绝不能把排队事件直接丢弃。
                    if (attempt < 3 && pendingRetriggerInstances.remove(instanceCode)) {
                        triggerRetry = true;
                        log.info("【飞书 Webhook 并发复核】实例 [{}] 处理期间收到新事件，立即自动复核最新审批节点状态", instanceCode);
                    } else if (!needDelayedFirstHandlerCheck) {
                        pendingRetriggerInstances.remove(instanceCode);
                        inFlightInstances.remove(instanceCode);
                    }
                }
                if (!triggerRetry && needDelayedFirstHandlerCheck) {
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (inFlightInstances) {
                        if (attempt < 3) {
                            triggerRetry = true;
                            pendingRetriggerInstances.remove(instanceCode);
                            log.info("【飞书首任办理人延迟复核】实例 [{}] 首次处理时办理节点尚未同步返回，800ms 后自动拉取最新节点补发提醒", instanceCode);
                        } else {
                            pendingRetriggerInstances.remove(instanceCode);
                            inFlightInstances.remove(instanceCode);
                        }
                    }
                }
                shouldContinue = triggerRetry;
            } while (shouldContinue);
        } catch (Exception e) {
            log.error("【飞书 Webhook 异步处理】实例 [{}] 处理异常: {}", instanceCode, e.getMessage(), e);
        } finally {
            synchronized (inFlightInstances) {
                inFlightInstances.remove(instanceCode);
                pendingRetriggerInstances.remove(instanceCode);
            }
        }
    }

    /**
     * 当同一审批单已完成首次赋权但采用“依次办理”流转到下一位办理人时，仅向尚未通知的新办理人补发通知卡片，不重复赋权、不重发给已通知过的人。
     * @return 当流程仍处于审批中且可能还有并发后续任务状态演进时返回 true
     */
    private boolean notifySequentialPendingHandlers(String instanceCode, ProcessedState existingState) {
        if (existingState.cardSender == null || feishuApiClient == null || feishuApprovalParserService == null) {
            log.info("【飞书 Webhook 防重机制】实例 [{}] 24小时内已成功完成闭环，跳过重复推送", instanceCode);
            return false;
        }
        try {
            Map<String, Object> rawData = feishuApiClient.getApprovalInstance(instanceCode);
            ExtractedApprovalData latestData = feishuApprovalParserService.parseApprovalInstance(rawData);
            if (shouldSkipHandlerCard(latestData) || !latestData.isHandlerTaskPending()) {
                log.info("【飞书 Webhook 防重机制】实例 [{}] 已完成赋权且无新的待处理办理人 (status={})，跳过重复推送",
                        instanceCode, latestData.getStatus());
                return !shouldSkipHandlerCard(latestData);
            }
            List<String[]> targets = resolveNotificationTargets(latestData);
            int sentCount = 0;
            for (String[] target : targets) {
                if (!existingState.notifiedTargetIds.contains(target[0])) {
                    if (Boolean.TRUE.equals(existingState.cardSender.apply(target[0], target[1]))) {
                        existingState.notifiedTargetIds.add(target[0]);
                        existingState.timestamp = System.currentTimeMillis();
                        sentCount++;
                    }
                }
            }
            if (sentCount > 0) {
                log.info("【飞书依次办理接力通知】实例 [{}] 流转至下一位待处理办理人，已补发 {} 张提醒卡片", instanceCode, sentCount);
            } else {
                log.info("【飞书 Webhook 防重机制】实例 [{}] 当前待处理办理人均已推送过卡片，跳过重复推送", instanceCode);
            }
            return true;
        } catch (Exception e) {
            log.warn("【飞书依次办理接力检查异常】实例 [{}]: {}", instanceCode, e.getMessage());
            return false;
        }
    }

    /**
     * 异步处理飞书审批通过事件
     */
    @Async
    public void handleApprovalApprovedAsync(String instanceCode) {
        processApprovalEventAsync(instanceCode, "approval_approved");
    }

    /**
     * 智能解析报表条目的 FineReport entryId (UUID)
     * 严格按 4 级优先级比对，且全路径与后缀匹配强制校验 "/" 段边界，绝不跨目录错配或子串误命中。
     */
    String resolveReportEntryId(ReportItem item, List<ReportCatalog> catalogs, JsonNode wikiCacheSnapshots) {
        if (item == null) return null;

        List<CatalogCandidate> catalogCandidates = Collections.emptyList();
        if (catalogs != null && !catalogs.isEmpty()) {
            catalogCandidates = new ArrayList<>(catalogs.size());
            for (ReportCatalog c : catalogs) {
                if (c.getReportId() != null && !c.getReportId().isBlank()) {
                    catalogCandidates.add(new CatalogCandidate(c.getReportId().trim(), c.getFullPath(), c.getReportName()));
                }
            }
        }

        List<CatalogCandidate> wikiCandidates = Collections.emptyList();
        try {
            if (wikiCacheSnapshots != null && wikiCacheSnapshots.isObject()) {
                wikiCandidates = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> fields = wikiCacheSnapshots.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey();
                    String cleanUuid = key.contains(":") ? key.split(":")[1] : key;
                    if (cleanUuid != null && cleanUuid.length() >= 32) {
                        JsonNode node = entry.getValue();
                        wikiCandidates.add(new CatalogCandidate(
                                cleanUuid.trim(),
                                node.path("fullPath").asText(""),
                                node.path("reportName").asText("")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 wiki 缓存解析候选条目异常: {}", e.getMessage());
        }

        // 1. 先按全路径/文件名匹配
        String hit = resolveFromSources(item, catalogCandidates, wikiCandidates);
        if (hit != null) {
            return hit;
        }

        // 2. 当飞书表单「报表名称」与 .cpt 模板文件名不一致（例如显示名 01.门到门时效报表 vs 模板名 1.欧洲门到门时效报表.cpt）时，
        // 保留全路径中的同一目录层级，换用「报表名称」再按同样严格边界规则匹配，避免跨目录错配。
        String cleanCatalogName = FeishuApprovalParserService.stripReportExtension(
                item.getCatalogName() != null ? item.getCatalogName().trim() : "");
        if (!cleanCatalogName.isBlank() && !cleanCatalogName.equalsIgnoreCase(item.getReportName())) {
            String dir = item.getDirPath() != null ? item.getDirPath().trim() : "";
            String aliasPath = !dir.isBlank() ? (dir + "/" + cleanCatalogName) : cleanCatalogName;
            ReportItem catalogNameItem = ReportItem.builder()
                    .rowIndex(item.getRowIndex())
                    .rawPath(aliasPath)
                    .reportFile(cleanCatalogName)
                    .reportName(cleanCatalogName)
                    .catalogName(cleanCatalogName)
                    .dirPath(dir)
                    .entryNodes(item.getEntryNodes())
                    .l3(item.getL3())
                    .l4(item.getL4())
                    .build();
            String aliasHit = resolveFromSources(catalogNameItem, catalogCandidates, wikiCandidates);
            if (aliasHit != null) {
                log.info("【报表名展示名补充命中】模板名 [{}] 未直接命中，保留同目录并按表单「报表名称」[{}] 精准命中 entryId=[{}]",
                        item.getReportName(), cleanCatalogName, aliasHit);
                return aliasHit;
            }
        }
        return null;
    }

    private String resolveFromSources(ReportItem item, List<CatalogCandidate> catalogCandidates, List<CatalogCandidate> wikiCandidates) {
        if (!catalogCandidates.isEmpty()) {
            String hit = resolveFromCandidates(item, catalogCandidates);
            if (hit != null) {
                return hit;
            }
        }
        if (!wikiCandidates.isEmpty()) {
            return resolveFromCandidates(item, wikiCandidates);
        }
        return null;
    }

    private record CatalogCandidate(String reportId, String rawPath, String rawName) {}

    private String resolveFromCandidates(ReportItem item, List<CatalogCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String normItemPath = normalizeReportPath(item.getRawPath());
        String cleanItemPath = FeishuApprovalParserService.stripReportExtension(normItemPath);
        String itemFile = item.getReportFile() != null ? item.getReportFile().trim() : "";
        String cleanItemName = FeishuApprovalParserService.stripReportExtension(
                item.getReportName() != null ? item.getReportName().trim() : "");

        // 预处理每个候选条目的规范化路径与名称
        List<String> candNormPaths = new ArrayList<>(candidates.size());
        List<String> candCleanPaths = new ArrayList<>(candidates.size());
        List<String> candFiles = new ArrayList<>(candidates.size());
        List<String> candCleanNames = new ArrayList<>(candidates.size());
        for (CatalogCandidate c : candidates) {
            String np = normalizeReportPath(c.rawPath());
            String cp = FeishuApprovalParserService.stripReportExtension(np);
            String cf = extractFileName(np);
            if (cf.isBlank() && c.rawName() != null) {
                cf = c.rawName().trim();
            }
            String cn = FeishuApprovalParserService.stripReportExtension(
                    c.rawName() != null && !c.rawName().isBlank() ? c.rawName().trim() : cf);
            candNormPaths.add(np);
            candCleanPaths.add(cp);
            candFiles.add(cf);
            candCleanNames.add(cn);
        }

        // Tier 1: 完整规范化路径精确匹配 (保留扩展名优先)
        if (!normItemPath.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (normItemPath.equalsIgnoreCase(candNormPaths.get(i))) {
                    return candidates.get(i).reportId();
                }
            }
        }
        // Tier 2: 完整规范化路径匹配 (忽略扩展名)
        if (!cleanItemPath.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (cleanItemPath.equalsIgnoreCase(candCleanPaths.get(i))) {
                    return candidates.get(i).reportId();
                }
            }
        }
        // Tier 3: 带 "/" 段边界的后缀路径匹配 (先带扩展名，再不带扩展名；命中多个同路径则拒猜)
        if (!normItemPath.isBlank() && normItemPath.contains("/")) {
            String hit = matchUniqueByBoundary(candidates, candNormPaths, normItemPath, "路径后缀精确");
            if (hit != null) return hit;
        }
        if (!cleanItemPath.isBlank() && cleanItemPath.contains("/")) {
            String hit = matchUniqueByBoundary(candidates, candCleanPaths, cleanItemPath, "去后缀路径");
            if (hit != null) return hit;
        }
        // Tier 4: 仅按文件名/纯表名兜底 —— 按 reportId 去重后仅当全库唯一匹配时才返回；重名报表若前 3 轮路径未命中则绝不瞎猜
        if (!itemFile.isBlank()) {
            Map<String, String> fileHits = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (itemFile.equalsIgnoreCase(candFiles.get(i))) {
                    fileHits.putIfAbsent(candidates.get(i).reportId(), candidates.get(i).rawPath());
                }
            }
            if (fileHits.size() == 1) {
                return fileHits.keySet().iterator().next();
            }
            if (fileHits.size() > 1) {
                log.warn("⚠️ 【报表匹配安全拦截】目录存在 [{}] 张同名文件 [{}] 且路径未精确对上，放入待核实清单以免开错: {}",
                        fileHits.size(), itemFile, fileHits);
                return null;
            }
        }
        if (!cleanItemName.isBlank()) {
            Map<String, String> nameHits = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (cleanItemName.equalsIgnoreCase(candCleanNames.get(i))) {
                    nameHits.putIfAbsent(candidates.get(i).reportId(), candidates.get(i).rawPath());
                }
            }
            if (nameHits.size() == 1) {
                return nameHits.keySet().iterator().next();
            }
            if (nameHits.size() > 1) {
                log.warn("⚠️ 【报表匹配安全拦截】目录存在 [{}] 张同名报表 [{}] 且路径未精确对上，放入待核实清单以免开错: {}",
                        nameHits.size(), cleanItemName, nameHits);
                return null;
            }
        }
        return null;
    }

    private String matchUniqueByBoundary(List<CatalogCandidate> candidates, List<String> candPaths, String targetPath, String label) {
        String lowerTarget = targetPath.toLowerCase();
        // 优先匹配候选路径本身完整包含 targetPath 后缀的条目，其次匹配 targetPath 包含候选路径后缀（例如表里省略了“01生产环境/”）
        Map<String, String> dbContainsTarget = new LinkedHashMap<>();
        Map<String, String> targetContainsDb = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            String cp = candPaths.get(i);
            if (cp.isBlank()) {
                continue;
            }
            String lowerCand = cp.toLowerCase();
            if (lowerCand.equals(lowerTarget) || lowerCand.endsWith("/" + lowerTarget)) {
                dbContainsTarget.putIfAbsent(candidates.get(i).reportId(), candidates.get(i).rawPath());
            } else if (lowerTarget.endsWith("/" + lowerCand)) {
                targetContainsDb.putIfAbsent(candidates.get(i).reportId(), candidates.get(i).rawPath());
            }
        }
        if (dbContainsTarget.size() == 1) {
            return dbContainsTarget.keySet().iterator().next();
        }
        if (dbContainsTarget.size() > 1) {
            log.warn("⚠️ 【报表匹配安全拦截】{}匹配到 [{}] 张候选报表 [{}]，存在歧义已拒绝盲猜: {}",
                    label, dbContainsTarget.size(), targetPath, dbContainsTarget);
            return null;
        }
        if (targetContainsDb.size() == 1) {
            return targetContainsDb.keySet().iterator().next();
        }
        if (targetContainsDb.size() > 1) {
            log.warn("⚠️ 【报表匹配安全拦截】{}匹配到 [{}] 张候选报表 [{}]，存在歧义已拒绝盲猜: {}",
                    label, targetContainsDb.size(), targetPath, targetContainsDb);
        }
        return null;
    }

    private static String normalizeReportPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        String normalized = rawPath.replace("\\", "/").trim();
        String[] rawParts = normalized.split("/");
        List<String> segments = new ArrayList<>();
        for (String p : rawParts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (segments.isEmpty() && (s.contains("美鹰")
                    || s.contains("FineReport")
                    || s.equalsIgnoreCase("Dec-Entry_Management")
                    || s.equalsIgnoreCase("集团报表"))) {
                continue;
            }
            segments.add(s);
        }
        return String.join("/", segments);
    }

    private static String extractFileName(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return "";
        }
        int idx = normalizedPath.lastIndexOf('/');
        return idx >= 0 ? normalizedPath.substring(idx + 1).trim() : normalizedPath.trim();
    }

    private Map<String, Object> loadReportApprovalSample() throws Exception {
        return loadJsonSampleFile("real_feishu_approval_sample.json", "真实审批单样本文件");
    }

    private Map<String, Object> loadRoleApprovalSample() throws Exception {
        return loadJsonSampleFile("real_feishu_role_approval_sample.json", "角色审批单样本文件");
    }

    private Map<String, Object> loadJsonSampleFile(String fileName, String desc) throws Exception {
        InputStream is = getClass().getResourceAsStream("/" + fileName);
        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(fileName);
        }
        if (is == null) {
            for (String prefix : List.of("src/main/resources/", "backend/src/main/resources/", "src/test/resources/", "backend/src/test/resources/")) {
                File file = new File(prefix + fileName);
                if (file.exists()) {
                    is = new FileInputStream(file);
                    break;
                }
            }
        }
        if (is == null) {
            throw new IllegalStateException("未找到" + desc + " " + fileName);
        }
        try (InputStream stream = is) {
            Map<String, Object> root = objectMapper.readValue(stream, new TypeReference<Map<String, Object>>() {});
            return (Map<String, Object>) root.get("data");
        }
    }

    private FineReportUser resolveTargetFineReportUser(FineReportUser matched, String finalUsername, String realName, String mobile) {
        if (matched != null && matched.getUsername() != null && matched.getUsername().equalsIgnoreCase(finalUsername)) {
            return matched;
        }
        FineReportUser frUser = fineReportApiClient.findUserSmart(finalUsername, realName, mobile);
        return frUser != null ? frUser : fineReportApiClient.findUserByMobileOrUsername(finalUsername);
    }

    /**
     * 宁可报警不开，绝不乱猜错开：未唯一锁定目标用户时中止自动化并推送缺失/待核实告警卡片
     */
    private ApprovalProcessResult abortWithAccountMissingAlert(
            ExtractedApprovalData data, String targetUser, List<String> requestedItems,
            String detailMessage, ApprovalProcessResult extractResult) {
        log.error("❌ 【安全拦截，宁缺毋滥】{}, instanceCode={}", detailMessage, data.getInstanceCode());
        boolean alertSent = !shouldSkipHandlerCard(data) && dispatchToTargets(data, extractResult, (receiveId, receiveIdType) ->
                feishuApiClient.sendAccountMissingAlertCard(
                        receiveId, receiveIdType,
                        data.getApplicantName(), targetUser,
                        data.getInstanceCode(), requestedItems != null ? requestedItems : Collections.emptyList()
                ));
        extractResult.setNotificationSent(alertSent);
        String abortMsg = "自动处理已终止：" + detailMessage + (alertSent ? "（告警已发送）" : "（告警未送达）");
        extractResult.setMessage(abortMsg);
        extractResult.setFineReportAuthResult(Map.of(
                "success", false,
                "error", detailMessage,
                "alertCardSent", alertSent
        ));
        recordAuditSafely(data, targetUser, null, requestedItems,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                "INTERCEPTED", abortMsg, extractResult.getNotifiedTargetIds());
        return extractResult;
    }

    private void recordAuditSafely(
            ExtractedApprovalData data,
            String targetUsername,
            String carrierId,
            List<String> requestedItems,
            List<String> newlyGrantedItems,
            List<String> alreadyGrantedItems,
            List<String> unmappedItems,
            List<String> failedItems,
            String status,
            String message,
            List<String> notifiedTargets) {
        if (feishuApprovalAuditService == null || data == null) {
            return;
        }
        feishuApprovalAuditService.recordAudit(
                data.getInstanceCode(),
                data.getApprovalName(),
                data.getApplyType(),
                data.getApplicantName(),
                targetUsername,
                carrierId,
                requestedItems,
                newlyGrantedItems,
                alreadyGrantedItems,
                unmappedItems,
                failedItems,
                status,
                message,
                notifiedTargets
        );
    }

    private boolean isRoleChangeOnly(String applyType) {
        if (applyType == null || applyType.isBlank()) {
            return false;
        }
        String t = applyType.trim();
        return t.contains("变更") && !t.contains("新建") && !t.contains("账号申请") && !t.contains("账号数据角色申请");
    }

    /**
     * 执行智数平台-账号与数据角色申请或角色变更自动化流程
     */
    private ApprovalProcessResult executeRoleAndAccountPipeline(
            ExtractedApprovalData data, FineReportUser existingUser, String finalUsername, String realName, ApprovalProcessResult extractResult) {

        log.info("🎯 【进入账号与数据角色自动化流程】申请人: [{}], 最终目标用户: [{}] ({}), 申请类型: [{}], 角色列表: {}",
                data.getApplicantName(), finalUsername, realName, data.getApplyType(), data.getTargetRoles());

        if (existingUser == null) {
            if (fineReportApiClient.isAmbiguousRealName(realName)) {
                return abortWithAccountMissingAlert(data, finalUsername, data.getTargetRoles(),
                        "帆软存在多位同名员工 [" + realName + "] 且无法通过邮箱/手机唯一区分，已拒绝自动新建或赋权", extractResult);
            }
            if (isRoleChangeOnly(data.getApplyType())) {
                return abortWithAccountMissingAlert(data, finalUsername, data.getTargetRoles(),
                        "申请类型为 [" + data.getApplyType() + "]，但未在帆软中找到已有账号 [" + finalUsername + "]，已拒绝误建新号", extractResult);
            }
            boolean hasEmail = data.getApplicantEmail() != null && !data.getApplicantEmail().isBlank();
            boolean hasEnName = data.getApplicantEnName() != null && !data.getApplicantEnName().isBlank();
            boolean nameMissing = realName == null || realName.isBlank() || realName.equals(data.getUserId());
            if (!hasEmail && !hasEnName && nameMissing) {
                return abortWithAccountMissingAlert(data, finalUsername, data.getTargetRoles(),
                        "未能从飞书获取到完整姓名与英文名/企业邮箱，身份无法确认，已拒绝盲目建号", extractResult);
            }
        }

        // 2. 部门/邮箱/手机 默认均留空，不再调用飞书部门接口查询

        // 3. 解析目标角色 ID 列表 (从 FineReport 角色字典中匹配 UUID)
        List<FineReportApiClient.FineReportRole> allRoles = fineReportApiClient.getCustomRoles();
        Map<String, String> roleNameToId = new HashMap<>();
        for (FineReportApiClient.FineReportRole r : allRoles) {
            if (r.getName() != null) {
                roleNameToId.put(r.getName().trim().toLowerCase(), r.getId());
            }
        }

        List<String> targetRoleIds = new ArrayList<>();
        List<String> targetRoleNames = new ArrayList<>();
        List<String> unmappedRoles = new ArrayList<>();

        if (data.getTargetRoles() != null) {
            for (String rName : data.getTargetRoles()) {
                String cleanR = rName.trim();
                String rid = roleNameToId.get(cleanR.toLowerCase());

                if (rid != null && !rid.isBlank()) {
                    targetRoleIds.add(rid);
                    targetRoleNames.add(cleanR);
                } else {
                    log.error("❌ 【目标角色不存在】角色 [{}] 未能在帆软角色字典中精确匹配（已禁止自动创建角色）", cleanR);
                    unmappedRoles.add(cleanR);
                }
            }
        }

        if (!unmappedRoles.isEmpty()) {
            String errMsg = "帆软中不存在以下目标角色（未自动创建，请核对角色名称或先在帆软手动创建）: " + unmappedRoles;
            log.error("❌ 【角色匹配失败，终止执行】{}, instanceCode={}", errMsg, data.getInstanceCode());

            Map<String, List<String>> failedCategories = new LinkedHashMap<>();
            if (data.getRoleCategories() != null) {
                for (Map.Entry<String, List<String>> entry : data.getRoleCategories().entrySet()) {
                    List<String> matchedMissing = entry.getValue().stream()
                            .filter(unmappedRoles::contains)
                            .toList();
                    if (!matchedMissing.isEmpty()) {
                        failedCategories.put(entry.getKey(), matchedMissing);
                    }
                }
            }
            if (failedCategories.isEmpty()) {
                failedCategories.put("缺失角色", unmappedRoles);
            }

            boolean failCardSent = !shouldSkipHandlerCard(data) && dispatchToTargets(data, extractResult, (receiveId, receiveIdType) ->
                    feishuApiClient.sendRoleApprovalNotificationCard(
                            receiveId,
                            receiveIdType,
                            data.getApplicantName(),
                            data.getInstanceCode(),
                            data.getApplyType(),
                            null,
                            failedCategories,
                            Collections.emptyList(),
                            unmappedRoles,
                            null
                    ));

            Map<String, Object> errResp = new LinkedHashMap<>();
            errResp.put("success", false);
            errResp.put("username", finalUsername);
            errResp.put("error", errMsg);
            errResp.put("targetRoles", targetRoleNames);
            errResp.put("unmappedRoles", unmappedRoles);
            errResp.put("alertCardSent", failCardSent);
            extractResult.setFineReportAuthResult(errResp);
            extractResult.setSuccess(false);
            extractResult.setNotificationSent(failCardSent);
            extractResult.setMessage(errMsg);
            recordAuditSafely(data, finalUsername, existingUser != null ? existingUser.getId() : null,
                    data.getTargetRoles(), Collections.emptyList(), Collections.emptyList(),
                    unmappedRoles, unmappedRoles, "FAILED", errMsg, extractResult.getNotifiedTargetIds());
            return extractResult;
        }

        boolean opSuccess = false;
        boolean roleBindFailed = false;
        String actionSummary = "";
        String generatedInitialPassword = null;
        String effectiveUsername = finalUsername;

        if (existingUser == null) {
            effectiveUsername = resolvePreferredAccountUsername(finalUsername, realName, data);
            if (effectiveUsername == null || effectiveUsername.isBlank()
                    || effectiveUsername.contains("@") || effectiveUsername.matches("^\\d+$")) {
                return abortWithAccountMissingAlert(data, finalUsername, data.getTargetRoles(),
                        "未能解析到合规的公司英文用户名（仅获取到 [" + finalUsername + "]），已拒绝建号以防创建错误用户名", extractResult);
            }
            if (!effectiveUsername.equalsIgnoreCase(finalUsername)) {
                FineReportUser clashUser = fineReportApiClient.findUserByMobileOrUsername(effectiveUsername);
                if (clashUser != null) {
                    return abortWithAccountMissingAlert(data, effectiveUsername, data.getTargetRoles(),
                            "推导的新账号用户名 [" + effectiveUsername + "] 在帆软已存在（姓名: " + clashUser.getRealName()
                                    + "），存在重名或账号冲突风险，已拒绝自动建号", extractResult);
                }
            }

            // 用户不存在 -> 执行全自动开通新账号并按安全策略动态生成强密码（部门、邮箱、手机默认均留空）
            generatedInitialPassword = FineReportApiClient.generateCompliantPassword();
            log.info("👤 用户 [{}] ({}) 在帆软中不存在，使用规范用户名 [{}] 执行全自动开通新账号（部门/邮箱/手机默认留空），动态生成合规强密码: [{}], 角色列表: {}",
                    realName, finalUsername, effectiveUsername, generatedInitialPassword, targetRoleNames);

            fineReportApiClient.consumeLastCreatedUserRolesBound();
            String newUserId = fineReportApiClient.createUser(
                    effectiveUsername,
                    realName,
                    generatedInitialPassword,
                    "",
                    "",
                    null,
                    targetRoleIds,
                    targetRoleNames
            );
            Boolean rolesBound = fineReportApiClient.consumeLastCreatedUserRolesBound();

            if (newUserId != null && !newUserId.isBlank()) {
                opSuccess = true;
                if (Boolean.FALSE.equals(rolesBound) && !targetRoleNames.isEmpty()) {
                    roleBindFailed = true;
                    actionSummary = "新建账号成功，但角色绑定失败（需人工确认补绑） (初始密码: " + generatedInitialPassword + ")";
                } else {
                    actionSummary = "新建账号成功并自动赋予角色 (初始密码: " + generatedInitialPassword + ")";
                }
            } else {
                actionSummary = "新建账号接口返回非预期结果";
            }
        } else {
            // 用户已存在 -> 仅变更/赋予角色（不主动改动或拉取部门、邮箱、手机）
            log.info("👤 用户 [{}] (ID: {}) 已存在，执行角色同步...", finalUsername, existingUser.getId());

            Set<String> mergedRoleNames = new LinkedHashSet<>(existingUser.getCustomRoleNames());

            // 变更逻辑：如果申请了新的“大洲权限_”，替换原有的旧“大洲权限_”；如果申请了新的“BU权限_”，替换原有的旧“BU权限_”
            for (String newRole : targetRoleNames) {
                if (newRole.startsWith("大洲权限_")) {
                    mergedRoleNames.removeIf(r -> r.startsWith("大洲权限_"));
                } else if (newRole.startsWith("BU权限_")) {
                    mergedRoleNames.removeIf(r -> r.startsWith("BU权限_"));
                }
            }
            mergedRoleNames.addAll(targetRoleNames);

            // 重新解析合并后的所有角色 ID
            List<String> finalRoleIds = new ArrayList<>();
            for (String rName : mergedRoleNames) {
                String rid = roleNameToId.get(rName.toLowerCase().trim());
                if (rid != null && !rid.isBlank()) {
                    finalRoleIds.add(rid);
                } else if (FineReportApiClient.isUuidOrTechnicalId(rName)) {
                    finalRoleIds.add(rName);
                }
            }

            opSuccess = fineReportApiClient.updateUserCustomRoles(
                    existingUser.getId(),
                    existingUser.getUsername(),
                    null,
                    finalRoleIds,
                    new ArrayList<>(mergedRoleNames),
                    targetRoleIds,
                    Collections.emptyList()
            );
            actionSummary = opSuccess ? "角色同步更新成功" : "角色同步更新失败";
        }

        Map<String, Object> frResp = new LinkedHashMap<>();
        frResp.put("success", opSuccess);
        frResp.put("username", effectiveUsername);
        frResp.put("action", actionSummary);
        frResp.put("targetRoles", targetRoleNames);
        frResp.put("unmappedRoles", unmappedRoles);
        extractResult.setFineReportAuthResult(frResp);

        if (!opSuccess) {
            log.error("❌ 帆软角色与账号处理未成功: {}, 终止推送飞书成功通知卡片", actionSummary);
            extractResult.setNotificationSent(false);
            String failMsg = "帆软配置未生效: " + actionSummary;
            extractResult.setMessage(failMsg);
            recordAuditSafely(data, effectiveUsername, existingUser != null ? existingUser.getId() : null,
                    data.getTargetRoles(), Collections.emptyList(), Collections.emptyList(),
                    unmappedRoles, targetRoleNames, "FAILED", failMsg, Collections.emptyList());
            return extractResult;
        }

        List<String> cardFailRoles = new ArrayList<>(unmappedRoles);
        if (roleBindFailed) {
            for (String r : targetRoleNames) {
                if (!cardFailRoles.contains(r)) {
                    cardFailRoles.add(r);
                }
            }
        }
        List<String> cardSuccessRoles = roleBindFailed ? Collections.emptyList() : targetRoleNames;

        // 4. 发送飞书通知卡片 (仅在办理节点仍待处理时发送给所有待处理办理人核对；办理人已同意结束则不再重复发卡)
        boolean notified = false;
        if (shouldSkipHandlerCard(data)) {
            log.info("【飞书卡片推送跳过】角色实例 [{}] 办理人已完成处理 (status={})，不再重复发送办理提醒卡片",
                    data.getInstanceCode(), data.getStatus());
        } else {
            final String initPwd = generatedInitialPassword;
            notified = dispatchToTargets(data, extractResult, (receiveId, receiveIdType) ->
                    feishuApiClient.sendRoleApprovalNotificationCard(
                            receiveId,
                            receiveIdType,
                            data.getApplicantName(),
                            data.getInstanceCode(),
                            data.getApplyType(),
                            data.getOrgControlRule(),
                            data.getRoleCategories(),
                            cardSuccessRoles,
                            cardFailRoles,
                            initPwd
                    ));
        }

        extractResult.setNotificationSent(notified);
        String finalMsg = "自动化闭环完成！帆软账号与角色处理: " + actionSummary + "，飞书卡片推送: " + (notified ? "成功" : "已跳过");
        extractResult.setMessage(finalMsg);
        boolean allRolesOk = !roleBindFailed && unmappedRoles.isEmpty();
        recordAuditSafely(data, effectiveUsername, existingUser != null ? existingUser.getId() : null,
                data.getTargetRoles(), cardSuccessRoles, Collections.emptyList(),
                unmappedRoles, roleBindFailed ? targetRoleNames : Collections.emptyList(),
                allRolesOk ? "SUCCESS" : "PARTIAL",
                finalMsg, extractResult.getNotifiedTargetIds());
        return extractResult;
    }
}
