package bot

import core.*
import lib.Call
import lib.Venue
import lib.call
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Zero Impact Trade: gradually spend (buy) or sell the whole available balance on one
 * market until a deadline, only ever as a maker.
 *
 * The time to the deadline is cut into equal cycles. When a cycle starts, whatever is
 * still unspent is spread evenly over the cycles left, so a slow cycle's leftover is
 * carried forward. Within a cycle one order sits at the best bid (buy) / best ask (sell)
 * for exactly the cycle's remaining quota and is re-priced whenever it gets outbid.
 * Once the quota fills zit idles until the next cycle.
 *
 * Every tick is: observe the venue into a map -> [plan] (pure) -> act on the decision.
 * Progress is derived from balances each tick rather than remembered, so there is no
 * fill bookkeeping to go stale, and re-running the same command after a crash resumes.
 */

const val TICK_SECONDS = 15L

/** Amount of the spend asset (quote on a buy, base on a sell) locked in [orders]. */
fun locked(orders: List<Data>, isBuy: Boolean, escrow: Double): Double =
   orders.sumOf { if (isBuy) it.num("remaining") * it.num("price") * escrow else it.num("remaining") }

/** Order quantity, truncated to the market's precision, that uses at most [amount] of the spend asset at [price]. */
fun orderQty(amount: Double, price: Double, isBuy: Boolean, escrow: Double, decimals: Int): Double {
   val step = 10.0.pow(decimals)
   return floor((if (isBuy) amount / (price * escrow) else amount) * step + 1e-9) / step
}

/** Smallest quantity at [price] the market accepts. */
fun minQty(minNotional: Double, price: Double, decimals: Int): Double {
   val step = 10.0.pow(decimals)
   return maxOf(0.0, ceil(minNotional / price * step - 1e-9) / step)
}

/**
 * Pure decision for one tick. Reads the observation (now, available, orders, book) and
 * the run's facts; returns the same map plus window/target carried to the next tick and
 * the decision: action (stop|wait|idle|keep|replace), price, qty, sleep. Progress for humans:
 * goalPct (share of the total spent) and orderPct (fill of our live order, when there is one).
 */
@Suppress("UNCHECKED_CAST")
fun plan(s: Data): Data {
   val isBuy = s["side"] == "buy"
   val total = s.num("total")
   val escrow = s.num("escrow")
   val now = s.long("now")
   val orders = s["orders"] as List<Data>
   val book = s.data("book")

   // spent = what left the wallet for good: neither available nor locked in our orders
   val spent = (total - s.num("available") - locked(orders, isBuy, escrow)).coerceIn(0.0, total)
   val resting = orders.singleOrNull()
   val progress = mapOf("goalPct" to spent / total * 100) +
      (resting?.let { mapOf("orderPct" to (it.num("qty") - it.num("remaining")) / it.num("qty") * 100) } ?: emptyMap())
   val out = s - "orderPct" + progress + mapOf("spent" to spent, "sleep" to TICK_SECONDS)
   if (now >= s.long("endTs")) return out + mapOf("action" to "stop", "why" to "deadline reached")
   if (spent >= total) return out + mapOf("action" to "stop", "why" to "everything spent")

   // a new cycle spreads the unspent over the cycles left; target = cumulative spent at its end
   val window = (now - s.long("startTs")) / s.long("cycleSec")
   val newCycle = window != s["window"]
   val target = if (newCycle) spent + (total - spent) / (s.long("cycles") - window) else s.num("target")
   val quota = (target - spent).coerceAtLeast(0.0)
   val cycle = out + mapOf("window" to window, "target" to target, "quota" to quota, "newCycle" to newCycle)

   val best = (book.rows(if (isBuy) "bids" else "asks").firstOrNull() as? Data)?.num("price")
      ?: return cycle + mapOf("action" to "wait", "why" to "empty ${if (isBuy) "bid" else "ask"} side")
   val decimals = s.int("qtyDecimals")
   val smallest = minQty(s.num("minNotional"), best, decimals)
   if (orderQty(total - spent, best, isBuy, escrow, decimals) < maxOf(smallest, 10.0.pow(-decimals)))
      return cycle + mapOf("action" to "stop", "why" to "the unspent ${fmt(total - spent)} is below the smallest order")
   // a quota under the minimum still trades the minimum: running ahead is corrected by the next cycle's redistribution
   val qty = if (quota <= 1e-12) 0.0 else maxOf(orderQty(quota, best, isBuy, escrow, decimals), smallest)
   val decided = cycle + mapOf("price" to best, "qty" to qty)

   if (qty <= 0.0) {
      val nextCycle = minOf(s.long("startTs") + (window + 1) * s.long("cycleSec"), s.long("endTs"))
      return decided + mapOf("action" to "idle", "sleep" to maxOf(nextCycle - now, TICK_SECONDS))
   }
   val opposite = (book.rows(if (isBuy) "asks" else "bids").firstOrNull() as? Data)?.num("price")
   if (!s.bool("serverPostOnly") && opposite != null && (if (isBuy) best >= opposite else best <= opposite))
      return decided + mapOf("action" to "wait", "why" to "book crossed at $opposite, joining would take")

   val keep = resting != null && abs(resting.num("price") - best) < 1e-9 && abs(resting.num("remaining") - qty) <= qty * 0.01
   return decided + ("action" to if (keep) "keep" else "replace")
}

private fun Data.bool(key: String): Boolean = this[key] == true

