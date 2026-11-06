# 秒跳 · AdSkipper

> 一个基于 Android 无障碍服务的开屏广告跳过工具，纯本地规则匹配，不上传任何屏幕内容。
> 当前版本 **v1.3.2**（versionCode 8） · minSdk 26 / targetSdk 34 · Kotlin + XML 布局

---

> ### ⚠️ 请先读这一段：本项目由 AI 生成
>
> 本仓库的**源代码、内置规则、测试样本、脚本与文档，均由 AI 编程助手（WorkBuddy，腾讯混元大模型驱动）根据作者的需求描述生成并迭代**，并非由专业团队设计、评审或审计的工程产品。
>
> - 代码**未经第三方安全审计、渗透测试或形式化验证**，可能存在未知缺陷；
> - 仅在 Redmi K40（MIUI / Android 11）、Redmi 24122RKC7C（HyperOS / Android 16）与自带的人造样本上做过实机验证，**不代表在其他机型、ROM 或 Android 版本上可用**；
> - 本项目属于**个人自用 / 学习研究性质的实验项目**，不是稳定产品，不承诺持续维护或适配；
> - 它会申请**无障碍服务**并在你的屏幕上模拟点击，存在误触、漏跳、影响第三方应用正常使用的风险。
>
> 使用前请完整阅读 [DISCLAIMER.md（免责声明）](DISCLAIMER.md)。安装并使用本软件，即视为你已理解并接受其中的全部条款。

---

## 这是什么

原理和李跳跳一类工具相同，三步：

```
读取当前窗口的控件节点树  →  在本地按规则匹配「跳过」类按钮  →  模拟点击
```

- **一次整树遍历 + 本地匹配**：每个扫描轮次只取一次 `rootInActiveWindow`，在同一棵树上同时匹配控件 id 与文字，不再调用会在 WebView 上阻塞数秒的系统文本检索接口。
- **规则驱动**：内置规则 + 远程规则 + 自定义规则分层合并；只认「跳过 / 跳过广告 / 关闭广告 / 关闭开屏」这类明确的跳过词，「关闭」不算。
- **保守的防误点**：冷启动监测窗口、时间节流的扫描、内容页闸门（有滚动列表 / 可点击元素过多就不自识别）、连续两轮同位置才动作；浏览器、系统设置、安装器等整体不扫描。
- **不学习坐标规则**：早期版本会因学错坐标而在每次打开 App 时误点固定位置，v1.1.0 起彻底移除，并自动清理历史数据。
- **ROM 兼容处理**：前台服务 + 1×1 透明微窗，解决部分 HyperOS / MIUI 上「无障碍开着却拿不到正确窗口、进程被后台冻结」的问题。

## 能做什么 / 不能做什么

| 能力 | 情况 |
|---|---|
| 跳过开屏广告（文字 / 控件 id） | ✅ 规则匹配 + 冷启动监测窗口 |
| 关闭应用内运营弹窗 | ✅ 「条件词 → 动作词」规则（需为该 App 配置） |
| 远程规则同步 | ✅ 仅 HTTPS、ETag 增量、失败自动回退上一份缓存 |
| 自定义规则 / 自检探测器 | ✅ 应用内编辑，悬浮球可 dump 当前节点树 |
| 防止摇一摇误跳转 | ⚠️ 只能在跳转后回退，会有短暂闪现 |
| 零延迟拦截跳转 / 破解会员 / 修改他人 App | ❌ 做不到，也没有这类功能 |
| 自绘（Canvas / Unity / 部分 Flutter）广告 | ⚠️ 节点无文字时只能靠手写规则，效果不保证 |

## 快速开始

