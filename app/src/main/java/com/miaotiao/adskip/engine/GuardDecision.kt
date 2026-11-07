package com.miaotiao.adskip.engine

/**
 * 防跳转（回退）判定的**纯逻辑**部分。
 *
 * 为什么要单独抽出来：
 *
 * 1. 回退误伤是这类工具最难缠的 bug——「什么时候该按返回」依赖一堆时序条件，
 *    真机上几乎无法稳定复现（用户自己都说不出来）。以前这套判定散在
 *    `JumpGuard.onForeground` 里，和 Android 生命周期、SharedPreferences、
 *    SystemClock 搅在一起，除了拿真机试没别的办法验证。
 * 2. 抽成纯函数后，同一套判定可以在 JVM 上跑单元测试，把「哪些组合该回退、
 *    哪些绝对不该回退」写成断言。以后改判定逻辑，CI 一跑就知道有没有把
 *    「用户主动打开某个 App」误判成「广告跳转」。
 * 3. 这个文件**不许 import 任何 android.* 的东西**，否则单元测试就跑不了。
 *
 * 判定的输入是「一次前台切换」的完整快照，输出是一个明确的动作 + 原因。
 * 原因会写进日志和 App 内的「最近记录」，用户下次再遇到可以直接截图反馈，
 * 不用连 adb（logcat 在有些机器上会被莫名清空，见 README 的已知问题）。
 */
object GuardDecision {

    /** 浏览器类目的地：正常点链接也常在这个时间内，窗口最短 */
    const val WINDOW_BROWSER_MS = 1_800L

    /** 应用商店：给 5 秒，「检查更新」也会跳商店 */
    const val WINDOW_STORE_MS = 5_000L

    /**
     * 广告落地页：来源**仍在开屏期**时给 8 秒。
     *
     * 摇一摇广告的真实时序比想象长：冷启动 → 约 2.8 秒广告才渲染出来 →
     * 用户摇手机 1~2 秒 → 落地页冷启动 1~2 秒。落到 App 时距来源 App
     * 前台已经 5 秒上下，最慢能到 8 秒。
     *
     * 之所以敢给 8 秒（内置规则的监测窗口正好也是 8 秒），是因为这时
     * 我们手里有**正面证据**：来源 App 确实还在开屏阶段
     * （见 originShowingSplash）。有证据的判定才配得上宽窗口。
     */
    const val WINDOW_LANDING_SPLASH_MS = 8_000L

    /**
     * 广告落地页：其余情况 5 秒。
     *
     * 没有「来源仍在开屏期」这个证据时，判定就该保守。
     * 实际上这一档还有一道更严的闸（见 WINDOW_UNWATCHED_MS），
     * 这里只是给个兜底上限。
     */
    const val WINDOW_LANDING_MS = 5_000L

    /**
     * 内容型 App（抖音/快手/小红书/番茄小说/各视频）：4 秒。
     *
     * 比电商落地页短一半。用户点进这些 App 基本都是「想看」，
     * 误弹回的代价远大于漏弹回，宁可漏。
     */
    const val WINDOW_CONTENT_MS = 4_000L

    /** 用户自定义名单：2.5 秒 */
    const val WINDOW_OTHER_MS = 2_500L

    /**
     * 来源 App **没有被开屏监测覆盖过**时的降级窗口：2.5 秒。
     *
     * 为什么要留这条退路：
     * 「来源仍在开屏阶段」这个信号，来自扫描器的实际观察，只有我们对
     * 来源 App 起过监测窗口才有。用户把「跳过开屏广告」关掉、或者那个
     * App 压根没被监测过时，这个信号永远是 false。
     *
     * 如果把它当成**必要条件**，防跳转对电商落地页就会彻底失效——
     * 用户明明开着「防跳转」，摇一摇广告拉起淘宝却不再弹回。
     *
     * 所以降级成一条更严的时间线：来源是冷启动、没进正片、
     * 而且落地发生得**非常快**（2.5 秒内）。正常用户打开 App 后
     * 2.5 秒内自己点开淘宝的极少，这条线基本只会被广告触发。
     */
    const val WINDOW_UNWATCHED_MS = 2_500L

    /**
     * 两次回退之间的最小间隔，避免连按
     */
    const val BACK_COOLDOWN_MS = 3_000L

    /** 动作 */
    enum class Action {
        /** 该弹回去 */
        BACK,

