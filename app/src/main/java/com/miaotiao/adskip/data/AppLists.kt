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
     * 「用户自己点开的链接」这类来源，永远不该被按返回。
     *
     * 微信 / QQ 里点小程序、点分享链接、点公众号文章，一跳外部就自动返回，
     * 用户会觉得这软件疯了。这些 App 的开屏也不需要防跳转兜底。
     */
    val NO_GUARD_ORIGIN = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.wework",
        "com.eg.android.AlipayGphone",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.tencent.docs",
        "com.zhiliaoapp.musically" // TikTok 国际版，同样是内容浏览场景
    )

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
}
