package com.example.datafill.service.feishu;

import com.example.datafill.entity.ReportCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 飞书新版云文档 (Docx) 1:1 像素级美化排版组件生成器
 * 精准采用飞书官方 HTML Add-on 组件 (blk_6358a421bca0001c190a9805)
 * 1:1 完美复刻目标【SKU经营分析】的所有设计细节：
 * 1. 顶部现代化 Banner + 统计图 SVG 动态插画 + 📊 大标题 + 🎯 科技蓝副标题
 * 2. 📋 基本信息 (浅蓝顶栏圆角卡片 + 浅蓝斑马纹交替行 + 📌 打开报表超链接)
 * 3. 🏷️ 业务属性 (浅蓝顶栏圆角卡片 + 浅蓝斑马纹交替行 + 蓝色密级药丸徽章)
 * 4. 🛠️ 维护信息 (浅蓝顶栏圆角卡片 + 浅蓝斑马纹交替行 + 绿色状态药丸徽章)
 * 5. 🎨 报表百科 底部微标
 */
@Component
public class FeishuDocxBuilder {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据报表实体组装 1:1 像素级复刻的飞书 Add-on 组件 Block
     */
    public List<Map<String, Object>> buildReportDocxBlocks(ReportCatalog report) {
        String cleanName = formatCleanName(report.getReportName());
        String status = (report.getLifecycleStatus() != null && !report.getLifecycleStatus().isBlank())
                ? report.getLifecycleStatus().trim() : "建设中";

        String rawDesc = report.getReportDesc();
        String desc = (rawDesc != null && !rawDesc.isBlank())
                ? (rawDesc.startsWith("🎯") ? rawDesc.trim() : "🎯 " + rawDesc.trim())
                : null;

        String reportUrl = (report.getReportUrl() != null && !report.getReportUrl().isBlank())
                ? report.getReportUrl().trim()
                : "https://bi.meiying.homes/webroot/decision#?activeTab=" + (report.getReportId() != null ? report.getReportId() : "cc28fcbd-258f-4036-b9eb-88d438c397b7");

        String fullPath = (report.getFullPath() != null && !report.getFullPath().isBlank())
                ? report.getFullPath().trim() : "美鹰集团报表/01生产环境/00.经营管理/01.经营月报/" + cleanName + ".cpt";

        // 🌟 1. 适用范围：完全基于数据库真实值原样输出，保持斜杠等原始标点
        String useScope = (report.getUseScope() != null && !report.getUseScope().isBlank())
                ? report.getUseScope().trim() : "-";

        // 🌟 2. 业务域、一级主题、二级主题
        String bizDomain = (report.getBizDomain() != null && !report.getBizDomain().isBlank())
                ? report.getBizDomain().trim()
                : ((report.getDomainL1() != null && !report.getDomainL1().isBlank()) ? report.getDomainL1().trim() : "-");

        String subjectL1 = (report.getSubjectL1() != null && !report.getSubjectL1().isBlank())
                ? report.getSubjectL1().trim() : "-";

        String subjectL2 = (report.getSubjectL2() != null && !report.getSubjectL2().isBlank())
                ? report.getSubjectL2().trim()
                : ((report.getDomainL2() != null && !report.getDomainL2().isBlank()) ? report.getDomainL2().trim() : "-");

        // 🌟 3. 分析维度与核心指标：统一转换为标准中文顿号 "、"
        String analysisDim = (report.getDimensions() != null && !report.getDimensions().isBlank())
                ? formatEnumeration(report.getDimensions()) : cleanName;

        String metrics = (report.getMetrics() != null && !report.getMetrics().isBlank())
                ? formatEnumeration(report.getMetrics()) : "-";

        // 🌟 4. 更新频率与更新时间
        String updateFreq = (report.getUpdateFrequency() != null && !report.getUpdateFrequency().isBlank())
                ? report.getUpdateFrequency().trim() : "每日";

        String updateTime = (report.getDataUpdateTime() != null && !report.getDataUpdateTime().isBlank())
                ? report.getDataUpdateTime().trim() : "-";

        // 🌟 5. 密级与敏感等级
        String isConfidential = (report.getIsConfidential() != null && !report.getIsConfidential().isBlank())
                ? report.getIsConfidential().trim() : "否";

        // 🌟 6. 维护人员与所属部门 (完全从数据库读取真实值)
        String owner = (report.getOwner() != null && !report.getOwner().isBlank())
                ? report.getOwner().trim() : "-";

        String deptName = (report.getDeptName() != null && !report.getDeptName().isBlank())
                ? report.getDeptName().trim() : "-";

        String developer = (report.getDeveloper() != null && !report.getDeveloper().isBlank())
                ? report.getDeveloper().trim() : "-";

        // 🌟 7. 新增表中扩展字段
        String analysisCycle = report.getAnalysisCycle();
        String dataGranularity = report.getDataGranularity();
        String dataSource = report.getDataSource();
        String sensitiveLevel = report.getSensitiveLevel();
        String reportCreateDate = report.getReportCreateDate();
        String reportUpdateDate = report.getReportUpdateDate();
        String remark = report.getRemark();

        String rawHtml = buildHtmlTemplate(
                cleanName, desc, fullPath, reportUrl, useScope,
                bizDomain, subjectL1, subjectL2, analysisDim, metrics,
                updateFreq, updateTime, isConfidential,
                owner, deptName, developer, status,
                analysisCycle, dataGranularity, dataSource,
                sensitiveLevel, reportCreateDate, reportUpdateDate, remark
        );
        String html = minifyHtml(rawHtml);

        Map<String, Object> block = new HashMap<>();
        block.put("block_type", 40); // 40 = Add-on / HTML Component Block

        Map<String, Object> addOns = new HashMap<>();
        addOns.put("component_type_id", "blk_6358a421bca0001c190a9805");
        try {
            addOns.put("record", objectMapper.writeValueAsString(Map.of("html", html)));
        } catch (Exception e) {
            addOns.put("record", "{\"html\":\"" + escapeJson(html) + "\"}");
        }

        block.put("add_ons", addOns);
        return List.of(block);
    }

