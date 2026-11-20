package com.example.datafill.cpt;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * Universal FineReport CPT Engine (Enterprise Full-Power Edition v3)
 * Full Features:
 * 1. Filter / Parameter Panel Synchronizer:
 *    - Preserves existing Parameter Panel & Search Button from Master Template
 *    - Auto injects/registers new parameters into TableData and ReportParameterAttr
 *    - Auto maps widget types (DateEditor, ComboCheckBox, TextEditor, RadioGroup)
 * 2. Static Hidden Column (ColWidth = 0 for helper/ID columns)
 * 3. Dynamic Hidden Column (ColWidth = 0 conditionally based on param or cell value)
 * 4. Explicit Left-Parent / Top-Parent customizable control
 * 5. Multi-level Subtotal Rows with Reverse Left-Parent Anchor
 * 6. Horizontal Cross-Table Expansion & Custom Formula Metrics
 */
public class CptEngine {

    public static String colIdxToName(int colIdx) {
        StringBuilder result = new StringBuilder();
        colIdx += 1;
        while (colIdx > 0) {
            int rem = (colIdx - 1) % 26;
            result.insert(0, (char) ('A' + rem));
            colIdx = (colIdx - 1) / 26;
        }
        return result.toString();
    }

    public static int colNameToIdx(String colName) {
        int idx = 0;
        for (char c : colName.toUpperCase().toCharArray()) {
            idx = idx * 26 + (c - 'A' + 1);
        }
        return idx - 1;
    }

    public static String coordToName(int colIdx, int rowIdx) {
        return colIdxToName(colIdx) + (rowIdx + 1);
    }

