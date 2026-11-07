package com.miaotiao.adskip.engine

import com.miaotiao.adskip.data.AppLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防跳转（回退）判定的回归测试。
 *
 * ## 为什么需要这层测试
 *
 * 「什么时候该按返回」依赖一堆时序条件（来源是冷启动还是后台恢复、
 * 来源有没有扫到内容页、来源还在不在开屏期、距离来源前台多久、
 * 距上次回退多久……），真机上几乎无法稳定复现——用户自己都说不清
 * 是哪一步触发的。
 *
 * 所以判定必须被抽成**纯函数**（[GuardDecision]，不 import 任何 android.*），
 * 用断言把「哪些组合该回退、哪些绝对不该回退」钉死。
 * 以后谁改了判定逻辑、谁动了名单，CI 一跑就知道有没有把
 * 「用户主动打开某个 App」误判成「广告跳转」。
 *
 * ## 这些测试覆盖的真实投诉
 *
 * 1. 2026-11-07：用 NFC 打开支付宝时被弹回。
 * 2. 2026-11-07：番茄小说打开个人主页时被弹回。
 *
 * 两条都「不可复制、无法测试」，所以只能用代码层面的断言来防复发。
 */
class GuardDecisionTest {

    // ---- 真实包名（和用户手机上一致）----
    private val alipay = "com.eg.android.AlipayGphone"
    private val tomato = "com.dragon.read" // 番茄小说
    private val tomatoFree = "com.phoenix.read" // 番茄免费小说
    private val taobao = "com.taobao.taobao"
    private val jd = "com.jingdong.app.mall"
    private val douyin = "com.ss.android.ugc.aweme"
    private val kuaishou = "com.smile.gifmaker"
    private val xhs = "com.xingin.xhs"
    private val weixin = "com.tencent.mm"
    private val chrome = "com.android.chrome"
    private val miStore = "com.xiaomi.market"
    private val wakeup = "com.papaya.wakeup" // 课程表，不在任何名单里

    /**
     * 用真实包名构造一次前台切换的判定输入。
     *
     * 关键点：目的地/来源的**分类**全部由 [AppLists] 现算，
     * 而不是测试里手写 true/false。这样名单一旦被改，测试立刻能发现
     * 「某个 App 悄悄从落地页变成了用户主动 App」这类事故。
     */
    private fun judge(
        dest: String,
        origin: String?,
        cold: Boolean = true,
        settled: Boolean = false,
        splash: Boolean = true,
        dtMs: Long = 3_000L,
        sinceLastBackMs: Long = Long.MAX_VALUE,
        changed: Boolean = true,
        custom: Boolean = false
    ): GuardDecision.Verdict = GuardDecision.decide(
        GuardDecision.Context(
            destPkg = dest,
            destChanged = changed,
            originPkg = origin,
            originCold = cold,
            originSettled = settled,
            originShowingSplash = splash,
            dtMs = dtMs,
            sinceLastBackMs = sinceLastBackMs,
            destIsStore = AppLists.isStore(dest),
            destIsBrowser = AppLists.isBrowser(dest),
            destIsLanding = AppLists.isAdLanding(dest),
            destIsContent = AppLists.isContentApp(dest),
            destIsCustom = custom,
            destIsUserDriven = dest in AppLists.USER_DRIVEN,
            originIsUserDriven = origin != null && origin in AppLists.USER_DRIVEN,
            originIsAdProne = origin != null && origin in AppLists.AD_PRONE
        )
    )

    // ================================================================
    // 一、名单本身的自洽性
    //
    // 防止以后有人往名单里加东西时，把「用户主动打开的 App」
    // 和「广告落地页」又搅到同一个集合里。
    // ================================================================

    @Test
    fun 支付宝必须在用户主动名单里且不在落地页名单里() {
        assertTrue("支付宝必须在用户主动打开的名单里", alipay in AppLists.USER_DRIVEN)
        assertFalse("支付宝绝不能是广告落地页", AppLists.isAdLanding(alipay))
        assertFalse(AppLists.isContentApp(alipay))
        assertFalse(AppLists.isStore(alipay))
    }

    @Test
    fun 番茄小说属于内容型App而不是落地页() {
        assertTrue(AppLists.isContentApp(tomato))
        assertTrue(AppLists.isContentApp(tomatoFree))
        assertFalse("番茄小说不能当落地页，否则同包切页会被当成跳转", AppLists.isAdLanding(tomato))
        assertFalse(AppLists.isAdLanding(tomatoFree))
    }

