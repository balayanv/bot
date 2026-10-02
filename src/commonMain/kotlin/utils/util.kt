package utils

import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.fputs
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.gmtime_r
import platform.posix.stderr
import platform.posix.time
import platform.posix.time_tVar
import core.Data
import core.Credentials
import core.Rows
import platform.posix.tm
import platform.posix.usleep
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Pure/general-purpose helpers shared across the codebase: locale-free number
 * formatting and unix-hour / deadline time math. No I/O, no exchange or CLI
 * knowledge.
 */


fun String.err() {
    val text = "$this\n"
    memScoped { fputs(text.cstr.ptr, stderr) }
}

fun String.log() { println(this) }


// ---- numbers -------------------------------------------------------------

/** Locale-free fixed 8-dp decimal string ("0.00100000"). */
internal fun decimal8(v: Double): String {
    val scaled = round(abs(v) * 1e8).toLong()
    return (if (v < 0) "-" else "") +
        (scaled / 100_000_000L).toString() + "." +
        (scaled % 100_000_000L).toString().padStart(8, '0')
}

/** Up to 8 decimal places, trailing zeros trimmed (locale-free). */
internal fun fmt(v: Double): String {
    val scaled = round(abs(v) * 1e8).toLong()
    var s = (scaled / 100_000_000L).toString() + "." +
        (scaled % 100_000_000L).toString().padStart(8, '0')
    s = s.trimEnd('0').trimEnd('.')
    return (if (v < 0) "-" else "") + s
}

/** Truncate toward zero at 8 dp — never round up into insufficient funds. */
internal fun floor8(v: Double): Double = floor(v * 1e8) / 1e8

/** Exact-length string: trims to [n] chars or pads with trailing spaces. */
internal fun String.toExactLength(n: Int): String =
    if (length >= n) take(n) else this + " ".repeat(n - length)

// ---- time ----------------------------------------------------------------

internal var nowSecSource: (() -> Long)? = null

internal fun nowSec(): Long = nowSecSource?.invoke() ?: time(null)

internal var sleepHook: ((Int) -> Unit)? = null

internal fun sleep(seconds: Int) {
    val hook = sleepHook
    if (hook == null) usleep((seconds * 1_000_000L).toUInt()) else hook(seconds)
    "".log()
}

/** Milliseconds since epoch — nonkyc nonces. */
internal fun nowMs(): Long = nowSec() * 1000

internal fun unixHourNow(): Long = nowSec() / 3600

/** "YYYY-MM-DD HH:MM:SS UTC" from epoch seconds (no String.format on Native). */
internal fun utc(sec: Long): String = memScoped {
    val t = alloc<time_tVar>()
    t.value = sec
    val tmv = alloc<tm>()
    gmtime_r(t.ptr, tmv.ptr)
    fun d(v: Int, w: Int = 2) = v.toString().padStart(w, '0')
    "${d(tmv.tm_year + 1900, 4)}-${d(tmv.tm_mon + 1)}-${d(tmv.tm_mday)} " +
        "${d(tmv.tm_hour)}:${d(tmv.tm_min)}:${d(tmv.tm_sec)} UTC"
}

/** "HH:MM:SS" UTC. */
internal fun clock(): String = utc(nowSec()).substring(11, 19)

/**
 * "30/10/2026" -> unix hour at the END of that UTC day (exclusive);
 * "30" / "0.5" -> that many (fractional) days from now. Null when invalid.
 */
internal fun parseDeadline(arg: String): Long? {
    if (arg.contains('/')) {
        val p = arg.split('/')
        if (p.size != 3) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val y = p[2].toIntOrNull() ?: return null
        val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        fun leap(yy: Int) = (yy % 4 == 0 && yy % 100 != 0) || yy % 400 == 0
        if (m !in 1..12 || y !in 1970..9999) return null
        if (d < 1 || d > dim[m - 1] + if (m == 2 && leap(y)) 1 else 0) return null
        return daysFromCivil(y, m, d) * 24 + 24 // end of day, UTC
    }
    val days = arg.toDoubleOrNull() ?: return null
    if (days <= 0.0) return null
    return unixHourNow() + ceil(days * 24).toLong().coerceAtLeast(1)
}

/** Days since 1970-01-01 for a civil (proleptic Gregorian) date. */
internal fun daysFromCivil(y: Int, m: Int, d: Int): Long {
    val yy = (if (m <= 2) y - 1 else y).toLong()
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153L * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

// ---- logging -------------------------------------------------------------

internal fun logLevelRank(level: String): Int = when (level) {
    "INFO" -> 0
    "DEBUG" -> 1
    "TRACE" -> 2
    else -> throw IllegalStateException("unknown log level '$level'")
}

// ---- fail-fast data accessors --------------------------------------------

@PublishedApi
internal fun fail(what: String, found: Any?, context: Any?): Nothing {
    val foundDesc = when (found) {
        null -> "absent"
        else -> "${found::class.simpleName} $found"
    }
    throw IllegalStateException("$what (found: $foundDesc) in: $context")
}

fun Data.getStr(key: String): String {
    val v = this[key] ?: fail("expected String at '$key'", null, this)
    return v as? String ?: fail("expected String at '$key'", v, this)
}

fun Data.getInt(key: String): Int {
    val v = this[key] ?: fail("expected int at '$key'", null, this)
    return when (v) {
        is Int -> v
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: fail("expected int at '$key'", v, this)
        else -> fail("expected int at '$key'", v, this)
    }
}

fun Data.getNum(key: String): Double {
    val v = this[key] ?: fail("expected number at '$key'", null, this)
    return when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull() ?: fail("expected number at '$key'", v, this)
        else -> fail("expected number at '$key'", v, this)
    }
}

inline fun <reified K, reified V> Data.getMap(key: String): Map<K, V> {
    val v = this[key] ?: fail("expected map at '$key'", null, this)
    val m = v as? Map<*, *> ?: fail("expected map<${K::class.simpleName}, ${V::class.simpleName}> at '$key'", v, this)
    if (m.keys.all { it is K } && m.values.all { it is V }) {
        @Suppress("UNCHECKED_CAST")
        return m as Map<K, V>
    }
    fail("expected map<${K::class.simpleName}, ${V::class.simpleName}> at '$key'", v, this)
}

fun Data.getData(key: String): Data {
    val v = this[key] ?: fail("expected data at '$key'", null, this)
    return v as? Data ?: fail("expected data at '$key'", v, this)
}

fun Data.getRows(key: String): Rows {
    val v = this[key] ?: fail("expected rows at '$key'", null, this)
    return v as? Rows ?: fail("expected rows at '$key'", v, this)
}

fun Rows.getData(index: Int): Data {
    val v = this.getOrNull(index) ?: fail("expected data at [$index]", null, this)
    return v as? Data ?: fail("expected data at [$index]", v, this)
}

fun Data.strOrNull(key: String): String? = this[key] as? String
fun Data.dataOrNull(key: String): Data? = this[key] as? Data
fun Data.rowsOrNull(key: String): Rows? = this[key] as? Rows

fun asData(v: Any?, context: Any? = v): Data =
    v as? Data ?: fail("expected data", v, context)

fun asRows(v: Any?, context: Any? = v): Rows =
    v as? Rows ?: fail("expected rows", v, context)

fun Array<String>.getIntOrNull(index: Int): Int? = getOrNull(index)?.toIntOrNull()

fun envString(name: String): String {
    try {
        return getenv(name)!!.toKString()
    } catch (e: Exception) {
        throw IllegalStateException("expected environment variable $name", e)
    }
}

fun credentials(vararg envNames: String): Credentials =
    envNames.associateWith { envString(it) }
