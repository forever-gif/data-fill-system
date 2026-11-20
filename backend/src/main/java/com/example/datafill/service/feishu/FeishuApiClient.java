package com.example.datafill.service.feishu;

import com.example.datafill.config.FeishuProperties;
import com.example.datafill.entity.ReportCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import javax.net.ssl.*;
import java.util.*;

/**
 * 飞书 OpenAPI 核心通讯客户端
 * 负责 Token 管理、知识库 Wiki 节点管理、Docx 云文档 Block 写入
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuApiClient {

    private final FeishuProperties feishuProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    // 🌟 实际 API 调用真实计数器 (通过 RestTemplate 拦截器 100% 精确统计向飞书发出的每一个 HTTP 请求)
    private final java.util.concurrent.atomic.AtomicInteger apiCallCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public void resetApiCallCount() {
        apiCallCount.set(0);
    }

    public int getApiCallCount() {
        return apiCallCount.get();
    }

    // 🌟 支持 PATCH 请求方法的通用 RestTemplate (通过反射绕过 HttpURLConnection 的 PATCH 限制，兼容所有 JDK 环境)
    private final RestTemplate restTemplate = initRestTemplate();

    private RestTemplate initRestTemplate() {
        RestTemplate rt = createPatchSupportedRestTemplate();
        rt.getInterceptors().add((request, body, execution) -> {
            apiCallCount.incrementAndGet();
            return execution.execute(request, body);
        });
        return rt;
    }

    private static RestTemplate createPatchSupportedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                if ("PATCH".equals(httpMethod)) {
                    try {
                        java.lang.reflect.Field methodField = HttpURLConnection.class.getDeclaredField("method");
                        methodField.setAccessible(true);
                        methodField.set(connection, "PATCH");
                    } catch (Exception e) {
                        connection.setRequestMethod(httpMethod);
                    }
                } else {
                    super.prepareConnection(connection, httpMethod);
                }
            }
        };
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    private static final String FEISHU_HOST = "https://open.feishu.cn/open-apis";

    // 内存按 App ID 隔离缓存 tenant_access_token 及过期时间，支持多自建应用并存
    private static class TokenCacheItem {
        final String token;
        final long expireTime;
        TokenCacheItem(String token, long expireTime) {
            this.token = token;
            this.expireTime = expireTime;
        }
    }
    private final Map<String, TokenCacheItem> appTokenCacheMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Object> appTokenLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 手动失效 Token 缓存 (权限变更时使用)
     */
    public void invalidateToken() {
        this.appTokenCacheMap.clear();
        log.info("已手动清空全部飞书应用的 tenant_access_token 内存缓存");
    }

    /**
     * 失效指定应用（或审批默认应用）的 Token 缓存
     */
    public void invalidateAppToken(String appId) {
        if (appId != null && !appId.isBlank()) {
            this.appTokenCacheMap.remove(appId.trim());
        }
    }

    private boolean isTokenInvalidError(String text) {
        return text != null && (text.contains("99991663") || text.contains("99991664") || text.contains("Invalid access token"));
    }

    /**
     * 获取租户访问凭证 (默认读取 application.yml 配置)
     */
    public String getTenantAccessToken() {
        return getTenantAccessToken(null, null);
    }

    /**
     * 获取审批专用应用的租户访问凭证
     */
    public String getApprovalTenantAccessToken() {
        return getTenantAccessToken(feishuProperties.getEffectiveApprovalAppId(), feishuProperties.getEffectiveApprovalAppSecret());
    }

    /**
     * 获取租户访问凭证 (tenant_access_token，按 App ID 独立加锁互不阻塞)
     */
    public String getTenantAccessToken(String customAppId, String customAppSecret) {
        String appId = (customAppId != null && !customAppId.isBlank()) ? customAppId.trim() : feishuProperties.getAppId();
        String appSecret = (customAppSecret != null && !customAppSecret.isBlank()) ? customAppSecret.trim() : feishuProperties.getAppSecret();

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank() || appId.startsWith("cli_a1b2c3d4e5f6")) {
            throw new IllegalArgumentException("未配置有效的飞书 App ID 与 App Secret，请在配置中填入真实的飞书应用凭证。");
        }

        long now = System.currentTimeMillis();
        TokenCacheItem cached = appTokenCacheMap.get(appId);
        if (cached != null && now < cached.expireTime - 60000) {
            return cached.token;
        }

        Object lock = appTokenLocks.computeIfAbsent(appId, k -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            cached = appTokenCacheMap.get(appId);
            if (cached != null && now < cached.expireTime - 60000) {
                return cached.token;
            }

            String url = FEISHU_HOST + "/auth/v3/tenant_access_token/internal";
            Map<String, String> body = Map.of(
                    "app_id", appId,
                    "app_secret", appSecret
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.CONNECTION, "keep-alive");
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

            Exception lastException = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                    Map respBody = response.getBody();
                    if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                        String token = (String) respBody.get("tenant_access_token");
                        int expireSeconds = (Integer) respBody.getOrDefault("expire", 7200);
                        appTokenCacheMap.put(appId, new TokenCacheItem(token, now + (expireSeconds * 1000L)));
                        log.info("成功获取飞书应用 [{}] tenant_access_token，有效期: {} 秒", appId, expireSeconds);
                        return token;
                    } else {
                        String msg = respBody != null ? (String) respBody.get("msg") : "未知错误";
                        throw new RuntimeException("获取飞书 access_token 失败: " + msg);
                    }
                } catch (Exception e) {
                    lastException = e;
                    log.warn("第 {} 次请求飞书 token 异常: {}，准备重试...", attempt, e.getMessage());
                    if (attempt < 3) {
                        try {
                            Thread.sleep(600L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            log.error("连续 3 次请求飞书获取 token 失败: {}", lastException != null ? lastException.getMessage() : "null");
            throw new RuntimeException("请求飞书 API 失败: " + (lastException != null ? lastException.getMessage() : "网络握手异常"), lastException);
        }
    }

    /**
     * 根据 node_token (或完整 URL) 获取节点详情及所在 space_id
     */
    public Map<String, Object> getNodeInfo(String tokenOrUrl, String token) {
        if (tokenOrUrl == null || tokenOrUrl.isBlank()) return null;
        String nodeToken = extractNodeToken(tokenOrUrl);
        String url = FEISHU_HOST + "/wiki/v2/spaces/get_node?token=" + nodeToken;

        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();
            log.info("调用飞书 get_node [{}] 响应结果: {}", nodeToken, respBody);
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("node")) {
                    return (Map<String, Object>) data.get("node");
                }
            } else {
                log.warn("飞书 get_node [{}] 返回错误: {}", nodeToken, respBody);
            }
        } catch (Exception e) {
            log.warn("根据 Token [{}] 获取节点信息异常: {}", nodeToken, e.getMessage());
        }
        return null;
    }

    public String extractNodeToken(String tokenOrUrl) {
        if (tokenOrUrl == null) return "";
        String s = tokenOrUrl.trim();

        // 1. 匹配空间主页或设置链接，如 /wiki/space/7678165788674624499 或 /wiki/settings/7678165788674624499
        java.util.regex.Matcher spaceMatcher = java.util.regex.Pattern.compile("/wiki/(?:space|settings)/(\\d+)").matcher(s);
        if (spaceMatcher.find()) {
            return spaceMatcher.group(1);
        }

        // 2. 匹配具体页面或文档链接，如 /wiki/wikcnXXX 或 /wiki/NpcCwXXX 或 /docx/doxcnXXX
        java.util.regex.Matcher tokenMatcher = java.util.regex.Pattern.compile("/(?:wiki|docx)/([a-zA-Z0-9_-]{15,})").matcher(s);
        if (tokenMatcher.find()) {
            return tokenMatcher.group(1);
        }

        // 3. 通用处理：去除参数与前缀
        if (s.contains("?")) s = s.substring(0, s.indexOf("?"));
        if (s.contains("/")) s = s.substring(s.lastIndexOf("/") + 1);
        return s;
    }

    /**
     * 获取当前自建应用有权限访问的所有知识库空间列表
     */
    public List<Map<String, Object>> listAccessibleSpaces(String token) {
        String url = FEISHU_HOST + "/wiki/v2/spaces";
        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("items")) {
                    return (List<Map<String, Object>>) data.get("items");
                }
            }
        } catch (Exception e) {
            log.warn("获取应用知识库空间列表异常: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 获取指定知识库空间下的子节点列表 (全量分页检索，防止漏节点导致重复创建)
     */
    public List<Map<String, Object>> getWikiNodes(String spaceId, String parentNodeToken, String token) {
        List<Map<String, Object>> allNodes = new ArrayList<>();
        String pageToken = null;
        HttpHeaders headers = createAuthHeaders(token);

        do {
            String url = FEISHU_HOST + "/wiki/v2/spaces/" + spaceId + "/nodes?page_size=50";
            if (parentNodeToken != null && !parentNodeToken.isBlank()) {
                url += "&parent_node_token=" + parentNodeToken;
            }
            if (pageToken != null && !pageToken.isBlank()) {
                url += "&page_token=" + pageToken;
            }

            try {
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    Map data = (Map) respBody.get("data");
                    if (data != null) {
                        if (data.containsKey("items")) {
                            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
                            if (items != null) allNodes.addAll(items);
                        }
                        Boolean hasMore = (Boolean) data.get("has_more");
                        if (Boolean.TRUE.equals(hasMore)) {
                            pageToken = (String) data.get("page_token");
                        } else {
                            pageToken = null;
                        }
                    } else {
                        pageToken = null;
                    }
                } else {
                    break;
                }
            } catch (Exception e) {
                log.warn("获取知识库子节点异常: {}", e.getMessage());
                break;
            }
        } while (pageToken != null && !pageToken.isBlank());

        return allNodes;
    }

    /**
     * 在飞书知识库空间中创建节点 (可以是新建 docx 文档或快捷方式)
     *
     * @param spaceId         知识库空间 ID
     * @param parentNodeToken 父级节点 Token (为 null 则在空间根节点创建)
     * @param title           文档/目录标题
     * @param objType         节点类型 (docx / origin)
     * @return 创建出的节点信息 (包含 node_token, obj_token 等)
     */
    public Map<String, Object> createWikiNode(String spaceId, String parentNodeToken, String title, String objType, String token) {
        String url = FEISHU_HOST + "/wiki/v2/spaces/" + spaceId + "/nodes";

        Map<String, Object> body = new HashMap<>();
        body.put("obj_type", objType != null ? objType : "docx");
        body.put("node_type", "origin"); // 🌟 必填字段：节点类型 (origin = 实体节点)
        body.put("title", title);
        if (parentNodeToken != null && !parentNodeToken.isBlank()) {
            body.put("parent_node_token", parentNodeToken);
        }

        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map respBody = response.getBody();
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("node")) {
                    return (Map<String, Object>) data.get("node");
                }
            }
            String msg = respBody != null ? (String) respBody.get("msg") : "未知错误";
            log.error("创建知识库节点失败 [{}]: {}", title, msg);
            if (msg.contains("131006") || msg.contains("permission denied")) {
                throw new RuntimeException("飞书知识库空间 [" + spaceId + "] 权限不足！请在飞书打开该知识库 -> 点击【知识空间设置】->【成员管理】->【添加成员】搜索选中你的自建应用，并赋予【管理员/可编辑】权限。");
            }
            throw new RuntimeException("创建知识库节点失败: " + msg);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("131006") || msg.contains("permission denied"))) {
                throw new RuntimeException("飞书知识库空间 [" + spaceId + "] 权限不足！请在飞书打开该知识库 -> 点击【知识空间设置】->【成员管理】->【添加成员】搜索选中你的自建应用，并赋予【管理员】权限。");
            }
            log.error("调用飞书创建知识库节点接口异常: {}", msg);
            throw new RuntimeException("创建知识库节点接口异常: " + msg, e);
        }
    }

    /**
     * 删除指定的知识库节点
     */
    public boolean deleteWikiNode(String spaceId, String nodeToken, String token) {
        String url = FEISHU_HOST + "/wiki/v2/spaces/" + spaceId + "/nodes/" + nodeToken;
        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, Map.class);
            Map respBody = response.getBody();
            return respBody != null && Integer.valueOf(0).equals(respBody.get("code"));
        } catch (Exception e) {
            log.error("调用飞书删除节点接口异常 [node: {}]: {}", nodeToken, e.getMessage());
            return false;
        }
    }

    /**
     * 更新知识库节点的标题
     */
    public boolean updateWikiNodeTitle(String spaceId, String nodeToken, String newTitle, String token) {
        String url = FEISHU_HOST + "/wiki/v2/spaces/" + spaceId + "/nodes/" + nodeToken + "/update_title";
        HttpHeaders headers = createAuthHeaders(token);
        Map<String, Object> req = Map.of("title", newTitle);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(req, headers);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            Map respBody = response.getBody();
            return respBody != null && Integer.valueOf(0).equals(respBody.get("code"));
        } catch (Exception e) {
            log.warn("调用飞书更新节点标题异常 [node: {}, title: {}]: {}", nodeToken, newTitle, e.getMessage());
            return false;
        }
    }

    /**
     * 智能写入报表 Docx 文档内容 (采用原生 Docx Block 结构化引擎，1:1 精确排版)
     */
    public void writeReportDocxContent(String documentId, ReportCatalog report, String token, FeishuDocxBuilder feishuDocxBuilder) {
        if (documentId == null || documentId.isBlank() || report == null) {
            return;
        }

        // 采用原生 Docx 结构化 Block 写入器，确保所有标题、表格、药丸徽章与卡片精准排版
        List<Map<String, Object>> blocks = feishuDocxBuilder.buildReportDocxBlocks(report);
        writeDocxBlocks(documentId, blocks, token);
    }

    /**
     * 调用飞书官方 Convert API 将 HTML / Markdown 转换为嵌套 Block 树，并调用 Descendant 接口一次性插入
     */
    public boolean convertAndInsertDocxContent(String documentId, String content, String preferredContentType, String token) {
        String convertUrl = FEISHU_HOST + "/docx/v1/documents/blocks/convert";
        HttpHeaders headers = createAuthHeaders(token);

        // 尝试不同的参数兼容类型 (支持 "markdown", "html", 1, 2)
        List<Object> candidateTypes = new ArrayList<>();
        if (preferredContentType != null) candidateTypes.add(preferredContentType);
        candidateTypes.addAll(List.of("markdown", "html", 1, 2));

        for (Object type : candidateTypes) {
            Map<String, Object> convertBody = Map.of(
                    "content", content,
                    "content_type", type
            );
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(convertBody, headers);
            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(convertUrl, entity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    Map data = (Map) respBody.get("data");
                    if (data != null && data.containsKey("blocks")) {
                        List<Map<String, Object>> allBlocks = (List<Map<String, Object>>) data.get("blocks");
                        if (allBlocks != null && !allBlocks.isEmpty()) {
                            // 1. 收集所有作为子节点的 ID
                            Set<String> childIds = new HashSet<>();
                            for (Map<String, Object> b : allBlocks) {
                                List<String> children = (List<String>) b.get("children");
                                if (children != null) {
                                    childIds.addAll(children);
                                }
                                // 移除表格可能带有的只读 merge_info 字段
                                Map<String, Object> table = (Map<String, Object>) b.get("table");
                                if (table != null) {
                                    table.remove("merge_info");
                                }
                            }

                            // 2. 筛选出一级顶层 Block ID 列表
                            List<String> topLevelIds = new ArrayList<>();
                            for (Map<String, Object> b : allBlocks) {
                                String blockId = (String) b.get("block_id");
                                if (blockId != null && !childIds.contains(blockId)) {
                                    topLevelIds.add(blockId);
                                }
                            }

                            if (topLevelIds.isEmpty() && !allBlocks.isEmpty()) {
                                topLevelIds.add((String) allBlocks.get(0).get("block_id"));
                            }

                            // 3. 清理文档历史内容
                            clearDocumentChildren(documentId, token);

                            // 4. 调用创建嵌套块 (descendant) 接口一次性写入全量层级
                            String descendantUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + documentId + "/descendant";
                            Map<String, Object> descendantReq = Map.of(
                                    "children_id", topLevelIds,
                                    "descendants", allBlocks,
                                    "index", -1
                            );
                            HttpEntity<Map<String, Object>> descendantEntity = new HttpEntity<>(descendantReq, headers);
                            ResponseEntity<Map> descResp = restTemplate.postForEntity(descendantUrl, descendantEntity, Map.class);
                            Map descBody = descResp.getBody();
                            if (descBody != null && Integer.valueOf(0).equals(descBody.get("code"))) {
                                log.info("🌟 成功通过飞书官方 Convert + Descendant 编译引擎 (type={}) 写入高品质云文档 [{}]！", type, documentId);
                                return true;
                            } else {
                                log.warn("飞书 Descendant 写入返回: {}", descBody);
                            }
                        }
                    }
                }
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getResponseBodyAsString() != null && e.getResponseBodyAsString().contains("99991672")) {
                    log.info("检测到飞书应用权限刚发布更新，正在自动刷新最新 Token 并重试 Convert...");
                    invalidateToken();
                    String freshToken = getTenantAccessToken();
                    if (freshToken != null && !freshToken.equals(token)) {
                        return convertAndInsertDocxContent(documentId, content, preferredContentType, freshToken);
                    }
                }
            } catch (Exception e) {
                // 尝试下一个候选类型
            }
        }
        return false;
    }

    /**
     * 组装标准 HTML 模版
     */
    public String buildReportHtml(ReportCatalog report) {
        String rawName = report.getReportName() != null ? report.getReportName() : "报表";
        String cleanName = rawName.replaceAll("^[0-9]+[._\\-\\s]*", "");
        String status = (report.getLifecycleStatus() != null && !report.getLifecycleStatus().isBlank())
                ? report.getLifecycleStatus().trim() : "建设中";
        String rawDesc = report.getReportDesc();
        String desc = (rawDesc != null && !rawDesc.isBlank()) ? rawDesc.trim() : null;
        String reportUrl = report.getReportUrl() != null && !report.getReportUrl().isBlank()
                ? report.getReportUrl()
                : "https://bi.meiying.homes/webroot/decision/view/report?viewlet=" + (report.getReportId() != null ? report.getReportId() : "xxx");

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>");
        sb.append("<h1>📊 ").append(cleanName).append("</h1>");
        if (desc != null && !desc.isBlank()) {
            sb.append("<blockquote><p>🎯 <strong>").append(desc).append("</strong></p></blockquote>");
        }

        sb.append("<h2>📜 基本信息</h2>");
        sb.append("<table><thead><tr><th>属性</th><th>详情</th></tr></thead><tbody>");
        sb.append("<tr><td>报表名称</td><td>").append(cleanName).append("</td></tr>");
        sb.append("<tr><td>报表路径</td><td>").append(report.getFullPath() != null ? report.getFullPath() : "美鹰集团报表/01生产环境/00.经营管理/01.经营月报/" + cleanName + ".cpt").append("</td></tr>");
        sb.append("<tr><td>报表链接</td><td><a href=\"").append(reportUrl).append("\">📌 打开报表</a></td></tr>");
        sb.append("<tr><td>适用范围</td><td>").append((report.getUseScope() != null && !report.getUseScope().isBlank()) ? report.getUseScope().trim() : "-").append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<h2>🏷️ 业务属性</h2>");
        sb.append("<table><thead><tr><th>属性</th><th>详情</th></tr></thead><tbody>");
        String biz = (report.getBizDomain() != null && !report.getBizDomain().isBlank()) ? report.getBizDomain() : ((report.getDomainL1() != null && !report.getDomainL1().isBlank()) ? report.getDomainL1() : "-");
        String s1 = (report.getSubjectL1() != null && !report.getSubjectL1().isBlank()) ? report.getSubjectL1() : "-";
        String s2 = (report.getSubjectL2() != null && !report.getSubjectL2().isBlank()) ? report.getSubjectL2() : ((report.getDomainL2() != null && !report.getDomainL2().isBlank()) ? report.getDomainL2() : "-");
        sb.append("<tr><td>业务域</td><td>").append(biz).append("</td></tr>");
        sb.append("<tr><td>一级主题</td><td>").append(s1).append("</td></tr>");
        sb.append("<tr><td>二级主题</td><td>").append(s2).append("</td></tr>");
        sb.append("<tr><td>分析维度</td><td>").append(cleanName).append("</td></tr>");
        sb.append("<tr><td>核心指标</td><td>").append((report.getMetrics() != null && !report.getMetrics().isBlank()) ? report.getMetrics() : "-").append("</td></tr>");
        sb.append("<tr><td>更新频率</td><td>").append(report.getUpdateFrequency() != null ? report.getUpdateFrequency() : "每日").append("</td></tr>");
        sb.append("<tr><td>数据更新时间</td><td>").append(report.getDataUpdateTime() != null ? report.getDataUpdateTime() : "每日 08:30").append("</td></tr>");
        sb.append("<tr><td>密级</td><td>").append(report.getIsConfidential() != null ? report.getIsConfidential() : "是").append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<h2>🛠️ 维护信息</h2>");
        sb.append("<table><thead><tr><th>属性</th><th>详情</th></tr></thead><tbody>");
        sb.append("<tr><td>报表负责人</td><td>👤 ").append(report.getOwner() != null ? report.getOwner() : "张三").append("</td></tr>");
        sb.append("<tr><td>所属部门</td><td>").append(report.getDeptName() != null ? report.getDeptName() : "智能数据部 / 数智中心 / 集团中台").append("</td></tr>");
        sb.append("<tr><td>开发人员</td><td>👤 ").append(report.getDeveloper() != null ? report.getDeveloper() : "李四").append("</td></tr>");
        sb.append("<tr><td>报表状态</td><td>").append(status).append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<blockquote>");
        sb.append("<p><b>💡 使用提示</b></p>");
        sb.append("<p>📌 <b>运营同学</b>: 每日关注 ").append(cleanName).append(" 的销售、利润率与库存变化，及时响应异常波动。</p>");
        sb.append("<p>📌 <b>管理层</b>: 通过 ").append(cleanName).append(" 维度识别重点商品的经营表现与潜在风险，辅助决策。</p>");
        sb.append("</blockquote>");

        sb.append("<hr/>");
        sb.append("<p>🌸 报表百科</p>");
        sb.append("</body></html>");

        return sb.toString();
    }

    /**
     * 组装优雅规范的 Markdown 模版
     */
    public String buildReportMarkdown(ReportCatalog report) {
        String rawName = report.getReportName() != null ? report.getReportName() : "报表";
        String cleanName = rawName.replaceAll("^[0-9]+[._\\-\\s]*", "");
        String status = (report.getLifecycleStatus() != null && !report.getLifecycleStatus().isBlank())
                ? report.getLifecycleStatus().trim() : "建设中";
        String rawDesc = report.getReportDesc();
        String desc = (rawDesc != null && !rawDesc.isBlank()) ? rawDesc.trim() : null;
        String reportUrl = report.getReportUrl() != null && !report.getReportUrl().isBlank()
                ? report.getReportUrl()
                : "https://bi.meiying.homes/webroot/decision/view/report?viewlet=" + (report.getReportId() != null ? report.getReportId() : "xxx");

        StringBuilder sb = new StringBuilder();
        sb.append("# 📊 ").append(cleanName).append("\n\n");
        if (desc != null && !desc.isBlank()) {
            sb.append("> 🎯 **").append(desc).append("**\n\n");
        }

        sb.append("## 📜 基本信息\n\n");
        sb.append("| 属性 | 详情 |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| **报表名称** | ").append(cleanName).append(" |\n");
        sb.append("| **报表路径** | ").append(report.getFullPath() != null ? report.getFullPath() : "美鹰集团报表/01生产环境/00.经营管理/01.经营月报/" + cleanName + ".cpt").append(" |\n");
        sb.append("| **报表链接** | [📌 打开报表](").append(reportUrl).append(") |\n");
        sb.append("| **适用范围** | ").append((report.getUseScope() != null && !report.getUseScope().isBlank()) ? report.getUseScope().trim() : "-").append(" |\n\n");

        sb.append("## 🏷️ 业务属性\n\n");
        sb.append("| 属性 | 详情 |\n");
        sb.append("| :--- | :--- |\n");
        String mBiz = (report.getBizDomain() != null && !report.getBizDomain().isBlank()) ? report.getBizDomain() : ((report.getDomainL1() != null && !report.getDomainL1().isBlank()) ? report.getDomainL1() : "-");
        String mS1 = (report.getSubjectL1() != null && !report.getSubjectL1().isBlank()) ? report.getSubjectL1() : "-";
        String mS2 = (report.getSubjectL2() != null && !report.getSubjectL2().isBlank()) ? report.getSubjectL2() : ((report.getDomainL2() != null && !report.getDomainL2().isBlank()) ? report.getDomainL2() : "-");
        sb.append("| **业务域** | ").append(mBiz).append(" |\n");
        sb.append("| **一级主题** | ").append(mS1).append(" |\n");
        sb.append("| **二级主题** | ").append(mS2).append(" |\n");
        sb.append("| **分析维度** | ").append(cleanName).append(" |\n");
        sb.append("| **核心指标** | ").append((report.getMetrics() != null && !report.getMetrics().isBlank()) ? report.getMetrics() : "-").append(" |\n");
        sb.append("| **更新频率** | ").append(report.getUpdateFrequency() != null ? report.getUpdateFrequency() : "每日").append(" |\n");
        sb.append("| **数据更新时间** | ").append(report.getDataUpdateTime() != null ? report.getDataUpdateTime() : "每日 08:30").append(" |\n");
        sb.append("| **密级** | ").append(report.getIsConfidential() != null ? report.getIsConfidential() : "是").append(" |\n\n");

        sb.append("## 🛠️ 维护信息\n\n");
        sb.append("| 属性 | 详情 |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| **报表负责人** | 👤 ").append(report.getOwner() != null ? report.getOwner() : "张三").append(" |\n");
        sb.append("| **所属部门** | ").append(report.getDeptName() != null ? report.getDeptName() : "智能数据部 / 数智中心 / 集团中台").append(" |\n");
        sb.append("| **开发人员** | 👤 ").append(report.getDeveloper() != null ? report.getDeveloper() : "李四").append(" |\n");
        sb.append("| **报表状态** | ").append(status).append(" |\n\n");

        sb.append("> 💡 **使用提示**\n");
        sb.append("> 📌 **运营同学**: 每日关注 ").append(cleanName).append(" 的销售、利润率与库存变化，及时响应异常波动。\n");
        sb.append("> 📌 **管理层**: 通过 ").append(cleanName).append(" 维度识别重点商品的经营表现与潜在风险，辅助决策。\n\n");

        sb.append("---\n\n");
        sb.append("🌸 报表百科\n");

        return sb.toString();
    }

    /**
     * 向指定 Docx 云文档批量写入 Blocks
     * 采用原子更新策略：先批量写入新内容 (每批最多 50 个 Block)，再安全清除历史旧 Block，彻底杜绝内容闪烁与旧数据残留
     *
     * @param documentId 文档的 obj_token / document_id
     * @param blocks     文档 Blocks 结构列表
     */
    public void writeDocxBlocks(String documentId, List<Map<String, Object>> blocks, String token) {
        if (blocks == null || blocks.isEmpty() || documentId == null || documentId.isBlank()) {
            return;
        }

        HttpHeaders headers = createAuthHeaders(token);

        // 1. 获取旧的历史 Block 数量
        int oldCount = getDocumentChildCount(documentId, headers);

        // 2. 批量写入最新生成的 Block 内容 (每次最高批量 50 个 Block，极致压缩 API 调用次数)
        List<Map<String, Object>> batch = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            Integer blockType = (Integer) block.get("block_type");

            if (Integer.valueOf(19).equals(blockType)) {
                // Callout 容器
                if (!batch.isEmpty()) {
                    postBlockChildren(documentId, documentId, Map.of("children", new ArrayList<>(batch), "index", -1), headers);
                    batch.clear();
                }
                Map<String, Object> calloutMap = (Map<String, Object>) block.get("callout");
                List<Map<String, Object>> children = null;
                if (calloutMap != null && calloutMap.containsKey("children")) {
                    children = (List<Map<String, Object>>) calloutMap.remove("children");
                }

                Map<String, Object> createReq = Map.of("children", List.of(block), "index", -1);
                Map resp = postBlockChildren(documentId, documentId, createReq, headers);
                String calloutBlockId = extractFirstChildBlockId(resp);

                if (calloutBlockId != null && children != null && !children.isEmpty()) {
                    Map<String, Object> childReq = Map.of("children", children, "index", -1);
                    postBlockChildren(documentId, calloutBlockId, childReq, headers);
                }
            } else if (Integer.valueOf(31).equals(blockType)) {
                // Table 容器
                if (!batch.isEmpty()) {
                    postBlockChildren(documentId, documentId, Map.of("children", new ArrayList<>(batch), "index", -1), headers);
                    batch.clear();
                }
                Map<String, Object> tableMap = (Map<String, Object>) block.get("table");
                List<Map<String, Object>> cellContentBlocks = null;
                if (tableMap != null && tableMap.containsKey("children")) {
                    cellContentBlocks = (List<Map<String, Object>>) tableMap.remove("children");
                }

                Map<String, Object> createReq = Map.of("children", List.of(block), "index", -1);
                Map resp = postBlockChildren(documentId, documentId, createReq, headers);
                List<String> cellBlockIds = extractTableCellBlockIds(resp);

                if (cellBlockIds != null && cellContentBlocks != null) {
                    int size = Math.min(cellBlockIds.size(), cellContentBlocks.size());
                    for (int i = 0; i < size; i++) {
                        String cellId = cellBlockIds.get(i);
                        Map<String, Object> cellContent = cellContentBlocks.get(i);
                        if (cellContent != null && cellContent.containsKey("children")) {
                            List<Map<String, Object>> cellTextBlocks = (List<Map<String, Object>>) cellContent.get("children");
                            if (cellTextBlocks != null && !cellTextBlocks.isEmpty()) {
                                updateCellParagraphText(documentId, cellId, cellTextBlocks.get(0), headers);
                            }
                        }
                    }
                }
            } else {
                // HTML 组件或普通 Block / Heading Blocks -> 加入批量缓冲队列
                batch.add(block);
                if (batch.size() >= 50) {
                    postBlockChildren(documentId, documentId, Map.of("children", new ArrayList<>(batch), "index", -1), headers);
                    batch.clear();
                    try { Thread.sleep(150L); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (!batch.isEmpty()) {
            postBlockChildren(documentId, documentId, Map.of("children", new ArrayList<>(batch), "index", -1), headers);
            batch.clear();
        }

        // 3. 写入完毕后原子删除所有旧 Block (彻底消除旧内容残留与闪烁)
        if (oldCount > 0) {
            deleteOldDocumentBlocks(documentId, oldCount, headers);
        }
    }

    private Map postBlockChildren(String documentId, String blockId, Map<String, Object> body, HttpHeaders headers) {
        String url = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + blockId + "/children";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    return respBody;
                } else {
                    String msg = respBody != null ? (String) respBody.get("msg") : "未知错误";
                    Integer code = respBody != null ? (Integer) respBody.get("code") : -1;
                    log.error("向飞书文档 [{}] 块 [{}] 写入失败: code={}, msg={}", documentId, blockId, code, msg);
                    throw new RuntimeException("向飞书文档写入内容失败 (code " + code + "): " + msg);
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("向飞书写 Block 发生网络或握手抖动 (第 {} 次尝试): {}，正在重试...", attempt, e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(500L); } catch (InterruptedException ignored) {}
                }
            }
        }
        log.error("请求飞书写 Block 接口重试 3 次均失败 [doc: {}, block: {}]: {}", documentId, blockId, lastException != null ? lastException.getMessage() : "null");
        throw new RuntimeException("请求飞书写入 Block 失败: " + (lastException != null ? lastException.getMessage() : "网络异常"), lastException);
    }

    /**
     * 更新单元格文本内容：先写入真实文本，然后精准删除飞书默认插入的空白第 0 段落，彻底保证极致紧凑的单行行高！
     */
    private void updateCellParagraphText(String documentId, String cellId, Map<String, Object> textBlock, HttpHeaders headers) {
        try {
            // 1. 查询飞书单元格默认自动建立的内部段落 Block ID
            String getUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + cellId + "/children";
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(getUrl, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();

            String defaultParaId = null;
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("items")) {
                    List items = (List) data.get("items");
                    if (items != null && !items.isEmpty()) {
                        Object firstObj = items.get(0);
                        if (firstObj instanceof Map) {
                            defaultParaId = (String) ((Map<?, ?>) firstObj).get("block_id");
                        } else if (firstObj instanceof String) {
                            defaultParaId = (String) firstObj;
                        }
                    }
                }
            }

            // 2. 先向单元格写入真实文本段落 (此时单元格内有 2 个段落: P0 空白, P1 真实内容)
            Map<String, Object> cellReq = Map.of("children", List.of(textBlock), "index", -1);
            postBlockChildren(documentId, cellId, cellReq, headers);

            // 3. 立即删除第 0 个空白段落 P0，彻底消除多余空行
            if (defaultParaId != null) {
                try {
                    String deleteUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + defaultParaId;
                    restTemplate.exchange(deleteUrl, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
                } catch (Exception e1) {
                    try {
                        String batchDelUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + cellId + "/children/batch_delete";
                        Map<String, Object> delBody = Map.of("start_index", 0, "end_index", 1);
                        restTemplate.exchange(batchDelUrl, HttpMethod.DELETE, new HttpEntity<>(delBody, headers), Map.class);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("更新单元格文本异常 [cell: {}]: {}", cellId, e.getMessage());
            try {
                Map<String, Object> cellReq = Map.of("children", List.of(textBlock), "index", -1);
                postBlockChildren(documentId, cellId, cellReq, headers);
            } catch (Exception ignored) {}
        }
    }

    private String extractFirstChildBlockId(Map respBody) {
        try {
            Map data = (Map) respBody.get("data");
            List children = (List) data.get("children");
            if (children != null && !children.isEmpty()) {
                Map first = (Map) children.get(0);
                return (String) first.get("block_id");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> extractTableCellBlockIds(Map respBody) {
        List<String> cellIds = new ArrayList<>();
        try {
            Map data = (Map) respBody.get("data");
            List children = (List) data.get("children");
            if (children != null && !children.isEmpty()) {
                Map tableBlock = (Map) children.get(0);
                Map table = (Map) tableBlock.get("table");
                if (table != null) {
                    Object cellsObj = table.get("cells");
                    if (cellsObj == null) {
                        cellsObj = table.get("children");
                    }
                    if (cellsObj == null && tableBlock.containsKey("children")) {
                        cellsObj = tableBlock.get("children");
                    }
                    if (cellsObj instanceof List) {
                        List list = (List) cellsObj;
                        for (Object item : list) {
                            if (item instanceof String) {
                                cellIds.add((String) item);
                            } else if (item instanceof Map) {
                                String id = (String) ((Map) item).get("block_id");
                                if (id != null) cellIds.add(id);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 Table Cell IDs 异常: {}", e.getMessage());
        }
        log.info("成功解析出飞书表格单元格 Block IDs 数量: {}", cellIds.size());
        return cellIds;
    }

    private int getDocumentChildCount(String documentId, HttpHeaders headers) {
        int total = 0;
        String pageToken = null;
        try {
            do {
                String getUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + documentId + "/children?page_size=500"
                        + (pageToken != null ? "&page_token=" + pageToken : "");
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> response = restTemplate.exchange(getUrl, HttpMethod.GET, entity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    Map data = (Map) respBody.get("data");
                    if (data != null) {
                        List items = (List) data.get("items");
                        if (items != null) total += items.size();
                        Boolean hasMore = (Boolean) data.get("has_more");
                        pageToken = Boolean.TRUE.equals(hasMore) ? (String) data.get("page_token") : null;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            } while (pageToken != null);
        } catch (Exception e) {
            log.warn("获取文档 [{}] 历史 Block 数量异常: {}", documentId, e.getMessage());
        }
        return total;
    }

    private void deleteOldDocumentBlocks(String documentId, int oldCount, HttpHeaders headers) {
        if (oldCount <= 0) return;
        int remaining = oldCount;
        try {
            String delUrl = FEISHU_HOST + "/docx/v1/documents/" + documentId + "/blocks/" + documentId + "/children/batch_delete";
            while (remaining > 0) {
                int deleteSize = Math.min(remaining, 50);
                Map<String, Object> delBody = Map.of(
                        "start_index", 0,
                        "end_index", deleteSize
                );
                HttpEntity<Map<String, Object>> delEntity = new HttpEntity<>(delBody, headers);
                ResponseEntity<Map> response = restTemplate.exchange(delUrl, HttpMethod.DELETE, delEntity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    remaining -= deleteSize;
                } else {
                    log.warn("批量删除飞书文档 [{}] 旧 Block 失败: {}", documentId, respBody);
                    break;
                }
            }
            log.info("成功彻底清除文档 [{}] 中的 {} 个历史旧 Block (保留最新生成内容)！", documentId, oldCount);
        } catch (Exception e) {
            log.warn("清理文档 [{}] 历史旧 Block 失败: {}", documentId, e.getMessage());
        }
    }

    /**
     * 自动清空云文档底部的历史 Block 节点 (用于重新同步覆盖时清理掉之前的旧数据/空白表格)
     */
    public void clearDocumentChildren(String documentId, String token) {
        HttpHeaders headers = createAuthHeaders(token);
        int count = getDocumentChildCount(documentId, headers);
        if (count > 0) {
            deleteOldDocumentBlocks(documentId, count, headers);
        }
    }

    /**
     * 从飞书表格 URL 中提取 spreadsheetToken
     */
    public String extractSpreadsheetToken(String tokenOrUrl) {
        if (tokenOrUrl == null) return "";
        String s = tokenOrUrl.trim();
        java.util.regex.Matcher sheetMatcher = java.util.regex.Pattern.compile("/sheets/([a-zA-Z0-9_-]{15,})").matcher(s);
        if (sheetMatcher.find()) {
            return sheetMatcher.group(1);
        }
        java.util.regex.Matcher wikiMatcher = java.util.regex.Pattern.compile("/wiki/([a-zA-Z0-9_-]{15,})").matcher(s);
        if (wikiMatcher.find()) {
            return wikiMatcher.group(1);
        }
        if (s.contains("?")) s = s.substring(0, s.indexOf("?"));
        if (s.contains("/")) s = s.substring(s.lastIndexOf("/") + 1);
        return s;
    }

    /**
     * 从飞书表格 URL 中提取参数中的 sheetId (如 ?sheet=xxxxxx)
     */
    public String extractSpreadsheetSheetId(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]sheet=([a-zA-Z0-9_-]+)").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 获取飞书电子表格的所有工作表子 Tab (Sheet Meta)
     */
    public List<Map<String, Object>> getSpreadsheetSheets(String spreadsheetToken, String token) {
        String realToken = extractSpreadsheetToken(spreadsheetToken);
        String url = FEISHU_HOST + "/sheets/v3/spreadsheets/" + realToken + "/sheets/query";
        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("sheets")) {
                    return (List<Map<String, Object>>) data.get("sheets");
                }
            } else {
                log.warn("获取飞书电子表格 [{}] 工作表列表失败: {}", realToken, respBody);
            }
        } catch (Exception e) {
            log.error("调用飞书 query sheets 异常: {}", e.getMessage());
            throw new RuntimeException("读取飞书表格工作表失败: " + e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    /**
     * 读取飞书工作表内容 (二维矩阵数据)
     */
    public List<List<Object>> getSheetValues(String spreadsheetToken, String sheetId, String token) {
        String realToken = extractSpreadsheetToken(spreadsheetToken);
        String range = (sheetId != null && !sheetId.isBlank()) ? sheetId + "!A1:Z5000" : "A1:Z5000";
        String url = FEISHU_HOST + "/sheets/v2/spreadsheets/" + realToken + "/values/" + range + "?valueRenderOption=ToString";
        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map data = (Map) respBody.get("data");
                if (data != null && data.containsKey("valueRange")) {
                    Map valueRange = (Map) data.get("valueRange");
                    if (valueRange != null && valueRange.containsKey("values")) {
                        return (List<List<Object>>) valueRange.get("values");
                    }
                }
            } else {
                log.warn("读取飞书电子表格 [{}] 范围 [{}] 失败: {}", realToken, range, respBody);
            }
        } catch (Exception e) {
            log.error("调用飞书 get sheet values 异常: {}", e.getMessage());
            throw new RuntimeException("读取飞书表格内容失败: " + e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    /**
     * 获取飞书审批实例详情 (approval v4)
     *
     * @param instanceCode 审批实例 Code (如 7D1E8F3C-XXXX-XXXX)
     * @return 审批实例原始数据 (包含 approval_name, status, form, user_id 等)
     */
    public Map<String, Object> getApprovalInstance(String instanceCode) {
        String url = FEISHU_HOST + "/approval/v4/instances/" + instanceCode + "?user_id_type=open_id";
        for (int attempt = 1; attempt <= 2; attempt++) {
            String token = getApprovalTenantAccessToken();
            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            try {
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
                Map respBody = response.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    return (Map<String, Object>) respBody.get("data");
                } else {
                    String respText = String.valueOf(respBody);
                    if (attempt == 1 && isTokenInvalidError(respText)) {
                        log.warn("【飞书审批】拉取实例 [{}] 发现 Token 失效，自动刷新后重试...", instanceCode);
                        invalidateAppToken(feishuProperties.getEffectiveApprovalAppId());
                        continue;
                    }
                    log.warn("获取飞书审批实例 [{}] 失败: {}", instanceCode, respBody);
                    throw new RuntimeException("飞书接口返回错误: " + (respBody != null ? respBody.get("msg") : "未知错误"));
                }
            } catch (Exception e) {
                if (attempt == 1 && isTokenInvalidError(e.getMessage())) {
                    log.warn("【飞书审批】拉取实例 [{}] 遭遇 Token 失效异常，自动刷新后重试...", instanceCode);
                    invalidateAppToken(feishuProperties.getEffectiveApprovalAppId());
                    continue;
                }
                log.error("调用飞书审批接口异常: {}", e.getMessage());
                throw new RuntimeException("获取飞书审批实例失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("获取飞书审批实例失败: 重试后仍未成功");
    }

    /**
     * 订阅指定飞书审批定义的事件推送 (POST /open-apis/approval/v4/approvals/{approval_code}/subscribe)
     * 飞书官方规范：配置事件订阅后，需调用一次此接口绑定对应的 approval_code 才会推送审批事件
     */
    public Map<String, Object> subscribeApprovalDefinitionDetailed(String approvalCode) {
        if (approvalCode == null || approvalCode.isBlank()) {
            return Map.of("code", -1, "msg", "approvalCode is blank");
        }
        try {
            String token = getApprovalTenantAccessToken();
            String url = FEISHU_HOST + "/approval/v4/approvals/" + approvalCode.trim() + "/subscribe";
            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Collections.emptyMap(), headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map respBody = response.getBody();
            log.info("【飞书审批事件订阅】绑定 approval_code=[{}] 响应: {}", approvalCode, respBody);
            return (respBody != null) ? (Map<String, Object>) respBody : Map.of("code", 0, "msg", "success");
        } catch (Exception e) {
            String errMsg = String.valueOf(e.getMessage());
            if (errMsg.contains("1390007") || errMsg.contains("subscription existed")) {
                log.info("【飞书审批事件订阅】approval_code=[{}] 已订阅生效（飞书返回 1390007），无需重复绑定", approvalCode);
                return Map.of("code", 0, "msg", "already subscribed");
            }
            log.error("调用飞书审批订阅接口 [{}] 异常: {}", approvalCode, errMsg);
            return Map.of("code", 500, "msg", errMsg);
        }
    }

    public boolean subscribeApprovalDefinition(String approvalCode) {
        Map<String, Object> res = subscribeApprovalDefinitionDetailed(approvalCode);
        return res != null && Integer.valueOf(0).equals(res.get("code"));
    }

    private HttpHeaders createAuthHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set(HttpHeaders.CONNECTION, "keep-alive");
        return headers;
    }

    /**
     * 发送飞书审批通过自动开通通知卡片 (给申请人)
     */
    public boolean sendApprovalNotificationCard(String receiveId, String receiveIdType, String applicantName, String serialNumber, List<String> reportNames) {
        return sendApprovalNotificationCard(receiveId, receiveIdType, applicantName, serialNumber, reportNames, Collections.emptyList());
    }

    /**
     * 发送飞书审批通过自动开通通知卡片 (兼容旧签名的重载方法)
     */
    public boolean sendApprovalNotificationCard(String receiveId, String receiveIdType, String applicantName,
                                                String serialNumber, List<String> grantedReports, List<String> unmappedReports) {
        return sendApprovalNotificationCard(receiveId, receiveIdType, applicantName, serialNumber,
                grantedReports, Collections.emptyList(), unmappedReports);
    }

    /**
     * 发送飞书审批通过自动开通通知卡片 (美观表格版，支持区分“本次新开通”与“原已有权限”)
     */
    public boolean sendApprovalNotificationCard(String receiveId, String receiveIdType, String applicantName,
                                                String serialNumber, List<String> newlyGrantedReports,
                                                List<String> alreadyGrantedReports, List<String> unmappedReports) {
        if (receiveId == null || receiveId.isBlank()) {
            return false;
        }
        try {
            // 1. 卡片 2.0 规范原生表格 (Native Table) 构建
            int newCount = newlyGrantedReports != null ? newlyGrantedReports.size() : 0;
            int alreadyCount = alreadyGrantedReports != null ? alreadyGrantedReports.size() : 0;
            int unmappedCount = unmappedReports != null ? unmappedReports.size() : 0;
            int totalCount = newCount + alreadyCount + unmappedCount;

            // 构造表格列 columns (飞书 table 规范中数据类型为 text)
            List<Map<String, Object>> columns = List.of(
                    Map.of(
                            "name", "seq",
                            "display_name", "序号",
                            "data_type", "text"
                    ),
                    Map.of(
                            "name", "report_name",
                            "display_name", "报表名称",
                            "data_type", "text"
                    ),
                    Map.of(
                            "name", "status",
                            "display_name", "赋权状态",
                            "data_type", "text"
                    )
            );

            // 构造表格行 rows (若有未匹配/失败报表，置顶展示，避免翻页遗漏)
            List<Map<String, Object>> rows = new ArrayList<>();
            int seqNum = 1;
            if (unmappedReports != null) {
                for (String r : unmappedReports) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", String.format("%02d", seqNum++));
                    row.put("report_name", r);
                    row.put("status", "⚠️ 未匹配/失败 需人工确认");
                    rows.add(row);
                }
            }
            if (newlyGrantedReports != null) {
                for (String r : newlyGrantedReports) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", String.format("%02d", seqNum++));
                    row.put("report_name", r);
                    row.put("status", "🟢 本次新开通");
                    rows.add(row);
                }
            }
            if (alreadyGrantedReports != null) {
                for (String r : alreadyGrantedReports) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", String.format("%02d", seqNum++));
                    row.put("report_name", r);
                    row.put("status", "🔵 原已有权限");
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                rows.add(Map.of("seq", "-", "report_name", "无报表条目", "status", "-"));
            }

            // 原生表格组件配置 (支持客户端分页)
            Map<String, Object> tableElement = new LinkedHashMap<>();
            tableElement.put("tag", "table");
            tableElement.put("page_size", 10);
            tableElement.put("row_height", "low");
            tableElement.put("columns", columns);
            tableElement.put("rows", rows);

            // 2. 卡片顶部标题栏 (若有待人工确认报表，切换醒目警告色并提示数量)
            boolean hasPending = unmappedCount > 0;
            boolean noneGranted = hasPending && newCount == 0 && alreadyCount == 0;
            String headerTemplate = !hasPending ? "turquoise" : (noneGranted ? "red" : "orange");
            String headerTitle = !hasPending
                    ? "✅ 帆软报表权限已自动开通"
                    : (noneGranted
                    ? "⚠️ 帆软报表自动开通失败 (" + unmappedCount + " 张需人工确认)"
                    : "⚠️ 帆软报表权限部分开通 (" + unmappedCount + " 张需人工确认)");
            Map<String, Object> header = Map.of(
                    "template", headerTemplate,
                    "title", Map.of("content", headerTitle, "tag", "plain_text")
            );

            StringBuilder statsBuilder = new StringBuilder();
            statsBuilder.append("共申请 **").append(totalCount).append("** 张 / 本次新开 **").append(newCount).append("** 张");
            if (alreadyCount > 0) {
                statsBuilder.append(" · 原已有 **").append(alreadyCount).append("** 张");
            }
            if (hasPending) {
                statsBuilder.append(" · <font color='red'>**需人工确认 ").append(unmappedCount).append(" 张**</font>");
            }
            String statsSummary = statsBuilder.toString();

            String approvalResultText = !hasPending
                    ? "<font color='green'>**已自动赋权生效**</font>"
                    : (noneGranted
                    ? "<font color='red'>**自动开通失败 (需人工确认)**</font>"
                    : "<font color='red'>**部分开通 (" + unmappedCount + " 张需人工确认)**</font>");

            // 3. 基础信息双列栅格
            List<Map<String, Object>> fields = List.of(
                    Map.of(
                            "is_short", true,
                            "text", Map.of("tag", "lark_md", "content", "**👤 申请人员**\n" + (applicantName != null ? applicantName : "申请人"))
                    ),
                    Map.of(
                            "is_short", true,
                            "text", Map.of("tag", "lark_md", "content", "**⚡ 审批结果**\n" + approvalResultText)
                    ),
                    Map.of(
                            "is_short", true,
                            "text", Map.of("tag", "lark_md", "content", "**🎫 审批单号**\n" + (serialNumber != null ? serialNumber : "-"))
                    ),
                    Map.of(
                            "is_short", true,
                            "text", Map.of("tag", "lark_md", "content", "**📊 开通统计**\n" + statsSummary)
                    )
            );

            List<Map<String, Object>> bodyElements = new ArrayList<>();
            // (1) 基础信息
            bodyElements.add(Map.of("tag", "div", "fields", fields));
            bodyElements.add(Map.of("tag", "hr"));

            // (2) 报表表格提示
            bodyElements.add(Map.of(
                    "tag", "div",
                    "text", Map.of("tag", "lark_md", "content", "**📋 权限开通清单明细 (" + totalCount + " 张，支持翻页查看)**：")
            ));

            // (3) 🌟 官方原生表格组件 (Native Table)
            bodyElements.add(tableElement);
            bodyElements.add(Map.of("tag", "hr"));

            // (4) 温馨提示组件 (Card 2.0 规范使用 markdown 代替废弃的 note)
            String tipContent = hasPending
                    ? "⚠️ <font color='red'>**重要提示：上方标记为「⚠️ 未匹配/失败 需人工确认」的 " + unmappedCount + " 张报表未自动开通成功（可能名称未匹配或授权接口异常），请先在帆软后台核实并手动补开权限后，再点击飞书审批【已处理】！**</font>"
                    : "💡 <font color='grey'>温馨提示：系统已根据数据主管审批结果自动开通权限！请办理人核对上方清单，确认无误后点击下方按钮进入飞书审批直接点击【已处理】完成闭环归档。</font>";
            bodyElements.add(Map.of(
                    "tag", "markdown",
                    "content", tipContent
            ));
            bodyElements.add(Map.of("tag", "hr"));

            // (5) 直达审批单与系统按钮
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "📋 打开飞书审批单 (点击【已处理】)"),
                    "type", "primary",
                    "width", "fill",
                    "url", buildApprovalAppLink(serialNumber)
            ));
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "🚀 进入帆软报表系统查看"),
                    "type", "default",
                    "width", "fill",
                    "url", "https://bi.meiying.homes/webroot/decision/"
            ));

            // 卡片 2.0 完整结构
            Map<String, Object> card = Map.of(
                    "schema", "2.0",
                    "header", header,
                    "body", Map.of("elements", bodyElements)
            );

            return dispatchInteractiveCard(receiveId, receiveIdType, card);
        } catch (Exception e) {
            log.warn("【飞书卡片推送】构建或发送通知异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送“帆软账号不存在”异常告警通知卡片给办理人
     */
    public boolean sendAccountMissingAlertCard(String receiveId, String receiveIdType, String applicantName,
                                               String expectedUsername, String serialNumber, List<String> requestedReports) {
        if (receiveId == null || receiveId.isBlank()) {
            return false;
        }
        try {
            int totalCount = requestedReports != null ? requestedReports.size() : 0;
            List<Map<String, Object>> columns = List.of(
                    Map.of("name", "seq", "display_name", "序号", "data_type", "text"),
                    Map.of("name", "report_name", "display_name", "申请报表名称", "data_type", "text"),
                    Map.of("name", "status", "display_name", "处理状态", "data_type", "text")
            );
            List<Map<String, Object>> rows = new ArrayList<>();
            int seqNum = 1;
            if (requestedReports != null) {
                for (String r : requestedReports) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", String.format("%02d", seqNum++));
                    row.put("report_name", r);
                    row.put("status", "🔴 未开通 (暂无帆软账号)");
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                rows.add(Map.of("seq", "-", "report_name", "无报表条目", "status", "-"));
            }

            Map<String, Object> tableElement = new LinkedHashMap<>();
            tableElement.put("tag", "table");
            tableElement.put("page_size", 10);
            tableElement.put("row_height", "low");
            tableElement.put("columns", columns);
            tableElement.put("rows", rows);

            Map<String, Object> header = Map.of(
                    "template", "red",
                    "title", Map.of("content", "⚠️ 帆软自动赋权提醒：申请人暂无帆软账号", "tag", "plain_text")
            );

            String displayApplicant = (applicantName != null ? applicantName : "申请人")
                    + (expectedUsername != null && !expectedUsername.isBlank() ? " (" + expectedUsername + ")" : "");
            List<Map<String, Object>> fields = List.of(
                    Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**👤 申请人员**\n" + displayApplicant)),
                    Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**⚡ 自动处理状态**\n<font color='red'>**已安全终止 (未找到账号)**</font>")),
                    Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**🎫 审批单号**\n" + (serialNumber != null ? serialNumber : "-"))),
                    Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**📊 申请明细**\n共申请 **" + totalCount + "** 张报表 (待人工处理)"))
            );

            List<Map<String, Object>> bodyElements = new ArrayList<>();
            bodyElements.add(Map.of("tag", "div", "fields", fields));
            bodyElements.add(Map.of("tag", "hr"));
            bodyElements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**📋 申请人请求开通的报表清单 (" + totalCount + " 张)**：")));
            bodyElements.add(tableElement);
            bodyElements.add(Map.of("tag", "hr"));
            bodyElements.add(Map.of(
                    "tag", "markdown",
                    "content", "🚨 <font color='red'>处理建议：系统未在帆软决策平台中匹配到【" + displayApplicant + "】的账号，已终止自动赋权以防误操作。请办理人确认其是否已走「账号申请」建号，建号或人工赋权后点击下方按钮进入审批单处理！</font>"
            ));
            bodyElements.add(Map.of("tag", "hr"));
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "📋 打开飞书审批单查看处理"),
                    "type", "primary",
                    "width", "fill",
                    "url", buildApprovalAppLink(serialNumber)
            ));
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "🚀 进入帆软决策系统后台"),
                    "type", "default",
                    "width", "fill",
                    "url", "https://bi.meiying.homes/webroot/decision/"
            ));

            Map<String, Object> card = Map.of(
                    "schema", "2.0",
                    "header", header,
                    "body", Map.of("elements", bodyElements)
            );
            return dispatchInteractiveCard(receiveId, receiveIdType, card);
        } catch (Exception e) {
            log.warn("【飞书卡片推送】构建或发送账号缺失告警卡片异常: {}", e.getMessage());
            return false;
        }
    }
    /**
     * 根据 user_id 或 open_id 获取飞书用户的通讯录详情 (含企业邮箱、姓名、手机号等)
     * 优先使用 App 2 查询，若未授权或失败则降级使用 App 1 查询
     *
     * @param userIdOrOpenId 飞书用户 ID 或 OpenID
     * @param idType "user_id" 或 "open_id"
     * @return 用户详情 Map (包含 enterprise_email, email, name, mobile, user_id, open_id 等)
     */
    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
        if (userIdOrOpenId == null || userIdOrOpenId.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Object> res = fetchContactUserInternal(userIdOrOpenId, idType, getApprovalTenantAccessToken());
        if (!res.isEmpty()) {
            return res;
        }
        return fetchContactUserInternal(userIdOrOpenId, idType, getTenantAccessToken());
    }

    private Map<String, Object> fetchContactUserInternal(String userIdOrOpenId, String idType, String token) {
        String realType = (idType != null && !idType.isBlank()) ? idType :
                (userIdOrOpenId.startsWith("ou_") ? "open_id" : "user_id");
        try {
            String url = FEISHU_HOST + "/contact/v3/users/" + userIdOrOpenId + "?user_id_type=" + realType;
            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map respBody = response.getBody();
            if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) respBody.get("data");
                if (data != null && data.get("user") instanceof Map) {
                    return (Map<String, Object>) data.get("user");
                }
            } else {
                log.warn("【飞书通讯录】查询用户详情失败 [{}={}]: resp={}", realType, userIdOrOpenId, respBody);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.contains("99991672")) {
                log.warn("【飞书通讯录】查询用户详情权限不足(需确认应用已开通并发布 contact:contact.base:readonly): [{}={}], error={}",
                        realType, userIdOrOpenId, errMsg);
            } else {
                log.warn("【飞书通讯录】查询用户详情异常 [{}={}]: {}", realType, userIdOrOpenId, errMsg);
            }
        }
        return Collections.emptyMap();
    }

    /**
     * 智能获取用户的企业邮箱 (优先查询 enterprise_email，其次 email)
     */
    public String getUserEnterpriseEmail(String userId, String openId) {
        Map<String, Object> userInfo = Collections.emptyMap();
        if (openId != null && !openId.isBlank()) {
            userInfo = getContactUser(openId, "open_id");
        }
        if (userInfo.isEmpty() && userId != null && !userId.isBlank()) {
            userInfo = getContactUser(userId, "user_id");
        }

        if (!userInfo.isEmpty()) {
            String enterpriseEmail = String.valueOf(userInfo.getOrDefault("enterprise_email", ""));
            if (enterpriseEmail != null && !enterpriseEmail.isBlank() && !"null".equalsIgnoreCase(enterpriseEmail)) {
                return enterpriseEmail.trim();
            }
            String email = String.valueOf(userInfo.getOrDefault("email", ""));
            if (email != null && !email.isBlank() && !"null".equalsIgnoreCase(email)) {
                return email.trim();
            }
        }
        return null;
    }

    /**
     * 根据飞书部门 ID 查询部门名称 (支持 App 2 与 App 1 降级互备)
     */
    public String getDepartmentName(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return "";
        for (String token : List.of(getApprovalTenantAccessToken(), getTenantAccessToken())) {
            try {
                String url = FEISHU_HOST + "/contact/v3/departments/" + departmentId + "?department_id_type=department_id";
                HttpHeaders headers = createAuthHeaders(token);
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
                if (resp.getBody() != null && Integer.valueOf(0).equals(resp.getBody().get("code"))) {
                    Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
                    if (data != null && data.get("department") instanceof Map) {
                        Map<String, Object> dept = (Map<String, Object>) data.get("department");
                        return String.valueOf(dept.getOrDefault("name", ""));
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 发送飞书账号与数据角色审批通过自动配置通知卡片 (Card 2.0 原生表格版)
     */
    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                   String applicantName, String instanceCode,
                                                   String applyType, String orgControlRule,
                                                   Map<String, List<String>> roleCategories,
                                                   List<String> successRoles, List<String> failRoles) {
        return sendRoleApprovalNotificationCard(receiveId, receiveIdType, applicantName, instanceCode, applyType, orgControlRule, roleCategories, successRoles, failRoles, null);
    }

    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                   String applicantName, String instanceCode,
                                                   String applyType, String orgControlRule,
                                                   Map<String, List<String>> roleCategories,
                                                   List<String> successRoles, List<String> failRoles,
                                                   String initialPassword) {
        if (receiveId == null || receiveId.isBlank()) {
            return false;
        }
        try {
            // 1. 构建 Card 2.0 原生表格 columns (数据类型为 text)
            List<Map<String, Object>> columns = List.of(
                    Map.of("name", "category", "display_name", "控权维度", "data_type", "text"),
                    Map.of("name", "role_name", "display_name", "角色 / 权限名称", "data_type", "text"),
                    Map.of("name", "status", "display_name", "配置状态", "data_type", "text")
            );

            // 2. 构建表格行 rows (若有失败角色置顶展示，避免被误认为全成功)
            List<Map<String, Object>> failedRows = new ArrayList<>();
            List<Map<String, Object>> okRows = new ArrayList<>();
            java.util.Set<String> coveredFailRoles = new java.util.LinkedHashSet<>();
            int successRoleCount = 0;

            if (roleCategories != null && !roleCategories.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : roleCategories.entrySet()) {
                    String category = entry.getKey();
                    for (String roleName : entry.getValue()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("category", category);
                        row.put("role_name", roleName);
                        boolean isFail = failRoles != null && failRoles.contains(roleName);
                        if (isFail) {
                            row.put("status", "⚠️ 配置失败 需人工确认");
                            failedRows.add(row);
                            coveredFailRoles.add(roleName);
                        } else {
                            row.put("status", "🟢 已开通生效");
                            okRows.add(row);
                            successRoleCount++;
                        }
                    }
                }
            }
            if (failRoles != null) {
                for (String failRole : failRoles) {
                    if (failRole != null && !coveredFailRoles.contains(failRole)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("category", "数据角色");
                        row.put("role_name", failRole);
                        row.put("status", "⚠️ 配置失败 需人工确认");
                        failedRows.add(row);
                        coveredFailRoles.add(failRole);
                    }
                }
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            rows.addAll(failedRows);
            rows.addAll(okRows);

            // 组织架构控权
            boolean hasOrgRule = orgControlRule != null && !orgControlRule.isBlank();
            if (hasOrgRule) {
                Map<String, Object> orgRow = new LinkedHashMap<>();
                orgRow.put("category", "组织结构控权");
                orgRow.put("role_name", orgControlRule);
                orgRow.put("status", "🟢 已自动关联");
                rows.add(orgRow);
            }

            if (rows.isEmpty()) {
                rows.add(Map.of("category", "-", "role_name", "无待配置角色", "status", "-"));
            }

            Map<String, Object> tableElement = new LinkedHashMap<>();
            tableElement.put("tag", "table");
            tableElement.put("page_size", 10);
            tableElement.put("row_height", "low");
            tableElement.put("columns", columns);
            tableElement.put("rows", rows);

            // 3. 卡片顶部标题栏 (存在失败角色时同步切为醒目告警样式)
            int failCount = coveredFailRoles.size();
            boolean hasFail = failCount > 0;
            boolean allFailed = hasFail && successRoleCount == 0 && !hasOrgRule;
            String headerTemplate = !hasFail ? "blue" : (allFailed ? "red" : "orange");
            String headerTitle = !hasFail
                    ? "✅ 帆软账号与数据角色已自动生效"
                    : (allFailed
                    ? "⚠️ 帆软账号与数据角色配置失败 (" + failCount + " 项需人工确认)"
                    : "⚠️ 帆软账号与数据角色部分生效 (" + failCount + " 项需人工确认)");
            Map<String, Object> header = Map.of(
                    "template", headerTemplate,
                    "title", Map.of("content", headerTitle, "tag", "plain_text")
            );

            String resultText = !hasFail
                    ? "<font color='green'>**已自动配置生效**</font>"
                    : (allFailed
                    ? "<font color='red'>**配置失败 (" + failCount + " 项需人工确认)**</font>"
                    : "<font color='red'>**部分生效 (" + failCount + " 项需人工确认)**</font>");

            // 4. 基础信息双列栅格
            List<Map<String, Object>> fields = new ArrayList<>();
            fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**👤 申请人员**\n" + (applicantName != null ? applicantName : "-"))));
            fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**⚡ 审批结果**\n" + resultText)));
            fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**🎫 审批单号**\n" + (instanceCode != null ? instanceCode : "-"))));
            fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**📌 申请类型**\n" + (applyType != null ? applyType : "账号数据角色申请"))));
            if (initialPassword != null && !initialPassword.isBlank()) {
                fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**🔑 初始密码**\n<font color='blue'>**" + initialPassword + "**</font>")));
                fields.add(Map.of("is_short", true, "text", Map.of("tag", "lark_md", "content", "**🛡️ 账号状态**\n<font color='green'>新账号已自动创建入库</font>")));
            }

            List<Map<String, Object>> bodyElements = new ArrayList<>();
            bodyElements.add(Map.of("tag", "div", "fields", fields));
            bodyElements.add(Map.of("tag", "hr"));
            bodyElements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**📋 数据角色与权限配置清单明细**：")));
            bodyElements.add(tableElement);
            bodyElements.add(Map.of("tag", "hr"));
            String roleTipContent = hasFail
                    ? "⚠️ <font color='red'>**重要提示：有 " + failCount + " 项角色未能自动匹配或创建，请先在帆软后台核实并手动补全配置后，再点击飞书审批【已处理】！**</font>"
                    : "💡 <font color='grey'>温馨提示：系统已根据数据主管审批结果自动完成账号与角色配置！请办理人核对上方清单，确认无误后点击下方按钮进入飞书审批直接点击【已处理】完成闭环归档。</font>";
            bodyElements.add(Map.of("tag", "markdown", "content", roleTipContent));
            bodyElements.add(Map.of("tag", "hr"));
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "📋 打开飞书审批单 (点击【已处理】)"),
                    "type", "primary",
                    "width", "fill",
                    "url", buildApprovalAppLink(instanceCode)
            ));
            bodyElements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "🚀 进入帆软决策系统查看"),
                    "type", "default",
                    "width", "fill",
                    "url", "https://bi.meiying.homes/webroot/decision/"
            ));

            Map<String, Object> card = Map.of(
                    "schema", "2.0",
                    "header", header,
                    "body", Map.of("elements", bodyElements)
            );

            return dispatchInteractiveCard(receiveId, receiveIdType, card);
        } catch (Exception e) {
            log.warn("【飞书卡片推送】构建或发送角色通知异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 统一发送交互式消息卡片：
     * 默认使用应用2（报表权限自动化审批专用应用 / 报表助手 getApprovalTenantAccessToken()）机器人发送；
     * 若遇跨应用 open_id (99992361) 自动切换为企业通用 user_id 重试。
     */
    private boolean dispatchInteractiveCard(String receiveId, String receiveIdType, Map<String, Object> card) throws Exception {
        if (receiveId == null || receiveId.isBlank()) {
            log.warn("【飞书卡片推送】receiveId 为空，放弃推送");
            return false;
        }

        String targetId = receiveId.trim();
        String idType = (receiveIdType != null && !receiveIdType.isBlank()) ? receiveIdType.trim().toLowerCase() : "open_id";

        // 🌟 1. 飞书 /im/v1/messages 接口不支持以 email 发送消息（会报 230001 invalid receive_id）。
        // 若入参为邮箱，必须先转换为 open_id 或 union_id
        if ("email".equalsIgnoreCase(idType) || targetId.contains("@")) {
            String resolvedId = resolveEmailToValidTargetId(targetId);
            if (resolvedId != null) {
                if (resolvedId.startsWith("union_id:")) {
                    idType = "union_id";
                    targetId = resolvedId.substring("union_id:".length());
                } else if (resolvedId.startsWith("open_id:")) {
                    idType = "open_id";
                    targetId = resolvedId.substring("open_id:".length());
                } else if (resolvedId.startsWith("ou_")) {
                    idType = "open_id";
                    targetId = resolvedId;
                } else if (resolvedId.startsWith("on_")) {
                    idType = "union_id";
                    targetId = resolvedId;
                }
            } else {
                log.warn("⚠️ 无法将邮箱 [{}] 转换为飞书 open_id 或 union_id（飞书消息接口不支持 receive_id_type=email）", targetId);
                // 检查是否有配置好的测试 open_id
                String defaultOpenId = feishuProperties != null ? feishuProperties.getDefaultTestOpenId() : null;
                if (defaultOpenId != null && defaultOpenId.startsWith("ou_") && defaultOpenId.length() >= 20) {
                    log.info("🧪 回退使用配置的默认测试 open_id: [{}]", defaultOpenId);
                    targetId = defaultOpenId;
                    idType = "open_id";
                } else {
                    log.error("❌ 无法发送飞书卡片：邮箱 [{}] 未能在飞书通讯录解析为有效 ID，且未配置有效测试 open_id", targetId);
                    return false;
                }
            }
        }

        String cardJson = objectMapper.writeValueAsString(card);
        String url = FEISHU_HOST + "/im/v1/messages?receive_id_type=" + idType;
        Map<String, Object> reqBody = Map.of(
                "receive_id", targetId,
                "msg_type", "interactive",
                "content", cardJson
        );

        for (int attempt = 1; attempt <= 2; attempt++) {
            String token = getApprovalTenantAccessToken();
            HttpHeaders headers = createAuthHeaders(token);
            try {
                ResponseEntity<Map> resp = restTemplate.postForEntity(url, new HttpEntity<>(reqBody, headers), Map.class);
                Map respBody = resp.getBody();
                if (respBody != null && Integer.valueOf(0).equals(respBody.get("code"))) {
                    log.info("🎉 【飞书卡片推送】使用审批专用应用(App 2)机器人成功向 [{}] ({}) 推送卡片: {}", targetId, idType, respBody);
                    return true;
                }
                String respText = String.valueOf(respBody);
                if (attempt == 1 && isTokenInvalidError(respText)) {
                    log.warn("【飞书卡片推送】Token 失效，刷新后重试推送...");
                    invalidateAppToken(feishuProperties.getEffectiveApprovalAppId());
                    continue;
                }
                if (respText.contains("99992361") && targetId.startsWith("ou_")) {
                    if (retrySendCardViaUnionId(targetId, cardJson, headers)) {
                        return true;
                    }
                }
                log.warn("【飞书卡片推送】App 2 推送卡片返回非成功状态 (targetId={}, type={}): {}", targetId, idType, respBody);
                return false;
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt == 1 && isTokenInvalidError(errMsg)) {
                    log.warn("【飞书卡片推送】遭遇 Token 失效，刷新后重试推送...");
                    invalidateAppToken(feishuProperties.getEffectiveApprovalAppId());
                    continue;
                }
                // 🌟 2. 99992361 (open_id cross app)：说明 targetId 是属于 App 1 的 open_id
                if (errMsg.contains("99992361") && targetId.startsWith("ou_")) {
                    if (retrySendCardViaUnionId(targetId, cardJson, headers)) {
                        return true;
                    }
                }
                log.warn("【飞书卡片推送】App 2 推送卡片异常 (targetId={}, type={}): {}", targetId, idType, errMsg);
                return false;
            }
        }
        return false;
    }

    private boolean retrySendCardViaUnionId(String app1OpenId, String cardJson, HttpHeaders headers) {
        log.info("🔄 检测到跨应用 OpenID [{}] (99992361)，尝试通过 App 1 换取全企业通用的 UnionID 并重试...", app1OpenId);
        String unionId = resolveUnionIdByApp1OpenId(app1OpenId);
        if (unionId == null || unionId.isBlank()) {
            return false;
        }
        try {
            String retryUrl = FEISHU_HOST + "/im/v1/messages?receive_id_type=union_id";
            Map<String, Object> retryBody = Map.of(
                    "receive_id", unionId,
                    "msg_type", "interactive",
                    "content", cardJson
            );
            ResponseEntity<Map> retryResp = restTemplate.postForEntity(retryUrl, new HttpEntity<>(retryBody, headers), Map.class);
            Map retryRespBody = retryResp.getBody();
            if (retryRespBody != null && Integer.valueOf(0).equals(retryRespBody.get("code"))) {
                log.info("🎉 【飞书卡片推送】跨应用桥接成功！App 2 通过通用 UnionID [{}] 成功推送卡片: {}", unionId, retryRespBody);
                return true;
            }
            log.warn("【飞书卡片推送】App 2 通过 UnionID [{}] 推送返回非成功状态: {}", unionId, retryRespBody);
        } catch (Exception retryEx) {
            log.warn("【飞书卡片推送】App 2 通过 UnionID [{}] 重试未成功: {}", unionId, retryEx.getMessage());
        }
        return false;
    }

    /**
     * 将邮箱解析为合规的接收目标 (优先 App 2 open_id，降级 App 1 union_id)
     */
    private String resolveEmailToValidTargetId(String email) {
        if (email == null || !email.contains("@")) return null;

        // 1. 尝试使用 App 2 查 open_id
        try {
            String lookupUrl = FEISHU_HOST + "/contact/v3/users/batch_get_id?user_id_type=open_id";
            HttpHeaders headers = createAuthHeaders(getApprovalTenantAccessToken());
            Map<String, Object> body = Map.of("emails", List.of(email.trim()));
            ResponseEntity<Map> resp = restTemplate.postForEntity(lookupUrl, new HttpEntity<>(body, headers), Map.class);
            if (resp.getBody() != null && Integer.valueOf(0).equals(resp.getBody().get("code"))) {
                Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
                if (data != null && data.get("user_list") instanceof List) {
                    List<Map<String, Object>> uList = (List<Map<String, Object>>) data.get("user_list");
                    if (!uList.isEmpty() && uList.get(0).get("open_id") != null) {
                        String oid = String.valueOf(uList.get(0).get("open_id"));
                        log.info("📧 App 2 成功根据邮箱 [{}] 解析出 open_id: [{}]", email, oid);
                        return "open_id:" + oid;
                    }
                }
            } else {
                log.warn("App 2 根据邮箱解析 open_id 返回: {}", resp.getBody());
            }
        } catch (Exception e) {
            log.warn("App 2 根据邮箱解析 open_id 异常 (可能未开通/发布 contact:user.id:readonly 权限): {}", e.getMessage());
        }

        // 2. 桥接降级：使用 App 1 (已有成熟通讯录权限) 查跨应用通用的 union_id
        try {
            String lookupUrl = FEISHU_HOST + "/contact/v3/users/batch_get_id?user_id_type=union_id";
            HttpHeaders headers = createAuthHeaders(getTenantAccessToken());
            Map<String, Object> body = Map.of("emails", List.of(email.trim()));
            ResponseEntity<Map> resp = restTemplate.postForEntity(lookupUrl, new HttpEntity<>(body, headers), Map.class);
            if (resp.getBody() != null && Integer.valueOf(0).equals(resp.getBody().get("code"))) {
                Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
                if (data != null && data.get("user_list") instanceof List) {
                    List<Map<String, Object>> uList = (List<Map<String, Object>>) data.get("user_list");
                    if (!uList.isEmpty() && uList.get(0).get("union_id") != null) {
                        String uid = String.valueOf(uList.get(0).get("union_id"));
                        log.info("🔗 App 1 成功根据邮箱 [{}] 桥接解析出跨应用通用 union_id: [{}]", email, uid);
                        return "union_id:" + uid;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("App 1 桥接解析 union_id 异常: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 通过 App 1 (已有成熟通讯录权限) 将 App 1 的 open_id 转换为全企业通用的 union_id
     */
    public String resolveUnionIdByApp1OpenId(String app1OpenId) {
        if (app1OpenId == null || !app1OpenId.startsWith("ou_")) return null;
        try {
            String token = getTenantAccessToken(); // 使用 App 1 的 Token
            String url = FEISHU_HOST + "/contact/v3/users/" + app1OpenId + "?user_id_type=open_id";
            HttpHeaders headers = createAuthHeaders(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (resp.getBody() != null && Integer.valueOf(0).equals(resp.getBody().get("code"))) {
                Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
                if (data != null && data.get("user") instanceof Map) {
                    Map<String, Object> user = (Map<String, Object>) data.get("user");
                    String unionId = String.valueOf(user.getOrDefault("union_id", ""));
                    if (!unionId.isBlank() && !"null".equalsIgnoreCase(unionId)) {
                        return unionId.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("通过 App 1 获取 union_id 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构建飞书官方标准审批 AppLink 跳转链接（兼容 PC 端与移动端）
     * 飞书内置审批应用固定 appId 为 cli_9cb844403dbb9108
     */
    private String buildApprovalAppLink(String instanceCode) {
        String code = instanceCode != null ? instanceCode.trim() : "";
        // 只有纯虚拟保留词 ("sample", "mock") 或空字符串才降级打开审批列表总览页
        boolean isMockOrSample = code.isEmpty()
                || "mock".equalsIgnoreCase(code)
                || "sample".equalsIgnoreCase(code)
                || "sample-role".equalsIgnoreCase(code);
        if (isMockOrSample) {
            // 本地虚拟测试词：直接唤起飞书客户端打开「审批中心 - 待办审批」列表页
            return "https://applink.feishu.cn/client/mini_program/open?appId=cli_9cb844403dbb9108"
                    + "&path_pc=pc%2Fpages%2Fin-process%2Findex"
                    + "&path=pages%2Fapproval-list%2Findex%3FselectIndex%3D0";
        }
        String encodedId = java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);
        String pcPath = java.net.URLEncoder.encode("pc/pages/in-process/index?instanceId=" + encodedId, java.nio.charset.StandardCharsets.UTF_8);
        String mobilePath = java.net.URLEncoder.encode("pages/detail/index?instanceId=" + encodedId, java.nio.charset.StandardCharsets.UTF_8);
        return "https://applink.feishu.cn/client/mini_program/open?appId=cli_9cb844403dbb9108"
                + "&path_pc=" + pcPath
                + "&path=" + mobilePath;
    }
}
