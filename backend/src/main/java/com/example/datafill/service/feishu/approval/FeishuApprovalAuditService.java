package com.example.datafill.service.feishu.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 飞书审批自动赋权/建号审计落库服务
 * 静默将每次审批自动处理明细（本次新开通、原已有跳过、未匹配、失败及拦截原因）写入主库表 feishu_approval_audit_log，
 * 不改动任何前端页面与飞书卡片样式，方便直接通过 SQL 查询追溯。
 */
@Service
@Slf4j
public class FeishuApprovalAuditService {

    @Autowired(required = false)
    @Qualifier("primaryJdbcTemplate")
    private JdbcTemplate primaryJdbcTemplate;

    @Autowired(required = false)
    private JdbcTemplate defaultJdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean tableEnsured = false;

    private JdbcTemplate getJdbcTemplate() {
        return primaryJdbcTemplate != null ? primaryJdbcTemplate : defaultJdbcTemplate;
    }

    @PostConstruct
    public void ensureAuditTable() {
        JdbcTemplate jdbc = getJdbcTemplate();
        if (jdbc == null || tableEnsured) {
            return;
        }
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS feishu_approval_audit_log (
                        id                    VARCHAR(64) PRIMARY KEY,
                        instance_code         VARCHAR(100),
                        approval_name         VARCHAR(255),
                        apply_type            VARCHAR(100),
                        applicant_name        VARCHAR(100),
                        target_username       VARCHAR(100),
                        carrier_id            VARCHAR(100),
                        requested_items       TEXT,
                        newly_granted_items   TEXT,
                        already_granted_items TEXT,
                        unmapped_items        TEXT,
                        failed_items          TEXT,
                        status                VARCHAR(32),
                        message               TEXT,
                        notified_targets      TEXT,
                        create_time           TIMESTAMP
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feishu_audit_inst ON feishu_approval_audit_log(instance_code)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feishu_audit_time ON feishu_approval_audit_log(create_time DESC)");
            tableEnsured = true;
        } catch (Exception e) {
            log.warn("【飞书审批审计表】初始化建表提示: {}", e.getMessage());
        }
    }

    public void recordAudit(
            String instanceCode,
            String approvalName,
            String applyType,
            String applicantName,
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
        JdbcTemplate jdbc = getJdbcTemplate();
        if (jdbc == null) {
            return;
        }
        try {
            if (!tableEnsured) {
                ensureAuditTable();
            }
            String sql = """
                    INSERT INTO feishu_approval_audit_log (
                        id, instance_code, approval_name, apply_type, applicant_name,
                        target_username, carrier_id, requested_items, newly_granted_items,
                        already_granted_items, unmapped_items, failed_items, status,
                        message, notified_targets, create_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            jdbc.update(sql,
                    UUID.randomUUID().toString(),
                    safeStr(instanceCode, 100),
                    safeStr(approvalName, 255),
                    safeStr(applyType, 100),
                    safeStr(applicantName, 100),
                    safeStr(targetUsername, 100),
                    safeStr(carrierId, 100),
                    toJson(requestedItems),
                    toJson(newlyGrantedItems),
                    toJson(alreadyGrantedItems),
                    toJson(unmappedItems),
                    toJson(failedItems),
                    safeStr(status, 32),
                    message,
                    toJson(notifiedTargets),
                    Timestamp.valueOf(LocalDateTime.now())
            );
        } catch (Exception e) {
            log.warn("【飞书审批审计表】记录流水失败 (不影响主流程): instanceCode={}, err={}", instanceCode, e.getMessage());
        }
    }

    private String toJson(List<String> items) {
        try {
            return objectMapper.writeValueAsString(items != null ? items : Collections.emptyList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private String safeStr(String val, int maxLen) {
        if (val == null) return null;
        String trimmed = val.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }
}
