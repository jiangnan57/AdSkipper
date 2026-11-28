#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把问卷导出的表格变成 overrides.json 里的规则。

用法:
    python tools/from_survey.py 导出.xlsx           # 写进 overrides.json
    python tools/from_survey.py 导出.csv --dry-run  # 只看识别到什么，不动文件
    python tools/from_survey.py 导出.xlsx --yes     # 冲突的也按「保留原规则」跳过，不再逐条问

流程（三步）:
    1. 腾讯问卷后台 → 数据 → 导出（Excel / CSV）
    2. python tools/from_survey.py 导出文件
    3. python tools/build_rules.py && git commit -am "..." && git push

认什么格式:
    App 里「复制」出来的那一行，一条一行：

        com.taobao.taobao | 淘宝 | 会员特惠 | 以后再逛

    三段也行（省掉应用名）:  com.taobao.taobao | 会员特惠 | 以后再逛
    整张表会被扫一遍——不管那一行落在第几列、哪一行，只要格式对就收。
    全角的 ｜、「」引号会自动规整。

为什么不直接让问卷按 JSON 收:
    填问卷的是普通用户，多打一个花括号就会得到一条坏规则；而坏规则的后果是
    「在这个 App 上乱点」。宁可这边多洗一遍，也不让原始文本直接进库。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import zipfile
from xml.etree import ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "rules_server")
BASE = os.path.join(LIB, "base.json")
OVERRIDES = os.path.join(LIB, "overrides.json")

# 引擎侧的实际限制：单 App 弹窗规则 40 条，字段太长的文案不可能出现在按钮上
MAX_POPUPS_PER_APP = 40
MAX_TEXT = 40

# 包名：至少两段，字母开头，只用字母数字下划线
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
SEP_RE = re.compile(r"\s*[|｜]\s*")


# --------------------------------------------------------------------------- 读表格

def read_rows(path: str) -> list[list[str]]:
    """按后缀读成二维文本；不认识的后缀按 CSV 试一遍。"""
    lower = path.lower()
    if lower.endswith((".xlsx", ".xlsm")):
        return read_xlsx(path)
    return read_csv(path)


def read_csv(path: str) -> list[list[str]]:
    # 腾讯问卷导出的 CSV 可能是 UTF-8 BOM，也可能被 Excel 存成 GBK
    for enc in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            with open(path, newline="", encoding=enc) as f:
                return [row for row in csv.reader(f)]
        except UnicodeDecodeError:
            continue
    raise SystemExit(f"读不出这个 CSV 的编码：{path}")


def read_xlsx(path: str) -> list[list[str]]:
    """
    只认第一个工作表。

    不装 openpyxl：构建机离线，多一个依赖就多一种装不上的可能。
    xlsx 本质是 zip + 几个 XML，导出表又是纯文本格子，够用了。
    """
    ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    if not zipfile.is_zipfile(path):
        raise SystemExit(f"不是 xlsx（文件坏了或其实不是 Excel 文件）：{path}")

    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        shared: list[str] = []
        if "xl/sharedStrings.xml" in names:
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in root.findall(f"{ns}si"):
                shared.append("".join(t.text or "" for t in si.iter(f"{ns}t")))

        sheets = sorted(n for n in names if n.startswith("xl/worksheets/sheet"))
        if not sheets:
            raise SystemExit("xlsx 里没有工作表")
        root = ET.fromstring(z.read(sheets[0]))

        rows: list[list[str]] = []
        for row in root.iter(f"{ns}row"):
            cells: list[str] = []
            for c in row.findall(f"{ns}c"):
                kind = c.get("t")
                v = c.find(f"{ns}v")
                inline = c.find(f"{ns}is")
                if kind == "s" and v is not None:
                    idx = int(v.text or "0")
                    cells.append(shared[idx] if 0 <= idx < len(shared) else "")
                elif kind == "inlineStr" and inline is not None:
                    cells.append("".join(t.text or "" for t in inline.iter(f"{ns}t")))
                elif v is not None:
                    cells.append(v.text or "")
                else:
                    cells.append("")
            rows.append(cells)
        return rows


