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
            originIsAdProne = origin != null && origin in AppLists.AD_PRONE,
            originIsShell = origin != null && AppLists.isShell(origin)
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

    // ================================================================
    // 九、多窗口 / 小窗（v1.3.5）
    // ================================================================
    //
    // 真实投诉：游戏全屏 + 番茄小说悬浮小窗，番茄从书页（ReaderActivity）
    // 退回书架（MainFragmentActivity），阅读进度丢失。
    // 小窗场景下前台包名在两个 App 之间反复横跳，旧代码每次都当「换了 App」判定。

    private fun samples(vararg pairs: Pair<String, Long>) =
        pairs.map { GuardDecision.ForegroundSample(it.first, it.second) }

    @Test
    fun 小窗里两个应用来回切换要识别成多窗口() {
        // 原神 ↔ 番茄小窗，2 秒内往返
        val s = samples(
            "com.miHoYo.Yuanshen" to 1_000L,
            "com.dragon.read" to 1_200L,
            "com.miHoYo.Yuanshen" to 1_500L
        )
        assertTrue("A→B→A 就是两块界面同时可见", GuardDecision.isMultiWindow(s, 1_500L))
    }

    @Test
    fun 真实的单向广告跳转不能被当成多窗口() {
        // 摇一摇：WakeUp → 淘宝，然后停在那儿，不会自己跳回来
        val s = samples(
            "com.taobao.taobao" to 1_000L,
            "com.taobao.taobao" to 1_200L,
            "com.taobao.taobao" to 1_500L
        )
        assertFalse("单向跳转不是多窗口，该拦的还得拦", GuardDecision.isMultiWindow(s, 1_500L))
    }

    @Test
    fun 两次往返间隔太久不算多窗口() {
        // 用户 5 秒前从 A 切到 B，现在又切回 A —— 这是正常来回用，不是小窗
        val s = samples(
            "com.miHoYo.Yuanshen" to 1_000L,
            "com.dragon.read" to 5_000L,
            "com.miHoYo.Yuanshen" to 5_200L
        )
        // 第一条已超出 2 秒窗口，窗口内只有 B→A 两条，不成往返
        assertFalse(GuardDecision.isMultiWindow(s, 5_200L))
    }

    @Test
    fun 采样太少不能判多窗口() {
        assertFalse(GuardDecision.isMultiWindow(emptyList(), 1_000L))
        assertFalse(
            GuardDecision.isMultiWindow(samples("a" to 1_000L), 1_000L)
        )
        assertFalse(
            GuardDecision.isMultiWindow(
                samples("a" to 1_000L, "b" to 1_100L),
                1_100L
            )
        )
    }

    @Test
    fun 四次往返也要能识别() {
        val s = samples(
            "game" to 1_000L,
            "com.dragon.read" to 1_100L,
            "game" to 1_200L,
            "com.dragon.read" to 1_300L
        )
        assertTrue(GuardDecision.isMultiWindow(s, 1_300L))
    }

    @Test
    fun 空包名不能参与往返判断() {
        val s = samples("" to 1_000L, "a" to 1_100L, "" to 1_200L)
        assertFalse("空包名说明事件还没稳定，不能据此判定", GuardDecision.isMultiWindow(s, 1_200L))
    }

    // ================================================================
    // 五、投诉场景 3（2026-11-20）：拼多多 / 美团被反复弹回
    //
    // 用户原话「拼多多多次拦截美团拦截」。日志：
    //   拦截跳转 pinduoduo：像广告跳转（来源 com.miui.personalassistant，1331ms，cold=true splash=true）
    //   拦截跳转 meituan：像广告跳转（来源 miui.systemui.plugin，128ms，cold=true splash=false）
    // 12:32:56 到 12:33:14 之间连拦 5 次，中间还夹着
    //   「已从 pinduoduo 退出（该页会吞返回键）」。
    // ================================================================

    private val negativeScreen = "com.miui.personalassistant"
    private val systemUiPlugin = "miui.systemui.plugin"
    private val pdd = "com.xunmeng.pinduoduo"
    private val meituan = "com.sankuai.meituan"

    @Test
    fun 从负一屏点开拼多多不许回退() {
        // 负一屏（智能助理）作为来源时，三个「像广告」的信号全是假的：
        //   cold=true  —— 它本来就不是 App，calcCold 对 passThrough 恒为 true
        //   settled=false —— 扫描链压根不扫它
        //   dt 很短    —— 用户点完卡片 1.3 秒就到了
        val v = judge(
            dest = pdd,
            origin = negativeScreen,
            cold = true,
            settled = false,
            splash = true,
            dtMs = 1_331L
        )
        assertFalse("负一屏不是 App，是找 App 的地方", v.shouldBack)
        assertTrue(v.reason.contains("负一屏"))
    }

    @Test
    fun 从控制中心插件跳美团不许回退() {
        // 日志原样：128ms。系统 UI 插件的窗口一闪就被当成了来源。
        val v = judge(
            dest = meituan,
            origin = systemUiPlugin,
            cold = true,
            settled = false,
            splash = false,
            dtMs = 128L
        )
        assertFalse(v.shouldBack)
    }

    @Test
    fun 来源停留不足五百毫秒算窗口抖动() {
        // 真实的摇一摇链路（打开 App → 广告渲染 → 摇 → 落地）最快也要 1 秒。
        // 128ms 只可能是切换动画的中间帧，不能据此按返回。
        val v = judge(dest = meituan, origin = douyin, cold = true, dtMs = 128L)
        assertFalse(v.shouldBack)
        assertTrue(v.reason.contains("抖动"))
    }

    @Test
    fun 真正的摇一摇仍然要拦() {
        // 底线：别为了修误拦把该拦的也放过了。
        // 冷启动 + 开屏期 + 2 秒落地 = 教科书式的摇一摇广告。
        val v = judge(dest = taobao, origin = douyin, cold = true, settled = false, splash = true, dtMs = 2_000L)
        assertTrue("这条是真广告，必须拦", v.shouldBack)
    }

    // ================================================================
    // 六、重复拦截熔断
    //
    // 名单再全也总有想不到的系统入口（各 ROM 负一屏、小组件、语音助手）。
    // 连续三次就是用户意图，这是最后一道保险。
    // ================================================================

    @Test
    fun 连续三次拦截同一目的地就熔断() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        assertFalse("第 1 次只记账", g.noteBack(meituan, t0))
        assertFalse("第 2 次还拦", g.noteBack(meituan, t0 + 3_000L))
        assertTrue("第 3 次就该放行了", g.noteBack(meituan, t0 + 6_000L))
    }

    @Test
    fun 熔断期内同一目的地直接放行() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        repeat(3) { g.noteBack(meituan, t0 + it * 1_000L) }
        assertTrue(g.isMuted(meituan, t0 + 3_000L))
        assertTrue(g.muteLeftMs(meituan, t0 + 3_000L) > 0L)
    }

    @Test
    fun 熔断只针对同一个目的地() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        repeat(3) { g.noteBack(meituan, t0 + it * 1_000L) }
        assertFalse("别的地儿该拦还得拦", g.isMuted(taobao, t0 + 3_000L))
    }

    @Test
    fun 熔断到期后恢复拦截() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        repeat(3) { g.noteBack(meituan, t0 + it * 1_000L) }
        // 熔断是从**第 3 次拦截**（t0+2s）开始算的，所以到期时刻要再加 2 秒
        val after = t0 + 2_000L + GuardDecision.REPEAT_MUTE_MS + 1_000L
        assertFalse("5 分钟过了，可以重新拦", g.isMuted(meituan, after))
    }

    @Test
    fun 超过一分钟的零散拦截不算连续() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        assertFalse(g.noteBack(meituan, t0))
        // 两次之间隔了 40 秒，累计超过 1 分钟窗口 → 重新计数
        assertFalse(g.noteBack(meituan, t0 + 40_000L))
        assertFalse(g.noteBack(meituan, t0 + 110_000L))
    }

    @Test
    fun 不同目的地穿插出现不算连续() {
        val g = GuardDecision.RepeatGuard()
        val t0 = 10_000L
        assertFalse(g.noteBack(meituan, t0))
        assertFalse(g.noteBack(pdd, t0 + 1_000L)) // 中间换了目的地，商家计数归零
        assertFalse(g.noteBack(meituan, t0 + 2_000L))
        assertFalse(g.noteBack(meituan, t0 + 3_000L))
        assertFalse("中间被打断过，要重新数到三次", g.isMuted(meituan, t0 + 4_000L))
    }

    // ================================================================
    // 七、systemui：透明层，但「最近任务 / 通知栏」是用户切页的地方（v1.3.10 · R1）
    //
    // 旧代码 `pkg == "com.android.systemui"` 直接 return，来源上下文原样留着。
    // 于是「在番茄小说里按最近任务 → 点淘宝」会被判成
    // 「番茄还在开屏、用户 1 秒后就到了淘宝」→ 弹回。
    // 判据只能靠类名：状态栏上的通知（heads-up）绝不能算用户切页，
    // 否则正在播广告时来一条通知，来源就被清空 → 该拦的不拦。
    // ================================================================

    @Test
    fun 最近任务界面算用户切页() {
        assertTrue(
            GuardDecision.isUserSurface("com.android.systemui.recents.RecentsActivity")
        )
        // 各家 ROM 叫法不同，overview 也常见
        assertTrue(GuardDecision.isUserSurface("com.miui.recents.OverviewActivity"))
    }

    @Test
    fun 下拉通知栏算用户切页() {
        assertTrue(
            GuardDecision.isUserSurface(
                "com.android.systemui.shade.NotificationShadeWindowView"
            )
        )
        assertTrue(
            GuardDecision.isUserSurface("com.android.systemui.qs.QSContainerImpl")
        )
    }

    @Test
    fun 弹出通知不算用户切页() {
        // heads-up 经常在别的 App 放着广告时出现（音乐控制、下载完成）。
        // 把它当成「用户切页」会把正在播广告的来源清掉 → 漏拦。
        assertFalse(
            GuardDecision.isUserSurface(
                "com.android.systemui.statusbar.phone.HeadsUpNotificationView"
            )
        )
    }

    @Test
    fun 状态栏本身不算用户切页() {
        // 这条是刻意与报告不同的地方：报告把 statusbar 写进了正向名单，
        // 但 heads-up 通知的容器就住在 statusbar 包里，正向匹配会把
        // `StatusBarWindowView` 也当成用户切页。宁可不认，也不能误清来源。
        assertFalse(
            GuardDecision.isUserSurface("com.android.systemui.statusbar.phone.StatusBar")
        )
    }

    @Test
    fun 拿不到类名时保持旧行为() {
        // 轮询路径没有 className。这时宁可什么都不做（保持 v1.3.9 行为），
        // 也不要基于猜测清空来源。
        assertFalse(GuardDecision.isUserSurface(null))
        assertFalse(GuardDecision.isUserSurface(""))
    }

    @Test
    fun 回滚开关存在() {
        // 真机上出问题时要能一行改回去，所以这个常量必须是真的能被关掉
        assertTrue(
            "保留这个常量是为了回滚，不能删",
            GuardDecision.GUARD_SYSTEMUI_AS_USER_SURFACE
        )
    }

    // ================================================================
    // 八、影子模式（v1.3.10 · R2）
    //
    // 影子模式的唯一要求：判定照跑，但**绝不动手**。
    // 一旦它顺手动了 lastBackAt / restoringPkg / RepeatGuard，
    // 跑出来的就不是「现行为」，拿它当基线定阈值等于用错数据。
    // ================================================================

    @Test
    fun 影子模式下该拦也只记录() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = 5_000L)
        assertEquals("前提：这条判定本来该拦", GuardDecision.Action.BACK, v.action)
        assertEquals(
            "影子模式下只能记录，不能动手",
            GuardDecision.Disposition.SHADOW,
            GuardDecision.disposition(v, shadow = true)
        )
        assertTrue(
            "影子模式绝不能映射到 ACT",
            GuardDecision.disposition(v, shadow = true) != GuardDecision.Disposition.ACT
        )
    }

    @Test
    fun 正常模式下该拦就动手() {
        val v = judge(dest = taobao, origin = wakeup, dtMs = 5_000L)
        assertEquals(
            GuardDecision.Disposition.ACT,
            GuardDecision.disposition(v, shadow = false)
        )
    }

    @Test
    fun 不该拦时影子模式也无事可做() {
        val v = judge(dest = alipay, origin = wakeup, dtMs = 800L)
        assertEquals(GuardDecision.Disposition.NONE, GuardDecision.disposition(v, false))
        assertEquals(GuardDecision.Disposition.NONE, GuardDecision.disposition(v, true))
    }
}
