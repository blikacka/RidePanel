package cz.blikacka.ridepanel

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tail-N log buffer that mirrors every line into the standard Android logcat
 * AND broadcasts it to a single listener so the UI can render it. Use this
 * everywhere instead of `android.util.Log` — that's the only way the in-app
 * log panel sees what's happening.
 */
object AppLog {
    private const val MAX_LINES = 400

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile private var listener: ((List<String>) -> Unit)? = null

    fun setListener(l: ((List<String>) -> Unit)?) {
        listener = l
        l?.invoke(snapshot())
    }

    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        listener?.invoke(emptyList())
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); append('I', tag, msg, null) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append('D', tag, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
        append('W', tag, msg, t)
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
        append('E', tag, msg, t)
    }

    private fun append(level: Char, tag: String, msg: String, t: Throwable?) {
        val time = synchronized(timeFmt) { timeFmt.format(Date()) }
        val short = tag.removePrefix("RidePanel.")
        val cause = t?.let { "  ${it.javaClass.simpleName}: ${it.message ?: ""}" }
        val line = if (cause == null) "$time $level/$short  $msg"
                   else "$time $level/$short  $msg\n$cause"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        listener?.invoke(snapshot())
    }
}
