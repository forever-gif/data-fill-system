import xml.etree.ElementTree as ET
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

cpt_path = r'D:\data-fill-system\frReport\0c4268cc-dd61-448b-8724-0dcd9f1b0ebf\美鹰集团报表\01生产环境\05.物流管理\01.周转率周报\01品类周转率分析.cpt'

tree = ET.parse(cpt_path)
root = tree.getroot()

print("==================== 1. 主 SQL 详情 ====================")
for td in root.findall('.//TableData'):
    name = td.get('name')
    query = td.find('.//Query')
    if query is not None and query.text:
        print(f"Dataset Name: {name}")
        print("SQL:")
        print(query.text.strip())
        print("-" * 60)

print("\n==================== 2. 参数 first_show 的配置与单选选项 ====================")
for w in root.findall('.//Widget'):
    w_name = w.get('name', '')
    if 'first_show' in w_name or '展示方式' in str(w.attrib):
        print(f"Widget Name: {w_name}, Class: {w.get('class')}")
        for elem in w.iter():
            if elem.text and elem.text.strip():
                print(f"  {elem.tag}: {elem.text.strip()}")

print("\n==================== 3. 模板行 (第 2 行至第 8 行) 深度剖析 ====================")
# 找到所有单元格
cells_by_pos = {}
for cell in root.findall('.//CellElement'):
    r = int(cell.get('r', 0))
    c = int(cell.get('c', 0))
    cells_by_pos[(r, c)] = cell

for r in range(1, 10):
    row_cells = [(c, cells_by_pos[(r, c)]) for c in range(0, 30) if (r, c) in cells_by_pos]
    if not row_cells:
        continue
    print(f"\n>>>>>>>>>>>>>> 【第 {r+1} 行 (XML r={r})】(共 {len(row_cells)} 个单元格) <<<<<<<<<<<<<<")
    for c, cell in row_cells:
        val_node = cell.find('O')
        val_str = ''
        if val_node is not None:
            t = val_node.get('t', '')
            if t == 'Formula':
                attr = val_node.find('.//Attributes')
                val_str = f"Formula: {attr.text if attr is not None else ''}"
            elif t == 'DSColumn':
                col = val_node.find('.//ColumnName')
                ds = val_node.find('.//TableDataName')
                val_str = f"DS: {ds.text if ds is not None else ''}.{col.text if col is not None else ''}"
            else:
                val_str = ''.join(val_node.itertext()).strip()
        
        expand = cell.find('.//Expand')
        exp_dir = expand.get('direction', '') if expand is not None else ''
        exp_desc = "纵向(↓)" if exp_dir == '1' else ("横向(→)" if exp_dir == '2' else "不扩展")
        
        lp = cell.find('.//LeftParent')
        lp_ref = lp.get('ref', '') if lp is not None else ''
        
        # 条件属性
        hls = []
        for hl in cell.findall('.//Highlight'):
            name = hl.get('name', '')
            cond_node = hl.find('.//Condition')
            cond = ''.join(cond_node.itertext()).strip() if cond_node is not None else ''
            actions = []
            for act in hl.findall('.//Action'):
                act_cls = act.get('class', '').split('.')[-1]
                act_val = ''.join(act.itertext()).strip()
                actions.append(f"{act_cls}({act_val[:15]})")
            hls.append(f"[{name}: IF({cond}) -> {','.join(actions)}]")
            
        hl_str = ("\n      ★条件属性: " + " | ".join(hls)) if hls else ""
        lp_str = f" [左父格:{lp_ref}]" if lp_ref else ""
        print(f"  列{c:2d}: {val_str:32s} | 扩展:{exp_desc}{lp_str}{hl_str}")
