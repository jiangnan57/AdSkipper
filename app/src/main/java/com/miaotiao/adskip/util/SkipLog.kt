package com.miaotiao.adskip.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存环形日志，最多保留 120 条，用来在首页看「刚才到底点了什么」。
 */
object SkipLog {

    private const val MAX = 120
    private val items = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(msg: String) {
        items.addFirst(fmt.format(Date()) + "  " + msg)
        while (items.size > MAX) items.removeLast()
        listeners.toList().forEach { runCatching { it() } }
    }

    @Synchronized
    fun snapshot(): List<String> = items.toList()

    @Synchronized
    fun clear() {
        items.clear()
        listeners.toList().forEach { runCatching { it() } }
    }

    @Synchronized
    fun observe(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun remove(l: () -> Unit) {
        listeners.remove(l)
    }
}