    @Test
    fun 落地页和内容型App两组不能有交集() {
        val overlap = AppLists.AD_LANDING.intersect(AppLists.CONTENT_APPS)
        assertTrue("两组重叠会让判定分支含糊：$overlap", overlap.isEmpty())
    }

    @Test
    fun 用户主动名单和落地页名单不能有交集() {
        val overlap = AppLists.USER_DRIVEN.intersect(AppLists.AD_LANDING)
        assertTrue("同一个包不能既是用户主动打开的、又是广告落地页：$overlap", overlap.isEmpty())
    }

    // ================================================================
    // 二、投诉场景 1：NFC 打开支付宝时被弹回
    // ================================================================

    @Test
    fun NFC唤起支付宝绝对不许回退() {
        // 时序完全像一次广告跳转：来源冷启动、1 秒内落地、
        // 冷却期也过了。旧代码就是在这里弹回去的。
        val v = judge(
            dest = alipay,
            origin = wakeup,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 800L
        )
        assertFalse("NFC 唤起支付宝是用户自己的意图，绝不能弹回", v.shouldBack)
        assertEquals(GuardDecision.Action.KEEP, v.action)
    }

    @Test
    fun 用户主动打开的名单优先级高于一切判据() {
        // 即使来源是广告高发 App、目的地伪装得像落地页，也一样放过
        val v = judge(
            dest = alipay,
            origin = douyin,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 200L
        )
        assertEquals(GuardDecision.Action.KEEP, v.action)
        assertTrue(v.reason.contains("用户主动"))
    }

    @Test
    fun 微信里点开一个App也不许回退() {
        val v = judge(dest = jd, origin = weixin, dtMs = 500L)
        assertFalse("微信里点开京东是用户自己点的", v.shouldBack)
    }

    // ================================================================
    // 三、投诉场景 2：番茄小说打开个人主页时被弹回
    // ================================================================

    @Test
    fun 番茄小说内部切页绝对不许回退() {
        // 「打开个人主页」= 同一个 App 内部切 Activity，包名没变。
        // 旧代码每来一条窗口事件就重判一次，于是这条事件被判成
        // 「跳到了广告落地页」→ 弹回。
        val v = judge(
            dest = tomato,
            origin = tomato,
            changed = false,
            dtMs = 300L
        )
        assertFalse("同一 App 内部切页面不是跳转", v.shouldBack)
        assertEquals(GuardDecision.Action.KEEP, v.action)
    }

    @Test
    fun 来源和目的地同一个App时也不许回退() {
        // 兜底：即使「包名变没变」的标志传错了，
        // 只要来源和目的地是同一个 App，就绝不弹回。
        val v = judge(
            dest = tomato,
            origin = tomato,
            changed = true,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 300L
        )
        assertFalse(v.shouldBack)
        assertEquals(GuardDecision.Action.KEEP, v.action)
    }

    @Test
    fun 从抖音跳进番茄小说在来源进正片后不该回退() {
        // 抖音的界面早就扫到过内容页（settled=true），
        // 说明用户正在正常看视频，这时跳走是自己点的。
        val v = judge(
            dest = tomato,
            origin = douyin,
            cold = true,
            settled = true,
            splash = false,
            dtMs = 2_000L
        )
        assertFalse("来源已进正片，跳走多半是用户自己点的", v.shouldBack)
    }

    // ================================================================
    // 四、摇一摇广告：必须回退
    // ================================================================

    @Test
    fun 摇一摇广告跳淘宝必须回退() {
        // 真实时序：冷启动 → 2.8s 广告渲染 → 摇 1~2s → 落地页冷启动 1~2s
        // 落到淘宝时距来源前台约 5 秒。
        val v = judge(
            dest = taobao,
            origin = wakeup,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 5_000L
        )
        assertTrue("摇一摇广告跳电商必须弹回", v.shouldBack)
        assertEquals(GuardDecision.Action.BACK, v.action)
    }

    @Test
    fun 摇一摇广告跳拼多多也必须回退() {
        val v = judge(dest = "com.xunmeng.pinduoduo", origin = wakeup, dtMs = 5_500L)
        assertTrue(v.shouldBack)
    }

    @Test
    fun 跳应用商店必须回退() {
        // 商店是唯一「不限来源」的目标：正常用户不会在 App 刚起来
        // 几秒里主动去应用商店。
        val v = judge(dest = miStore, origin = wakeup, dtMs = 4_000L)
        assertTrue(v.shouldBack)
    }

