package bot

import bot.algo.zeroImpactTrade
import core.Credentials
import core.Data
import core.Response
import core.http
import kotlinx.coroutines.runBlocking
import lib.balancesReq
import lib.cancelReq
import lib.historyReq
import lib.ordersReq
import lib.parse
import lib.placeReq
import utils.asData
import utils.err
import utils.getInt
import utils.getIntOrNull
import utils.getStr
import utils.log
import utils.parseDeadline
import utils.rowsOrNull
import utils.strOrNull
import utils.unixHourNow
import utils.credentials
import utils.transport
import kotlin.system.exitProcess

fun usage(): Nothing {
    """
        usage: bot [options] <command> [args]

        options:
          --log=<info|debug|trace>              logging level (default debug)

        nexa — neoxa.exchange (env: NEOXA_API_KEY, NEOXA_API_SECRET):
          nexa balances                          account balances
          nexa orders [status] [pair] [limit]    active orders (status: active|open|partial|...)
          nexa history [limit]                   filled/cancelled orders
          nexa order <order_id>                  one order, active or historical
          nexa place <pair> <buy|sell> <qty> <price> [postonly]   limit order
          nexa cancel <order_id>                 cancel an order
          nexa zit <buy|sell> <pair> <deadline>  zero-impact gradual maker trade:
                                                 spend/sell the full available balance
                                                 as best bid/ask post-only orders,
                                                 hourly schedule until <deadline>
                                                 ("30/10/2026" end of UTC day, or days: "30", fractional ok;
                                                  safe to re-run the same command after interruption)

        sim — simulated neoxa exchange (virtual clock, synthetic market):
          sim nexa <command> [args]             any nexa command, e.g.:
          sim nexa zit buy BTCB2_USDC 30         30-day zit, runs in seconds
          market: sinusoid priceLow..priceHigh (low->high in fullCycleDurationInDays),
          config vals at top of bot/sim/sim.kt
        """.trimIndent().err()
    exitProcess(1)
}

suspend fun runNexa(t: http, c: Credentials, args: Array<String>, logLevel: String = "DEBUG"): Int {
    if (args.isEmpty()) usage()

    try {
        return when (args[0]) {
            "balances" -> runCliCommand {
                t(balancesReq(c)).printResponse()
            }

            "orders" -> runCliCommand {
                val request = ordersReq(
                    c,
                    status = args.getOrNull(1),
                    pair = args.getOrNull(2),
                    limit = args.getIntOrNull(3),
                )
                t(request).printResponse()
            }

            "history" -> runCliCommand {
                val request = historyReq(c, args.getIntOrNull(1))
                t(request).printResponse()
            }

            "order" -> runCliCommand {
                val id = args.getOrNull(1) ?: usage()
                findOrder(t, c, id).printResponse()
            }

            "place" -> runCliCommand {
                val pair = args.getOrNull(1) ?: usage()
                val side = args.getOrNull(2) ?: usage()
                if (side != "buy" && side != "sell") usage()
                val quantity = args.getOrNull(3)?.toDoubleOrNull() ?: usage()
                val price = args.getOrNull(4)?.toDoubleOrNull() ?: usage()
                val postOnly = args.getOrNull(5) == "postonly"
                t(placeReq(c, pair, side, quantity, price, postOnly)).printResponse()
            }

            "cancel" -> runCliCommand {
                val id = args.getOrNull(1) ?: usage()
                t(cancelReq(id, c)).printResponse()
            }

            "zit" -> runCliCommand {
                zit(t, c, args.drop(1).toTypedArray(), logLevel)
            }

            else -> {
                usage()
            }
        }
    } catch (e: Exception) {
        "error: ${e.message}".err()
        return 1
    }
}

suspend inline fun runCliCommand(crossinline function: suspend () -> Unit): Int {
    try {
        function.invoke()
        return 0
    } catch (e: Exception) {
        e.printStackTrace()
        return 1
    }
}

private suspend fun findOrder(t: http, c: Credentials, orderId: String): Response {
    val requests = listOf(
        ordersReq(c, status = null),
        historyReq(c),
    )
    for (r in requests) {
        val resp = t(r)
        if (resp.getInt("status") != 200) continue
        val orders = asData(parse(resp.getStr("body"))).rowsOrNull("orders") ?: continue
        val found = orders.firstOrNull { (it as? Data)?.get("order_id") == orderId }
        if (found != null) return mutableMapOf("status" to 200, "body" to found.toString())
    }
    return mutableMapOf("status" to 404, "body" to "{\"error\":\"order not found\"}")
}

private suspend fun zit(t: http, c: Credentials, args: Array<String>, logLevel: String): Int {
    val side = args.getOrNull(0)?.lowercase()
    val pair = args.getOrNull(1)
    val deadline = args.getOrNull(2)
    if (side != "buy" && side != "sell") usage()
    if (pair == null || !pair.contains('_')) usage()
    val endHour = parseDeadline(deadline ?: usage()) ?: usage()
    if (endHour <= unixHourNow()) {
        "error: deadline '$deadline' has already passed".err()
        return 1
    }
    return zeroImpactTrade(t, c, side, pair, endHour, logLevel)
}

fun Response.printResponse() {
    "body : ${this.getStr("body")}".log()
    "code : ${this.getInt("status")}".log()
}
