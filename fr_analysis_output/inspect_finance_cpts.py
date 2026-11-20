import os
import xml.etree.ElementTree as ET
import json
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

root_dir = r'D:\data-fill-system\frReport'

# 查找所有财务管理下的 CPT
finance_cpts = []
for root, dirs, files in os.walk(root_dir):
    if '财务管理' in root:
        for f in files:
            if f.lower().endswith('.cpt'):
                finance_cpts.append(os.path.join(root, f))

print(f"Total finance CPTs found: {len(finance_cpts)}")

# 重点分析几套标杆报表:
# 1. 财务分析会利润表: 01财务分析会小组利润表.cpt
# 2. 运营利润月报: 01.运营利润月报.cpt / 03运营利润SKU明细月报.cpt
# 3. 成本/指导价: 指导价.cpt / 01cost_price核算.cpt
# 4. 费用分摊: 02集团费用分析.cpt / 03分摊明细.cpt
# 5. 同比/趋势: 04报表半年同比.cpt

target_samples = [
    '01财务分析会小组利润表.cpt',
    '01.运营利润月报.cpt',
    '03运营利润SKU明细月报.cpt',
    '指导价.cpt',
    '02集团费用分析.cpt',
    '04报表半年同比.cpt'
]

sample_files = {}
for p in finance_cpts:
    fname = os.path.basename(p)
    if fname in target_samples and fname not in sample_files:
        sample_files[fname] = p

print("Found sample files:", list(sample_files.keys()))

for name, fpath in sample_files.items():
    print(f"\n================================================================================")
    print(f"【深入剖析财务标杆报表】: {name}")
    print(f"路径: {fpath}")
    print("================================================================================")
    tree = ET.parse(fpath)
    root = tree.getroot()
    
    # 1. 数据集列表与主SQL
    td_names = []
    main_sql = ''
    for td in root.findall('.//TableData'):
        td_name = td.get('name')
        if td_name:
            td_names.append(td_name)
            q = td.find('.//Query')
            if q is not None and q.text and not main_sql:
                main_sql = q.text.strip()
    print(f"• 数据集总数: {len(td_names)} 个 -> {td_names[:6]}")
    if main_sql:
        print(f"• 主 SQL 预览 (前 250 字):\n{main_sql[:250]}...")
        
    # 2. 参数列表
    params = []
    for p in root.findall('.//Parameter'):
        attrs = p.find('.//Attributes')
        if attrs is not None and attrs.get('name'):
            params.append(attrs.get('name'))
    print(f"• 报表参数: {list(set(params))}")
    
    # 3. 单元格结构（前 8 行）
    rows = {}
    formulas = []
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
                f_expr = attr.text if attr is not None else ''
                val_str = f"={f_expr}"
                formulas.append((r, c, f_expr))
            elif t == 'DSColumn':
                col = val_node.find('.//ColumnName')
                ds = val_node.find('.//TableDataName')
                val_str = f"DS:{ds.text if ds is not None else ''}.{col.text if col is not None else ''}"
            else:
                val_str = ''.join(val_node.itertext()).strip()
        
        # 扩展
        exp = cell.find('Expand')
        exp_dir = exp.get('dir') if exp is not None else ''
        
        # 父格
        lp = cell.find('.//LeftParent')
        lp_ref = lp.get('ref', '') if lp is not None else ''
        
        # 条件属性
        hls = []
        for hl in cell.findall('.//Highlight'):
            name_tag = hl.find('Name')
            h_name = name_tag.text.strip() if name_tag is not None and name_tag.text else ''
            for act in hl.findall('.//HighlightAction'):
                hls.append(act.get('class', '').split('.')[-1])
                
        rows[r].append((c, val_str, exp_dir, lp_ref, hls))
        
    print(f"• 表格总行数: {len(rows)}, 公式单元格数: {len(formulas)}")
    if formulas:
        print("• 典型代表计算公式 (前 5 个):")
        for r, c, f_expr in formulas[:5]:
            print(f"   Cell({chr(65+c)}{r+1}): ={f_expr[:80]}")
            
    print("• 前 5 行骨架结构:")
    for r in sorted(rows.keys())[:5]:
        cells = sorted(rows[r], key=lambda x: x[0])
        # 过滤空单元格
        valid_cells = [f"{chr(65+c)}{r+1}: {v[:18]}" for c, v, exp, lp, hls in cells if v]
        print(f"   Row {r+1} (非空格 {len(valid_cells)}): {' | '.join(valid_cells[:6])}")
