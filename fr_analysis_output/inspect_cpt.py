import xml.etree.ElementTree as ET
import sys

cpt_path = r'D:\data-fill-system\frReport\0c4268cc-dd61-448b-8724-0dcd9f1b0ebf\美鹰集团报表\01生产环境\05.物流管理\01.周转率周报\01品类周转率分析.cpt'

tree = ET.parse(cpt_path)
root = tree.getroot()

print("=== 1. 所有数据集及 SQL ===")
for td in root.findall('.//TableData'):
    name = td.get('name')
    query = td.find('.//Query')
    q_txt = query.text.strip() if query is not None and query.text else ''
    print(f"--- Dataset: {name} (SQL length: {len(q_txt)}) ---")
    if 'SELECT' in q_txt.upper():
        print("SQL预览:\n" + q_txt[:400] + "...\n")

print("\n=== 2. 报表参数与参数面板控件 ===")
for p in root.findall('.//Parameter'):
    attrs = p.find('.//Attributes')
    if attrs is not None:
        pname = attrs.get('name')
        print(f"Param: {pname}")

print("\n=== 3. 展示方式单选控件的字典配置 ===")
for w in root.findall('.//Widget'):
    w_name = w.get('name', '')
    w_class = w.get('class', '').split('.')[-1]
    if 'show' in w_name.lower() or 'type' in w_name.lower() or 'Radio' in w_class:
        print(f"Widget: {w_name}, Class: {w_class}")
        for item in w.iter():
            if item.text and len(item.text.strip()) > 0 and len(item.text.strip()) < 50:
                print(f"   text: {item.text.strip()}")

print("\n=== 4. 重点分析：第 2、3、4、5、6、7、8 行所有单元格及条件属性 ===")
rows = {}
for cell in root.findall('.//CellElement'):
    r = int(cell.get('r', 0))
    c = int(cell.get('c', 0))
    if r not in rows:
        rows[r] = []
    
    val_node = cell.find('O')
    val_str = ''
    if val_node is not None:
        t = val_node.get('t', '')
        if t == 'Formula':
            formula = val_node.find('.//Attributes')
            val_str = formula.text if formula is not None else ''.join(val_node.itertext())
        else:
            ds_col = val_node.find('.//ColumnName')
            ds_name = val_node.find('.//TableDataName')
            if ds_col is not None and ds_name is not None:
                val_str = f"DS:{ds_name.text}.{ds_col.text}"
            else:
                val_str = ''.join(val_node.itertext()).strip()
    
    expand = cell.find('.//Expand')
    exp_dir = expand.get('direction', '') if expand is not None else ''
    
    left_p = cell.find('.//LeftParent')
    lp = left_p.get('ref', '') if left_p is not None else ''
    
    up_p = cell.find('.//UpParent')
    up = up_p.get('ref', '') if up_p is not None else ''

    hls = []
    for hl in cell.findall('.//Highlight'):
        hl_name = hl.get('name', '')
        cond = hl.find('.//Condition')
        cond_expr = ''
        if cond is not None:
            cond_expr = ''.join(cond.itertext()).strip()
        acts = []
        for act in hl.findall('.//Action'):
            act_cls = act.get('class', '').split('.')[-1]
            act_val = ''.join(act.itertext()).strip()
            acts.append(f"{act_cls}({act_val[:20]})")
        hls.append(f"[{hl_name}: cond={cond_expr[:40]} => {', '.join(acts)}]")
    
    rows[r].append((c, val_str, exp_dir, lp, up, hls))

for r in sorted(rows.keys()):
    if r < 10:
        cells = sorted(rows[r], key=lambda x: x[0])
        print(f"\n====================== ROW {r} (Total cells: {len(cells)}) ======================")
        for c, v, exp, lp, up, hls in cells:
            exp_info = f" Exp:{exp}" if exp else ""
            lp_info = f" LeftParent:{lp}" if lp else ""
            up_info = f" UpParent:{up}" if up else ""
            hl_info = f"\n      Highlights: {' | '.join(hls)}" if hls else ""
            print(f"  Col {c:2d}: {v:30s}{exp_info}{lp_info}{up_info}{hl_info}")
