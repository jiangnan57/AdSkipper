#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「上游快照 + 我自己的修正」合成 App 要拉的那份规则库。

用法:
    python tools/build_rules.py              # 合成本地已有 base.json
    python tools/build_rules.py --refresh    # 先重新拉上游快照再合成
    python tools/build_rules.py --check      # 只校验，不写文件（给 CI/提交前用）

输入:
    rules_server/base.json      上游社区库原样快照（李跳跳哈希数组，可能带尾随逗号）
    rules_server/overrides.json 我自己的规则，对象格式、包名可读

输出:
    rules_server/rules.json     发布产物，App 的「获取最新规则」拉的就是它

为什么分三层而不是「在产物上直接改」:
    在产物上改，上游一更新就冲突，改着改着就不知道自己动过什么了。
    base 只做「原样快照」，overrides 只放我改的，产物每次都是重算出来的，
    任何一条规则从哪来、是不是我改的，永远查得清。
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "rules_server")
BASE = os.path.join(LIB, "base.json")
OVERRIDES = os.path.join(LIB, "overrides.json")
OUTPUT = os.path.join(LIB, "rules.json")

UPSTREAM = "https://testingcf.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/BasicRules.json"

MAX_APPS = 4000
MAX_STR = 160


# --------------------------------------------------------------------------- 清理

