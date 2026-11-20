import os
import xml.etree.ElementTree as ET
from collections import Counter
import json
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

root_dir = r'D:\data-fill-system\frReport'

cpt_files = []
for root, dirs, files in os.walk(root_dir):
    for f in files:
        if f.lower().endswith('.cpt'):
            cpt_files.append(os.path.join(root, f))

classified_list = []

for idx, fpath in enumerate(cpt_files):
    rel_path = os.path.relpath(fpath, root_dir)
    fname = os.path.basename(fpath)
    
    # 默认分类
    archetype_key = 'STANDARD_METRIC_CARD'
    
    # 特征检测
    has_cross = False
    has_row_height = False
    max_c = 0
    subtotal_count = 0
    
    try:
        tree = ET.parse(fpath)
        root = tree.getroot()
        
        for cell in root.findall('.//CellElementList/C'):
            c = int(cell.get('c', 0))
            max_c = max(max_c, c)
            
            exp = cell.find('Expand')
            if exp is not None and exp.get('dir') == '1':
                has_cross = True
                
            for hl in cell.findall('.//Highlight'):
                for act in hl.findall('.//HighlightAction'):
                    if 'RowHeight' in act.get('class', ''):
                        has_row_height = True
                        
            val_node = cell.find('O')
            if val_node is not None:
                txt = ''.join(val_node.itertext()).strip()
                if '小计' in txt or '合计' in txt:
                    subtotal_count += 1
    except:
        pass
        
    # 分类逻辑优先级：
    # 1. 财务管理目录优先归类为 FINANCIAL_PL
    if '财务管理' in rel_path or '财务' in fname or '利润' in fname:
        if max_c >= 25 and not ('财务分析会' in fname or '利润表' in fname):
            archetype_key = 'WIDE_DETAIL_AUDIT' # 财务大宽表
        else:
            archetype_key = 'FINANCIAL_PL' # 财务损益表
    # 2. 多粒度动态折叠 (周转率分析)
    elif ('周转率' in fname or '经营总表' in fname or has_row_height) and ('分析' in fname or '动态' in fname):
        archetype_key = 'MULTI_GRANULARITY_DYNAMIC'
    # 3. 交叉矩阵报表
    elif has_cross or '计划' in fname or '交叉' in fname or '时效' in fname:
        archetype_key = 'CROSS_TAB_MATRIX'
    # 4. 多级小计报表
    elif subtotal_count >= 1 or '品类' in fname or '事业部' in fname or '专员' in fname:
        archetype_key = 'MULTI_LEVEL_SUBTOTAL'
    # 5. 大宽表
    elif max_c >= 20:
        archetype_key = 'WIDE_DETAIL_AUDIT'
    else:
        archetype_key = 'STANDARD_METRIC_CARD'
        
    classified_list.append({
        'name': fname,
        'path': fpath,
        'rel_path': rel_path,
        'archetypeKey': archetype_key,
        'maxCols': max_c + 1
    })

counts = Counter(c['archetypeKey'] for c in classified_list)
print("Classification counts:")
for k, v in counts.items():
    print(f"  {k:30s}: {v}")

out_path = r'D:\data-fill-system\fr_analysis_output\cpt_classified_templates.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(classified_list, f, ensure_ascii=False, indent=2)

print(f"Saved to {out_path}")
