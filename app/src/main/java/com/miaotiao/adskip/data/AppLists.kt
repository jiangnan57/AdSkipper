package com.miaotiao.adskip.data

/**
 * 跨模块共用的包名清单。
 *
 * 之所以集中到一处：浏览器名单同时被「无障碍扫描」和「防跳转」用到，
 * 两边各写一份迟早会漏。
 */
object AppLists {

    /**
     * 浏览器。
     *
     * 这类 App 里从来不会有开屏广告，但界面上全是「关闭 ×」「新建标签页 +」这种
     * 通用控件——扫描器一旦把标签页/加页界面当成开屏页，就会把用户的标签页关掉、
     * 或者一直新建空白页（截图里那个 about:blank 就是这么来的）。
     * 直接整体排除，收益远大于损失。
     */
    val BROWSERS = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.android.browser",
        "com.miui.browser",
        "com.tencent.mtt",
        "com.tencent.mtt.x86",
        "com.UCMobile",
        "com.UCMobile.intl",
        "com.quark.browser",
        "com.quark.browser.lite",
        "com.baidu.searchbox",
        "com.baidu.searchbox.lite",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.qihoo.browser",
        "com.sohu.sohuvideo.browser",
        "com.Android.browser"
    )

    /** 名字里带这些片段的，基本可以断定是浏览器 */
    private val BROWSER_HINTS = listOf(
        "browser", ".mtt", "chrome", "quark", "ucmobile", "firefox", "opera"
    )

    fun isBrowser(pkg: String): Boolean {
        if (pkg in BROWSERS) return true
        val lower = pkg.lowercase()
        return BROWSER_HINTS.any { lower.contains(it) }
    }

    /**
     * 应用商店。
     *
     * 正常用户不会在 App 刚起来的几秒里主动去应用商店，所以从任何来源
     * 跳进商店都高度可疑，误触概率极高，是唯一可以「不限来源」的拦截目标。
     */
    val STORES = setOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.wandoujia.phoenix2"
        // 这里**不能**放 com.miui.packageinstaller。
        //
        // 它曾被当成「小米应用商店」写在这里，而 STORES 是唯一「不限来源」的
        // 拦截目标——于是用 MT 管理器 / 文件管理器点开一个 APK，系统安装界面
        // 一弹出来就被判成广告跳转、立刻按返回弹回去，用户装不了软件。
        //
        // 安装器和商店是两回事：商店（com.xiaomi.market）从 App 里被拉起九成是
        // 误触广告，该拦；安装器只会在用户自己点 APK 或点「更新」时出现，
        // 是明确的用户意图，永远不该拦。见 [SYSTEM_UI]。
    )

    fun isStore(pkg: String): Boolean =
        pkg in STORES || STORES.any { pkg.startsWith("$it.") }

    /**
     * 系统 UI 容器：安装器 / 权限弹窗 / 分享面板 / 文件选择器 / 系统设置。
     *
     * 这些都是「用户主动触发的系统页面」，两种角色都必须豁免：
     *
     *   · 作为**目的地**：绝对不能按返回。装 APK、授权限、选文件时被弹回去，
     *     轻则操作丢失，重则安装中断。
     *   · 作为**来源**：也不参与判定，否则从安装器返回 App 会被当成一次跳转。
     *
     * 为什么单独一份名单，而不是复用 NEVER_SCAN：
     * NEVER_SCAN 只作用于「扫描链」，JumpGuard 的判定链根本读不到它。
     * 名单只在一个环节生效，这个项目已经因此栽过三次（v1.3.4 的 isBlocked
     * 让被拦包当不了来源、contentPage 统计放在规则命中之后、以及这次的
     * 安装器被写进 STORES）。所以统一在这里定义，两条链共用。
     *
     * 注意**不要**按 `FLAG_SYSTEM` 泛判「系统应用一律豁免」——
     * com.miui.systemAdSolution 也是系统应用，而它恰恰是最该拦的
     * （日志里它拉起应用商店那条拦截是对的）。
     */
    val SYSTEM_UI = setOf(
        // 安装器（各家 ROM 的包名都不一样，常见几家都列上）
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.oppo.packageinstaller",
        "com.vivo.packageinstaller",
        // 权限授予弹窗（Android 11+ 几乎都在 permissioncontroller 里）
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.lbe.security.miui",
        "com.miui.securitycenter",
        // 分享面板 / 文件选择器
        "com.android.intentresolver",
        "com.android.documentsui",
        "com.google.android.documentsui",
        // 系统设置
        "com.android.settings"
    )

    fun isSystemUi(pkg: String): Boolean =
        pkg in SYSTEM_UI || SYSTEM_UI.any { pkg.startsWith("$it.") }

    /**
     * 系统外壳：桌面 / 负一屏 / 控制中心插件 / 全局搜索。
     *
     * 这些包**不是用户要去的目的地，而是找 App 的地方**。用户在这里点一个图标
     * 或卡片，等于说「我要打开下面那个 App」——那是最明确的用户意图，
     * 绝不能被判成「广告跳转然后弹回去」。
     *
     * ## 为什么单独一份，不并进 [SYSTEM_UI]
     *
     * 两者的处理方式不同，而且必须不同：
     *
     *   · [SYSTEM_UI]（安装器/权限弹窗）要当**透明层**——它盖在真实 App 上面，
     *     下面的 App 还在，不能清空来源上下文，否则装完 APK 回到 MT 管理器
     *     会被算成一次全新跳转。
     *   · [SHELL] 要当**桌面**——用户在这里停留时，上一个 App 的上下文
     *     已经彻底失效了（从负一屏点开拼多多，和从桌面点开拼多多没有区别）。
     *     所以它必须触发 reset()，让来源变成「未知」，判定直接放过。
     *
     * ## 这份名单是怎么来的
     *
     * 2026-11-20 用户反馈「拼多多多次拦截、美团也拦」：日志里十几次
     * `拦截跳转 pinduoduo：像广告跳转（来源 com.miui.personalassistant，
     * cold=true splash=true）`、`来源 miui.systemui.plugin，128ms`。
     *
     * 这两个包早就在扫描链的 NEVER_SCAN 里（不扫它们），但 JumpGuard 的
     * 判定链根本读不到那份名单——**同一个错误在这个项目里第四次出现**
     * （前三次见 [STORES] 和 [SYSTEM_UI] 的注释）。于是负一屏成了「来源」：
     * 它是冷启动（calcCold 对 passThrough 返回 true）、永远不会被扫到内容页
     * （所以 settled 恒为 false）、用户 1.3 秒后就点开了拼多多（dt 很短）。
     * 三个条件正好凑齐「来源还在开屏、摇一摇落地」的判据，于是每点一次
     * 弹回一次，还会把自己重新武装成「仍在开屏」（见 restoringPkg），
     * 形成自激循环。
     *
     * 名单按各家 ROM 的实际包名列，不按 `FLAG_SYSTEM` 泛判——
     * com.miui.systemAdSolution 也是系统应用，而它恰恰是最该拦的。
     */
    val SHELL = setOf(
        // 小米 / Redmi（HyperOS、MIUI）
        "com.miui.home",
        "com.miui.personalassistant", // 负一屏「智能助理」
        "miui.systemui.plugin", // 控制中心 / 通知中心的插件进程
        "com.miui.globalsearch",
        // 语音助手：「打开 XX」是最明确的用户意图。
        // 实测（2026-11-20）从小爱同学点开拼多多，它会成为跳转来源；
        // 停留不够 5 秒的话就会命中「冷启动 + 未进正片」→ 弹回。
        "com.miui.voiceassist",
        "com.huawei.vassistant",
        // 原生 / Google
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.quicksearchbox",
        "com.google.android.googlequicksearchbox", // 负一屏 Discover
        // 注意：com.android.systemui 不在这里。它是**透明层**（状态栏/导航栏/
        // 弹窗宿主），不该清空来源上下文，由 JumpGuard 单独 return 处理。
        // 华为 / 荣耀
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.huawei.intelligent", // 负一屏「智慧助手·今天」
        // OPPO / 一加 / realme
        "com.oppo.launcher",
        "com.oplus.launcher",
        "com.coloros.assistantscreen", // 负一屏「智慧助手」
        // vivo / iQOO
        "com.bbk.launcher2",
        "com.vivo.assistant", // 负一屏「Jovi」
        // 三星
        "com.sec.android.app.launcher",
        "com.samsung.android.app.spage", // 负一屏「Samsung Free」
        // 常见第三方桌面
        "com.teslacoilsw.launcher",
        "ch.deletescape.lawnchair.plah",
        "app.lawnchair",
        // 系统框架自己：日志里出现过「未找到跳过按钮：android」
        "android"
    )

    /** 负一屏/桌面的包名特征，覆盖名单里没列到的小众 ROM */
    private val SHELL_HINTS = listOf(
        "personalassistant",
        "assistantscreen",
        "quicksearchbox",
        "globalsearch",
        "systemui.plugin",
        ".spage",
        "voiceassist",
        "vassistant",
        "bixby",
        "launcher"
    )

    /**
     * 是不是系统外壳（桌面 / 负一屏 / 控制中心插件 / 全局搜索）。
     *
     * 命中即「用户在自己找 App」，一律不参与跳转判定。
     */
    fun isShell(pkg: String): Boolean {
        if (pkg in SHELL) return true
        val lower = pkg.lowercase()
        // 前缀匹配兜住 "com.miui.home.xxx" 这类子包
        if (SHELL.any { pkg.startsWith("$it.") }) return true
        return SHELL_HINTS.any { lower.contains(it) }
    }

    /**
     * 「用户自己点开的 App」。
     *
     * 这份名单是**双向豁免**：
     *
     *   · 作为**来源**：微信 / QQ 里点小程序、点分享链接、点公众号文章，
     *     一跳外部就自动返回，用户会觉得这软件疯了。
     *   · 作为**目的地**：NFC 唤起支付宝、手表弹窗打开淘宝、微信里点开
     *     一个 App——这些都是用户自己的意图，再怎么像广告也不能弹回。
     *
     * v1.3.4 的教训：旧代码里这份名单只当成「来源豁免」，而且判断排在
     * 「是不是落地页」之后。于是支付宝同时出现在「落地页」和「来源豁免」
     * 两份名单里，落地页判断在前 → **NFC 一碰支付宝就被弹回去**，
     * 而那份豁免根本走不到。现在统一成一份名单，优先级最高。
     */
    val USER_DRIVEN = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.wework",
        "com.eg.android.AlipayGphone", // 支付宝：NFC / 手表 / 收款码唤起
        "com.alibaba.android.rimet", // 钉钉
        "com.ss.android.lark", // 飞书
        "com.tencent.docs", // 腾讯文档
        "com.zhiliaoapp.musically" // TikTok 国际版，同样是内容浏览场景
    )

    /**
     * 「用户自己点开的链接」这类来源，永远不该被按返回。
     *
     * 保留这个旧名字是为了不破坏别的调用点，语义已经等同于 [USER_DRIVEN]。
     */
    val NO_GUARD_ORIGIN = USER_DRIVEN

    /**
     * 广告跳转高发的来源。
     *
     * 浏览器类目的地只对这些 App 「返回」，因为这些场景里九成是误触广告；
     * 应用商店类目的地则不限来源（正常用户不会在 2 秒内自己点进商店）。
     */
    val AD_PRONE = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.dragon.read",
        "com.dragon.read.pad",
        "com.qidian.QDReader",
        "com.tencent.qqlive",
        "com.qiyi.video",
        "com.youku.phone",
        "tv.danmaku.bili",
        "com.xingin.xhs",
        "com.sina.weibo",
        "com.zhihu.android",
        "com.taobao.taobao",
        "com.xunmeng.pinduoduo",
        "com.jingdong.app.mall",
        "com.sankuai.meituan",
        "com.netease.cloudmusic",
        "com.gotokeep.keep"
    )

    /**
     * 「广告落地页」：**电商类**。
     *
     * 判据和商店不同。商店可以「任何来源、几秒内」无条件返回，因为正常人不会
     * 在 App 刚起来的 5 秒里主动去应用商店；但电商 App 用户是会自己点的
     * （详情页里的商品卡、推广位），所以这类只在
     * 「来源 App 是刚从桌面打开、还没怎么用」时才按返回——那正是摇一摇广告
     * 和误触落地的唯一形态。见 JumpGuard 里的 originCold。
     *
     * 加这一组的直接原因：WakeUp 课程表的开屏是穿山甲「摇一摇」广告，
     * 摇一下直接拉起电商/商店，无障碍服务看不到传感器事件，只能落地后补救。
     */
    val AD_LANDING = setOf(
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.jingdong.app.mall",
        "com.jingdong.pdj",
        "com.xunmeng.pinduoduo",
        "com.achievo.vipshop",
        "com.suning.mobile.ebuy",
        "com.sankuai.meituan",
        "me.ele",
        "com.taobao.idlefish",
        "com.kaola",
        "com.alibaba.aliexpresshd",
        "com.taobao.litetao",
        "com.jd.jdlite"
    )

    /**
     * 「内容型 App」：抖音、快手、小红书、番茄小说、各家视频……
     *
     * 为什么要从 [AD_LANDING] 里拆出来：
     *
     * 旧名单把「电商落地页」和「内容 App」混在一起当同一种东西，
     * 判据却只有「来源冷启动 + 没进正片」。于是**番茄小说自己**也成了
     * 「落地页」——用户点开番茄小说的个人主页（同包切换），因为番茄在
     * 旧名单里，就被当成「跳到了广告落地页」→ 弹回。这就是那次投诉。
     *
     * 内容 App 的性质完全不同：用户点进去就是**想看**，误弹回的代价远大于
     * 漏弹回。所以这一组额外要求来源必须是广告高发 App，且时间窗更短。
     */
    val CONTENT_APPS = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.xingin.xhs",
        "com.phoenix.read", // 番茄免费小说
        "com.dragon.read", // 番茄小说
        "com.dragon.read.pad",
        "com.miui.video",
        "com.qiyi.video",
        "com.tencent.qqlive",
        "com.youku.phone",
        "com.sina.weibo",
        "com.zhihu.android",
        // 下面这几个原本只在 AD_PRONE（当来源用）里，没进目的地名单。
        // 后果是摇一摇广告直接拉起 B 站 / 起点读书时不会被拦——漏拦。
        "tv.danmaku.bili",
        "com.qidian.QDReader",
        "com.netease.cloudmusic",
        "com.gotokeep.keep"
    )

    /**
     * 是不是「电商类广告落地页」。
     *
     * 这里用的是**精确匹配**，不做 `pkg.startsWith("$it.")` 的前缀展开。
     * 前缀匹配会把「同一个开发者的另一个独立 App」也卷进来：
     * `com.sankuai.meituan` 前缀能命中 `com.sankuai.meituan.takeout`（美团外卖），
     * 于是用户从抖音点个美团外卖链接就会被当成广告跳走弹回去。
     * 这些条目本身都是各自独立的应用，没有需要前缀覆盖的子包。
     */
    fun isAdLanding(pkg: String): Boolean = pkg in AD_LANDING

    /** 是不是「内容型 App」（用户点进去是想看，回退代价高） */
    fun isContentApp(pkg: String): Boolean =
        pkg in CONTENT_APPS || CONTENT_APPS.any { pkg.startsWith("$it.") }
}