1. 到 **[最新 Release](https://github.com/jiangnan57/AdSkipper/releases/latest)** 下载 APK（或自行从源码构建），建议核对发布页给出的 **SHA-256**。
2. 安装后**先打开一次 App**，让进程与前台服务启动起来。
3. 系统设置 → 无障碍 → 找到「秒跳」→ 打开。
4. 按首页体检提示补齐三项授权：**通知权限**、**悬浮窗（显示在其他应用上层）**、**电池优化白名单 / 自启动**。三项缺一项，部分 ROM 上就会出现「开关开着但一个广告都不跳」。首页的黄字提示可直接点进对应设置页。

> **重装 / 升级 APK 后**，HyperOS / MIUI 常常不会自动重连无障碍服务。表现是「设置里看着是开的，但完全没反应」——把该开关关掉再打开一次即可（有 USB 调试时也可重新 `settings put secure enabled_accessibility_services ...`）。

## 规则

四层合并，后者覆盖前者：`内置（res/raw/default_rules.json） → 远程缓存 → 自动学习 → 自定义（手写）`。

- 内置规则覆盖常见应用；远程规则可填任意返回 JSON 的 **https** 地址（明文 http 会被拒绝，因为规则本质是可执行的点击指令，中途篡改风险太高）。
- 兼容「李跳跳」社区规则格式（包名哈希数组），会在本机已安装应用里反查包名，未安装的规则不建立对象。
- 可选公共规则源来自 [Snoopy1866/LiTiaotiao-Custom-Rules](https://github.com/Snoopy1866/LiTiaotiao-Custom-Rules)，仅作为第三方数据源，权利归原作者，本项目与其无关联、也未获其认可。远程规则内容请自行审查。
- 规则语法、时序参数与排障实例见 [docs/DEV_NOTES.md](docs/DEV_NOTES.md)（**早期版本笔记，部分内容已过时**）。

## 从源码构建

需要 JDK 17、Android SDK 34（build-tools 33+）、Gradle 8.4；在 `local.properties` 里写 `sdk.dir=<你的 SDK 路径>`（该文件不入库）。

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

GitHub Actions（`.github/workflows/build.yml`）会在 push 时编译 debug 包并上传为 artifact，**不需要任何密钥**。

打 release 包需自备签名密钥，口令通过环境变量或 `local.properties` 提供（**绝不写入版本库**）：

```bash
ADSKIP_STORE_PASS=xxx ADSKIP_KEY_PASS=xxx ./gradlew assembleRelease
```

> 缺少密钥时 release 构建会直接报错，而 debug 构建不受影响。`keystore/`、`local.properties`、`dist/` 均已在 `.gitignore` 中排除。

## 测试

`adsample/` 是一个人造广告测试台，包含 **20 种样本**：多种开屏广告形态、内容页负向样本（验证不误点）、以及运营弹窗样本。

```bash
bash test_variants.sh <adb 设备序列号>    # 广告形态：20/20
bash test_guard.sh   <adb 设备序列号>    # 防误跳四场景：4/4
```

回归需要 USB 调试、测试期间**独占前台**（不要同时打开其他 App），并且每次重装后重新绑定无障碍。当前 v1.3.2 在 Redmi K40（Android 11）上为 **20/20 + 4/4 通过**。

`test_guard.sh` 验证的是「该拦的拦住、不该拦的放过」：广告跳淘宝 / 跳商店要能自动退出，而用户自己点进淘宝、或在内容页正常使用后跳转时绝不能乱返回。

## 隐私与权限

**本项目不收集、不存储、不上传任何屏幕内容；无账号体系、无统计或埋点 SDK、无数据回传。** 节点树只在内存中即时匹配，用完释放。

| 权限 | 用途 |
|---|---|
| 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`） | 读取当前窗口控件、执行点击 / 返回 |
| `SYSTEM_ALERT_WINDOW` | 自检悬浮球，以及 1×1 透明微窗（保证后台仍能取到正确窗口） |
| `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` | 前台服务保活，避免被系统冻结 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复服务 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加入电池优化白名单 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **仅用于** HTTPS 下载用户配置的规则文件 |
| `QUERY_ALL_PACKAGES` | 把社区规则中的包名哈希反查为本机已安装应用 |

## 已知限制

1. 无 root 情况下无法零延迟拦截跳转，防跳转只能事后按返回。
2. 自绘页面节点树可能为空；Android 14+ 的 `accessibilityDataSensitive` 会让金融、输入法等应用对无障碍不可见。
3. 通用规则（generic）中的 `popups` 不生效；应用级 `exclude` 字段被忽略，只认顶层 `defaults.exclude`。
4. 各家 ROM 的省电策略会静默解绑无障碍，必须完成上述四项授权。
5. 内置规则只是起点，App 版本差异很大，建议用自检探测器抓真实节点后自行补规则。
6. 未在多种机型 / 系统版本上做过系统性兼容测试。

## 目录结构

```
app/                     主程序（Kotlin + XML）
├── engine/              规则模型、匹配器、节点树扫描与点击
├── service/             无障碍服务、保活、自检、防跳转
├── data/                配置、规则仓库、应用白名单
├── net/                 远程规则同步（HTTPS / ETag / 原子写入）
└── res/raw/             内置规则
adsample/                19 种样本的广告测试台
rules_server/            规则文件示例
docs/DEV_NOTES.md        历史开发笔记（早期版本，仅供参考）
test_variants.sh         实机回归脚本
share.sh                 打包分发脚本
```

## 免责声明与许可

完整条款见 **[DISCLAIMER.md](DISCLAIMER.md)**：AI 生成、无担保、误触风险、第三方应用协议与广告收益影响、隐私与安全安装建议、责任限制。

本仓库**暂未附带 LICENSE 文件**，在作者明确授权前默认保留所有权利；阅读、学习与本地构建自用不受限制，商业使用与二次分发请先取得授权。