# --------------------------------------------------------------------------- 解析一行

class Rule:
    __slots__ = ("pkg", "app", "when", "action", "raw")

    def __init__(self, pkg: str, app: str, when: str, action: str, raw: str):
        self.pkg = pkg
        self.app = app
        self.when = when
        self.action = action
        self.raw = raw

    def key(self) -> tuple[str, str]:
        return (self.when, self.action)


def tidy(s: str) -> str:
    # 用户从聊天窗口复制，常带「」《》和两端空白
    return s.strip().strip("「」『』《》“”\"'").strip()


def parse_line(line: str) -> tuple[Rule | None, str]:
    """返回 (规则, 错在哪)。两者必有其一。"""
    raw = line.strip()
    if "|" not in raw and "｜" not in raw:
        return None, "没有竖线"

    parts = [tidy(p) for p in SEP_RE.split(raw)]
    parts = [p for p in parts if p]
    if len(parts) == 4:
        pkg, app, when, action = parts
    elif len(parts) == 3:
        pkg, app, when, action = parts[0], "", parts[1], parts[2]
    elif len(parts) > 4:
        return None, f"竖线太多（{len(parts)} 段），文案里可能真带竖线"
    else:
        return None, f"只有 {len(parts)} 段，至少要「包名 | 什么时候 | 点哪里」"

    if not PKG_RE.match(pkg):
        return None, f"包名不像包名：{pkg}"
    if not when:
        return None, "when 空着"
    if not action:
        return None, "action 空着"
    if when == action:
        return None, "when 和 action 一模一样（会点到自己）"
    if "&" in when or "&" in action:
        return None, "文案里有 &（引擎当「且」解析，没有转义）"
    if when[0] in "+-=" or action[0] in "+-=":
        return None, "开头是 + - =（引擎当修饰符）"
    if len(when) > MAX_TEXT or len(action) > MAX_TEXT:
        return None, f"文案超过 {MAX_TEXT} 字，不像按钮"
    return Rule(pkg, app, when, action, raw), ""


def collect(rows: list[list[str]]) -> tuple[list[Rule], list[tuple[str, str]]]:
    """整张表扫一遍。返回 (认出的规则, [(原始文本, 原因)])。"""
    good: list[Rule] = []
    bad: list[tuple[str, str]] = []
    seen: set[tuple[str, str, str]] = set()

    for row in rows:
        for cell in row:
            if not cell or ("|" not in cell and "｜" not in cell):
                continue
            # 一个格子里可能贴了好几行
            for line in cell.splitlines():
                if not line.strip():
                    continue
                rule, why = parse_line(line)
                if rule is None:
                    bad.append((line.strip(), why))
                    continue
                k = (rule.pkg, rule.when, rule.action)
                if k in seen:
                    continue
                seen.add(k)
                good.append(rule)
    return good, bad


# --------------------------------------------------------------------------- 入库

def load_overrides() -> dict:
    if not os.path.exists(OVERRIDES):
        return {"_name": "秒跳规则库", "apps": {}}
    with open(OVERRIDES, encoding="utf-8") as f:
        return json.load(f)


def legacy_hashes() -> set[str]:
    """
    base.json 是李跳跳哈希数组，包里读不出包名，但键就是「包名.hashCode()」。

    java 的 String.hashCode 是 31 进制累加，自己算一遍就能知道社区库里有没有这个包——
    有的话，自己写的这条会盖掉社区那条，值得提醒一句。
    """
    if not os.path.exists(BASE):
        return set()
    try:
        with open(BASE, encoding="utf-8") as f:
            raw = f.read()
        raw = re.sub(r",\s*([\]}])", r"\1", raw)
        doc = json.loads(raw)
    except Exception:
        return set()

    out: set[str] = set()
    if isinstance(doc, list):
        for entry in doc:
            if isinstance(entry, dict):
                out.update(str(k) for k in entry.keys())
    return out


def jhash(s: str) -> str:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h >= 2 ** 31:
        h -= 2 ** 32
    return str(h)


