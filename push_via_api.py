#!/usr/bin/env python3
"""
通过 GitHub Git Data API 推送改动。

为什么不用 git push：这台机器的代理只放行了 api.github.com，
github.com 主机的 CONNECT 隧道被拒（502），git smart-http 走不通。
Git Data API 走的是同一个 api.github.com，所以能用。

流程（官方 Git Data API）：
  1. 取 main 分支当前 commit -> 拿 tree
  2. 每个变更文件 POST /git/blobs 拿 blob sha
  3. POST /git/trees（带 base_tree，只覆盖变更路径）
  4. POST /git/commits
  5. PATCH /git/refs/heads/main

最后逐文件比对远端 tree 的 blob sha 和本地 git ls-tree 的 sha，
确保推上去的内容和本地工作区**逐字节一致**。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "jiangnan57/AdSkipper"
API = f"https://api.github.com/repos/{REPO}"
PROJ = os.path.dirname(os.path.abspath(__file__))
TOKEN = open(os.path.expanduser("~/.workbuddy/github_token")).read().strip()


def api(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "adskip-release")
    if data:
        req.add_header("Content-Type", "application/json")
    ctx = None
    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
        raw = r.read().decode()
    return json.loads(raw) if raw else {}


def sh(*args):
    return subprocess.run(args, cwd=PROJ, capture_output=True, text=True,
                          check=True).stdout


def main():
    # 变更清单：来自本地 git 提交（已 git add -A）
    status = sh("git", "diff", "--name-status", "HEAD").strip()
    changed = []
    for line in status.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        code = parts[0]
        if code.startswith("D"):
            changed.append((parts[1], None))       # 删除
        else:
            changed.append((parts[-1], "M"))
    # 尚未纳入 git 的新文件
    untracked = sh("git", "ls-files", "--others", "--exclude-standard").split()
    for f in untracked:
        changed.append((f, "A"))

    if not changed:
        print("没有需要推送的改动")
        return

    print(f"待推送 {len(changed)} 个文件：")
    for f, op in changed:
        print(f"  {op}  {f}")

    # 现有 mode（可执行文件要保留 100755）
    modes = {}
    for line in sh("git", "ls-tree", "-r", "HEAD").splitlines():
        meta, path = line.split("\t", 1)
        mode, _typ, sha = meta.split()
        modes[path] = (mode, sha)

    # 1) 当前 main
    ref = api("GET", "/git/ref/heads/main")
    base_commit = ref["object"]["sha"]
    base_tree = api("GET", f"/git/commits/{base_commit}")["tree"]["sha"]
    print(f"\n远端 main = {base_commit}")
    print(f"远端 tree = {base_tree}")

    # 2) 创建 blobs
    entries = []
    for path, op in changed:
        abs_path = os.path.join(PROJ, path)
        if op is None:
            entries.append({"path": path, "mode": "100644", "type": "blob",
                            "sha": None})
            continue
        content = open(abs_path, "rb").read()
        blob = api("POST", "/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        mode = modes.get(path, ("100644", None))[0]
        entries.append({"path": path, "mode": mode, "type": "blob",
                        "sha": blob["sha"]})
        print(f"  blob {blob['sha'][:8]}  {path}  ({len(content)} 字节)")

    # 3) 创建 tree
    tree = api("POST", "/git/trees", {
        "base_tree": base_tree,
        "tree": entries,
    })
    print(f"\n新 tree = {tree['sha']}")

    # 4) 创建 commit
    message = sh("git", "log", "-1", "--format=%B", "HEAD").strip()
    commit = api("POST", "/git/commits", {
        "message": message,
        "tree": tree["sha"],
        "parents": [base_commit],
    })
    print(f"新 commit = {commit['sha']}")

    # 5) 更新 ref
    api("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
    print(f"\n✅ 已更新 main -> {commit['sha']}")

    # ---- 校验：远端 tree 与本地 HEAD 逐文件比对 blob sha ----
    remote = api("GET", f"/git/trees/{commit['sha']}?recursive=1")
    remote_map = {e["path"]: e["sha"] for e in remote["tree"] if e["type"] == "blob"}
    local = {}
    for line in sh("git", "ls-tree", "-r", "HEAD").splitlines():
        meta, path = line.split("\t", 1)
        mode, _typ, sha = meta.split()
        local[path] = sha

    diff = [p for p in set(list(remote_map) + list(local))
            if remote_map.get(p) != local.get(p)]
    if diff:
        print(f"\n❌ 校验失败，远端与本地不一致：{diff}")
        sys.exit(1)
    print(f"✅ 校验通过：远端 {len(remote_map)} 个文件的 blob SHA 与本地 HEAD 完全一致")


if __name__ == "__main__":
    main()
