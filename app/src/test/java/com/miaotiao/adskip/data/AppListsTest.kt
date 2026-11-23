package com.miaotiao.adskip.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名单自洽性测试。
 *
 * 这个项目三次栽在「同一个包出现在两份名单里、或者名单只在一个环节生效」：
 *   · v1.3.4：支付宝同时在 USER_DRIVEN 和 AD_LANDING，落地页判断在前 → NFC 唤起被弹回；
 *   · v1.3.4：contentPage 统计放在规则命中之后 → 内容页闸门永久失效；
 *   · v1.3.7：com.miui.packageinstaller 被写进 STORES → MT 管理器装 APK 时
 *     系统安装界面一弹出来就被当成广告跳转弹回去，用户装不了软件。
 *
 * 所以这里不只测「某几个包名对不对」，而是测**集合之间的关系**，
 * 以后再有人往名单里加东西，交集不为空就会红。
 */
class AppListsTest {

    private val installers = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.oppo.packageinstaller",
        "com.vivo.packageinstaller"
    )

    @Test
    fun 所有安装器都必须被识别为系统UI() {
        installers.forEach {
            assertTrue("$it 是安装器，必须豁免跳转判定", AppLists.isSystemUi(it))
        }
    }

    @Test
    fun 安装器绝不能被当成应用商店() {
        // 这正是 v1.3.7 修的 bug：安装器在 STORES 里，而 STORES 是
        // 唯一「不限来源」的拦截目标，于是装 APK 必被弹回。
        installers.forEach {
            assertFalse("$it 不是应用商店，放进 STORES 会让用户装不了软件",
                AppLists.isStore(it))
        }
    }

    @Test
    fun 权限弹窗与文件选择器也必须豁免() {
        listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.documentsui",
            "com.android.intentresolver",
            "com.android.settings"
        ).forEach {
            assertTrue("$it 是系统弹层，被弹回去会丢失用户操作", AppLists.isSystemUi(it))
        }
    }

    @Test
    fun 系统广告组件不能被误当成系统UI豁免() {
        // com.miui.systemAdSolution 也是「系统应用」，但它恰恰最该拦——
        // 日志里它拉起应用商店那条拦截是对的。所以 SYSTEM_UI 只能列具体包名，
        // 不能按 FLAG_SYSTEM 泛判。
        assertFalse(AppLists.isSystemUi("com.miui.systemAdSolution"))
        assertFalse(AppLists.isStore("com.miui.systemAdSolution"))
    }

    @Test
    fun 应用商店仍然必须被拦截() {
        listOf(
            "com.xiaomi.market",
            "com.android.vending",
            "com.huawei.appmarket",
            "com.tencent.android.qqdownloader"
        ).forEach {
            assertTrue("$it 是应用商店，跳进来九成是误触广告，必须拦", AppLists.isStore(it))
        }
    }

    @Test
    fun 系统UI与商店名单不能有交集() {
        // 一个包同时是「必拦」和「必放行」时，结果取决于判断顺序，
        // 而顺序是会被人改的——所以干脆禁止重叠。
        val overlap = AppLists.SYSTEM_UI.filter { AppLists.isStore(it) }
        assertTrue("SYSTEM_UI 与 STORES 不允许重叠，实际重叠：$overlap", overlap.isEmpty())
    }

    // ================================================================
    // 系统外壳（桌面 / 负一屏 / 控制中心插件 / 全局搜索）
    //
    // v1.3.8 的 bug：这批包在扫描链的 NEVER_SCAN 里有，但 JumpGuard 的判定链
    // 读不到，于是负一屏成了「冷启动 + 从未进正片」的完美假来源，
    // 用户从负一屏点开拼多多 / 美团，每点一次被弹回一次。
    // ================================================================

    @Test
    fun 各家负一屏都必须被识别成系统外壳() {
        listOf(
            "com.miui.personalassistant", // 小米「智能助理」
            "com.huawei.intelligent", // 华为「智慧助手·今天」
            "com.coloros.assistantscreen", // OPPO 负一屏
            "com.samsung.android.app.spage", // 三星 Samsung Free
            "com.google.android.googlequicksearchbox" // 原生 Discover
        ).forEach {
            assertTrue("$it 是负一屏，不是 App，不能当跳转来源", AppLists.isShell(it))
        }
    }

    @Test
    fun 桌面与控制中心插件也算系统外壳() {
        listOf(
            "com.miui.home",
            "com.android.launcher3",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "miui.systemui.plugin", // 日志里的 128ms 来源
            "com.miui.voiceassist" // 小爱同学：实测能成为跳转来源
        ).forEach {
            assertTrue("$it 是系统外壳", AppLists.isShell(it))
        }
    }

    @Test
    fun 真实应用绝不能被当成系统外壳() {
        // 这一条比上面几条都重要：判错一次就是「用户点开拼多多被当成桌面」，
        // 于是所有拦截全部失效。
        listOf(
            "com.xunmeng.pinduoduo",
            "com.sankuai.meituan",
            "com.taobao.taobao",
            "com.dragon.read",
            "com.tencent.mm",
            "com.miui.gallery",
            "com.miui.video",
            "com.miui.player"
        ).forEach {
            assertFalse("$it 是真实 App，不能被当成系统外壳", AppLists.isShell(it))
        }
    }

    @Test
    fun 系统外壳不当来源也不能当拦截目标() {
        // 外壳永远不会是「广告落地页」，否则从负一屏跳负一屏会被拦。
        AppLists.SHELL.forEach {
            assertFalse("$it 不该出现在落地页名单", AppLists.isAdLanding(it))
            assertFalse("$it 不该出现在内容 App 名单", AppLists.isContentApp(it))
        }
    }

    @Test
    fun 安装器属于系统UI但不属于外壳() {
        // 两者的处理方式必须不同：安装器是「透明层」（保留来源上下文），
        // 外壳是「桌面」（清空来源上下文）。混在一起会让装完 APK
        // 回到 MT 管理器时被当成一次全新跳转。
        installers.forEach {
            assertFalse("$it 只是覆盖层，不是桌面", AppLists.isShell(it))
        }
    }

    @Test
    fun 各ROM应用商店包名齐全() {
        // 漏一个的后果不是「少拦一次」，而是那个 ROM 的用户完全用不上这个功能：
        // 商店是唯一「不限来源」的拦截目标，名单里没有 = 永远不拦。
        listOf(
            "com.xiaomi.market", // 小米
            "com.huawei.appmarket", // 华为
            "com.hihonor.appmarket", // 荣耀（独立后改了包名）
            "com.heytap.market", // OPPO / 一加 / realme
            "com.bbk.appstore", // vivo / iQOO
            "com.sec.android.app.samsungapps", // 三星 Galaxy Store
            "com.android.vending" // Google Play
        ).forEach {
            assertTrue("$it 是应用商店", AppLists.isStore(it))
        }
    }

    // ================================================================
    // 运行时发现的系统外壳
    //
    // 静态名单只能覆盖「见过的 ROM」，而桌面进程的包名毫无规律
    // （com.miui.home 里连 launcher 这个词都没有；原生兜底桌面更是
    // 塞在 com.android.settings 里）。所以「谁是桌面」交给系统回答。
    // ================================================================

    @Test
    fun 运行时发现的桌面也必须被当成系统外壳() {
        try {
            AppLists.setRuntimeShellForTest(listOf("com.somevendor.home"))
            assertTrue("系统说是 HOME 处理器，就该当桌面", AppLists.isShell("com.somevendor.home"))
            // 有些 ROM 的桌面进程带后缀
            assertTrue(AppLists.isShell("com.somevendor.home.overlay"))
        } finally {
            AppLists.setRuntimeShellForTest(emptyList())
        }
    }

    @Test
    fun 运行时名单不能顺手把真实应用卷进来() {
        try {
            AppLists.setRuntimeShellForTest(listOf("com.somevendor.home"))
            listOf("com.somevendor.shop", "com.somevendor.homework").forEach {
                assertFalse("$it 不是桌面，只是名字挨着", AppLists.isShell(it))
            }
        } finally {
            AppLists.setRuntimeShellForTest(emptyList())
        }
    }

    @Test
    fun 清空运行时名单后静态名单仍然有效() {
        // 某些 ROM 上查询 HOME 会抛 SecurityException，那时 runtimeShell 是空的，
        // 功能必须退回静态名单，不能整体失效。
        AppLists.setRuntimeShellForTest(emptyList())
        assertTrue(AppLists.isShell("com.miui.home"))
        assertTrue(AppLists.isShell("com.miui.personalassistant"))
    }

    @Test
    fun 运行时名单必须排除系统UI包() {
        // 实测本机枚举 HOME 处理器时，除了 com.miui.home 还会返回
        // com.android.settings —— 那是 AOSP 的兜底桌面 FallbackHome 住在设置里。
        // 设置页是「透明层」（保留来源），桌面是「清空来源」，两者语义相反。
        // 如果让设置混进运行时外壳，用户从 App 跳去授权再返回，
        // 来源就变成 null，防跳转在那之后几分钟里等于关闭。
        val merged = AppLists.mergeRuntimeShell(
            listOf("com.miui.home", "com.android.settings", "com.miui.voiceassist")
        )
        try {
            AppLists.setRuntimeShellForTest(merged)
            assertTrue("桌面要保留", "com.miui.home" in merged)
            assertTrue("语音助手要保留", "com.miui.voiceassist" in merged)
            assertFalse("设置是透明层，不能当桌面", "com.android.settings" in merged)
            assertFalse("设置仍应走系统 UI 分支", AppLists.isShell("com.android.settings"))
        } finally {
            AppLists.setRuntimeShellForTest(emptyList())
        }
    }
}
