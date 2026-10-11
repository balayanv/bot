package lib

import core.*

/**
 * One exchange call as two pure functions: input map -> request map, response map -> data.
 * A null request means the answer is known without asking the server.
 *
 * Input keys: creds {ENV_NAME: value}, nonce (unix ms, strictly increasing), pair, side, qty, price, id.
 */
class Call<T>(val request: (Data) -> Request?, val parse: (Response) -> T)

/** The one impure step: send the built request and parse the reply. */
fun <T> Http.call(c: Call<T>, input: Data): T = c.parse(c.request(input)?.let(this) ?: emptyMap())

/**
 * An exchange as zit sees it: six calls returning normalized shapes, plus the facts
 * that differ per venue.
 *
 *   market   -> trading rules of a pair: {qtyDecimals, minNotional (smallest price * qty, in quote)}
 *   balances -> {asset: available}
 *   orders   -> our live orders on a pair: [{id, price, qty, remaining}]
 *   book     -> {bids: [{price, qty}], asks: [...]}, best first
 *   place    -> the placed order, same shape as [orders]
 *   cancel   -> throws unless the order is gone afterwards
 *
 * @param keys           env vars holding the credentials
 * @param sep            pair separator: BASE_QUOTE vs BASE/QUOTE
 * @param escrow         what the server locks on a buy, as a multiple of its notional
 *                       (the worst-case taker fee on top)
 * @param serverPostOnly whether the server rejects orders that would cross; when it
 *                       doesn't, zit refuses to place into a crossed book itself
 */
class Venue(
   val keys: List<String>,
   val sep: Char,
   val escrow: Double,
   val serverPostOnly: Boolean,
   val market: Call<Data>,
   val balances: Call<Map<String, Double>>,
   val orders: Call<List<Data>>,
   val book: Call<Data>,
   val place: Call<Data>,
   val cancel: Call<Unit>,
) {
   val calls: Map<String, Call<*>> =
      mapOf("market" to market, "balances" to balances, "orders" to orders, "book" to book, "place" to place, "cancel" to cancel)
}

fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Request =
   mapOf("method" to method, "url" to url, "headers" to headers) + (body?.let { mapOf("body" to it) } ?: emptyMap())

/** Parsed body of a 200, otherwise an [ApiError] carrying the venue's message. */
fun ok(r: Response): Any {
   if (r.int("status") == 200) return parseJson(r.str("body"))
   throw ApiError(r.int("status"), errorMessage(r.str("body")))
}

private fun errorMessage(body: String): String = runCatching {
   val d = parseJson(body).asData()
   when (val e = d["error"]) {
      is Map<*, *> -> e["description"] ?: e["message"]
      null -> d["message"]
      else -> e
   }
}.getOrNull()?.toString() ?: body.take(200)

fun levels(rows: Rows): List<Data> = rows.map { it.asData() }.map { mapOf("price" to it.num("price"), "qty" to it.num("quantity")) }

fun Data.creds(): Data = data("creds")
