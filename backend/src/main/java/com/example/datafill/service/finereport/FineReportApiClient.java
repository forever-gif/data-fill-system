package com.example.datafill.service.finereport;

import com.example.datafill.config.FineReportProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.util.*;

/**
 * 帆软 FineReport 11.5.6 决策系统 OpenAPI 通讯客户端
 * 负责管理员 Token 鉴权、用户清单与角色字典拉取、用户自定义角色批量维护
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FineReportApiClient {

    private final FineReportProperties fineReportProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate = createPatchSupportedRestTemplate();

    // 内存缓存 fine_auth_token
    private String cachedToken = null;
    private long tokenExpireTime = 0;
    private final List<String> sessionCookies = new java.util.concurrent.CopyOnWriteArrayList<>();

    // 内存缓存组织架构部门与职位 ID 字典 (支持部门自动同步)
    private final Map<String, List<String>> deptToPostIdsCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> deptToFullNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 登记部门与职务 ID 的缓存映射 (同时建立全称、去除序号简称等多索引)
     */
    public void cacheDeptInfo(String fullName, List<String> ids) {
        if (fullName == null || fullName.isBlank() || ids == null || ids.isEmpty()) return;
        String fn = fullName.trim();
        deptToFullNameCache.put(fn.toLowerCase(), fn);
        deptToPostIdsCache.put(fn.toLowerCase(), ids);

        // 去除数字序号前缀，如 "16.其它部门" -> "其它部门"
        String clean = fn.replaceAll("^\\d+[.、_\\s-]*", "").trim();
        if (!clean.isBlank()) {
            deptToFullNameCache.put(clean.toLowerCase(), fn);
            deptToPostIdsCache.put(clean.toLowerCase(), ids);
        }

        // 拆分横杠职位，如 "运营二部-员工" -> "运营二部"
        for (String sep : List.of("-", "/", " ")) {
            if (clean.contains(sep)) {
                String dOnly = clean.split(sep)[0].trim();
                if (!dOnly.isBlank()) {
                    deptToFullNameCache.put(dOnly.toLowerCase(), fn);
                    deptToPostIdsCache.put(dOnly.toLowerCase(), ids);
                }
            }
        }
    }

    /**
     * 递归扫描帆软组织架构树，提取所有部门及职务节点的 ID 映射
     */
    private void scanDeptTreeRecursively(JsonNode node, String parentName) {
        if (node == null || node.isNull()) return;

        if (node.isArray()) {
            for (JsonNode child : node) {
                scanDeptTreeRecursively(child, parentName);
            }
        } else if (node.isObject()) {
            String id = node.path("id").asText(node.path("departmentPostId").asText(node.path("deptId").asText("")));
            String name = node.path("text").asText(node.path("name").asText(node.path("departmentName").asText("")));

            if (!name.isBlank() && !id.isBlank()) {
                String fullName = parentName.isBlank() ? name : parentName + "-" + name;
                cacheDeptInfo(name, List.of(id));
                if (!parentName.isBlank()) {
                    cacheDeptInfo(fullName, List.of(id));
                }
            }

            String nextParent = !name.isBlank() ? name : parentName;
            List<String> childKeys = List.of("children", "posts", "items", "subDepartments", "departmentPosts", "depPosts");
            for (String ck : childKeys) {
                if (node.has(ck) && node.get(ck).isArray()) {
                    scanDeptTreeRecursively(node.get(ck), nextParent);
                }
            }
        }
    }

    /**
     * 探测拉取帆软所有部门与职务架构字典
     */
    public synchronized void loadAllDepartmentsAndPosts() {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();
        HttpHeaders headers = createAuthHeaders(token);

        List<String> urls = List.of(
                appendAuthParam(baseUrl + "/v10/departments/decision-dep-root", token),
                appendAuthParam(baseUrl + "/v10/departments", token),
                appendAuthParam(baseUrl + "/v10/dep/posts", token),
                appendAuthParam(baseUrl + "/v10/department/posts", token),
                appendAuthParam(baseUrl + "/v10/departments/all", token),
                appendAuthParam(baseUrl + "/v10/org/departments", token)
        );

        for (String url : urls) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    String body = resp.getBody().trim();
                    if (!body.startsWith("<")) {
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode data = root.has("data") ? root.get("data") : root;
                        scanDeptTreeRecursively(data, "");
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 智能打分匹配最佳部门及职位节点（严格防止普通员工被错误赋予主管/经理等职务）
     */
    public Map.Entry<String, List<String>> resolveBestDepartment(String targetDepartment) {
        if (targetDepartment == null || targetDepartment.isBlank()) return null;
        if (deptToPostIdsCache.isEmpty()) {
            loadAllDepartmentsAndPosts();
        }

        String targetClean = targetDepartment.replaceAll("^\\d+[.、_\\s-]*", "").trim().toLowerCase();
        boolean targetSpecifiesPost = targetClean.contains("主管") || targetClean.contains("经理") ||
                targetClean.contains("总监") || targetClean.contains("专员") || targetClean.contains("组长") ||
                targetClean.contains("助理") || targetClean.contains("员工") || targetClean.contains("负责人") ||
                targetClean.contains("副理") || targetClean.contains("部长");

        String bestCandidate = null;
        int bestScore = -999;

        for (String candidateKey : deptToPostIdsCache.keySet()) {
            String fullCandidateName = deptToFullNameCache.getOrDefault(candidateKey, candidateKey);
            String[] segments = fullCandidateName.split("[-/]");
            String leaf = segments[segments.length - 1].replaceAll("^\\d+[.、_\\s-]*", "").trim().toLowerCase();

            int score = 0;
            boolean candidateHasPost = candidateKey.contains("主管") || candidateKey.contains("经理") ||
                    candidateKey.contains("总监") || candidateKey.contains("专员") || candidateKey.contains("组长") ||
                    candidateKey.contains("助理") || candidateKey.contains("员工") || candidateKey.contains("负责人") ||
                    candidateKey.contains("副理") || candidateKey.contains("部长");

            // 1. 叶子节点精准匹配 (例如 candidate 是 "01.北美事业部-运营二部", leaf 就是 "运营二部", 完全等于 targetClean)
            if (leaf.equals(targetClean)) {
                score += 200;
            } else if (candidateKey.equals(targetClean)) {
                score += 180;
            } else if (leaf.contains(targetClean) || targetClean.contains(leaf)) {
                score += 80;
            } else if (candidateKey.contains(targetClean)) {
                score += 50;
            } else {
                continue;
            }

            // 2. 职务惩罚机制：如果表格里没写职务，但候选节点是职务（主管、经理等），大幅扣分，严禁误升官！
            if (!targetSpecifiesPost && candidateHasPost) {
                score -= 150;
            } else if (targetSpecifiesPost && candidateHasPost) {
                score += 50;
            }

            // 3. 纯部门节点加分 (不带任何职位后缀的优先)
            if (!candidateHasPost) {
                score += 30;
            }

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidateKey;
            }
        }

        if (bestCandidate != null && bestScore > 0) {
            String realFullName = deptToFullNameCache.getOrDefault(bestCandidate, bestCandidate);
            List<String> ids = deptToPostIdsCache.get(bestCandidate);
            log.info("🏢 【部门智能匹配胜出】目标=[{}], 命中节点=[{}], 得分={}, IDs={}",
                    targetDepartment, realFullName, bestScore, ids);
            return Map.entry(realFullName, ids);
        }

        return null;
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
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }

    @Data
    public static class FineReportRole {
        private String id;
        private String name;
        private String description;
    }

    @Data
    public static class FineReportUser {
        private String id;
        private String username;
        private String realName;
        private String mobile;
        private String email;
        private String department; // 帆软当前所属机构/部门 (如 14.人力资源部)
        private Set<String> customRoleIds = new HashSet<>();
        private Set<String> customRoleNames = new HashSet<>();
    }

    /**
     * 清理本地 Token 缓存
     */
    public synchronized void invalidateToken() {
        this.cachedToken = null;
        this.tokenExpireTime = 0;
        this.sessionCookies.clear();
    }

    public String getCleanBaseUrl() {
        String url = fineReportProperties.getBaseUrl();
        if (url == null) return "";
        return url.replaceAll("[;；\\s/]+$", "").trim();
    }

    private String appendAuthParam(String url, String token) {
        if (url == null) return "";
        if (token == null || token.isBlank()) return url;
        String sep = url.contains("?") ? "&" : "?";
        try {
            return url + sep + "fine_auth_token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return url + sep + "fine_auth_token=" + token;
        }
    }

    public synchronized String getAuthToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireTime - 60000) {
            return cachedToken;
        }

        String username = fineReportProperties.getAdminUsername();
        String password = fineReportProperties.getAdminPassword();

        if (password == null || password.isBlank()) {
            throw new RuntimeException("帆软管理员密码未填写，请点击页面右上角【帆软连接配置】输入密码并保存");
        }

        String baseUrl = getCleanBaseUrl();
        Exception lastException = null;

        // 策略 1：帆软官方最通用跨域登录端点 (GET /login/cross/domain)
        try {
            String crossDomainUrl = baseUrl + "/login/cross/domain?fine_username="
                    + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                    + "&fine_password="
                    + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                    + "&validity=-2";

            ResponseEntity<String> response = restTemplate.getForEntity(crossDomainUrl, String.class);
            String token = extractTokenFromResponse(response);
            if (token != null && !token.isBlank()) {
                this.cachedToken = token;
                this.tokenExpireTime = now + (2 * 3600 * 1000L);
                log.info("通过 cross/domain 端点成功获取帆软 fine_auth_token: {}...", token.substring(0, Math.min(8, token.length())));
                return this.cachedToken;
            }
        } catch (Exception e) {
            lastException = e;
            log.warn("尝试 cross/domain 登录端点未成功: {}", e.getMessage());
        }

        // 策略 2：POST /login (标准 JSON 登录)
        try {
            String loginUrl = baseUrl + "/login";
            Map<String, Object> body = new HashMap<>();
            body.put("username", username);
            body.put("password", password);
            body.put("validity", -2);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(loginUrl, entity, String.class);
            String token = extractTokenFromResponse(response);
            if (token != null && !token.isBlank()) {
                this.cachedToken = token;
                this.tokenExpireTime = now + (2 * 3600 * 1000L);
                log.info("通过 /login 端点成功获取帆软 fine_auth_token: {}...", token.substring(0, Math.min(8, token.length())));
                return this.cachedToken;
            }
        } catch (Exception e) {
            lastException = e;
            log.warn("尝试 /login 登录端点未成功: {}", e.getMessage());
        }

        // 策略 3：POST /v10/login (OpenAPI 登录端点)
        try {
            String v10LoginUrl = baseUrl + "/v10/login";
            Map<String, Object> body = new HashMap<>();
            body.put("username", username);
            body.put("password", password);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(v10LoginUrl, entity, String.class);
            String token = extractTokenFromResponse(response);
            if (token != null && !token.isBlank()) {
                this.cachedToken = token;
                this.tokenExpireTime = now + (2 * 3600 * 1000L);
                log.info("通过 /v10/login 端点成功获取帆软 fine_auth_token: {}...", token.substring(0, Math.min(8, token.length())));
                return this.cachedToken;
            }
        } catch (Exception e) {
            lastException = e;
        }

        log.error("帆软登录认证失败，所有端点均未返回有效 Token: {}", lastException != null ? lastException.getMessage() : "未知");
        throw new RuntimeException("帆软登录认证失败。请点击右上角【帆软连接配置】核对访问地址 (如 http://8.136.2.153:8081/webroot/decision) 与管理员账密: "
                + (lastException != null ? lastException.getMessage() : ""));
    }

    private String extractTokenFromResponse(ResponseEntity<String> response) {
        if (response == null) return null;

        // 1. 保存所有 Set-Cookie 供会话传递
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null && !cookies.isEmpty()) {
            this.sessionCookies.clear();
            this.sessionCookies.addAll(cookies);
            for (String cookie : cookies) {
                if (cookie.contains("fine_auth_token=")) {
                    String tokenPart = cookie.split(";", 2)[0];
                    String token = tokenPart.replace("fine_auth_token=", "").trim();
                    if (isValidToken(token)) {
                        return token;
                    }
                }
            }
        }

        // 2. 从响应 Body 中提取 (兼容 accessToken / token / data 字符串)
        if (response.getBody() != null) {
            String body = response.getBody().trim();
            if (!body.startsWith("<")) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    if (root.has("data")) {
                        JsonNode dataNode = root.get("data");
                        if (dataNode.isTextual() && isValidToken(dataNode.asText())) return dataNode.asText();
                        if (dataNode.has("accessToken") && isValidToken(dataNode.get("accessToken").asText())) return dataNode.get("accessToken").asText();
                        if (dataNode.has("token") && isValidToken(dataNode.get("token").asText())) return dataNode.get("token").asText();
                    }
                    if (root.has("accessToken") && isValidToken(root.get("accessToken").asText())) return root.get("accessToken").asText();
                    if (root.has("token") && isValidToken(root.get("token").asText())) return root.get("token").asText();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        token = token.trim();
        if (token.equals("\"\"") || token.equals("''") || token.equalsIgnoreCase("null")) return false;
        return token.length() >= 8 && !token.startsWith("<") && !token.contains("html") && !token.contains("{");
    }

    /**
     * 测试与帆软决策系统连接
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> res = new HashMap<>();
        try {
            invalidateToken(); // 测试连接时强制重新换取 Token
            String token = getAuthToken();
            // 立即尝试拉取一次角色以验证 Token 是否真正生效且具备接口权限
            List<FineReportRole> roles = getCustomRoles();
            res.put("success", true);
            res.put("message", "帆软 11.5.6 API 连通成功！已成功读取到 " + roles.size() + " 个角色。");
            res.put("tokenPrefix", token.length() > 8 ? token.substring(0, 8) + "..." : token);
            res.put("baseUrl", getCleanBaseUrl());
            return res;
        } catch (Exception e) {
            res.put("success", false);
            res.put("message", "连接测试失败: " + e.getMessage());
            res.put("baseUrl", getCleanBaseUrl());
            return res;
        }
    }

    /**
     * 获取全量自定义角色列表 (名称 -> ID 映射)
     * 支持所有可能的 JSON 结构 (data.roles, data.items, data 数组, 纯字符串数组等) 及分页探测
     */
    public List<FineReportRole> getCustomRoles() {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();

        List<FineReportRole> roleList = new ArrayList<>();
        Set<String> seenRoleNames = new HashSet<>();

        List<String> endpoints = List.of(
                baseUrl + "/v10/roles",
                baseUrl + "/v10/roles/custom",
                baseUrl + "/v10/custom/roles",
                baseUrl + "/v10/role",
                baseUrl + "/v10/roles/all",
                baseUrl + "/v10/role/custom/roles",
                baseUrl + "/v10/authority/roles",
                baseUrl + "/v10/privilege/roles"
        );

        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (String baseEndpoint : endpoints) {
            List<String> tryUrls = List.of(
                    appendAuthParam(baseEndpoint, token),
                    appendAuthParam(baseEndpoint + "?page=1&count=1000", token),
                    appendAuthParam(baseEndpoint + "?page=1&size=1000", token),
                    appendAuthParam(baseEndpoint + "?page=1&pageSize=1000", token),
                    appendAuthParam(baseEndpoint + "?page=1&count=100", token)
            );

            for (String url : tryUrls) {
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        String body = resp.getBody().trim();
                        if (body.startsWith("<")) {
                            continue;
                        }

                        List<FineReportRole> parsed = parseRolesFromJson(body);
                        log.info("【角色接口测试】URL: {}, 状态: {}, 响应长度: {}, 解析角色数: {}",
                                url, resp.getStatusCode(), body.length(), parsed.size());

                        if (!parsed.isEmpty()) {
                            for (FineReportRole r : parsed) {
                                if (!seenRoleNames.contains(r.getName())) {
                                    seenRoleNames.add(r.getName());
                                    roleList.add(r);
                                }
                            }

                            // 继续探测后续页
                            fetchRemainingPagesIfPresent(baseEndpoint, token, headers, seenRoleNames, roleList);

                            log.info("成功从端点 {} 拉取全部角色，总计 {} 个角色", baseEndpoint, roleList.size());
                            return roleList;
                        } else {
                            log.warn("【角色接口返回无角色】URL: {}, 内容片段: {}", url, body.substring(0, Math.min(200, body.length())));
                        }
                    }
                } catch (Exception e) {
                    log.debug("尝试角色端点 {} 失败: {}", url, e.getMessage());
                }
            }
        }

        log.warn("所有候选角色端点均未解析出角色，当前获取角色数为 0");
        return roleList;
    }

    private void fetchRemainingPagesIfPresent(String baseEndpoint, String token, HttpHeaders headers,
                                              Set<String> seenRoleNames, List<FineReportRole> roleList) {
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        for (int p = 2; p <= 30; p++) {
            List<String> pageUrls = List.of(
                    appendAuthParam(baseEndpoint + "?page=" + p + "&count=1000", token),
                    appendAuthParam(baseEndpoint + "?page=" + p + "&count=100", token),
                    appendAuthParam(baseEndpoint + "?page=" + p + "&size=100", token),
                    appendAuthParam(baseEndpoint + "?page=" + p, token)
            );

            boolean gotNewRoles = false;
            for (String pu : pageUrls) {
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(pu, HttpMethod.GET, entity, String.class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        String b = resp.getBody().trim();
                        if (!b.startsWith("<")) {
                            List<FineReportRole> pRoles = parseRolesFromJson(b);
                            int added = 0;
                            for (FineReportRole r : pRoles) {
                                if (!seenRoleNames.contains(r.getName())) {
                                    seenRoleNames.add(r.getName());
                                    roleList.add(r);
                                    added++;
                                }
                            }
                            if (added > 0) {
                                gotNewRoles = true;
                                log.info("端点 {} 第 {} 页获取到 {} 个新角色", pu, p, added);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (!gotNewRoles) {
                break;
            }
        }
    }

    private List<FineReportRole> parseRolesFromJson(String jsonStr) {
        List<FineReportRole> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.isBlank() || jsonStr.startsWith("<")) return list;
        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode arrayNode = null;

            if (root.isArray()) {
                arrayNode = root;
            } else {
                List<String> arrayKeys = List.of(
                        "roles", "customRoles", "items", "roleList", "customRoleList",
                        "records", "list", "data", "rows", "result", "content"
                );

                if (root.has("data")) {
                    JsonNode data = root.get("data");
                    if (data.isArray()) {
                        arrayNode = data;
                    } else if (data.isObject()) {
                        for (String k : arrayKeys) {
                            if (data.has(k) && data.get(k).isArray()) {
                                arrayNode = data.get(k);
                                break;
                            }
                        }
                        if (arrayNode == null) {
                            Iterator<Map.Entry<String, JsonNode>> it = data.fields();
                            while (it.hasNext()) {
                                Map.Entry<String, JsonNode> entry = it.next();
                                if (entry.getValue().isArray() && entry.getValue().size() > 0) {
                                    arrayNode = entry.getValue();
                                    break;
                                }
                            }
                        }
                    }
                }

                if (arrayNode == null && root.isObject()) {
                    for (String k : arrayKeys) {
                        if (root.has(k) && root.get(k).isArray()) {
                            arrayNode = root.get(k);
                            break;
                        }
                    }
                    if (arrayNode == null) {
                        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                        while (it.hasNext()) {
                            Map.Entry<String, JsonNode> entry = it.next();
                            if (entry.getValue().isArray() && entry.getValue().size() > 0) {
                                arrayNode = entry.getValue();
                                break;
                            }
                        }
                    }
                }
            }

            if (arrayNode != null && arrayNode.isArray()) {
                for (JsonNode item : arrayNode) {
                    String rName = "";
                    String rId = "";
                    String rDesc = "";

                    if (item.isTextual()) {
                        rName = item.asText().trim();
                        rId = rName;
                    } else if (item.isObject()) {
                        List<String> nameKeys = List.of(
                                "name", "roleName", "role", "displayName", "text", "customRoleName",
                                "title", "label", "role_name", "custom_role_name"
                        );
                        for (String nk : nameKeys) {
                            if (item.has(nk) && !item.get(nk).isNull()) {
                                String val = item.get(nk).asText().trim();
                                if (!val.isBlank() && !val.equalsIgnoreCase("null")) {
                                    rName = val;
                                    break;
                                }
                            }
                        }
                        List<String> idKeys = List.of(
                                "id", "roleId", "customRoleId", "code", "value", "key", "role_id"
                        );
                        for (String ik : idKeys) {
                            if (item.has(ik) && !item.get(ik).isNull()) {
                                String val = item.get(ik).asText().trim();
                                if (!val.isBlank()) {
                                    rId = val;
                                    break;
                                }
                            }
                        }
                        rDesc = item.path("description").asText("");
                    }

                    if (!rName.isBlank()) {
                        FineReportRole r = new FineReportRole();
                        r.setId(rId.isBlank() ? rName : rId);
                        r.setName(rName);
                        r.setDescription(rDesc);
                        list.add(r);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析角色 JSON 异常: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 获取帆软决策系统中的所有用户列表
     * 全面支持各种角色字段命名（roles, customRoles, roleList 等）、分号/逗号字符串、对象/字符串数组
     */
    public Map<String, FineReportUser> getAllUsers() {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();

        List<String> userEndpoints = List.of(
                appendAuthParam(baseUrl + "/v10/users?page=1&count=5000", token),
                appendAuthParam(baseUrl + "/v10/users?page=1&size=5000", token),
                appendAuthParam(baseUrl + "/v10/users", token)
        );

        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Map<String, FineReportUser> userMap = new HashMap<>();
        Exception lastException = null;

        for (String url : userEndpoints) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String body = response.getBody().trim();
                    if (body.startsWith("<")) {
                        log.warn("帆软用户接口返回了 HTML 页面 (重定向至登录页): {}", url);
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(body);
                    JsonNode dataNode = root.has("data") ? root.get("data") : root;
                    if (dataNode.has("items")) {
                        dataNode = dataNode.get("items");
                    }
                    if (dataNode.isArray()) {
                        for (JsonNode item : dataNode) {
                            FineReportUser user = new FineReportUser();
                            user.setId(item.path("id").asText(item.path("userId").asText("")));
                            user.setUsername(item.path("username").asText(item.path("userName").asText("")));
                            user.setRealName(item.path("realName").asText(item.path("name").asText("")));
                            user.setMobile(item.path("mobile").asText(""));
                            user.setEmail(item.path("email").asText(""));
                            user.setDepartment(extractDepartmentFromUserNode(item));
                            extractRolesFromUserNode(item, user);

                            // 动态提取并缓存部门职务 ID 映射关系
                            String dpNames = item.path("depPostNames").asText("").trim();
                            if (dpNames.isBlank()) dpNames = user.getDepartment();
                            if (item.has("departmentPostIds") && item.get("departmentPostIds").isArray()) {
                                List<String> dpIds = new ArrayList<>();
                                for (JsonNode idNode : item.get("departmentPostIds")) {
                                    if (idNode.isTextual() && !idNode.asText().isBlank()) {
                                        dpIds.add(idNode.asText().trim());
                                    }
                                }
                                if (!dpNames.isBlank() && !dpIds.isEmpty()) {
                                    cacheDeptInfo(dpNames, dpIds);
                                }
                            }

                            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                                if (user.getUsername().equalsIgnoreCase("donghao-he")) {
                                    log.info("【帆软用户 donghao-he 节点识别】部门={}, 角色数={}, 角色列表={}, 原始JSON: {}",
                                            user.getDepartment(), user.getCustomRoleNames().size(), user.getCustomRoleNames(), item.toString());
                                }
                                userMap.put(user.getUsername().toLowerCase().trim(), user);
                            }
                        }
                        if (!userMap.isEmpty()) {
                            log.info("成功拉取帆软用户列表，共 {} 位用户", userMap.size());
                            return userMap;
                        }
                    }
                }
            } catch (Exception e) {
                lastException = e;
            }
        }

        if (userMap.isEmpty()) {
            invalidateToken();
            log.error("获取帆软用户列表失败: {}", lastException != null ? lastException.getMessage() : "所有端点均未返回有效用户数据");
            throw new RuntimeException("获取帆软用户列表失败，接口返回非预期格式。请核对地址与账密。");
        }
        return userMap;
    }

    /**
     * 判断文本是否是 UUID 或纯技术哈希 ID
     */
    public static boolean isUuidOrTechnicalId(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        // 标准 UUID (8-4-4-4-12)
        if (trimmed.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            return true;
        }
        // 20位以上的纯十六进制/技术ID
        if (trimmed.matches("^[0-9a-fA-F-]{20,}$")) {
            return true;
        }
        // 长无中文的技术串 (24位以上且不含业务词汇)
        if (trimmed.length() >= 24 && trimmed.matches("^[0-9a-zA-Z_-]+$")
                && !trimmed.contains("权限") && !trimmed.contains("部") && !trimmed.contains("角色")) {
            return true;
        }
        return false;
    }

    /**
     * 从用户 JSON 节点中全方位挖掘角色信息
     */
    private void extractRolesFromUserNode(JsonNode item, FineReportUser user) {
        if (item == null) return;

        // 1. 专门的 ID 字段，纯提取到 customRoleIds，绝不污染 customRoleNames
        List<String> idKeys = List.of(
                "customRoleIds", "roleIds", "customRoleIdList", "roleIdList", "userRoleIds"
        );
        for (String key : idKeys) {
            if (item.has(key) && !item.get(key).isNull()) {
                JsonNode idNode = item.get(key);
                if (idNode.isTextual()) {
                    String val = idNode.asText().trim();
                    if (!val.isBlank()) user.getCustomRoleIds().add(val);
                } else if (idNode.isArray()) {
                    for (JsonNode elem : idNode) {
                        if (elem.isTextual()) {
                            String val = elem.asText().trim();
                            if (!val.isBlank()) user.getCustomRoleIds().add(val);
                        }
                    }
                }
            }
        }

        // 2. 角色对象/名称字段
        List<String> possibleRoleKeys = List.of(
                "customRoles", "roles", "customRoleList", "roleList",
                "customRoleNames", "roleNames",
                "customRole", "role", "userRoles", "positions", "posts"
        );

        for (String key : possibleRoleKeys) {
            if (item.has(key) && !item.get(key).isNull()) {
                parseRoleNodeIntoUser(item.get(key), user);
            }
        }
    }

    private void parseRoleNodeIntoUser(JsonNode rNode, FineReportUser user) {
        if (rNode == null || rNode.isNull()) return;

        if (rNode.isTextual()) {
            // 文本字符串：可能是分号或逗号分隔，如 "大洲权限_北美;BU权限_北美事业部"
            String text = rNode.asText().trim();
            if (!text.isBlank() && !text.equalsIgnoreCase("null")) {
                for (String part : text.split("[;；,，\n]+")) {
                    String clean = part.trim();
                    if (!clean.isBlank()) {
                        if (isUuidOrTechnicalId(clean)) {
                            user.getCustomRoleIds().add(clean);
                        } else {
                            user.getCustomRoleNames().add(clean);
                        }
                    }
                }
            }
        } else if (rNode.isArray()) {
            for (JsonNode elem : rNode) {
                if (elem.isTextual()) {
                    // 字符串数组：["大洲权限_北美", "BU权限_北美事业部"] 或 ID 列表
                    String text = elem.asText().trim();
                    if (!text.isBlank()) {
                        if (isUuidOrTechnicalId(text)) {
                            user.getCustomRoleIds().add(text);
                        } else {
                            user.getCustomRoleNames().add(text);
                        }
                    }
                } else if (elem.isObject()) {
                    // 对象数组：[{"id": "...", "name": "..."}, ...]
                    String id = elem.path("id").asText(elem.path("roleId").asText(elem.path("customRoleId").asText("")));
                    String name = elem.path("name").asText(elem.path("roleName").asText(elem.path("customRoleName").asText(elem.path("displayName").asText(elem.path("text").asText("")))));
                    if (name.isBlank() && elem.has("role")) {
                        JsonNode inner = elem.get("role");
                        name = inner.path("name").asText(inner.path("roleName").asText(inner.path("text").asText("")));
                        if (id.isBlank()) id = inner.path("id").asText("");
                    }
                    if (name.isBlank() && elem.has("customRole")) {
                        JsonNode inner = elem.get("customRole");
                        name = inner.path("name").asText(inner.path("roleName").asText(inner.path("text").asText("")));
                        if (id.isBlank()) id = inner.path("id").asText("");
                    }

                    if (!id.isBlank()) user.getCustomRoleIds().add(id);
                    if (!name.isBlank()) {
                        if (isUuidOrTechnicalId(name)) {
                            user.getCustomRoleIds().add(name);
                        } else {
                            user.getCustomRoleNames().add(name);
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断文本是否像部门名称（过滤掉包含“权限”的角色名及 UUID）
     */
    public static boolean isLikelyDepartmentName(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        if (isUuidOrTechnicalId(t)) return false;
        if (t.contains("权限") || t.contains("role") || t.contains("Role") || t.contains("token") || t.contains("http")) return false;
        if (t.length() > 40) return false;
        // 含有常见部门词汇 (如 14.人力资源部, 运营二部, 财务中心, 供应链组)
        if (t.contains("部") || t.contains("中心") || t.contains("组") || t.contains("室")
                || t.contains("院") || t.contains("处") || t.contains("团队") || t.contains("公司")) {
            return true;
        }
        // 数字编号开头格式，如 14.人力资源部
        if (t.matches("^\\d+[.、_\\s-].+")) {
            return true;
        }
        return false;
    }

    private String extractNameFromDeptObject(JsonNode d) {
        if (d == null || !d.isObject()) return "";
        List<String> keys = List.of("text", "name", "departmentName", "deptName", "depName", "displayName", "title", "label");
        for (String k : keys) {
            if (d.has(k) && d.get(k).isTextual()) {
                String val = d.get(k).asText().trim();
                if (!val.isBlank() && isLikelyDepartmentName(val)) {
                    return val;
                }
            }
        }
        return "";
    }

    private void findDepartmentNamesRecursively(JsonNode node, Set<String> result) {
        if (node == null || node.isNull()) return;

        if (node.isTextual()) {
            String val = node.asText().trim();
            if (isLikelyDepartmentName(val)) {
                result.add(val);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                findDepartmentNamesRecursively(child, result);
            }
        } else if (node.isObject()) {
            // 优先从对象字段名包含 dept/depart/dep/org 的字段中提取
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String field = entry.getKey().toLowerCase();
                JsonNode valNode = entry.getValue();

                if (field.contains("dept") || field.contains("depart") || field.contains("dep") || field.contains("org") || field.contains("post")) {
                    if (valNode.isTextual()) {
                        String text = valNode.asText().trim();
                        if (isLikelyDepartmentName(text)) {
                            result.add(text);
                            continue;
                        }
                    } else if (valNode.isObject()) {
                        String name = extractNameFromDeptObject(valNode);
                        if (!name.isBlank()) {
                            result.add(name);
                            continue;
                        }
                    }
                }
                findDepartmentNamesRecursively(valNode, result);
            }
        }
    }

    /**
     * 从用户 JSON 节点中全方位挖掘所属部门/机构名称 (支持 department, deptName, depPosts 等各形态)
     */
    private String extractDepartmentFromUserNode(JsonNode item) {
        if (item == null || item.isNull()) return "";

        // 1. 扁平字符串/对象字段
        List<String> textKeys = List.of(
                "departmentName", "deptName", "department", "dept", "orgName", "organization", "depName", "depart", "dep"
        );
        for (String k : textKeys) {
            if (item.has(k) && !item.get(k).isNull()) {
                JsonNode n = item.get(k);
                if (n.isTextual() && !n.asText().isBlank() && isLikelyDepartmentName(n.asText())) {
                    return n.asText().trim();
                } else if (n.isObject()) {
                    String name = extractNameFromDeptObject(n);
                    if (!name.isBlank()) return name;
                }
            }
        }

        // 2. departments / depts 数组
        for (String arrKey : List.of("departments", "depts", "orgs")) {
            if (item.has(arrKey) && item.get(arrKey).isArray()) {
                List<String> depts = new ArrayList<>();
                for (JsonNode d : item.get(arrKey)) {
                    if (d.isTextual() && !d.asText().isBlank() && isLikelyDepartmentName(d.asText())) {
                        depts.add(d.asText().trim());
                    } else if (d.isObject()) {
                        String name = extractNameFromDeptObject(d);
                        if (!name.isBlank()) depts.add(name);
                    }
                }
                if (!depts.isEmpty()) {
                    return String.join(";", depts);
                }
            }
        }

        // 3. depPosts / posts 部门职务数组 (如 [{"department": {"text": "14.人力资源部"}}, {"post": "..."}])
        for (String pk : List.of("depPosts", "posts", "departmentPosts", "userDepPosts", "positionList", "depAndPosts")) {
            if (item.has(pk) && item.get(pk).isArray()) {
                List<String> depts = new ArrayList<>();
                for (JsonNode p : item.get(pk)) {
                    if (p.has("department")) {
                        JsonNode d = p.get("department");
                        if (d.isTextual() && !d.asText().isBlank() && isLikelyDepartmentName(d.asText())) {
                            depts.add(d.asText().trim());
                        } else if (d.isObject()) {
                            String name = extractNameFromDeptObject(d);
                            if (!name.isBlank()) depts.add(name);
                        }
                    } else if (p.has("dep")) {
                        JsonNode d = p.get("dep");
                        if (d.isTextual() && !d.asText().isBlank() && isLikelyDepartmentName(d.asText())) {
                            depts.add(d.asText().trim());
                        } else if (d.isObject()) {
                            String name = extractNameFromDeptObject(d);
                            if (!name.isBlank()) depts.add(name);
                        }
                    } else {
                        String name = extractNameFromDeptObject(p);
                        if (!name.isBlank()) depts.add(name);
                    }
                }
                if (!depts.isEmpty()) {
                    return String.join(";", depts);
                }
            }
        }

        // 4. 深度递归兜底扫描整个节点树（确保任何嵌套如 14.人力资源部 绝不漏网）
        Set<String> recursiveDepts = new LinkedHashSet<>();
        findDepartmentNamesRecursively(item, recursiveDepts);
        if (!recursiveDepts.isEmpty()) {
            return String.join(";", recursiveDepts);
        }

        return "";
    }

    /**
     * 定向拉取并补全特定用户的角色与部门详情
     */
    public void enrichUserDetailDirectly(FineReportUser user) {
        if (user == null || user.getId() == null || user.getId().isBlank()) return;
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();

        List<String> urls = List.of(
                appendAuthParam(baseUrl + "/v10/users/" + user.getId(), token),
                appendAuthParam(baseUrl + "/v10/users/" + user.getId() + "/dep/posts", token),
                appendAuthParam(baseUrl + "/v10/users/" + user.getId() + "/departments", token),
                appendAuthParam(baseUrl + "/v10/users/" + user.getId() + "/positions", token),
                appendAuthParam(baseUrl + "/v10/users/" + user.getId() + "/roles", token),
                appendAuthParam(baseUrl + "/v10/users/" + user.getId() + "/custom/roles", token)
        );

        HttpHeaders headers = createAuthHeaders(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (String u : urls) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(u, HttpMethod.GET, entity, String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    String body = resp.getBody().trim();
                    if (!body.startsWith("<")) {
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode data = root.has("data") ? root.get("data") : root;
                        extractRolesFromUserNode(data, user);
                        if (user.getDepartment() == null || user.getDepartment().isBlank()) {
                            String dept = extractDepartmentFromUserNode(data);
                            if (!dept.isBlank()) user.setDepartment(dept);
                        }
                        if (data.has("items") && data.get("items").isArray()) {
                            for (JsonNode it : data.get("items")) {
                                extractRolesFromUserNode(it, user);
                                if (user.getDepartment() == null || user.getDepartment().isBlank()) {
                                    String dept = extractDepartmentFromUserNode(it);
                                    if (!dept.isBlank()) user.setDepartment(dept);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 定向拉取特定用户的角色集合（兜底保障：当批量用户接口中缺失某些用户的角色详情时直接调用此接口）
     */
    public Set<String> fetchUserRolesDirectly(String userId) {
        Set<String> roles = new HashSet<>();
        if (userId == null || userId.isBlank()) return roles;
        FineReportUser dummy = new FineReportUser();
        dummy.setId(userId);
        enrichUserDetailDirectly(dummy);
        return dummy.getCustomRoleNames();
    }

    private boolean isValidApiResponse(String body) {
        if (body == null) return true;
        String b = body.trim();
        return !b.startsWith("<") && !b.contains("Failed to convert") && !b.contains("NumberFormatException");
    }

    /**
     * 获取特定用户的原始详情数据 (从 /v10/users?keyword={username} 精准提取，与前端 F12 抓包 100% 对齐)
     */
    public Map<String, Object> fetchUserRawData(String userId, String username) {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();
        HttpHeaders headers = createAuthHeaders(token);

        List<String> keywords = new ArrayList<>();
        if (username != null && !username.isBlank()) keywords.add(username.trim());
        if (userId != null && !userId.isBlank()) keywords.add(userId.trim());

        for (String kw : keywords) {
            try {
                String searchUrl = appendAuthParam(baseUrl + "/v10/users?page=1&count=10&keyword=" +
                        java.net.URLEncoder.encode(kw, java.nio.charset.StandardCharsets.UTF_8), token);
                ResponseEntity<String> resp = restTemplate.exchange(searchUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    JsonNode root = objectMapper.readTree(resp.getBody());
                    JsonNode data = root.has("data") ? root.get("data") : root;
                    JsonNode items = data.has("items") ? data.get("items") : data;
                    if (items.isArray()) {
                        for (JsonNode it : items) {
                            String uId = it.path("id").asText("");
                            String uName = it.path("username").asText("");
                            if (uId.equals(userId) || (username != null && uName.equalsIgnoreCase(username))) {
                                return objectMapper.convertValue(it, new TypeReference<Map<String, Object>>() {});
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("精准查询用户 [{}] 失败: {}", kw, e.getMessage());
            }
        }
        return Collections.emptyMap();
    }

    /**
     * 更新单个用户的自定义角色列表（重载方法 1）
     */
    public boolean updateUserCustomRoles(String userId, List<String> targetRoleIds) {
        return updateUserCustomRoles(userId, null, null, targetRoleIds, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 更新单个用户的自定义角色列表（重载方法 2）
     */
    public boolean updateUserCustomRoles(String userId, String username, List<String> targetRoleIds,
                                        List<String> addedRoleIds, List<String> removedRoleIds) {
        return updateUserCustomRoles(userId, username, null, targetRoleIds, Collections.emptyList(), addedRoleIds, removedRoleIds);
    }

    /**
     * 更新单个用户的自定义角色列表（重载方法 3）
     */
    public boolean updateUserCustomRoles(String userId, String username, List<String> targetRoleIds,
                                        List<String> targetRoleNames, List<String> addedRoleIds, List<String> removedRoleIds) {
        return updateUserCustomRoles(userId, username, null, targetRoleIds, targetRoleNames, addedRoleIds, removedRoleIds);
    }

    /**
     * 更新单个用户的自定义角色列表与所属部门职务（🌟 100% 完整复刻浏览器 F12 原生 PUT /v10/user 报文）
     */
    public boolean updateUserCustomRoles(String userId, String username, String targetDepartment,
                                        List<String> targetRoleIds, List<String> targetRoleNames,
                                        List<String> addedRoleIds, List<String> removedRoleIds) {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();
        HttpHeaders headers = createAuthHeaders(token);

        log.info("【开始执行用户角色与部门同步】用户={}, userId={}, 目标部门={}, 目标角色数={}, 目标角色列表={}",
                username, userId, targetDepartment, targetRoleIds != null ? targetRoleIds.size() : 0, targetRoleNames);

        // 🌟 策略 1（最高优先级！与浏览器 F12 抓包原生保存报文 100% 完全对齐）：
        // 抓包已实锤验证：帆软保存角色必须通过 PUT /v10/user，且必须包含 roleIds, roleNames, depPostNames, departmentPostIds 等完整参数
        try {
            Map<String, Object> rawUser = fetchUserRawData(userId, username);
            Map<String, Object> payload = new LinkedHashMap<>();

            // 1. 基础识别信息
            payload.put("id", userId);
            payload.put("username", username != null && !username.isBlank() ? username : (String) rawUser.getOrDefault("username", ""));
            payload.put("realName", rawUser.getOrDefault("realName", ""));
            payload.put("email", rawUser.getOrDefault("email", ""));
            payload.put("mobile", rawUser.getOrDefault("mobile", ""));

            // 2. 部门职务字段处理 (若传入了目标部门，则在缓存和组织架构树中匹配对应的 departmentPostIds)
            String currentDepNames = (String) rawUser.getOrDefault("depPostNames", "");
            Object currentDepIds = rawUser.getOrDefault("departmentPostIds", Collections.emptyList());

            if (targetDepartment != null && !targetDepartment.isBlank()) {
                Map.Entry<String, List<String>> bestDept = resolveBestDepartment(targetDepartment);

                if (bestDept != null && bestDept.getValue() != null && !bestDept.getValue().isEmpty()) {
                    payload.put("depPostNames", bestDept.getKey());
                    payload.put("departmentPostIds", bestDept.getValue());
                    log.info("🏢 【同步更新部门职务】用户 [{}]: 原部门 [{}] ➔ 目标部门 [{}] (ID: {})",
                            username, currentDepNames, payload.get("depPostNames"), bestDept.getValue());
                } else {
                    payload.put("depPostNames", currentDepNames);
                    payload.put("departmentPostIds", currentDepIds);
                    log.warn("⚠️ 【未找到部门 ID】未能在帆软组织架构中匹配到 [{}] 的 departmentPostId，为保障账号安全暂保留原部门 [{}]",
                            targetDepartment, currentDepNames);
                }
            } else {
                payload.put("depPostNames", currentDepNames);
                payload.put("departmentPostIds", currentDepIds);
            }

            // 3. 密码安全与系统属性
            payload.put("resetPassword", false);
            if (rawUser.containsKey("password") && rawUser.get("password") != null && !rawUser.get("password").toString().isBlank()) {
                payload.put("password", rawUser.get("password"));
            }

            // 4. 关键角色字段 (精准对齐抓包发现的 roleIds 数组与 roleNames 逗号拼接串)
            payload.put("roleIds", targetRoleIds != null ? targetRoleIds : Collections.emptyList());
            String roleNamesStr = (targetRoleNames != null && !targetRoleNames.isEmpty())
                    ? String.join(",", targetRoleNames) : "";
            payload.put("roleNames", roleNamesStr);

            String putUrl = appendAuthParam(baseUrl + "/v10/user", token);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            log.info("【调用帆软官方原生 PUT /v10/user 保存角色与部门】URL: {}, Payload: {}", putUrl, objectMapper.writeValueAsString(payload));
            ResponseEntity<String> resp = restTemplate.exchange(putUrl, HttpMethod.PUT, entity, String.class);
            log.info("【帆软原生保存角色与部门响应】状态: {}, 响应内容: {}", resp.getStatusCode(), resp.getBody());

            if (resp.getStatusCode().is2xxSuccessful() && isValidApiResponse(resp.getBody())) {
                log.info("🎉 成功通过帆软官方原生接口更新用户 [{}] 的角色 [{}] 及部门 [{}]",
                        username, roleNamesStr, payload.get("depPostNames"));
                return true;
            }
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.warn("【帆软原生 PUT /v10/user 失败】状态: {}, 响应: {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("【帆软原生 PUT /v10/user 执行异常】: {}", e.getMessage());
        }

        // 🌟 策略 2（容灾备份）：基于角色层级的 OpenAPI 增删绑定 (Role-Centric Sync)
        log.info("【转入策略 2 容灾执行】尝试角色成员直接绑定与解绑...");
        boolean roleCentricExecuted = false;
        boolean allRoleOpsOk = true;

        List<String> toAdd = (addedRoleIds != null && !addedRoleIds.isEmpty()) ? addedRoleIds : targetRoleIds;
        if (toAdd != null && !toAdd.isEmpty()) {
            roleCentricExecuted = true;
            for (String rid : toAdd) {
                if (rid != null && !rid.isBlank()) {
                    boolean ok = bindUserToRole(baseUrl, token, headers, rid, userId, username);
                    if (!ok) allRoleOpsOk = false;
                }
            }
        }

        if (removedRoleIds != null && !removedRoleIds.isEmpty()) {
            roleCentricExecuted = true;
            for (String rid : removedRoleIds) {
                if (rid != null && !rid.isBlank()) {
                    boolean ok = unbindUserFromRole(baseUrl, token, headers, rid, userId, username);
                    if (!ok) allRoleOpsOk = false;
                }
            }
        }

        if (roleCentricExecuted && allRoleOpsOk) {
            log.info("✅ 策略 2 角色层级接口成功执行用户 [{}] 的角色绑定与解绑！", username);
            return true;
        }

        log.warn("❌ 用户 [{}] 的角色更新策略执行完毕，未能成功写入帆软权限中", username);
        return false;
    }

    /**
     * 针对特定角色添加用户绑定 (全面覆盖 FineReport 10/11 各种端点、方法与参数结构)
     */
    public boolean bindUserToRole(String baseUrl, String token, HttpHeaders headers, String roleId, String userId, String username) {
        List<String> urls = List.of(
                appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users", token),
                appendAuthParam(baseUrl + "/v10/custom/roles/" + roleId + "/users", token),
                appendAuthParam(baseUrl + "/v10/role/" + roleId + "/users", token),
                appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/user", token),
                appendAuthParam(baseUrl + "/v10/role/" + roleId + "/user", token),
                appendAuthParam(baseUrl + "/v10/roles/users", token)
        );

        List<Object> bodies = new ArrayList<>();
        // 1. 标准 userIds 列表
        bodies.add(Map.of("userIds", List.of(userId)));
        // 2. 纯 ID 数组
        bodies.add(List.of(userId));
        // 3. 用户名模式
        if (username != null && !username.isBlank()) {
            bodies.add(Map.of("userNames", List.of(username)));
            bodies.add(Map.of("usernames", List.of(username)));
            bodies.add(List.of(username));
            bodies.add(Map.of("users", List.of(username)));
            bodies.add(Map.of("roleId", roleId, "userNames", List.of(username)));
        }
        // 4. users 列表 / 单对象
        bodies.add(Map.of("users", List.of(userId)));
        bodies.add(Map.of("id", userId));
        bodies.add(Map.of("userId", userId));
        bodies.add(Map.of("roleId", roleId, "userIds", List.of(userId)));

        // 尝试 POST 与 PUT 方法
        List<HttpMethod> methods = List.of(HttpMethod.POST, HttpMethod.PUT);

        for (String url : urls) {
            for (Object body : bodies) {
                for (HttpMethod method : methods) {
                    try {
                        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
                        ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
                        log.info("【角色绑定尝试响应】方法: {}, URL: {}, Body: {}, 状态: {}, 响应内容: {}",
                                method, url, body, resp.getStatusCode(), resp.getBody());
                        if (resp.getStatusCode().is2xxSuccessful() && isValidApiResponse(resp.getBody())) {
                            log.info("🎉 成功将用户 [{}] (ID: {}) 绑定至角色 [{}]！", username, userId, roleId);
                            return true;
                        }
                    } catch (org.springframework.web.client.HttpStatusCodeException e) {
                        log.info("【角色绑定端点返回错误】方法: {}, URL: {}, Body: {}, 状态: {}, 响应: {}",
                                method, url, body, e.getStatusCode(), e.getResponseBodyAsString());
                    } catch (Exception e) {
                        log.debug("角色绑定请求异常: {}", e.getMessage());
                    }
                }
            }
        }
        return false;
    }

    /**
     * 针对特定角色解绑用户
     */
    public boolean unbindUserFromRole(String baseUrl, String token, HttpHeaders headers, String roleId, String userId, String username) {
        List<String> deleteUrls = new ArrayList<>();
        deleteUrls.add(appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users/" + userId, token));
        if (username != null && !username.isBlank()) {
            deleteUrls.add(appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users/" + username, token));
        }
        deleteUrls.add(appendAuthParam(baseUrl + "/v10/role/" + roleId + "/users/" + userId, token));
        deleteUrls.add(appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/user/" + userId, token));
        deleteUrls.add(appendAuthParam(baseUrl + "/v10/custom/roles/" + roleId + "/users/" + userId, token));

        for (String url : deleteUrls) {
            try {
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
                log.info("【角色解绑 DELETE 响应】URL: {}, 响应码: {}, 响应内容: {}", url, resp.getStatusCode(), resp.getBody());
                if (resp.getStatusCode().is2xxSuccessful() && isValidApiResponse(resp.getBody())) {
                    log.info("🎉 成功从角色 [{}] 移除用户 [{}] (ID: {})", roleId, username, userId);
                    return true;
                }
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                log.info("【角色解绑 DELETE 失败】URL: {}, 状态: {}, 响应: {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            } catch (Exception e) {
                log.debug("尝试 DELETE 解绑失败 {}: {}", url, e.getMessage());
            }
        }

        // 尝试批量删除端点带 Body
        List<String> batchDeleteUrls = List.of(
                appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users", token),
                appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users/delete", token),
                appendAuthParam(baseUrl + "/v10/roles/" + roleId + "/users/remove", token),
                appendAuthParam(baseUrl + "/v10/role/" + roleId + "/users/delete", token)
        );

        List<Object> bodies = new ArrayList<>();
        bodies.add(Map.of("userIds", List.of(userId)));
        bodies.add(List.of(userId));
        if (username != null && !username.isBlank()) {
            bodies.add(Map.of("userNames", List.of(username)));
            bodies.add(Map.of("usernames", List.of(username)));
            bodies.add(List.of(username));
        }

        for (String url : batchDeleteUrls) {
            for (Object body : bodies) {
                HttpEntity<Object> entity = new HttpEntity<>(body, headers);
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
                    log.info("【角色批量 DELETE 响应】URL: {}, Body: {}, 响应: {}", url, body, resp.getBody());
                    if (resp.getStatusCode().is2xxSuccessful() && isValidApiResponse(resp.getBody())) {
                        return true;
                    }
                } catch (org.springframework.web.client.HttpStatusCodeException e) {
                    log.info("【角色批量 DELETE 失败】URL: {}, Body: {}, 状态: {}, 响应: {}", url, body, e.getStatusCode(), e.getResponseBodyAsString());
                } catch (Exception ignored) {}

                try {
                    ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
                    log.info("【角色批量 POST 删除响应】URL: {}, Body: {}, 响应: {}", url, body, resp.getBody());
                    if (resp.getStatusCode().is2xxSuccessful() && isValidApiResponse(resp.getBody())) {
                        return true;
                    }
                } catch (org.springframework.web.client.HttpStatusCodeException e) {
                    log.info("【角色批量 POST 删除失败】URL: {}, Body: {}, 状态: {}, 响应: {}", url, body, e.getStatusCode(), e.getResponseBodyAsString());
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    public boolean addUserToRole(String roleId, String userId) {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();
        HttpHeaders headers = createAuthHeaders(token);
        return bindUserToRole(baseUrl, token, headers, roleId, userId, null);
    }

    /**
     * 自动创建缺失的自定义角色 (可选辅助能力)
     */
    public String createCustomRole(String roleName) {
        String token = getAuthToken();
        String baseUrl = getCleanBaseUrl();
        String url = appendAuthParam(baseUrl + "/v10/roles", token);

        HttpHeaders headers = createAuthHeaders(token);
        Map<String, Object> body = Map.of("name", roleName.trim(), "description", "智数系统批量同步自动创建");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode root = objectMapper.readTree(resp.getBody());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                return dataNode.path("id").asText();
            }
        } catch (Exception e) {
            log.warn("创建角色 [{}] 失败: {}", roleName, e.getMessage());
        }
        return null;
    }

    private HttpHeaders createAuthHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set("fine_auth_token", token);

        List<String> cookieParts = new ArrayList<>();
        cookieParts.add("fine_auth_token=" + token);
        for (String sc : sessionCookies) {
            String c = sc.split(";", 2)[0].trim();
            if (!c.startsWith("fine_auth_token=") && !c.isBlank()) {
                cookieParts.add(c);
            }
        }
        headers.set("Cookie", String.join("; ", cookieParts));
        headers.set(HttpHeaders.CONNECTION, "keep-alive");
        return headers;
    }

    public Map<String, Object> debugInspect(String targetUsername) {
        Map<String, Object> debugMap = new LinkedHashMap<>();
        try {
            String token = getAuthToken();
            String baseUrl = getCleanBaseUrl();
            debugMap.put("baseUrl", baseUrl);
            debugMap.put("tokenPrefix", token.length() > 8 ? token.substring(0, 8) + "..." : token);

            // 1. 测试各 roles 端点并记录
            Map<String, Object> rolesTests = new LinkedHashMap<>();
            List<String> roleUrls = List.of(
                    baseUrl + "/v10/roles",
                    baseUrl + "/v10/roles?page=1&count=5000",
                    baseUrl + "/v10/roles?page=1&size=5000",
                    baseUrl + "/v10/roles?page=1&pageSize=5000",
                    baseUrl + "/v10/roles/custom",
                    baseUrl + "/v10/custom/roles"
            );
            for (String u : roleUrls) {
                try {
                    String fullUrl = appendAuthParam(u, token);
                    ResponseEntity<String> resp = restTemplate.exchange(fullUrl, HttpMethod.GET, new HttpEntity<>(createAuthHeaders(token)), String.class);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("status", resp.getStatusCode().value());
                    String b = resp.getBody();
                    info.put("bodyLength", b != null ? b.length() : 0);
                    if (b != null && !b.startsWith("<")) {
                        info.put("containsBU", b.contains("组织架构权限_BU"));
                        JsonNode node = objectMapper.readTree(b);
                        List<String> topKeys = new ArrayList<>();
                        node.fieldNames().forEachRemaining(topKeys::add);
                        info.put("topKeys", topKeys);
                        JsonNode data = node.has("data") ? node.get("data") : node;
                        if (data.has("items")) data = data.get("items");
                        if (data.isArray()) {
                            info.put("arraySize", data.size());
                            List<String> sampleNames = new ArrayList<>();
                            for (int i = 0; i < Math.min(10, data.size()); i++) {
                                JsonNode item = data.get(i);
                                sampleNames.add(item.path("name").asText(item.path("roleName").asText(item.asText())));
                            }
                            info.put("sampleNames", sampleNames);
                        }
                    } else if (b != null) {
                        info.put("htmlSnippet", b.substring(0, Math.min(200, b.length())));
                    }
                    rolesTests.put(u, info);
                } catch (Exception e) {
                    rolesTests.put(u, Map.of("error", e.getMessage()));
                }
            }
            debugMap.put("rolesTests", rolesTests);

            // 2. 测试用户列表，查找 targetUsername
            Map<String, Object> userTests = new LinkedHashMap<>();
            String usersUrl = appendAuthParam(baseUrl + "/v10/users?page=1&count=5000", token);
            try {
                ResponseEntity<String> resp = restTemplate.exchange(usersUrl, HttpMethod.GET, new HttpEntity<>(createAuthHeaders(token)), String.class);
                String b = resp.getBody();
                if (b != null && !b.startsWith("<")) {
                    JsonNode node = objectMapper.readTree(b);
                    JsonNode data = node.has("data") ? node.get("data") : node;
                    if (data.has("items")) data = data.get("items");
                    if (data.isArray()) {
                        userTests.put("totalUsersInArray", data.size());
                        JsonNode foundUserNode = null;
                        for (JsonNode uNode : data) {
                            String uname = uNode.path("username").asText("");
                            if (uname.equalsIgnoreCase(targetUsername)) {
                                foundUserNode = uNode;
                                break;
                            }
                        }
                        if (foundUserNode != null) {
                            userTests.put("foundUserRaw", objectMapper.treeToValue(foundUserNode, Object.class));
                            String userId = foundUserNode.path("id").asText("");
                            if (!userId.isBlank()) {
                                List<String> detailUrls = List.of(
                                        baseUrl + "/v10/users/" + userId,
                                        baseUrl + "/v10/users/" + userId + "/roles",
                                        baseUrl + "/v10/users/" + userId + "/custom/roles"
                                );
                                Map<String, Object> detailTests = new LinkedHashMap<>();
                                for (String du : detailUrls) {
                                    try {
                                        ResponseEntity<String> dResp = restTemplate.exchange(appendAuthParam(du, token), HttpMethod.GET, new HttpEntity<>(createAuthHeaders(token)), String.class);
                                        if (dResp.getBody() != null && !dResp.getBody().startsWith("<")) {
                                            detailTests.put(du, objectMapper.readTree(dResp.getBody()));
                                        } else {
                                            detailTests.put(du, "HTML response");
                                        }
                                    } catch (Exception ex) {
                                        detailTests.put(du, "Error: " + ex.getMessage());
                                    }
                                }
                                userTests.put("singleUserDetailTests", detailTests);
                            }
                        } else {
                            userTests.put("foundUser", "Not found in user list array");
                        }
                    }
                }
            } catch (Exception e) {
                userTests.put("error", e.getMessage());
            }
            debugMap.put("userTests", userTests);

            // 3. 测试角色的用户端点 (如 /v10/roles/{roleId}/users)
            try {
                List<FineReportRole> currentRoles = getCustomRoles();
                if (!currentRoles.isEmpty()) {
                    FineReportRole sampleRole = null;
                    for (FineReportRole r : currentRoles) {
                        if (r.getName().contains("组织架构") || r.getName().contains("BU")) {
                            sampleRole = r;
                            break;
                        }
                    }
                    if (sampleRole == null) sampleRole = currentRoles.get(0);

                    Map<String, Object> roleEndpointTests = new LinkedHashMap<>();
                    roleEndpointTests.put("testedRoleName", sampleRole.getName());
                    roleEndpointTests.put("testedRoleId", sampleRole.getId());

                    List<String> roleUserUrls = List.of(
                            baseUrl + "/v10/roles/" + sampleRole.getId() + "/users",
                            baseUrl + "/v10/roles/" + sampleRole.getId() + "/user",
                            baseUrl + "/v10/custom/roles/" + sampleRole.getId() + "/users"
                    );
                    for (String ru : roleUserUrls) {
                        try {
                            ResponseEntity<String> ruResp = restTemplate.exchange(appendAuthParam(ru, token), HttpMethod.GET, new HttpEntity<>(createAuthHeaders(token)), String.class);
                            Map<String, Object> ruInfo = new LinkedHashMap<>();
                            ruInfo.put("status", ruResp.getStatusCode().value());
                            String ruBody = ruResp.getBody();
                            ruInfo.put("bodySnippet", ruBody != null ? ruBody.substring(0, Math.min(200, ruBody.length())) : "");
                            roleEndpointTests.put(ru, ruInfo);
                        } catch (Exception ex) {
                            roleEndpointTests.put(ru, Map.of("error", ex.getMessage()));
                        }
                    }
                    debugMap.put("roleEndpointTests", roleEndpointTests);
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            debugMap.put("globalError", e.getMessage());
        }
        return debugMap;
    }
}
