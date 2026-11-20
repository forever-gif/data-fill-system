package com.example.datafill.cpt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 帆软 CPT 企业级公共规则、函数模板与团队共享资产库
 * 涵盖：
 * 1. 210 套生产 CPT 报表深度逆向提取的系统内建标准资产 (builtin = true)
 * 2. 团队同事自主沉淀并持久化共享的自定义规则资产 (builtin = false)
 */
@Slf4j
public class CptCommonRules {

    private static final String STORAGE_FILE_PATH = "D:\\data-fill-system\\fr_analysis_output\\cpt_shared_custom_rules.json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ParameterPreset {
        private String key;
        private String paramName;
        private String label;
        private String widgetType; // DateEditor, ComboCheckBox, TextEditor, RadioGroup, Hidden
        private String defaultValue;
        private String description;
        private boolean isSecurity; // 是否为系统权限参数
        private boolean builtin;    // 是否为系统内置
        private String author;      // 录入人/贡献同事
        private String createTime;  // 贡献时间

        public ParameterPreset(String key, String paramName, String label, String widgetType, String defaultValue, String description, boolean isSecurity) {
            this(key, paramName, label, widgetType, defaultValue, description, isSecurity, true, "系统内置", "2026-11-01");
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FormulaTemplate {
        private String id;
        private String name;
        private String category; // 占比分析, 变化率, 汇总, 文本拼接, 业务自定义
        private String templateExpr; // 如: =IF(ISNULL({denom}) || {denom}==0, 0, {numer}/{denom})
        private String description;
        private List<String> placeholders; // ["numer", "denom"]
        private boolean builtin;
        private String author;
        private String createTime;

        public FormulaTemplate(String id, String name, String category, String templateExpr, String description, List<String> placeholders) {
            this(id, name, category, templateExpr, description, placeholders, true, "系统内置", "2026-11-01");
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RulePreset {
        private String id;
        private String name;
        private String category; // 预警标红, 列宽控制, 行隐藏, 业务预警
        private String conditionType; // value, formula
        private String formulaExpr;
        private String compareOp; // 1: <, 2: <=, 0: ==, 3: >=, 4: >
        private String compareVal;
        private String actionType; // font_red, bg_yellow, hide_col, hide_row
        private String description;
        private boolean builtin;
        private String author;
        private String createTime;

        public RulePreset(String id, String name, String category, String conditionType, String formulaExpr, String compareOp, String compareVal, String actionType, String description) {
            this(id, name, category, conditionType, formulaExpr, compareOp, compareVal, actionType, description, true, "系统内置", "2026-11-01");
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SharedStorageWrapper {
        private List<ParameterPreset> parameters = new ArrayList<>();
        private List<FormulaTemplate> formulas = new ArrayList<>();
        private List<RulePreset> rules = new ArrayList<>();
    }

    // =========================================================================
    // 1. 全局标准安全与公共参数库 (生产环境 100% 出现)
    // =========================================================================
    public static List<ParameterPreset> getStandardParameters() {
        List<ParameterPreset> list = new ArrayList<>();
        
        // 权限三件套 (全报表通用底座)
        list.add(new ParameterPreset("fine_username", "fine_username", "当前登录用户", "Hidden", "${fine_username}", "系统内建工号参数，用于数据行级权限过滤", true));
        list.add(new ParameterPreset("fine_role", "fine_role", "当前用户角色", "Hidden", "${fine_role}", "系统内建角色参数，用于控制敏感指标查看权限", true));
        list.add(new ParameterPreset("s_read_permission", "s_read_permission", "行级权限掩码", "Hidden", "1", "数仓统一权限控制标志位 (0:无权, 1:有权)", true));

        // 常用业务日期参数
        list.add(new ParameterPreset("v_report_date", "v_report_date", "报表基准日期", "DateEditor", "", "周报/日报截点代表日期，如 2026-11-04", false));
        list.add(new ParameterPreset("v_month", "v_month", "统计月份", "TextEditor", "", "如 2026-10，用于月报周期过滤", false));
        list.add(new ParameterPreset("v_start_date", "v_start_date", "开始日期", "DateEditor", "", "区间查询开始时间", false));
        list.add(new ParameterPreset("v_end_date", "v_end_date", "结束日期", "DateEditor", "", "区间查询结束时间", false));

        // 常用维度过滤控件
        list.add(new ParameterPreset("v_country", "v_country", "国家/站点", "ComboCheckBox", "840", "美国(840), 英国, 德国等国家多选", false));
        list.add(new ParameterPreset("v_continent", "v_continent", "大洲", "ComboCheckBox", "北美", "北美、欧洲、澳洲等大洲过滤", false));
        list.add(new ParameterPreset("v_bu", "v_bu", "运营事业部", "ComboCheckBox", "", "事业部多选联动", false));
        list.add(new ParameterPreset("v_first_sort_desc", "v_first_sort_desc", "一级品类", "ComboCheckBox", "", "如 家具、工具等品类过滤", false));
        list.add(new ParameterPreset("v_second_sort_desc", "v_second_sort_desc", "二级品类", "ComboCheckBox", "", "二级细分品类多选", false));
        list.add(new ParameterPreset("v_third_sort_desc", "v_third_sort_desc", "三级品类", "ComboCheckBox", "", "三级末级品类多选", false));
        list.add(new ParameterPreset("v_sku", "v_sku", "SKU模糊检索", "TextEditor", "", "输入单个或多个SKU过滤", false));
        list.add(new ParameterPreset("v_show_type", "v_show_type", "展示模式切换", "RadioGroup", "1", "1:汇总模式, 2:明细模式, 3:专员模式 (联动隐藏列)", false));

        return list;
    }

    // =========================================================================
    // 2. 公共公式与函数模板库 (涵盖 12,000+ 次生产公式用法)
    // =========================================================================
    public static List<FormulaTemplate> getStandardFormulas() {
        List<FormulaTemplate> list = new ArrayList<>();

        // 1. 安全除法占比 (防止分母为 0 报错)
        list.add(new FormulaTemplate(
                "SAFE_DIVIDE",
                "安全除法 / 占比率",
                "比率与百分比",
                "=IF(ISNULL({denom}) || {denom}==0, 0, {numer}/{denom})",
                "防分母为 0 或空值抛错，计算达成率、集中度占比或毛利率",
                Arrays.asList("numer", "denom")
        ));

        // 2. 同比/环比增长率
        list.add(new FormulaTemplate(
                "GROWTH_RATE",
                "环比 / 同比变化率",
                "比率与百分比",
                "=IF(ISNULL({base}) || {base}==0, 0, ({curr}-{base})/{base})",
                "计算当期与基期的增长百分比，已包含基期为 0 保护",
                Arrays.asList("curr", "base")
        ));

        // 3. 差异差额
        list.add(new FormulaTemplate(
                "DIFF_AMOUNT",
                "实际与预算差额",
                "差额与计算",
                "={curr} - {base}",
                "计算两个指标列的直接净差额",
                Arrays.asList("curr", "base")
        ));

        // 4. 四舍五入保留2位
        list.add(new FormulaTemplate(
                "ROUND_2",
                "保留2位小数",
                "数值修剪",
                "=ROUND({target}, 2)",
                "金额与数量的标准四舍五入展示",
                Collections.singletonList("target")
        ));

        // 5. 动态报表标题拼接
        list.add(new FormulaTemplate(
                "DYNAMIC_TITLE",
                "参数动态报表标题",
                "文本处理",
                "=CONCATENATE($v_report_date, \" \", \"{title}\")",
                "根据顶部所选日期/月份参数自动动态变换表头大标题",
                Collections.singletonList("title")
        ));

        return list;
    }

    // =========================================================================
    // 3. 公共条件属性与高亮规则库 (涵盖 5,600+ 次生产规则用法)
    // =========================================================================
    public static List<RulePreset> getStandardRules() {
        List<RulePreset> list = new ArrayList<>();

        // 1. 负数与亏损标红
        list.add(new RulePreset(
                "RULE_NEGATIVE_RED",
                "负数 / 亏损文字标红",
                "预警与字体",
                "value",
                "",
                "1", // <
                "0", // 0
                "font_red",
                "当单元格数值小于 0 时，字体颜色自动标红加粗显示"
        ));

        // 2. 预警黄色背景高亮
        list.add(new RulePreset(
                "RULE_ALERT_YELLOW",
                "库存不足 / 告警黄色背景",
                "背景高亮",
                "value",
                "",
                "2", // <=
                "0",
                "bg_yellow",
                "数值小于等于 0 或不达标时，填充醒目的黄色背景"
        ));

        // 3. 动态参数控制列隐藏 (非常高频: 1900+次)
        list.add(new RulePreset(
                "RULE_HIDE_BY_SHOW_TYPE",
                "按单选参数联动隐藏列",
                "列宽控制",
                "formula",
                "$v_show_type != '1'",
                "",
                "",
                "hide_col",
                "当顶部参数 $v_show_type 不等于 1 时，此列列宽自动置为 0 (隐藏)"
        ));

        // 4. 零值 / 空行整行隐藏
        list.add(new RulePreset(
                "RULE_HIDE_ZERO_ROW",
                "无数据时自动隐藏整行",
                "行高控制",
                "value",
                "",
                "0", // ==
                "0",
                "hide_row",
                "当本行核心指标为 0 时，行高自动置为 0，防止页面留白"
        ));

        return list;
    }

    // =========================================================================
    // 4. 磁盘持久化共享资产读写逻辑 (供团队同事自由新增与共享)
    // =========================================================================

    public static synchronized SharedStorageWrapper loadCustomStorage() {
        File file = new File(STORAGE_FILE_PATH);
        if (!file.exists()) {
            return new SharedStorageWrapper();
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            SharedStorageWrapper wrapper = objectMapper.readValue(json, SharedStorageWrapper.class);
            return wrapper != null ? wrapper : new SharedStorageWrapper();
        } catch (Exception e) {
            log.error("Failed to read shared custom rules from {}", STORAGE_FILE_PATH, e);
            return new SharedStorageWrapper();
        }
    }

    public static synchronized void saveCustomStorage(SharedStorageWrapper wrapper) {
        try {
            File file = new File(STORAGE_FILE_PATH);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to write shared custom rules to {}", STORAGE_FILE_PATH, e);
            throw new RuntimeException("保存自定义规则失败: " + e.getMessage());
        }
    }

    public static synchronized List<ParameterPreset> getAllParameters() {
        List<ParameterPreset> all = new ArrayList<>(getStandardParameters());
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getParameters() != null) {
            all.addAll(wrapper.getParameters());
        }
        return all;
    }

    public static synchronized List<FormulaTemplate> getAllFormulas() {
        List<FormulaTemplate> all = new ArrayList<>(getStandardFormulas());
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getFormulas() != null) {
            all.addAll(wrapper.getFormulas());
        }
        return all;
    }

    public static synchronized List<RulePreset> getAllRules() {
        List<RulePreset> all = new ArrayList<>(getStandardRules());
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getRules() != null) {
            all.addAll(wrapper.getRules());
        }
        return all;
    }

    public static synchronized void addCustomParameter(ParameterPreset p) {
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getParameters() == null) wrapper.setParameters(new ArrayList<>());
        if (p.getKey() == null || p.getKey().trim().isEmpty()) {
            p.setKey("custom_param_" + System.currentTimeMillis());
        }
        p.setBuiltin(false);
        if (p.getAuthor() == null || p.getAuthor().trim().isEmpty()) {
            p.setAuthor("业务同事");
        }
        p.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        
        // 覆盖同 key 或追加
        wrapper.getParameters().removeIf(item -> item.getKey().equals(p.getKey()));
        wrapper.getParameters().add(p);
        saveCustomStorage(wrapper);
    }

    public static synchronized void addCustomFormula(FormulaTemplate f) {
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getFormulas() == null) wrapper.setFormulas(new ArrayList<>());
        if (f.getId() == null || f.getId().trim().isEmpty()) {
            f.setId("FORMULA_" + System.currentTimeMillis());
        }
        f.setBuiltin(false);
        if (f.getAuthor() == null || f.getAuthor().trim().isEmpty()) {
            f.setAuthor("业务同事");
        }
        f.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        wrapper.getFormulas().removeIf(item -> item.getId().equals(f.getId()));
        wrapper.getFormulas().add(f);
        saveCustomStorage(wrapper);
    }

