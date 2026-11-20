import xml.etree.ElementTree as ET
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

cpt_path = r'D:\data-fill-system\frReport\0c4268cc-dd61-448b-8724-0dcd9f1b0ebf\美鹰集团报表\01生产环境\05.物流管理\01.周转率周报\01品类周转率分析.cpt'

tree = ET.parse(cpt_path)
root = tree.getroot()

print("==================== 1. 参数面板上的控件 ====================")
for w in root.findall('.//Widget'):
    w_name = w.get('name', '')
    w_class = w.get('class', '').split('.')[-1]
    # 查找是否有 Radio, Button, first_show 等
    label = ''
    items = []
    dict_elem = w.find('.//Dictionary')
    if dict_elem is not None:
        for item in dict_elem.iter():
            if item.text and item.text.strip():
                items.append(item.text.strip())
    print(f"Widget: name={w_name:20s} class={w_class:20s} dict={','.join(items[:6])}")

print("\n==================== 2. 单元格各行深度分析 ====================")
rows = {}
for cell in root.findall('.//CellElementList/C'):
    r = int(cell.get('r', 0))
    c = int(cell.get('c', 0))
    if r not in rows:
        rows[r] = []
    
    val_node = cell.find('O')
    val_str = ''
    if val_node is not None:
        t = val_node.get('t', '')
        if t == 'Formula':
            attr = val_node.find('.//Attributes')
            val_str = f"={attr.text if attr is not None else ''}"
        elif t == 'DSColumn':
            col = val_node.find('.//ColumnName')
            ds = val_node.find('.//TableDataName')
            val_str = f"DS:{ds.text if ds is not None else ''}.{col.text if col is not None else ''}"
        else:
            val_str = ''.join(val_node.itertext()).strip()
    
    expand = cell.find('.//Expand')
    exp_dir = expand.get('direction', '') if expand is not None else ''
    exp_desc = "↓" if exp_dir == '1' else ("→" if exp_dir == '2' else "-")
    
    lp = cell.find('.//LeftParent')
    lp_ref = lp.get('ref', '') if lp is not None else ''
    if lp is not None and lp.get('type') == '0':
        lp_ref = '默认父格'
    elif lp is not None and lp.get('type') == '1':
        lp_ref = '无父格(None)'
    
    # 过滤条件
    filt = cell.find('.//Condition')
    filt_str = ''
    if filt is not None:
        filt_str = ''.join(filt.itertext()).strip()[:30]
        
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
            actions.append(f"{act_cls}({act_val[:10]})")
        hls.append(f"{name}[{cond[:25]} -> {','.join(actions)}]")
        
    rows[r].append((c, val_str, exp_desc, lp_ref, filt_str, hls))

for r in sorted(rows.keys()):
    if r < 12: # 重点看前 12 行
        cells = sorted(rows[r], key=lambda x: x[0])
        print(f"\n>>>>>>>>>>>>>> 【Row {r+1} (XML r={r})】共 {len(cells)} 个单元格 <<<<<<<<<<<<<<")
        for c, v, exp, lp, filt, hls in cells:
            v_short = (v[:30] + '..') if len(v) > 30 else v
            lp_info = f" [左父:{lp}]" if lp else ""
            exp_info = f" [扩:{exp}]" if exp != '-' else ""
            f_info = f" [过滤:{filt}]" if filt else ""
            hl_info = f"\n        ★HL: {' | '.join(hls)}" if hls else ""
            print(f"  Col {c:2d}: {v_short:32s}{exp_info}{lp_info}{f_info}{hl_info}")