def clean_json(raw: str) -> str:
    """去掉字符串外的尾随逗号。

    和 Kotlin 侧 RuleDocument.cleanJson 同一套语义：社区库里 `,]` `,}` 遍地都是，
    标准 json 直接炸，但字符串里的逗号（规则文案）一个都不能动。
    """
    out = []
    in_str = False
    esc = False
    i = 0
    n = len(raw)
    while i < n:
        c = raw[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif c == ",":
            j = i + 1
            while j < n and raw[j].isspace():
                j += 1
            if j >= n or raw[j] not in "]}":
                out.append(c)
        else:
            out.append(c)
        i += 1
    return "".join(out)


def load_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.loads(clean_json(f.read()))


# --------------------------------------------------------------------------- 校验

def norm_list(value, limit=80):
    if not isinstance(value, list):
        return []
    out = []
    for item in value[:limit]:
        if isinstance(item, str):
            s = item.strip()[:MAX_STR]
            if s and s not in out:
                out.append(s)
    return out


def check_app(pkg: str, app: dict, problems: list, warns: list) -> bool:
    if not isinstance(app, dict):
        problems.append(f"{pkg}: 不是对象")
        return False
    if len(pkg) > 200 or "/" in pkg:
        problems.append(f"{pkg}: 包名不合法")
        return False

    splash = app.get("splash")
    popups = app.get("popups")
    if splash is None and popups is None:
        problems.append(f"{pkg}: 既没有 splash 也没有 popups")
        return False

    if splash is not None:
        if not isinstance(splash, dict):
            problems.append(f"{pkg}.splash: 不是对象")
            return False
        kw = norm_list(splash.get("keywords", []))
        ap = norm_list(splash.get("keywords_append", []))
        if not kw and not ap:
            problems.append(f"{pkg}.splash: keywords 和 keywords_append 都为空")
            return False
        for key in ("delay", "duration", "interval"):
            if key in splash and not isinstance(splash[key], (int, float)):
                problems.append(f"{pkg}.splash.{key}: 必须是数字")
                return False

    if popups is not None:
        if not isinstance(popups, list):
            problems.append(f"{pkg}.popups: 必须是数组")
            return False
        if len(popups) > 40:
            problems.append(f"{pkg}.popups: 超过 40 条（引擎只读前 40 条，多的会静默丢弃）")
            return False
        for idx, rule in enumerate(popups):
            if not isinstance(rule, dict):
                problems.append(f"{pkg}.popups[{idx}]: 不是对象")
                return False
            when = norm_list(rule.get("when", []))
            action = rule.get("action")
            actions = norm_list(action if isinstance(action, list) else [action])
            globs = isinstance(action, list) and any(
                isinstance(a, str) and a.upper() == "GLOBAL_ACTION_BACK" for a in action
            )
            if not when:
                problems.append(f"{pkg}.popups[{idx}]: when 为空")
                return False
            if not actions and not globs:
                problems.append(f"{pkg}.popups[{idx}]: action 为空")
                return False
            # 引擎里 when 和 action 是「同一个控件上的两个文本」：
            # 如果两边一模一样，就会无限点自己，是最常见的一类写坏。
            if actions and actions == when:
                warns.append(f"{pkg}.popups[{idx}]: when 与 action 完全相同，可能点到自己")
    return True


def text_only(app: dict) -> bool:
    """这条规则是不是全靠中文文案匹配（App 一改文案就失效）。"""
    splash = app.get("splash") or {}
    words = norm_list(splash.get("keywords", [])) + norm_list(splash.get("keywords_append", []))
    if not words:
        return False
    return all(any(ord(ch) > 127 for ch in w) for w in words)


# --------------------------------------------------------------------------- 合成

def fetch_base() -> None:
    print(f"拉取上游快照: {UPSTREAM}")
    req = urllib.request.Request(UPSTREAM, headers={"User-Agent": "miaotiao-rules-build"})
    with urllib.request.urlopen(req, timeout=40) as resp:
        raw = resp.read().decode("utf-8")
    json.loads(clean_json(raw))  # 拉下来先验一遍，坏数据别落盘
    with open(BASE, "w", encoding="utf-8", newline="\n") as f:
        f.write(raw)
    print(f"  -> {os.path.relpath(BASE, ROOT)} ({len(raw)} 字符)")


def build(refresh: bool, check_only: bool) -> int:
    if refresh:
        fetch_base()
    if not os.path.exists(BASE):
        print("缺少 rules_server/base.json，先跑一次 --refresh", file=sys.stderr)
        return 2
    if not os.path.exists(OVERRIDES):
        print("缺少 rules_server/overrides.json", file=sys.stderr)
        return 2

    base = load_json(BASE)
    if not isinstance(base, list):
        print("base.json 不是李跳跳数组格式，拒绝合成", file=sys.stderr)
        return 2

    ov = load_json(OVERRIDES)
    apps = ov.get("apps") or {}
    disabled = set(norm_list(ov.get("_disabled", []), limit=10_000))
    defaults = ov.get("defaults") or {}

    problems: list = []
    warns: list = []

    for pkg, app in apps.items():
        if pkg.startswith("_"):
            continue
        if pkg in disabled:
            continue
        check_app(pkg, app, problems, warns)

    if len(apps) > MAX_APPS:
        problems.append(f"应用数 {len(apps)} 超过上限 {MAX_APPS}")

    if problems:
        print(f"校验不通过（{len(problems)} 处），没有写文件：", file=sys.stderr)
        for p in problems[:30]:
            print("  ✗ " + p, file=sys.stderr)
        return 1

    # 版本号：内容变了才 +1，没变就保持——否则每次构建都会说「有新版本」，
    # 用户点了「获取最新规则」却什么都没变，会以为功能坏了。
    prev_version = 1
    prev_body = None
    if os.path.exists(OUTPUT):
        try:
            prev = load_json(OUTPUT)
            prev_version = int(prev.get("version", 1))
            prev_body = prev
        except Exception:
            pass

    payload = {
        "version": prev_version,
        "updatedAt": dt.date.today().isoformat(),
        "name": ov.get("_name", "秒跳规则库"),
        "home": ov.get("_home", ""),
        "defaults": defaults,
        "apps": {k: v for k, v in apps.items() if not k.startswith("_") and k not in disabled},
        "legacy": [e for e in base if not _legacy_disabled(e, disabled)],
    }

    changed = True
    if prev_body is not None:
        a = json.dumps(payload, sort_keys=True, ensure_ascii=False)
        b = json.dumps(
            {**prev_body, "version": payload["version"], "updatedAt": payload["updatedAt"]},
            sort_keys=True,
            ensure_ascii=False,
        )
        changed = a != b
    if changed and prev_body is not None:
        payload["version"] = prev_version + 1

    body = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if not check_only:
        with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
            f.write(body)

    # ---------------------------------------------------------------- 报告
    own = payload["apps"]
    legacy_n = len(payload["legacy"])
    splash = sum(1 for a in own.values() if a.get("splash"))
    popup_apps = sum(1 for a in own.values() if a.get("popups"))
    popup_rules = sum(len(a.get("popups") or []) for a in own.values())
    textual = sum(1 for a in own.values() if text_only(a))
    # App 里的「共 N 条」= apps + legacy，和 Kotlin 侧 RuleDocument.stats 同一套口径。
    # 两个数字对不上，用户就会以为库缩水了。
    app_view = len(own) + legacy_n

    print("")
    print(f"  版本        v{payload['version']}" + ("" if changed else "（内容未变）"))
    print(f"  自己维护    {len(own)} 个应用 / 开屏 {splash} / 弹窗 {popup_apps}（共 {popup_rules} 条）")
    print(f"  社区快照    {legacy_n} 条")
    print(f"  App 会显示  {app_view} 条规则")
    print(f"  文件大小    {len(body.encode('utf-8')) / 1024:.1f} KB")
    if textual:
        print(f"  纯文案匹配  {textual} 个应用 —— App 改文案就会失效，属正常损耗")
    if warns:
        print("")
        for w in warns[:20]:
            print("  ! " + w)
    if changed and not check_only:
        print("")
        print(f"  已写入 {os.path.relpath(OUTPUT, ROOT)}，记得提交并 push，App 才拉得到")
    elif not changed:
        print("")
        print("  没有变化，未改动文件")
    return 0


def _legacy_disabled(entry, disabled: set) -> bool:
    if not isinstance(entry, dict):
        return False
    return any(str(k) in disabled for k in entry.keys())


def main() -> int:
    ap = argparse.ArgumentParser(description="合成规则库")
    ap.add_argument("--refresh", action="store_true", help="先重新拉上游快照")
    ap.add_argument("--check", action="store_true", help="只校验不写文件")
    args = ap.parse_args()
    return build(args.refresh, args.check)


if __name__ == "__main__":
    sys.exit(main())