    @Test
    fun 从广告高发App跳浏览器要回退() {
        val v = judge(dest = chrome, origin = douyin, dtMs = 1_000L)
        assertTrue(v.shouldBack)
    }

    @Test
    fun 从广告高发App跳快手要回退() {
        val v = judge(dest = kuaishou, origin = douyin, dtMs = 2_000L)
        assertTrue(v.shouldBack)
    }

    // ================================================================
    // 五、必须放行的场景
    // ================================================================

    @Test
    fun 桌面直接开淘宝不许回退() {
        // 没有来源 = 无从判断，一律放过
        val v = judge(dest = taobao, origin = null, dtMs = 500L)
        assertFalse(v.shouldBack)
        assertTrue(v.reason.contains("没有来源"))
    }

    @Test
    fun 内容页出来后点商品卡不许回退() {
        // test_guard.sh 场景 D：打开 App 用 5 秒再跳淘宝。
        // dt=5350ms、cold=true，和真实摇一摇的时序完全重叠，
        // 只能靠「已经扫到内容页」这个信号区分。
        val v = judge(
            dest = taobao,
            origin = wakeup,
            cold = true,
            settled = true,
            splash = false,
            dtMs = 5_350L
        )
        assertFalse("来源已经进正片，后面跳走是用户自己点的", v.shouldBack)
    }

    @Test
    fun 来源是后台恢复的不算广告跳转() {
        val v = judge(dest = taobao, origin = douyin, cold = false, dtMs = 3_000L)
        assertFalse(v.shouldBack)
    }

