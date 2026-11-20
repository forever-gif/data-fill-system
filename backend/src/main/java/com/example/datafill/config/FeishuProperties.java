package com.example.datafill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书开放平台与知识库同步配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /**
     * 飞书自建应用 App ID (如 cli_a1b2c3d4e5f6)
     */
    private String appId;

    /**
     * 飞书自建应用 App Secret
     */
    private String appSecret;

    /**
     * 飞书知识库空间 ID (Space ID)
     */
    private String wikiSpaceId;

    /**
     * 可选：根节点 Token，留空则默认挂载在知识库根目录
     */
    private String rootNodeToken;

    /**
     * 帆软报表前台基础访问 URL
     */
    private String reportBaseUrl = "https://bi.meiying.homes/webroot/decision/#?activeTab=";

    /**
     * 报表权限自动化审批专用应用配置 (独立凭证)
     */
    private ApprovalConfig approval = new ApprovalConfig();

    @Data
    public static class ApprovalConfig {
        /**
         * 审批专用应用 App ID
         */
        private String appId;
        /**
         * 审批专用应用 App Secret
         */
        private String appSecret;

        /**
         * 允许自动触发的审批定义编码白名单 (approval_code，优先匹配，不受表单改名影响)
         */
        private java.util.List<String> targetCodes = new java.util.ArrayList<>();

        /**
         * 允许自动触发的审批流名称白名单 (支持多个流程名称精准匹配)
         */
        private java.util.List<String> targetNames = new java.util.ArrayList<>();

        /**
         * 是否开启安全试运行模式 (Dry-Run):
         * true: 仅模拟执行，解析单据并比对用户，打印拟操作日志，绝不真正修改帆软后台权限;
         * false: 正式上线模式，真正调用帆软 API 赋权并推飞书卡片通知。
         */
        private boolean dryRun = true;

        /**
         * 🌟 测试环境严格安全隔离模式 (默认开启):
         * true: 强制开启生产安全守卫，向帆软写权限时只允许操作测试白名单账号 (绝不碰任何其他真实生产账号);
         * false: 生产正式模式。
         */
        private boolean testMode = true;

        /**
         * 测试环境允许操作的测试账号白名单 (用户名，如 donghao-he)
         */
        private java.util.List<String> allowedTestUsers = new java.util.ArrayList<>(java.util.List.of("donghao-he"));

        /**
         * 测试期是否将其他人的申请安全重定向给测试账号验收 (方便测试人登录自己账号查看权限开通效果):
         * true: 无论同事谁提的单，测试期权限只给测试账号 (如 donghao-he)，同事真实账号完全不受触碰;
         * false: 若不是测试人本人申请，直接拒绝写入。
         */
        private boolean redirectOthersToTestUser = true;

        /**
         * 默认测试账号用户名
         */
        private String defaultTestUser = "donghao-he";

        /**
         * 默认测试账号邮箱 (用于测试环境接收机器人卡片提醒)
         */
        private String defaultTestEmail = "donghao-he@furniwell.com";

        /**
         * 默认测试账号飞书 Open ID (精准推送直达)
         */
        private String defaultTestOpenId = "";

        /**
         * 可选：飞书事件订阅 Verification Token（配置后会在 Webhook 入口校验来路合法性）
         */
        private String verificationToken = "";
    }

    public String getVerificationToken() {
        return (approval != null && approval.getVerificationToken() != null)
                ? approval.getVerificationToken().trim()
                : "";
    }

    public String getDefaultTestEmail() {
        return (approval != null && approval.getDefaultTestEmail() != null && !approval.getDefaultTestEmail().isBlank())
                ? approval.getDefaultTestEmail().trim()
                : "donghao-he@furniwell.com";
    }

    public String getDefaultTestOpenId() {
        return (approval != null && approval.getDefaultTestOpenId() != null)
                ? approval.getDefaultTestOpenId().trim()
                : "";
    }

    public boolean isApprovalDryRun() {
        return approval != null && approval.isDryRun();
    }

    public boolean isApprovalTestMode() {
        return approval != null && approval.isTestMode();
    }

    public boolean isUserAllowedInTest(String username) {
        if (!isApprovalTestMode()) return true;
        if (username == null || username.isBlank()) return false;
        String cleanUser = username.split("@")[0].toLowerCase().trim();
        return approval.getAllowedTestUsers().stream()
                .anyMatch(u -> cleanUser.equalsIgnoreCase(u.split("@")[0].toLowerCase().trim()));
    }

    public boolean shouldRedirectOthersToTestUser() {
        return isApprovalTestMode() && approval != null && approval.isRedirectOthersToTestUser();
    }

    public String getDefaultTestUser() {
        return (approval != null && approval.getDefaultTestUser() != null && !approval.getDefaultTestUser().isBlank())
                ? approval.getDefaultTestUser().trim()
                : "donghao-he";
    }

    /**
     * 校验审批定义编码 (approval_code) 是否在白名单中
     */
    public boolean isApprovalCodeAllowed(String approvalCode) {
        if (approvalCode == null || approvalCode.isBlank()) {
            return false;
        }
        String cleanCode = approvalCode.trim().toUpperCase();
        if (approval != null && approval.getTargetCodes() != null && !approval.getTargetCodes().isEmpty()) {
            for (String target : approval.getTargetCodes()) {
                if (target == null || target.isBlank()) continue;
                String cleanTarget = target.trim().toUpperCase();
                if (cleanCode.equals(cleanTarget) || cleanCode.contains(cleanTarget) || cleanTarget.contains(cleanCode)) {
                    return true;
                }
                // 兼容前8位-后8位简写匹配 (如 2D598A70-6470318A 匹配 2D598A70-XXXX-XXXX-XXXX-XXXX6470318A)
                String[] parts = cleanTarget.split("-");
                if (parts.length == 2 && cleanCode.startsWith(parts[0]) && cleanCode.endsWith(parts[1])) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * 综合校验审批单 (优先按 approval_code 匹配，其次按 approval_name 匹配)
     */
    public boolean isApprovalAllowed(String approvalCode, String approvalName) {
        boolean hasTargetCodes = approval != null && approval.getTargetCodes() != null && !approval.getTargetCodes().isEmpty();
        if (hasTargetCodes && approvalCode != null && !approvalCode.isBlank() && !"null".equalsIgnoreCase(approvalCode)) {
            return isApprovalCodeAllowed(approvalCode);
        }
        if (approvalName != null && !approvalName.isBlank() && !"null".equalsIgnoreCase(approvalName)) {
            return isApprovalNameAllowed(approvalName);
        }
        return true;
    }

    /**
     * 校验审批流程名称是否在白名单中
     */
    public boolean isApprovalNameAllowed(String approvalName) {
        if (approvalName == null || approvalName.isBlank()) {
            return false;
        }
        String cleanName = approvalName.trim();
        if (approval != null && approval.getTargetNames() != null && !approval.getTargetNames().isEmpty()) {
            return approval.getTargetNames().stream()
                    .anyMatch(target -> cleanName.contains(target.trim()) || target.trim().contains(cleanName));
        }
        // 默认兜底策略：包含“报表”关键字的流程放行
        return cleanName.contains("报表") || cleanName.contains("帆软");
    }

    /**
     * 获取审批专用的 App ID (若未单独配置则退回默认 appId)
     */
    public String getEffectiveApprovalAppId() {
        if (approval != null && approval.getAppId() != null && !approval.getAppId().isBlank()) {
            return approval.getAppId().trim();
        }
        return appId;
    }

    /**
     * 获取审批专用的 App Secret (若未单独配置则退回默认 appSecret)
     */
    public String getEffectiveApprovalAppSecret() {
        if (approval != null && approval.getAppSecret() != null && !approval.getAppSecret().isBlank()) {
            return approval.getAppSecret().trim();
        }
        return appSecret;
    }
}
