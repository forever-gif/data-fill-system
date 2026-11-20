package com.example.datafill.service.feishu.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 飞书审批单明细数据提取器 (独立 Java 服务版)
 * 职责：
 * 1. 负责反序列化飞书审批实例返回的复杂嵌套 JSON 树与明细表格 (Table 控件)
 * 2. 提取申请人与每行申请的【报表全路径】
 * 3. 规范化清洗为符合帆软后台配置的 entry 目录节点与报表文件名
 * 
 * 注意：本模块为独立服务，完全不与 CPT 报表前端代码耦合。
 */
@Slf4j
@Service
public class FeishuApprovalParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedApprovalData {
        private String instanceCode;
        private String approvalCode;
        private String approvalName;
        private String status;
        private String openId;
        private String userId;
        private String applicantName;
        private String applicantEnName;
        private String applicantEmail;
        private String applicantMobile;
        private List<ReportItem> reportItems;

        // 🌟 账号与数据角色申请专属字段
        private String applyType; // 申请类型 (例如: 账号数据角色申请, 角色变更)
        private String applyReason; // 申请原因 (例如: 亚洲数据权限申请)
        private String orgControlRule; // 组织结构控权 (例如: 按申请人所在二级部门控权)
        private String departmentId; // 审批单部门 ID (例如: B013)
        private List<String> targetRoles; // 待开通/绑定的所有角色名列表
        private Map<String, List<String>> roleCategories; // 控权类别分组 (例如: "大洲控权" -> ["大洲权限_亚洲"])
        private boolean roleApply; // 是否是账号与角色申请流程

        // 🌟 流程节点状态与办理人信息 (支持“数据主管同意即触发自动开通，通知办理人核验后归档”)
        private boolean dataSupervisorApproved; // 数据主管审批节点是否已通过
        private boolean handlerTaskPending;     // 办理节点是否处于待处理状态
        private String handlerOpenId;           // 主办理人 Open ID (兼容单接收人读取)
        private List<String> handlerOpenIds;    // 全部待处理/有效办理人 Open ID 列表 (支持多人会签/或签同时通知)
        private String handlerUserId;           // 办理人 User ID / 工号
        private List<String> handlerUserIds;    // 全部待处理/有效办理人 User ID 列表
        private String handlerNodeName;         // 办理节点名称 (如: 技术Owner办理, 办理)
        private String handlerTaskId;           // 办理任务 ID
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportItem {
        private int rowIndex;
        private String rawPath;
        private String reportFile;
        private String reportName;
        private String catalogName;
        private String dirPath;
        private List<String> entryNodes;
        private String l3;
        private String l4;
    }