    @Test
    fun 超出时间窗不许回退() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = 9_000L)
        assertFalse(v.shouldBack)
        assertTrue(v.reason.contains("超出时间窗"))
    }

    @Test
    fun 冷却期内不连按返回() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = 5_000L, sinceLastBackMs = 1_000L)
        assertFalse(v.shouldBack)
        assertTrue(v.reason.contains("冷却"))
    }

    @Test
    fun 时间未知不许回退() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = -1L)
        assertFalse(v.shouldBack)
        assertTrue(v.reason.contains("时间未知"))
    }

    @Test
    fun 来源没被监测过就不拦落地页() {
        // 没监测过就不知道来源在不在开屏期，一律当作「不知道」，宁可不拦
        val v = judge(dest = taobao, origin = wakeup, splash = false, dtMs = 3_000L)
        assertFalse(v.shouldBack)
    }

    @Test
    fun 目的地不在名单里不许回退() {
        val v = judge(dest = "com.example.notepad", origin = douyin, dtMs = 1_000L)
        assertFalse(v.shouldBack)
    }

    @Test
    fun 浏览器里点链接到普通App不许回退() {
        // 浏览器当来源：用户自己点的链接
        val v = judge(dest = "com.example.notepad", origin = chrome, dtMs = 1_000L)
        assertFalse(v.shouldBack)
    }

    // ================================================================
    // 六、内容型 App 的门槛应当高于电商
    // ================================================================

    @Test
    fun 内容型App要求来源也是广告高发App() {
        // 来源是课程表（不在广告高发名单）→ 不回退
        val v = judge(dest = tomato, origin = wakeup, dtMs = 2_000L)
        assertFalse("来源不是广告高发 App，内容型目的地不该拦", v.shouldBack)

        // 来源是抖音（广告高发）→ 回退
        val v2 = judge(dest = tomato, origin = douyin, dtMs = 2_000L)
        assertTrue("来源是广告高发 App 且还在开屏期，内容型目的地也该拦", v2.shouldBack)
    }

    @Test
    fun 内容型App的时间窗比电商短() {
        assertTrue(GuardDecision.WINDOW_CONTENT_MS < GuardDecision.WINDOW_LANDING_MS)
        // 电商落地页在「来源仍在开屏期」时给 8 秒窗口
        assertTrue(judge(dest = taobao, origin = wakeup, dtMs = 5_000L).shouldBack)
        // 内容型 App 只在 4 秒内拦
        assertFalse(judge(dest = tomato, origin = douyin, dtMs = 5_000L).shouldBack)
    }

    // ================================================================
    // 九、来源没被监测过时怎么办
    //
    // 「来源仍在开屏期」这个信号来自扫描器的实际观察，只有我们对来源 App
    // 起过监测窗口才有。用户关掉「跳过开屏广告」、或者那个 App 压根没规则时，
    // 它恒为 false。如果把它当成**必要**条件，摇一摇广告就永远拦不住了。
    // ================================================================

    @Test
    fun 来源没被监测过时靠更短的时间窗兜底() {
        // 冷启动 + 还没进正片 + 落地发生在 2.5 秒内 → 仍然要拦
        val v = judge(
            dest = taobao,
            origin = wakeup,
            cold = true,
            settled = false,
            splash = false, // 没被监测过
            dtMs = 2_000L
        )
        assertTrue("来源没被监测过时，短时间窗内仍要拦广告跳转", v.shouldBack)
    }

    @Test
    fun 来源没被监测过且时间已久就不拦() {
        val v = judge(
            dest = taobao,
            origin = wakeup,
            cold = true,
            settled = false,
            splash = false,
            dtMs = 4_000L
        )
        assertFalse("没有证据就不能弹回，4 秒已经算用户自己在操作了", v.shouldBack)
    }

    @Test
    fun 来源没被监测过但已经进正片就不拦() {
        val v = judge(
            dest = taobao,
            origin = wakeup,
            cold = true,
            settled = true,
            splash = false,
            dtMs = 1_000L
        )
        assertFalse(v.shouldBack)
    }

    @Test
    fun 落地页的时间窗按证据强度分档() {
        // 有「来源仍在开屏期」的正面证据 → 宽窗口（8 秒）
        assertTrue(
            "有正面证据时窗口要够宽，否则最慢的摇一摇链路会漏掉",
            GuardDecision.WINDOW_LANDING_SPLASH_MS >= 8_000L
        )
        // 没证据 → 窄窗口
        assertTrue(
            GuardDecision.WINDOW_LANDING_MS < GuardDecision.WINDOW_LANDING_SPLASH_MS
        )
        // 实际效果：dt=7.5 秒时，有证据才拦
        assertTrue(judge(dest = taobao, origin = wakeup, splash = true, dtMs = 7_500L).shouldBack)
        assertFalse(judge(dest = taobao, origin = wakeup, splash = false, dtMs = 7_500L).shouldBack)
    }

    @Test
    fun 抖音里点商品链接跳淘宝是最容易误伤的组合() {
        // 来源是广告高发 App + 落地页 + 冷启动 + 还没进正片 + 仍在开屏期
        // ——这五项全中时判定会弹回。
        // 但真实使用里，抖音的内容页早就被扫到了（settled=true），
        // 所以关键是「来源已进正片」这一条必须可靠。
        val risky = judge(
            dest = taobao,
            origin = douyin,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 3_000L
        )
        assertTrue("证据齐全时确实该拦，这是摇一摇的形态", risky.shouldBack)

        val safe = judge(
            dest = taobao,
            origin = douyin,
            cold = true,
            settled = true, // 抖音的内容页被扫到了
            splash = false,
            dtMs = 3_000L
        )
        assertFalse("来源已进正片说明用户在正常看视频，不该弹回", safe.shouldBack)
    }

    // ================================================================
    // 七、用户自定义名单
    // ================================================================

    @Test
    fun 用户自定义名单只对广告高发来源回退() {
        assertTrue(judge(dest = "com.custom.app", origin = douyin, custom = true, dtMs = 2_000L).shouldBack)
        assertFalse(judge(dest = "com.custom.app", origin = wakeup, custom = true, dtMs = 2_000L).shouldBack)
    }

    // ================================================================
    // 八、判定结果本身的质量
    // ================================================================

    @Test
    fun 判定原因必须是人话能看懂() {
        // 原因会写进 App 内「最近记录」和 logcat，
        // 用户下次再遇到可以直接截图反馈，不用连 adb。
        val reasons = listOf(
            judge(dest = alipay, origin = wakeup),
            judge(dest = tomato, origin = tomato, changed = false),
            judge(dest = taobao, origin = null),
            judge(dest = taobao, origin = wakeup, dtMs = 5_000L)
        )
        reasons.forEach {
            assertTrue("原因不能为空", it.reason.isNotBlank())
            assertTrue("原因不该超过 60 个字：${it.reason}", it.reason.length <= 60)
        }
    }

    @Test
    fun 回退判定必须给出可核对的关键信息() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = 5_000L)
        assertTrue("原因里要能看到来源和耗时，方便排查", v.reason.contains(wakeup))
        assertTrue(v.reason.contains("5000"))
    }
}
