package com.miaotiao.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自绘跳过按钮的判据测试。
 *
 * 样本全部来自真机实测（Redmi 24122RKC7C / Android 17 / 1440×3200），
 * 不是编出来的数字。对应用户截图里那条「小红书」开屏广告。
 */
class DrawSkipTest {

    private val W = 1440
    private val H = 3200

    // ---------------------------------------------------------- 形状

    @Test
    fun 穿山甲开屏的自绘跳过按钮能认出来() {
        // 实测：View clickable=true 150×150 @ [60,2326][210,2476]
        assertTrue(
            "150×150、中心 x=135（贴左边）、纵向在 73% 处，必须命中",
            DrawSkip.shapeOk(150, 150, 135, 2326, 2476, W, H)
        )
    }

    @Test
    fun 同一页上的广告小标签不算跳过按钮() {
        // 实测：TextView clickable=true 120×53 @ [75,2517][195,2570]
        // 长宽比 2.26，是「广告」两个字，点它没用
        assertFalse(
            "120×53 是扁条，不是跳过",
            DrawSkip.shapeOk(120, 53, 135, 2517, 2570, W, H)
        )
    }

    @Test
    fun 画面正中的大按钮不算跳过() {
        // 视频 App 主界面的「发布」按钮就长这样，形状一样但位置在中间
        assertFalse(
            "中心 x=720 在正中，跳过按钮永远贴边",
            DrawSkip.shapeOk(150, 150, 720, 1500, 1650, W, H)
        )
    }

    @Test
    fun 状态栏上的图标不算跳过() {
        assertFalse(
            "top=20 在状态栏里",
            DrawSkip.shapeOk(150, 150, 135, 20, 170, W, H)
        )
    }

    @Test
    fun 太大的方形不算跳过() {
        // 短边 350 > 屏宽 1440 的 20%（288）
        assertFalse(
            "350×350 是内容区，不是按钮",
            DrawSkip.shapeOk(350, 350, 200, 1200, 1550, W, H)
        )
    }

    @Test
    fun 太小的方形不算跳过() {
        // 短边 40 < 屏宽 1440 的 4%（57.6）
        assertFalse(
            "40×40 是图标，不是跳过",
            DrawSkip.shapeOk(40, 40, 100, 2000, 2040, W, H)
        )
    }

    @Test
    fun 右上角的方形跳过也能认出来() {
        // 常见的右上角圆形跳过：中心 x=1340 > 1440*0.68
        assertTrue(
            "右上角贴边",
            DrawSkip.shapeOk(160, 160, 1340, 200, 360, W, H)
        )
    }

    @Test
    fun 横屏时用短边算基准() {
        // 1280×720 横屏：基准是 720，短边 90 落在 4%(28.8)~20%(144) 之间
        assertTrue(
            "横屏也要按短边换算",
            DrawSkip.shapeOk(90, 90, 60, 300, 390, 1280, 720)
        )
    }

    // ---------------------------------------------------------- 强标记

    @Test
    fun 开屏模板的引导语是强标记() {
        assertTrue(DrawSkip.markOf("向上滑动"))
        assertTrue(DrawSkip.markOf("跳转至详情页或第三方应用".take(24)))
        assertTrue(DrawSkip.markOf("点击到落地页或三方APP".take(24)))
    }

    @Test
    fun 内容页里的跳过不算强标记() {
        // 「跳过教程」「跳过这一步」是内容页常见文案，不能当开屏广告
        assertFalse(DrawSkip.markOf("跳过教程"))
        assertFalse(DrawSkip.markOf("跳过这一步"))
        assertFalse(DrawSkip.markOf(null))
    }

    @Test
    fun 广告SDK的容器id是强标记() {
        // 实测：id_splash_bottom_logo
        assertTrue(DrawSkip.markOfId("com.bytedance.sdk.openadsdk:id/id_splash_bottom_logo"))
        assertTrue(DrawSkip.markOfId("com.qq.e.ads:id/splash_container"))
        assertFalse(DrawSkip.markOfId(null))
        assertFalse(DrawSkip.markOfId("com.xingin.xhs:id/title"))
    }

    @Test
    fun 广告SDK的容器描述是强标记() {
        // 实测：content-desc=NativeVideoAdView
        assertTrue(DrawSkip.markOfDesc("NativeVideoAdView"))
        assertTrue(DrawSkip.markOfDesc("AdView"))
        assertFalse(DrawSkip.markOfDesc(null))
        assertFalse(DrawSkip.markOfDesc(""))
        // 太长的描述多半是内容节点
        assertFalse(DrawSkip.markOfDesc("a".repeat(60) + "AdView"))
    }
}