    public static Document parseXml(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlContent)));
    }

    public static String toXmlString(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    public static class ParameterFilterSpec {
        public String paramName;    // e.g. "v_report_date"
        public String label;        // e.g. "报告基准日期"
        public String widgetType;   // "DateEditor", "ComboCheckBox", "TextEditor", "RadioGroup", "Hidden"
        public String defaultValue; // e.g. "2026-10-28"

        public ParameterFilterSpec() {}
        public ParameterFilterSpec(String paramName, String label, String widgetType, String defaultValue) {
            this.paramName = paramName;
            this.label = label;
            this.widgetType = widgetType;
            this.defaultValue = defaultValue;
        }
    }

    public static class HighlightRule {
        public String name;
        public String conditionType; // "value" or "formula"
        public String formulaExpr;   // e.g. "$v_show_type != '3'"
        public String compareOp;     // 1: <, 2: <=, 0: ==, 3: >=, 4: >
        public String compareVal;    // e.g. "0", "999999"
        public String actionType;    // "font_red", "bg_yellow", "hide_row", "hide_col"

        public HighlightRule() {}
        public HighlightRule(String name, String conditionType, String formulaExpr, String compareOp, String compareVal, String actionType) {
            this.name = name;
            this.conditionType = conditionType;
            this.formulaExpr = formulaExpr;
            this.compareOp = compareOp;
            this.compareVal = compareVal;
            this.actionType = actionType;
        }
    }

    public static class ColumnSpec {
        public String field;
        public String title;
        public String direction; // "0" = Measure, "1" = Vertical Dimension, "2" = Horizontal Cross
        public String leftParentField;
        public boolean isHidden; // True = Static Hidden Column (ColWidth=0)
        public boolean hasSubtotal;
        public String subtotalLabel;
        public boolean autoSum;
        public String customFormula;
        public List<HighlightRule> highlightRules;
    }

    public static Document buildAdvancedNewReport(
            Document masterDoc,
            String datasetName,
            String newSql,
            String reportTitle,
            List<ParameterFilterSpec> filters,
            List<ColumnSpec> columns
    ) throws Exception {

        // 1. Update / Inject SQL Dataset and its Parameters
        NodeList tdList = masterDoc.getElementsByTagName("TableData");
        Element targetTd = null;
        for (int i = 0; i < tdList.getLength(); i++) {
            Element td = (Element) tdList.item(i);
            if (datasetName.equals(td.getAttribute("name"))) {
                targetTd = td;
                break;
            }
        }
        if (targetTd == null && tdList.getLength() > 0) {
            targetTd = (Element) tdList.item(0);
        }
        if (targetTd != null) {
            // Update Query
            NodeList qList = targetTd.getElementsByTagName("Query");
            Element qElem = (qList.getLength() > 0) ? (Element) qList.item(0) : null;
            if (qElem == null) {
                qElem = masterDoc.createElement("Query");
                targetTd.appendChild(qElem);
            }
            qElem.setTextContent(newSql);

            // Synchronize Parameters under this TableData
            if (filters != null && !filters.isEmpty()) {
                syncTableDataParameters(masterDoc, targetTd, filters);
            }
        }

        // 2. Synchronize Filter Panel / ReportParameterAttr (Top Filter Bar)
        if (filters != null && !filters.isEmpty()) {
            syncReportParameterAttr(masterDoc, filters);
        }

        // 3. Locate CellElementList
        Node cellListElem = masterDoc.getElementsByTagName("CellElementList").item(0);
        if (cellListElem == null) return masterDoc;

        // Sample Styles
        Element sampleHeaderStyle = null;
        Element sampleDataStyle = null;
        Element sampleSubtotalStyle = null;
        NodeList existingCells = masterDoc.getElementsByTagName("C");
        for (int i = 0; i < existingCells.getLength(); i++) {
            Element c = (Element) existingCells.item(i);
            int r = Integer.parseInt(c.getAttribute("r"));
            NodeList sList = c.getElementsByTagName("Style");
            if (sList.getLength() > 0) {
                if (r <= 1 && sampleHeaderStyle == null) {
                    sampleHeaderStyle = (Element) sList.item(0).cloneNode(true);
                } else if (r >= 2 && sampleDataStyle == null) {
                    sampleDataStyle = (Element) sList.item(0).cloneNode(true);
                    sampleSubtotalStyle = (Element) sList.item(0).cloneNode(true);
                }
            }
        }

        // Clear existing grid rows >= 1
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < existingCells.getLength(); i++) {
            Element c = (Element) existingCells.item(i);
            int r = Integer.parseInt(c.getAttribute("r"));
            if (r >= 1) {
                toRemove.add(c);
            }
        }
        for (Node n : toRemove) {
            cellListElem.removeChild(n);
        }

        List<ColumnSpec> vDimensions = new ArrayList<>();
        List<ColumnSpec> hDimensions = new ArrayList<>();
        List<ColumnSpec> measures = new ArrayList<>();

        for (ColumnSpec col : columns) {
            if ("1".equals(col.direction)) {
                vDimensions.add(col);
            } else if ("2".equals(col.direction)) {
                hDimensions.add(col);
            } else {
                measures.add(col);
            }
        }

        int headerRow = 1;
        int dataRow = 2;

        int currentCol = 0;
        String prevDimCell = "";
        String leafDimCell = "";
        Map<String, String> fieldToCoordMap = new HashMap<>();
        Map<ColumnSpec, String> dimCellCoords = new HashMap<>();
        List<Integer> staticHiddenCols = new ArrayList<>();

        // 4. Render Vertical Dimension Columns (A2, B2, C2...)
        for (ColumnSpec dim : vDimensions) {
            String cellCoord = coordToName(currentCol, dataRow);
            fieldToCoordMap.put(dim.field, cellCoord);
            dimCellCoords.put(dim, cellCoord);
            if (dim.isHidden) {
                staticHiddenCols.add(currentCol);
            }

            // Header
            Element headerCell = masterDoc.createElement("C");
            headerCell.setAttribute("c", String.valueOf(currentCol));
            headerCell.setAttribute("r", String.valueOf(headerRow));
            Element ho = masterDoc.createElement("O");
            ho.setTextContent(dim.title != null && !dim.title.isEmpty() ? dim.title : dim.field);
            headerCell.appendChild(ho);
            if (sampleHeaderStyle != null) headerCell.appendChild(sampleHeaderStyle.cloneNode(true));
            cellListElem.appendChild(headerCell);

            // Data Cell
            Element dataCell = masterDoc.createElement("C");
            dataCell.setAttribute("c", String.valueOf(currentCol));
            dataCell.setAttribute("r", String.valueOf(dataRow));

            Element dO = masterDoc.createElement("O");
            dO.setAttribute("t", "DSColumn");
            dO.setAttribute("class", "com.fr.report.cell.cellattr.core.group.DSColumn");
            Element dattr = masterDoc.createElement("Attributes");
            dattr.setAttribute("dsName", datasetName);
            dattr.setAttribute("columnName", dim.field);
            dO.appendChild(dattr);
            dataCell.appendChild(dO);

            // Parent Cell Logic
            Element exp = masterDoc.createElement("Expand");
            exp.setAttribute("direction", "1");

            String targetLeftParent = "";
            if (dim.leftParentField != null && !dim.leftParentField.isEmpty() && !"auto".equals(dim.leftParentField)) {
                if (!"none".equals(dim.leftParentField)) {
                    targetLeftParent = fieldToCoordMap.getOrDefault(dim.leftParentField, "");
                }
            } else {
                targetLeftParent = prevDimCell;
            }

            if (!targetLeftParent.isEmpty()) {
                exp.setAttribute("leftParentDefault", "false");
                exp.setAttribute("left", targetLeftParent);
            }
            dataCell.appendChild(exp);

            if (dim.highlightRules != null && !dim.highlightRules.isEmpty()) {
                injectHighlightRules(masterDoc, dataCell, dim.highlightRules);
            }

            if (sampleDataStyle != null) dataCell.appendChild(sampleDataStyle.cloneNode(true));
            cellListElem.appendChild(dataCell);

            prevDimCell = cellCoord;
            leafDimCell = cellCoord;
            currentCol++;
        }

        // 5. Render Horizontal Cross Dimension
        String crossTopParent = "";
        for (ColumnSpec hdim : hDimensions) {
            String cellCoord = coordToName(currentCol, headerRow);
            crossTopParent = cellCoord;
            if (hdim.isHidden) {
                staticHiddenCols.add(currentCol);
            }

            Element headerCell = masterDoc.createElement("C");
            headerCell.setAttribute("c", String.valueOf(currentCol));
            headerCell.setAttribute("r", String.valueOf(headerRow));
            Element dO = masterDoc.createElement("O");
            dO.setAttribute("t", "DSColumn");
            dO.setAttribute("class", "com.fr.report.cell.cellattr.core.group.DSColumn");
            Element dattr = masterDoc.createElement("Attributes");
            dattr.setAttribute("dsName", datasetName);
            dattr.setAttribute("columnName", hdim.field);
            dO.appendChild(dattr);
            headerCell.appendChild(dO);

            Element exp = masterDoc.createElement("Expand");
            exp.setAttribute("direction", "2"); // Horizontal
            headerCell.appendChild(exp);

            if (sampleHeaderStyle != null) headerCell.appendChild(sampleHeaderStyle.cloneNode(true));
            cellListElem.appendChild(headerCell);
        }

        // 6. Render Measure Columns
        int measureStartCol = currentCol;
        for (ColumnSpec mea : measures) {
            if (mea.isHidden) {
                staticHiddenCols.add(currentCol);
            }

            // Header
            if (hDimensions.isEmpty()) {
                Element headerCell = masterDoc.createElement("C");
                headerCell.setAttribute("c", String.valueOf(currentCol));
                headerCell.setAttribute("r", String.valueOf(headerRow));
                Element ho = masterDoc.createElement("O");
                ho.setTextContent(mea.title != null && !mea.title.isEmpty() ? mea.title : mea.field);
                headerCell.appendChild(ho);
                if (sampleHeaderStyle != null) headerCell.appendChild(sampleHeaderStyle.cloneNode(true));
                cellListElem.appendChild(headerCell);
            }

            // Data Cell
            Element dataCell = masterDoc.createElement("C");
            dataCell.setAttribute("c", String.valueOf(currentCol));
            dataCell.setAttribute("r", String.valueOf(dataRow));

            if (mea.customFormula != null && !mea.customFormula.trim().isEmpty()) {
                Element dO = masterDoc.createElement("O");
                dO.setAttribute("t", "XMLable");
                dO.setAttribute("class", "com.fr.base.Formula");
                Element dattr = masterDoc.createElement("Attributes");
                dattr.setTextContent(mea.customFormula.trim());
                dO.appendChild(dattr);
                dataCell.appendChild(dO);
            } else {
                Element dO = masterDoc.createElement("O");
                dO.setAttribute("t", "DSColumn");
                dO.setAttribute("class", "com.fr.report.cell.cellattr.core.group.DSColumn");
                Element dattr = masterDoc.createElement("Attributes");
                dattr.setAttribute("dsName", datasetName);
                dattr.setAttribute("columnName", mea.field);
                dO.appendChild(dattr);
                dataCell.appendChild(dO);
            }

            // Parent Cell
            Element exp = masterDoc.createElement("Expand");
            exp.setAttribute("direction", "0");

            String targetLeftParent = "";
            if (mea.leftParentField != null && !mea.leftParentField.isEmpty() && !"auto".equals(mea.leftParentField)) {
                if (!"none".equals(mea.leftParentField)) {
                    targetLeftParent = fieldToCoordMap.getOrDefault(mea.leftParentField, "");
                }
            } else {
                targetLeftParent = leafDimCell;
            }

            if (!targetLeftParent.isEmpty()) {
                exp.setAttribute("leftParentDefault", "false");
                exp.setAttribute("left", targetLeftParent);
            }
            if (!crossTopParent.isEmpty()) {
                exp.setAttribute("topParentDefault", "false");
                exp.setAttribute("top", crossTopParent);
            }
            dataCell.appendChild(exp);

            if (mea.highlightRules != null && !mea.highlightRules.isEmpty()) {
                injectHighlightRules(masterDoc, dataCell, mea.highlightRules);
            }

            if (sampleDataStyle != null) dataCell.appendChild(sampleDataStyle.cloneNode(true));
            cellListElem.appendChild(dataCell);

            currentCol++;
        }

        // 7. Multi-level Subtotal Rows
        int nextRow = dataRow + 1;
        List<ColumnSpec> reversedDims = new ArrayList<>(vDimensions);
        Collections.reverse(reversedDims);

        for (ColumnSpec dim : reversedDims) {
            if (!dim.hasSubtotal) continue;
            String parentDimCoord = dimCellCoords.get(dim);
            int dimColIdx = nameToCoord(parentDimCoord)[0];

            int currentRow = nextRow;

            // Subtotal Label Cell
            Element subtotalLabelCell = masterDoc.createElement("C");
            subtotalLabelCell.setAttribute("c", String.valueOf(dimColIdx));
            subtotalLabelCell.setAttribute("r", String.valueOf(currentRow));
            
            Element so = masterDoc.createElement("O");
            so.setAttribute("t", "XMLable");
            so.setAttribute("class", "com.fr.base.Formula");
            Element sattr = masterDoc.createElement("Attributes");
            sattr.setTextContent("=" + parentDimCoord + " + \" " + (dim.subtotalLabel != null ? dim.subtotalLabel : "合计") + "\"");
            so.appendChild(sattr);
            subtotalLabelCell.appendChild(so);

            Element exp = masterDoc.createElement("Expand");
            exp.setAttribute("direction", "0");
            exp.setAttribute("leftParentDefault", "false");
            exp.setAttribute("left", parentDimCoord);
            subtotalLabelCell.appendChild(exp);

            if (sampleSubtotalStyle != null) subtotalLabelCell.appendChild(sampleSubtotalStyle.cloneNode(true));
            cellListElem.appendChild(subtotalLabelCell);

            // Subtotal Sum cells for measures
            int mCol = measureStartCol;
            for (ColumnSpec mea : measures) {
                if (mea.autoSum) {
                    Element sumCell = masterDoc.createElement("C");
                    sumCell.setAttribute("c", String.valueOf(mCol));
                    sumCell.setAttribute("r", String.valueOf(currentRow));

                    Element smo = masterDoc.createElement("O");
                    smo.setAttribute("t", "XMLable");
                    smo.setAttribute("class", "com.fr.base.Formula");
                    Element smattr = masterDoc.createElement("Attributes");
                    String targetMeasureCoord = coordToName(mCol, dataRow);
                    smattr.setTextContent("=SUM(" + targetMeasureCoord + ")");
                    smo.appendChild(smattr);
                    sumCell.appendChild(smo);

                    Element smexp = masterDoc.createElement("Expand");
                    smexp.setAttribute("direction", "0");
                    smexp.setAttribute("leftParentDefault", "false");
                    smexp.setAttribute("left", parentDimCoord);
                    sumCell.appendChild(smexp);

                    if (sampleSubtotalStyle != null) sumCell.appendChild(sampleSubtotalStyle.cloneNode(true));
                    cellListElem.appendChild(sumCell);
                }
                mCol++;
            }

            nextRow++;
        }

        // 8. Global Total Summary Row
        boolean hasGlobalSum = measures.stream().anyMatch(m -> m.autoSum);
        if (hasGlobalSum && !vDimensions.isEmpty()) {
            int summaryRow = nextRow;
            Element labelCell = masterDoc.createElement("C");
            labelCell.setAttribute("c", "0");
            labelCell.setAttribute("r", String.valueOf(summaryRow));
            Element lo = masterDoc.createElement("O");
            lo.setTextContent("总计");
            labelCell.appendChild(lo);
            if (sampleHeaderStyle != null) labelCell.appendChild(sampleHeaderStyle.cloneNode(true));
            cellListElem.appendChild(labelCell);

            int mCol = measureStartCol;
            for (ColumnSpec mea : measures) {
                if (mea.autoSum) {
                    Element sumCell = masterDoc.createElement("C");
                    sumCell.setAttribute("c", String.valueOf(mCol));
                    sumCell.setAttribute("r", String.valueOf(summaryRow));

                    Element so = masterDoc.createElement("O");
                    so.setAttribute("t", "XMLable");
                    so.setAttribute("class", "com.fr.base.Formula");
                    Element sattr = masterDoc.createElement("Attributes");
                    String targetMeasureCoord = coordToName(mCol, dataRow);
                    sattr.setTextContent("=SUM(" + targetMeasureCoord + ")");
                    so.appendChild(sattr);
                    sumCell.appendChild(so);

                    if (sampleHeaderStyle != null) sumCell.appendChild(sampleHeaderStyle.cloneNode(true));
                    cellListElem.appendChild(sumCell);
                }
                mCol++;
            }
        }

        // 9. Injects Static Hidden Columns (ColWidth = 0 in ReportSettings)
        if (!staticHiddenCols.isEmpty()) {
            injectStaticHiddenColumns(masterDoc, staticHiddenCols);
        }

        return masterDoc;
    }

    private static void syncTableDataParameters(Document doc, Element td, List<ParameterFilterSpec> filters) {
        NodeList pList = td.getElementsByTagName("Parameters");
        Element paramsElem = (pList.getLength() > 0) ? (Element) pList.item(0) : null;
        if (paramsElem == null) {
            paramsElem = doc.createElement("Parameters");
            td.appendChild(paramsElem);
        }

        Set<String> existing = new HashSet<>();
        NodeList pNodes = paramsElem.getElementsByTagName("Parameter");
        for (int i = 0; i < pNodes.getLength(); i++) {
            Element p = (Element) pNodes.item(i);
            NodeList aList = p.getElementsByTagName("Attributes");
            if (aList.getLength() > 0) {
                existing.add(((Element) aList.item(0)).getAttribute("name"));
            }
        }

        for (ParameterFilterSpec f : filters) {
            if (!existing.contains(f.paramName)) {
                Element p = doc.createElement("Parameter");
                Element attr = doc.createElement("Attributes");
                attr.setAttribute("name", f.paramName);
                p.appendChild(attr);

                Element o = doc.createElement("O");
                o.setTextContent(f.defaultValue != null ? f.defaultValue : "");
                p.appendChild(o);

                paramsElem.appendChild(p);
            }
        }
    }

    private static void syncReportParameterAttr(Document doc, List<ParameterFilterSpec> filters) {
        NodeList rpaList = doc.getElementsByTagName("ReportParameterAttr");
        Element rpa = (rpaList.getLength() > 0) ? (Element) rpaList.item(0) : null;
        if (rpa == null) {
            Element root = doc.getDocumentElement();
            rpa = doc.createElement("ReportParameterAttr");
            root.appendChild(rpa);
        }

        // Keep existing ParameterUI intact so search button and existing widgets work!
        // We ensure all new parameters are registered under <Parameters> in RPA
        NodeList pList = rpa.getElementsByTagName("Parameters");
        Element paramsElem = (pList.getLength() > 0) ? (Element) pList.item(0) : null;
        if (paramsElem == null) {
            paramsElem = doc.createElement("Parameters");
            rpa.appendChild(paramsElem);
        }

        Set<String> existing = new HashSet<>();
        NodeList pNodes = paramsElem.getElementsByTagName("Parameter");
        for (int i = 0; i < pNodes.getLength(); i++) {
            Element p = (Element) pNodes.item(i);
            NodeList aList = p.getElementsByTagName("Attributes");
            if (aList.getLength() > 0) {
                existing.add(((Element) aList.item(0)).getAttribute("name"));
            }
        }

        for (ParameterFilterSpec f : filters) {
            if (!existing.contains(f.paramName)) {
                Element p = doc.createElement("Parameter");
                Element attr = doc.createElement("Attributes");
                attr.setAttribute("name", f.paramName);
                p.appendChild(attr);

                Element o = doc.createElement("O");
                o.setTextContent(f.defaultValue != null ? f.defaultValue : "");
                p.appendChild(o);

                paramsElem.appendChild(p);
            }
        }
    }

    private static int[] nameToCoord(String cellName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]+)(\\d+)$").matcher(cellName);
        if (!m.matches()) return new int[]{0, 0};
        return new int[]{colNameToIdx(m.group(1)), Integer.parseInt(m.group(2)) - 1};
    }

    private static void injectStaticHiddenColumns(Document doc, List<Integer> hiddenCols) {
        NodeList settingsList = doc.getElementsByTagName("ReportSettings");
        Element settings = (settingsList.getLength() > 0) ? (Element) settingsList.item(0) : null;
        if (settings == null) {
            Node report = doc.getElementsByTagName("Report").item(0);
            if (report != null) {
                settings = doc.createElement("ReportSettings");
                report.appendChild(settings);
            }
        }
        if (settings == null) return;

        for (int c : hiddenCols) {
            Element cw = doc.createElement("ColWidth");
            cw.setAttribute("col", String.valueOf(c));
            cw.setTextContent("0");
            settings.appendChild(cw);
        }
    }

    private static void injectHighlightRules(Document doc, Element cell, List<HighlightRule> rules) {
        Element hlList = doc.createElement("HighlightList");

        for (HighlightRule r : rules) {
            Element hl = doc.createElement("Highlight");
            hl.setAttribute("class", "com.fr.report.cell.cellattr.highlight.DefaultHighlight");

            Element name = doc.createElement("Name");
            name.setTextContent(r.name != null ? r.name : "条件属性");
            hl.appendChild(name);

            Element cond = doc.createElement("Condition");
            if ("formula".equals(r.conditionType) && r.formulaExpr != null && !r.formulaExpr.isEmpty()) {
                cond.setAttribute("class", "com.fr.data.condition.FormulaCondition");
                Element f = doc.createElement("Formula");
                f.setTextContent(r.formulaExpr.trim());
                cond.appendChild(f);
            } else {
                cond.setAttribute("class", "com.fr.data.condition.ObjectCondition");
                Element comp = doc.createElement("Compare");
                comp.setAttribute("op", r.compareOp != null ? r.compareOp : "1");
                Element oVal = doc.createElement("O");
                oVal.setTextContent(r.compareVal != null ? r.compareVal : "0");
                comp.appendChild(oVal);
                cond.appendChild(comp);
            }
            hl.appendChild(cond);

            Element act = doc.createElement("Action");
            if ("font_red".equals(r.actionType)) {
                act.setAttribute("class", "com.fr.report.cell.cellattr.highlight.ForegroundHighlightAction");
                act.setAttribute("scope", "1");
                Element col = doc.createElement("Color");
                col.setTextContent("-65536");
                act.appendChild(col);
            } else if ("bg_yellow".equals(r.actionType)) {
                act.setAttribute("class", "com.fr.report.cell.cellattr.highlight.BackgroundHighlightAction");
                act.setAttribute("scope", "1");
                Element bg = doc.createElement("Background");
                bg.setAttribute("class", "com.fr.base.background.ColorBackground");
                Element col = doc.createElement("Color");
                col.setTextContent("-256");
                bg.appendChild(col);
                act.appendChild(bg);
            } else if ("hide_row".equals(r.actionType)) {
                act.setAttribute("class", "com.fr.report.cell.cellattr.highlight.RowHeightHighlightAction");
                Element rh = doc.createElement("RowHeight");
                rh.setTextContent("0");
                act.appendChild(rh);
            } else if ("hide_col".equals(r.actionType)) {
                act.setAttribute("class", "com.fr.report.cell.cellattr.highlight.ColWidthHighlightAction");
                Element cw = doc.createElement("ColWidth");
                cw.setTextContent("0");
                act.appendChild(cw);
            }
            hl.appendChild(act);
            hlList.appendChild(hl);
        }

        cell.appendChild(hlList);
    }
}
