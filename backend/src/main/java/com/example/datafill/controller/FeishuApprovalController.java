package com.example.datafill.controller;

import com.example.datafill.service.feishu.approval.FeishuApprovalAutomationService;
import com.example.datafill.service.feishu.approval.FeishuApprovalAutomationService.ApprovalProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 飞书审批自动化接入控制器
 * 
 * 端点说明：
 * 1. GET  /api/feishu/approval/instance/{instanceCode} : 手动/在线测试指定审批单解析 (传 mock 则返回模拟结果)
 * 2. POST /api/feishu/approval/webhook                  : 飞书开放平台事件订阅 Webhook 接收端
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu/approval")
@RequiredArgsConstructor
public class FeishuApprovalController {

    private final com.example.datafill.config.FeishuProperties feishuProperties;
    private final FeishuApprovalAutomationService feishuApprovalAutomationService;
    private final com.example.datafill.service.feishu.FeishuApiClient feishuApiClient;

    /**
     * 容器就绪后自动向飞书开放平台激活审批定义事件订阅
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("【飞书审批】系统启动就绪，正在自动向飞书开放平台激活审批事件订阅...");
        try {
            if (feishuProperties.getApproval() != null && feishuProperties.getApproval().getTargetCodes() != null) {
                for (String code : feishuProperties.getApproval().getTargetCodes()) {
                    if (code != null && code.length() > 15 && !code.endsWith("-")) {
                        Map<String, Object> res = feishuApiClient.subscribeApprovalDefinitionDetailed(code.trim());
                        log.info("【飞书审批自激活】绑定审批流程 [{}] 结果: {}", code, res);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("【飞书审批自激活】提示: {}", e.getMessage());
        }
    }

    /**
     * 手动触发向飞书开放平台订阅审批定义事件 (POST /open-apis/approval/v4/approvals/{code}/subscribe)
     * 支持在浏览器中直接打开检验：
     * GET /api/feishu/approval/subscribe
     * GET /api/feishu/approval/subscribe/{code}
     */
    @GetMapping({"/subscribe", "/subscribe/{code}"})
    public ResponseEntity<?> subscribeApproval(
            @PathVariable(value = "code", required = false) String code) {
        List<String> codesToSub = new ArrayList<>();
        if (code != null && !code.isBlank()) {
            codesToSub.add(code.trim());
        } else if (feishuProperties.getApproval() != null && feishuProperties.getApproval().getTargetCodes() != null) {
            for (String targetCode : feishuProperties.getApproval().getTargetCodes()) {
                if (targetCode != null && targetCode.length() > 15 && !targetCode.endsWith("-")) {
                    codesToSub.add(targetCode.trim());
                }
            }
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (String c : codesToSub) {
            Map<String, Object> subRes = feishuApiClient.subscribeApprovalDefinitionDetailed(c);
            results.put(c, subRes);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "飞书审批定义事件订阅处理完毕",
                "targetCodes", codesToSub,
                "results", results
        ));
    }

