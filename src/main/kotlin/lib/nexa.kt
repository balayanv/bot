package lib

import core.*

/** neoxa.exchange: plain key/secret headers, BASE_QUOTE pairs, server-side post-only. */

const val NEXA_URL = "https://neoxa.exchange/api/exchange"

private fun nexaRequest(input: Data, method: String, path: String, body: Data? = null): Request = request(
   method, NEXA_URL + path,
   mapOf("X-API-Key" to input.creds().str("NEOXA_API_KEY"), "X-API-Secret" to input.creds().str("NEOXA_API_SECRET")),
   body?.let(::toJson),
)

private fun nexaOrder(o: Data): Data = mapOf(
   "id" to (o["order_id"] ?: o.getValue("id")).toString(),
   "price" to o.num("price"),
   "qty" to o.num("quantity"),
   "remaining" to o.num("quantity_remaining"),
)

val nexa = Venue(
   keys = listOf("NEOXA_API_KEY", "NEOXA_API_SECRET"),
   sep = '_',
   escrow = 1.002,
   serverPostOnly = true,
   market = Call({ null }, { mapOf("qtyDecimals" to 8, "minNotional" to 0.0) }),
   balances = Call(
      { nexaRequest(it, "GET", "/balances") },
      { r -> ok(r).asData().data("balances").mapValues { it.value.asData().num("available") } },
   ),
   orders = Call(
      { nexaRequest(it, "GET", "/orders?status=active&pair=${it.str("pair")}") },
      { r -> ok(r).asData().rows("orders").map { nexaOrder(it.asData()) }.filter { it.num("remaining") > 0 } },
   ),
   book = Call(
      { request("GET", "$NEXA_URL/orderbook/${it.str("pair")}?depth=1") },
      { r -> ok(r).asData().let { mapOf("bids" to levels(it.rows("bids")), "asks" to levels(it.rows("asks"))) } },
   ),
   place = Call(
      {
         nexaRequest(
            it, "POST", "/order",
            mapOf("pair" to it.str("pair"), "side" to it.str("side"), "quantity" to dec8(it.num("qty")), "price" to dec8(it.num("price")), "postOnly" to true),
         )
      },
      { r -> nexaOrder(ok(r).asData().data("order")) },
   ),
   cancel = Call({ nexaRequest(it, "DELETE", "/order/${it.str("id")}") }, { r -> ok(r); Unit }),
)
