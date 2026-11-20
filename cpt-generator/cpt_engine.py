# -*- coding: utf-8 -*-
"""
Universal FineReport CPT Engine
- XML DOM Parsing & Preservation
- Coordinate Mapping (A1 <-> col 0, row 0)
- Grid Shifting & Cell Insertion/Deletion
- Formula AST / Cell Reference Rewriting (e.g., =SUM(D3:F3) shifts when inserting column)
- Parent-child Hierarchy Propagation
- SQL Dataset & Schema Binding
"""

import re
import copy
import xml.etree.ElementTree as ET

def col_idx_to_name(col_idx: int) -> str:
    """Convert 0-based col index to letter (0 -> A, 27 -> AB)."""
    result = ""
    col_idx += 1
    while col_idx > 0:
        col_idx, remainder = divmod(col_idx - 1, 26)
        result = chr(65 + remainder) + result
    return result

def col_name_to_idx(col_name: str) -> int:
    """Convert col letters to 0-based index (A -> 0, AB -> 27)."""
    idx = 0
    for char in col_name.upper():
        idx = idx * 26 + (ord(char) - ord('A') + 1)
    return idx - 1

def coord_to_name(col_idx: int, row_idx: int) -> str:
    """Convert (0, 2) -> A3."""
    return f"{col_idx_to_name(col_idx)}{row_idx + 1}"

def name_to_coord(cell_name: str):
    """Convert A3 -> (0, 2)."""
    m = re.match(r"^([A-Za-z]+)(\d+)$", cell_name)
    if not m:
        return None, None
    col_str, row_str = m.groups()
    return col_name_to_idx(col_str), int(row_str) - 1

def shift_cell_reference_in_formula(formula_text: str, insert_at_col: int, num_cols: int = 1) -> str:
    """
    Intelligently rewrites cell coordinates in a formula when columns are shifted.
    e.g. if insert_at_col = 3 (column D), then E5 becomes F5, but C5 remains C5.
    Works for single cells and ranges like =SUM(D4:G4).
    """
    if not formula_text:
        return formula_text

    def replace_match(match):
        prefix = match.group(1) or "" # maybe $, !, or nothing
        col_letters = match.group(2)
        row_digits = match.group(3)
        suffix = match.group(4) or ""

        col_idx = col_name_to_idx(col_letters)
        if col_idx >= insert_at_col:
            new_col_letters = col_idx_to_name(col_idx + num_cols)
            return f"{prefix}{new_col_letters}{row_digits}{suffix}"
        return match.group(0)

    # Regex matches patterns like A3, $B$4, C5:D8, E5[!0]
    pattern = r'(\$|!)?([A-Za-z]+)(\$?\d+)(\[?[^\]]*\]?)'
    return re.sub(pattern, replace_match, formula_text)