    /**
     * 1:1 精准还原目标文档的 HTML 与 SVG 插画模板 (完整支持所有 DWD 字段与中文顿号排版)
     */
    private String buildHtmlTemplate(
            String reportName, String desc, String fullPath, String reportUrl, String useScope,
            String bizDomain, String subjectL1, String subjectL2, String analysisDim, String metrics,
            String updateFreq, String updateTime, String isConfidential,
            String owner, String deptName, String developer, String status,
            String analysisCycle, String dataGranularity, String dataSource,
            String sensitiveLevel, String reportCreateDate, String reportUpdateDate, String remark
    ) {
        String[] topMetrics = extractTop3Metrics(metrics);

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"\n")
          .append("  background: #FFFFFF;\n")
          .append("  padding: 24px;\n")
          .append("  font-family: 'PingFang SC', -apple-system, BlinkMacSystemFont, sans-serif;\n")
          .append("  border-radius: 12px;\n")
          .append("  box-shadow: 0 2px 12px rgba(51, 112, 255, 0.08);\n")
          .append("  color: #1F2329;\n")
          .append("\">\n\n")

          // 顶部标题 + 插画
          .append("  <div style=\"\n")
          .append("    margin-bottom: 20px; \n")
          .append("    padding-bottom: 16px; \n")
          .append("    border-bottom: 1px solid #E8F0FE;\n")
          .append("    display: flex;\n")
          .append("    align-items: center;\n")
          .append("    justify-content: space-between;\n")
          .append("    gap: 20px;\n")
          .append("  \">\n")
          .append("    <div style=\"flex: 1;\">\n")
          .append("      <h1 style=\"\n")
          .append("        font-size: 28px; \n")
          .append("        font-weight: 700; \n")
          .append("        color: #1E3A5F; \n")
          .append("        margin: 0 0 10px 0;\n")
          .append("        display: flex; \n")
          .append("        align-items: center;\n")
          .append("      \">\n")
          .append("        <span style=\"font-size: 28px; margin-right: 12px;\">📊</span> ").append(escapeHtml(reportName)).append("\n")
          .append("      </h1>\n");

