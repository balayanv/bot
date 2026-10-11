package bot

import core.*
import lib.NEXA_URL
import lib.NKYC_URL
import lib.hmacHex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.random.Random

/**
 * Simulated exchange: an [Http] answering nexa or nkyc requests from an in-memory market
 * on a virtual clock (sleeping advances time), so a 30-day zit runs in seconds while the
 * real venue code - request building, signing, parsing - runs end to end.
 *
 * The mid follows a sinusoid PRICE_LOW..PRICE_HIGH plus noise. Our orders fill fully when
 * the mid crosses them and sometimes partially when it is within the spread.
 */

private const val PRICE_LOW = 350.0
private const val PRICE_HIGH = 450.0
private const val HALF_PERIOD_DAYS = 0.05 // low -> high
private const val SPREAD = 0.003
private const val NOISE = 0.5 // max mid jump per market tick, on top of the sinusoid
private const val FEE = 0.002
private const val MARKET_TICK = 10L
private const val QUOTE_FUND = 10000.0
private const val BASE_FUND = 2.0

/** Runs zit against the simulated market; returns {code, quote, base, placed, cancelled, fills} (quote/base = wallet change). */
fun sim(args: List<String>): Data {
   val dialect = args.getOrNull(0)
   val v = venues[dialect] ?: usage()
   val pair = args.getOrNull(3)?.takeIf { v.sep in it } ?: usage()
   val (base, quote) = pair.split(v.sep)

   val realNow = nowSec
   val realSleep = sleep
   val start = realNow() / 3600 * 3600
   var now = start
   var marketAt = start
   var mid = PRICE_LOW
   val rng = Random(42)
   val wallet = mutableMapOf(quote to QUOTE_FUND, base to BASE_FUND) // available, escrow excluded
   val orders = mutableListOf<MutableMap<String, Any>>() // id, side, price, qty, filled, status
   var placed = 0
   var cancelled = 0
   var fills = 0
   var volBase = 0.0
   var volQuote = 0.0

   fun rem(o: Data) = o.num("qty") - o.num("filled")
   fun active() = orders.filter { it["status"] == "active" }

   /** Forget all but the last few finished orders, so long runs stay linear. */
   fun prune() {
      val done = orders.filter { it["status"] != "active" }
      if (done.size > 20) orders.removeAll(done.dropLast(10).toSet())
   }
   fun escrowed(asset: String) = active().sumOf { o ->
      if (o["side"] == "buy") if (asset == quote) rem(o) * o.num("price") * (1 + FEE) else 0.0
      else if (asset == base) rem(o) else 0.0
   }

   fun bestBid() = round(mid * (1 - SPREAD / 2) * 10 - 0.5) / 10
   fun bestAsk() = round(mid * (1 + SPREAD / 2) * 10 + 0.5) / 10

   fun fill(o: MutableMap<String, Any>, q: Double) {
      val price = o.num("price")
      o["filled"] = o.num("filled") + q
      if (o["side"] == "buy") wallet[base] = wallet.getValue(base) + q // the fee was escrowed with the order
      else wallet[quote] = wallet.getValue(quote) + q * price * (1 - FEE)
      if (rem(o) <= 1e-9) o["status"] = "filled".also { prune() }
      fills++; volBase += q; volQuote += q * price
      debug("SIM fill ${o["id"]}: ${fmt(q)} $base @ ${fmt(price)}")
   }

   fun priceAt(sec: Long): Double {
      val half = HALF_PERIOD_DAYS * 86400
      return PRICE_LOW + (PRICE_HIGH - PRICE_LOW) * (1 - cos(PI * (sec - start) / half)) / 2
   }

   fun advanceMarket() {
      while (marketAt <= now) {
         mid = (mid + priceAt(marketAt) - priceAt(marketAt - MARKET_TICK) + (rng.nextDouble() - 0.5) * 2 * NOISE).coerceIn(PRICE_LOW, PRICE_HIGH)
         for (o in active()) {
            val p = o.num("price")
            val buy = o["side"] == "buy"
            if (if (buy) mid < p else mid > p) fill(o, rem(o))
            else if ((if (buy) mid <= p * (1 + SPREAD / 2) else mid >= p * (1 - SPREAD / 2)) && rng.nextDouble() < 0.2)
               fill(o, rem(o) * (0.2 + 0.6 * rng.nextDouble()))
         }
         marketAt += MARKET_TICK
      }
   }

   /** The placed order, or an error message. */
   fun place(side: String, requested: Double, price: Double, postOnly: Boolean): Any {
      // nkyc truncates quantity to the market's decimals, then enforces its minimum order value
      val qty = if (dialect == "nkyc") floor(requested * 1e4 + 1e-9) / 1e4 else requested
      if (dialect == "nkyc" && qty * price < 1.0) return "Minimum order value for this market is 1 $quote"
      if (postOnly && (if (side == "buy") price >= bestAsk() else price <= bestBid())) return "post-only order would cross"
      val asset = if (side == "buy") quote else base
      val need = if (side == "buy") qty * price * (1 + FEE) else qty
      if (wallet.getValue(asset) < need - 1e-9) return "insufficient $asset: need ${fmt(need)}, have ${fmt(wallet.getValue(asset))}"
      wallet[asset] = wallet.getValue(asset) - need
      val o = mutableMapOf<String, Any>("id" to "s${++placed}", "side" to side, "price" to price, "qty" to qty, "filled" to 0.0, "status" to "active")
      orders += o
      debug("SIM placed ${o["id"]}: $side ${fmt(qty)} $base @ ${fmt(price)}")
      return o
   }

   /** False when the order isn't active (unknown, filled or already cancelled). */
   fun cancel(id: String): Boolean {
      val o = active().firstOrNull { it["id"] == id } ?: return false
      if (o["side"] == "buy") wallet[quote] = wallet.getValue(quote) + rem(o) * o.num("price") * (1 + FEE)
      else wallet[base] = wallet.getValue(base) + rem(o)
      o["status"] = "cancelled"
      prune()
      cancelled++
      return true
   }

   fun book(): Data = mapOf(
      "bids" to (0..2).map { mapOf("price" to bestBid() - it * 0.1, "quantity" to 0.02 + rng.nextDouble() * 0.28) },
      "asks" to (0..2).map { mapOf("price" to bestAsk() + it * 0.1, "quantity" to 0.02 + rng.nextDouble() * 0.28) },
   )

   fun respond(status: Int, body: Any): Response = mapOf("status" to status, "body" to toJson(body))
   fun bodyOf(r: Request): Data = parseJson(r.str("body")).asData()

   fun nexaOrder(o: Data) = mapOf(
      "order_id" to o["id"], "pair" to pair, "side" to o["side"], "price" to o["price"], "quantity" to o["qty"],
      "quantity_filled" to o["filled"], "quantity_remaining" to rem(o), "status" to o["status"],
   )

   fun nexaRoute(r: Request): Response {
      val path = r.str("url").removePrefix(NEXA_URL)
      if (path.startsWith("/orderbook/")) return respond(200, book())
      if (r.data("headers")["X-API-Key"] != "sim") return respond(401, mapOf("error" to "Invalid API key"))
      return when {
         path == "/balances" -> respond(200, mapOf("balances" to listOf(base, quote).associateWith { mapOf("available" to wallet[it]) }))
         path.startsWith("/orders?") -> respond(200, mapOf("orders" to active().map(::nexaOrder)))
         path == "/order" -> bodyOf(r).let { b ->
            when (val o = place(b.str("side"), b.num("quantity"), b.num("price"), b["postOnly"] == true)) {
               is String -> respond(400, mapOf("error" to o))
               else -> respond(200, mapOf("order" to nexaOrder(o.asData())))
            }
         }

         r["method"] == "DELETE" -> if (cancel(path.substringAfterLast('/'))) respond(200, mapOf("ok" to true))
         else respond(404, mapOf("error" to "order not found"))

         else -> respond(404, mapOf("error" to "no route ${r["method"]} $path"))
      }
   }

   var lastNonce = 0L
   fun nkycError(status: Int, code: Int, msg: String) = respond(status, mapOf("error" to mapOf("code" to code, "message" to msg)))

   // the list variant carries symbol and omits remainQuantity/isActive, createorder the reverse
   fun nkycOrder(o: Data, full: Boolean) = mapOf(
      "id" to o["id"], "side" to o["side"], "type" to "limit", "price" to o["price"], "quantity" to o["qty"], "executedQuantity" to o["filled"],
      "status" to when (o["status"]) {
         "active" -> if (o.num("filled") > 0) "Partly Filled" else "New"
         "filled" -> "Filled"
         else -> "Cancelled"
      },
   ) + if (full) mapOf("remainQuantity" to rem(o), "isActive" to (o["status"] == "active")) else mapOf("symbol" to pair)

   fun nkycRoute(r: Request): Response {
      val path = r.str("url").removePrefix(NKYC_URL).substringBefore('?')
      if (path == "/market/orderbook") return respond(200, book())
      if (path.startsWith("/market/getbysymbol/")) return respond(200, mapOf("quantityDecimals" to 4, "isMinQuoteActive" to true, "minQuote" to 1))
      val h = r.data("headers")
      val nonce = (h["X-API-NONCE"] as? String)?.toLongOrNull() ?: return nkycError(401, 401, "Authorisation required")
      if (nonce <= lastNonce) return nkycError(401, 401, "Nonce expired or invalid")
      lastNonce = nonce
      if (h["X-API-SIGN"] != hmacHex("sim", "${h["X-API-KEY"]}${r["url"]}${r["body"] ?: ""}$nonce")) return nkycError(401, 401, "Not Authorized")
      return when (path) {
         "/balances" -> respond(200, listOf(base, quote).map { mapOf("asset" to it, "available" to wallet[it], "held" to escrowed(it)) })
         // like the real api, finished orders come back even when only active ones are asked for
         "/account/orders" -> respond(200, orders.map { nkycOrder(it, false) })
         "/createorder" -> bodyOf(r).let { b ->
            when (val o = place(b.str("side"), b.num("quantity"), b.num("price"), postOnly = false)) {
               is String -> nkycError(400, 10001, o)
               else -> respond(200, nkycOrder(o.asData(), true))
            }
         }

         "/cancelorder" -> if (cancel(bodyOf(r).str("id"))) respond(200, mapOf("success" to true))
         else nkycError(400, 20002, "Not found")

         else -> nkycError(404, 404, "no route $path")
      }
   }

   nowSec = { now }
   sleep = { now += it; advanceMarket() }
   val exchange: Http = { r -> advanceMarket(); if (dialect == "nexa") nexaRoute(r) else nkycRoute(r) }
   val creds = v.keys.associateWith { "sim" }
   info("SIM sinusoid ${fmt(PRICE_LOW)}..${fmt(PRICE_HIGH)} (${HALF_PERIOD_DAYS}d low->high), funds ${fmt(QUOTE_FUND)} $quote + ${fmt(BASE_FUND)} $base")

   val code = run(v, traced(exchange), creds, args.drop(1))

   val dQuote = wallet.getValue(quote) + escrowed(quote) - QUOTE_FUND
   val dBase = wallet.getValue(base) + escrowed(base) - BASE_FUND
   info("SIM ${(now - start) / 86400.0}d, mid ended ${fmt(mid)}; $placed placed / $cancelled cancelled / $fills fills")
   info("SIM quote ${fmt(dQuote)} $quote, base ${fmt(dBase)} $base")
   if (volBase > 0) info("SIM avg fill ${fmt(volQuote / volBase)} $quote/$base (sinusoid midpoint ${fmt((PRICE_LOW + PRICE_HIGH) / 2)})")

   nowSec = realNow
   sleep = realSleep
   return mapOf("code" to code, "quote" to dQuote, "base" to dBase, "placed" to placed, "cancelled" to cancelled, "fills" to fills)
}
