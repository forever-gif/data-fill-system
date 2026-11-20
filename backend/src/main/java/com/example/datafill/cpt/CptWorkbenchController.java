package com.example.datafill.cpt;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/cpt")
public class CptWorkbenchController {

    private static final String CPT_ROOT = "D:\\data-fill-system\\frReport";
    private static final String CLASSIFIED_FILE = "D:\\data-fill-system\\fr_analysis_output\\cpt_classified_templates.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @GetMapping("/preset_templates")
    public Map<String, Object> getPresetTemplates() {
        File cFile = new File(CLASSIFIED_FILE);
        if (cFile.exists()) {
            try {
                String json = Files.readString(cFile.toPath(), StandardCharsets.UTF_8);
                List<Map<String, Object>> classified = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                Map<String, Object> res = new HashMap<>();
                res.put("total", classified.size());
                res.put("templates", classified);
                return res;
            } catch (Exception e) {
                log.error("Failed to read classified templates", e);
            }
        }

        List<Map<String, String>> list = new ArrayList<>();
        File rootDir = new File(CPT_ROOT);
        if (rootDir.exists()) {
            scanCpts(rootDir, rootDir, list);
        }
        Map<String, Object> res = new HashMap<>();
        res.put("total", list.size());
        res.put("templates", list);
        return res;
    }

    @GetMapping("/common_rules")
    public Map<String, Object> getCommonRules() {
        Map<String, Object> res = new HashMap<>();
        res.put("parameters", CptCommonRules.getAllParameters());
        res.put("formulas", CptCommonRules.getAllFormulas());
        res.put("rules", CptCommonRules.getAllRules());
        return res;
    }

    @PostMapping("/common_rules/parameter")
    public Map<String, Object> addCustomParameter(@RequestBody CptCommonRules.ParameterPreset param) {
        CptCommonRules.addCustomParameter(param);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "自定义公共参数已保存并共享给全团队");
        return res;
    }

    @PostMapping("/common_rules/formula")
    public Map<String, Object> addCustomFormula(@RequestBody CptCommonRules.FormulaTemplate formula) {
        CptCommonRules.addCustomFormula(formula);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "自定义公式模板已保存并共享给全团队");
        return res;
    }

    @PostMapping("/common_rules/rule")
    public Map<String, Object> addCustomRule(@RequestBody CptCommonRules.RulePreset rule) {
        CptCommonRules.addCustomRule(rule);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "自定义条件属性规则已保存并共享给全团队");
        return res;
    }

    @DeleteMapping("/common_rules/{category}/{id}")
    public Map<String, Object> deleteCustomRule(@PathVariable("category") String category, @PathVariable("id") String id) {
        boolean removed = CptCommonRules.deleteCustomRule(category, id);
        Map<String, Object> res = new HashMap<>();
        res.put("success", removed);
        res.put("message", removed ? "规则已删除" : "未找到该自定义规则或系统内置规则不可删除");
        return res;
    }

    private void scanCpts(File root, File current, List<Map<String, String>> list) {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanCpts(root, f, list);
            } else if (f.getName().toLowerCase().endsWith(".cpt")) {
                Map<String, String> item = new HashMap<>();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath());
                item.put("rel_path", root.toURI().relativize(f.toURI()).getPath());
                list.add(item);
            }
        }
    }

    @PostMapping("/parse_template")
    public Map<String, Object> parseTemplate(@RequestParam("path") String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException("Template file not found: " + path);
        }

        String xml = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        Document doc = CptEngine.parseXml(xml);

        List<Map<String, Object>> datasets = new ArrayList<>();
        NodeList tdList = doc.getElementsByTagName("TableData");
        for (int i = 0; i < tdList.getLength(); i++) {
            Element td = (Element) tdList.item(i);
            String name = td.getAttribute("name");
            List<String> params = new ArrayList<>();
            NodeList pList = td.getElementsByTagName("Parameter");
            for (int j = 0; j < pList.getLength(); j++) {
                Element p = (Element) pList.item(j);
                NodeList aList = p.getElementsByTagName("Attributes");
                if (aList.getLength() > 0) {
                    params.add(((Element) aList.item(0)).getAttribute("name"));
                }
            }
            Map<String, Object> dsItem = new HashMap<>();
            dsItem.put("name", name);
            dsItem.put("params", params);
            datasets.add(dsItem);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("datasets", datasets);
        result.put("template_name", file.getName());
        return result;
    }

    @PostMapping("/analyze_sql")
    public Map<String, Object> analyzeSql(@RequestBody Map<String, String> body) {
        String sql = body.getOrDefault("sql", "").trim();

        // Parameters extraction
        Set<String> params = new LinkedHashSet<>();
        Matcher pm = Pattern.compile("\\$\\{?([a-zA-Z0-9_]+)\\}?").matcher(sql);
        while (pm.find()) {
            params.add(pm.group(1));
        }

        // Fields extraction
        List<String> fields = new ArrayList<>();
        Matcher m = Pattern.compile("SELECT\\s+(.*?)\\s+FROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        if (m.find()) {
            String selectPart = m.group(1);
            String[] items = selectPart.split(",");
            for (String item : items) {
                item = item.trim();
                if (item.isEmpty()) continue;
                Matcher aliasM = Pattern.compile("\\bAS\\s+([a-zA-Z0-9_]+)$", Pattern.CASE_INSENSITIVE).matcher(item);
                if (aliasM.find()) {
                    fields.add(aliasM.group(1));
                } else {
                    String[] tokens = item.split("\\s+");
                    String last = tokens[tokens.length - 1].replaceAll("[`\"']", "");
                    String[] dots = last.split("\\.");
                    String clean = dots[dots.length - 1];
                    if (clean.matches("^[a-zA-Z0-9_]+$")) {
                        fields.add(clean);
                    }
                }
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("parameters_found", new ArrayList<>(params));
        res.put("fields_found", new ArrayList<>(new LinkedHashSet<>(fields)));
        return res;
    }

    @Data
    public static class BuildNewReportRequest {
        private String templatePath;
        private String datasetName;
        private String sql;
        private String reportTitle;
        private List<CptEngine.ParameterFilterSpec> filters;
        private List<CptEngine.ColumnSpec> columns;
    }

    @PostMapping("/build_new_report")
    public ResponseEntity<byte[]> buildNewReport(@RequestBody BuildNewReportRequest req) throws Exception {
        File f = new File(req.getTemplatePath());
        if (!f.exists()) throw new IllegalArgumentException("Template file not found");

        String xml = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        Document masterDoc = CptEngine.parseXml(xml);

        Document newDoc = CptEngine.buildAdvancedNewReport(
                masterDoc,
                req.getDatasetName(),
                req.getSql(),
                req.getReportTitle(),
                req.getFilters(),
                req.getColumns()
        );

        String newXml = CptEngine.toXmlString(newDoc);
        byte[] bytes = newXml.getBytes(StandardCharsets.UTF_8);

        String exportName = (req.getReportTitle() != null && !req.getReportTitle().isEmpty())
                ? req.getReportTitle() : "new_report";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportName + ".cpt\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }
}
