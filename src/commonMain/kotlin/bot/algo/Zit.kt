package bot.algo

import core.Balances
import core.Credentials
import core.Data
import core.Order
import core.OrderBook
import core.Price
import core.Qty
import core.http
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.runBlocking
import lib.ApiError
import lib.balances
import lib.book
import lib.cancel
import lib.cancelAllOn
import lib.historyOrders
import lib.orders
import lib.place
import util.forEach
import util.map
import util.subject
import utils.clock
import utils.dataOrNull
import utils.fmt
import utils.floor8
import utils.getInt
import utils.getNum
import utils.getRows
import utils.getStr
import utils.logLevelRank
import utils.nowSec
import utils.sleep
import utils.toExactLength
import utils.unixHourNow
import utils.utc
import kotlin.math.abs
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stdout

private const val CYCLE_SECONDS = 15

const val ESCROW_BUFFER = 1.002

const val EPSILON = 1e-12

fun remainingBudget(o: Order, isBuy: Boolean): Double {
    val rem = o.getNum("qtyRemaining")
    return if (isBuy) rem * o.getNum("price") else rem
}

fun targetLevel(b: OrderBook, isBuy: Boolean): Data? =
    (b.getRows(if (isBuy) "bids" else "asks")).firstOrNull() as? Data

fun liveOrders(mine: List<Order>): List<Order> = mine.filter { it.getNum("qtyRemaining") > EPSILON }

fun keepResting(resting: Order, target: Data, budget: Double, isBuy: Boolean): Boolean {
    val tolerance = budget * 0.01
    val rem = remainingBudget(resting, isBuy)
    return abs(resting.getNum("price") - target.getNum("price")) < 1e-9 &&
        rem + tolerance >= budget && rem - tolerance <= budget
}

fun orderQty(budget: Double, price: Price, isBuy: Boolean): Qty =
    if (isBuy) floor8(budget / (price * ESCROW_BUFFER)) else floor8(budget)

fun hourlyBudget(unfilled: Double, remainingHours: Double): Double = unfilled / remainingHours

fun freeBudget(available: Double, resting: List<Order>, isBuy: Boolean): Double {
    var free = available
    for (o in resting) {
        if (o.getNum("qtyRemaining") > EPSILON) {
            val rem = o.getNum("qtyRemaining")
            free += if (isBuy) rem * o.getNum("price") * ESCROW_BUFFER else rem
        }
    }
    return free
}

fun spent(total: Double, available: Double, resting: List<Order>, isBuy: Boolean): Double =
    (total - freeBudget(available, resting, isBuy)).coerceIn(0.0, total)

fun windowIndex(startTs: Long, now: Long): Long = (now - startTs) / 3600

