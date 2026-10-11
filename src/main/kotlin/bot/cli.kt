package bot

import core.*
import lib.Venue
import lib.nexa
import lib.nkyc
import kotlin.system.exitProcess

val venues = mapOf("nexa" to nexa, "nkyc" to nkyc)

fun usage(): Nothing {
   System.err.println(
      """
      usage: bot [--log=info|debug|trace] <venue> zit <buy|sell> <pair> <deadline> [cycle_minutes]
             bot [--log=...] sim <venue> zit ...           same, against a simulated market

      venues:
        nexa   neoxa.exchange, pairs BASE_QUOTE (env: NEOXA_API_KEY, NEOXA_API_SECRET)
        nkyc   nonkyc.io,      pairs BASE/QUOTE (env: NOKYC_API_KEY, NOKYC_API_SECRET)

      zit: zero-impact gradual maker trade - spends/sells the full available balance as
           best bid/ask orders, quota split into even cycles until <deadline>.
           deadline: "30/10/2026" (end of that UTC day) or days from now ("30", "0.5")
           cycle_minutes: default 60. Safe to re-run the same command after interruption.
      """.trimIndent()
   )
   exitProcess(1)
}

fun run(v: Venue, t: Http, c: Credentials, args: List<String>): Int {
   if (args.getOrNull(0) != "zit") usage()
   val side = args.getOrNull(1)?.takeIf { it == "buy" || it == "sell" } ?: usage()
   val pair = args.getOrNull(2)?.takeIf { v.sep in it } ?: usage()
   val endTs = args.getOrNull(3)?.let(::parseDeadline) ?: usage()
   val cycleMinutes = args.getOrNull(4)?.let { it.toLongOrNull()?.takeIf { m -> m > 0 } ?: usage() } ?: 60
   if (endTs <= nowSec()) {
      err("deadline '${args[3]}' has already passed")
      return 1
   }
   return try {
      zit(v, t, c, side, pair, endTs, cycleMinutes * 60)
   } catch (e: ApiError) {
      err("HTTP ${e.status}: ${e.message}")
      if (e.status == 401) 2 else 1
   } catch (e: Exception) {
      err(e.stackTraceToString())
      1
   }
}
