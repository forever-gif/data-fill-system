# -*- coding: utf-8 -*-
"""
FineReport CPT Assets Analyzer
Scans all 210 CPT templates in D:\data-fill-system\frReport\
Extracts:
1. Common Parameters (fine_username, v_report_date, etc.)
2. Database Connections and Datasets
3. Cell Hierarchies (Parent-child patterns: leftParent, topParent)
4. Formula Patterns (SUM, IF, ratio, ranking, coordinate references)
5. Condition Attributes (Highlight, BackgroundColor, Font, RowHeight=0)
6. Widget / Form Elements in Parameter Panel
"""

import os
import glob
import re
import json
from collections import Counter, defaultdict
import xml.etree.ElementTree as ET

REPORT_ROOT = r"D:\data-fill-system\frReport"
OUTPUT_DIR = r"D:\data-fill-system\fr_analysis_output"

os.makedirs(OUTPUT_DIR, exist_ok=True)

def find_all_cpts(root_dir):
    cpt_files = []
    for dirpath, _, filenames in os.walk(root_dir):
        for f in filenames:
            if f.lower().endswith(".cpt"):
                cpt_files.append(os.path.join(dirpath, f))
    return cpt_files

def analyze_cpts(cpt_files):
    stats = {
        "total_cpts": len(cpt_files),
        "xml_versions": Counter(),
        "release_versions": Counter(),
        "database_connections": Counter(),
        "parameters": Counter(),
        "parameter_types": defaultdict(Counter),
        "parent_child_patterns": Counter(),
        "formula_functions": Counter(),
        "raw_formulas": Counter(),
        "condition_actions": Counter(),
        "condition_colors": Counter(),
        "common_dimensions": Counter(),
    }

    # Samples of structures
    sample_reports = []
    
    # Track detail by category/folder
    folder_dist = Counter()

    for idx, path in enumerate(cpt_files):
        rel_path = os.path.relpath(path, REPORT_ROOT)
        parts = rel_path.split(os.sep)
        # Category usually in folder name
        cat = parts[1] if len(parts) > 1 else "root"
        if len(parts) > 2 and parts[1] == "美鹰集团报表":
            cat = parts[2]
            if len(parts) > 3:
                cat += "/" + parts[3]
        folder_dist[cat] += 1

        try:
            tree = ET.parse(path)
            root = tree.getroot()
        except Exception as e:
            # print(f"Error parsing {rel_path}: {e}")
            continue

        stats["xml_versions"][root.attrib.get("xmlVersion", "unknown")] += 1
        stats["release_versions"][root.attrib.get("releaseVersion", "unknown")] += 1

        # 1. Analyze Database Connections & Parameters in TableDataMap
        td_map = root.find("TableDataMap")
        if td_map is not None:
            for td in td_map.findall("TableData"):
                conn = td.find("Connection")
                if conn is not None:
                    dbname = conn.find("DatabaseName")
                    if dbname is not None and dbname.text:
                        stats["database_connections"][dbname.text.strip()] += 1

                params = td.find("Parameters")
                if params is not None:
                    for p in params.findall("Parameter"):
                        attr = p.find("Attributes")
                        if attr is not None:
                            p_name = attr.attrib.get("name")
                            if p_name:
                                stats["parameters"][p_name] += 1
                                o_tag = p.find("O")
                                if o_tag is not None:
                                    p_type = o_tag.attrib.get("t", "String")
                                    stats["parameter_types"][p_name][p_type] += 1

        # Check Global ReportParameterAttr
        rpa = root.find("ReportParameterAttr")
        if rpa is not None:
            # Check widgets and global params
            for p in rpa.findall(".//Parameter"):
                attr = p.find("Attributes")
                if attr is not None:
                    p_name = attr.attrib.get("name")
                    if p_name:
                        stats["parameters"][p_name] += 1

        # 2. Analyze Report Cells: Hierarchies (Parent-Child), Formulas, Condition Attributes
        for report in root.findall(".//Report"):
            cell_list = report.find(".//CellElementList")
            if cell_list is None:
                continue

            for cell in cell_list.findall("C"):
                col = cell.attrib.get("c")
                row = cell.attrib.get("r")

                # Expand (Parent-child hierarchy)
                expand = cell.find("Expand")
                if expand is not None:
                    direction = expand.attrib.get("direction", "default")
                    left_parent = expand.attrib.get("left", "")
                    top_parent = expand.attrib.get("top", "")
                    left_def = expand.attrib.get("leftParentDefault", "true")
                    top_def = expand.attrib.get("topParentDefault", "true")

                    if left_parent:
                        stats["parent_child_patterns"][f"LeftParent: {left_parent} (dir={direction})"] += 1
                    if top_parent:
                        stats["parent_child_patterns"][f"TopParent: {top_parent} (dir={direction})"] += 1

                # Formulas
                o_val = cell.find("O")
                if o_val is not None and o_val.attrib.get("t") == "Formula":
                    attr = o_val.find("Attributes")
                    if attr is not None and attr.text:
                        f_text = attr.text.strip()
                        # Clean CDATA or leading '='
                        clean_f = f_text.lstrip("=")
                        stats["raw_formulas"][f_text] += 1
                        
                        # Find Excel / FineReport functions like SUM, IF, ROUND, etc.
                        funcs = re.findall(r'\b([A-Z_]+)\s*\(', clean_f)
                        for fn in funcs:
                            if len(fn) > 1 and fn not in ["SELECT", "FROM", "WHERE", "AND", "OR"]:
                                stats["formula_functions"][fn] += 1

                # Highlight / Condition Attributes
                hl_list = cell.find(".//HighlightList")
                if hl_list is not None:
                    for hl in hl_list.findall("Highlight"):
                        for action in hl.findall("Action"):
                            act_cls = action.attrib.get("class", "").split(".")[-1]
                            stats["condition_actions"][act_cls] += 1

                            # If color action
                            color_tag = action.find(".//Color")
                            if color_tag is not None and color_tag.text:
                                stats["condition_colors"][color_tag.text.strip()] += 1

                        # Check row height 0 action (RowHidden)
                        for rha in hl.findall(".//RowHeightHighlightAction"):
                            stats["condition_actions"]["RowHeightHighlightAction"] += 1

    return stats, folder_dist

if __name__ == "__main__":
    cpts = find_all_cpts(REPORT_ROOT)
    print(f"Total CPT files found: {len(cpts)}")
    stats, folder_dist = analyze_cpts(cpts)

    result = {
        "summary": {
            "total_cpts": stats["total_cpts"],
            "xml_versions": dict(stats["xml_versions"]),
            "release_versions": dict(stats["release_versions"]),
        },
        "folder_distribution": dict(folder_dist.most_common(20)),
        "database_connections": dict(stats["database_connections"].most_common(15)),
        "top_25_parameters": dict(stats["parameters"].most_common(25)),
        "top_20_formula_functions": dict(stats["formula_functions"].most_common(20)),
        "top_20_popular_formulas": dict(stats["raw_formulas"].most_common(20)),
        "top_parent_child_patterns": dict(stats["parent_child_patterns"].most_common(20)),
        "condition_attribute_actions": dict(stats["condition_actions"].most_common(15)),
        "condition_colors": dict(stats["condition_colors"].most_common(15))
    }

    out_file = os.path.join(OUTPUT_DIR, "cpt_assets_summary.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print("Analysis finished successfully! Results saved to:", out_file)