        /** 不该动 */
        KEEP
    }

    /**
     * 一次前台切换的判定输入。
     *
     * @param destPkg 新前台包名
     * @param destChanged 目的地是不是**刚换**的包。同一个 App 内部切 Activity
     *   （番茄小说打开个人主页、抖音进评论区）必须传 false，否则每次窗口事件
     *   都会重判一次，dt 用的是上一个 App 的时间戳 → 必误伤。
     * @param originPkg 来源包名（上一个真正的前台 App），null = 不知道
     * @param originCold 来源 App 是不是冷启动进来的
     * @param originSettled 来源 App 是否已经「进入正片」（扫到内容页，
     *   或开屏期正常结束）。已经进入正片说明用户是在正常使用它，
     *   这时跳走多半是用户自己点的，不该弹回。
     * @param originShowingSplash 来源 App 是否**仍有可能停在开屏阶段**。
     *   这是区分「广告摇一摇跳走」和「用户手动切 App」最硬的信号：
     *   摇一摇只可能发生在开屏广告还在播的时候。
     *
     *   它来自扫描器的实际观察，不是时间猜测：
     *   · JumpGuard.noteSplashStarted —— 来源确实被开屏监测覆盖过
     *   · JumpGuard.noteContentPage —— 扫到内容页，广告已结束
     *   两者合起来就是「监测过、且至今没扫到内容页」。
     *   压根没被监测过的来源一律传 false：不知道就当不知道，宁可不拦。
     *
     * @param dtMs 距来源 App 进入前台过了多久，负数 = 未知
     * @param sinceLastBackMs 距上一次回退过了多久
     * @param destIsStore 目的地是不是应用商店
     * @param destIsBrowser 目的地是不是浏览器
     * @param destIsLanding 目的地是不是电商类广告落地页（淘宝/京东/拼多多…）
     * @param destIsContent 目的地是不是内容型 App（抖音/快手/小红书/番茄小说…）
     * @param destIsCustom 目的地是不是用户自己加进「防跳转名单」的包
     * @param destIsUserDriven 目的地是不是用户主动打开的 App
     *   （微信/QQ/支付宝/钉钉……）。NFC 唤起支付宝就是这种。
     * @param originIsUserDriven 来源是不是用户主动打开的 App
     * @param originIsAdProne 来源是不是广告高发 App
     */
    data class Context(
        val destPkg: String,
        val destChanged: Boolean,
        val originPkg: String?,
        val originCold: Boolean,
        val originSettled: Boolean,
        val originShowingSplash: Boolean,
        val dtMs: Long,
        val sinceLastBackMs: Long,
        val destIsStore: Boolean,
        val destIsBrowser: Boolean,
        val destIsLanding: Boolean,
        val destIsContent: Boolean,
        val destIsCustom: Boolean,
        val destIsUserDriven: Boolean,
        val originIsUserDriven: Boolean,
        val originIsAdProne: Boolean
    )

    /**
     * 判定结果。
     *
     * @param reason 人话版原因，写进日志和 App 内记录
     */
    data class Verdict(
        val action: Action,
        val reason: String,
        val windowMs: Long = 0L
    ) {
        val shouldBack: Boolean get() = action == Action.BACK
    }

    private fun keep(reason: String, windowMs: Long = 0L) =
        Verdict(Action.KEEP, reason, windowMs)

    private fun back(reason: String, windowMs: Long) =
        Verdict(Action.BACK, reason, windowMs)

