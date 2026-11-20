import xml.etree.ElementTree as ET
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

cpt_path = r'D:\data-fill-system\frReport\0c4268cc-dd61-448b-8724-0dcd9f1b0ebf\美鹰集团报表\01生产环境\05.物流管理\01.周转率周报\01品类周转率分析.cpt'

tree = ET.parse(cpt_path)
root = tree.getroot()

# 1. 查看 first_show 的字典定义
for elem in root.iter():
    if elem.tag == 'Dictionary' or 'first_show' in str(elem.attrib) or elem.text == 'first_show':
        # 打印其上下文
        p = elem
        # 打印子项
        items = [f"{c.tag}:{c.text}" for c in elem.iter() if c.text and c.text.strip()]
        if any('展示' in x or 'SKU' in x or 'SPU' in x or '品类' in x for x in items):
            print("找到展示方式字典配置:", items)

# 2. 详细分析第 1 行到第 7 行的所有单元格详情
print("\n" + "="*80)
print("【第 2 行到第 6 行完整矩阵细节】")
print("="*80)

cells_by_pos = {}
for cell in root.findall('.//CellElementList/C'):
    r = int(cell.get('r', 0))
    c = int(cell.get('c', 0))
    cells_by_pos[(r, c)] = cell

for r in range(1, 7):
    print(f"\n====================== ROW {r+1} (r={r}) ======================")
    for c in range(0, 24):
        if (r, c) not in cells_by_pos:
            continue
        cell = cells_by_pos[(r, c)]
        
        # 1. 字段/值
        val_node = cell.find('O')
        val_str = ''
        if val_node is not None:
            t = val_node.get('t', '')
            if t == 'Formula':
                attr = val_node.find('.//Attributes')
                val_str = f"公式:{attr.text if attr is not None else ''}"
            elif t == 'DSColumn':
                col = val_node.find('.//ColumnName')
                ds = val_node.find('.//TableDataName')
                val_str = f"DS:{col.text if col is not None else ''}"
            else:
                val_str = ''.join(val_node.itertext()).strip()
        
        # 2. 父格
        lp = cell.find('.//LeftParent')
        lp_ref = lp.get('ref', '') if lp is not None else ''
        
        # 3. 条件属性
        hls = []
        for hl in cell.findall('.//Highlight'):
            name = hl.get('name', '')
            cond_node = hl.find('.//Condition')
            cond_xml = ''.join(cond_node.itertext()).strip() if cond_node is not None else ''
            
            # Action 详细信息
            act_details = []
            for act in hl.findall('.//Action'):
                act_cls = act.get('class', '').split('.')[-1]
                # 抓取子属性
                sub_txt = ' '.join([f"{k}={v}" for k, v in act.attrib.items()])
                text_content = ''.join(act.itertext()).strip()
                act_details.append(f"{act_cls}({sub_txt} {text_content})")
            
            hls.append(f"{name} | 条件:{cond_xml} | 动作:{'; '.join(act_details)}")
        
        print(f"Col {c:2d} ({chr(65+c)}{r+1}): {val_str:30s} [左父:{lp_ref or '默认'}]")
        for h in hls:
            print(f"       >>> 条件属性: {h}")