    /**
     * 在线测试指定审批单据明细解析
     * 支持在浏览器中直接测试：
     * - 测试真实样本：GET /api/feishu/approval/instance/sample
     * - 测试 Mock：GET /api/feishu/approval/instance/mock
     * - 测试真实单据：GET /api/feishu/approval/instance/{真实instanceCode}
     */
    @GetMapping("/instance/{instanceCode}")
    public ResponseEntity<?> testExtractInstance(@PathVariable("instanceCode") String instanceCode) {
        ApprovalProcessResult result = feishuApprovalAutomationService.fetchAndExtract(instanceCode);
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发全流程执行 (解析 + 匹配用户 + 调帆软API赋权 + 飞书机器人推卡片)
     * 支持在浏览器或 Postman 测试：
     * - 执行真实样本：GET/POST /api/feishu/approval/execute/sample
     * - 执行 Mock：GET/POST /api/feishu/approval/execute/mock
     * - 执行真实实例：GET/POST /api/feishu/approval/execute/{instanceCode}
     */
    @RequestMapping(value = "/execute/{instanceCode}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> executePipeline(
            @PathVariable("instanceCode") String instanceCode,
            @RequestParam(value = "email", required = false) String email) {
        ApprovalProcessResult result = feishuApprovalAutomationService.executeFullPipeline(instanceCode, email);
        return ResponseEntity.ok(result);
    }

    /**
     * Webhook 健康检查端点 (支持在浏览器直接 GET 验证域名与 Nginx 路由是否通畅)
     */
    @GetMapping({"/webhook", "/webhock"})
    public ResponseEntity<?> testWebhookGet() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "message", "飞书审批 Webhook 回调服务运行正常！",
                "timestamp", System.currentTimeMillis(),
                "time", new Date().toString()
        ));
    }

    /**
     * 飞书开放平台事件订阅 Webhook 回调接收器
     * 处理逻辑：
     * 1. URL 挑战握手请求 (含 challenge 参数) -> 直接原样返回以完成飞书配置验证
     * 2. 审批实例事件通知 (approval_instance 状态变为 APPROVED) -> 毫秒级异步分发处理
     */
    @PostMapping({"/webhook", "/webhock"})
    public ResponseEntity<?> handleWebhook(
            @RequestBody(required = false) Map<String, Object> payload,
            jakarta.servlet.http.HttpServletRequest request) {

        if (payload == null || payload.isEmpty()) {
            log.info("【飞书 Webhook】收到空 Body 的探测请求 (IP={}, User-Agent={})，安全返回 success (code=0)",
                    request != null ? request.getRemoteAddr() : "unknown",
                    request != null ? request.getHeader("User-Agent") : "unknown");
            return ResponseEntity.ok(Map.of("code", 0, "msg", "empty payload received"));
        }

        log.info("【飞书 Webhook】收到 HTTP 回调请求 payload keys: {}", payload.keySet());

        // 0. 若配置了 Verification Token，校验请求头/事件中的 token 防止伪造回调
        String expectedToken = feishuProperties.getVerificationToken();
        if (expectedToken != null && !expectedToken.isEmpty()) {
            String incomingToken = extractWebhookToken(payload);
            if (!java.security.MessageDigest.isEqual(
                    expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    incomingToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                log.warn("【飞书 Webhook】Verification Token 校验失败，已拒绝请求 (IP={})",
                        request != null ? request.getRemoteAddr() : "unknown");
                return ResponseEntity.status(401).body(Map.of("code", 401, "msg", "invalid verification token"));
            }
        }

        // 1. 飞书首次配置 Webhook URL 时的握手验证
        if (payload.containsKey("challenge")) {
            String challenge = String.valueOf(payload.get("challenge"));
            log.info("【飞书 Webhook】收到 URL 挑战握手请求 challenge={}", challenge);
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // 1.1 若开启了加密策略提醒
        if (payload.containsKey("encrypt")) {
            log.warn("⚠️ 收到加密 Webhook 事件 (encrypt)，当前系统接收明文事件，请在飞书开放平台「事件与回调 - 加密策略」中将 Encrypt Key 置空或关闭加密策略。");
            return ResponseEntity.ok(Map.of("code", 0, "msg", "encrypted payload not supported"));
        }

        // 2. 解析飞书推送的业务事件 (深度兼容 v1.0 与 v2.0 事件结构)
        try {
            Map<String, Object> header = (payload.get("header") instanceof Map) ? (Map<String, Object>) payload.get("header") : null;
            Map<String, Object> event = (payload.get("event") instanceof Map) ? (Map<String, Object>) payload.get("event") : null;

            String eventType = "";
            if (header != null && header.containsKey("event_type")) {
                eventType = String.valueOf(header.get("event_type"));
            } else if (event != null && event.containsKey("type")) {
                eventType = String.valueOf(event.get("type"));
            } else if (payload.containsKey("type")) {
                eventType = String.valueOf(payload.get("type"));
            }
            log.info("【飞书 Webhook】收到事件通知: eventType={}, isV2={}, payloadKeys={}",
                    eventType, event != null, payload.keySet());

            // 🌟 核心兼容：如果是 v1.0 事件，数据直接位于根 payload 中；如果是 v2.0 事件，数据位于 event 对象中
            Map<String, Object> eventData = (event != null) ? event : payload;

            String approvalCode = String.valueOf(eventData.getOrDefault("approval_code", eventData.getOrDefault("approvalCode", "")));
            String approvalName = String.valueOf(eventData.getOrDefault("approval_name", eventData.getOrDefault("approvalName", "")));
            String instanceCode = String.valueOf(eventData.getOrDefault("instance_code", eventData.getOrDefault("instanceCode", "")));
            String status = String.valueOf(eventData.getOrDefault("status", ""));

            // 🛡️ 流程白名单精准过滤：优先按 approval_code 匹配，其次按 approval_name 匹配
            if ((!approvalCode.isEmpty() || !approvalName.isEmpty())
                    && !feishuProperties.isApprovalAllowed(approvalCode, approvalName)) {
                log.info("【飞书 Webhook】安全忽略非目标审批: code={}, name={}, instanceCode={}", approvalCode, approvalName, instanceCode);
                return ResponseEntity.ok(Map.of("code", 0, "msg", "ignored non-target approval: " + approvalCode));
            }

            if (!instanceCode.isBlank() && !"null".equalsIgnoreCase(instanceCode)) {
                // 当审批状态为 APPROVED (通过)，或任务节点更新 (如数据主管通过流转至办理人) 时触发自动赋权检查
                if ("APPROVED".equalsIgnoreCase(status) || "pass".equalsIgnoreCase(status)
                        || eventType.contains("task") || eventType.contains("approval_task") || "PENDING".equalsIgnoreCase(status)) {
                    log.info("【飞书 Webhook】目标审批流程事件通知，触发自动授权/节点核验: code={}, name={}, instanceCode={}, status={}, eventType={}",
                            approvalCode, approvalName, instanceCode, status, eventType);
                    feishuApprovalAutomationService.processApprovalEventAsync(instanceCode, eventType);
                } else {
                    log.info("【飞书 Webhook】收到审批状态流转通知 (无需处理): status={}, instanceCode={}", status, instanceCode);
                }
            } else {
                log.info("【飞书 Webhook】通知中未包含 instance_code，忽略处理: eventDataKeys={}", eventData.keySet());
            }

            return ResponseEntity.ok(Map.of("code", 0, "msg", "success"));

        } catch (Exception e) {
            log.error("【飞书 Webhook】处理回调通知异常: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("code", 0, "msg", "handled with error"));
        }
    }

    private String extractWebhookToken(Map<String, Object> payload) {
        if (payload.get("header") instanceof Map<?, ?> headerMap && headerMap.get("token") != null) {
            return String.valueOf(headerMap.get("token")).trim();
        }
        if (payload.get("token") != null) {
            return String.valueOf(payload.get("token")).trim();
        }
        return "";
    }
}
