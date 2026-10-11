package lib

import core.*
import java.net.URLEncoder
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * nonkyc.io: HMAC-signed requests, BASE/QUOTE pairs, no post-only flag.
 *
 * X-API-SIGN = hex(hmac_sha256(secret, apiKey + fullUrl + body + nonce)); the nonce
 * comes from the input map so signing stays a pure function.
 */

const val NKYC_URL = "https://api.nonkyc.io/api/v2"

fun hmacHex(key: String, message: String): String {
   val mac = Mac.getInstance("HmacSHA256")
   mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
   return HexFormat.of().formatHex(mac.doFinal(message.toByteArray()))
}

private fun url(path: String, query: Map<String, String> = emptyMap()): String {
   val qs = query.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
   return NKYC_URL + path + (if (qs.isEmpty()) "" else "?$qs")
}

private fun signed(input: Data, method: String, path: String, query: Map<String, String> = emptyMap(), body: Data? = null): Request {
   val url = url(path, query)
   val json = body?.let(::toJson)
   val key = input.creds().str("NOKYC_API_KEY")
   val nonce = input.long("nonce").toString()
   val sign = hmacHex(input.creds().str("NOKYC_API_SECRET"), key + url + (json ?: "") + nonce)
   return request(method, url, mapOf("X-API-KEY" to key, "X-API-NONCE" to nonce, "X-API-SIGN" to sign), json)
}

/**
 * Live is the isActive flag when present: statuses vary (New, Active, Partly Filled, ...).
 * The list variant may omit remainQuantity.
 */
private fun nkycOrder(o: Data): Data {
   val remaining = if ("remainQuantity" in o) o.num("remainQuantity") else o.num("quantity") - o.num("executedQuantity")
   val live = o["isActive"] as? Boolean ?: (o.str("status").lowercase() !in setOf("filled", "cancelled", "canceled", "expired", "rejected"))
   return mapOf("id" to o.str("id"), "price" to o.num("price"), "qty" to o.num("quantity"), "remaining" to if (live) remaining else 0.0)
}

val nkyc = Venue(
   keys = listOf("NOKYC_API_KEY", "NOKYC_API_SECRET"),
   sep = '/',
   escrow = 1.002,
   serverPostOnly = false,
   // the server truncates quantity to quantityDecimals, then checks the minimum order value
   market = Call(
      { request("GET", url("/market/getbysymbol/" + it.str("pair").replace('/', '_'))) },
      { r ->
         val m = ok(r).asData()
         mapOf("qtyDecimals" to m.num("quantityDecimals").toInt(), "minNotional" to if (m["isMinQuoteActive"] == true) m.num("minQuote") else 0.0)
      },
   ),
   balances = Call(
      { signed(it, "GET", "/balances") },
      { r -> (ok(r) as Rows).map { it.asData() }.associate { it.str("asset") to it.num("available") } },
   ),
   // the server returns finished orders too even when asked for active ones: nkycOrder zeroes them
   orders = Call(
      { signed(it, "GET", "/account/orders", mapOf("status" to "active", "symbol" to it.str("pair"))) },
      { r -> (ok(r) as Rows).map { nkycOrder(it.asData()) }.filter { it.num("remaining") > 0 } },
   ),
   book = Call(
      { request("GET", url("/market/orderbook", mapOf("symbol" to it.str("pair"), "limit" to "1"))) },
      { r -> ok(r).asData().let { mapOf("bids" to levels(it.rows("bids")), "asks" to levels(it.rows("asks"))) } },
   ),
   // strictValidate stays off: the server truncates our 8dp numbers to the market's precision
   place = Call(
      {
         signed(
            it, "POST", "/createorder",
            body = mapOf("symbol" to it.str("pair"), "side" to it.str("side"), "type" to "limit", "quantity" to dec8(it.num("qty")), "price" to dec8(it.num("price"))),
         )
      },
      { r -> nkycOrder(ok(r).asData()) },
   ),
   // the active list lags /cancelorder: "not found" (20002) means already filled or cancelled - done either way
   cancel = Call(
      { signed(it, "POST", "/cancelorder", body = mapOf("id" to it.str("id"))) },
      { r ->
         val code = runCatching { parseJson(r.str("body")).asData().data("error")["code"].toString() }.getOrNull()
         if (code != "20002") ok(r)
      },
   ),
)