fun zit(v: Venue, t: Http, c: Credentials, side: String, pair: String, endTs: Long, cycleSec: Long): Int {
   val isBuy = side == "buy"
   val (base, quote) = pair.split(v.sep)
   val spend = if (isBuy) quote else base

   fun <T> ask(call: Call<T>, extra: Data = emptyMap()): T = t.call(call, mapOf("creds" to c, "pair" to pair, "nonce" to nextNonce()) + extra)
   fun cancelAll(orders: List<Data>) = orders.forEach {
      val qty = it.num("qty")
      if (fmt(qty).endsWith("11")) { debug("skipping manual order qty=$qty"); return@forEach }
      ask(v.cancel, mapOf("id" to it.str("id"))); debug("cancelled ${it.str("id")}")
   }

   // our own leftovers from an interrupted run go first, so their funds count in the total
   val stale = ask(v.orders)
   val manual = stale.count { fmt(it.num("qty")).endsWith("11") }
   cancelAll(stale)
   if (stale.isNotEmpty()) info("cancelled ${stale.size - manual} preexisting order(s) on $pair (${manual} manual skipped)")

   val startBalances = ask(v.balances)
   val total = startBalances[spend] ?: 0.0
   if (total <= 0.0) {
      info("no $spend available, nothing to do")
      return 0
   }
   val startTs = nowSec()
   val cycles = (endTs - startTs + cycleSec - 1) / cycleSec
   val usdAssets = setOf("USD", "USDT", "USDC")
   fun usdSuffix(asset: String, amount: Double): String {
      if (asset in usdAssets) return ""
      for (quote in listOf("USDT", "USDC", "USD")) {
         val p = "$asset${v.sep}$quote"
         try {
            val b = ask(v.book, mapOf("pair" to p))
            val bids = b.rows("bids"); val asks = b.rows("asks")
            val bid = (bids.firstOrNull() as? Data)?.num("price")
            val askp = (asks.firstOrNull() as? Data)?.num("price")
            val price = when {
               bid != null && askp != null -> (bid + askp) / 2.0
               bid != null -> bid
               askp != null -> askp
               else -> null
            }
            if (price != null && price > 0.0) return " (≈ ${fmt(amount * price)} USD)"
         } catch (e: Exception) { }
      }
      return ""
   }
   info("${if (isBuy) "buying $base with" else "selling"} ${fmt(total)} $spend${usdSuffix(spend, total)} on $pair until ${utc(endTs)}, $cycles cycle(s) of ${cycleSec / 60}m")

   val market = ask(v.market)
   debug("market $pair: ${market}")
   var s: Data = market + mapOf(
      "side" to side, "total" to total, "escrow" to v.escrow, "serverPostOnly" to v.serverPostOnly, 
      "startTs" to startTs, "endTs" to endTs, "cycleSec" to cycleSec, "cycles" to cycles,
   )

   fun observe(s: Data): Data = s + mapOf(
      "now" to nowSec(),
      "available" to (ask(v.balances)[spend] ?: 0.0),
      "orders" to ask(v.orders),
      "book" to ask(v.book),
   )

   @Suppress("UNCHECKED_CAST")
   fun act(s: Data) {
      val orders = s["orders"] as List<Data>
      if (s["newCycle"] == true)
         info("cycle ${s["window"]}/${s["cycles"]}: goal ${pct(s.num("goalPct"))} (spent ${fmt(s.num("spent"))}/${fmt(total)} $spend), quota ${fmt(s.num("quota"))} $spend${usdSuffix(spend, s.num("quota"))}")
      val order = s["orderPct"]?.let { "order ${pct(it as Double)} filled" } ?: "${orders.size} live"
      debug("goal ${pct(s.num("goalPct"))}, $order, quota ${fmt(s.num("quota"))} $spend, best ${s["price"]?.let { fmt(it as Double) }} -> ${s["action"]} ${s["why"] ?: ""}")
      when (s["action"]) {
         "idle" -> {
            cancelAll(orders)
            info("cycle ${s["window"]} quota filled, sleeping ${s["sleep"]}s")
         }

         "replace" -> {
            cancelAll(orders)
            val o = ask(v.place, mapOf("side" to side, "qty" to s.num("qty"), "price" to s.num("price")))
            info("placed $side ${fmt(o.num("qty"))} $base @ ${fmt(o.num("price"))} (${o["id"]})")
         }
      }
   }

   fun loop(): Int {
      while (true) {
         var pause = TICK_SECONDS
         try {
            s = plan(observe(s))
            if (s["action"] == "stop") {
               info("${s["why"]}: goal ${pct(s.num("goalPct"))} (spent ${fmt(s.num("spent"))}/${fmt(total)} $spend)")
               return 0
            }
            act(s)
            pause = s.long("sleep")
         } catch (e: ApiError) {
            if (e.status == 401) {
               err("fatal auth failure: ${e.message}")
               return 2
            }
            err("api error (HTTP ${e.status}): ${e.message}; retry in ${pause}s")
         } catch (e: Exception) {
            err("${e.message}; retry in ${pause}s")
         }
         sleep(pause)
      }
   }

   val code = loop()

   runCatching { cancelAll(ask(v.orders)) }.onFailure { err("cleanup failed: ${it.message}") }
   runCatching {
      val end = ask(v.balances)
      val dQuote = (end[quote] ?: 0.0) - (startBalances[quote] ?: 0.0)
      val dBase = (end[base] ?: 0.0) - (startBalances[base] ?: 0.0)
      info(if (isBuy) "done: spent ${fmt(-dQuote)} $quote, acquired ${fmt(dBase)} $base" else "done: sold ${fmt(-dBase)} $base, received ${fmt(dQuote)} $quote")
   }.onFailure { err("summary unavailable: ${it.message}") }
   return code
}
