import argparse
import requests

FEISHU_HOST = "https://open.feishu.cn/open-apis"

def get_tenant_access_token(app_id, app_secret):
    url = f"{FEISHU_HOST}/auth/v3/tenant_access_token/internal"
    resp = requests.post(url, json={"app_id": app_id, "app_secret": app_secret})
    data = resp.json()
    if data.get("code") == 0:
        return data.get("tenant_access_token")
    print(f"获取 Token 失败: {data}")
    return None

def check_spaces(token):
    url = f"{FEISHU_HOST}/wiki/v2/spaces"
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(url, headers=headers)
    print(f"1. 查询应用有权限的知识库空间列表 (GET /wiki/v2/spaces):")
    print(f"   HTTP Status: {resp.status_code}")
    print(f"   Response Body: {resp.text}")

def check_space_nodes(space_id, token):
    url = f"{FEISHU_HOST}/wiki/v2/spaces/{space_id}/nodes"
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(url, headers=headers)
    print(f"\n2. 测试读取知识库 [{space_id}] 的根节点列表 (GET /wiki/v2/spaces/{space_id}/nodes):")
    print(f"   HTTP Status: {resp.status_code}")
    print(f"   Response Body: {resp.text}")

def main():
    parser = argparse.ArgumentParser(description="排查飞书知识库空间权限")
    parser.add_argument("--app_id", required=True)
    parser.add_argument("--app_secret", required=True)
    parser.add_argument("--space_id", required=True)
    args = parser.parse_args()

    token = get_tenant_access_token(args.app_id, args.app_secret)
    if token:
        check_spaces(token)
        check_space_nodes(args.space_id, token)

if __name__ == "__main__":
    main()
