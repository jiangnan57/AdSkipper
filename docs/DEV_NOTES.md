# 历史开发笔记（早期 v2 版本，内容已过时）

> ⚠️ 本文件是项目早期版本的开发记录，仅用于追溯设计取舍与排查过程。
> 文中提到的「系统文本检索快路径」「第 0/2/5/9 轮全树遍历」「250ms 时间预算」「坐标自动学习」等实现，
> 在 v1.1.0 ~ v1.3.1 中已被移除或重写，**请勿据此理解当前代码**。当前行为请以 [README.md](../README.md) 与源码为准。
> 作者本机的工具链路径已替换为占位符。

---

# 秒跳 · Android 跳过开屏广告 + 防跳转（v2）

自用版本。基于无障碍服务（AccessibilityService）的自动点击工具，原理与李跳跳一致：**读屏幕节点 → 匹配规则 → 模拟点击**。

> ✅ 已在 Redmi 24122RKC7C（HyperOS / Android 16）实机编译、安装、跑通。
> 产物：`app/build/outputs/apk/debug/app-debug.apk`（5.5 MB）
> 工具链：Microsoft OpenJDK 17.0.10 + Android SDK 34 + Gradle 8.4（路径见下方「编译」）
> 内存：后台常驻 PSS 约 36 MB（退到后台图形内存归零，见「十一、内存」）

---

## 一、v2 相比第一版改了什么

第一版装到手机后的问题是「不太稳定，有时候慢」。查下来是三个具体原因，逐个修掉了：

| 问题 | 原因 | 修法 |
|---|---|---|
| 有时候完全没反应 | 起 App 时系统会先发一条桌面（launcher）的窗口事件，旧逻辑在这条事件上把监测停了，结果整轮扫描一次都没跑 | 桌面/系统页只当「路过」，不再停表，到时间自己结束 |
| 有时候慢一两秒 | 遍历节点树跑在主线程，一次几百个 IPC，把后续事件全堵住了 | 扫描整体挪到独立线程，主线程只做「标记 + 投递」 |
| 每次扫描都卡 | 每次都要把整棵树拉一遍（淘宝 500+ 节点约 250ms） | 改用系统自带的文本检索走快路径，一次 IPC 拿结果，约 5~10ms |

实机数据（`adb logcat -s AdSkip`）：

```
watch start pkg=com.taobao.taobao delay=200 kw=4
scan 227ms nodes=506 fast=false pkg=com.taobao.taobao      ← 首轮整树遍历
watch end pkg=com.taobao.taobao scans=18 cost=356ms        ← 一轮 18 次扫描共 356ms
```

一轮 4.2 秒里扫 18 次，总 CPU 时间 356ms（其中 227ms 是首轮遍历，剩下 17 次走快路径共约 130ms）。

另外新增了三个能力：**远程规则同步**、**自动学习**、**自检悬浮球**。

---

## 二、能做什么 / 不能做什么

| | 实现情况 |
|---|---|
| 跳过开屏广告 | ✅ 控件 id / 文字 / 坐标 三级匹配，快路径 + 整树兜底 |
| 关闭应用内弹窗（更新提示等） | ✅ 「条件 → 动作」规则 |
| 规则远程同步 | ✅ 填一个 URL，定时拉取，本地缓存，失败自动退回上一份 |
| 自己认按钮（自动学习） | ⚠️ 只对「规则库里没有的 App」生效，有内容页闸门，仍可能认错 |
| 防止摇一摇跳转 | ⚠️ 只能**事后回退**，会有 1 秒左右的跳转闪现 |
| 零延迟拦截跳转 | ❌ 无 root 做不到 |
| 改别人 App / 破解会员 | ❌ 没有这类功能 |

---

## 三、目录结构

