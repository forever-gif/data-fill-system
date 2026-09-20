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

    // 内存缓存 tenant_access_token 及过期时间
    private String cachedToken = null;
    private long tokenExpireTime = 0;

    /**
     * 手动失效 Token 缓存 (权限变更时使用)
     */
    public synchronized void invalidateToken() {
        this.cachedToken = null;
        this.tokenExpireTime = 0;
        log.info("已手动清空飞书 tenant_access_token 内存缓存");
    }

    /**
     * 获取租户访问凭证 (默认读取 application.yml 配置)
     */
    public synchronized String getTenantAccessToken() {
        return getTenantAccessToken(null, null);
    }

    /**
     * 获取租户访问凭证 (tenant_access_token)
     */
    public synchronized String getTenantAccessToken(String customAppId, String customAppSecret) {
        String appId = (customAppId != null && !customAppId.isBlank()) ? customAppId : feishuProperties.getAppId();
        String appSecret = (customAppSecret != null && !customAppSecret.isBlank()) ? customAppSecret : feishuProperties.getAppSecret();

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank() || appId.startsWith("cli_a1b2c3d4e5f6")) {
            throw new IllegalArgumentException("未配置有效的飞书 App ID 与 App Secret，请在配置中填入真实的飞书应用凭证。");
        }

        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireTime - 60000) {
            return cachedToken;
        }

        String url = FEISHU_HOST + "/auth/v3/tenant_access_token/internal";
        Map<String, String> body = Map.of(
                "app_id", appId.trim(),
                "app_secret", appSecret.trim()
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
                    this.cachedToken = (String) respBody.get("tenant_access_token");
                    int expireSeconds = (Integer) respBody.getOrDefault("expire", 7200);
                    this.tokenExpireTime = now + (expireSeconds * 1000L);
                    log.info("成功获取飞书 tenant_access_token，有效期: {} 秒", expireSeconds);
                    return this.cachedToken;
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
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        log.error("连续 3 次请求飞书获取 token 失败: {}", lastException != null ? lastException.getMessage() : "null");
        throw new RuntimeException("请求飞书 API 失败: " + (lastException != null ? lastException.getMessage() : "网络握手异常"), lastException);
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

    private HttpHeaders createAuthHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set(HttpHeaders.CONNECTION, "keep-alive");
        return headers;
    }
}
