package com.example.datafill.service.feishu.approval;

import com.example.datafill.config.FeishuProperties;
import com.example.datafill.service.feishu.approval.FeishuApprovalParserService.ExtractedApprovalData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class FeishuApprovalParserServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testParseRealFeishuApprovalSample() throws Exception {
        InputStream is = getClass().getResourceAsStream("/real_feishu_approval_sample.json");
        assertNotNull(is, "真实测试样例文件应存在");

        Map<String, Object> root = objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) root.get("data");

        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        ExtractedApprovalData result = parser.parseApprovalInstance(data);

        assertNotNull(result);
        assertEquals("智数平台-报表权限申请", result.getApprovalName());
        assertEquals("APPROVED", result.getStatus());
        assertEquals("2", String.valueOf(result.getReportItems().size()));

        var rep1 = result.getReportItems().get(0);
        assertEquals("03供应商管理报表.cpt", rep1.getReportFile());
        assertEquals("03供应商管理报表", rep1.getReportName());
        assertEquals("02.供应链管理", rep1.getL3());
        assertEquals("03.供应商管理分析", rep1.getL4());

        var rep2 = result.getReportItems().get(1);
        assertEquals("04供应商管理报表明细数据.cpt", rep2.getReportFile());
        assertEquals("04供应商管理报表明细数据", rep2.getReportName());

        System.out.println("====== [Java] 真实飞书审批数据解析成功 ======");
        System.out.println("流程名称: " + result.getApprovalName());
        System.out.println("申请人 UserID/手机: " + result.getUserId());
        System.out.println("申请人 OpenID: " + result.getOpenId());
        System.out.println("提取报表数量: " + result.getReportItems().size());
        for (var item : result.getReportItems()) {
            System.out.println("  - 报表: " + item.getReportFile());
            System.out.println("    帆软目录节点: " + item.getEntryNodes());
        }
    }

    @Test
    public void testAutomationServiceFetchSample() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                null, parser, null, objectMapper, null, null, null
        );

        var result = service.fetchAndExtract("sample");
        assertTrue(result.isSuccess(), "sample 应当解析成功: " + result.getMessage());
        assertNotNull(result.getApprovalData());
        assertEquals(2, result.getFineReportPayload().size());
        System.out.println("====== [Java] AutomationService fetchAndExtract(sample) 测试通过 ======");
    }

    @Test
    public void testParseFvsReportExtensionStripped() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        String formJson = "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"报表全路径\",\"value\":\"美鹰集团报表/01生产环境/03.销售分析/03历史销量趋势与未来预估分析.fvs\"}]]}]";
        Map<String, Object> raw = Map.of(
                "instance_code", "INST-FVS-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "APPROVED",
                "form", formJson
        );
        ExtractedApprovalData data = parser.parseApprovalInstance(raw);
        assertEquals(1, data.getReportItems().size());
        var item = data.getReportItems().get(0);
        assertEquals("03历史销量趋势与未来预估分析.fvs", item.getReportFile());
        assertEquals("03历史销量趋势与未来预估分析", item.getReportName());
    }

    @Test
    public void testDataSupervisorAutoPassAndHandlerPendingRecognized() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        String formJson = "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"报表全路径\",\"value\":\"美鹰集团报表/01生产环境/销售分析.cpt\"}]]}]";

        // 1. 数据主管审批为 DONE + AUTO_PASS，技术Owner办理为 PENDING
        Map<String, Object> autoPassDone = Map.of(
                "instance_code", "INST-AUTO-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "form", formJson,
                "task_list", List.of(
                        Map.of("id", "t1", "node_name", "直属上级审批", "status", "APPROVED"),
                        Map.of("id", "t2", "node_name", "业务Owner审批", "status", "APPROVED"),
                        Map.of("id", "t3", "node_name", "数据主管审批", "status", "DONE", "type", "AUTO_PASS"),
                        Map.of("id", "t4", "node_name", "技术Owner办理", "status", "PENDING", "user_id", "15888036039")
                )
        );
        ExtractedApprovalData d1 = parser.parseApprovalInstance(autoPassDone);
        assertTrue(d1.isDataSupervisorApproved(), "数据主管自动通过 (DONE/AUTO_PASS) 应判定已放行");
        assertTrue(d1.isHandlerTaskPending());
        assertEquals("15888036039", d1.getHandlerUserId());

        // 2. 数据主管节点被去重跳过 (未出现在 task_list，仅技术Owner办理进入 PENDING)
        Map<String, Object> skippedSupervisor = Map.of(
                "instance_code", "INST-AUTO-2",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "form", formJson,
                "task_list", List.of(
                        Map.of("id", "t1", "node_name", "直属上级审批", "status", "APPROVED"),
                        Map.of("id", "t2", "node_name", "业务Owner审批", "status", "APPROVED"),
                        Map.of("id", "t4", "node_name", "技术Owner办理", "status", "PENDING", "user_id", "15888036039")
                )
        );
        ExtractedApprovalData d2 = parser.parseApprovalInstance(skippedSupervisor);
        assertTrue(d2.isDataSupervisorApproved(), "已进入技术Owner办理 PENDING 且无待处理数据主管节点时应判定已放行");

        // 3. 负向用例：数据主管仍在 PENDING，不得放行
        Map<String, Object> waitingSupervisor = Map.of(
                "instance_code", "INST-WAIT-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "form", formJson,
                "task_list", List.of(
                        Map.of("id", "t1", "node_name", "业务Owner审批", "status", "APPROVED"),
                        Map.of("id", "t2", "node_name", "数据主管审批", "status", "PENDING")
                )
        );
        ExtractedApprovalData d3 = parser.parseApprovalInstance(waitingSupervisor);
        assertFalse(d3.isDataSupervisorApproved(), "数据主管仍在审批中时不得提前放行");

        // 4. 负向用例：已拒绝实例不得通过兜底放行
        Map<String, Object> rejectedInst = Map.of(
                "instance_code", "INST-REJ-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "REJECTED",
                "form", formJson,
                "task_list", List.of(
                        Map.of("id", "t4", "node_name", "技术Owner办理", "status", "PENDING")
                )
        );
        ExtractedApprovalData d4 = parser.parseApprovalInstance(rejectedInst);
        assertFalse(d4.isDataSupervisorApproved(), "已拒绝实例不得触发自动放行");
    }

    @Test
    public void testConcurrentWebhookCoalescedAndRetriggered() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstFinish = new CountDownLatch(1);
        CountDownLatch secondAttemptDone = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                null, new FeishuApprovalParserService(), null, objectMapper, null, null, null
        ) {
            @Override
            public ApprovalProcessResult executeFullPipeline(String instanceCode) {
                int n = callCount.incrementAndGet();
                ApprovalProcessResult r = new ApprovalProcessResult();
                r.setSuccess(true);
                if (n == 1) {
                    firstEntered.countDown();
                    try {
                        allowFirstFinish.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    r.setMessage("数据主管尚未审批同意，暂不执行自动化赋权");
                    return r;
                }
                r.setFineReportAuthResult(Map.of("success", true));
                r.setMessage("自动赋权成功");
                secondAttemptDone.countDown();
                return r;
            }
        };

        Thread t1 = new Thread(() -> service.processApprovalEventAsync("INST-RACE-1", "approval_task"));
        t1.start();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        // 在第一条事件处理期间并发到达第二条事件，不应被静默丢弃
        service.processApprovalEventAsync("INST-RACE-1", "approval_task");
        allowFirstFinish.countDown();

        assertTrue(secondAttemptDone.await(2, TimeUnit.SECONDS), "并发期间到达的事件应当在首个执行完成后自动触发复核");
        t1.join(2000);
        assertEquals(2, callCount.get());
    }

    @Test
    public void testShouldSkipHandlerCardAfterHandlerApproved() {
        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                null, new FeishuApprovalParserService(), null, objectMapper, null, null, null
        );

        ExtractedApprovalData waitingHandler = ExtractedApprovalData.builder()
                .instanceCode("INST-REAL-1")
                .status("PENDING")
                .handlerTaskPending(true)
                .build();
        assertFalse(service.shouldSkipHandlerCard(waitingHandler), "办理人未处理前应发送核对卡片");

        ExtractedApprovalData handlerFinished = ExtractedApprovalData.builder()
                .instanceCode("INST-REAL-1")
                .status("APPROVED")
                .handlerTaskPending(false)
                .build();
        assertTrue(service.shouldSkipHandlerCard(handlerFinished), "办理人已同意/已处理后不应再发办理提醒卡片");

        ExtractedApprovalData sampleFinished = ExtractedApprovalData.builder()
                .instanceCode("sample")
                .status("APPROVED")
                .handlerTaskPending(false)
                .build();
        assertFalse(service.shouldSkipHandlerCard(sampleFinished), "内置 sample 演示不受跳过限制");
    }

    @Test
    public void testMultiplePendingHandlersAllNotified() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        String formJson = "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"报表全路径\",\"value\":\"美鹰集团报表/01生产环境/销售分析.cpt\"}]]}]";

        Map<String, Object> raw = Map.of(
                "instance_code", "INST-MULTI-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "open_id", "ou_applicant",
                "form", formJson,
                "task_list", List.of(
                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "TRANSFERRED", "open_id", "ou_transferred"),
                        Map.of("id", "t3", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_kaiyang", "user_id", "u1"),
                        Map.of("id", "t4", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_donghao", "user_id", "u2")
                )
        );
        ExtractedApprovalData data = parser.parseApprovalInstance(raw);
        assertEquals(List.of("ou_kaiyang", "ou_donghao"), data.getHandlerOpenIds());
        assertEquals(List.of("u1", "u2"), data.getHandlerUserIds());

        // 生产模式：向所有待处理办理人都发送
        FeishuProperties prodProps = new FeishuProperties();
        prodProps.getApproval().setTestMode(false);
        FeishuApprovalAutomationService prodService = new FeishuApprovalAutomationService(
                null, parser, null, objectMapper, prodProps, null, null
        );
        var prodTargets = prodService.resolveNotificationTargets(data);
        assertEquals(2, prodTargets.size());
        assertEquals("ou_kaiyang", prodTargets.get(0)[0]);
        assertEquals("ou_donghao", prodTargets.get(1)[0]);

        // 测试模式：仍只发给测试账号一次，避免重复推送
        FeishuProperties testProps = new FeishuProperties();
        testProps.getApproval().setTestMode(true);
        testProps.getApproval().setDefaultTestOpenId("ou_12345678901234567890");
        FeishuApprovalAutomationService testService = new FeishuApprovalAutomationService(
                null, parser, null, objectMapper, testProps, null, null
        );
        var testTargets = testService.resolveNotificationTargets(data);
        assertEquals(1, testTargets.size());
        assertEquals("ou_12345678901234567890", testTargets.get(0)[0]);
    }

    @Test
    public void testSequentialHandlersRelayNotificationWithoutDuplicateGrant() {
        FeishuProperties props = new FeishuProperties();
        props.getApproval().setTestMode(false);
        props.getApproval().setDryRun(false);
        props.getApproval().setTargetNames(List.of("智数平台-账号与数据角色申请或角色变更"));

        AtomicInteger step = new AtomicInteger(1);
        AtomicInteger grantCount = new AtomicInteger(0);
        java.util.List<String> sentTargets = new java.util.ArrayList<>();

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(props) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        int s = step.get();
                        List<Map<String, Object>> tasks;
                        String instStatus = "PENDING";
                        if (s == 1) {
                            tasks = List.of(
                                    Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                    Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_kaiyang")
                            );
                        } else if (s == 2) {
                            tasks = List.of(
                                    Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                    Map.of("id", "t2", "node_name", "技术Owner办理", "status", "APPROVED", "open_id", "ou_kaiyang"),
                                    Map.of("id", "t3", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_donghao")
                            );
                        } else {
                            instStatus = "APPROVED";
                            tasks = List.of(
                                    Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                    Map.of("id", "t2", "node_name", "技术Owner办理", "status", "APPROVED", "open_id", "ou_kaiyang"),
                                    Map.of("id", "t3", "node_name", "技术Owner办理", "status", "APPROVED", "open_id", "ou_donghao")
                            );
                        }
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "2D598A70-6470318A",
                                "approval_name", "智数平台-账号与数据角色申请或角色变更",
                                "status", instStatus,
                                "open_id", "ou_app",
                                "user_id", "13800138000",
                                "form", "[{\"name\":\"申请类型\",\"value\":\"账号数据角色申请\"},"
                                        + "{\"name\":\"大洲控权\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"角色名\",\"value\":\"大洲权限_亚洲\"}]]}]",
                                "task_list", tasks
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "蒋丹敏", "enterprise_email", "danmin-jiang@furniwell.com");
                    }

                    @Override
                    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                                    String applicantName, String instanceCode,
                                                                    String applyType, String orgControlRule,
                                                                    Map<String, List<String>> roleCategories,
                                                                    List<String> successRoles, List<String> failRoles,
                                                                    String initialPassword) {
                        sentTargets.add(receiveId);
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        FineReportUser u = new FineReportUser();
                        u.setId("uid-1");
                        u.setUsername("danmin-jiang");
                        u.setRealName("蒋丹敏");
                        u.setEmail("danmin-jiang@furniwell.com");
                        return Map.of("danmin-jiang", u);
                    }

                    @Override
                    public List<FineReportRole> getCustomRoles() {
                        FineReportRole r = new FineReportRole();
                        r.setId("r-1");
                        r.setName("大洲权限_亚洲");
                        return List.of(r);
                    }

                    @Override
                    public boolean updateUserCustomRoles(String userId, String username, String targetDepartment,
                                                         List<String> targetRoleIds, List<String> targetRoleNames,
                                                         List<String> addedRoleIds, List<String> removedRoleIds) {
                        grantCount.incrementAndGet();
                        return true;
                    }
                };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, props, null, null
        );

        // 1. 依次办理第 1 人待处理 -> 赋权 1 次并通知第 1 人
        service.processApprovalEventAsync("INST-SEQ-1", "approval_task");
        assertEquals(1, grantCount.get());
        assertEquals(List.of("ou_kaiyang"), sentTargets);

        // 2. 第 1 人未点前重复推送 -> 不重复赋权、不重复发卡
        service.processApprovalEventAsync("INST-SEQ-1", "approval_task");
        assertEquals(1, grantCount.get());
        assertEquals(List.of("ou_kaiyang"), sentTargets);

        // 3. 第 1 人点提交，流转至第 2 人 PENDING -> 不重复赋权，接力补发第 2 人
        step.set(2);
        service.processApprovalEventAsync("INST-SEQ-1", "approval_task");
        assertEquals(1, grantCount.get());
        assertEquals(List.of("ou_kaiyang", "ou_donghao"), sentTargets);

        // 4. 第 2 人也点提交，流程 APPROVED -> 不再发任何卡片
        step.set(3);
        service.processApprovalEventAsync("INST-SEQ-1", "approval_instance");
        assertEquals(1, grantCount.get());
        assertEquals(List.of("ou_kaiyang", "ou_donghao"), sentTargets);
    }

    @Test
    public void testFirstHandlerReceivesCardEvenWhenSupervisorAndTaskCreateEventsOverlap() throws Exception {
        FeishuProperties props = new FeishuProperties();
        props.getApproval().setTestMode(false);
        props.getApproval().setDryRun(false);
        props.getApproval().setTargetNames(List.of("智数平台-账号与数据角色申请或角色变更"));

        AtomicInteger step = new AtomicInteger(1);
        java.util.List<String> sentTargets = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstGrantStarted = new CountDownLatch(1);
        CountDownLatch allowFirstGrantFinish = new CountDownLatch(1);

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(props) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        int s = step.get();
                        List<Map<String, Object>> tasks = (s == 1)
                                ? List.of(Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"))
                                : List.of(
                                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_kaiyang")
                                );
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "2D598A70-6470318A",
                                "approval_name", "智数平台-账号与数据角色申请或角色变更",
                                "status", "PENDING",
                                "open_id", "ou_applicant",
                                "user_id", "u_applicant",
                                "form", "[{\"name\":\"大洲控权\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"角色名\",\"value\":\"大洲权限_亚洲\"}]]}]",
                                "task_list", tasks
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "蒋丹敏", "enterprise_email", "danmin-jiang@furniwell.com");
                    }

                    @Override
                    public boolean sendRoleApprovalNotificationCard(String receiveId, String receiveIdType,
                                                                    String applicantName, String instanceCode,
                                                                    String applyType, String orgControlRule,
                                                                    Map<String, List<String>> roleCategories,
                                                                    List<String> successRoles, List<String> failRoles,
                                                                    String initialPassword) {
                        sentTargets.add(receiveId);
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        FineReportUser u = new FineReportUser();
                        u.setId("uid-1");
                        u.setUsername("danmin-jiang");
                        u.setRealName("蒋丹敏");
                        u.setEmail("danmin-jiang@furniwell.com");
                        return Map.of("danmin-jiang", u);
                    }

                    @Override
                    public List<FineReportRole> getCustomRoles() {
                        FineReportRole r = new FineReportRole();
                        r.setId("r-1");
                        r.setName("大洲权限_亚洲");
                        return List.of(r);
                    }

                    @Override
                    public boolean updateUserCustomRoles(String userId, String username, String targetDepartment,
                                                         List<String> targetRoleIds, List<String> targetRoleNames,
                                                         List<String> addedRoleIds, List<String> removedRoleIds) {
                        firstGrantStarted.countDown();
                        try {
                            allowFirstGrantFinish.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                        }
                        return true;
                    }
                };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, props, null, null
        );

        Thread t1 = new Thread(() -> service.processApprovalEventAsync("INST-FIRST-RACE-1", "approval_task"));
        t1.start();
        assertTrue(firstGrantStarted.await(2, TimeUnit.SECONDS));

        // 正在给帆软写权限期间，第 1 位办理人张凯洋的 PENDING 任务生成并触发了第 2 条 Webhook
        step.set(2);
        service.processApprovalEventAsync("INST-FIRST-RACE-1", "approval_task");
        allowFirstGrantFinish.countDown();
        t1.join(3000);

        assertEquals(List.of("ou_kaiyang"), sentTargets, "第1位办理人张凯洋必须收到卡片且绝不能错发给申请人");
    }

    @Test
    public void testResolveReportEntryIdStrictBoundaryAndNoCrossDirGuess() throws Exception {
        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                null, new FeishuApprovalParserService(), null, objectMapper, null, null, null
        );

        var cCost = new com.example.datafill.entity.ReportCatalog();
        cCost.setReportId("uuid-cost");
        cCost.setReportName("成本明细表");
        cCost.setFullPath("01生产环境/销售分析/成本明细表.cpt");

        var cSalesOverview = new com.example.datafill.entity.ReportCatalog();
        cSalesOverview.setReportId("uuid-sales-overview");
        cSalesOverview.setReportName("概览");
        cSalesOverview.setFullPath("01生产环境/销售部/概览.cpt");

        var cFinOverview = new com.example.datafill.entity.ReportCatalog();
        cFinOverview.setReportId("uuid-fin-overview");
        cFinOverview.setReportName("概览");
        cFinOverview.setFullPath("02测试环境/财务部/概览.cpt");

        var cFvs = new com.example.datafill.entity.ReportCatalog();
        cFvs.setReportId("uuid-fvs");
        cFvs.setReportName("概览");
        cFvs.setFullPath("01生产环境/销售部/概览.fvs");

        List<com.example.datafill.entity.ReportCatalog> catalogs = List.of(cCost, cSalesOverview, cFinOverview, cFvs);

        // 1. 子串“明细表”绝不能误命中“成本明细表”
        var itemSub = FeishuApprovalParserService.ReportItem.builder()
                .rawPath("01生产环境/销售分析/明细表.cpt")
                .reportFile("明细表.cpt")
                .reportName("明细表")
                .build();
        assertNull(service.resolveReportEntryId(itemSub, catalogs, null));

        // 2. 多目录下均有“概览”，按精确路径各自命中对应版本 (.cpt vs .fvs / 销售部 vs 财务部)
        var itemFin = FeishuApprovalParserService.ReportItem.builder()
                .rawPath("美鹰集团报表/02测试环境/财务部/概览.cpt")
                .reportFile("概览.cpt")
                .reportName("概览")
                .build();
        assertEquals("uuid-fin-overview", service.resolveReportEntryId(itemFin, catalogs, null));

        var itemFvs = FeishuApprovalParserService.ReportItem.builder()
                .rawPath("01生产环境/销售部/概览.fvs")
                .reportFile("概览.fvs")
                .reportName("概览")
                .build();
        assertEquals("uuid-fvs", service.resolveReportEntryId(itemFvs, catalogs, null));

        // 3. 路径未对上且存在同名报表时，宁可返回 null 进待核实也不跨目录乱开
        var itemOther = FeishuApprovalParserService.ReportItem.builder()
                .rawPath("01生产环境/人事部/概览.cpt")
                .reportFile("概览.cpt")
                .reportName("概览")
                .build();
        assertNull(service.resolveReportEntryId(itemOther, catalogs, null));

        // 4. wikiCacheSnapshots 兜底同样遵守路径优先，绝不按纯名短路跨目录错配
        String wikiJson = "{"
                + "\"node1:11111111111111111111111111111111\":{\"reportName\":\"概览\",\"fullPath\":\"01生产环境/销售部/概览.cpt\"},"
                + "\"node2:22222222222222222222222222222222\":{\"reportName\":\"概览\",\"fullPath\":\"02测试环境/财务部/概览.cpt\"}"
                + "}";
        var wikiSnapshots = objectMapper.readTree(wikiJson);
        assertEquals("22222222222222222222222222222222", service.resolveReportEntryId(itemFin, List.of(), wikiSnapshots));
        assertNull(service.resolveReportEntryId(itemOther, List.of(), wikiSnapshots));

        // 5. 真实场景：DB 为 美鹰数据分析/00.经营管理/01.经营月报/01品类维度分析，飞书为 美鹰集团报表/01生产环境/00.经营管理/01.经营月报/01品类维度分析.cpt
        var cWeekly = new com.example.datafill.entity.ReportCatalog();
        cWeekly.setReportId("10b59ce9-e8ab-47d7-a371-b534e60e7c33");
        cWeekly.setReportName("01品类维度分析");
        cWeekly.setFullPath("美鹰数据分析/00.经营管理/02.经营周报/01品类维度分析");

        var cMonthly = new com.example.datafill.entity.ReportCatalog();
        cMonthly.setReportId("e1caa86e-a214-4454-a5ae-8e454d821ddf");
        cMonthly.setReportName("01品类维度分析");
        cMonthly.setFullPath("美鹰数据分析/00.经营管理/01.经营月报/01品类维度分析");

        var itemMonthly = FeishuApprovalParserService.ReportItem.builder()
                .rawPath("美鹰集团报表/01生产环境/00.经营管理/01.经营月报/01品类维度分析.cpt")
                .reportFile("01品类维度分析.cpt")
                .reportName("01品类维度分析")
                .build();
        assertEquals("e1caa86e-a214-4454-a5ae-8e454d821ddf",
                service.resolveReportEntryId(itemMonthly, List.of(cWeekly, cMonthly), null));

        // 6. 真实场景：Wiki 快照存在多节点指向同一 reportId 时，按 reportId 去重后仍唯一命中，不误报歧义
        String dupWikiJson = "{"
                + "\"nodeA:10b59ce9-e8ab-47d7-a371-b534e60e7c33\":{\"reportName\":\"01品类维度分析\",\"fullPath\":\"美鹰数据分析/00.经营管理/02.经营周报/01品类维度分析\"},"
                + "\"nodeB:10b59ce9-e8ab-47d7-a371-b534e60e7c33\":{\"reportName\":\"01品类维度分析\",\"fullPath\":\"美鹰数据分析/00.经营管理/02.经营周报/01品类维度分析\"},"
                + "\"nodeC:e1caa86e-a214-4454-a5ae-8e454d821ddf\":{\"reportName\":\"01品类维度分析\",\"fullPath\":\"美鹰数据分析/00.经营管理/01.经营月报/01品类维度分析\"},"
                + "\"nodeD:e1caa86e-a214-4454-a5ae-8e454d821ddf\":{\"reportName\":\"01品类维度分析\",\"fullPath\":\"美鹰数据分析/00.经营管理/01.经营月报/01品类维度分析\"}"
                + "}";
        var dupWikiSnapshots = objectMapper.readTree(dupWikiJson);
        assertEquals("e1caa86e-a214-4454-a5ae-8e454d821ddf",
                service.resolveReportEntryId(itemMonthly, List.of(), dupWikiSnapshots));

        // 7. 真实场景：表单「报表名称」(01.门到门时效报表) 与「报表全路径」末尾文件名(1.欧洲门到门时效报表.cpt)不一致
        var cDoorToDoor = new com.example.datafill.entity.ReportCatalog();
        cDoorToDoor.setReportId("uuid-door-to-door-1");
        cDoorToDoor.setReportName("01.门到门时效报表");
        cDoorToDoor.setFullPath("美鹰数据分析/05.物流管理/11.欧洲门到门时效分析/01.门到门时效报表");

        FeishuApprovalParserService realParser = new FeishuApprovalParserService();
        String dtdForm = "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[["
                + "{\"name\":\"报表名称\",\"value\":\"01.门到门时效报表\"},"
                + "{\"name\":\"报表全路径\",\"value\":\"美鹰集团报表/05.物流管理/11.欧洲门到门时效分析/1.欧洲门到门时效报表.cpt\"}"
                + "]]}]";
        ExtractedApprovalData dtdParsed = realParser.parseApprovalInstance(Map.of(
                "instance_code", "INST-DTD-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "form", dtdForm
        ));
        assertEquals(1, dtdParsed.getReportItems().size());
        assertEquals("uuid-door-to-door-1",
                service.resolveReportEntryId(dtdParsed.getReportItems().get(0), List.of(cDoorToDoor), null));
    }

    @Test
    public void testPreCheckSkipsAlreadyGrantedAndRecordsAudit() {
        FeishuProperties props = new FeishuProperties();
        props.getApproval().setTestMode(false);
        props.getApproval().setDryRun(false);
        props.getApproval().setTargetNames(List.of("智数平台-报表权限申请"));

        List<String> grantedBatchCalls = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<List<String>> auditNew = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<String>> auditOld = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<String>> cardNew = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<String>> cardOld = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> auditStatus = new java.util.concurrent.atomic.AtomicReference<>();

        com.example.datafill.service.feishu.FeishuApiClient stubFeishu =
                new com.example.datafill.service.feishu.FeishuApiClient(props) {
                    @Override
                    public Map<String, Object> getApprovalInstance(String instanceCode) {
                        return Map.of(
                                "instance_code", instanceCode,
                                "approval_code", "676B4BD4-0B2D-473A-BC8B-5F908ED3AF0C",
                                "approval_name", "智数平台-报表权限申请",
                                "status", "PENDING",
                                "open_id", "ou_app",
                                "user_id", "13800138000",
                                "form", "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":["
                                        + "[{\"name\":\"报表全路径\",\"value\":\"01生产环境/原已有报表.cpt\"}],"
                                        + "[{\"name\":\"报表全路径\",\"value\":\"01生产环境/新开通报表.cpt\"}]]}]",
                                "task_list", List.of(
                                        Map.of("id", "t1", "node_name", "数据主管审批", "status", "APPROVED"),
                                        Map.of("id", "t2", "node_name", "技术Owner办理", "status", "PENDING", "open_id", "ou_handler")
                                )
                        );
                    }

                    @Override
                    public Map<String, Object> getContactUser(String userIdOrOpenId, String idType) {
                        return Map.of("name", "蒋丹敏", "enterprise_email", "danmin-jiang@furniwell.com");
                    }

                    @Override
                    public boolean sendApprovalNotificationCard(String receiveId, String receiveIdType,
                                                                String applicantName, String serialNumber,
                                                                List<String> newlyGrantedReports,
                                                                List<String> alreadyGrantedReports,
                                                                List<String> unmappedReports) {
                        cardNew.set(newlyGrantedReports);
                        cardOld.set(alreadyGrantedReports);
                        return true;
                    }
                };

        com.example.datafill.service.finereport.FineReportApiClient stubFr =
                new com.example.datafill.service.finereport.FineReportApiClient(new com.example.datafill.config.FineReportProperties()) {
                    @Override
                    public Map<String, FineReportUser> getAllUsers() {
                        FineReportUser u = new FineReportUser();
                        u.setId("carrier-1");
                        u.setUsername("danmin-jiang");
                        u.setRealName("蒋丹敏");
                        u.setEmail("danmin-jiang@furniwell.com");
                        return Map.of("danmin-jiang", u);
                    }

                    @Override
                    public Map<String, Boolean> getUserExistingReportAuthorities(String carrierId, List<String> reportEntryIds) {
                        return Map.of("id-old", true, "id-new", false);
                    }

                    @Override
                    public Map<String, Boolean> grantReportAuthoritiesToUserBatch(String carrierId, List<String> reportEntryIds) {
                        grantedBatchCalls.addAll(reportEntryIds);
                        Map<String, Boolean> res = new java.util.HashMap<>();
                        for (String id : reportEntryIds) {
                            res.put(id, true);
                        }
                        return res;
                    }
                };

        com.example.datafill.service.ReportCatalogService stubCatalog = new com.example.datafill.service.ReportCatalogService() {
            @Override
            public List<com.example.datafill.entity.ReportCatalog> listCatalogs() {
                var r1 = new com.example.datafill.entity.ReportCatalog();
                r1.setReportId("id-old");
                r1.setReportName("原已有报表");
                r1.setFullPath("01生产环境/原已有报表.cpt");
                var r2 = new com.example.datafill.entity.ReportCatalog();
                r2.setReportId("id-new");
                r2.setReportName("新开通报表");
                r2.setFullPath("01生产环境/新开通报表.cpt");
                return List.of(r1, r2);
            }
        };

        FeishuApprovalAuditService stubAudit = new FeishuApprovalAuditService() {
            @Override
            public void recordAudit(String instanceCode, String approvalName, String applyType, String applicantName,
                                    String targetUsername, String carrierId, List<String> requestedItems,
                                    List<String> newlyGrantedItems, List<String> alreadyGrantedItems,
                                    List<String> unmappedItems, List<String> failedItems, String status,
                                    String message, List<String> notifiedTargets) {
                auditNew.set(newlyGrantedItems);
                auditOld.set(alreadyGrantedItems);
                auditStatus.set(status);
            }
        };

        FeishuApprovalAutomationService service = new FeishuApprovalAutomationService(
                stubFeishu, new FeishuApprovalParserService(), stubFr, objectMapper, props, stubCatalog, null
        );
        service.setFeishuApprovalAuditService(stubAudit);

        var res = service.executeFullPipeline("INST-PRECHECK-1");
        assertTrue(res.isSuccess());
        assertEquals(List.of("id-new"), grantedBatchCalls, "原已有报表不应重复发起 PUT 赋权");
        assertEquals(List.of("新开通报表"), auditNew.get());
        assertEquals(List.of("原已有报表"), auditOld.get());
        assertEquals(List.of("新开通报表"), cardNew.get());
        assertEquals(List.of("原已有报表"), cardOld.get());
        assertEquals("SUCCESS", auditStatus.get());
    }

    @Test
    public void testGetUserExistingReportAuthoritiesOnlyTrustsValue2Allow() {
        String sampleJson = """
                {
                  "data": {
                    "entry": [
                      {"id": "entry-allow", "highLight": true, "values": [{"privilegeType": 1, "privilegeValue": 2}]},
                      {"id": "entry-partial", "highLight": true, "values": [{"privilegeType": 1, "privilegeValue": 3}]},
                      {"id": "entry-none", "highLight": false, "values": []}
                    ]
                  }
                }
                """;
        var props = new com.example.datafill.config.FineReportProperties();
        props.setBaseUrl("http://127.0.0.1:8081/webroot/decision");
        var client = new com.example.datafill.service.finereport.FineReportApiClient(props) {
            @Override
            public String getAuthToken() {
                return "mock-token";
            }
        };
        var server = org.springframework.test.web.client.MockRestServiceServer.createServer(
                (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "restTemplate"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        sampleJson, org.springframework.http.MediaType.APPLICATION_JSON));

        Map<String, Boolean> res = client.getUserExistingReportAuthorities(
                "carrier-1", List.of("entry-allow", "entry-partial", "entry-none"));
        server.verify();
        assertNotNull(res);
        assertEquals(Boolean.TRUE, res.get("entry-allow"), "privilegeValue=2 才应判定为已有查看权限");
        assertEquals(Boolean.FALSE, res.get("entry-partial"), "privilegeValue=3 保守不跳过，绝不漏开");
        assertEquals(Boolean.FALSE, res.get("entry-none"));
    }

    @Test
    public void testReportApprovalWithRemarkContainingPermissionDoesNotMisrouteToRoleApply() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        String formJson = "["
                + "{\"name\":\"申请类型\",\"value\":\"新增报表查看\"},"
                + "{\"name\":\"备注说明\",\"value\":\"需要开通销售报表权限\"},"
                + "{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[[{\"name\":\"报表全路径\",\"value\":\"01生产环境/销售分析.cpt\"}]]}"
                + "]";
        Map<String, Object> raw = Map.of(
                "instance_code", "INST-REPORT-GUARD-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "PENDING",
                "form", formJson
        );
        ExtractedApprovalData data = parser.parseApprovalInstance(raw);
        assertFalse(data.isRoleApply(), "报表权限申请单即使备注含“权限”或带申请类型字段也绝不能误路由到角色流程");
        assertEquals(1, data.getReportItems().size());
    }

    @Test
    public void testParseRowPreservesFullPathWhenFileColumnComesLater() {
        FeishuApprovalParserService parser = new FeishuApprovalParserService();
        String formJson = "[{\"name\":\"明细\",\"type\":\"fieldList\",\"value\":[["
                + "{\"name\":\"报表全路径\",\"value\":\"美鹰集团报表/01生产环境/02.供应链管理/03.供应商管理分析/03供应商管理报表.cpt\"},"
                + "{\"name\":\"L5\",\"value\":\"03供应商管理报表.cpt\"}"
                + "]]}]";

        Map<String, Object> raw = Map.of(
                "instance_code", "INST-COL-ORDER-1",
                "approval_name", "智数平台-报表权限申请",
                "status", "APPROVED",
                "form", formJson
        );

        ExtractedApprovalData parsed = parser.parseApprovalInstance(raw);
        assertEquals(1, parsed.getReportItems().size());
        assertEquals("美鹰集团报表/01生产环境/02.供应链管理/03.供应商管理分析/03供应商管理报表.cpt",
                parsed.getReportItems().get(0).getRawPath());
    }
}