```
AdSkipper/
├── rules_server/rules.json      远程规则示例（可直接当静态文件托管）
├── .github/workflows/build.yml  GitHub 云端编译
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/miaotiao/adskip/
    │   ├── data/
    │   │   ├── Prefs.kt              配置持久化
    │   │   ├── RuleRepository.kt     四层规则合并（内置/远程/学习/自定义）
    │   │   └── LearnStore.kt         自动学习：把认到的按钮写成规则
    │   ├── engine/
    │   │   ├── RuleSet.kt            规则模型（关键词预编译）
    │   │   ├── Matcher.kt            匹配引擎（修饰符 / 模糊 / 坐标）
    │   │   ├── SplashScanner.kt      识别器：快路径 + 整树遍历 + 启发式
    │   │   └── Access.kt             节点树遍历、点击、dump
    │   ├── net/
    │   │   └── RemoteRules.kt        远程规则下载（ETag / 原子写入 / 失败回退）
    │   ├── service/
    │   │   ├── SkipAccessibilityService.kt   核心：事件监听 + 后台扫描 + 点击
    │   │   ├── JumpGuard.kt                  防跳转
    │   │   └── InspectorService.kt           自检悬浮球
    │   └── ui/
    │       ├── MainActivity.kt
    │       └── RulesActivity.kt
    └── res/raw/default_rules.json    内置规则（17 个常见 App）
```

---

## 四、编译与安装

### 本机环境（已就绪）

| 组件 | 路径 |
|---|---|
| JDK 17 | `<TOOLCHAIN_DIR>\jdk17` |
| Android SDK 34 | `<TOOLCHAIN_DIR>\android-sdk` |
| Gradle 8.4 | `<TOOLCHAIN_DIR>\gradle-8.4\bin\gradle.bat` |

```powershell
$env:JAVA_HOME = "<TOOLCHAIN_DIR>\jdk17"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
& "<TOOLCHAIN_DIR>\gradle-8.4\bin\gradle.bat" -p "<PROJECT_DIR>" assembleDebug --console=plain
```

安装（手机已连、开着 USB 调试时）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure enabled_accessibility_services com.miaotiao.adskip/com.miaotiao.adskip.service.SkipAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

> ⚠️ **每次重装 APK 后，无障碍服务大概率不会自动重连**（HyperOS / MIUI 常见）。
> 表现是「装完看起来正常，但一点反应都没有」。回到系统设置里把「秒跳」的无障碍开关关掉再打开即可；
> 或者用上面两条 `settings put` 命令（需要 USB 调试）。

---

## 五、远程规则

### 1. 手机上怎么填

首页 → 「规则来源」卡片 → 填地址 → 「立即同步」。同步成功后状态栏会显示「远程规则 N 个应用 · 同步时间」。

也可以用链接直接灌进去（不用手打）：

```bash
adb shell am start -a android.intent.action.VIEW -d "miaotiao://sync?url=http://你的地址/rules.json" com.miaotiao.adskip
```

自动同步：默认开启，6 小时一次，App 回到前台时判断是否到期。

### 2. 规则文件格式

支持两种格式：

**A. 秒跳原生格式**（和本地自定义规则一致，就是多几个可选字段）：

```json
{
  "version": 3,
  "updatedAt": "2026-11-05",
  "defaults": { "keywords": ["跳过", "跳过广告"], "delay": 200, "duration": 4200, "interval": 130 },
  "apps": {
    "com.taobao.taobao": {
      "name": "淘宝",
      "splash": {
        "keywords": ["跳过", "跳过广告"],
        "keywords_append": ["tv_skip", "skip_btn"]
      },
      "popups": [
        { "when": ["隐私政策"], "action": ["同意"], "cooldown": 8000 }
      ]
    }
  }
}
```

**B. 李跳跳社区规则格式**（哈希数组），现在也能直接读：

```json
[
  { "-1835777899": "{\"popup_rules\":[{\"id\":\"青少年模式\",\"action\":\"我知道了\"}]}" }
]
```

这种格式里的 key 是包名的 `String.hashCode()`。App 会在**本机已安装的包里**反查对应的包名，只对已安装的应用建立规则对象——所以 326 条远程规则不会变成 326 份常驻内存，未安装的直接跳过。

