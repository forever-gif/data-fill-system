package com.example.datafill.controller;

import com.example.datafill.service.feishu.FeishuApiClient;
import com.example.datafill.service.feishu.FeishuWikiSyncService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 飞书知识库与云文档自动化同步控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu")
@RequiredArgsConstructor
public class FeishuSyncController {

    private final FeishuWikiSyncService feishuWikiSyncService;
    private final FeishuApiClient feishuApiClient;
    private final com.example.datafill.config.FeishuProperties feishuProperties;

    @Data
    public static class SyncRequest {
        private String appId;
        private String appSecret;
        private String spaceId;
        private Boolean force;
        private List<String> reportIds;
    }

    /**
     * 获取 yml 中的飞书默认配置参数
     */
    @GetMapping("/config")
    public ResponseEntity<?> getDefaultConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("appId", feishuProperties.getAppId() != null ? feishuProperties.getAppId() : "");
        config.put("appSecret", feishuProperties.getAppSecret() != null ? feishuProperties.getAppSecret() : "");
        config.put("spaceId", feishuProperties.getWikiSpaceId() != null ? feishuProperties.getWikiSpaceId() : "");
        config.put("wikiDocUrl", feishuWikiSyncService.getCachedDocUrl() != null ? feishuWikiSyncService.getCachedDocUrl() : "https://feishu.cn/wiki/Q58FwZAMeiobL3kdmWFc6zwcnSq");
        return ResponseEntity.ok(config);
    }

    /**
     * 测试飞书凭证与网络连通性
     */
    @PostMapping("/test-connect")
    public ResponseEntity<?> testConnect(@RequestBody(required = false) SyncRequest req) {
        try {
            String appId = req != null ? req.getAppId() : null;
            String appSecret = req != null ? req.getAppSecret() : null;
            String inputSpace = req != null ? req.getSpaceId() : null;
            String token = feishuApiClient.getTenantAccessToken(appId, appSecret);
            List<Map<String, Object>> spaces = new ArrayList<>(feishuApiClient.listAccessibleSpaces(token));

            StringBuilder msg = new StringBuilder("飞书 API 连通性测试通过！");

            // 如果用户输入了 node_token 或 URL，主动尝试解析
            if (inputSpace != null && !inputSpace.isBlank()) {
                Map<String, Object> nodeInfo = feishuApiClient.getNodeInfo(inputSpace, token);
                if (nodeInfo != null && nodeInfo.containsKey("space_id")) {
                    String spaceId = (String) nodeInfo.get("space_id");
                    String title = (String) nodeInfo.getOrDefault("title", "数仓知识库");
                    Map<String, Object> resolvedSpace = Map.of(
                            "space_id", spaceId,
                            "name", title,
                            "node_token", nodeInfo.getOrDefault("node_token", "")
                    );
                    spaces.add(resolvedSpace);
                    msg.append(" 成功从 Token 解析出空间: 【").append(title).append(" (ID: ").append(spaceId).append(")】！");
                }
            }

            if (!spaces.isEmpty()) {
                if (!msg.toString().contains("成功从 Token 解析")) {
                    msg.append(" 当前应用已关联以下知识库空间: ");
                    for (Map<String, Object> sp : spaces) {
                        msg.append("【").append(sp.get("name")).append(" (ID: ").append(sp.get("space_id")).append(")】 ");
                    }
                }
            } else {
                msg.append(" 提示：可直接填入文档链接或 Token，系统将自动识别空间并写入。");
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", msg.toString(),
                    "spaces", spaces,
                    "tokenPrefix", token.substring(0, Math.min(10, token.length())) + "..."
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "飞书连通性测试失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 全量同步报表资产至飞书知识库 (支持增量跳过或强制全量)
     */
    @PostMapping("/sync/all")
    public ResponseEntity<?> syncAllReports(@RequestBody(required = false) SyncRequest req) {
        try {
            String appId = req != null ? req.getAppId() : null;
            String appSecret = req != null ? req.getAppSecret() : null;
            String spaceId = req != null ? req.getSpaceId() : null;
            Boolean force = req != null ? req.getForce() : false;

            Map<String, Object> result = feishuWikiSyncService.syncAllReportsToFeishu(appId, appSecret, spaceId, force);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("全量同步飞书知识库失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "同步失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 手动批量同步选中的报表资产至飞书知识库
     */
    @PostMapping("/sync/batch")
    public ResponseEntity<?> syncBatchReports(@RequestBody SyncRequest req) {
        try {
            if (req == null || req.getReportIds() == null || req.getReportIds().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "请勾选需要同步的报表！"
                ));
            }

            String appId = req.getAppId();
            String appSecret = req.getAppSecret();
            String spaceId = req.getSpaceId();
            Boolean force = req.getForce() != null ? req.getForce() : true; // 批量手动选中的默认强制更新

            Map<String, Object> result = feishuWikiSyncService.syncBatchReportsToFeishu(req.getReportIds(), appId, appSecret, spaceId, force);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("批量同步飞书知识库失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "批量同步失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 同步单个报表至飞书知识库
     */
    @PostMapping("/sync/report/{reportId}")
    public ResponseEntity<?> syncSingleReport(
            @PathVariable String reportId,
            @RequestBody(required = false) SyncRequest req) {
        try {
            String appId = req != null ? req.getAppId() : null;
            String appSecret = req != null ? req.getAppSecret() : null;
            String spaceId = req != null ? req.getSpaceId() : null;

            Map<String, Object> result = feishuWikiSyncService.syncSingleReport(reportId, appId, appSecret, spaceId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("单篇同步飞书知识库失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "同步失败: " + e.getMessage()
            ));
        }
    }
}