    public static synchronized void addCustomRule(RulePreset r) {
        SharedStorageWrapper wrapper = loadCustomStorage();
        if (wrapper.getRules() == null) wrapper.setRules(new ArrayList<>());
        if (r.getId() == null || r.getId().trim().isEmpty()) {
            r.setId("RULE_" + System.currentTimeMillis());
        }
        r.setBuiltin(false);
        if (r.getAuthor() == null || r.getAuthor().trim().isEmpty()) {
            r.setAuthor("业务同事");
        }
        r.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        wrapper.getRules().removeIf(item -> item.getId().equals(r.getId()));
        wrapper.getRules().add(r);
        saveCustomStorage(wrapper);
    }

    public static synchronized boolean deleteCustomRule(String category, String id) {
        SharedStorageWrapper wrapper = loadCustomStorage();
        boolean removed = false;
        if ("parameter".equalsIgnoreCase(category) && wrapper.getParameters() != null) {
            removed = wrapper.getParameters().removeIf(item -> item.getKey().equals(id));
        } else if ("formula".equalsIgnoreCase(category) && wrapper.getFormulas() != null) {
            removed = wrapper.getFormulas().removeIf(item -> item.getId().equals(id));
        } else if ("rule".equalsIgnoreCase(category) && wrapper.getRules() != null) {
            removed = wrapper.getRules().removeIf(item -> item.getId().equals(id));
        }
        if (removed) {
            saveCustomStorage(wrapper);
        }
        return removed;
    }

