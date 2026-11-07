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
        "com.wandoujia.phoenix2",
        "com.miui.packageinstaller"
    )

    fun isStore(pkg: String): Boolean =
        pkg in STORES || STORES.any { pkg.startsWith("$it.") }

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
