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
     * 「在来源 App 里待过」的最短时长：500ms。
     *
     * 比这更短的时间不可能是「用户打开 App → 开屏广告渲染 → 摇一摇 → 落地」，
     * 那条链路最快也要 1 秒以上。500ms 以内发生的「跳转」只有一种解释：
     * **前台包名采样抖了一下**——系统 UI 插件、负一屏卡片、切换动画的中间帧
     * 被当成了前台 App，紧接着真实 App 出现。
     *
     * 日志实证（v1.3.7，2026-11-20）：
     * `拦截跳转 meituan：像广告跳转（来源 miui.systemui.plugin，128ms）`
     * ——128 毫秒就"跳"到美团，然后按返回把用户弹回去。
     *
     * 所以这类事件直接放过：漏拦一次抖动没有任何代价，
     * 误拦一次就是用户点开的美团没了。
     */
    const val MIN_DWELL_MS = 500L

    /**
     * 是否把 `com.android.systemui` 的某些界面当成「用户切 App 的地方」。
     *
     * 关掉它就退回 v1.3.9 的行为（systemui 一律当透明层，什么都不做），
     * 用来在真机上快速回滚。
     */
    const val GUARD_SYSTEMUI_AS_USER_SURFACE = true

    /**
     * systemui 里属于「用户自己切 App」的界面。
     *
     * 用户的动作是明确的：按最近任务键、下拉通知栏、拉控制中心、长按电源键。
     * 从这些界面点开一个 App，和从桌面点开它是同一件事——上一个 App 的
     * 上下文早已失效，必须清空来源，否则「冷启动 + 没进正片」会让它被
     * 当成完美假来源（就是负一屏那次事故的同一形态）。
     *
     * 刻意**不**包含 `statusbar`：状态栏本身也是 heads-up 通知的宿主，
     * 而 heads-up 经常在别的 App 播放时出现（音乐控制、下载完成）。
     * 把 statusbar 当成「用户切页」，会把正在播广告的来源清掉 → 漏拦。
     *
     * `qs\.` 是控制中心面板的真实类名（`com.android.systemui.qs.QSContainerImpl`），
     * 不是 `quicksettings`——这两个都写上，是因为不同 ROM 两者都出现过。
     * 只在 `pkg == com.android.systemui` 时才会用这个正则，所以前缀够窄。
     */
    private val USER_SURFACE = Regex(
        "(?i)recents|overview|notificationshade|quicksettings|qs\\.|globalscreen|globalsearch"
    )

    /**
     * 反向否决：这些是**通知本体**，不是用户切页。
     *
     * 早期版本把 `statusbar` 写进正向名单，结果一条音乐通知就能把来源清空；
     * 现在正向名单里已经没有它，这里再兜一层，防止有人为了「多拦」
     * 把匹配放松到 `statusbar`。
     */
    private val HEADS_UP = Regex("(?i)heads?up|notificationrow|notificationview")

    /**
     * 这个 systemui 界面是不是「用户在这里切 App」。
     *
     * @param className 窗口事件里的 `className`。轮询路径拿不到，传 null，
     *   此时一律返回 false —— 宁可不改判定（保持 v1.3.9 行为），
     *   也不要基于猜测清空来源。
     */
    fun isUserSurface(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        if (HEADS_UP.containsMatchIn(className)) return false
        return USER_SURFACE.containsMatchIn(className)
    }


    /**
     * 同一个目的地被连续拦截多少次，就认定「用户确实想去」，开始熔断。
     */
    const val REPEAT_THRESHOLD = 3

    /** 统计「连续拦截同一目的地」的时间窗：1 分钟 */
    const val REPEAT_WINDOW_MS = 60_000L

    /** 熔断时长：5 分钟内不再拦这个目的地 */
    const val REPEAT_MUTE_MS = 300_000L

    /**
     * 两次回退之间的最小间隔，避免连按
     */
    const val BACK_COOLDOWN_MS = 3_000L

    /**
     * 多窗口（小窗 / 分屏 / 游戏悬浮窗）的识别窗口。
     *
     * 这类场景下**两块界面同时可见**，前台包名会在两个 App 之间反复横跳
     * （游戏 ↔ 小窗里的番茄小说）。每一次都像一次「刚换了 App」，
     * 旧代码于是每次都重判一遍，把「切窗口」当成「跳转」。
     *
     * 判据：2 秒内出现过 A → B → A 的往返（A、B 都不是桌面）。
     * 真实的广告跳转是**单向**的（A → 广告落地页，然后停在那儿），
     * 不会在两秒内自己跳回来，所以这条判据不会挡住该拦的广告。
     */
    const val FLIP_WINDOW_MS = 2_000L

    /** 一次前台采样：谁、什么时候 */
    data class ForegroundSample(val pkg: String, val at: Long)

    /**
     * 是否处于多窗口 / 小窗场景。
     *
     * 只要最近 [FLIP_WINDOW_MS] 内出现过 A → B → A 的往返，就判定为
     * 「两块界面同时可见」，此时一律不参与防跳转判定。
     *
     * 为什么宁可漏拦也要这样判：
     * 小窗场景下用户是在**同时用两个 App**，任何一次「弹回去」都会
     * 打断可见界面上的操作（实测就是番茄小说的阅读页被退成书架，
     * 读者连看到第几章都丢了）。漏拦的代价是广告多显示两秒，
     * 误拦的代价是用户正在看的内容消失——不对等。
     *
     * @param recent 最近的前台采样，按时间顺序（旧 → 新）
     * @param now 当前时刻（uptimeMillis）
     */
    fun isMultiWindow(recent: List<ForegroundSample>, now: Long): Boolean {
        if (recent.size < 3) return false
        val inWindow = recent.filter { now - it.at <= FLIP_WINDOW_MS }
        if (inWindow.size < 3) return false
        for (i in 2 until inWindow.size) {
            val a = inWindow[i - 2].pkg
            val b = inWindow[i - 1].pkg
            val c = inWindow[i].pkg
            if (a.isNotEmpty() && b.isNotEmpty() && a == c && a != b) return true
        }
        return false
    }

    /** 动作 */
    enum class Action {
        /** 该弹回去 */
        BACK,

        /** 不该动 */
        KEEP
    }

    /**
     * 「重复拦截」熔断器。
     *
     * ## 为什么需要它
     *
     * 判定逻辑再小心，也总有我没想到的系统入口（各 ROM 的负一屏、
     * 桌面小组件、车机投屏、语音助手的"打开 XX"……）。这类入口一旦被当成
     * 广告来源，用户就会陷入「点一次、弹回一次、再点一次、再弹回一次」的地狱。
     *
     * 2026-11-20 的真实日志：12:32:56 到 12:33:14 之间，用户点开拼多多
     * 被连拦 5 次，中间还有 `已从 pinduoduo 退出（该页会吞返回键）`。
     * 用户当时的原话是「拼多多多次拦截美团拦截」。
     *
     * ## 判据
     *
     * 1 分钟内同一个目的地被拦到第 [threshold] 次，就认定「用户确实想进去」，
     * 熔断 [muteMs] 分钟不再拦它。
     *
     * ## 为什么宁可放过
     *
     * 熔断的代价：一个真广告可能多显示 5 分钟。
     * 不熔断的代价：用户反复打不开自己要用的 App，直接卸载。
     * 这个项目一贯的选择是前者。
     *
     * 注意熔断状态**不能**被「回到桌面」清掉——它记的是用户意图，
     * 跨桌面依然有效。进程被杀才会失效，那是可以接受的。
     */
    class RepeatGuard(
        private val threshold: Int = REPEAT_THRESHOLD,
        private val windowMs: Long = REPEAT_WINDOW_MS,
        private val muteMs: Long = REPEAT_MUTE_MS
    ) {

        private var streakPkg: String? = null
        private var streakStart = 0L
        private var streakCount = 0

        private var mutedPkg: String? = null
        private var mutedUntil = 0L

        /** 该目的地此刻是否处于熔断期 */
        @Synchronized
        fun isMuted(dest: String, now: Long): Boolean {
            val muted = mutedPkg ?: return false
            if (now < mutedUntil && dest == muted) return true
            // 到期，顺手清掉，免得下次还要再比一遍
            if (now >= mutedUntil) {
                mutedPkg = null
                mutedUntil = 0L
            }
            return false
        }

        /** 熔断还会持续多久（毫秒，0 = 没在熔断） */
        @Synchronized
        fun muteLeftMs(dest: String, now: Long): Long {
            val muted = mutedPkg ?: return 0L
            if (dest != muted || now >= mutedUntil) return 0L
            return mutedUntil - now
        }

        /**
         * 记一次拦截。
         *
         * @return true 表示**这一次刚好触发熔断**，调用方应该记一条日志
         *   告诉用户「我不再拦这个了」，否则用户会以为软件坏了。
         */
        @Synchronized
        fun noteBack(dest: String, now: Long): Boolean {
            if (dest != streakPkg || now - streakStart > windowMs) {
                streakPkg = dest
                streakStart = now
                streakCount = 1
                return false
            }
            streakCount++
            if (streakCount < threshold) return false
            mutedPkg = dest
            mutedUntil = now + muteMs
            // 清掉计数：熔断到期后重新开始数，不能一到期就立刻又熔断一轮
            streakPkg = null
            streakStart = 0L
            streakCount = 0
            return true
        }

        /**
         * 直接熔断某个目的地。
         *
         * 与 [noteBack] 的区别：那是**推断**（连着拦了三次 = 用户想去），
         * 这是**用户明确表态**（他点了「撤销」）。所以时长由调用方给，
         * 通常远长于 [REPEAT_MUTE_MS]。
         */
        @Synchronized
        fun mute(dest: String, now: Long, ms: Long = muteMs) {
            if (dest.isEmpty()) return
            mutedPkg = dest
            mutedUntil = now + ms
            // 计数清零：熔断到期后重新开始数，不能一到期就立刻又熔断一轮
            streakPkg = null
            streakStart = 0L
            streakCount = 0
        }

        /** 单元测试用：清空全部状态 */
        @Synchronized
        fun clear() {
            streakPkg = null
            streakStart = 0L
            streakCount = 0
            mutedPkg = null
            mutedUntil = 0L
        }
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
     * @param originIsShell 来源是不是系统外壳（桌面 / 负一屏 / 控制中心插件 /
     *   全局搜索）。这类包**不是 App，是找 App 的地方**，作为来源没有任何
     *   判定意义——用户从负一屏点开拼多多，和从桌面点开拼多多是同一件事。
     *   见 AppLists.SHELL。
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
        val originIsAdProne: Boolean,
        val originIsShell: Boolean = false
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
     * 判定之后该怎么处置。
     *
     * 影子模式的全部约束都收在这一个函数里：**它只可能返回 [Disposition.SHADOW]
     * 或 [Disposition.NONE]，永远回不到 [Disposition.ACT]**。
     *
     * 为什么要把这件事单独写出来、还要测：
     * 影子模式一旦顺手动了状态（`lastBackAt`、`restoringPkg`、`RepeatGuard`），
     * 它跑出来的就不是「现行为」而是「现行为 + 影子污染」，拿它当基线定阈值
     * 等于用错数据做决定。这个项目已经四次栽在「看起来只改了一点点」上。
     */
    enum class Disposition {
        /** 本来就不该拦 */
        NONE,

        /** 该拦，但开着影子模式：只记录，不动手、不记账 */
        SHADOW,

        /** 真的动手 */
        ACT
    }

    fun disposition(v: Verdict, shadow: Boolean): Disposition = when {
        !v.shouldBack -> Disposition.NONE
        shadow -> Disposition.SHADOW
        else -> Disposition.ACT
    }

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

        // ③' 来源是系统外壳（桌面 / 负一屏 / 控制中心插件 / 全局搜索）。
        //
        //     这类包不是 App，是**用户找 App 的地方**。从这里点开任何东西
        //     都是明确意图，不能当成广告跳转。
        //
        //     v1.3.8 的根因就在这里：负一屏 com.miui.personalassistant 作为来源时，
        //     它永远「刚冷启动」（calcCold 对 passThrough 返回 true）、
        //     永远「没进正片」（扫描链压根不扫它）、而用户 1.3 秒后就点开了
        //     拼多多——三个条件正好凑齐「来源还在开屏、摇一摇落地」的判据。
        //     日志里 `cold=true splash=true` 连拦 5 次就是这么来的。
        //
        //     JumpGuard 已经在入口处把这类包 reset 掉了，这里是第二道防线，
        //     防止以后有别的调用点绕过那一层。
        if (c.originIsShell) {
            return keep("来源是桌面/负一屏，不是真实 App")
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

        // ⑥' 在来源 App 里只待了几百毫秒 = 前台包名抖动，不是一次真实跳转。
        //    见 [MIN_DWELL_MS]：摇一摇广告不可能这么快落地，
        //    这种事件多半是系统 UI 插件/切换动画的中间帧被当成了来源。
        if (c.dtMs < MIN_DWELL_MS) {
            return keep("在来源 App 停留过短（${c.dtMs}ms），属窗口抖动")
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
