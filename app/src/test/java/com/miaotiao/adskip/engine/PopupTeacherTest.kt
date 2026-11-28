package com.miaotiao.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「学弹窗」的判定逻辑。
 *
 * 坐标数字都照着真机抓下来的那棵节点树写：屏幕 1440×3200、
 * 卡片 [120,1377][1320,1909]、右上角关闭 [1155,1392][1305,1542]。
 * 也就是 adsample 里的 variant 951（只有自绘叉号）和 950（有文字按钮）。
 */
class PopupTeacherTest {

    private val screenArea = 1440L * 3200L

    private fun cand(
        l: Int, t: Int, r: Int, b: Int,
        text: String = "", desc: String = "", id: String = "", clickable: Boolean = false
    ) = PopupTeacher.Cand(l, t, r, b, text, desc, id, clickable)

    // ---------------------------------------------------------------- 选点

    @Test
    fun `点在按钮上时选面积最小的那个`() {
        val card = cand(120, 1377, 1320, 1909, text = "会员特惠")
        val title = cand(210, 1467, 1230, 1561, text = "会员特惠")
        val close = cand(1155, 1392, 1305, 1542, desc = "关闭", clickable = true)

        assertEquals(close, PopupTeacher.pickAt(listOf(card, title, close), 1230, 1467, screenArea))
    }

    @Test
    fun `点在卡片空白处时不会选中卡片`() {
        // 卡片 1200×532 占屏 13.9%，超过「目标按钮」12.5% 的上限
        val card = cand(120, 1377, 1320, 1909, text = "会员特惠")
        assertNull(PopupTeacher.pickAt(listOf(card), 200, 1880, screenArea))
    }

    @Test
    fun `点在什么都没有的地方返回 null`() {
        val close = cand(1155, 1392, 1305, 1542, desc = "关闭", clickable = true)
        assertNull(PopupTeacher.pickAt(listOf(close), 100, 100, screenArea))
    }

    @Test
    fun `点在标题文字上返回 null 而不是选中标题`() {
        // 真机事故：用户戳右上角的裸叉号，叉号没标签所以不在候选里，
        // 手指底下的标题「会员特惠」面积又小，于是被当成目标，
        // 生成 when:会员特惠 → action:会员特惠，执行时去点卡片，直接跳广告页。
        val title = cand(210, 1467, 1230, 1561, text = "会员特惠", clickable = false)
        assertNull(PopupTeacher.pickAt(listOf(title), 1229, 1473, screenArea))
    }

    @Test
    fun `两个重叠节点取小的`() {
        val big = cand(200, 1700, 1240, 1860, text = "立刻开通", clickable = true)
        val small = cand(1000, 1710, 1100, 1810, text = "叉", clickable = true)
        assertEquals(small, PopupTeacher.pickAt(listOf(big, small), 1050, 1760, screenArea))
    }

    @Test
    fun `不可点的文字按钮不会被选中`() {
        // 有些关闭键靠父容器响应触摸，节点自身 clickable=false。
        // 这种宁可不学：选中它之后 Access.click 会顺着父链点卡片。
        val label = cand(210, 1745, 1230, 1819, text = "以后再说", clickable = false)
        assertNull(PopupTeacher.pickAt(listOf(label), 700, 1780, screenArea))
    }

    // ---------------------------------------------------------------- 定位写法

    @Test
    fun `有控件 id 时优先用 id`() {
        val c = cand(0, 0, 40, 40, text = "关闭", desc = "关闭", id = "com.x:id/iv_close")
        assertEquals("com.x:id/iv_close", PopupTeacher.locatorFor(c))
    }

    @Test
    fun `没有 id 时用内容描述`() {
        val c = cand(0, 0, 40, 40, desc = "关闭弹窗")
        assertEquals("关闭弹窗", PopupTeacher.locatorFor(c))
    }

    @Test
    fun `没有 id 和描述时用文字`() {
        val c = cand(0, 0, 40, 40, text = "以后再说")
        assertEquals("以后再说", PopupTeacher.locatorFor(c))
    }

    @Test
    fun `单个 ascii 字符不当规则`() {
        // 「x」这种一个字符的符号在界面里到处都是，学了等于给自己埋雷
        assertNull(PopupTeacher.locatorFor(cand(0, 0, 40, 40, text = "x")))
    }

