package com.example.datafill.controller;

import com.example.datafill.config.FineReportProperties;
import com.example.datafill.service.finereport.FineReportApiClient;
import com.example.datafill.service.finereport.FineReportUserRoleService;
import com.example.datafill.service.finereport.FineReportUserRoleService.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 帆软用户角色批量同步控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/finereport/roles")
@RequiredArgsConstructor
public class FineReportRoleController {

    private final FineReportProperties fineReportProperties;
    private final FineReportApiClient fineReportApiClient;
    private final FineReportUserRoleService fineReportUserRoleService;

    @Data
    public static class ConfigUpdateRequest {
        private String baseUrl;
        private String adminUsername;
        private String adminPassword;
    }

    @Data
    public static class PreviewRequest {
        private String feishuUrl;
        private String sheetId;
        private String syncMode; // incremental | overwrite
    }

    @Data
    public static class SheetsQueryRequest {
        private String feishuUrl;
    }

    /**
     * 获取帆软 API 配置信息
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("baseUrl", fineReportProperties.getBaseUrl());
        map.put("adminUsername", fineReportProperties.getAdminUsername());
        map.put("hasPassword", fineReportProperties.getAdminPassword() != null && !fineReportProperties.getAdminPassword().isBlank());
        return ResponseEntity.ok(map);
    }

    /**
     * 更新帆软 API 连接配置
     */
    @PostMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody ConfigUpdateRequest req) {
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            fineReportProperties.setBaseUrl(req.getBaseUrl().replaceAll("[;；\\s/]+$", "").trim());
        }
        if (req.getAdminUsername() != null && !req.getAdminUsername().isBlank()) {
            fineReportProperties.setAdminUsername(req.getAdminUsername().trim());
        }
        if (req.getAdminPassword() != null && !req.getAdminPassword().isBlank()) {
            fineReportProperties.setAdminPassword(req.getAdminPassword().trim());
        }
        fineReportApiClient.invalidateToken();
        return ResponseEntity.ok(Map.of("success", true, "message", "配置已保存并生效"));
    }

    /**
     * 测试帆软 11.5.6 API 连通性
     */
    @PostMapping("/test-connect")
    public ResponseEntity<?> testConnect() {
        Map<String, Object> result = fineReportApiClient.testConnection();
        return ResponseEntity.ok(result);
    }

    /**
     * 根据飞书表格链接提取所有工作表 Tabs
     */
    @PostMapping("/sheets")
    public ResponseEntity<?> getSheets(@RequestBody SheetsQueryRequest req) {
        try {
            if (req.getFeishuUrl() == null || req.getFeishuUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请提供有效的飞书表格链接"));
            }
            List<Map<String, Object>> sheets = fineReportUserRoleService.getSpreadsheetTabs(req.getFeishuUrl());
            return ResponseEntity.ok(Map.of("success", true, "sheets", sheets));
        } catch (Exception e) {
            log.warn("提取飞书表格工作表失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 从飞书在线表格读取角色并生成 Diff 比对预览
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody PreviewRequest req) {
        try {
            if (req.getFeishuUrl() == null || req.getFeishuUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请填写飞书表格链接"));
            }
            RolePreviewResult result = fineReportUserRoleService.previewFromFeishu(
                    req.getFeishuUrl(),
                    req.getSheetId(),
                    req.getSyncMode() != null ? req.getSyncMode() : "incremental"
            );
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            log.error("生成角色差异预览失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "message", "解析或比对失败: " + e.getMessage()));
        }
    }

    /**
     * 执行批量同步到帆软 11.5.6 决策系统
     */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody RoleApplyRequest req) {
        try {
            if (req.getItems() == null || req.getItems().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "没有可同步的员工项"));
            }
            RoleApplyResult result = fineReportUserRoleService.applyBatchUserRoles(req);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "批量同步完成，成功: " + result.getSuccessCount() + " 人，失败: " + result.getFailCount() + " 人",
                    "data", result
            ));
        } catch (Exception e) {
            log.error("批量同步执行异常: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "message", "执行同步发生异常: " + e.getMessage()));
        }
    }

    /**
     * 获取历史同步快照审计记录
     */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(defaultValue = "30") int limit) {
        try {
            List<?> list = fineReportUserRoleService.getSyncHistoryList(limit);
            return ResponseEntity.ok(Map.of("success", true, "data", list));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "获取同步历史失败: " + e.getMessage()));
        }
    }

    /**
     * 针对指定历史批次执行一键回滚
     */
    @PostMapping("/rollback/{historyId}")
    public ResponseEntity<?> rollback(@PathVariable String historyId, @RequestParam(defaultValue = "admin") String operator) {
        try {
            Map<String, Object> res = fineReportUserRoleService.rollbackHistory(historyId, operator);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("回滚异常: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "message", "回滚失败: " + e.getMessage()));
        }
    }

    /**
     * 调试诊断接口：深入探测帆软系统中的原始角色列表与指定用户的原始 JSON 结构
     */
    @GetMapping("/debug")
    public ResponseEntity<?> debug(@RequestParam(defaultValue = "donghao-he") String username) {
        Map<String, Object> debugInfo = fineReportApiClient.debugInspect(username);
        return ResponseEntity.ok(debugInfo);
    }
}
