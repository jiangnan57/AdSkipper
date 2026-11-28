package com.miaotiao.adskip.data

import com.miaotiao.adskip.engine.PopupTeacher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「学弹窗」的存储层。
 *
 * 关键是把「一条规则」的增删去重和「转成引擎 JSON」这两件事钉死：
 * 学出来的规则一旦写坏，表现是「弹窗规则全都不生效」，而用户看不出原因。
 */
class TaughtRulesStoreTest {

    private fun rule(whenText: String, action: String) =
        PopupTeacher.Rule(whenText, action)

    // ---------------------------------------------------------------- 追加

    @Test
    fun `第一条从空白开始写`() {
        val out = TaughtRulesStore.add("", "com.x", "某应用", rule("会员特惠", "id/iv_close"))
        assertNotNull(out)
        assertEquals(1, TaughtRulesStore.count(out!!))
        assertEquals(1, TaughtRulesStore.appCount(out))
    }

    @Test
    fun `同一条规则学两遍不会变成两条`() {
        val r = rule("会员特惠", "id/iv_close")
        val first = TaughtRulesStore.add("", "com.x", "某应用", r)!!
        assertNull(TaughtRulesStore.add(first, "com.x", "某应用", r))
    }

    @Test
    fun `同一个应用的第二条会追加`() {
        val first = TaughtRulesStore.add("", "com.x", "某应用", rule("会员特惠", "id/a"))!!
        val second = TaughtRulesStore.add(first, "com.x", "某应用", rule("每日签到", "id/b"))!!
        assertEquals(2, TaughtRulesStore.count(second))
        assertEquals(1, TaughtRulesStore.appCount(second))
    }

    @Test
    fun `不同应用各自独立`() {
        var doc = TaughtRulesStore.add("", "com.a", "甲", rule("标题甲", "id/a"))!!
        doc = TaughtRulesStore.add(doc, "com.b", "乙", rule("标题乙", "id/b"))!!
        assertEquals(2, TaughtRulesStore.count(doc))
        assertEquals(2, TaughtRulesStore.appCount(doc))
    }

    @Test
    fun `单应用超过四十条就拒绝`() {
        var doc = ""
        for (i in 0 until TaughtRulesStore.MAX_RULES_PER_APP) {
            doc = TaughtRulesStore.add(doc, "com.x", "某应用", rule("标题$i", "id/b$i"))!!
        }
        assertEquals(TaughtRulesStore.MAX_RULES_PER_APP, TaughtRulesStore.count(doc))
        assertNull(TaughtRulesStore.add(doc, "com.x", "某应用", rule("第41条", "id/zz")))
    }

    @Test
    fun `字段里带制表符直接拒绝`() {
        // 制表符是行分隔符，混进来会把整段格式撑散
        assertNull(TaughtRulesStore.add("", "com.x", "应用", rule("标题\t带制表", "id/a")))
        assertNull(TaughtRulesStore.add("", "com.x", "应用", rule("正常", "id/a\n换行")))
    }

    @Test
    fun `应用名里的换行会被清掉而不是拒绝`() {
        val out = TaughtRulesStore.add("", "com.x", "某\n应用", rule("标题", "id/a"))
        assertNotNull(out)
        assertTrue(TaughtRulesStore.rows(out!!)[0].name.contains("某 应用"))
    }

