package dev.apkwhispr

import android.content.Context

/** All settings live in one SharedPreferences file. */
class Cfg(ctx: Context) {
    val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)!!

    val key get() = p.getString(KEY, "")!!
    val cleanup get() = p.getBoolean(CLEANUP, true)
    val agentName get() = p.getString(AGENT, DEF_AGENT)!!.ifBlank { DEF_AGENT }
    val language get() = p.getString(LANG, "")!!
    val sttModel get() = p.getString(STT, DEF_STT)!!.ifBlank { DEF_STT }
    val llmModel get() = p.getString(LLM, DEF_LLM)!!.ifBlank { DEF_LLM }
    val dictionary get() = p.getString(DICT, "")!!.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    var bubbleOffset
        get() = p.getInt(OFFSET, Int.MIN_VALUE)
        set(v) = p.edit().putInt(OFFSET, v).apply()

    companion object {
        const val KEY = "key"
        const val CLEANUP = "cleanup"
        const val AGENT = "agent"
        const val LANG = "lang"
        const val STT = "stt"
        const val LLM = "llm"
        const val DICT = "dict"
        const val OFFSET = "offset"
        const val DEF_AGENT = "Jarvis"
        const val DEF_STT = "whisper-large-v3-turbo"
        const val DEF_LLM = "llama-3.3-70b-versatile"
    }
}
