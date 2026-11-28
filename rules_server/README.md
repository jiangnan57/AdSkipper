# 秒跳规则库

App 里「规则管理 → 获取最新规则」拉的就是这个目录下的 `rules.json`。

```
rules_server/
  base.json       上游社区库原样快照，不手改
  overrides.json  我自己维护的规则（包名可读），只改这个
  rules.json      产物，构建脚本生成，不要手改
```

## 改一条规则

```bash
# 1. 改 overrides.json 里的 apps
# 2. 合成
python tools/build_rules.py
# 3. 提交 + 推送（App 才拉得到）
git add rules_server && git commit -m "规则库：xxx" && git push
```

`build_rules.py` 会做这些事：

- 校验每条规则（`when`/`action` 不能为空、不能 when 和 action 完全相同、
  开屏关键词不能为空、弹窗不超过 40 条……），不通过就不写文件
- 内容变了才把 `version` +1；没变就保持原样，避免「点了更新却什么都没变」
- 打印「自己维护 N 条 / 社区快照 N 条 / App 会显示 N 条」

想让上游规则保持最新：

```bash
python tools/build_rules.py --refresh
```

## 规则怎么写

```json
{
  "apps": {
    "com.example.app": {
      "name": "示例应用",
      "splash": {
        "keywords": ["跳过", "关闭广告"],
        "keywords_append": ["tv_skip", "skip_btn"]
      },
      "popups": [
        { "when": ["检测到更新"], "action": ["以后再", "取消"], "cooldown": 8000 }
      ]
    }
  }
}
```

- `keywords` 覆盖默认开屏关键词，`keywords_append` 是追加
- 弹窗的 `when` 是**出现什么字样**，`action` 是**点哪个**，按顺序试
- 文字默认模糊匹配：`+开头` / `-结尾` / `=全等`，`&` 连接多个条件
- `cooldown` 默认 8000 毫秒，范围 1500–60000

`when` 和 `action` 是同一个界面上的两段文字，写反了就会点到自己——
构建脚本会把这种情况单独标出来。

## 屏蔽某条社区规则

不想删 `base.json`（下次 `--refresh` 又回来），
把它的标识填进 `overrides.json` 的 `_disabled`：包名，或者 `base.json` 里的 hashCode 字符串。

```json
"_disabled": ["-1835777899", "com.example.noisy"]
```

## 把别人学会的规则收进来

用户不用手写规则：在「规则管理 → 已学会」里点一条「复制」，拿到的是

```
com.taobao.taobao | 淘宝 | 会员特惠 | 以后再逛
       包名        应用名   什么时候      点哪里
```

四段竖线分隔，直接对应 `apps[包名].popups[]` 的一项：

```json
"popups": [{ "when": ["会员特惠"], "action": ["以后再逛"] }]
```

粘进问卷、或者直接发过来，照着抄进 `overrides.json` 就行。
`when` 必须是那个弹窗**独有的**文字（标题最合适）；`action` 是按钮上的原文。

## 从问卷收规则（每次收一批）

问卷：<https://wj.qq.com/s2/28867380/8kz9/>（标题「新规则入库」）

用户在里面贴的是 App 里复制出来的那一行，一条一行，所以整批可以机器处理：

```bash
# 1. 腾讯问卷后台 → 数据 → 导出（xlsx 或 csv 都行，第一个工作表）
# 2. 先空跑看一眼，不写文件
python tools/from_survey.py ~/Downloads/新规则入库.xlsx --dry-run

# 3. 确认没问题再落盘（冲突项一律跳过、不逐条问）
python tools/from_survey.py ~/Downloads/新规则入库.xlsx --yes

# 4. 合成 + 发版
python tools/build_rules.py && git commit -am "规则库：收 N 条问卷" && git push
```

推完在 App「规则管理 → 获取最新规则」，用户那边一拉就生效。

脚本会把整张表扫一遍找这种行，不管它落在第几列：

```
com.taobao.taobao | 淘宝 | 会员特惠 | 以后再逛     ← 四段
com.xingin.xhs | 打开通知 | 以后再说              ← 三段也行
```

**自动规整**：全角 `｜`、两端空白、`「」《》“”` 引号。

**直接拒收**（打印原因，不猜）：

- 包名不像包名（`com.foo.bar` 这种才算）
- `when` 或 `action` 空着
- 两边一模一样（写了会点到自己）
- 文案里有 `&`（引擎当「且」，没有转义）、开头是 `+ - =`（引擎当修饰符）
- 文案超过 40 字、单个应用超过 40 条

**不猜的**：同一个 `when` 配了两个不同 `action`——库里已经点「同意」，新来的点「我同意」，
两条都留着只会先命中先写的。脚本把它列成冲突，不动手，加 `--yes` 也只是跳过，
要换就手动改 `overrides.json` 再跑 `build_rules.py`。

## 地址

```
https://cdn.jsdelivr.net/gh/jiangnan57/AdSkipper@main/rules_server/rules.json
```

jsDelivr 对 `@main` 有约 12 小时缓存，刚 push 完可能还是旧版。
App 里另外配了 `fastly.jsdelivr`、GitHub Pages、`raw.githubusercontent` 三路兜底，
手动「获取最新规则」时会带 `Cache-Control: no-cache`。

## 注意

规则是**可执行的界面指令**——它会真的在别的 App 上点下去。
所以只接受 https，且只往 `apps` 里放自己确认过的文案；
不确定的规则宁可先写进「学弹窗」在真机上验一遍，再搬进来。