> 已实测：社区 `BasicRules.json`（326 条）下载、解析、反查成功，映射到本机 38 个已安装应用（淘宝/京东/拼多多/美团/B站/抖音/微信/QQ/支付宝等），加上内置 17 个去重后共 **46 个应用规则**。

### 3. 哪里能放这个文件

任何能返回 JSON 的静态地址都行，最简单三种：

| 方式 | 地址形如 | 说明 |
|---|---|---|
| GitHub Raw | `https://raw.githubusercontent.com/你/仓库/main/rules.json` | 改完提交，手机下次同步自动更新 |
| Gitee Raw | `https://gitee.com/你/仓库/raw/main/rules.json` | 国内访问快 |
| 本机 HTTP | `http://192.168.x.x:8000/rules.json` | 电脑上 `python -m http.server 8000` |

本机调试还有个更省事的办法——USB 反向端口，不用管 IP：

```bash
cd AdSkipper/rules_server
python -m http.server 8765 --bind 127.0.0.1
adb reverse tcp:8765 tcp:8765
adb shell am start -a android.intent.action.VIEW -d "miaotiao://sync?url=http://127.0.0.1:8765/rules.json" com.miaotiao.adskip
```

> 上面这套是本机验证通过的路径，日志：`remote rules synced, 20 apps, 4715 bytes`

### 4. 合并优先级

```
内置（res/raw） → 远程（缓存） → 自动学习 → 自定义（手写）
```

后者覆盖前者。`keywords` 是覆盖，`keywords_append` 是追加。
网络失败不影响任何东西：继续用上一次缓存的文件，缓存也没有就只用内置。

---

## 六、自动学习（自己认按钮）

规则库里没有的 App，第一次启动时会做一次「像不像开屏广告」的判断：

**必须同时满足** 才有动作：

1. 屏幕上存在一块 ≥45% 屏的无文字区域（广告大图）
2. 页面不是内容页 —— 没有 ≥40% 屏的滚动列表，且可点击元素 ≤ 6 个
3. 候选节点满足其一：倒计时数字（`3` / `12s` / `5秒`）、带 skip/跳过/关闭 字眼、右上角无文字的纯图标

认到就点，并把控件 id（没有就文字，再没有就坐标）写进 `learned_rules`，下次直接按规则命中。

**为什么要这么保守**：第一版放得比较松，实机立刻出事——淘宝顶部的「双11」「国补」频道标签、闲鱼首页的入口图标都被当成跳过按钮点了。加了「内容页闸门」之后就挡住了：真实数据里闲鱼首页 391 个节点、可点击元素一大堆，直接判定为内容页，不做自识别。

**开关**：首页「自动学习」，长按可以清空已学内容。如果你发现某个 App 被点错，先关掉它。

---

## 七、自检悬浮球（让手机自己告诉你为什么没跳）

首页 →「自检 / 探测器」→ 授权「显示在其他应用上层」→ 屏幕右侧出现蓝色「检」小球。

切到目标 App，点小球，直接给结论：

```
包名  com.taobao.idlefish
规则  未配置，用通用规则
扫描  391 个节点 / 116 ms
大图  占屏 62%
判定  内容页（有滚动列表/入口很多，不做自识别）

没有命中任何候选。
如果屏幕上确实有跳过按钮，说明它是自绘的，节点里没有文字。
```

面板上四个按钮：

| 按钮 | 作用 |
|---|---|
| 节点树 | 当前窗口完整节点 dump（写规则时照着抄） |
| 记住 | 把当前屏幕上最像跳过按钮的节点直接写进规则 |
| 复制 | 复制当前面板内容 |
| 关闭 | 收起 |

---

## 八、规则怎么写

入口：首页「规则管理」。保存后立即生效。

### 匹配语法

