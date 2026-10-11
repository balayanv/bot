package core

import kotlinx.serialization.json.*
import org.tinylog.Logger
import org.tinylog.configuration.Configuration
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.ceil

/**
 * Everything is plain data: maps in, maps out. An http request is a map
 * {method, url, headers, body?}, a response is {status, body}; the only impure
 * function is the [Http] that turns one into the other.
 */

typealias Data = Map<String, Any>
typealias Rows = List<Any>
typealias Request = Data
typealias Response = Data
typealias Http = (Request) -> Response
typealias Credentials = Map<String, String>

class ApiError(val status: Int, message: String) : Exception(message)

// ---- fail-fast data access -------------------------------------------------

private fun Data.bad(key: String): Nothing = error("unexpected '$key' = ${this[key]} in $this")

fun Data.str(key: String): String = when (val v = this[key]) {
   is String -> v
   is Long -> v.toString()
   else -> bad(key)
}

fun Data.num(key: String): Double = when (val v = this[key]) {
   is Number -> v.toDouble()
   is String -> v.toDoubleOrNull() ?: bad(key)
   else -> bad(key)
}

fun Data.long(key: String): Long = this[key] as? Long ?: bad(key)
fun Data.int(key: String): Int = (this[key] as? Number)?.toInt() ?: bad(key)
fun Data.rows(key: String): Rows = this[key] as? Rows ?: bad(key)
fun Data.data(key: String): Data = this[key]?.asData() ?: bad(key)

@Suppress("UNCHECKED_CAST")
fun Any.asData(): Data = this as? Data ?: error("expected map, got $this")

// ---- json ------------------------------------------------------------------

/** JSON text -> maps/lists/strings/longs/doubles/booleans; nulls are dropped. */
fun parseJson(text: String): Any = fromJson(Json.parseToJsonElement(text))

private fun fromJson(e: JsonElement): Any = when (e) {
   is JsonNull -> error("null at json root")
   is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.double
   is JsonArray -> e.filter { it !is JsonNull }.map(::fromJson)
   is JsonObject -> e.filterValues { it !is JsonNull }.mapValues { fromJson(it.value) }
}

/** Compact JSON text from maps/lists/strings/numbers/booleans. */
fun toJson(v: Any?): String = when (v) {
   null -> "null"
   is String -> JsonPrimitive(v).toString()
   is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
   is List<*> -> v.joinToString(",", "[", "]", transform = ::toJson)
   else -> v.toString()
}

// ---- numbers ---------------------------------------------------------------

/** Fixed 8 dp ("0.00100000"), the form the exchanges accept. */
fun dec8(v: Double): String = BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP).toPlainString()

/** Up to 8 dp, trailing zeros trimmed, for humans. */
fun fmt(v: Double): String = BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Percent with 2 decimals ("12.34%"). */
fun pct(v: Double): String = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"

// ---- time (swappable: the simulator runs days in seconds) -------------------

var nowSec: () -> Long = { System.currentTimeMillis() / 1000 }

private var lastNonce = 0L

/** Unix ms, strictly increasing: servers reject replayed nonces and two calls can share a millisecond. */
fun nextNonce(): Long = maxOf(System.currentTimeMillis(), lastNonce + 1).also { lastNonce = it }
var sleep: (Long) -> Unit = { Thread.sleep(it * 1000) }

/** "YYYY-MM-DD HH:MM:SS UTC" */
fun utc(sec: Long): String = Instant.ofEpochSecond(sec).toString().replace('T', ' ').removeSuffix("Z") + " UTC"

/**
 * Deadline as epoch seconds: "30/10/2026" is the end of that UTC day,
 * "30" / "0.5" that many (fractional) days from now, rounded up to a whole hour.
 */
fun parseDeadline(arg: String): Long? {
   if ('/' in arg) return runCatching {
      LocalDate.parse(arg, DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)).plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
   }.getOrNull()
   val days = arg.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
   return (nowSec() / 3600 + ceil(days * 24).toLong()) * 3600
}

// ---- log -------------------------------------------------------------------
// Lines are stamped here with [nowSec] (virtual in the simulator) and handed to tinylog,
// which writes them to stdout and, once [logTo] ran, to rolling files.

val LOG_LEVELS = listOf("info", "debug", "trace")
var logLevel = 1

/** Also write every line to [dir]/[name].<date>.<n>.log (new file per start, day and 10MB), newest linked as [name].log. */
fun logTo(dir: String, name: String) = Configuration.replace(
   mapOf(
      "writer1" to "console", "writer1.stream" to "out", "writer1.format" to "{message}",
      "writer2" to "rolling file", "writer2.format" to "{message}", "writer2.charset" to "UTF-8",
      "writer2.file" to "$dir/$name.{date:yyyy-MM-dd}.{count}.log", "writer2.latest" to "$dir/$name.log",
      "writer2.policies" to "startup, daily, size: 10mb", "writer2.backups" to "100",
   )
)

private fun log(level: Int, tag: String, msg: String) {
   if (level > logLevel) return
   val line: Any = "${utc(nowSec()).removeSuffix(" UTC")} ${tag.padEnd(5)} $msg"
   when (tag) {
      "ERROR" -> Logger.error(line)
      "INFO" -> Logger.info(line)
      "DEBUG" -> Logger.debug(line)
      else -> Logger.trace(line)
   }
}

fun err(msg: String) = log(0, "ERROR", msg)
fun info(msg: String) = log(0, "INFO", msg)
fun debug(msg: String) = log(1, "DEBUG", msg)
fun trace(msg: String) = log(2, "TRACE", msg)
