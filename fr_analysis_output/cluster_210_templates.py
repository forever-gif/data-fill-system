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
        
        # 3. 单元格统计
        expand_down_rows = set()
        expand_right_cols = set()
        max_r = 0
        max_c = 0
        
        has_row_height_action = False
        has_col_width_action = False
        has_font_red_action = False
        has_bg_color_action = False
        formula_count = 0
        subtotal_rows = set()
        
        # 是否包含填报
        is_write_report = len(root.findall('.//SubWrite')) > 0 or len(root.findall('.//DMLConfig')) > 0
        
        # 是否包含图表
        has_chart = len(root.findall('.//Chart')) > 0 or len(root.findall('.//ChartCollection')) > 0
        
        for cell in root.findall('.//CellElementList/C'):
            r = int(cell.get('r', 0))
            c = int(cell.get('c', 0))
            max_r = max(max_r, r)
            max_c = max(max_c, c)
            
            exp = cell.find('Expand')
            if exp is not None:
                d = exp.get('dir')
                if d == '0':
                    expand_down_rows.add(r)
                elif d == '1':
                    expand_right_cols.add(c)
            
            val_node = cell.find('O')
            if val_node is not None:
                t = val_node.get('t', '')
                txt = ''.join(val_node.itertext()).strip()
                if t == 'Formula':
                    formula_count += 1
                    if any(kw in txt.upper() for kw in ['SUM(', 'AVERAGE(', 'COUNT(']):
                        subtotal_rows.add(r)
                elif '小计' in txt or '合计' in txt or '汇总' in txt:
                    subtotal_rows.add(r)
            
            # 高亮属性
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
                        
        has_show_param = any('show' in p.lower() or 'type' in p.lower() or 'first_show' in p.lower() for p in params)
        
        # 聚类判定逻辑
        archetype = 'STANDARD_GRID'
        archetype_name = '标准多维列表报表'
        desc = ''
        
        if is_write_report or '填报' in fname:
            archetype = 'DATA_ENTRY_FORM'
            archetype_name = '业务数据填报/审批表单'
            desc = '包含可编辑控件、DML更新配置或提交事件'
        elif len(expand_right_cols) > 0:
            archetype = 'CROSS_TAB_MATRIX'
            archetype_name = '横向动态交叉透视报表 (双向扩展)'
            desc = f'包含 {len(expand_right_cols)} 列横向向右展开单元格(dir=1)，年月/地区动态交叉'
        elif len(expand_down_rows) >= 3 and has_row_height_action and (has_show_param or has_col_width_action):
            archetype = 'MULTI_GRANULARITY_DYNAMIC'
            archetype_name = '多粒度动态聚合与折叠报表'
            desc = f'包含 {len(expand_down_rows)} 行独立数据模板行，通过展示方式单选参数进行行高/列宽互锁'
        elif len(subtotal_rows) >= 2 or len(expand_down_rows) >= 2:
            archetype = 'MULTI_LEVEL_SUBTOTAL'
            archetype_name = '层级多级小计与分组汇总报表'
            desc = f'包含 {len(expand_down_rows)} 行分组扩展与 {len(subtotal_rows)} 行小计/总计汇总行'
        elif max_c >= 20:
            archetype = 'WIDE_DETAIL_AUDIT'
            archetype_name = '大宽表明细穿透与审计清单'
            desc = f'单行明细扩展，包含 {max_c+1} 超宽列，支持多维度过滤和静态隐藏排序列'
        else:
            archetype = 'STANDARD_METRIC_CARD'
            archetype_name = '标准业务看板/简明指标表'
            desc = f'列数较少({max_c+1}列)，紧凑的单向纵向列表或卡片'
            
        features.append({
            'file': fname,
            'rel_path': rel_path,
            'archetype': archetype,
            'archetype_name': archetype_name,
            'desc': desc,
            'max_rows': max_r + 1,
            'max_cols': max_c + 1,
            'expand_down_rows': len(expand_down_rows),
            'expand_right_cols': len(expand_right_cols),
            'has_row_height_action': has_row_height_action,
            'has_col_width_action': has_col_width_action,
            'has_font_red': has_font_red_action,
            'has_bg_color': has_bg_color_action,
            'ds_count': ds_count,
            'params_count': len(params),
            'formula_count': formula_count
        })
    except Exception as e:
        pass

counter = Counter(f['archetype'] for f in features)
name_map = {f['archetype']: f['archetype_name'] for f in features}

print("\n" + "="*80)
print(f"【210 套生产 CPT 报表深度结构画像与模板母版提炼】(有效解析: {len(features)} 套)")
print("="*80)

for k, count in counter.most_common():
    pct = count / len(features) * 100
    print(f"★ 母版类型 [{k:26s}] ({name_map.get(k)}): {count:3d} 套 ({pct:5.1f}%)")

print("\n" + "="*80)
print("各母版类别的核心拓扑结构、典型代表报表与设计规范")
print("="*80)

grouped = defaultdict(list)
for f in features:
    grouped[f['archetype']].append(f)

for k, count in counter.most_common():
    items = grouped[k]
    print(f"\n================================================================================")
    print(f"【母版 {counter.most_common().index((k, count))+1}】: {name_map.get(k)} ({k}) - 占 {len(items)} 套 ({len(items)/len(features)*100:.1f}%)")
    print(f"核心特征: {items[0]['desc']}")
    print(f"代表报表清单:")
    for it in items[:5]:
        print(f"   • {it['file']:35s} | 列数:{it['max_cols']:2d} | 公式格:{it['formula_count']:2d} | 参数:{it['params_count']:2d}")
        print(f"     路径: {it['rel_path']}")

out_file = r'D:\data-fill-system\fr_analysis_output\cpt_archetypes_final.json'
with open(out_file, 'w', encoding='utf-8') as f:
    json.dump({
        'archetypes': [
            {
                'key': k,
                'name': name_map.get(k),
                'count': count,
                'percentage': f"{count/len(features)*100:.1f}%",
                'examples': grouped[k][:8]
            }
            for k, count in counter.most_common()
        ]
    }, f, ensure_ascii=False, indent=2)

print(f"\n提炼结果已导出至: {out_file}")
