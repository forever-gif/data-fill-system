import os
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
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

print(f"Total CPT files scanned: {len(cpt_files)}")

features = []

for idx, fpath in enumerate(cpt_files):
    rel_path = os.path.relpath(fpath, root_dir)
    fname = os.path.basename(fpath)
    try:
        tree = ET.parse(fpath)
        root = tree.getroot()
        
        # 1. 数据集
        table_datas = [td.get('name') for td in root.findall('.//TableData') if td.get('name')]
        ds_count = len(table_datas)
        
        # 2. 参数
        params = []
        for p in root.findall('.//Parameter'):
            attrs = p.find('.//Attributes')
            if attrs is not None and attrs.get('name'):
                params.append(attrs.get('name'))
        params = list(set(params))
        
        # 3. 单元格与扩展方向
        has_cross_expand = False # 横向向右扩展
        has_down_expand = False  # 纵向向下扩展
        expand_rows = set()      # 哪些行有扩展单元格
        dim_cols = set()
        measure_cols = set()
        
        cell_count = 0
        max_r = 0
        max_c = 0
        has_row_height_action = False
        has_col_width_action = False
        has_font_red_action = False
        has_bg_color_action = False
        has_formula_cells = 0
        
        # 检查是否包含分组/汇总
        has_group = False
        has_summary = False
        
        # 检查展示方式参数
        has_show_type_param = any('show' in p.lower() or 'type' in p.lower() or 'gran' in p.lower() for p in params)
        
        for cell in root.findall('.//CellElementList/C'):
            cell_count += 1
            r = int(cell.get('r', 0))
            c = int(cell.get('c', 0))
            max_r = max(max_r, r)
            max_c = max(max_c, c)
            
            # 扩展
            expand = cell.find('.//Expand')
            if expand is not None:
                d = expand.get('direction', '')
                if d == '2':
                    has_cross_expand = True
                elif d == '1':
                    has_down_expand = True
                    expand_rows.add(r)
            
            # 内容类型
            val_node = cell.find('O')
            if val_node is not None:
                t = val_node.get('t', '')
                if t == 'Formula':
                    has_formula_cells += 1
                elif t == 'DSColumn':
                    # 查看是分组还是汇总
                    complex_tag = cell.find('.//Complex')
                    # 也可以从其他标签看
                    xml_snippet = ET.tostring(cell, encoding='utf-8').decode('utf-8')
                    if 'select="1"' in xml_snippet or 'G(' in xml_snippet:
                        has_group = True
                    if 'select="2"' in xml_snippet or 'Summary' in xml_snippet or '求和' in xml_snippet:
                        has_summary = True
            
            # 条件属性
            for hl in cell.findall('.//Highlight'):
                for act in hl.findall('.//HighlightAction'):
                    cls_name = act.get('class', '')
                    if 'RowHeight' in cls_name:
                        has_row_height_action = True
                    elif 'ColWidth' in cls_name:
                        has_col_width_action = True
                    elif 'Foreground' in cls_name:
                        has_font_red_action = True
                    elif 'Background' in cls_name:
                        has_bg_color_action = True
                        
        # 结构特征聚类判断
        # 拓扑类型识别:
        archetype = 'UNKNOWN'
        archetype_reason = ''
        
        if has_cross_expand:
            archetype = 'CROSS_TAB' # 横向交叉透视报表 (矩阵表/年月周横向扩展)
            archetype_reason = '存在向右扩展单元格(direction=2)，多维年月或品类横向交叉展开'
        elif len(expand_rows) >= 3 and has_row_height_action and has_show_type_param:
            archetype = 'MULTI_GRANULARITY_DYNAMIC' # 多粒度动态聚合/折叠报表
            archetype_reason = f'包含多行独立展开行({len(expand_rows)}行)且通过展示方式参数与行高/列宽互锁'
        elif len(expand_rows) >= 2 or (has_group and has_summary):
            archetype = 'GROUP_SUBTOTAL' # 多级分组小计/层级汇总报表
            archetype_reason = '多层级纵向分组，带部门/品类/事业部小计或总计行'
        elif max_c >= 15 and has_down_expand:
            archetype = 'WIDE_DETAIL_LIST' # 超宽表明细清单/监控表
            archetype_reason = f'单行扩展的宽列表格({max_c+1}列)，包含大量监控维度与指标'
        else:
            archetype = 'STANDARD_REPORT' # 常规单维度报表
            archetype_reason = '标准单向展开分析报表'
            
        features.append({
            'file': fname,
            'rel_path': rel_path,
            'archetype': archetype,
            'archetype_reason': archetype_reason,
            'max_rows': max_r + 1,
            'max_cols': max_c + 1,
            'expand_rows_count': len(expand_rows),
            'has_cross': has_cross_expand,
            'has_down': has_down_expand,
            'has_row_height_action': has_row_height_action,
            'has_col_width_action': has_col_width_action,
            'has_font_red': has_font_red_action,
            'has_bg_color': has_bg_color_action,
            'ds_count': ds_count,
            'params': params,
            'formula_cells': has_formula_cells
        })
    except Exception as e:
        # print(f"Error parsing {fname}: {e}")
        pass

# 聚类统计
counter = Counter(f['archetype'] for f in features)
print("\n" + "="*80)
print(f"210 套生产 CPT 报表大类聚类分布 (成功解析: {len(features)} 套)")
print("="*80)
for k, count in counter.most_common():
    pct = count / len(features) * 100
    print(f"【模板大类】{k:28s} : {count:3d} 套 ({pct:5.1f}%)")

print("\n" + "="*80)
print("各模板大类最具代表性的生产 CPT 报表示例")
print("="*80)
by_type = defaultdict(list)
for f in features:
    by_type[f['archetype']].append(f)

for k, list_files in by_type.items():
    print(f"\n>>> 类别: {k} (共 {len(list_files)} 套)")
    for item in list_files[:4]:
        print(f"   • {item['file']:35s} (列数:{item['max_cols']:2d}, 展开行数:{item['expand_rows_count']:2d}, 横向扩展:{item['has_cross']}, 参数数:{len(item['params'])})")
        print(f"     路径: {item['rel_path']}")

# 保存聚类结果供后续集成使用
out_path = r'D:\data-fill-system\fr_analysis_output\cpt_archetype_clusters.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump({
        'total': len(features),
        'summary': dict(counter),
        'features': features
    }, f, ensure_ascii=False, indent=2)

print(f"\n聚类分析画像已保存至: {out_path}")