    /**
     * 核心判定。
     *
     * 判定顺序是刻意排的：**先排除所有「确定不是广告跳转」的情况**，
     * 最后才看「像不像广告跳转」。任何一条排除规则都足以否决回退，
     * 所以顺序上宁可把排除项都放在前面。
     */
    fun decide(c: Context): Verdict {
        // ① 同一个 App 内部切页面（个人主页、评论区、详情页）。
        //    这一条能挡掉「打开番茄小说个人主页时回退」——
        //    包名没变，根本不是一次跳转。
        if (!c.destChanged) {
            return keep("同一应用内切换，不是跳转")
        }

        // ①' 来源和目的地是同一个 App —— 兜底防线。
        //    正常情况下 ① 已经拦掉了，但两处判断来自不同的数据源
        //    （一个来自「包名变没变」，一个来自「来源快照」），
        //    万一不一致，以「没换 App」为准，绝不弹回。
        if (c.originPkg != null && c.originPkg == c.destPkg) {
            return keep("来源和目的地是同一个 App")
        }

        // ② 目的地是用户主动打开的 App（微信/QQ/支付宝/钉钉……）。
        //    NFC 唤起支付宝就落在这里：这是用户自己的意图，
        //    绝不能因为「时间上碰巧落在窗口里」就弹回去。
        if (c.destIsUserDriven) {
            return keep("目的地是用户主动打开的 App")
        }

        // ③ 没有来源就无从判断是「从哪跳过来的」，一律放过。
        if (c.originPkg.isNullOrEmpty()) {
            return keep("没有来源 App")
        }

        // ④ 来源是用户主动操作的 App（微信里点分享链接、QQ 里打开小程序）。
        //    那些跳转本来就是用户点的。
        if (c.originIsUserDriven) {
            return keep("来源是用户主动操作的 App")
        }

        // ⑤ 目的地不在需要拦截的名单里。
        if (!c.destIsStore && !c.destIsBrowser && !c.destIsLanding &&
            !c.destIsContent && !c.destIsCustom
        ) {
            return keep("目的地不需要拦截")
        }

        // ⑥ 时间未知（刚开机、进程刚起、事件乱序）→ 不猜。
        if (c.dtMs < 0L) {
            return keep("时间未知")
        }

        // ⑦ 按目的地类型选时间窗。
        //    落地页分两档：有「来源仍在开屏期」这个正面证据时给宽窗口，
        //    没有证据就收紧。
        val window = when {
            c.destIsBrowser -> WINDOW_BROWSER_MS
            c.destIsStore -> WINDOW_STORE_MS
            c.destIsLanding ->
                if (c.originShowingSplash) WINDOW_LANDING_SPLASH_MS else WINDOW_LANDING_MS
            c.destIsContent -> WINDOW_CONTENT_MS
            else -> WINDOW_OTHER_MS
        }

        // ⑧ 超出时间窗：用户正常用 App 之后自己点进去的，不该弹回。
        if (c.dtMs > window) {
            return keep("超出时间窗 ${c.dtMs}ms > ${window}ms", window)
        }

        // ⑨ 冷却：防止连按返回。
        if (c.sinceLastBackMs < BACK_COOLDOWN_MS) {
            return keep("冷却中（距上次回退 ${c.sinceLastBackMs}ms）", window)
        }

        // ⑩ 来源是否「像广告」——这一步才是真正的实质判断。
        val originOk = when {
            // 应用商店：正常用户不会在 App 刚起来 5 秒内自己点进商店，
            // 误触概率极高，不限来源。
            c.destIsStore -> true

            // 电商落地页：必须是「来源 App 刚打开就被弹走」。
            // 三个条件缺一不可：
            //   · originCold —— 来源是冷启动进来的
            //   · !originSettled —— 来源还没进入正片（否则是用户自己在用）
            //   · originShowingSplash —— 来源仍在开屏阶段
            // 第三个条件是「摇一摇只可能发生在开屏广告还在播的时候」。
            //
            // 但它不能是**唯一**的硬条件：来源没被监测过时它恒为 false，
            // 那样「用户开着防跳转、摇一摇广告拉起淘宝却不弹回」就成了
            // 功能失效。所以留一条降级路径：没监测过来源时，
            // 改用一条更严的时间线（落地发生在冷启动后 2.5 秒内）来判。
            c.destIsLanding -> c.originCold && !c.originSettled &&
                (c.originShowingSplash || c.dtMs <= WINDOW_UNWATCHED_MS)

            // 内容型 App（抖音/番茄/小红书/视频）：门槛再高一档。
            // 用户点进这些 App 就是想看，误弹回代价大，
            // 所以额外要求来源本身就是广告高发 App。
            c.destIsContent -> c.originCold && !c.originSettled &&
                c.originShowingSplash && c.originIsAdProne

            // 浏览器 / 用户自定义名单：只对广告高发来源回退。
            else -> c.originIsAdProne
        }

        if (!originOk) {
            return keep(
                "来源不像广告（cold=${c.originCold} settled=${c.originSettled} " +
                    "splash=${c.originShowingSplash} adProne=${c.originIsAdProne}）",
                window
            )
        }

        return back(
            "像广告跳转（来源 ${c.originPkg}，${c.dtMs}ms，cold=${c.originCold} " +
                "splash=${c.originShowingSplash}）",
            window
        )
    }
}
