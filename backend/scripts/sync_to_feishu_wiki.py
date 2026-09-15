#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
飞书知识库与云文档自动化同步脚本 (Python 测试版)
功能：从 PostgreSQL 或示例数据中读取报表资产，在飞书知识库自动创建左侧多级目录树与右侧美化版云文档。

使用方法:
    python sync_to_feishu_wiki.py --app_id cli_xxx --app_secret xxx --space_id 738xxxx
"""

import sys
import json
import argparse
import requests

FEISHU_HOST = "https://open.feishu.cn/open-apis"

def get_tenant_access_token(app_id, app_secret):
    url = f"{FEISHU_HOST}/auth/v3/tenant_access_token/internal"
    resp = requests.post(url, json={"app_id": app_id, "app_secret": app_secret})
    data = resp.json()
    if data.get("code") == 0:
        token = data["tenant_access_token"]
        print(f"[OK] 成功获取飞书 Tenant Token: {token[:12]}...")
        return token
    else:
        print(f"[ERROR] 获取 Token 失败: {data.get('msg')}")
        sys.exit(1)

def resolve_space_id_and_parent(space_id_or_url, token):
    raw = space_id_or_url.strip()
    if "/wiki/" in raw:
        raw = raw.split("/wiki/")[1].split("?")[0].split("/")[0]
    
    if raw.isdigit():
        return raw, None
    
    url = f"{FEISHU_HOST}/wiki/v2/spaces/get_node?token={raw}"
    headers = {"Authorization": f"Bearer {token}"}
    try:
        resp = requests.get(url, headers=headers)
        data = resp.json()
        if data.get("code") == 0:
            node = data["data"]["node"]
            print(f"[OK] 成功从链接/Token 解析: 空间 Space ID = {node['space_id']}, 挂载父节点 = {node['node_token']}")
            return node["space_id"], node["node_token"]
    except Exception as e:
        pass
    return raw, None

def get_existing_nodes(space_id, parent_node_token, token):
    url = f"{FEISHU_HOST}/wiki/v2/spaces/{space_id}/nodes"
    if parent_node_token:
        url += f"?parent_node_token={parent_node_token}"
    headers = {"Authorization": f"Bearer {token}"}
    try:
        resp = requests.get(url, headers=headers)
        data = resp.json()
        if data.get("code") == 0:
            return data.get("data", {}).get("items", [])
    except Exception:
        pass
    return []

def create_wiki_node(space_id, parent_node_token, title, token):
    # 1. 查找是否已存在同名节点
    existing_items = get_existing_nodes(space_id, parent_node_token, token)
    for item in existing_items:
        if item.get("title") == title:
            print(f"  [~] 发现知识库已存在节点 [{title}]，直接原地覆盖内容 (Token: {item['node_token']})")
            return item

    # 2. 不存在则新建
    url = f"{FEISHU_HOST}/wiki/v2/spaces/{space_id}/nodes"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    body = {
        "obj_type": "docx",
        "node_type": "origin",
        "title": title
    }
    if parent_node_token:
        body["parent_node_token"] = parent_node_token

    resp = requests.post(url, headers=headers, json=body)
    data = resp.json()
    if data.get("code") == 0:
        node = data["data"]["node"]
        print(f"  [+] 新建知识库节点成功: {title} (Token: {node['node_token']})")
        return node
    else:
        print(f"  [!] 创建节点提示 [{title}]: {data.get('msg')}")
        return None

def build_docx_blocks(report_info):
    title = report_info.get("report_name", "报表")
    status = report_info.get("lifecycle_status", "正式使用")
    desc = report_info.get("report_desc")
    report_url = report_info.get("report_url", "https://bi.meiying.homes/webroot/decision/")

    blocks = []

    # 1. 顶部大标题
    blocks.append({
        "block_type": 3, # Heading 1
        "heading1": {
            "elements": [{"type": "text_run", "text_run": {"content": f"{title} [{status}]", "text_element_style": {"bold": True}}}]
        }
    })

    # 2. 顶部 Banner 浅绿提示卡片 (Callout Block)
    banner_text = f"{desc}\n\n🔗 报表访问链接: {report_url}" if desc else f"🔗 报表访问链接: {report_url}"
    blocks.append({
        "block_type": 19, # Callout
        "callout": {
            "background_color": 2, # 浅绿
            "emoji_id": "chart_increasing",
            "children": [{
                "block_type": 2,
                "text": {"elements": [{"type": "text_run", "text_run": {"content": banner_text}}]}
            }]
        }
    })

    # 3. 模块一：基本信息
    blocks.append({
        "block_type": 4, # Heading 2
        "heading2": {"elements": [{"type": "text_run", "text_run": {"content": "📄 基本信息", "text_element_style": {"bold": True}}}]}
    })
    
    rows = [
        ("报表名称", title, "更新频率", report_info.get("update_frequency", "每日")),
        ("报表ID", report_info.get("report_id", "-"), "数据更新时间", report_info.get("data_update_time", "每日 08:30")),
        ("所属目录", report_info.get("full_path", "默认目录"), "所属主题", report_info.get("biz_domain", "经营管理")),
        ("开发人员", report_info.get("developer", "许旭庆"), "报表状态", status)
    ]
    blocks.append(create_table_block(rows))

    # 4. 模块二：维护信息
    blocks.append({
        "block_type": 4,
        "heading2": {"elements": [{"type": "text_run", "text_run": {"content": "👤 维护信息", "text_element_style": {"bold": True}}}]}
    })
    rows2 = [
        ("责任人", report_info.get("owner", "张三"), "所属部门", report_info.get("dept_name", "财务与经营分析部")),
        ("是否机密", report_info.get("is_confidential", "否"), "报表状态", status)
    ]
    blocks.append(create_table_block(rows2))

    # 5. 模块三：业务属性
    blocks.append({
        "block_type": 4,
        "heading2": {"elements": [{"type": "text_run", "text_run": {"content": "📊 业务属性", "text_element_style": {"bold": True}}}]}
    })
    rows3 = [
        ("业务用途", desc, "关联指标", report_info.get("metrics", "销售额、毛利、库存周转")),
        ("数据来源", report_info.get("data_source", "ERP系统、平台数据、海外仓WMS"), "备注", report_info.get("remark", "-"))
    ]
    blocks.append(create_table_block(rows3))

    # 6. 模块四：使用说明提示卡片
    blocks.append({
        "block_type": 19,
        "callout": {
            "background_color": 1, # 浅蓝
            "emoji_id": "information_source",
            "children": [{
                "block_type": 2,
                "text": {"elements": [{"type": "text_run", "text_run": {"content": "【适用对象】：BU负责人、运营经理、财务人员、数据分析师\n【使用建议】：支持按时间、区域、产品等多维度下钻分析，异常指标可直接导出明细归因。"}}]}
            }]
        }
    })

    return blocks

def create_table_block(rows):
    row_count = len(rows)
    col_count = 4
    children = []

    for r in rows:
        for idx in range(col_count):
            val = r[idx] if idx < len(r) else "-"
            is_label = (idx == 0 or idx == 2)
            children.append({
                "block_type": 32, # Cell
                "children": [{
                    "block_type": 2,
                    "text": {
                        "elements": [{
                            "type": "text_run",
                            "text_run": {
                                "content": val,
                                "text_element_style": {"bold": is_label}
                            }
                        }]
                    }
                }]
            })

    return {
        "block_type": 31, # Table
        "table": {
            "row_size": row_count,
            "column_size": col_count,
            "property": {
                "row_size": row_count,
                "column_size": col_count,
                "column_width": [180, 540] if col_count == 2 else [140, 220, 140, 220]
            },
            "children": children
        }
    }

def post_block_children(document_id, block_id, body, token):
    url = f"{FEISHU_HOST}/docx/v1/documents/{document_id}/blocks/{block_id}/children"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    resp = requests.post(url, headers=headers, json=body)
    data = resp.json()
    if data.get("code") == 0:
        return data
    else:
        msg = data.get("msg", "未知错误")
        code = data.get("code", -1)
        raise RuntimeError(f"写入飞书文档 Block 失败 (code {code}): {msg}")

def write_docx_blocks(document_id, blocks, token):
    if not blocks or not document_id:
        return

    for block in blocks:
        block_type = block.get("block_type")
        if block_type == 19: # Callout
            callout = block.get("callout", {})
            children = callout.pop("children", None)
            resp = post_block_children(document_id, document_id, {"children": [block], "index": -1}, token)
            first_child = resp.get("data", {}).get("children", [{}])[0]
            callout_block_id = first_child.get("block_id")
            if callout_block_id and children:
                post_block_children(document_id, callout_block_id, {"children": children, "index": -1}, token)
        elif block_type == 31: # Table
            table = block.get("table", {})
            cell_content_blocks = table.pop("children", None)
            resp = post_block_children(document_id, document_id, {"children": [block], "index": -1}, token)
            first_child = resp.get("data", {}).get("children", [{}])[0]
            tbl = first_child.get("table", {})
            cell_block_ids = tbl.get("cells") or tbl.get("children") or []
            if cell_block_ids and cell_content_blocks:
                size = min(len(cell_block_ids), len(cell_content_blocks))
                for i in range(size):
                    cell_id = cell_block_ids[i]
                    cell_content = cell_content_blocks[i]
                    if cell_content and "children" in cell_content:
                        post_block_children(document_id, cell_id, {"children": cell_content["children"], "index": -1}, token)
        else:
            post_block_children(document_id, document_id, {"children": [block], "index": -1}, token)
    print(f"  [OK] 成功向文档 [{document_id}] 写入多级 Blocks 内容排版！")

def main():
    parser = argparse.ArgumentParser(description="飞书知识库自动化同步测试脚本")
    parser.add_argument("--app_id", required=True, help="飞书自建应用 App ID (cli_xxx)")
    parser.add_argument("--app_secret", required=True, help="飞书自建应用 App Secret")
    parser.add_argument("--space_id", required=True, help="飞书知识库空间 Space ID (纯数字或UUID)")
    args = parser.parse_args()

    token = get_tenant_access_token(args.app_id, args.app_secret)
    real_space_id, root_parent_token = resolve_space_id_and_parent(args.space_id, token)

    # 模拟示例报表数据
    sample_reports = [
        {
            "report_id": "REP-202508-0001",
            "report_name": "SKU维度分析",
            "lifecycle_status": "正式使用",
            "domain_l1": "经营管理",
            "domain_l2": "经营分析",
            "full_path": "经营管理 / 经营分析 / SKU维度分析",
            "folder_chain": ["00.经营管理", "03.经营分析"],
            "owner": "张三",
            "dept_name": "运营中心",
            "developer": "李四",
            "update_frequency": "每日",
            "data_update_time": "10:00",
            "is_confidential": "否",
            "sensitive_level": "中",
            "report_desc": "从SKU维度查看销售、利润、库存等核心经营指标，分析商品经营表现，帮助业务人员识别重点商品、优化经营策略。",
            "report_url": "https://bi.meiying.homes/webroot/decision/#?activeTab=REP-202508-0001"
        }
    ]

    print(f"\n🚀 开始在飞书知识库 [Space: {real_space_id}] (挂载根: {root_parent_token}) 中自动同步创建...")

    folder_cache = {}

    for rep in sample_reports:
        parent_token = root_parent_token
        current_path = ""
        for folder in rep["folder_chain"]:
            current_path = f"{current_path}/{folder}" if current_path else folder
            if current_path not in folder_cache:
                node = create_wiki_node(real_space_id, parent_token, folder, token)
                if node:
                    folder_cache[current_path] = node["node_token"]
            parent_token = folder_cache.get(current_path)

        # 创建云文档
        doc_node = create_wiki_node(real_space_id, parent_token, rep["report_name"], token)
        if doc_node:
            blocks = build_docx_blocks(rep)
            write_docx_blocks(doc_node["obj_token"], blocks, token)

    print("\n🎉 同步测试完成！请打开飞书知识库查看生成的树结构与云文档。")

if __name__ == "__main__":
    main()