    // =========================================================================
    // 5. 智能推导引擎：根据字段命名习惯，自动匹配最佳公共规则
    // =========================================================================
    public static void autoApplyCommonRules(CptEngine.ColumnSpec col) {
        if (col == null || col.field == null) return;
        String f = col.field.toLowerCase();

        // 1. 自动识别静态辅助列 (sort, rank, id, rn 等) -> 设为隐藏
        if (f.endsWith("_sort") || f.endsWith("_rank") || f.equals("sort_id") || f.equals("rn") || f.equals("row_num")) {
            col.isHidden = true;
            col.direction = "1";
        }

        // 2. 自动识别指标列并开启小计
        if (f.contains("num") || f.contains("qty") || f.contains("amount") || f.contains("price") 
                || f.contains("sales") || f.contains("stock") || f.contains("transit")) {
            col.direction = "0"; // 指标
            col.autoSum = true;  // 默认启用总计和小计 SUM
            
            // 如果可能为负数 (利润、差额、毛利)，自动装配负数标红规则
            if (f.contains("profit") || f.contains("diff") || f.contains("margin")) {
                if (col.highlightRules == null) col.highlightRules = new ArrayList<>();
                col.highlightRules.add(new CptEngine.HighlightRule(
                        "负数标红", "value", "", "1", "0", "font_red"
                ));
            }
        }

        // 3. 自动识别主要品类/层级维度列 -> 开启多级小计
        if (f.contains("second_sort") || f.contains("bu") || f.contains("department") || f.contains("cat")) {
            col.direction = "1"; // 纵向维度
            col.hasSubtotal = true;
            col.subtotalLabel = "小计";
        }
    }
}