class CptModel:
    def __init__(self, xml_content: str):
        self.xml_content = xml_content
        # Register namespace or parse directly
        self.root = ET.fromstring(xml_content.encode('utf-8'))
        self.report = self.root.find(".//Report")
        self.cell_list_elem = self.root.find(".//CellElementList")
        if self.cell_list_elem is None:
            # Create if absent
            if self.report is not None:
                self.cell_list_elem = ET.SubElement(self.report, "CellElementList")

    def get_datasets(self):
        """Extract all datasets and queries."""
        datasets = []
        td_map = self.root.find("TableDataMap")
        if td_map is not None:
            for td in td_map.findall("TableData"):
                name = td.attrib.get("name", "")
                q = td.find(".//Query")
                q_text = q.text.strip() if (q is not None and q.text) else ""
                
                # Parameters
                params = []
                for p in td.findall(".//Parameter"):
                    attr = p.find("Attributes")
                    if attr is not None:
                        pn = attr.attrib.get("name")
                        if pn:
                            params.append(pn)
                datasets.append({"name": name, "query": q_text, "params": params})
        return datasets

    def update_dataset_query(self, dataset_name: str, new_query: str):
        """Update or inject a SQL query into a specific dataset."""
        td_map = self.root.find("TableDataMap")
        if td_map is None:
            td_map = ET.SubElement(self.root, "TableDataMap")
        
        target_td = None
        for td in td_map.findall("TableData"):
            if td.attrib.get("name") == dataset_name:
                target_td = td
                break
        
        if target_td is None:
            # create new
            target_td = ET.SubElement(td_map, "TableData", {
                "name": dataset_name,
                "class": "com.fr.data.impl.DBTableData"
            })
            conn = ET.SubElement(target_td, "Connection", {"class": "com.fr.data.impl.NameDatabaseConnection"})
            db = ET.SubElement(conn, "DatabaseName")
            db.text = "美鹰PG数仓"

        q = target_td.find(".//Query")
        if q is None:
            q = ET.SubElement(target_td, "Query")
        q.text = ET.CDATA(new_query) if hasattr(ET, 'CDATA') else new_query

    def get_cells(self):
        """Parse all cells into structured grid metadata."""
        cells = {}
        if self.cell_list_elem is None:
            return cells

        for cell in self.cell_list_elem.findall("C"):
            c = int(cell.attrib.get("c", 0))
            r = int(cell.attrib.get("r", 0))
            name = coord_to_name(c, r)

            # Value & Binding
            val = ""
            val_type = "string"
            ds_column = ""
            o_tag = cell.find("O")
            if o_tag is not None:
                val_type = o_tag.attrib.get("t", "string")
                if "Formula" in o_tag.attrib.get("class", "") or val_type == "Formula":
                    val_type = "Formula"
                    attr = o_tag.find("Attributes")
                    val = attr.text.strip() if (attr is not None and attr.text) else ""
                elif "DSColumn" in o_tag.attrib.get("class", "") or val_type == "DSColumn":
                    val_type = "DSColumn"
                    col_elem = o_tag.find(".//ColumnName")
                    ds_column = col_elem.text.strip() if (col_elem is not None and col_elem.text) else ""
                    val = f"=ds.{ds_column}"
                else:
                    val = o_tag.text.strip() if o_tag.text else ""

            # Expand
            expand = cell.find("Expand")
            direction = "0"
            left_parent = ""
            top_parent = ""
            if expand is not None:
                direction = expand.attrib.get("direction", "0")
                left_parent = expand.attrib.get("left", "")
                top_parent = expand.attrib.get("top", "")

            # Highlight count
            hl_count = len(cell.findall(".//HighlightList/Highlight"))

            cells[name] = {
                "col": c,
                "row": r,
                "name": name,
                "value": val,
                "type": val_type,
                "ds_column": ds_column,
                "direction": direction,
                "left_parent": left_parent,
                "top_parent": top_parent,
                "highlight_count": hl_count,
                "xml_elem": cell
            }
        return cells

    def insert_column(self, at_col_idx: int, header_title: str, dataset_name: str, ds_field: str, formula: str = ""):
        """
        Universal Column Insertion Engine:
        1. Shifts all cells with col >= at_col_idx to the right by 1
        2. Rewrites all formulas referencing shifted columns
        3. Updates parent cell references (left/top)
        4. Copies header styling from adjacent column (at_col_idx - 1)
        5. Injects new data cell bound to ds_field or formula, with leftParent inherited!
        """
        if self.cell_list_elem is None:
            return

        all_cells = self.cell_list_elem.findall("C")

        # Step 1: Shift existing cells to the right
        for cell in all_cells:
            c = int(cell.attrib.get("c", 0))
            if c >= at_col_idx:
                cell.attrib["c"] = str(c + 1)

            # Rewrite formulas in any existing cell
            o_tag = cell.find("O")
            if o_tag is not None and ("Formula" in o_tag.attrib.get("class", "") or o_tag.attrib.get("t") == "Formula"):
                attr = o_tag.find("Attributes")
                if attr is not None and attr.text:
                    attr.text = shift_cell_reference_in_formula(attr.text, at_col_idx, 1)

            # Rewrite leftParent / topParent if pointing to shifted cols
            expand = cell.find("Expand")
            if expand is not None:
                left = expand.attrib.get("left")
                if left:
                    lc, lr = name_to_coord(left)
                    if lc is not None and lc >= at_col_idx:
                        expand.attrib["left"] = coord_to_name(lc + 1, lr)
                top = expand.attrib.get("top")
                if top:
                    tc, tr = name_to_coord(top)
                    if tc is not None and tc >= at_col_idx:
                        expand.attrib["top"] = coord_to_name(tc + 1, tr)

        # Step 2: Determine header row (typically row 0, 1 or 2) and data row
        # We sample the left neighbor (at_col_idx - 1) or right neighbor to clone style
        sample_neighbor = None
        for cell in all_cells:
            if int(cell.attrib.get("c", -1)) == at_col_idx - 1:
                sample_neighbor = cell
                break

        # Assume header is row 1 or 2, data is row 2 or 3
        # Let's create Header Cell at (at_col_idx, 1) or 0
        header_row = 1
        data_row = 2
        # Look for existing data rows
        for cell in all_cells:
            exp = cell.find("Expand")
            if exp is not None and exp.attrib.get("direction") in ["1", "2"]:
                data_row = int(cell.attrib.get("r", 2))
                header_row = max(0, data_row - 1)
                break

        # Create Header Cell
        new_header = ET.SubElement(self.cell_list_elem, "C", {"c": str(at_col_idx), "r": str(header_row)})
        h_o = ET.SubElement(new_header, "O")
        h_o.text = header_title
        if sample_neighbor is not None:
            # Clone Style if present
            s_tag = sample_neighbor.find("Style")
            if s_tag is not None:
                new_header.append(copy.deepcopy(s_tag))

        # Create Data Cell
        new_data = ET.SubElement(self.cell_list_elem, "C", {"c": str(at_col_idx), "r": str(data_row)})
        
        if formula:
            d_o = ET.SubElement(new_data, "O", {"t": "XMLable", "class": "com.fr.base.Formula"})
            d_attr = ET.SubElement(d_o, "Attributes")
            d_attr.text = formula
        else:
            d_o = ET.SubElement(new_data, "O", {"t": "DSColumn", "class": "com.fr.report.cell.cellattr.core.group.DSColumn"})
            d_attr = ET.SubElement(d_o, "Attributes", {"dsName": dataset_name, "columnName": ds_field})
            ET.SubElement(d_o, "Condition", {"class": "com.fr.data.condition.ListCondition"})
            ET.SubElement(d_o, "Complex")
            ET.SubElement(d_o, "RG", {"class": "com.fr.report.cell.cellattr.core.group.SummaryGrouper"})
            ET.SubElement(d_o, "Select")

        # Automatically inherit leftParent from adjacent cell on the same data row
        adjacent_left_parent = ""
        for cell in all_cells:
            if int(cell.attrib.get("r", -1)) == data_row:
                exp = cell.find("Expand")
                if exp is not None and exp.attrib.get("left"):
                    adjacent_left_parent = exp.attrib.get("left")
                    break

        exp_elem = ET.SubElement(new_data, "Expand", {
            "direction": "0",
            "leftParentDefault": "false" if adjacent_left_parent else "true"
        })
        if adjacent_left_parent:
            exp_elem.attrib["left"] = adjacent_left_parent

    def to_xml_string(self) -> str:
        """Serialize back to XML formatted string."""
        return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(self.root, encoding='utf-8').decode('utf-8')