def merge(doc: dict, rules: list[Rule], base_hashes: set[str], ask: bool):
    apps = doc.setdefault("apps", {})
    added: list[Rule] = []
    dup: list[Rule] = []
    conflict: list[tuple[Rule, str]] = []
    capped: list[Rule] = []
    overrides_base: list[Rule] = []

    for r in rules:
        entry = apps.setdefault(r.pkg, {"name": r.app or r.pkg, "popups": []})
        if r.app and not entry.get("name"):
            entry["name"] = r.app
        popups = entry.setdefault("popups", [])

        same = [p for p in popups if r.when in (p.get("when") or [])]
        if any(r.action in (p.get("action") or []) for p in same):
            dup.append(r)
            continue
        if same:
            # 同一个 when、不同 action：两条都留着会先点中先写的那条，
            # 到底点哪个得人来定，脚本不猜。
            conflict.append((r, same[0].get("action", [""])[0] if same[0].get("action") else ""))
            if not ask:
                continue
            ans = input(
                f"  {r.pkg} 的「{r.when}」已有 action「{same[0].get('action')}」，"
                f"新来的是「{r.action}」。替换吗？[y/N] "
            ).strip().lower()
            if ans != "y":
                continue

        if len(popups) >= MAX_POPUPS_PER_APP:
            capped.append(r)
            continue

        popups.append({"when": [r.when], "action": [r.action]})
        added.append(r)
        if jhash(r.pkg) in base_hashes:
            overrides_base.append(r)

    return added, dup, conflict, capped, overrides_base


# --------------------------------------------------------------------------- 报告

def report(good, bad, added, dup, conflict, capped, over_base, dry):
    print("")
    print(f"  认出规则    {len(good)} 条")
    if bad:
        print(f"  不认的      {len(bad)} 行")
        for text, why in bad[:8]:
            print(f"      {text[:60]}")
            print(f"        └ {why}")
        if len(bad) > 8:
            print(f"      …… 还有 {len(bad) - 8} 行")

    print("")
    print(f"  新增        {len(added)} 条")
    for r in added[:10]:
        print(f"      {r.pkg}  「{r.when}」→ {r.action}")
    if len(added) > 10:
        print(f"      …… 还有 {len(added) - 10} 条")
    if dup:
        print(f"  已存在      {len(dup)} 条（同样的 when 和 action，跳过）")
    if conflict:
        print(f"  冲突        {len(conflict)} 条（同一个 when、不同 action，没动）")
        for r, old in conflict[:5]:
            print(f"      {r.pkg}  「{r.when}」库里点「{old}」，新来的点「{r.action}」")
    if capped:
        print(f"  超出上限    {len(capped)} 条（单 App 最多 {MAX_POPUPS_PER_APP} 条）")
    if over_base:
        print(f"  覆盖社区层  {len(over_base)} 条（社区库本来也有这个包，自己写的会盖掉它）")

    if dry:
        print("")
        print("  --dry-run：没写文件")
        return
    if added:
        print("")
        print("  下一步：python tools/build_rules.py → commit + push → 手机上点「获取最新规则」")


def main() -> int:
    ap = argparse.ArgumentParser(description="问卷导出表 → overrides.json")
    ap.add_argument("file", help="导出的 .xlsx / .csv")
    ap.add_argument("--dry-run", action="store_true", help="只看识别结果，不写文件")
    ap.add_argument("--yes", action="store_true", help="冲突项一律跳过，不逐条询问")
    args = ap.parse_args()

    if not os.path.exists(args.file):
        raise SystemExit(f"文件不存在：{args.file}")

    rows = read_rows(args.file)
    good, bad = collect(rows)
    if not good and not bad:
        raise SystemExit(
            "整张表里没找到「包名 | when | action」这种行。\n"
            "确认导出的表里有用户粘贴的那一列，且间隔符是竖线。"
        )

    doc = load_overrides()
    hashes = legacy_hashes()
    result = merge(doc, good, hashes, ask=not args.yes)
    report(good, bad, *result, dry=args.dry_run)

    if not args.dry_run and result[0]:
        tmp = OVERRIDES + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp, OVERRIDES)
        print(f"  已写入      {os.path.relpath(OVERRIDES, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
