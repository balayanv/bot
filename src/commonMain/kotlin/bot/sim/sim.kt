package bot.sim

import core.Credentials
import core.Data
import core.Money
import core.Percentage
import core.Price
import core.Qty
import core.Request
import core.Response
import core.http
import bot.runNexa
import bot.usage
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import lib.NEXA_BASE
import lib.parse
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stdout
import utils.asData
import utils.clock
import utils.fmt
import utils.nowSecSource
import utils.sleepHook
import utils.strOrNull
import utils.getNum
import utils.getStr
import utils.toExactLength
import utils.unixHourNow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.random.Random


suspend fun sim(args: Array<String>, logLevel: String = "DEBUG"): Int {
    val TICK = 10
    val FEE: Percentage = 0.002
    val EPS = 1e-12

    val priceLow: Price = 350.0
    val priceHigh: Price = 450.0
    val fullCycleDurationInDays = 0.05
    val spread: Percentage = 0.003
    val seed = 42L
    val chaosPerTick: Price = 0.5
    val quoteFund: Money = 10000.0
    val baseFund: Qty = 2.0

    if (args.isEmpty() || args[0] != "nexa") usage()
    val sub = args.drop(1).toTypedArray()
    if (sub.isEmpty()) usage()

    val pairHint = sub.firstOrNull { it.contains('_') } ?: "BTCB2_USDC"
    val base = pairHint.substringBefore('_')
    val quote = pairHint.substringAfter('_')

    var now = unixHourNow() * 3600
    val start = now
    var tickAt = now
    var mid = priceLow
    val wallet = mutableMapOf(quote to quoteFund, base to baseFund)
    val orders = mutableListOf<Data>()
    var nextId = 1L
    val rng = Random(seed)
    var placedCount = 0
    var cancelCount = 0
    var fillCount = 0
    var volumeBase: Qty = 0.0
    var volumeQuote: Money = 0.0
    var fees: Money = 0.0

    fun emit(msg: String) {
        memScoped {
            fputs("[${clock()}] SIM   $msg\n".cstr.ptr, stdout)
            fflush(stdout)
        }
    }

    fun available(asset: String): Double = wallet[asset] ?: 0.0

    fun prune() {
        val dead = orders.filter { it.getStr("status") != "active" }
        if (dead.size > 100) {
            val keep = dead.takeLast(100)
            orders.retainAll { it.getStr("status") == "active" || keep.contains(it) }
        }
    }

    fun lockedOf(asset: String): Double = orders.filter {
        it.getStr("status") == "active" && it.getNum("quantity_remaining") > EPS
    }.sumOf { o ->
        val rem = o.getNum("quantity_remaining")
        if (o.getStr("side") == "buy") {
            if (o.getStr("pair").substringAfter('_') == asset) rem * o.getNum("price") * (1 + FEE) else 0.0
        } else {
            if (o.getStr("pair").substringBefore('_') == asset) rem else 0.0
        }
    }

    fun tickSize(): Price = when {
        mid >= 100.0 -> 0.1
        mid >= 1.0 -> 0.01
        else -> 0.001
    }

    fun bestBid(): Price = floor(mid * (1 - spread / 2) / tickSize()) * tickSize()
    fun bestAsk(): Price = ceil(mid * (1 + spread / 2) / tickSize()) * tickSize()

    var fillBuy: Qty = 0.0
    var fillSell: Qty = 0.0

    fun fill(o: Data, qty: Double) {
        val rem = o.getNum("quantity_remaining")
        val q = qty.coerceIn(0.0, rem)
        if (q <= EPS) return
        val price = o.getNum("price")
        o["quantity_filled"] = o.getNum("quantity_filled") + q
        o["quantity_remaining"] = rem - q
        val b = o.getStr("pair").substringBefore('_')
        val qt = o.getStr("pair").substringAfter('_')
        volumeBase += q
        volumeQuote += q * price
        fees += q * price * FEE
        if (o.getStr("side") == "buy") {
            wallet[b] = available(b) + q
            fillBuy += q
        } else {
            wallet[qt] = available(qt) + q * price * (1 - FEE)
            fillSell += q
        }
        fillCount++
        if (o.getNum("quantity_remaining") <= 1e-9) {
            o["quantity_remaining"] = 0.0
            o["status"] = "filled"
        }
        prune()
        emit("fill ${o["order_id"]}: ${fmt(q)} $b @ ${fmt(price)} (${fmt(o.getNum("quantity_remaining"))} left)")
    }

    fun priceAt(sec: Long): Price {
        val cycleSec = fullCycleDurationInDays * 86400.0
        val phase = PI * ((sec - start).toDouble() % (2 * cycleSec)) / cycleSec
        return priceLow + (priceHigh - priceLow) * (1 - cos(phase)) / 2
    }

    fun step() {
        val drift = priceAt(tickAt) - priceAt(tickAt - TICK)
        val chaos = (rng.nextDouble() - 0.5) * 2 * chaosPerTick
        mid = (mid + drift + chaos).coerceIn(priceLow, priceHigh)
        val touch = spread / 2
        for (o in orders.toList()) {
            if (o.getStr("status") != "active") continue
            val rem = o.getNum("quantity_remaining")
            if (rem <= EPS) continue
            val p = o.getNum("price")
            if (o.getStr("side") == "buy") {
                if (mid < p) fill(o, rem)
                else if (mid <= p * (1 + touch) && rng.nextDouble() < 0.2) fill(o, rem * (0.2 + 0.6 * rng.nextDouble()))
            } else {
                if (mid > p) fill(o, rem)
                else if (mid >= p * (1 - touch) && rng.nextDouble() < 0.2) fill(o, rem * (0.2 + 0.6 * rng.nextDouble()))
            }
        }
    }

    fun marketTicks() {
        while (tickAt <= now) {
            step()
            tickAt += TICK
        }
    }

    nowSecSource = { now }
    sleepHook = { s -> now += s; marketTicks() }

    fun jsonString(value: String): String = "\"" + value + "\""
    fun jsonNumber(value: Double): String = jsonString(fmt(value))
    fun jsonObject(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (key, value) -> jsonString(key) + ":" + value }

    fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]")

    fun orderJson(order: Data): String = jsonObject(
        "order_id" to jsonString(order.getStr("order_id")),
        "pair" to jsonString(order.getStr("pair")),
        "side" to jsonString(order.getStr("side")),
        "price" to jsonNumber(order.getNum("price")),
        "quantity" to jsonNumber(order.getNum("quantity")),
        "quantity_filled" to jsonNumber(order.getNum("quantity_filled")),
        "quantity_remaining" to jsonNumber(order.getNum("quantity_remaining")),
        "status" to jsonString(order.getStr("status")),
    )

    fun ok(body: String): Response = mutableMapOf("status" to 200, "body" to body)
    fun fail(status: Int, msg: String): Response =
        mutableMapOf("status" to status, "body" to jsonObject("error" to jsonString(msg)))

    fun route(request: Request): Response {
        val method = request.getStr("method")
        val path = request.getStr("url").removePrefix(NEXA_BASE).removePrefix("/api/exchange")
        val purePath = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            it.take(i) to it.drop(i + 1)
        }

        if (method == "GET" && purePath == "/balances") {
            val assets = (wallet.keys + setOf(base, quote)).distinct()
            return ok(
                jsonObject(
                    "balances" to jsonObject(
                        *assets.map { a ->
                            a to jsonObject(
                                "available" to jsonNumber(available(a)),
                                "locked" to jsonNumber(lockedOf(a)),
                                "total" to jsonNumber(available(a) + lockedOf(a)),
                            )
                        }.toTypedArray()
                    )
                )
            )
        }

        if (method == "GET" && purePath.startsWith("/orderbook/")) {
            val t = tickSize()
            val bb = bestBid()
            val ba = bestAsk()
            fun levels(down: Boolean): String = jsonArray((0..7).map { i ->
                jsonObject(
                    "price" to jsonNumber(if (down) bb - i * t else ba + i * t),
                    "quantity" to jsonNumber(0.02 + rng.nextDouble() * 0.28),
                )
            })
            return ok(jsonObject("bids" to levels(true), "asks" to levels(false)))
        }

        if (method == "GET" && purePath == "/orders/history") {
            return ok(jsonObject("orders" to jsonArray(orders.filter { it.getStr("status") != "active" }
                .map { orderJson(it) })))
        }

        if (method == "GET" && purePath == "/orders") {
            val status = query["status"]
            val pair = query["pair"]
            val sel = orders.filter { o ->
                (status == null || o.getStr("status") == status) && (pair == null || o.getStr("pair") == pair)
            }
            return ok(jsonObject("orders" to jsonArray(sel.map { orderJson(it) })))
        }

        if (method == "POST" && purePath == "/order") {
            val body = asData(parse(request.getStr("body")))
            val pair = body.getStr("pair")
            val side = body.getStr("side")
            val qty = body.getNum("quantity")
            val price = body.getNum("price")
            val postOnly = body.strOrNull("postOnly") == "true"
            val b = pair.substringBefore('_')
            val qt = pair.substringAfter('_')
            if (postOnly && ((side == "buy" && price >= bestAsk()) || (side == "sell" && price <= bestBid())))
                return fail(400, "post-only order would cross")
            if (side == "buy") {
                val need = qty * price * (1 + FEE)
                if (available(qt) < need - 1e-9)
                    return fail(400, "insufficient $qt: need ${fmt(need)}, have ${fmt(available(qt))}")
                wallet[qt] = available(qt) - need
            } else {
                if (available(b) < qty - 1e-9)
                    return fail(400, "insufficient $b: need ${fmt(qty)}, have ${fmt(available(b))}")
                wallet[b] = available(b) - qty
            }
            val o: Data = mutableMapOf(
                "order_id" to "s$nextId",
                "pair" to pair,
                "side" to side,
                "price" to price,
                "quantity" to qty,
                "quantity_filled" to 0.0,
                "quantity_remaining" to qty,
                "status" to "active",
            )
            nextId++
            orders += o
            placedCount++
            emit("placed ${o["order_id"]}: $side ${fmt(qty)} $b @ ${fmt(price)}")
            return ok(jsonObject("order" to orderJson(o)))
        }

        if (method == "DELETE" && purePath.startsWith("/order/")) {
            val id = purePath.substringAfterLast('/')
            val o = orders.firstOrNull { it.getStr("order_id") == id } ?: return fail(404, "order not found")
            if (o.getStr("status") == "active") {
                val rem = o.getNum("quantity_remaining")
                val b = o.getStr("pair").substringBefore('_')
                val qt = o.getStr("pair").substringAfter('_')
                if (o.getStr("side") == "buy") wallet[qt] = available(qt) + rem * o.getNum("price") * (1 + FEE)
                else wallet[b] = available(b) + rem
                o["status"] = "cancelled"
                cancelCount++
                prune()
                emit("cancelled $id (${fmt(rem)} $b remaining)")
            }
            return ok(jsonObject("order" to orderJson(o)))
        }

        return fail(404, "no route: $method $purePath")
    }

    val exchange: http = { request ->
        marketTicks()
        route(request)
    }

    emit(
        "config — sinusoid ${fmt(priceLow)}..${fmt(priceHigh)}, low->high in ${fmt(fullCycleDurationInDays)}d, chaos ±${fmt(chaosPerTick)}/tick, spread=$spread, seed=$seed, funds ${
            fmt(
                quoteFund
            )
        } $quote + ${fmt(baseFund)} $base"
    )

    val creds: Credentials = mapOf("NEOXA_API_KEY" to "sim", "NEOXA_API_SECRET" to "sim")
    val code = runNexa(exchange, creds, sub, logLevel)

    marketTicks()

    fun money(v: Money): String {
        val body = fmt(v).removePrefix("-")
        val intPart = body.substringBefore('.')
        val frac = if (body.contains('.')) "." + body.substringAfter('.') else ""
        val grouped = intPart.reversed().chunked(3).map { it.reversed() }.reversed().joinToString(",")
        return (if (v < 0) "-" else "") + grouped + frac
    }

    fun row(key: String, value: String) = emit("${key.toExactLength(12)} $value")

    val elapsed = now - start
    val quoteNow = available(quote) + lockedOf(quote)
    val baseNow = available(base) + lockedOf(base)
    val cycles = elapsed.toDouble() / (2 * fullCycleDurationInDays * 86400.0)

    emit("─".repeat(60))
    row(
        "price",
        "sinusoid ${fmt(priceLow)}..${fmt(priceHigh)} (${fmt(fullCycleDurationInDays)}d low->high), " +
                "ended ${fmt(mid)} after ${fmt(elapsed / 86400.0)}d (${fmt(cycles)} cycles)",
    )
    if (fillBuy >= fillSell) {
        val spentQ = quoteFund - quoteNow
        row(
            "spent",
            "${money(spentQ)} / ${money(quoteFund)} $quote (${money((quoteFund - spentQ).coerceAtLeast(0.0))} unfilled)"
        )
        row("acquired", "${fmt(baseNow - baseFund)} $base, fees ${money(fees)} $quote")
    } else {
        val soldB = baseFund - baseNow
        row("sold", "${fmt(soldB)} / ${fmt(baseFund)} $base (${fmt((baseFund - soldB).coerceAtLeast(0.0))} unfilled)")
        row("received", "${money(quoteNow - quoteFund)} $quote, fees ${money(fees)} $quote")
    }
    if (volumeBase > EPS)
        row(
            "cost basis",
            "${fmt((volumeQuote + fees) / volumeBase)} $quote/$base over ${fmt(volumeBase)} $base (midpoint ${fmt((priceLow + priceHigh) / 2)})",
        )
    row("activity", "$placedCount placed / $cancelCount cancelled / $fillCount fills")
    row(
        "wallet",
        wallet.entries.joinToString(", ", "{", "}") { "${it.key}=${fmt(it.value)}" } +
                ", locked " + listOf(base, quote).joinToString(", ", "{", "}") { "$it=${fmt(lockedOf(it))}" },
    )
    emit("─".repeat(60))

    nowSecSource = null
    sleepHook = null
    return code
}
