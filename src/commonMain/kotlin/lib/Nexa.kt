package lib

import core.Balances
import core.Credentials
import core.Data
import core.Level
import core.Order
import core.OrderBook
import core.Price
import core.Qty
import core.Request
import core.Response
import utils.asData
import utils.getData
import utils.getInt
import utils.getNum
import utils.getRows
import utils.getStr
import core.http
import utils.strOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import utils.decimal8

class ApiError(val status: Int, message: String) : Exception(message)

const val NEXA_BASE = "https://neoxa.exchange"

private fun req(method: String, path: String, creds: Credentials?, body: String? = null): Request =
    mutableMapOf<String, Any>().apply {
        put("method", method)
        put("url", NEXA_BASE + path)
        put("headers", creds?.toNexaCreds() ?: mutableMapOf<String, String>())
        body?.let { put("body", it) }
    }

fun Credentials.toNexaCreds(): Map<String, String> = mutableMapOf(
    "X-API-Key" to getValue("NEOXA_API_KEY"),
    "X-API-Secret" to getValue("NEOXA_API_SECRET"),
)

fun balancesReq(c: Credentials): Request = req("GET", "/api/exchange/balances", c)

fun ordersReq(c: Credentials, status: String? = "active", pair: String? = null, limit: Int? = null): Request {
    val query = buildList {
        status?.let { add("status=$it") }
        pair?.let { add("pair=$it") }
        limit?.let { add("limit=$it") }
    }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    return req("GET", "/api/exchange/orders$query", c)
}

fun historyReq(c: Credentials, limit: Int? = null): Request =
    req("GET", "/api/exchange/orders/history" + (limit?.let { "?limit=$it" } ?: ""), c)

fun bookReq(pair: String): Request = req("GET", "/api/exchange/orderbook/$pair?depth=1", null)
fun placeReq(c: Credentials, pair: String, side: String, qty: Qty, price: Price, postOnly: Boolean): Request =
    req(
        "POST", "/api/exchange/order", c,
        buildJsonObject {
            put("pair", pair)
            put("side", side)
            put("quantity", decimal8(qty))
            put("price", decimal8(price))
            if (postOnly) put("postOnly", true)
        }.toString(),
    )

fun cancelReq(orderId: String, c: Credentials): Request = req("DELETE", "/api/exchange/order/$orderId", c)

suspend fun book(t: http, pair: String): OrderBook {
    val r = t(bookReq(pair))
    ensure(r, "orderbook")
    return bookFrom(asData(parse(r.getStr("body"))), pair)
}

suspend fun balances(t: http, c: Credentials): Balances {
    val r = t(balancesReq(c))
    ensure(r, "balances")
    return balancesFrom(asData(parse(r.getStr("body"))).getData("balances"))
}

suspend fun orders(t: http, c: Credentials, pair: String? = null): List<Order> {
    val r = t(ordersReq(c, status = "active", pair = pair))
    ensure(r, "orders")
    return asData(parse(r.getStr("body"))).getRows("orders").map { orderFrom(asData(it)) }
}

suspend fun historyOrders(t: http, c: Credentials, limit: Int? = null): List<Order> {
    val r = t(historyReq(c, limit))
    ensure(r, "history")
    return asData(parse(r.getStr("body"))).getRows("orders").map { orderFrom(asData(it)) }
}

suspend fun place(t: http, c: Credentials, pair: String, side: String, qty: Qty, price: Price, postOnly: Boolean): Order {
    val r = t(placeReq(c, pair, side, qty, price, postOnly))
    ensure(r, "order")
    return orderFrom(asData(parse(r.getStr("body"))).getData("order"))
}

suspend fun cancel(t: http, c: Credentials, orderId: String): Boolean =
    t(cancelReq(orderId, c)).getInt("status") == 200

suspend fun cancelAllOn(t: http, c: Credentials, pair: String): List<String> {
    val cancelled = mutableListOf<String>()
    for (o in orders(t, c, pair)) {
        val id = o.getStr("orderId")
        if (cancel(t, c, id)) cancelled += id
        else throw ApiError(0, "failed to cancel $id")
    }
    return cancelled
}

private fun ensure(r: Response, desc: String) {
    if (r.getInt("status") != 200) throw ApiError(r.getInt("status"), apiError(r.getStr("body")))
}

fun parse(json: String): Any = conv(Json.parseToJsonElement(json))

private fun conv(e: JsonElement): Any = when (e) {
    is JsonNull -> error("null at root")
    is JsonPrimitive -> e.doubleOrNull ?: e.content
    is JsonArray -> e.mapNotNull { if (it is JsonNull) null else conv(it) }
    is JsonObject -> e.entries.mapNotNull { (k, v) ->
        if (v is JsonNull) null else k to conv(v)
    }.toMap()
}

fun orderFrom(raw: Data): Order = mutableMapOf(
    "orderId" to (raw.strOrNull("order_id") ?: raw.strOrNull("id")
        ?: throw IllegalStateException("expected order id in: $raw")),
    "side" to raw.getStr("side"),
    "pair" to raw.getStr("pair"),
    "price" to raw.getNum("price"),
    "qty" to raw.getNum("quantity"),
    "qtyFilled" to raw.getNum("quantity_filled"),
    "qtyRemaining" to raw.getNum("quantity_remaining"),
    "status" to raw.getStr("status"),
)

fun bookFrom(raw: Data, pair: String): OrderBook {
    fun levels(key: String): List<Level> =
        raw.getRows(key).map { l ->
            val m = asData(l)
            mutableMapOf("price" to m.getNum("price"), "qty" to m.getNum("quantity"))
        }
    return mutableMapOf("pair" to pair, "bids" to levels("bids"), "asks" to levels("asks"))
}

fun balancesFrom(raw: Data): Balances =
    mutableMapOf<String, Any>().apply {
        raw.forEach { (asset, v) ->
            val b = asData(v)
            put(
                asset,
                mutableMapOf(
                    "available" to b.getNum("available"),
                    "locked" to b.getNum("locked"),
                    "total" to b.getNum("total"),
                ),
            )
        }
    }

fun Response.getStatus(): Int = this.getInt("status")

private fun apiError(body: String): String {
    val msg = runCatching {
        asData(parse(body)).strOrNull("error") ?: asData(parse(body)).strOrNull("message")
    }.getOrNull()
    return msg ?: body.take(200)
}
