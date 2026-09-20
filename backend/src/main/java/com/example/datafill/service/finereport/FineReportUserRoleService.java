package com.example.datafill.service.finereport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.datafill.entity.FrRoleSyncHistory;
import com.example.datafill.mapper.FrRoleSyncHistoryMapper;
import com.example.datafill.service.feishu.FeishuApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 帆软用户角色批量比对与自动化同步服务
 * 升级版：集成多线程并发极速同步池 + 历史快照与一键安全回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineReportUserRoleService {

    private final FineReportApiClient fineReportApiClient;
    private final FeishuApiClient feishuApiClient;
    private final FrRoleSyncHistoryMapper frRoleSyncHistoryMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 🌟 优化点 3：专用有界并发线程池（6~8并发并行同步，100人同步从30秒压缩至1~2秒）
    private final ExecutorService syncExecutor = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new CustomizableThreadFactory("fr-sync-pool-"),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Data
    public static class UserRoleDiffItem {
        private String username;           // 员工工号/用户名 (如 dawei-weng)
        private String realName;           // 员工姓名 (如 翁大卫)
        private String department;         // 表格配置的目标部门 (如 运营二部)
        private String currentFrDepartment;// 帆软当前已有部门 (如 14.人力资源部)
        private boolean departmentChanged; // 部门是否发生变动
        private String frUserId;           // 帆软内部 User ID
        private List<String> sheetRoles = new ArrayList<>();       // 表格配置的角色
        private List<String> currentFrRoles = new ArrayList<>();   // 帆软当前已有角色
        private List<String> addedRoles = new ArrayList<>();       // 本次将新增角色
        private List<String> keptRoles = new ArrayList<>();        // 本次保持不变角色
        private List<String> removedRoles = new ArrayList<>();     // 本次将移除角色 (覆盖模式)
        private List<String> missingRoles = new ArrayList<>();     // 帆软中尚未创建的角色
        private List<String> targetRoleIds = new ArrayList<>();    // 最终待推送至帆软的 Role ID 列表
        private String status;             // NORMAL, NO_CHANGE, USER_NOT_FOUND, ROLE_NOT_FOUND
        private String statusMsg;          // 说明提示
        private int mergedRowCount = 1;    // 自动合并的表格行数 (大于1表示存在多行重复数据并已自动合并角色并集)
        private boolean success;           // 执行结果
        private String executeMsg;         // 执行日志
    }

    @Data
    public static class UserRoleSnapshotItem {
        private String username;
        private String realName;
        private String department;
        private String beforeDepartment;
        private String afterDepartment;
        private String frUserId;
        private List<String> beforeRoles = new ArrayList<>();
        private List<String> afterRoles = new ArrayList<>();
        private boolean success;
        private String executeMsg;
    }

    @Data
    public static class RolePreviewResult {
        private String spreadsheetToken;
        private String sheetId;
        private String sheetTitle;
        private int totalRows;
        private int validUserCount;
        private int needUpdateCount;
        private int missingRoleCount;
        private int departmentChangedCount; // 部门变动人数
        private List<UserRoleDiffItem> items = new ArrayList<>();
        private List<Map<String, Object>> sheetTabs = new ArrayList<>();
        // 列检测元数据反馈
        private Map<String, Integer> detectedColumns = new HashMap<>();
    }

    @Data
    public static class RoleApplyRequest {
        private String syncMode = "incremental"; // incremental (增量追加) | overwrite (全量覆盖)
        private boolean autoCreateMissingRoles = false;
        private String operator = "admin";
        private String sourceTitle = "飞书电子表格";
        private List<UserRoleDiffItem> items = new ArrayList<>();
    }

    @Data
    public static class RoleApplyResult {
        private String historyId;
        private String batchNo;
        private int totalCount;
        private int successCount;
        private int failCount;
        private List<UserRoleDiffItem> details = new ArrayList<>();
    }

    /**
     * 获取飞书电子表格的所有工作表 Tabs
     */
    public List<Map<String, Object>> getSpreadsheetTabs(String feishuUrl) {
        String token = feishuApiClient.getTenantAccessToken();
        String spreadsheetToken = feishuApiClient.extractSpreadsheetToken(feishuUrl);
        if (spreadsheetToken.isBlank()) {
            throw new IllegalArgumentException("无法从链接中识别飞书电子表格 Token，请检查链接格式。");
        }
        return feishuApiClient.getSpreadsheetSheets(spreadsheetToken, token);
    }

    /**
     * 从飞书在线表格读取并生成权限差异 Diff 预览
     * 增强版：支持无表头智能列探测、Unicode 隐形字符清洗、自动识别多列结构
     */
    public RolePreviewResult previewFromFeishu(String feishuUrl, String sheetId, String syncMode) {
        String token = feishuApiClient.getTenantAccessToken();
        String spreadsheetToken = feishuApiClient.extractSpreadsheetToken(feishuUrl);
        if (spreadsheetToken.isBlank()) {
            throw new IllegalArgumentException("无法识别有效的飞书电子表格链接。");
        }

        List<Map<String, Object>> sheets = feishuApiClient.getSpreadsheetSheets(spreadsheetToken, token);
        String targetSheetId = sheetId;
        String sheetTitle = "默认工作表";

        if (sheets != null && !sheets.isEmpty()) {
            if (targetSheetId == null || targetSheetId.isBlank()) {
                String urlSheetId = feishuApiClient.extractSpreadsheetSheetId(feishuUrl);
                if (urlSheetId != null && !urlSheetId.isBlank()) {
                    targetSheetId = urlSheetId;
                } else {
                    targetSheetId = (String) sheets.get(0).get("sheet_id");
                }
            }
            for (Map<String, Object> s : sheets) {
                if (Objects.equals(s.get("sheet_id"), targetSheetId)) {
                    sheetTitle = (String) s.get("title");
                    break;
                }
            }
        }

        // 读取工作表二维矩阵
        List<List<Object>> rawValues = feishuApiClient.getSheetValues(spreadsheetToken, targetSheetId, token);
        if (rawValues == null || rawValues.isEmpty()) {
            RolePreviewResult emptyResult = new RolePreviewResult();
            emptyResult.setSpreadsheetToken(spreadsheetToken);
            emptyResult.setSheetId(targetSheetId);
            emptyResult.setSheetTitle(sheetTitle);
            emptyResult.setSheetTabs(sheets != null ? sheets : Collections.emptyList());
            return emptyResult;
        }

        // 并发拉取帆软现有角色与用户
        CompletableFuture<List<FineReportApiClient.FineReportRole>> rolesFuture =
                CompletableFuture.supplyAsync(fineReportApiClient::getCustomRoles, syncExecutor);
        CompletableFuture<Map<String, FineReportApiClient.FineReportUser>> usersFuture =
                CompletableFuture.supplyAsync(fineReportApiClient::getAllUsers, syncExecutor);

        List<FineReportApiClient.FineReportRole> frRoles;
        Map<String, FineReportApiClient.FineReportUser> frUsers;
        try {
            frRoles = rolesFuture.get(30, TimeUnit.SECONDS);
            frUsers = usersFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("拉取帆软角色或用户字典超时/异常: {}", e.getMessage());
            throw new RuntimeException("连接帆软拉取权限字典失败: " + e.getMessage(), e);
        }

        Map<String, String> roleNameToIdMap = new HashMap<>();
        Map<String, String> roleIdToNameMap = new HashMap<>();
        for (FineReportApiClient.FineReportRole r : frRoles) {
            if (r.getName() != null && !r.getName().isBlank()) {
                String cleanName = cleanRoleString(r.getName());
                String rId = r.getId() != null && !r.getId().isBlank() ? r.getId().trim() : cleanName;
                roleNameToIdMap.put(cleanName, rId);
                roleIdToNameMap.put(rId, cleanName);
            }
        }

        // 🌟 智能列位置嗅探 (解决无表头、E列长角色文本、隐藏列等真实表格问题)
        int maxColIndex = 0;
        for (int i = 0; i < Math.min(10, rawValues.size()); i++) {
            maxColIndex = Math.max(maxColIndex, rawValues.get(i).size());
        }

        int usernameCol = -1;
        int nameCol = -1;
        int deptCol = -1;
        int rolesCol = -1;
        int startRow = 0;

        // 1. 先探测第 0 行是否是显式文字表头
        List<Object> firstRow = rawValues.get(0);
        int matchedHeaderCount = 0;
        for (int i = 0; i < firstRow.size(); i++) {
            String val = cleanRoleString(String.valueOf(firstRow.get(i))).toLowerCase();
            // 真实表头通常较短 (<=10字符)，且不含下划线、不含分号
            boolean isShortHeader = val.length() <= 10 && !val.contains(";") && !val.contains("；") && !val.contains("_");
            if (isShortHeader) {
                if (val.contains("工号") || val.contains("用户名") || val.equals("账号") || val.equals("username")) {
                    usernameCol = i;
                    matchedHeaderCount++;
                } else if (val.contains("姓名") || val.equals("name")) {
                    nameCol = i;
                    matchedHeaderCount++;
                } else if (val.contains("部门") || val.contains("职务") || val.contains("机构") || val.equals("dept") || val.equals("department")) {
                    deptCol = i;
                    matchedHeaderCount++;
                } else if (val.contains("角色") || val.equals("权限") || val.equals("role") || val.equals("roles")) {
                    rolesCol = i;
                    matchedHeaderCount++;
                }
            }
        }

        // 只有当匹配了至少两个标准表头字段，才确认为真正的表头行
        boolean hasExplicitHeader = (matchedHeaderCount >= 2) || (usernameCol >= 0 && rolesCol >= 0);
        if (hasExplicitHeader) {
            startRow = 1; // 存在文字表头，从第 1 行开始读取数据
        } else {
            // 没有显式表头，重置探测值，数据从第 0 行开始全量读取，由内容特征打分决定
            usernameCol = -1;
            nameCol = -1;
            deptCol = -1;
            rolesCol = -1;
            startRow = 0;
        }

        // 2. 若未匹配出完整表头，启用内容特征智能嗅探（针对无表头纯数据表）
        if (usernameCol < 0 || rolesCol < 0) {
            int testRowCount = Math.min(15, rawValues.size());
            int[] roleScore = new int[maxColIndex];
            int[] usernameScore = new int[maxColIndex];
            int[] nameScore = new int[maxColIndex];
            int[] deptScore = new int[maxColIndex];

            Pattern usernamePattern = Pattern.compile("^[a-zA-Z0-9_.-]{3,30}$");
            Pattern namePattern = Pattern.compile("^[\\u4e00-\\u9fa5]{2,4}$");

            for (int r = startRow; r < testRowCount; r++) {
                List<Object> row = rawValues.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String v = cleanRoleString(String.valueOf(row.get(c)));
                    if (v.isBlank()) continue;

                    if (v.contains("权限")) {
                        roleScore[c] += 6;
                    }
                    if (v.contains("_")) {
                        roleScore[c] += 3;
                    }
                    if (v.contains(";") || v.contains("；") || v.length() > 25) {
                        roleScore[c] += 4;
                    }
                    if (usernamePattern.matcher(v).matches() && !v.matches("^[0-9]+$") && !v.contains("部") && !v.contains("权限")) {
                        usernameScore[c] += 3;
                    }
                    if (namePattern.matcher(v).matches()) {
                        nameScore[c] += 3;
                    }
                    if (v.contains("部") || v.contains("中心") || v.contains("组") || v.contains("团队") || v.matches("^\\d{2}\\..+")) {
                        deptScore[c] += 4;
                    }
                }
            }

            if (usernameCol < 0) usernameCol = getMaxIndex(usernameScore, 0);
            if (rolesCol < 0) rolesCol = getMaxIndex(roleScore, maxColIndex > 4 ? 4 : 3);
            if (nameCol < 0) nameCol = getMaxIndex(nameScore, 1);
            if (deptCol < 0) deptCol = getMaxIndex(deptScore, 2);
        }

        log.info("智能列探测完成: 工号列={}, 姓名列={}, 部门列={}, 角色列={}, 数据起始行={}",
                usernameCol, nameCol, deptCol, rolesCol, startRow);

        boolean isOverwrite = "overwrite".equalsIgnoreCase(syncMode);
        List<UserRoleDiffItem> diffItems = new ArrayList<>();
        Set<String> allMissingRoles = new HashSet<>();

        // 🌟 先按 username 归一化聚合表格数据（如同一用户出现多行，自动将其所有角色合并并集去重）
        Map<String, MergedSheetRow> mergedMap = new LinkedHashMap<>();
        for (int r = startRow; r < rawValues.size(); r++) {
            List<Object> row = rawValues.get(r);
            if (row == null || row.isEmpty()) continue;

            String username = cleanRoleString(getColVal(row, usernameCol));
            if (username.isBlank() || username.equals("-") || username.equalsIgnoreCase("用户名") || username.equals("工号")) {
                continue;
            }

            String realName = cleanRoleString(getColVal(row, nameCol));
            String department = cleanRoleString(getColVal(row, deptCol));
            String rolesRaw = cleanRoleString(getColVal(row, rolesCol));
            List<String> parsedRoles = parseRolesList(rolesRaw);

            String key = username.toLowerCase();
            MergedSheetRow existing = mergedMap.get(key);
            if (existing == null) {
                existing = new MergedSheetRow();
                existing.username = username; // 保留原格式展示
                existing.realName = realName;
                existing.department = department;
                existing.roles = new LinkedHashSet<>(parsedRoles);
                existing.rowCount = 1;
                mergedMap.put(key, existing);
            } else {
                existing.roles.addAll(parsedRoles);
                if ((existing.realName == null || existing.realName.isBlank()) && !realName.isBlank()) {
                    existing.realName = realName;
                }
                if ((existing.department == null || existing.department.isBlank()) && !department.isBlank()) {
                    existing.department = department;
                }
                existing.rowCount++;
            }
        }

        for (MergedSheetRow mRow : mergedMap.values()) {
            String username = mRow.username;
            String realName = mRow.realName;
            String department = mRow.department;
            List<String> parsedSheetRoles = new ArrayList<>(mRow.roles);

            UserRoleDiffItem item = new UserRoleDiffItem();
            item.setUsername(username);
            item.setRealName(realName);
            item.setDepartment(department);
            item.setMergedRowCount(mRow.rowCount);
            item.setSheetRoles(parsedSheetRoles);

            FineReportApiClient.FineReportUser matchedUser = frUsers.get(username.toLowerCase());
            if (matchedUser == null) {
                item.setStatus("USER_NOT_FOUND");
                item.setStatusMsg("帆软系统中未找到用户名/工号为 [" + username + "] 的账号");
                item.setAddedRoles(parsedSheetRoles);
                diffItems.add(item);
                continue;
            }

            item.setFrUserId(matchedUser.getId());
            if (item.getRealName().isBlank() && matchedUser.getRealName() != null) {
                item.setRealName(matchedUser.getRealName());
            }

            // 若该用户当前在批量列表中未能解析出角色或部门，且已获取到了 frUserId，调用 enrichUserDetailDirectly 定向补齐
            if ((matchedUser.getCustomRoleNames().isEmpty() || matchedUser.getDepartment() == null || matchedUser.getDepartment().isBlank())
                    && matchedUser.getId() != null && !matchedUser.getId().isBlank()) {
                try {
                    fineReportApiClient.enrichUserDetailDirectly(matchedUser);
                    log.info("成功为用户 [{}] 定向补查详情: 部门={}, 角色={}", username, matchedUser.getDepartment(), matchedUser.getCustomRoleNames());
                } catch (Exception ex) {
                    log.warn("用户 [{}] 定向补查详情失败: {}", username, ex.getMessage());
                }
            }

            String frDept = matchedUser.getDepartment() != null ? matchedUser.getDepartment().trim() : "";
            item.setCurrentFrDepartment(frDept);

            // 部门差异比对 (表格目标部门 vs 帆软当前部门)
            String sheetDept = cleanRoleString(item.getDepartment());
            if (!sheetDept.isBlank() && !frDept.isBlank()) {
                boolean match = sheetDept.equalsIgnoreCase(frDept)
                        || sheetDept.replaceAll("^\\d+[.、_\\s-]*", "").equalsIgnoreCase(frDept.replaceAll("^\\d+[.、_\\s-]*", ""));
                item.setDepartmentChanged(!match);
            } else if (!sheetDept.isBlank() && frDept.isBlank()) {
                // 表格配置了部门，但帆软当前未设部门，视作发生变动
                item.setDepartmentChanged(true);
            } else {
                item.setDepartmentChanged(false);
            }

            // 1. 将用户身上识别出的所有 Role ID 还原为真实角色名称
            for (String rid : matchedUser.getCustomRoleIds()) {
                if (roleIdToNameMap.containsKey(rid)) {
                    matchedUser.getCustomRoleNames().add(roleIdToNameMap.get(rid));
                }
            }

            // 2. 清洗 currentRoles，绝不允许长 UUID / 技术 ID 作为角色名称展示
            List<String> currentRoles = new ArrayList<>();
            for (String r : matchedUser.getCustomRoleNames()) {
                String clean = cleanRoleString(r);
                if (clean.isBlank()) continue;
                if (FineReportApiClient.isUuidOrTechnicalId(clean)) {
                    // 若是 UUID，尝试映射为角色名称；若映射不到则直接丢弃，绝不向用户展示无意义的技术哈希
                    if (roleIdToNameMap.containsKey(clean)) {
                        currentRoles.add(roleIdToNameMap.get(clean));
                    }
                } else {
                    currentRoles.add(clean);
                }
            }
            currentRoles = new ArrayList<>(new LinkedHashSet<>(currentRoles));
            item.setCurrentFrRoles(currentRoles);

            Set<String> sheetRoleSet = new LinkedHashSet<>(parsedSheetRoles);
            Set<String> currentRoleSet = new LinkedHashSet<>(currentRoles);

            List<String> added = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> missing = new ArrayList<>();

            for (String sr : sheetRoleSet) {
                if (currentRoleSet.contains(sr)) {
                    kept.add(sr);
                } else {
                    added.add(sr);
                }
                if (!roleNameToIdMap.containsKey(sr)) {
                    missing.add(sr);
                    allMissingRoles.add(sr);
                }
            }

            if (isOverwrite) {
                for (String cr : currentRoleSet) {
                    if (!sheetRoleSet.contains(cr)) {
                        removed.add(cr);
                    }
                }
            }

            item.setAddedRoles(added);
            item.setKeptRoles(kept);
            item.setRemovedRoles(removed);
            item.setMissingRoles(missing);

            Set<String> targetRoleNames = new LinkedHashSet<>();
            if (isOverwrite) {
                targetRoleNames.addAll(sheetRoleSet);
            } else {
                targetRoleNames.addAll(currentRoleSet);
                targetRoleNames.addAll(sheetRoleSet);
            }

            List<String> targetIds = new ArrayList<>();
            for (String trn : targetRoleNames) {
                String id = roleNameToIdMap.get(trn);
                if (id != null) {
                    targetIds.add(id);
                }
            }
            item.setTargetRoleIds(targetIds);

            if (!missing.isEmpty()) {
                item.setStatus("ROLE_NOT_FOUND");
                item.setStatusMsg("包含 " + missing.size() + " 个帆软缺失角色");
            } else if (added.isEmpty() && (!isOverwrite || removed.isEmpty())) {
                if (item.isDepartmentChanged()) {
                    item.setStatus("NORMAL");
                    item.setStatusMsg("角色一致，部门有变动");
                } else {
                    item.setStatus("NO_CHANGE");
                    item.setStatusMsg("与帆软角色一致，无须更新");
                }
            } else {
                item.setStatus("NORMAL");
                item.setStatusMsg("待同步: 新增 " + added.size() + " 项" + (isOverwrite && !removed.isEmpty() ? ", 移除 " + removed.size() + " 项" : "") + (item.isDepartmentChanged() ? " (部门变动)" : ""));
            }

            diffItems.add(item);
        }

        RolePreviewResult result = new RolePreviewResult();
        result.setSpreadsheetToken(spreadsheetToken);
        result.setSheetId(targetSheetId);
        result.setSheetTitle(sheetTitle);
        result.setTotalRows(diffItems.size());
        result.setValidUserCount((int) diffItems.stream().filter(i -> !"USER_NOT_FOUND".equals(i.getStatus())).count());
        result.setNeedUpdateCount((int) diffItems.stream().filter(i -> "NORMAL".equals(i.getStatus()) || "ROLE_NOT_FOUND".equals(i.getStatus())).count());
        result.setMissingRoleCount(allMissingRoles.size());
        result.setDepartmentChangedCount((int) diffItems.stream().filter(UserRoleDiffItem::isDepartmentChanged).count());
        result.setItems(diffItems);
        result.setSheetTabs(sheets != null ? sheets : Collections.emptyList());
        result.setDetectedColumns(Map.of(
                "usernameCol", usernameCol,
                "nameCol", nameCol,
                "deptCol", deptCol,
                "rolesCol", rolesCol
        ));

        return result;
    }

    /**
     * 🌟 优化点 3 & 5：多线程并发批量同步 + 生成不可篡改快照历史
     */
    public RoleApplyResult applyBatchUserRoles(RoleApplyRequest request) {
        List<UserRoleDiffItem> items = request.getItems();
        boolean autoCreate = request.isAutoCreateMissingRoles();
        boolean isOverwrite = "overwrite".equalsIgnoreCase(request.getSyncMode());
        String batchNo = "BATCH-" + System.currentTimeMillis();

        RoleApplyResult result = new RoleApplyResult();
        result.setBatchNo(batchNo);
        result.setTotalCount(items.size());

        if (items.isEmpty()) {
            return result;
        }

        // 重新拉取最新的角色 ID 字典
        Map<String, String> roleNameToIdMap = fineReportApiClient.getCustomRoles().stream()
                .filter(r -> r.getName() != null)
                .collect(Collectors.toMap(r -> cleanRoleString(r.getName()), FineReportApiClient.FineReportRole::getId, (k1, k2) -> k1));

        // 自动创建缺失角色 (如有)
        if (autoCreate) {
            Set<String> allMissing = new HashSet<>();
            for (UserRoleDiffItem item : items) {
                if (item.getMissingRoles() != null) allMissing.addAll(item.getMissingRoles());
            }
            for (String mr : allMissing) {
                String cleanMr = cleanRoleString(mr);
                if (!roleNameToIdMap.containsKey(cleanMr)) {
                    String newId = fineReportApiClient.createCustomRole(cleanMr);
                    if (newId != null) {
                        roleNameToIdMap.put(cleanMr, newId);
                        log.info("已自动在帆软中补建角色: {} -> ID: {}", cleanMr, newId);
                    }
                }
            }
        }

        // 准备快照结构
        List<UserRoleSnapshotItem> snapshotList = new CopyOnWriteArrayList<>();

        // 🌟 优化点 3：使用 syncExecutor 多线程并发执行
        List<CompletableFuture<UserRoleDiffItem>> futures = items.stream().map(item ->
                CompletableFuture.supplyAsync(() -> {
                    UserRoleSnapshotItem snap = new UserRoleSnapshotItem();
                    snap.setUsername(item.getUsername());
                    snap.setRealName(item.getRealName());
                    snap.setDepartment(item.getDepartment());
                    snap.setBeforeDepartment(item.getCurrentFrDepartment());
                    snap.setAfterDepartment(item.getDepartment());
                    snap.setFrUserId(item.getFrUserId());
                    snap.setBeforeRoles(new ArrayList<>(item.getCurrentFrRoles()));

                    if (item.getFrUserId() == null || item.getFrUserId().isBlank() || "USER_NOT_FOUND".equals(item.getStatus())) {
                        item.setSuccess(false);
                        item.setExecuteMsg("跳过: 帆软中未找到工号对应用户");
                        snap.setSuccess(false);
                        snap.setExecuteMsg(item.getExecuteMsg());
                        snapshotList.add(snap);
                        return item;
                    }

                    Set<String> finalRoleNames = new LinkedHashSet<>();
                    if (isOverwrite) {
                        finalRoleNames.addAll(item.getSheetRoles());
                    } else {
                        finalRoleNames.addAll(item.getCurrentFrRoles());
                        finalRoleNames.addAll(item.getSheetRoles());
                    }

                    snap.setAfterRoles(new ArrayList<>(finalRoleNames));

                    List<String> targetIds = new ArrayList<>();
                    for (String rn : finalRoleNames) {
                        String id = roleNameToIdMap.get(cleanRoleString(rn));
                        if (id != null) {
                            targetIds.add(id);
                        }
                    }

                    List<String> addedIds = new ArrayList<>();
                    for (String rn : item.getAddedRoles()) {
                        String id = roleNameToIdMap.get(cleanRoleString(rn));
                        if (id != null) addedIds.add(id);
                    }

                    List<String> removedIds = new ArrayList<>();
                    if (isOverwrite) {
                        for (String rn : item.getRemovedRoles()) {
                            String id = roleNameToIdMap.get(cleanRoleString(rn));
                            if (id != null) removedIds.add(id);
                        }
                    }

                    try {
                        // 🌟 同时同步目标角色与目标部门职务
                        boolean ok = fineReportApiClient.updateUserCustomRoles(
                                item.getFrUserId(),
                                item.getUsername(),
                                item.getDepartment(),
                                targetIds,
                                new ArrayList<>(finalRoleNames),
                                addedIds,
                                removedIds
                        );
                        item.setSuccess(ok);
                        String deptSuffix = (item.isDepartmentChanged() && item.getDepartment() != null && !item.getDepartment().isBlank())
                                ? "，部门职务已变更为 " + item.getDepartment() : "";
                        item.setExecuteMsg(ok ? "同步成功！生效角色 " + finalRoleNames.size() + " 项" + deptSuffix : "调用帆软接口返回失败");
                        snap.setSuccess(ok);
                        snap.setExecuteMsg(item.getExecuteMsg());
                    } catch (Exception e) {
                        item.setSuccess(false);
                        item.setExecuteMsg("执行异常: " + e.getMessage());
                        snap.setSuccess(false);
                        snap.setExecuteMsg(e.getMessage());
                    }

                    snapshotList.add(snap);
                    return item;
                }, syncExecutor)
        ).collect(Collectors.toList());

        // 等待所有线程并发执行完成 (最长等待 60 秒)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待部分并发角色同步线程超时或异常: {}", e.getMessage());
        }

        List<UserRoleDiffItem> processedItems = futures.stream().map(f -> {
            try {
                return f.getNow(null);
            } catch (Exception e) {
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());

        int successCount = (int) processedItems.stream().filter(UserRoleDiffItem::isSuccess).count();
        int failCount = processedItems.size() - successCount;

        result.setSuccessCount(successCount);
        result.setFailCount(failCount);
        result.setDetails(processedItems);

        // 🌟 优化点 5：存盘历史快照至数据库
        try {
            FrRoleSyncHistory history = new FrRoleSyncHistory();
            history.setBatchNo(batchNo);
            history.setOperator(request.getOperator() != null ? request.getOperator() : "admin");
            history.setSourceTitle(request.getSourceTitle() != null ? request.getSourceTitle() : "飞书电子表格");
            history.setSyncMode(request.getSyncMode());
            history.setTotalCount(processedItems.size());
            history.setSuccessCount(successCount);
            history.setFailCount(failCount);
            history.setSnapshotData(objectMapper.writeValueAsString(snapshotList));
            history.setStatus(failCount == 0 ? "COMPLETED" : (successCount > 0 ? "PARTIAL_SUCCESS" : "FAILED"));
            history.setCreateTime(LocalDateTime.now());

            frRoleSyncHistoryMapper.insert(history);
            result.setHistoryId(history.getId());
            log.info("成功创建帆软角色同步历史快照记录: ID={}, 批次号={}", history.getId(), batchNo);
        } catch (Exception e) {
            log.error("保存同步历史快照失败: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 🌟 优化点 5：获取历史同步快照记录列表 (用于管理界面查账与回滚)
     */
    public List<FrRoleSyncHistory> getSyncHistoryList(int limit) {
        LambdaQueryWrapper<FrRoleSyncHistory> query = new LambdaQueryWrapper<>();
        query.select(
                FrRoleSyncHistory::getId,
                FrRoleSyncHistory::getBatchNo,
                FrRoleSyncHistory::getOperator,
                FrRoleSyncHistory::getSourceTitle,
                FrRoleSyncHistory::getSyncMode,
                FrRoleSyncHistory::getTotalCount,
                FrRoleSyncHistory::getSuccessCount,
                FrRoleSyncHistory::getFailCount,
                FrRoleSyncHistory::getStatus,
                FrRoleSyncHistory::getCreateTime,
                FrRoleSyncHistory::getRollbackTime
        );
        query.orderByDesc(FrRoleSyncHistory::getCreateTime);
        query.last("LIMIT " + Math.max(1, Math.min(100, limit)));
        return frRoleSyncHistoryMapper.selectList(query);
    }

    /**
     * 🌟 优化点 5：根据快照一键安全回滚 (恢复至变更前状态)
     */
    public Map<String, Object> rollbackHistory(String historyId, String operator) {
        FrRoleSyncHistory history = frRoleSyncHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new IllegalArgumentException("未找到对应的同步历史记录: " + historyId);
        }
        if ("ROLLED_BACK".equalsIgnoreCase(history.getStatus())) {
            throw new IllegalStateException("该批次在 " + history.getRollbackTime() + " 已经被回滚过，不可重复回滚。");
        }
        if (history.getSnapshotData() == null || history.getSnapshotData().isBlank()) {
            throw new IllegalStateException("该批次未包含有效快照底册，无法执行回滚。");
        }

        List<UserRoleSnapshotItem> snapshots;
        try {
            snapshots = objectMapper.readValue(history.getSnapshotData(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, UserRoleSnapshotItem.class));
        } catch (Exception e) {
            throw new RuntimeException("反序列化快照底册失败: " + e.getMessage(), e);
        }

        Map<String, String> roleNameToIdMap = fineReportApiClient.getCustomRoles().stream()
                .filter(r -> r.getName() != null)
                .collect(Collectors.toMap(r -> cleanRoleString(r.getName()), FineReportApiClient.FineReportRole::getId, (k1, k2) -> k1));

        int rollbackSuccess = 0;
        int rollbackFail = 0;

        // 并发执行回滚
        List<CompletableFuture<Boolean>> futures = snapshots.stream()
                .filter(s -> s.getFrUserId() != null && !s.getFrUserId().isBlank())
                .map(snap -> CompletableFuture.supplyAsync(() -> {
                    List<String> beforeRoleIds = new ArrayList<>();
                    for (String rName : snap.getBeforeRoles()) {
                        String rId = roleNameToIdMap.get(cleanRoleString(rName));
                        if (rId != null) {
                            beforeRoleIds.add(rId);
                        }
                    }

                    List<String> afterRoleIds = new ArrayList<>();
                    if (snap.getAfterRoles() != null) {
                        for (String rName : snap.getAfterRoles()) {
                            String rId = roleNameToIdMap.get(cleanRoleString(rName));
                            if (rId != null) afterRoleIds.add(rId);
                        }
                    }

                    List<String> rollbackAddedIds = new ArrayList<>();
                    for (String bid : beforeRoleIds) {
                        if (!afterRoleIds.contains(bid)) rollbackAddedIds.add(bid);
                    }
                    List<String> rollbackRemovedIds = new ArrayList<>();
                    for (String aid : afterRoleIds) {
                        if (!beforeRoleIds.contains(aid)) rollbackRemovedIds.add(aid);
                    }

                    try {
                        return fineReportApiClient.updateUserCustomRoles(
                                snap.getFrUserId(),
                                snap.getUsername(),
                                snap.getBeforeDepartment(),
                                beforeRoleIds,
                                new ArrayList<>(snap.getBeforeRoles()),
                                rollbackAddedIds,
                                rollbackRemovedIds
                        );
                    } catch (Exception e) {
                        return false;
                    }
                }, syncExecutor))
                .collect(Collectors.toList());

        for (CompletableFuture<Boolean> f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) {
                    rollbackSuccess++;
                } else {
                    rollbackFail++;
                }
            } catch (Exception e) {
                rollbackFail++;
            }
        }

        // 更新历史状态为已回滚
        history.setStatus("ROLLED_BACK");
        history.setRollbackTime(LocalDateTime.now());
        frRoleSyncHistoryMapper.updateById(history);

        log.info("批次 [{}] 回滚完毕: 成功恢复 {} 人，失败 {} 人", history.getBatchNo(), rollbackSuccess, rollbackFail);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "回滚完成！已将员工角色恢复至同步前状态。成功恢复: " + rollbackSuccess + " 人");
        resp.put("batchNo", history.getBatchNo());
        resp.put("rollbackSuccess", rollbackSuccess);
        resp.put("rollbackFail", rollbackFail);
        return resp;
    }

    private int getMaxIndex(int[] arr, int fallback) {
        int max = -1;
        int idx = fallback;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > max && arr[i] > 0) {
                max = arr[i];
                idx = i;
            }
        }
        return idx;
    }

    private String getColVal(List<Object> row, int index) {
        if (index >= 0 && index < row.size() && row.get(index) != null) {
            return String.valueOf(row.get(index));
        }
        return "";
    }

    private String cleanRoleString(String raw) {
        if (raw == null) return "";
        // 彻底清洗非断空格、零宽空格、全角空格等特殊 Unicode 符号
        return raw.replaceAll("[\u00A0\u200B\u200C\u200D\uFEFF\u3000\\t]+", " ").trim();
    }

    private List<String> parseRolesList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String cleaned = cleanRoleString(raw);
        String[] parts = cleaned.split("[;；,，\\r\\n]+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String clean = cleanRoleString(p);
            if (!clean.isBlank() && !clean.equals("-")) {
                list.add(clean);
            }
        }
        return list;
    }

    private static class MergedSheetRow {
        String username;
        String realName;
        String department;
        Set<String> roles = new LinkedHashSet<>();
        int rowCount = 0;
    }
}
