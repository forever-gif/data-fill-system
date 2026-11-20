package com.example.datafill.service.feishu.approval;

import com.example.datafill.service.feishu.approval.FeishuApprovalParserService.ExtractedApprovalData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RoleApprovalTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testParseRoleApprovalInstance() throws Exception {
        java.io.InputStream is = getClass().getResourceAsStream("/real_feishu_role_approval_sample.json");
        assertNotNull(is, "real_feishu_role_approval_sample.json 文件应存在");

        Map<String, Object> root = objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) root.get("data");

        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        ExtractedApprovalData result = parser.parseApprovalInstance(data);

        assertNotNull(result);
        System.out.println("====== [测试] 账号与数据角色申请解析 ======");
        System.out.println("审批名称: " + result.getApprovalName());
        System.out.println("审批单号: " + result.getInstanceCode());
        System.out.println("申请人手机/UserID: " + result.getUserId());
        System.out.println("申请人 OpenID: " + result.getOpenId());
        System.out.println("申请类型: " + result.getApplyType());
        System.out.println("组织结构控权: " + result.getOrgControlRule());
        System.out.println("申请原因: " + result.getApplyReason());
        System.out.println("提取目标角色: " + result.getTargetRoles());
        System.out.println("角色类别分组: " + result.getRoleCategories());
        System.out.println("是否为角色/账号申请: " + result.isRoleApply());

        assertTrue(result.isRoleApply());
        assertEquals("账号数据角色申请", result.getApplyType());
        assertEquals("按申请人所在二级部门控权", result.getOrgControlRule());
        assertEquals("亚洲数据权限申请", result.getApplyReason());
        assertEquals(3, result.getTargetRoles().size());
        assertTrue(result.getTargetRoles().contains("大洲权限_亚洲"));
        assertTrue(result.getTargetRoles().contains("BU权限_第二事业群_亚洲事业部"));
        assertTrue(result.getTargetRoles().contains("财务报表列不可查看权限"));

        assertTrue(result.isDataSupervisorApproved());
        assertEquals("15888036039", result.getHandlerUserId());
        assertEquals("ou_f34a6b90a4cfa1f", result.getHandlerOpenId());
    }

    @Test
    public void testAutomationServiceFetchRoleSample() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                null, parser, null, objectMapper, null, null, null
        );

        var result = service.fetchAndExtract("sample-role");
        assertTrue(result.isSuccess(), "sample-role 应当解析成功: " + result.getMessage());
        assertNotNull(result.getApprovalData());
        assertTrue(result.getApprovalData().isRoleApply());
        assertEquals("账号数据角色申请", result.getApprovalData().getApplyType());
        assertEquals(3, result.getApprovalData().getTargetRoles().size());
        System.out.println("====== [Java] AutomationService fetchAndExtract(sample-role) 测试通过 ======");
        System.out.println("提示信息: " + result.getMessage());
    }

    @org.junit.jupiter.api.Disabled("手动联调测试")
    @Test
    public void testCheckDonghaoHe() throws Exception {
        com.example.datafill.config.FineReportProperties frProps = new com.example.datafill.config.FineReportProperties();
        frProps.setBaseUrl("https://bi.meiying.homes/webroot/decision");
        frProps.setAdminUsername("finereport_manage");
        frProps.setAdminPassword("kc7OMF^E^7L!");

        com.example.datafill.service.finereport.FineReportApiClient client = new com.example.datafill.service.finereport.FineReportApiClient(frProps);
        String token = client.getAuthToken();
        System.out.println("FineReport token: " + token);

        System.out.println("====== 精准查找 donghao-he / 何动豪 ======");
        Map<String, Object> rawData = client.fetchUserRawData(null, "donghao-he");
        System.out.println("通过 username 精准查询结果: " + rawData);

        var frUser = client.findUserSmart("donghao-he", "何动豪", null);
        System.out.println("findUserSmart 结果: " + (frUser != null ? (frUser.getUsername() + ", ID=" + frUser.getId() + ", RealName=" + frUser.getRealName()) : "null"));
    }

    @org.junit.jupiter.api.Disabled("手动联调测试")
    @Test
    public void testManualIntegration() throws Exception {
        com.example.datafill.config.FineReportProperties frProps = new com.example.datafill.config.FineReportProperties();
        frProps.setBaseUrl("https://bi.meiying.homes/webroot/decision");
        frProps.setAdminUsername("finereport_manage");
        frProps.setAdminPassword("kc7OMF^E^7L!");

        com.example.datafill.service.finereport.FineReportApiClient client = new com.example.datafill.service.finereport.FineReportApiClient(frProps);
        String token = client.getAuthToken();
        System.out.println("FineReport token: " + token);

        System.out.println("====== 获取所有角色 ======");
        java.util.List<com.example.datafill.service.finereport.FineReportApiClient.FineReportRole> roles = client.getCustomRoles();
        System.out.println("所有角色数量: " + roles.size());
        java.util.List<String> targetRoleIds = new java.util.ArrayList<>();
        java.util.List<String> targetRoleNames = java.util.List.of("大洲权限_亚洲", "BU权限_第二事业群_亚洲事业部", "财务报表列不可查看权限");
        for (com.example.datafill.service.finereport.FineReportApiClient.FineReportRole r : roles) {
            if (targetRoleNames.contains(r.getName())) {
                System.out.println("找到匹配角色: " + r.getName() + " -> " + r.getId());
                targetRoleIds.add(r.getId());
            }
        }

        org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("fine_auth_token", token);
        headers.set("Cookie", "fine_auth_token=" + token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("username", "donghao-he");
        payload.put("realName", "何动豪");
        payload.put("password", "Furniwell@2026");
        payload.put("email", "donghao-he@furniwell.com");
        payload.put("mobile", "15888036039");
        payload.put("roleIds", targetRoleIds);
        payload.put("roleNames", String.join(",", targetRoleNames));
        org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(payload, headers);


        System.out.println("====== 测试创建用户接口具体报错 ======");
        try {
            org.springframework.http.ResponseEntity<String> r1 = rt.exchange(
                    "https://bi.meiying.homes/webroot/decision/v10/user?fine_auth_token=" + token,
                    org.springframework.http.HttpMethod.POST,
                    entity,
                    String.class
            );
            System.out.println("POST /v10/user 完整返回: " + r1.getBody());
        } catch (Exception e) {
            System.out.println("POST /v10/user 抛出异常: " + e.getMessage());
        }

        try {
            org.springframework.http.ResponseEntity<String> r2 = rt.exchange(
                    "https://bi.meiying.homes/webroot/decision/v10/users?fine_auth_token=" + token,
                    org.springframework.http.HttpMethod.POST,
                    entity,
                    String.class
            );
            System.out.println("POST /v10/users 完整返回: " + r2.getBody());
        } catch (Exception e) {
            System.out.println("POST /v10/users 抛出异常: " + e.getMessage());
        }


        System.out.println("====== 检索帆软系统中全部 243 个用户 ======");
        for (int p = 1; p <= 3; p++) {
            org.springframework.http.ResponseEntity<String> pResp = rt.exchange(
                    "https://bi.meiying.homes/webroot/decision/v10/users?page=" + p + "&count=100&fine_auth_token=" + token,
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class
            );
            com.fasterxml.jackson.databind.JsonNode pJson = objectMapper.readTree(pResp.getBody());
            com.fasterxml.jackson.databind.JsonNode pItems = pJson.path("data").path("items");
            for (com.fasterxml.jackson.databind.JsonNode u : pItems) {
                String un = u.path("username").asText("").toLowerCase();
                String rn = u.path("realName").asText("");
                if (un.contains("test") || rn.contains("测试") || un.contains("ceshi")) {
                    System.out.println("【发现测试用户】username=" + un + ", realName=" + rn + ", id=" + u.path("id").asText(""));
                }
            }
        }

        System.out.println("====== 测试给 test_qx (权限测试) 赋予 3 个角色 ======");
        boolean updateSuccess = client.updateUserCustomRoles(
                "ef535504-5d41-45ec-bf7b-66d8f86b883d",
                "test_qx",
                null,
                targetRoleIds,
                targetRoleNames,
                targetRoleIds,
                java.util.Collections.emptyList()
        );
        System.out.println("赋权执行结果: " + updateSuccess);

        System.out.println("====== 验证 test_qx 当前的角色列表 ======");
        Map<String, Object> testQxData = client.fetchUserRawData("ef535504-5d41-45ec-bf7b-66d8f86b883d", "test_qx");
        System.out.println("test_qx raw data: roleNames=" + testQxData.get("roleNames") + ", roleIds=" + testQxData.get("roleIds"));
    }

    @Test
    public void testRoleCreateUserLeavesDeptEmailMobileEmpty() {
        com.example.datafill.config.FeishuProperties feishuProps = new com.example.datafill.config.FeishuProperties();
        feishuProps.getApproval().setTestMode(false);
        feishuProps.getApproval().setDryRun(false);
        feishuProps.getApproval().setTargetNames(java.util.List.of("智数平台-账号与数据角色申请或角色变更"));

        java.util.concurrent.atomic.AtomicBoolean deptQueried = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> capturedMobile = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedEmail = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedDept = new java.util.concurrent.atomic.AtomicReference<>("UNSET");

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(feishuProps) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "2D598A70-6470318A",
                                "approval_name", "智数平台-账号与数据角色申请或角色变更",
                                "department_id", "B013",
                                "status", "PENDING",
                                "open_id", "ou_applicant_1",
                                "user_id", "13800138000",
                                "form", "[{\"name\":\"申请类型\",\"value\":\"账号数据角色申请\"},"
                                        + "{\"name\":\"组织结构控权\",\"value\":\"按申请人所在二级部门控权\"},"
                                        + "{\"name\":\"大洲控权\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"角色名\",\"value\":\"大洲权限_亚洲\"}]]}]",
                                "task_list", java.util.List.of(
                                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_handler_1")
                                )
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "新同事", "enterprise_email", "xin-tongshi@furniwell.com", "mobile", "13800138000");
                    }

                    @Override
                    public String getDepartmentName(String departmentId) {
                        deptQueried.set(true);
                        return "某测试部门";
                    }

                    @Override
                    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                                    String applicantName, String instanceCode,
                                                                    String applyType, String orgControlRule,
                                                                    Map<String, java.util.List<String>> roleCategories,
                                                                    java.util.List<String> successRoles,
                                                                    java.util.List<String> failRoles,
                                                                    String initialPassword) {
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public com.example.datafill.service.finereport.FineReportApiClient.FineReportUser findUserSmart(
                            String email, String realName, String mobile) {
                        return null;
                    }

                    @Override
                    public java.util.List<FineReportRole> getCustomRoles() {
                        FineReportRole r = new FineReportRole();
                        r.setId("role-asia-1");
                        r.setName("大洲权限_亚洲");
                        return java.util.List.of(r);
                    }

                    @Override
                    public String createUser(String username, String realName, String password,
                                             String mobile, String email, String targetDepartment,
                                             java.util.List<String> targetRoleIds,
                                             java.util.List<String> targetRoleNames) {
                        capturedMobile.set(mobile);
                        capturedEmail.set(email);
                        capturedDept.set(targetDepartment);
                        return "new-user-id-1";
                    }
                };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, feishuProps, null, null
        );
        var res = service.executeFullPipeline("INST-ROLE-NEW-1");
        assertTrue(res.isSuccess(), res.getMessage());
        assertFalse(deptQueried.get(), "不应再调用飞书查询部门接口");
        assertEquals("", capturedMobile.get(), "新建账号手机号应默认置空");
        assertEquals("", capturedEmail.get(), "新建账号邮箱应默认置空");
        assertNull(capturedDept.get(), "新建账号部门应默认置空");
    }

    @Test
    public void testNamesakeSafetyInFindUserSmart() {
        com.example.datafill.service.finereport.FineReportApiClient client =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        FineReportUser u1 = new FineReportUser();
                        u1.setId("id-1");
                        u1.setUsername("wei-zhang");
                        u1.setRealName("张伟");
                        FineReportUser u2 = new FineReportUser();
                        u2.setId("id-2");
                        u2.setUsername("wei-zhang2");
                        u2.setRealName("张伟");
                        u2.setMobile("13900002222");
                        return Map.of("wei-zhang", u1, "wei-zhang2", u2);
                    }
                };

        // 1. 仅姓名且存在重名时，拒绝盲目匹配以免开错人
        assertNull(client.findUserSmart("15800000000", "张伟", null));

        // 2. 有唯一企业邮箱或准确用户名时，精准命中对应的那一位
        var byEmail = client.findUserSmart("wei-zhang2@furniwell.com", "张伟", null);
        assertNotNull(byEmail);
        assertEquals("id-2", byEmail.getId());

        var byUsername = client.findUserSmart("wei-zhang", "张伟", null);
        assertNotNull(byUsername);
        assertEquals("id-1", byUsername.getId());

        // 3. 有手机号消歧时，精准命中对应那一位
        var byMobile = client.findUserSmart("13900002222", "张伟", "13900002222");
        assertNotNull(byMobile);
        assertEquals("id-2", byMobile.getId());

        // 4. 非公司域邮箱绝不靠 @ 前缀跨域名瞎猜
        assertNull(client.findUserSmart("wei-zhang@163.com", null, null));
    }

    @Test
    public void testZeroGuessStrictBlockingOnAmbiguousOrMissingRoleChange() {
        com.example.datafill.config.FeishuProperties feishuProps = new com.example.datafill.config.FeishuProperties();
        feishuProps.getApproval().setTestMode(false);
        feishuProps.getApproval().setDryRun(false);
        feishuProps.getApproval().setTargetNames(java.util.List.of("智数平台-账号与数据角色申请或角色变更"));

        java.util.concurrent.atomic.AtomicBoolean createCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean updateCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger alertCount = new java.util.concurrent.atomic.AtomicInteger(0);

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(feishuProps) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        String applyType = "INST-DUP-NAME".equals(instanceCode) ? "账号数据角色申请" : "角色变更";
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "2D598A70-6470318A",
                                "approval_name", "智数平台-账号与数据角色申请或角色变更",
                                "status", "PENDING",
                                "open_id", "ou_app",
                                "user_id", "13800009999",
                                "form", "[{\"name\":\"申请类型\",\"value\":\"" + applyType + "\"},"
                                        + "{\"name\":\"大洲控权\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"角色名\",\"value\":\"大洲权限_亚洲\"}]]}]",
                                "task_list", java.util.List.of(
                                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_handler")
                                )
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "张伟");
                    }

                    @Override
                    public boolean sendAccountMissingAlertCard(String receiveId, String receiveIdType,
                                                               String applicantName, String attemptedUsername,
                                                               String instanceCode, java.util.List<String> requestedReports) {
                        alertCount.incrementAndGet();
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        FineReportUser u1 = new FineReportUser();
                        u1.setId("id-1");
                        u1.setUsername("wei-zhang");
                        u1.setRealName("张伟");
                        FineReportUser u2 = new FineReportUser();
                        u2.setId("id-2");
                        u2.setUsername("wei-zhang2");
                        u2.setRealName("张伟");
                        return Map.of("wei-zhang", u1, "wei-zhang2", u2);
                    }

                    @Override
                    public java.util.List<FineReportRole> getCustomRoles() {
                        return java.util.List.of();
                    }

                    @Override
                    public String createUser(String username, String realName, String password, String mobile, String email,
                                             String targetDepartment, java.util.List<String> targetRoleIds, java.util.List<String> targetRoleNames) {
                        createCalled.set(true);
                        return "should-not-create";
                    }

                    @Override
                    public boolean updateUserCustomRoles(String userId, String username, String targetDepartment,
                                                         java.util.List<String> targetRoleIds, java.util.List<String> targetRoleNames,
                                                         java.util.List<String> addedRoleIds, java.util.List<String> removedRoleIds) {
                        updateCalled.set(true);
                        return false;
                    }
                };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, feishuProps, null, null
        );

        // 重名无法区分 -> 报警不开，绝不新建或误改
        var dupRes = service.executeFullPipeline("INST-DUP-NAME");
        assertFalse(Boolean.TRUE.equals(dupRes.getFineReportAuthResult().get("success")));
        assertEquals(true, dupRes.getFineReportAuthResult().get("alertCardSent"));

        // 角色变更找不到原账号 -> 报警不开，绝不误建新号
        var changeRes = service.executeFullPipeline("INST-CHANGE-MISS");
        assertFalse(Boolean.TRUE.equals(changeRes.getFineReportAuthResult().get("success")));
        assertEquals(true, changeRes.getFineReportAuthResult().get("alertCardSent"));

        assertFalse(createCalled.get(), "重名或角色变更找不到原账号时绝不能新建账号");
        assertFalse(updateCalled.get(), "重名未消歧时绝不能修改任何人的角色");
        assertEquals(2, alertCount.get());
    }

    @Test
    public void testNewAccountUsernameUsesEnterprisePrefixOrChinesePinyinOverEnName() {
        // 即使飞书填了花名/英文名 Sunny 且只有 163 个人邮箱，帆软用户名仍严格按中文姓名转 `<名全拼>-<姓全拼>`
        var withEnNameAnd163 = FeishuApprovalParserService.ExtractedApprovalData.builder()
                .applicantEnName("Sunny")
                .build();
        assertEquals("mengyuan-hu",
                FeishuApprovalAutomationService.resolvePreferredAccountUsername("humy0916@163.com", "胡梦园", withEnNameAnd163));

        var onlyPersonalEmail = FeishuApprovalParserService.ExtractedApprovalData.builder().build();
        assertEquals("mengyuan-hu",
                FeishuApprovalAutomationService.resolvePreferredAccountUsername("humy0916@163.com", "胡梦园", onlyPersonalEmail));

        var enterpriseEmail = FeishuApprovalParserService.ExtractedApprovalData.builder()
                .applicantEnName("Alice")
                .build();
        assertEquals("mengyuan-hu2",
                FeishuApprovalAutomationService.resolvePreferredAccountUsername("mengyuan-hu2@furniwell.com", "胡梦园", enterpriseEmail));

        assertEquals("ming-ouyang",
                FeishuApprovalAutomationService.toGivenSurnamePinyin("欧阳明"));
    }

    @Test
    public void testCreateUserResolvesRealUuidViaPagingAndNeverSendsSuccessAsUserId() {
        var props = new com.example.datafill.config.FineReportProperties();
        props.setBaseUrl("http://127.0.0.1:8081/webroot/decision");
        var client = new com.example.datafill.service.finereport.FineReportApiClient(props) {
            @Override
            public String getAuthToken() {
                return "mock-token";
            }

            @Override
            public String encryptPasswordWithRsa(String plainPassword) {
                return "enc-" + plainPassword;
            }
        };
        var rest = (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "restTemplate");
        var server = org.springframework.test.web.client.MockRestServiceServer.createServer(rest);

        // 1. POST /v10/user 返回 {"data":"success"}
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/v10/user")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":\"success\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        // 2. keyword 搜索未命中
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("keyword=")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":{\"items\":[]}}", org.springframework.http.MediaType.APPLICATION_JSON));

        // 3. 全量分页扫描命中真实 UUID
        String userItem = "{\"id\":\"real-uuid-humy\",\"username\":\"humy0916\",\"realName\":\"胡梦园\",\"email\":\"\",\"mobile\":\"\",\"depPostNames\":\"\",\"departmentPostIds\":[]}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/v10/users?page=1&count=5000")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":{\"totalCount\":1,\"items\":[" + userItem + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));

        // 4. updateUserCustomRoles 内部 fetchUserRawData 查询与 PUT /v10/user 必须使用 real-uuid-humy，绝不能传 "success"
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("keyword=")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":{\"items\":[" + userItem + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/v10/user")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.id").value("real-uuid-humy"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":\"success\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        String createdId = client.createUser(
                "humy0916", "胡梦园", "Pass!1234", "", "", null,
                java.util.List.of("role-1"), java.util.List.of("大洲权限_亚洲"));
        server.verify();
        assertEquals("real-uuid-humy", createdId);
        assertEquals(Boolean.TRUE, client.consumeLastCreatedUserRolesBound());
    }

    @Test
    public void testMissingRoleDoesNotAutoCreateAndFailsDirectly() {
        com.example.datafill.config.FeishuProperties feishuProps = new com.example.datafill.config.FeishuProperties();
        feishuProps.getApproval().setTestMode(false);
        feishuProps.getApproval().setDryRun(false);
        feishuProps.getApproval().setTargetNames(java.util.List.of("智数平台-账号与数据角色申请或角色变更"));

        java.util.concurrent.atomic.AtomicBoolean createRoleCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean createUserCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> cardFailRoles = new java.util.concurrent.atomic.AtomicReference<>();

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(feishuProps) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "2D598A70-6470318A",
                                "approval_name", "智数平台-账号与数据角色申请或角色变更",
                                "status", "PENDING",
                                "open_id", "ou_app",
                                "user_id", "13800138000",
                                "form", "[{\"name\":\"申请类型\",\"value\":\"账号数据角色申请\"},"
                                        + "{\"name\":\"大洲控权\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"角色名\",\"value\":\"不存在的角色_测试\"}]]}]",
                                "task_list", java.util.List.of(
                                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_handler")
                                )
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "胡梦园", "email", "humy0916@163.com");
                    }

                    @Override
                    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                                    String applicantName, String instanceCode,
                                                                    String applyType, String orgControlRule,
                                                                    Map<String, java.util.List<String>> roleCategories,
                                                                    java.util.List<String> successRoles,
                                                                    java.util.List<String> failRoles,
                                                                    String initialPassword) {
                        cardFailRoles.set(failRoles);
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        return java.util.Collections.emptyMap();
                    }

                    @Override
                    public java.util.List<FineReportRole> getCustomRoles() {
                        return java.util.List.of();
                    }

                    @Override
                    public String createCustomRole(String roleName) {
                        createRoleCalled.set(true);
                        return "should-not-create-role";
                    }

                    @Override
                    public String createUser(String username, String realName, String password, String mobile, String email,
                                             String targetDepartment, java.util.List<String> targetRoleIds, java.util.List<String> targetRoleNames) {
                        createUserCalled.set(true);
                        return "should-not-create-user";
                    }
                };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, feishuProps, null, null
        );
        var res = service.executeFullPipeline("INST-MISSING-ROLE-1");
        assertFalse(res.isSuccess(), "角色未命中时应直接报错失败");
        assertFalse(createRoleCalled.get(), "绝不能自动创建缺失角色");
        assertFalse(createUserCalled.get(), "角色校验未通过前不应新建半成品账号");
        assertEquals(java.util.List.of("不存在的角色_测试"), cardFailRoles.get());
    }
}