    @Test
    fun `含与符号的文字不写进规则`() {
        // & 是规则里的「且」，没有转义机制，写进去会被解析成两个条件
        assertNull(PopupTeacher.locatorFor(cand(0, 0, 40, 40, text = "跳过&关闭")))
    }

    @Test
    fun `纯倒计时不写进规则`() {
        assertNull(PopupTeacher.locatorFor(cand(0, 0, 40, 40, text = "5s")))
    }

    @Test
    fun `以修饰符开头的文字不写进规则`() {
        // 「+」「-」「=」在规则语法里是前缀修饰符
        assertNull(PopupTeacher.locatorFor(cand(0, 0, 40, 40, text = "=跳过")))
    }

    // ---------------------------------------------------------------- 锚点

    @Test
    fun `目标自己的文字够特异时直接当锚点`() {
        val target = cand(200, 1700, 1240, 1860, text = "以后再说", clickable = true)
        assertEquals("以后再说", PopupTeacher.anchorFor(listOf(target), target))
    }

    @Test
    fun `目标文字太通用时改用上方标题当锚点`() {
        val title = cand(210, 1467, 1230, 1561, text = "会员特惠")
        val body = cand(210, 1561, 1230, 1715, text = "开通会员解锁全部内容")
        val target = cand(210, 1745, 1230, 1819, text = "关闭", clickable = true)

        // 「关闭」是两个字且落在通用词表里，不能单独当锚点
        assertEquals("会员特惠", PopupTeacher.anchorFor(listOf(title, body, target), target))
    }

    @Test
    fun `带会员二字的标题仍然可以当锚点`() {
        // 不能拿 SafeWords.isUnsafe 判锚点：它会把所有含「会员」的文案都拒掉，
        // 而「会员特惠」恰恰是最理想的 when 条件
        val title = cand(0, 0, 100, 50, text = "会员特惠")
        val target = cand(0, 100, 100, 140, text = "取消", clickable = true)
        assertEquals("会员特惠", PopupTeacher.anchorFor(listOf(title, target), target))
    }

    @Test
    fun `可点的兄弟按钮不当锚点`() {
        val title = cand(210, 1467, 1230, 1561, text = "会员特惠")
        val other = cand(210, 1745, 1230, 1819, text = "立刻开通", clickable = true)
        val target = cand(210, 1850, 1230, 1900, text = "关闭", clickable = true)

        assertEquals("会员特惠", PopupTeacher.anchorFor(listOf(title, other, target), target))
    }

    @Test
    fun `只有通用词时给不出锚点`() {
        val a = cand(0, 0, 100, 100, text = "关闭")
        val b = cand(0, 200, 100, 300, text = "取消", clickable = true)
        assertNull(PopupTeacher.anchorFor(listOf(a, b), b))
    }

    @Test
    fun `倒计时文字当不了锚点`() {
        val target = cand(0, 200, 100, 300, text = "取消", clickable = true)
        val clock = cand(0, 0, 100, 50, text = "5")
        assertNull(PopupTeacher.anchorFor(listOf(clock, target), target))
    }

    // ---------------------------------------------------------------- 拼规则

    @Test
    fun `规则结构符合引擎期望`() {
        val target = cand(0, 0, 40, 40, id = "com.x:id/iv_close")
        val rule = PopupTeacher.buildRule(target, "会员特惠")
        assertNotNull(rule)
        assertEquals("会员特惠", rule!!.whenText)
        assertEquals("com.x:id/iv_close", rule.actionText)
        assertEquals(8000L, rule.cooldownMs)
    }

    @Test
    fun `定位不出目标时不给规则`() {
        assertNull(PopupTeacher.buildRule(cand(0, 0, 40, 40, text = "x"), "会员特惠"))
    }

    @Test
    fun `锚点为空时不给规则`() {
        assertNull(PopupTeacher.buildRule(cand(0, 0, 40, 40, id = "com.x:id/a"), ""))
    }

    @Test
    fun `裸叉号学不出来但不会抛异常`() {
        // variant 951 的实测节点：id / text / desc 全空
        val bare = cand(1155, 1392, 1305, 1542, clickable = true)
        assertNull(PopupTeacher.locatorFor(bare))
        assertNull(PopupTeacher.buildRule(bare, "会员特惠"))
        assertTrue(PopupTeacher.pickAt(listOf(bare), 1230, 1467, screenArea) === bare)
    }
}
