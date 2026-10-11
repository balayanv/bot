import bot.run
import bot.sim
import bot.usage
import bot.venues
import core.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
   val (opts, rest) = args.partition { it.startsWith("--") }
   for (o in opts) logLevel = LOG_LEVELS.indexOf(o.removePrefix("--log=")).takeIf { o.startsWith("--log=") && it >= 0 } ?: usage()
   // one log file family per run kind, e.g. logs/nexa-buy-BTCB2_USDC.log, logs/sim-nkyc-buy-BTCB2_USDT.log
   val sim = rest.firstOrNull() == "sim"
   logTo("logs", rest.take(if (sim) 5 else 4).filter { it != "zit" }.joinToString("-").replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "bot" })

   val code = if (rest.firstOrNull() == "sim") sim(rest.drop(1)).int("code") else {
      val v = venues[rest.firstOrNull()] ?: usage()
      val creds = v.keys.associateWith { System.getenv(it) ?: error("expected environment variable $it") }
      run(v, traced(transport()), creds, rest.drop(1))
   }
   exitProcess(code)
}