| 写法 | 含义 |
|---|---|
| `跳过` | 包含即可，能匹配「跳过 3」「跳过广告」 |
| `+检测到` | 必须以「检测到」开头 |
| `-本次不再` | 必须以「本次不再」结尾 |
| `=以后再说` | 必须完全相等 |
| `+检测到&-本次不再` | `&` 表示且 |
| `900,160,1170,250` | 坐标（left,top,right,bottom），节点读不到文字时兜底 |

### 时序参数

`delay` 多久后开始扫、`duration` 扫多久、`interval` 每次间隔。默认 `200 / 4200 / 130`。
快路径能压到 10ms 以内，所以间隔给到 130ms 也不费电。

---

## 九、已知限制（重要）

1. **防跳转是事后补救**。检测到前台包名变成应用商店/浏览器后立刻按返回，会有 1 秒闪现。
2. **快路径依赖系统文本检索**。极少数自绘控件检索不到，所以每轮仍在第 0/2/5/9 次扫描做整树遍历兜底。
3. **App 忙碌时快路径也会慢**。实机见过一次 934ms（京东冷启动时 UI 线程被广告 SDK 占住），它在后台线程跑，不会卡界面，但会推迟这一轮。
4. **Flutter / Unity / WebView 自绘页面节点树可能是空的**，只能靠坐标规则，位置变了就失效。
5. **Android 14+ 的 `accessibilityDataSensitive`** 会让部分 App（金融、输入法）对无障碍不可见。
6. 内置 18 条规则只是**起点**，各家 App 版本差异很大，以自检球抓到的为准；远程规则用社区源能补到 326 条。
7. 系统设置、权限弹窗、安装器、桌面、负一屏已加入 `NEVER_SCAN` 白名单——起因是设置页里我们自己的无障碍描述文本含「跳过」，被误点过一次。
8. **远程规则默认不联网也能用**：App 里预填了公共规则源，但首次同步需要联网；之后缓存生效，断网照常用内置 + 缓存。

---

## 九之二、某个 App 跳不掉怎么查（oopz 实例）

「Oopz 总是跳不掉」这类问题有一套固定查法，2026-11-05 排查 oopz 时完整走了一遍：

**第 1 步：确认包里有没有规则**

```bash
adb shell pm list packages | grep -i oopz        # → com.weilaishanhai.oopz
```

**第 2 步：看日志确认扫描有没有跑**

```bash
adb logcat -c
adb shell monkey -p com.weilaishanhai.oopz -c android.intent.category.LAUNCHER 1
adb logcat -d -s AdSkip:*
```

关键三行：

| 日志 | 含义 |
|---|---|
| `watch start pkg=... delay=400 kw=4` | 规则命中了，关键词 4 个，`kw` 就是关键词数量 |
| `scan 3283ms nodes=-1` | 单次扫描卡了 3.2 秒 → 文本检索在 WebView 上阻塞了 |
| `skipped pkg=... label=跳过｜5 how=规则 跳过` | 命中并点击成功 |

**第 3 步：抓广告的真实节点结构**

`uiautomator dump` 太慢（一次 1~2 秒），抓不到广告那 0.5 秒。用**延时截图**先定位广告出现时刻，再在那一刻 dump：

```bash
adb shell monkey -p <包名> -c android.intent.category.LAUNCHER 1 &
for i in $(seq 1 16); do sleep 1.5; adb exec-out screencap -p > f_$i.png; done
# 找到广告那一帧后，立刻
adb shell uiautomator dump /sdcard/ad.xml
adb pull /sdcard/ad.xml ./ad.xml
```

oopz 抓到的真实结构（这才是关键）：

```
FrameLayout id='com.weilaishanhai.oopz:id/ptgAdvertLayout'   ← 广告容器，铺满全屏
  WebView                                                    ← 广告内容在 WebView 里
  View id='countdown-root'  [1113,165][1402,262]
    TextView text='跳过'     [1200,183][1290,243]   ← id 为空！只能靠文字匹配
    TextView text='2'        [1290,183][1316,243]   ← 倒计时是独立节点
  LinearLayout id='ptgSplashShakeLayout'                    ← 摇一摇
  TextView id='ptgSplashHotArea' text='点击跳转至详情页...'  ← 跳转热区，绝不能点
```