    @Test
    fun `空包名拒绝`() {
        assertNull(TaughtRulesStore.add("", "", "应用", rule("标题", "id/a")))
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `损坏的行被跳过而不是抛异常`() {
        val raw = "com.x\t应用\t标题\tid/a\t8000\n这一行只有两段\t坏了\n\n"
        val rows = TaughtRulesStore.rows(raw)
        assertEquals(1, rows.size)
        assertEquals("com.x", rows[0].pkg)
    }

    @Test
    fun `缺少冷却时间时用默认八秒`() {
        val rows = TaughtRulesStore.rows("com.x\t应用\t标题\tid/a")
        assertEquals(8000L, rows[0].cooldownMs)
    }

    @Test
    fun `冷却时间超出范围的会被夹回来`() {
        val rows = TaughtRulesStore.rows("com.x\t应用\t标题\tid/a\t10")
        assertEquals(1500L, rows[0].cooldownMs)
    }

    // ---------------------------------------------------------------- 转 JSON

    @Test
    fun `转出来的结构引擎能认`() {
        val doc = TaughtRulesStore.add("", "com.x", "某应用", rule("会员特惠", "id/iv_close"))!!
        val json = TaughtRulesStore.toJson(doc)
        assertNotNull(json)
        assertTrue(json!!.contains("\"apps\""))
        assertTrue(json.contains("\"com.x\""))
        assertTrue(json.contains("\"when\":[\"会员特惠\"]"))
        assertTrue(json.contains("\"action\":[\"id/iv_close\"]"))
        assertTrue(json.contains("\"cooldown\":8000"))
        // 粗略的结构检查：括号必须配平
        assertEquals(
            json.count { it == '{' }, json.count { it == '}' }
        )
    }

    @Test
    fun `没有规则时不生成 JSON`() {
        assertNull(TaughtRulesStore.toJson(""))
        assertNull(TaughtRulesStore.toJson("   "))
        assertNull(TaughtRulesStore.toJson("坏行"))
    }

    @Test
    fun `文字里的引号和反斜杠会被转义`() {
        val doc = TaughtRulesStore.add("", "com.x", "应用", rule("他说\"关\"", "id/a"))!!
        val json = TaughtRulesStore.toJson(doc)!!
        assertTrue(json.contains("\\\"关\\\""))
        assertFalse(json.contains("\"他说\"关\"\""))
    }

    @Test
    fun `多个应用会分组到各自的 popups 下`() {
        var doc = TaughtRulesStore.add("", "com.a", "甲", rule("甲标题", "id/a"))!!
        doc = TaughtRulesStore.add(doc, "com.a", "甲", rule("甲标题2", "id/a2"))!!
        doc = TaughtRulesStore.add(doc, "com.b", "乙", rule("乙标题", "id/b"))!!
        val json = TaughtRulesStore.toJson(doc)!!

        // 甲有两组 when，乙有一组
        assertEquals(1, Regex("\"com.a\":\\{").findAll(json).count())
        assertEquals(1, Regex("\"com.b\":\\{").findAll(json).count())
        assertEquals(3, Regex("\"when\":").findAll(json).count())
    }

    @Test
    fun `删除某一条后其余保持不变`() {
        var doc = TaughtRulesStore.add("", "com.x", "应用", rule("标题一", "id/a"))!!
        doc = TaughtRulesStore.add(doc, "com.x", "应用", rule("标题二", "id/b"))!!
        val out = TaughtRulesStore.removeWhere(doc) { it.whenText == "标题一" }
        assertEquals(1, TaughtRulesStore.count(out))
        assertEquals("标题二", TaughtRulesStore.rows(out)[0].whenText)
    }

    // ---------------------------------------------------------------- 导出发给人

    @Test
    fun `导出一行是竖线分隔的四段，粘贴后不会丢字段`() {
        val doc = TaughtRulesStore.add("", "com.taobao.taobao", "淘宝", rule("会员特惠", "以后再逛"))!!
        assertEquals(
            "com.taobao.taobao | 淘宝 | 会员特惠 | 以后再逛",
            TaughtRulesStore.exportAll(doc)
        )
    }

    @Test
    fun `文字里带竖线时换成全角，不把一行切成五段`() {
        val doc = TaughtRulesStore.add("", "com.a", "应用", rule("A|B", "id/c"))!!
        val line = TaughtRulesStore.exportAll(doc)
        assertEquals("com.a | 应用 | A｜B | id/c", line)
        assertEquals(4, line.split(" | ").size)
    }

    @Test
    fun `应用名缺失时用包名顶上，不出现空字段`() {
        val doc = TaughtRulesStore.add("", "com.b", "", rule("标题", "id/d"))!!
        assertEquals("com.b | com.b | 标题 | id/d", TaughtRulesStore.exportAll(doc))
    }
}