    /**
     * 核心方法：解析飞书审批单返回的原始 JSON (Map 或 JsonNode)
     *
     * @param instanceData 飞书官方接口 /open-apis/approval/v4/instances/{code} 返回的 data 对象
     * @return 结构化的申请人及待授权报表清单
     */
    public ExtractedApprovalData parseApprovalInstance(Map<String, Object> instanceData) {
        if (instanceData == null) {
            return ExtractedApprovalData.builder().reportItems(Collections.emptyList()).build();
        }

        String instanceCode = String.valueOf(instanceData.getOrDefault("instance_code", ""));
        String approvalCode = String.valueOf(instanceData.getOrDefault("approval_code", ""));
        String approvalName = String.valueOf(instanceData.getOrDefault("approval_name", ""));
        String status = String.valueOf(instanceData.getOrDefault("status", ""));
        String openId = String.valueOf(instanceData.getOrDefault("open_id", ""));
        String userId = String.valueOf(instanceData.getOrDefault("user_id", ""));
        String applicantName = String.valueOf(instanceData.getOrDefault("user_name", userId));
        String departmentId = String.valueOf(instanceData.getOrDefault("department_id", ""));

        String applyType = null;
        String applyReason = null;
        String orgControlRule = null;
        List<String> targetRoles = new ArrayList<>();
        Map<String, List<String>> roleCategories = new LinkedHashMap<>();

        List<ReportItem> reportItems = new ArrayList<>();

        // 解析嵌套的 form 字段 (飞书通常为 JSON 序列化字符串)
        Object rawForm = instanceData.get("form");
        List<Map<String, Object>> widgets = new ArrayList<>();

        if (rawForm instanceof String) {
            String formStr = ((String) rawForm).trim();
            if (!formStr.isEmpty()) {
                try {
                    widgets = objectMapper.readValue(formStr, new TypeReference<List<Map<String, Object>>>() {});
                } catch (Exception e) {
                    log.warn("反序列化飞书 form 字段异常: {}", e.getMessage());
                }
            }
        } else if (rawForm instanceof List) {
            widgets = (List<Map<String, Object>>) rawForm;
        }

        // 遍历所有表单控件，提取报表明细、角色控权、申请类型及组织范围
        int rowIndex = 1;
        for (Map<String, Object> widget : widgets) {
            String wType = String.valueOf(widget.getOrDefault("type", "")).toLowerCase();
            String wName = String.valueOf(widget.getOrDefault("name", ""));
            Object wVal = widget.get("value");

            // 优先从表单「申请人」联系人控件提取真实申请人 open_id 和 user_id/手机
            if ("申请人".equals(wName) || ("contact".equalsIgnoreCase(wType) && "申请人".equals(wName))) {
                Object openIdsObj = widget.get("open_ids");
                if (openIdsObj instanceof List && !((List<?>) openIdsObj).isEmpty()) {
                    String formOpenId = String.valueOf(((List<?>) openIdsObj).get(0));
                    if (formOpenId != null && !formOpenId.isBlank() && !"null".equalsIgnoreCase(formOpenId)) {
                        openId = formOpenId.trim();
                    }
                }
                if (wVal instanceof List && !((List<?>) wVal).isEmpty()) {
                    String formUserId = String.valueOf(((List<?>) wVal).get(0));
                    if (formUserId != null && !formUserId.isBlank() && !"null".equalsIgnoreCase(formUserId)) {
                        userId = formUserId.trim();
                        applicantName = userId;
                    }
                }
            }

            if ("申请类型".equals(wName) || wName.contains("申请类型")) {
                if (wVal != null) applyType = String.valueOf(wVal).trim();
            } else if ("组织结构控权".equals(wName) || wName.contains("组织结构")) {
                if (wVal != null) orgControlRule = String.valueOf(wVal).trim();
            } else if ("申请原因".equals(wName) || wName.contains("申请事由") || wName.contains("申请原因")) {
                if (wVal != null) applyReason = String.valueOf(wVal).trim();
            }

            if (wVal instanceof List) {
                List<?> rowList = (List<?>) wVal;
                // 判断是否是二维数组结构 (每一行又是一个 List)
                for (Object rowObj : rowList) {
                    if (rowObj instanceof List) {
                        List<?> cellList = (List<?>) rowObj;
                        ReportItem item = parseTableRow(cellList, rowIndex);
                        if (item != null) {
                            reportItems.add(item);
                            rowIndex++;
                        } else {
                            // 检查是否包含角色控件 (如大洲控权、BG/BU控权、特殊列权限)
                            for (Object cellObj : cellList) {
                                if (cellObj instanceof Map) {
                                    Map<?, ?> cell = (Map<?, ?>) cellObj;
                                    String cName = cell.get("name") != null ? String.valueOf(cell.get("name")).trim() : "";
                                    String cVal = cell.get("value") != null ? String.valueOf(cell.get("value")).trim() : "";
                                    if (!cVal.isBlank() && !"null".equalsIgnoreCase(cVal)) {
                                        if (cName.contains("角色") || cVal.contains("权限") || wName.contains("控权") || wName.contains("角色") || wName.contains("权限")) {
                                            if (!targetRoles.contains(cVal)) {
                                                targetRoles.add(cVal);
                                            }
                                            List<String> catRoles = roleCategories.computeIfAbsent(wName, k -> new ArrayList<>());
                                            if (!catRoles.contains(cVal)) {
                                                catRoles.add(cVal);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (rowObj instanceof Map) {
                        Map<?, ?> cell = (Map<?, ?>) rowObj;
                        String cName = cell.get("name") != null ? String.valueOf(cell.get("name")).trim() : "";
                        String cVal = cell.get("value") != null ? String.valueOf(cell.get("value")).trim() : "";
                        if (!cVal.isBlank() && !"null".equalsIgnoreCase(cVal)) {
                            if (cName.contains("角色") || cVal.contains("权限") || wName.contains("控权")) {
                                if (!targetRoles.contains(cVal)) targetRoles.add(cVal);
                                roleCategories.computeIfAbsent(wName, k -> new ArrayList<>()).add(cVal);
                            }
                        }
                    } else if (rowObj instanceof String) {
                        String strVal = ((String) rowObj).trim();
                        if (!strVal.isBlank() && (wName.contains("控权") || wName.contains("角色") || wName.contains("权限"))) {
                            if (!targetRoles.contains(strVal)) targetRoles.add(strVal);
                            roleCategories.computeIfAbsent(wName, k -> new ArrayList<>()).add(strVal);
                        }
                    }
                }
            } else if (wVal instanceof String) {
                // 单值控件 (如直接单选控权角色)
                String strVal = ((String) wVal).trim();
                if (!strVal.isBlank() && !"null".equalsIgnoreCase(strVal) && !wName.contains("组织结构") && !wName.contains("申请类型") && !wName.contains("申请原因")) {
                    if (wName.contains("控权") || wName.contains("角色") || (strVal.contains("权限") && !strVal.contains("按申请人"))) {
                        if (!targetRoles.contains(strVal)) targetRoles.add(strVal);
                        roleCategories.computeIfAbsent(wName, k -> new ArrayList<>()).add(strVal);
                    }
                }
            }
        }

        // 🌟 提取审批流程节点与办理人信息 (关键：识别数据主管已同意或自动通过/去重跳过，且收集全部待处理办理人)
        boolean dataSupervisorApproved = false;
        boolean hasPendingDataSupervisor = false;
        boolean handlerTaskPending = false;
        String handlerOpenId = null;
        String handlerUserId = null;
        String handlerNodeName = null;
        String handlerTaskId = null;
        List<String> pendingHandlerOpenIds = new ArrayList<>();
        List<String> pendingHandlerUserIds = new ArrayList<>();
        List<String> allHandlerOpenIds = new ArrayList<>();
        List<String> allHandlerUserIds = new ArrayList<>();

        Set<String> passedTimelineTaskIds = new HashSet<>();
        String timelineHandlerOpenId = null;
        String timelineHandlerUserId = null;
        Object rawTimeline = instanceData.get("timeline");
        if (rawTimeline instanceof List) {
            for (Object tlObj : (List<?>) rawTimeline) {
                if (tlObj instanceof Map) {
                    Map tl = (Map) tlObj;
                    String nodeKey = tl.get("node_key") != null ? String.valueOf(tl.get("node_key")) : "";
                    String tlType = tl.get("type") != null ? String.valueOf(tl.get("type")).trim() : "";
                    String tlTaskId = tl.get("task_id") != null ? String.valueOf(tl.get("task_id")).trim() : "";
                    if (!tlTaskId.isBlank() && !"null".equalsIgnoreCase(tlTaskId)
                            && ("PASS".equalsIgnoreCase(tlType)
                            || "AUTO_PASS".equalsIgnoreCase(tlType)
                            || "APPROVE".equalsIgnoreCase(tlType)
                            || "DONE".equalsIgnoreCase(tlType))) {
                        passedTimelineTaskIds.add(tlTaskId);
                    }
                    if (nodeKey.contains("HANDLER") || tlType.contains("HANDLER")) {
                        if (timelineHandlerOpenId == null) {
                            String tlOpenId = tl.get("open_id") != null ? String.valueOf(tl.get("open_id")) : "";
                            if (!tlOpenId.isBlank() && !"null".equalsIgnoreCase(tlOpenId)) timelineHandlerOpenId = tlOpenId;
                        }
                        if (timelineHandlerUserId == null) {
                            String tlUserId = tl.get("user_id") != null ? String.valueOf(tl.get("user_id")) : "";
                            if (!tlUserId.isBlank() && !"null".equalsIgnoreCase(tlUserId)) timelineHandlerUserId = tlUserId;
                        }
                    }
                }
            }
        }

        List<String> taskSummary = new ArrayList<>();
        Object rawTaskList = instanceData.get("task_list");
        if (rawTaskList instanceof List) {
            for (Object taskObj : (List<?>) rawTaskList) {
                if (taskObj instanceof Map) {
                    Map task = (Map) taskObj;
                    String nodeName = task.get("node_name") != null ? String.valueOf(task.get("node_name")) : "";
                    String tStatus = task.get("status") != null ? String.valueOf(task.get("status")) : "";
                    String tType = task.get("type") != null ? String.valueOf(task.get("type")) : "";
                    String tOpenId = task.get("open_id") != null ? String.valueOf(task.get("open_id")) : "";
                    String tUserId = task.get("user_id") != null ? String.valueOf(task.get("user_id")) : "";
                    String tId = task.get("id") != null ? String.valueOf(task.get("id")) : "";

                    taskSummary.add(nodeName + ":" + tStatus + (tType.isBlank() ? "" : "(" + tType + ")"));

                    if (nodeName.contains("数据主管")) {
                        if ("PENDING".equalsIgnoreCase(tStatus) || "processing".equalsIgnoreCase(tStatus) || "todo".equalsIgnoreCase(tStatus)) {
                            hasPendingDataSupervisor = true;
                        } else if ("APPROVED".equalsIgnoreCase(tStatus)
                                || "pass".equalsIgnoreCase(tStatus)
                                || "AUTO_PASS".equalsIgnoreCase(tStatus)
                                || "DONE".equalsIgnoreCase(tStatus)
                                || "AUTO_PASS".equalsIgnoreCase(tType)
                                || (!tId.isBlank() && passedTimelineTaskIds.contains(tId))) {
                            dataSupervisorApproved = true;
                        }
                    }

                    if (nodeName.contains("办理") || nodeName.contains("技术Owner") || nodeName.contains("处理") || nodeName.contains("实施")) {
                        boolean isTaskPending = "PENDING".equalsIgnoreCase(tStatus)
                                || "processing".equalsIgnoreCase(tStatus)
                                || "todo".equalsIgnoreCase(tStatus);
                        boolean hasValidOpenId = !tOpenId.isBlank() && !"null".equalsIgnoreCase(tOpenId);
                        boolean hasValidUserId = !tUserId.isBlank() && !"null".equalsIgnoreCase(tUserId);

                        if (hasValidOpenId && !allHandlerOpenIds.contains(tOpenId)) {
                            allHandlerOpenIds.add(tOpenId);
                        }
                        if (hasValidUserId && !allHandlerUserIds.contains(tUserId)) {
                            allHandlerUserIds.add(tUserId);
                        }
                        if (isTaskPending) {
                            handlerTaskPending = true;
                            if (hasValidOpenId && !pendingHandlerOpenIds.contains(tOpenId)) {
                                pendingHandlerOpenIds.add(tOpenId);
                            }
                            if (hasValidUserId && !pendingHandlerUserIds.contains(tUserId)) {
                                pendingHandlerUserIds.add(tUserId);
                            }
                        }

                        // 优先保留 PENDING 办理任务上的元信息，避免被同节点非 PENDING 记录覆盖
                        if (isTaskPending || handlerNodeName == null) {
                            handlerNodeName = nodeName;
                            handlerTaskId = tId;
                            if (hasValidOpenId && (isTaskPending || handlerOpenId == null)) handlerOpenId = tOpenId;
                            if (hasValidUserId && (isTaskPending || handlerUserId == null)) handlerUserId = tUserId;
                        }
                    }
                }
            }
        }

        // 兜底判定：当审批人开启“同一审批人自动去重/自动通过”时，飞书可能直接跳过数据主管任务或直接激活下游办理节点
        boolean isInstanceActive = !"REJECTED".equalsIgnoreCase(status)
                && !"CANCELED".equalsIgnoreCase(status)
                && !"DELETED".equalsIgnoreCase(status);
        if (!dataSupervisorApproved && isInstanceActive && handlerTaskPending && !hasPendingDataSupervisor) {
            dataSupervisorApproved = true;
            log.info("【飞书审批解析】实例 [{}] 已流转至办理节点 [{}] (status=PENDING)，且无待处理的数据主管节点，判定数据主管审批已放行(含自动通过/审批人去重)",
                    instanceCode, handlerNodeName);
        }
        if (!dataSupervisorApproved && log.isInfoEnabled()) {
            log.info("【飞书审批解析】实例 [{}] 数据主管尚未放行: status={}, tasks={}", instanceCode, status, taskSummary);
        }

        List<String> resolvedHandlerOpenIds = !pendingHandlerOpenIds.isEmpty()
                ? new ArrayList<>(pendingHandlerOpenIds)
                : new ArrayList<>(allHandlerOpenIds);
        List<String> resolvedHandlerUserIds = !pendingHandlerUserIds.isEmpty()
                ? new ArrayList<>(pendingHandlerUserIds)
                : new ArrayList<>(allHandlerUserIds);

        if ((handlerOpenId == null || handlerOpenId.isBlank()) && !resolvedHandlerOpenIds.isEmpty()) {
            handlerOpenId = resolvedHandlerOpenIds.get(0);
        }
        if (handlerOpenId == null || handlerOpenId.isBlank()) {
            handlerOpenId = timelineHandlerOpenId;
        }
        if (handlerOpenId != null && !handlerOpenId.isBlank() && !resolvedHandlerOpenIds.contains(handlerOpenId)) {
            resolvedHandlerOpenIds.add(handlerOpenId);
        }

        if ((handlerUserId == null || handlerUserId.isBlank()) && !resolvedHandlerUserIds.isEmpty()) {
            handlerUserId = resolvedHandlerUserIds.get(0);
        }
        if (handlerUserId == null || handlerUserId.isBlank()) {
            handlerUserId = timelineHandlerUserId;
        }
        if (handlerUserId != null && !handlerUserId.isBlank() && !resolvedHandlerUserIds.contains(handlerUserId)) {
            resolvedHandlerUserIds.add(handlerUserId);
        }

        boolean explicitReportApproval = approvalName.contains("报表权限")
                || (!reportItems.isEmpty() && !approvalName.contains("角色") && !approvalName.contains("账号"));
        boolean isRoleApply = !explicitReportApproval && (
                approvalName.contains("角色") || approvalName.contains("账号")
                || (approvalCode.toUpperCase().contains("2D598A70") || approvalCode.toUpperCase().contains("6470318A"))
                || !targetRoles.isEmpty() || (applyType != null && !applyType.isBlank())
        );

        return ExtractedApprovalData.builder()
                .instanceCode(instanceCode)
                .approvalCode(approvalCode)
                .approvalName(approvalName)
                .status(status)
                .openId(openId)
                .userId(userId)
                .applicantName(applicantName)
                .departmentId(departmentId)
                .applyType(applyType)
                .applyReason(applyReason)
                .orgControlRule(orgControlRule)
                .targetRoles(targetRoles)
                .roleCategories(roleCategories)
                .roleApply(isRoleApply)
                .reportItems(reportItems)
                .dataSupervisorApproved(dataSupervisorApproved)
                .handlerTaskPending(handlerTaskPending)
                .handlerOpenId(handlerOpenId)
                .handlerOpenIds(resolvedHandlerOpenIds)
                .handlerUserId(handlerUserId)
                .handlerUserIds(resolvedHandlerUserIds)
                .handlerNodeName(handlerNodeName)
                .handlerTaskId(handlerTaskId)
                .build();
    }

    private ReportItem parseTableRow(List<?> cellList, int rowIndex) {
        String rawPath = "";
        String catalogName = "";
        String l3 = "";
        String l4 = "";

        for (Object cellObj : cellList) {
            if (cellObj instanceof Map) {
                Map cell = (Map) cellObj;
                String cName = cell.get("name") != null ? String.valueOf(cell.get("name")).trim() : "";
                String cVal = cell.get("value") != null ? String.valueOf(cell.get("value")).trim() : "";

                String lowerVal = cVal.toLowerCase();
                boolean isPathColumn = cName.contains("全路径") || cName.contains("路径");
                boolean looksLikeReportFile = lowerVal.endsWith(".cpt") || lowerVal.endsWith(".frm") || lowerVal.endsWith(".fvs");
                if (isPathColumn && !cVal.isEmpty()) {
                    rawPath = cVal;
                } else if (looksLikeReportFile && (rawPath.isEmpty() || (!rawPath.contains("/") && cVal.contains("/")))) {
                    rawPath = cVal;
                }
                if (("报表名称".equals(cName) || cName.contains("报表名称")) && !cVal.isEmpty() && !"null".equalsIgnoreCase(cVal)) {
                    catalogName = stripReportExtension(cVal);
                }
                if ("L3".equalsIgnoreCase(cName)) {
                    l3 = cVal;
                }
                if ("L4".equalsIgnoreCase(cName)) {
                    l4 = cVal;
                }
            }
        }

        if (rawPath.isEmpty()) {
            return null;
        }

        // 清洗为标准结构
        String normalized = rawPath.replace("\\", "/").trim();
        String[] parts = Arrays.stream(normalized.split("/"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        // 去掉顶级根目录（如“美鹰集团报表”）
        List<String> validSegments = new ArrayList<>();
        for (String p : parts) {
            if (validSegments.isEmpty() && (p.contains("美鹰") || p.contains("FineReport"))) {
                continue;
            }
            validSegments.add(p);
        }

        if (validSegments.isEmpty()) {
            return null;
        }

        String reportFile = validSegments.get(validSegments.size() - 1);
        String reportName = stripReportExtension(reportFile);

        List<String> entryNodes = new ArrayList<>();
        for (int i = 0; i < validSegments.size() - 1; i++) {
            entryNodes.add(validSegments.get(i));
        }
        entryNodes.add(reportName);

        String dirPath = String.join("/", validSegments.subList(0, Math.max(0, validSegments.size() - 1)));

        return ReportItem.builder()
                .rowIndex(rowIndex)
                .rawPath(rawPath)
                .reportFile(reportFile)
                .reportName(reportName)
                .catalogName(catalogName)
                .dirPath(dirPath)
                .entryNodes(entryNodes)
                .l3(l3)
                .l4(l4)
                .build();
    }

    public static String stripReportExtension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        if (lower.endsWith(".cpt") || lower.endsWith(".frm") || lower.endsWith(".fvs")) {
            return name.substring(0, name.lastIndexOf('.'));
        }
        return name;
    }

    /**
     * 生成内置的 Mock 审批单数据 (用于零依赖快速本地验证)
     */
    public Map<String, Object> buildMockApprovalInstance() {
        Map<String, Object> data = new HashMap<>();
        data.put("instance_code", "MOCK-APPROVAL-20261109-001");
        data.put("approval_name", "帆软报表权限申请");
        data.put("status", "APPROVED");
        data.put("open_id", "ou_1234567890abcdef");
        data.put("user_id", "u_88889999");
        data.put("user_name", "李四");

        List<List<Map<String, String>>> tableRows = new ArrayList<>();

        // 第 1 行
        List<Map<String, String>> row1 = new ArrayList<>();
        row1.add(Map.of("name", "报表全路径", "value", "美鹰集团报表/01生产环境/02.供应链管理/03.供应商管理分析/03供应商管理报表.cpt"));
        row1.add(Map.of("name", "L3", "value", "供应链管理"));
        row1.add(Map.of("name", "L4", "value", "供应商管理分析"));
        tableRows.add(row1);

        // 第 2 行
        List<Map<String, String>> row2 = new ArrayList<>();
        row2.add(Map.of("name", "报表全路径", "value", "美鹰集团报表/01生产环境/04.财务管理/01.经营利润/01毛利明细表.cpt"));
        row2.add(Map.of("name", "L3", "value", "财务管理"));
        row2.add(Map.of("name", "L4", "value", "经营利润"));
        tableRows.add(row2);

        List<Map<String, Object>> widgets = new ArrayList<>();
        widgets.add(Map.of("id", "widget_reason", "name", "申请事由", "type", "input", "value", "日常业务报表分析需求"));
        widgets.add(Map.of("id", "widget_table", "name", "申请报表明细", "type", "table", "value", tableRows));

        try {
            data.put("form", objectMapper.writeValueAsString(widgets));
        } catch (Exception e) {
            data.put("form", "[]");
        }

        return data;
    }
}