        if (desc != null && !desc.isBlank()) {
            sb.append("      <p style=\"font-size: 15px; color: #3370FF; margin: 0; line-height: 1.6;\">\n")
              .append("        ").append(escapeHtml(desc)).append("\n")
              .append("      </p>\n");
        }

        sb.append("    </div>\n\n")

          // 右侧插画（1:1 像素级还原 5 根蓝色柱状图 + 橙色折线节点 + 浅蓝底板）
          .append("    <div style=\"flex-shrink: 0;\">\n")
          .append("      <svg width=\"220\" height=\"100\" viewBox=\"0 0 220 100\" xmlns=\"http://www.w3.org/2000/svg\">\n")
          .append("        <rect x=\"0\" y=\"0\" width=\"220\" height=\"100\" rx=\"16\" fill=\"#F0F6FF\"/>\n")
          .append("        <circle cx=\"170\" cy=\"32\" r=\"14\" fill=\"#DBEAFE\"/>\n")
          .append("        <circle cx=\"185\" cy=\"56\" r=\"8\" fill=\"#FFEDD5\"/>\n")
          .append("        <!-- 5 根圆角柱状图 -->\n")
          .append("        <rect x=\"22\" y=\"38\" width=\"14\" height=\"42\" rx=\"4\" fill=\"#2563EB\"/>\n")
          .append("        <rect x=\"44\" y=\"24\" width=\"14\" height=\"56\" rx=\"4\" fill=\"#3B82F6\"/>\n")
          .append("        <rect x=\"66\" y=\"42\" width=\"14\" height=\"38\" rx=\"4\" fill=\"#60A5FA\"/>\n")
          .append("        <rect x=\"88\" y=\"30\" width=\"14\" height=\"50\" rx=\"4\" fill=\"#3B82F6\"/>\n")
          .append("        <rect x=\"110\" y=\"50\" width=\"14\" height=\"30\" rx=\"4\" fill=\"#93C5FD\"/>\n")
          .append("        <!-- 橙色折线趋势与数据拐点 -->\n")
          .append("        <polyline points=\"29,42 51,28 73,46 95,34 117,54\" fill=\"none\" stroke=\"#F97316\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
          .append("        <circle cx=\"29\" cy=\"42\" r=\"4.5\" fill=\"#F97316\"/>\n")
          .append("        <circle cx=\"51\" cy=\"28\" r=\"5\" fill=\"#F97316\"/>\n")
          .append("        <circle cx=\"73\" cy=\"46\" r=\"4.5\" fill=\"#F97316\"/>\n")
          .append("        <circle cx=\"95\" cy=\"34\" r=\"5\" fill=\"#F97316\"/>\n")
          .append("        <circle cx=\"117\" cy=\"54\" r=\"4.5\" fill=\"#F97316\"/>\n")
          .append("      </svg>\n")
          .append("    </div>\n")
          .append("  </div>\n\n")
          .append("  <div style=\"background: #FFFFFF; border: 1px solid #E8F0FE; border-radius: 8px; margin-bottom: 20px; overflow: hidden;\">\n")
          .append("    <div style=\"background: #F5F8FF; padding: 12px 20px; font-size: 16px; font-weight: 700; color: #1E3A5F; border-bottom: 1px solid #E8F0FE;\">\n")
          .append("      📋 基本信息\n")
          .append("    </div>\n")
          .append("    <table style=\"width: 100%; border-collapse: collapse; font-size: 14px;\">\n")
          .append("      <tr style=\"background: #F9FBFF;\">\n")
          .append("        <td style=\"padding: 12px 20px; color: #4A6A8A; width: 20%; border-bottom: 1px solid #E8F0FE;\">报表名称</td>\n")
          .append("        <td style=\"padding: 12px 20px; color: #1F2329; border-bottom: 1px solid #E8F0FE;\">").append(escapeHtml(reportName)).append("</td>\n")
          .append("      </tr>\n")
          .append("      <tr>\n")
          .append("        <td style=\"padding: 12px 20px; color: #4A6A8A; border-bottom: 1px solid #E8F0FE;\">报表路径</td>\n")
          .append("        <td style=\"padding: 12px 20px; color: #1F2329; border-bottom: 1px solid #E8F0FE; font-family: monospace; font-size: 13px;\">").append(escapeHtml(fullPath)).append("</td>\n")
          .append("      </tr>\n")
          .append("      <tr style=\"background: #F9FBFF;\">\n")
          .append("        <td style=\"padding: 12px 20px; color: #4A6A8A; border-bottom: 1px solid #E8F0FE;\">报表链接</td>\n")
          .append("        <td style=\"padding: 12px 20px; border-bottom: 1px solid #E8F0FE;\">\n")
          .append("          <a href=\"").append(escapeHtml(reportUrl)).append("\" style=\"color: #3370FF; text-decoration: none;\">📌 打开报表</a>\n")
          .append("        </td>\n")
          .append("      </tr>\n")
          .append("      <tr>\n")
          .append("        <td style=\"padding: 12px 20px; color: #4A6A8A;\">适用范围</td>\n")
          .append("        <td style=\"padding: 12px 20px; color: #1F2329;\">").append(escapeHtml(useScope)).append("</td>\n")
          .append("      </tr>\n")
          .append("    </table>\n")
          .append("  </div>\n\n")

          // 卡片2：业务属性
          .append("  <div style=\"background: #FFFFFF; border: 1px solid #E8F0FE; border-radius: 8px; margin-bottom: 20px; overflow: hidden;\">\n")
          .append("    <div style=\"background: #F5F8FF; padding: 12px 20px; font-size: 16px; font-weight: 700; color: #1E3A5F; border-bottom: 1px solid #E8F0FE;\">\n")
          .append("      🏷️ 业务属性\n")
          .append("    </div>\n")
          .append("    <table style=\"width: 100%; border-collapse: collapse; font-size: 14px;\">\n");

        List<String[]> bizRows = new ArrayList<>();
        bizRows.add(new String[]{"业务域", bizDomain});
        bizRows.add(new String[]{"一级主题", subjectL1});
        bizRows.add(new String[]{"二级主题", subjectL2});
        bizRows.add(new String[]{"分析维度", analysisDim});
        bizRows.add(new String[]{"核心指标", metrics});
        if (analysisCycle != null && !analysisCycle.isBlank()) {
            bizRows.add(new String[]{"分析周期", analysisCycle.trim()});
        }
        bizRows.add(new String[]{"更新频率", updateFreq});
        bizRows.add(new String[]{"数据更新时间", updateTime});
        bizRows.add(new String[]{"密级", isConfidential});

        for (int i = 0; i < bizRows.size(); i++) {
            String[] row = bizRows.get(i);
            boolean isLast = (i == bizRows.size() - 1);
            boolean isZebra = (i % 2 == 0);
            String trBg = isZebra ? "background: #F9FBFF;" : "";
            String borderBottom = isLast ? "" : "border-bottom: 1px solid #E8F0FE;";

            sb.append("      <tr style=\"").append(trBg).append("\">\n")
              .append("        <td style=\"padding: 12px 20px; color: #4A6A8A; width: 20%; ").append(borderBottom).append("\">").append(escapeHtml(row[0])).append("</td>\n");

            if ("密级".equals(row[0])) {
                sb.append("        <td style=\"padding: 12px 20px; ").append(borderBottom).append("\">\n")
                  .append("          <span style=\"background: #F0F6FF; color: #3370FF; padding: 4px 10px; border-radius: 4px; font-size: 13px; font-weight: 600;\">")
                  .append(escapeHtml(row[1]))
                  .append("</span>\n")
                  .append("        </td>\n");
            } else {
                sb.append("        <td style=\"padding: 12px 20px; color: #1F2329; ").append(borderBottom).append("\">").append(escapeHtml(row[1])).append("</td>\n");
            }
            sb.append("      </tr>\n");
        }

        sb.append("    </table>\n")
          .append("  </div>\n\n")

          // 卡片3：维护信息
          .append("  <div style=\"background: #FFFFFF; border: 1px solid #E8F0FE; border-radius: 8px; margin-bottom: 20px; overflow: hidden;\">\n")
          .append("    <div style=\"background: #F5F8FF; padding: 12px 20px; font-size: 16px; font-weight: 700; color: #1E3A5F; border-bottom: 1px solid #E8F0FE;\">\n")
          .append("      🛠️ 维护信息\n")
          .append("    </div>\n")
          .append("    <table style=\"width: 100%; border-collapse: collapse; font-size: 14px;\">\n");

        List<String[]> maintRows = new ArrayList<>();
        maintRows.add(new String[]{"业务Owner", !owner.equals("-") ? "👤 " + owner : "-"});
        maintRows.add(new String[]{"所属部门", deptName});
        maintRows.add(new String[]{"技术Owner", !developer.equals("-") ? "👤 " + developer : "-"});
        if (dataSource != null && !dataSource.isBlank()) {
            maintRows.add(new String[]{"数据源", dataSource.trim()});
        }
        maintRows.add(new String[]{"报表状态", status});
        if (remark != null && !remark.isBlank()) {
            maintRows.add(new String[]{"备注说明", remark.trim()});
        }

        for (int i = 0; i < maintRows.size(); i++) {
            String[] row = maintRows.get(i);
            boolean isLast = (i == maintRows.size() - 1);
            boolean isZebra = (i % 2 == 0);
            String trBg = isZebra ? "background: #F9FBFF;" : "";
            String borderBottom = isLast ? "" : "border-bottom: 1px solid #E8F0FE;";

            sb.append("      <tr style=\"").append(trBg).append("\">\n")
              .append("        <td style=\"padding: 12px 20px; color: #4A6A8A; width: 20%; ").append(borderBottom).append("\">").append(escapeHtml(row[0])).append("</td>\n");

            if ("报表状态".equals(row[0])) {
                sb.append("        <td style=\"padding: 12px 20px; ").append(borderBottom).append("\">\n")
                  .append("          <span style=\"background: #E8F3E8; color: #00B42A; padding: 4px 10px; border-radius: 4px; font-size: 13px; font-weight: 600;\">")
                  .append(escapeHtml(row[1]))
                  .append("</span>\n")
                  .append("        </td>\n");
            } else {
                sb.append("        <td style=\"padding: 12px 20px; color: #1F2329; ").append(borderBottom).append("\">").append(escapeHtml(row[1])).append("</td>\n");
            }
            sb.append("      </tr>\n");
        }

        sb.append("    </table>\n")
          .append("  </div>\n\n")

          // 底部装饰
          .append("  <div style=\"text-align: center; margin-top: 16px; font-size: 13px; color: #3370FF; opacity: 0.6;\">\n")
          .append("    <p>🎨 报表百科 </p>\n")
          .append("  </div>\n\n")
          .append("</div>");

        return sb.toString();
    }