**三个关键结论**：

1. **「跳过」按钮的 id 是空的**，只有文字。所以规则只能写 `"keywords": ["跳过"]`，写 id 没用。
2. **广告在 4.5 秒后才加载出来**，而默认扫描窗口只有 3.6 秒 → 窗口不够，扫不到。oopz 的规则改成 `delay 400 / duration 9000`。
3. **「跳过」和倒计时是两个独立 TextView**，所以匹配到的是 `跳过｜5` 这种组合文本。

### 为此做的两处代码加固

| 问题 | 修法 |
|---|---|
| `findAccessibilityNodeInfosByText` 在 WebView 上阻塞 3.2 秒 | 加 **250ms 时间预算**：超预算后本轮只做 id 检索（O(1)），不做文本检索 |
| 可点击祖先可能是铺满全屏的广告容器，点它的中心会点到广告图 | `Access.click` 加 `screenArea` 参数，祖先区域 > 25% 屏时不用它的位置，改用节点自身位置 |
| 英文关键词（Skip/skip）在 WebView 上慢 | 关键词排序改成**含中文的排前面**，中文词先做文本检索 |

效果：oopz 从「跳不掉」变成 **3/3 稳定跳过**，单轮总耗时从 3392ms 降到 176~2100ms（原来每次必有一次 3 秒阻塞）。

---

## 十、免责

仅供个人学习与自用。请勿用于商业分发、二次打包销售，或修改他人应用功能。跳过广告会影响广告主收益，公开发布存在法律风险，请自行评估。

---

## 十一、内存与稳定性（v2 实测）

| 指标 | 数值 | 说明 |
|---|---|---|
| 后台常驻 PSS | 约 36 MB | 退回桌面后，图形内存归零 |
| Native Heap | 约 5 MB | 节点树遍历后即时回收 |
| Dalvik Heap | 约 3 MB | 规则预编译后只留一份 |
| APK 体积 | 5.5 MB | 无三方依赖 |

**做了什么**：

1. **节点生命周期管理**（`Access.recycle`）：跨进程的 `AccessibilityNodeInfo` 用完立即释放，中间父节点、未访问的队列节点都回收。这是之前「退到后台还有 76MB」的根因——图形内存 + 节点引用没释放。
2. **远程规则只建「已安装应用」的对象**：326 条社区规则反查后只保留本机装了的那 38 个，其余不占内存。
3. **自动学习默认关闭**：避免分享给朋友后，对方手机上一堆 App 被误判、还学进规则库。

## 十二、分享给朋友（重点看这节）

APK 直接发过去就能装，**不需要**对方装 Android Studio、不需要连你电脑、不需要 ADB。几个确保「不出错」的设计：

1. **本机联调地址会自动迁移**：如果你之前填过 `127.0.0.1` / `localhost` / `10.0.2.2` 这类调试地址，App 启动时会自动换回公共规则源，不会让朋友去连你电脑。
2. **远程规则四层保险**：内置 → 远程 → 学习 → 自定义，网络失败退回上一份缓存，缓存也没有就用内置，永远不会「规则清空」。
3. **损坏 JSON 自动修复**：社区规则里常有尾随逗号（`,]`、`,}`），解析前会清理；下载有 4MB 上限、5000 条上限，异常只降级不崩溃。
4. **多镜像自动切换**：填公共源时内置 jsdelivr / fastly / raw 三个镜像，一个挂了自动试下一个。

**唯一要提醒对方的**：装完后去系统设置 → 无障碍 → 打开「秒跳」，并在省电设置里把「秒跳」设为不受限（小米/华为/OPPO 都会静默杀无障碍服务，不设白名单过几分钟就失效）。这一步没法自动完成，只能引导。
