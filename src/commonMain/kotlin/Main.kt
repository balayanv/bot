import bot.runNexa
import bot.sim.sim
import bot.usage
import kotlinx.coroutines.runBlocking
import utils.credentials
import utils.transport
import kotlin.system.exitProcess

fun main(args: Array<String>): Unit = runBlocking {
    var logLevel = "debug"
    val rest = args.filter { arg ->
        if (arg.startsWith("--log=")) {
            logLevel = arg.substringAfter('=').lowercase()
            false
        } else true
    }
    if (logLevel !in listOf("info", "debug", "trace")) usage()

    if (rest.isEmpty()) usage()

    val code = when (rest[0]) {
        "nexa" -> runNexa(
            transport(),
            credentials("NEOXA_API_KEY", "NEOXA_API_SECRET"),
            rest.drop(1).toTypedArray(),
            logLevel.uppercase(),
        )

        "sim" -> sim(rest.drop(1).toTypedArray(), logLevel.uppercase())

        else -> usage()
    }
    exitProcess(code)
}