    /**
     * 将英文逗号、中文逗号、斜杠、空格等多项内容统一转为标准中文顿号 "、"
     */
    private String formatEnumeration(String text) {
        if (text == null || text.isBlank()) return "-";
        String[] parts = text.split("[,，/\\s;；、]+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        if (list.isEmpty()) return "-";
        return String.join("、", list);
    }

    /**
     * 智能提取核心指标中的前 3 个指标名称，用于动态渲染右上角 SVG 统计图插画图例
     */
    private String[] extractTop3Metrics(String metricsStr) {
        String[] defaults = new String[]{"销售", "利润", "库存"};
        if (metricsStr == null || metricsStr.isBlank() || "-".equals(metricsStr.trim())) {
            return defaults;
        }
        String[] tokens = metricsStr.split("[,，、/\\s;；]+");
        List<String> list = new ArrayList<>();
        for (String t : tokens) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > 5) {
                    trimmed = trimmed.substring(0, 5);
                }
                list.add(trimmed);
            }
        }
        if (list.isEmpty()) return defaults;

        String m1 = list.size() > 0 ? list.get(0) : "指标一";
        String m2 = list.size() > 1 ? list.get(1) : (list.size() > 0 ? "指标二" : "利润");
        String m3 = list.size() > 2 ? list.get(2) : (list.size() > 1 ? "指标三" : "趋势");
        return new String[]{m1, m2, m3};
    }

    /**
     * 构建动态 Heading 标题块 (block_type: 2 + level，对应飞书各级大纲)
     */
    public Map<String, Object> buildHeadingBlock(int level, String text) {
        int clamped = Math.max(1, Math.min(9, level));
        Map<String, Object> block = new HashMap<>();
        block.put("block_type", 2 + clamped);
        Map<String, Object> h = new HashMap<>();
        h.put("elements", List.of(Map.of("text_run", Map.of("content", text))));
        block.put("heading" + clamped, h);
        return block;
    }

    public Map<String, Object> buildHeading1Block(String text) {
        return buildHeadingBlock(1, text);
    }

    public Map<String, Object> buildHeading2Block(String text) {
        return buildHeadingBlock(2, text);
    }

    public Map<String, Object> buildHeading3Block(String text) {
        return buildHeadingBlock(3, text);
    }

    /**
     * 构建分割线块 (block_type: 22)
     */
    public Map<String, Object> buildDividerBlock() {
        Map<String, Object> block = new HashMap<>();
        block.put("block_type", 22);
        block.put("divider", Collections.emptyMap());
        return block;
    }

    /**
     * 构建手册顶部介绍文本块 (block_type: 2)
     */
    public Map<String, Object> buildIntroCalloutBlock(int reportCount) {
        Map<String, Object> block = new HashMap<>();
        block.put("block_type", 2);
        Map<String, Object> text = new HashMap<>();
        String content = "💡 本手册由数据中台统一实时同步，收录企业全量 " + reportCount + " 篇核心报表资产。您可通过飞书文档左侧【文档大纲】目录树快速定位、检索并直达各业务域与对应报表。";
        text.put("elements", List.of(Map.of("text_run", Map.of("content", content))));
        block.put("text", text);
        return block;
    }

    /**
     * 从报表实体中提取纯净的父级目录链 (严格排除末尾的报表文件自身，深度还原真实文件夹树)
     */
    public List<String> extractFolderChain(ReportCatalog r) {
        List<String> chain = new ArrayList<>();
        String fullPath = r.getFullPath();

        // 🌟 1. 优先从 fullPath 解析：路径的最后一个段是报表文件自身，前面的段全部是纯目录！
        if (fullPath != null && fullPath.contains("/")) {
            String[] parts = fullPath.split("/");
            // 从 index 0 到 parts.length - 2（排除最后 1 个报表文件）
            for (int i = 0; i < parts.length - 1; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()
                        || p.equalsIgnoreCase("Dec-Entry_Management")
                        || p.equalsIgnoreCase("美鹰数据分析")
                        || p.equalsIgnoreCase("美鹰集团报表")
                        || p.equalsIgnoreCase("集团报表")) {
                    continue;
                }
                String formatted = formatPreserveNumber(p);
                if (!formatted.isEmpty() && !chain.contains(formatted)) {
                    chain.add(formatted);
                }
            }
        }

        // 🌟 2. 兜底：若 fullPath 为空，则从 L2~L6 中提取（自动剔除末尾的报表文件）
        if (chain.isEmpty()) {
            String cleanReport = formatCleanName(r.getReportName());
            String rawReport = formatPreserveNumber(r.getReportName());
            String[] levels = {r.getL2(), r.getL3(), r.getL4(), r.getL5(), r.getL6()};
            List<String> validLevels = new ArrayList<>();
            for (String s : levels) {
                if (s != null && !s.isBlank()) {
                    String trimmed = s.trim();
                    if (!trimmed.equalsIgnoreCase("Dec-Entry_Management")
                            && !trimmed.equalsIgnoreCase("美鹰数据分析")
                            && !trimmed.equalsIgnoreCase("美鹰集团报表")
                            && !trimmed.equalsIgnoreCase("集团报表")
                            && !trimmed.toLowerCase().endsWith(".cpt")
                            && !trimmed.toLowerCase().endsWith(".frm")) {
                        validLevels.add(formatPreserveNumber(trimmed));
                    }
                }
            }
            // 若最后一个元素等于报表名称自身，剔除它（因为它是报表文件而非父目录）
            if (!validLevels.isEmpty()) {
                String last = validLevels.get(validLevels.size() - 1);
                if (last.equalsIgnoreCase(cleanReport) || last.equalsIgnoreCase(rawReport)) {
                    validLevels.remove(validLevels.size() - 1);
                }
            }
            chain.addAll(validLevels);
        }

        // 🌟 3. 若仍为空，从业务域与主题提取
        if (chain.isEmpty()) {
            if (r.getBizDomain() != null && !r.getBizDomain().isBlank()) {
                chain.add(formatPreserveNumber(r.getBizDomain().trim()));
            }
            if (r.getSubjectL1() != null && !r.getSubjectL1().isBlank()) {
                String s1 = formatPreserveNumber(r.getSubjectL1().trim());
                if (!chain.contains(s1)) chain.add(s1);
            }
            if (r.getSubjectL2() != null && !r.getSubjectL2().isBlank()) {
                String s2 = formatPreserveNumber(r.getSubjectL2().trim());
                if (!chain.contains(s2)) chain.add(s2);
            }
        }

        if (chain.isEmpty()) {
            chain.add("99.通用业务报表");
        }
        return chain;
    }

    /**
     * 构建全量报表资产汇总大文档的所有 Blocks (深度还原 L2 -> L3 -> L4 -> L5 完整大纲树)
     */
    public List<Map<String, Object>> buildMasterCatalogDocxBlocks(List<ReportCatalog> reports) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (reports == null || reports.isEmpty()) return blocks;

        // 1. 顶部导言
        blocks.add(buildIntroCalloutBlock(reports.size()));
        blocks.add(buildDividerBlock());

        // 2. 排序报表：按完整目录链 + 报表名自然序号升序排列
        List<ReportCatalog> sorted = new ArrayList<>(reports);
        sorted.sort((a, b) -> {
            List<String> chainA = extractFolderChain(a);
            List<String> chainB = extractFolderChain(b);
            int minLen = Math.min(chainA.size(), chainB.size());
            for (int i = 0; i < minLen; i++) {
                int cmp = chainA.get(i).compareToIgnoreCase(chainB.get(i));
                if (cmp != 0) return cmp;
            }
            int lenCmp = Integer.compare(chainA.size(), chainB.size());
            if (lenCmp != 0) return lenCmp;

            String nameA = getOrderedReportName(a);
            String nameB = getOrderedReportName(b);
            return nameA.compareToIgnoreCase(nameB);
        });

        List<String> currentPath = new ArrayList<>();

        for (ReportCatalog r : sorted) {
            List<String> folderChain = extractFolderChain(r);
            String reportTitle = getOrderedReportName(r);

            // 计算与上一个报表目录链的公共前缀长度
            int commonPrefix = 0;
            int minLen = Math.min(currentPath.size(), folderChain.size());
            while (commonPrefix < minLen && currentPath.get(commonPrefix).equalsIgnoreCase(folderChain.get(commonPrefix))) {
                commonPrefix++;
            }

            // 从分叉点开始，为每一级新的子目录生成对应层级的 Heading Block
            for (int i = commonPrefix; i < folderChain.size(); i++) {
                int headingLevel = i + 1; // 1-based: H1, H2, H3, H4, H5...
                blocks.add(buildHeadingBlock(headingLevel, folderChain.get(i)));
            }
            currentPath = new ArrayList<>(folderChain);

            // 报表标题作为最末级大纲 Heading
            int reportHeadingLevel = Math.min(9, folderChain.size() + 1);
            blocks.add(buildHeadingBlock(reportHeadingLevel, reportTitle));

            // 插入报表精美卡片 (HTML Add-on Block)
            List<Map<String, Object>> cardBlocks = buildReportDocxBlocks(r);
            if (cardBlocks != null && !cardBlocks.isEmpty()) {
                blocks.addAll(cardBlocks);
            }

            // 插入分割线
            blocks.add(buildDividerBlock());
        }

        return blocks;
    }

    private String getOrderedReportName(ReportCatalog r) {
        String name = r.getReportName();
        if (name == null || name.isBlank()) return "未命名报表";
        return formatPreserveNumber(name);
    }

    private String formatPreserveNumber(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replace(".cpt", "").replace(".frm", "").trim();
    }

    private String formatCleanName(String name) {
        if (name == null || name.isBlank()) return "未命名报表";
        return name.replace(".cpt", "").replace(".frm", "").trim();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String minifyHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html.replaceAll(">\\s+<", "><").replaceAll("\\r?\\n\\s*", "").trim();
    }
}

