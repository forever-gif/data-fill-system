import argparse
import requests

FEISHU_HOST = "https://open.feishu.cn/open-apis"

def get_tenant_access_token(app_id, app_secret):
    url = f"{FEISHU_HOST}/auth/v3/tenant_access_token/internal"
    resp = requests.post(url, json={"app_id": app_id, "app_secret": app_secret})
    data = resp.json()
    if data.get("code") == 0:
        return data.get("tenant_access_token")
    raise Exception(f"获取 Token 失败: {data.get('msg')}")

def list_root_nodes(space_id, token):
    url = f"{FEISHU_HOST}/wiki/v2/spaces/{space_id}/nodes"
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(url, headers=headers)
    data = resp.json()
    if data.get("code") == 0:
        return data.get("data", {}).get("items", [])
    print(f"查询空间节点失败: {data.get('msg')}")
    return []

def delete_node(space_id, node_token, token):
    url = f"{FEISHU_HOST}/wiki/v2/spaces/{space_id}/nodes/{node_token}"
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.delete(url, headers=headers)
    data = resp.json()
    if data.get("code") == 0:
        print(f"  [+] 成功删除知识库节点: {node_token}")
        return True
    else:
        print(f"  [!] 删除节点 {node_token} 失败: {data.get('msg')}")
        return False

def main():
    parser = argparse.ArgumentParser(description="删除飞书知识库节点工具")
    parser.add_argument("--app_id", required=True, help="飞书自建应用 App ID")
    parser.add_argument("--app_secret", required=True, help="飞书自建应用 App Secret")
    parser.add_argument("--space_id", required=True, help="飞书知识库 Space ID")
    parser.add_argument("--node_token", help="指定要删除的 node_token (留空则删除根下所有节点)")
    args = parser.parse_args()

    token = get_tenant_access_token(args.app_id, args.app_secret)

    if args.node_token:
        delete_node(args.space_id, args.node_token, token)
    else:
        nodes = list_root_nodes(args.space_id, token)
        print(f"找到空间 [{args.space_id}] 下的 {len(nodes)} 个根节点:")
        for n in nodes:
            print(f"  - 正在删除节点: {n.get('title')} (Token: {n.get('node_token')})")
            delete_node(args.space_id, n.get("node_token"), token)

if __name__ == "__main__":
    main()