fun zeroImpactTrade(t: http, c: Credentials, side: String, pair: String, endHour: Long, logLevel: String = "DEBUG"): Int {
    val isBuy = side == "buy"
    val base = pair.substringBefore("_")
    val quote = pair.substringAfter("_")

    var currentTick: Data? = null
    var currentCycle = 0L
    val logRank = logLevelRank(logLevel)

    fun emit(level: String, msg: String) {
        if (logLevelRank(level) > logRank) return
        memScoped {
            fputs("[${clock()}] [cycle $currentCycle] ${level.toExactLength(5)} $msg\n".cstr.ptr, stdout)
            fflush(stdout)
        }
    }

    fun info(msg: String) = emit("INFO", msg)
    fun debug(msg: String) = emit("DEBUG", msg)
    fun trace(msg: String) = emit("TRACE", msg)

    fun avail(b: Balances, asset: String): Double = (b.dataOrNull(asset) ?: mutableMapOf()).getNum("available")

    val recording: http = { request ->
        val response = t(request)
        currentTick?.let {
            it["request"] = request
            it["response"] = response
        }
        trace("http ${request.getStr("method")} ${request.getStr("url")} -> ${response.getInt("status")} ${response.getStr("body")}")
        response
    }

    fun fetchBalances(): Balances = runBlocking { balances(recording, c) }
    fun fetchOrders(): List<Order> = runBlocking { orders(recording, c, pair) }
    fun fetchBook(): OrderBook = runBlocking { book(recording, pair) }
    fun placePostOnly(qty: Double, price: Price): Order = runBlocking { place(recording, c, pair, side, qty, price, postOnly = true) }
    fun cancelOrder(orderId: String): Boolean = runBlocking { cancel(recording, c, orderId) }
    fun cancelPreexisting(): List<String> = runBlocking { cancelAllOn(recording, c, pair) }

    fun spentTotalNow(data: Data): Double =
        spent(data.getNum("total"), avail(fetchBalances(), if (isBuy) quote else base), fetchOrders(), isBuy)

    fun stop(data: Data, code: Int, reason: String) {
        data["stopCode"] = code
        data["stopReason"] = reason
    }

    fun fatal(data: Data, e: ApiError): Boolean {
        if (e.status != 401) return false
        info("fatal auth failure: ${e.message}")
        stop(data, 2, "fatal")
        return true
    }

    fun withSchedule(data: Data): Data {
        data.remove("maintained")
        data.remove("decision")
        data.remove("cycleError")
        data.remove("scheduleFailed")
        if (data["stopCode"] != null) return data

        val now = data["time"] as Long
        if (now >= data["endTs"] as Long) {
            info("deadline reached with ${fmt(data.getNum("total") - spentTotalNow(data))} ${if (isBuy) quote else base} unfilled")
            stop(data, 0, "deadline")
            return data
        }
        val window = windowIndex(data["startTs"] as Long, now)
        if (window >= data["hoursTotal"] as Long) {
            stop(data, 0, "windowsDone")
            return data
        }
        if (data["windowDone"] == true && window == data["lastWindow"] as Long) {
            val boundary = (data["startTs"] as Long) + (window + 1) * 3600
            val target = minOf(boundary, data["endTs"] as Long)
            val left = (target - now).toInt().coerceAtLeast(0)
            if (left > CYCLE_SECONDS) {
                info("window $window quota filled, sleeping ${left}s until next hour window")
                data["sleepSeconds"] = left
            } else {
                debug("window $window quota filled, idling until next hour window")
            }
            return data
        }
        if (data["reschedule"] != true && window == data["lastWindow"] as Long) return data
        if (window != data["lastWindow"] as Long) debug("window $window elapsed, moving to next hour window")

        val spentNow = try {
            spentTotalNow(data)
        } catch (e: Exception) {
            info("balance check failed: ${e.message}; retry in ${CYCLE_SECONDS}s")
            data["reschedule"] = true
            data["scheduleFailed"] = true
            return data
        }
        val unfilled = data.getNum("total") - spentNow
        debug("window $window/${data["hoursTotal"]}h: spent ${fmt(spentNow)} of ${fmt(data.getNum("total"))}, unfilled ${fmt(unfilled)} ${if (isBuy) quote else base}")

        if (unfilled <= 0.0) {
            info("schedule fully filled ${fmt(data.getNum("total"))} ${if (isBuy) quote else base}")
            stop(data, 0, "filled")
            return data
        }
        val remainingHours = (data["hoursTotal"] as Long - window).toDouble()
        val hourly = hourlyBudget(unfilled, remainingHours)
        if (window == 0L) {
            info("schedule ${fmt(data.getNum("total"))} ${if (isBuy) quote else base} over ${data["hoursTotal"]}h = ${fmt(hourly)}/h (even redistribution)")
        } else {
            info("window $window: redistributed, new hourly budget ${fmt(hourly)} ${if (isBuy) quote else base}/h for ${remainingHours.toLong()}h")
        }
        data["budget"] = hourly
        data["lastWindow"] = window
        data["reschedule"] = false
        data["windowDone"] = false
        return data
    }

    fun withBook(data: Data): Data {
        if (data["stopCode"] != null || data["scheduleFailed"] == true || data["cycleError"] == true || data["windowDone"] == true) return data
        debug("fetching orderbook for $pair")
        val book = try {
            fetchBook()
        } catch (e: ApiError) {
            if (fatal(data, e)) return data
            info("api error (HTTP ${e.status}): ${e.message}; retry in ${CYCLE_SECONDS}s")
            data["cycleError"] = true
            return data
        } catch (e: Exception) {
            info("unexpected error: ${e.message}; retry in ${CYCLE_SECONDS}s")
            data["cycleError"] = true
            return data
        }
        data["book"] = book
        val target = targetLevel(book, isBuy)
        if (target == null) {
            debug("empty ${if (isBuy) "bid" else "ask"} side on $pair, waiting")
            data.remove("target")
            return data
        }
        debug(
            "best ${if (isBuy) "bid" else "ask"} ${fmt(target.getNum("price"))} x ${fmt(target.getNum("qty"))} " +
                "(${book.getRows("bids").size} bids / ${book.getRows("asks").size} asks)",
        )
        data["target"] = target
        return data
    }

    fun withLiveOrders(data: Data): Data {
        if (data["stopCode"] != null || data["scheduleFailed"] == true || data["cycleError"] == true || data["windowDone"] == true) return data
        debug("fetching our active orders on $pair")
        val mine = try {
            fetchOrders()
        } catch (e: ApiError) {
            if (fatal(data, e)) return data
            info("api error (HTTP ${e.status}): ${e.message}; retry in ${CYCLE_SECONDS}s")
            data["cycleError"] = true
            return data
        } catch (e: Exception) {
            info("unexpected error: ${e.message}; retry in ${CYCLE_SECONDS}s")
            data["cycleError"] = true
            return data
        }
        data["liveOrders"] = liveOrders(mine)
        debug("${(data["liveOrders"] as List<*>).size} live order(s) with remaining qty")
        return data
    }

    fun withDecision(data: Data): Data {
        val target = data["target"] as? Data ?: return data
        if (data["stopCode"] != null || data["scheduleFailed"] == true || data["cycleError"] == true || data["windowDone"] == true) return data
        val resting = (data["liveOrders"] as List<*>).firstOrNull() as? Order
        if (resting != null && keepResting(resting, target, data.getNum("budget"), isBuy)) {
            data["decision"] = "keep"
            data["maintained"] = resting
            return data
        }
        data["decision"] = "replace"
        data["placePrice"] = target.getNum("price")
        val budget = data.getNum("budget")
        val size = if (resting != null) {
            val rem = remainingBudget(resting, isBuy)
            if (rem + budget * 0.01 >= budget && rem - budget * 0.01 <= budget) rem else budget
        } else budget
        data["placeQty"] = orderQty(size, target.getNum("price"), isBuy)
        return data
    }

    fun withExecution(data: Data): Data {
        if (data["stopCode"] != null) return data
        when (data["decision"]) {
            "keep" -> {
                debug("resting ${(data["maintained"] as Order)["orderId"]} already at best price with budget covered, keeping it")
            }

            "replace" -> {
                val cancelledIds = mutableListOf<String>()
                for (o in data["liveOrders"] as List<Order>) {
                    debug("cancelling ${o["orderId"]} @ ${fmt(o.getNum("price"))} (repricing)")
                    try {
                        if (cancelOrder(o.getStr("orderId"))) cancelledIds += o.getStr("orderId")
                    } catch (e: Exception) {
                        info("cancel ${o["orderId"]} failed: ${e.message}")
                    }
                }
                val qty = data.getNum("placeQty")
                val price = data.getNum("placePrice")
                if (qty <= 0.0) {
                    if (cancelledIds.isNotEmpty())
                        info("withdrew: cancelled ${cancelledIds.joinToString()} (budget ${fmt(data.getNum("budget"))} ${if (isBuy) quote else base} too small at ${fmt(price)})")
                    else
                        debug("budget ${fmt(data.getNum("budget"))} ${if (isBuy) quote else base} too small for an order at ${fmt(price)}")
                    return data
                }
                debug("placing post-only $side ${fmt(qty)} $base @ ${fmt(price)} (budget ${fmt(data.getNum("budget"))} ${if (isBuy) quote else base})")
                val placed = try {
                    placePostOnly(qty, price)
                } catch (e: ApiError) {
                    if (fatal(data, e)) return data
                    info("api error (HTTP ${e.status}): ${e.message}; retry in ${CYCLE_SECONDS}s")
                    return data
                } catch (e: Exception) {
                    info("unexpected error: ${e.message}; retry in ${CYCLE_SECONDS}s")
                    return data
                }
                val orderLine = "${if (isBuy) "bid" else "ask"} ${fmt(qty)} $base @ ${fmt(price)} (budget ${fmt(data.getNum("budget"))} ${if (isBuy) quote else base}, ${placed["orderId"]})"
                if (cancelledIds.isEmpty()) info("placed: $orderLine")
                else info("repositioned: cancelled ${cancelledIds.joinToString()}, $orderLine")
                data["maintained"] = placed
            }
        }
        return data
    }

    fun withRestAndRefresh(data: Data): Data {
        if (data["stopCode"] != null) return data
        val sleepFor = data["sleepSeconds"] as? Int
        if (sleepFor != null) {
            data.remove("sleepSeconds")
            sleep(sleepFor)
            return data
        }
        if (data["scheduleFailed"] == true) {
            sleep(CYCLE_SECONDS)
            return data
        }
        val maintained = data["maintained"] as? Order
        if (maintained == null) {
            debug("nothing placed this cycle, sleeping ${CYCLE_SECONDS}s")
            sleep(CYCLE_SECONDS)
            return data
        }
        debug("order resting, sleeping ${CYCLE_SECONDS}s before refresh")
        sleep(CYCLE_SECONDS)
        debug("refreshing order state on $pair")
        val refreshed = try {
            fetchOrders()
        } catch (e: Exception) {
            info("order refresh failed: ${e.message}")
            return data
        }
        val live = refreshed.firstOrNull { it["orderId"] == maintained["orderId"] }
        if (live == null) {
            val id = maintained.getStr("orderId")
            val gone = runBlocking { historyOrders(recording, c, 25) }
                .firstOrNull { it.getStr("orderId") == id }
            if (gone == null) {
                info("order $id vanished from active and history; re-placing remaining")
                return data
            }
            val filled = gone.getNum("qtyFilled")
            if (gone.getStr("status") == "filled" || gone.getNum("qtyRemaining") <= EPSILON) {
                info("order $id filled in full (${fmt(filled)} $base @ ${fmt(gone.getNum("price"))}); window quota done")
                data["windowDone"] = true
                return data
            }
            info("order $id cancelled externally after ${fmt(filled)}/${fmt(gone.getNum("qty"))} filled; re-placing remaining")
            val filledValue = if (isBuy) filled * gone.getNum("price") else filled
            data["budget"] = (data.getNum("budget") - filledValue).coerceAtLeast(0.0)
            return data
        }
        if (live.getNum("qtyRemaining") <= EPSILON) {
            info("order ${maintained["orderId"]} filled in full; window quota done")
            data["windowDone"] = true
            return data
        }
        debug("order ${maintained["orderId"]} partially active: ${fmt(live.getNum("qtyFilled"))}/${fmt(live.getNum("qty"))} filled, remaining budget ${fmt(remainingBudget(live, isBuy))}")
        data["budget"] = remainingBudget(live, isBuy)
        return data
    }

    fun finish(data: Data): Int {
        debug("fetching our active orders on $pair for cleanup")
        try {
            liveOrders(fetchOrders()).forEach { o ->
                debug("cancelling resting ${o["orderId"]}")
                runCatching { cancelOrder(o.getStr("orderId")) }
                    .onSuccess { ok -> if (ok) info("cancelled resting ${o["orderId"]}") }
            }
        } catch (e: Exception) {
            info("cleanup failed: ${e.message}")
        }

        debug("fetching final balances for summary")
        try {
            val now = fetchBalances()
            val nowQuote = avail(now, quote)
            val nowBase = avail(now, base)
            if (isBuy) {
                info("done — spent ${fmt((data.getNum("startQuote") - nowQuote).coerceAtLeast(0.0))} $quote, acquired ${fmt((nowBase - data.getNum("startBase")).coerceAtLeast(0.0))} $base")
            } else {
                info("done — sold ${fmt((data.getNum("startBase") - nowBase).coerceAtLeast(0.0))} $base, received ${fmt((nowQuote - data.getNum("startQuote")).coerceAtLeast(0.0))} $quote")
            }
        } catch (e: Exception) {
            info("summary unavailable: ${e.message}")
        }
        return data["stopCode"] as Int
    }

    debug("fetching starting balances")
    val startBalances = fetchBalances()
    val startQuote = avail(startBalances, quote)
    val startBase = avail(startBalances, base)
    val total = if (isBuy) startQuote else startBase
    debug("available: ${fmt(startQuote)} $quote, ${fmt(startBase)} $base")

    val startHour = unixHourNow()
    if (endHour <= startHour) {
        info("deadline ${utc(endHour * 3600)} already reached, nothing to do")
        return 0
    }

    info(
        "gradually ${if (isBuy) "buying '$base' with ${fmt(startQuote)} $quote" else "selling ${fmt(startBase)} $base"}" +
            " on $pair until ${utc(endHour * 3600)}",
    )

    debug("fetching active orders on $pair to cancel preexisting ones")
    val cancelled = cancelPreexisting()
    if (cancelled.isNotEmpty()) info("cancelled ${cancelled.size} preexisting: ${cancelled.joinToString()}")
    else debug("no preexisting orders on $pair")

    if (total <= 0.0) {
        info("no ${if (isBuy) quote else base} available, nothing to do")
        return 0
    }

    val carry: Data = mutableMapOf(
        "total" to total,
        "startQuote" to startQuote,
        "startBase" to startBase,
        "hoursTotal" to endHour - startHour,
        "endTs" to endHour * 3600,
        "startTs" to nowSec(),
        "budget" to 0.0,
        "lastWindow" to -1L,
        "reschedule" to true,
    )

    fun withState(data: Data): Data {
        data.putAll(carry)
        data["neoxaCreds"] = c
        return data
    }

    val ticks = subject<Data>()
    ticks
        .map(::withState)
        .map(::withSchedule)
        .map(::withBook)
        .map(::withLiveOrders)
        .map(::withDecision)
        .map(::withExecution)
        .map(::withRestAndRefresh)
        .forEach { }

    val persistentKeys = listOf("budget", "lastWindow", "reschedule", "windowDone", "stopCode", "stopReason")
    while (carry["stopCode"] == null) {
        val tick: Data = mutableMapOf("time" to nowSec(), "cycle" to currentCycle)
        currentTick = tick
        ticks.emit(tick)
        currentTick = null
        for (key in persistentKeys) {
            val value = tick[key]
            if (value != null) carry[key] = value else carry.remove(key)
        }
        currentCycle++
    }

    if (carry["stopReason"] == "filled" || carry["stopReason"] == "windowsDone") info("schedule window complete")
    return finish(carry)
}
