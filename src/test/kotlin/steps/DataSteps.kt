package steps

import bot.minQty
import bot.orderQty
import bot.plan
import bot.sim
import bot.venues
import core.*
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * Generic steps: every piece of the bot is a function from data to data, so a scenario
 * is "given this map (as JSON), when this function runs, then the result matches this map".
 */
class DataSteps {
   private var input: Data = emptyMap()
   private var response: Response = emptyMap()
   private var result: Any? = null
   private var failure: Throwable? = null

   private val realNow = nowSec
   private val realLogLevel = logLevel

   @Before
   fun quiet() {
      logLevel = -1
   }

   @After
   fun restore() {
      nowSec = realNow
      logLevel = realLogLevel
   }

   private fun attempt(f: () -> Any?) {
      failure = null
      result = try {
         f()
      } catch (e: Exception) {
         failure = e
         null
      }
   }

   private fun call(venue: String, name: String) =
      (venues[venue] ?: error("unknown venue $venue")).calls[name] ?: error("unknown call $name")

   // ---- given

   @Given("the input:")
   fun theInput(json: String) {
      input = parseJson(json).asData()
   }

   @Given("the state:")
   fun theState(json: String) = theInput(json)

   @Given("a {int} response:")
   fun aResponse(status: Int, body: String) {
      response = mapOf("status" to status, "body" to body)
   }

   @Given("the clock is at {string}")
   fun theClockIsAt(iso: String) {
      val sec = Instant.parse(iso).epochSecond
      nowSec = { sec }
   }

   // ---- when

   @When("{word} builds the {word} request")
   fun buildsRequest(venue: String, name: String) = attempt { call(venue, name).request(input) }

   @When("{word} parses it as {word}")
   fun parsesAs(venue: String, name: String) = attempt { call(venue, name).parse(response) }

   @When("zit plans")
   fun zitPlans() = attempt { plan(input) }

   @When("{word} is applied to {string}")
   fun applied(function: String, arg: String) = attempt {
      when (function) {
         "dec8" -> dec8(arg.toDouble())
         "fmt" -> fmt(arg.toDouble())
         "pct" -> pct(arg.toDouble())
         "parseDeadline" -> parseDeadline(arg)?.let(::utc)
         "parseJson" -> parseJson(arg)
         "toJson" -> toJson(parseJson(arg))
         else -> error("no function $function")
      }
   }

   @When("{word} is applied to:")
   fun appliedToDoc(function: String, arg: String) = applied(function, arg)

   @When("buying with {double} quote at {double} on a market with {int} decimals")
   fun buyingWith(amount: Double, price: Double, decimals: Int) = attempt { orderQty(amount, price, true, 1.002, decimals) }

   @When("selling {double} base on a market with {int} decimals")
   fun selling(amount: Double, decimals: Int) = attempt { orderQty(amount, 1.0, false, 1.002, decimals) }

   @When("the minimum order of {double} quote is priced at {double} with {int} decimals")
   fun minimumOrder(notional: Double, price: Double, decimals: Int) = attempt { minQty(notional, price, decimals) }

   @When("I simulate {string}")
   fun simulate(args: String) = attempt { sim(args.split(' ')) }

   // ---- then

   @Then("the result is:")
   fun resultIs(json: String) = assertMatches(parseJson(json), exact = true)

   @Then("the result has:")
   fun resultHas(json: String) = assertMatches(parseJson(json), exact = false)

   @Then("the result is {string}")
   fun resultIsString(expected: String) {
      ok()
      check(result.toString() == expected) { "expected '$expected', got '$result'" }
   }

   @Then("the text is:")
   fun textIs(expected: String) = resultIsString(expected)

   @Then("the result has no {word}")
   fun resultHasNo(key: String) {
      ok()
      check(key !in (result as Data)) { "expected no $key in $result" }
   }

   @Then("the result is nothing")
   fun resultIsNothing() {
      check(failure == null && (result == null || result == Unit)) { "expected nothing, got $result / $failure" }
   }

   @Then("the result's {word} is between {double} and {double}")
   fun resultBetween(key: String, low: Double, high: Double) {
      ok()
      val v = (result as Data).num(key)
      check(v in low..high) { "$key = $v, expected $low..$high in $result" }
   }

   @Then("it fails with HTTP {int}: {string}")
   fun failsWith(status: Int, message: String) {
      val e = failure as? ApiError ?: error("expected ApiError, got ${failure ?: result}")
      check(e.status == status && e.message == message) { "expected HTTP $status '$message', got HTTP ${e.status} '${e.message}'" }
   }

   // ---- matching

   private fun ok() {
      failure?.let { throw AssertionError("unexpected failure: $it", it) }
   }

   private fun assertMatches(expected: Any?, exact: Boolean) {
      ok()
      val problem = mismatch(canon(expected), canon(result), exact, "$")
      check(problem == null) { "$problem\nactual: ${toJson(result)}" }
   }

   /** Numbers compare as doubles (JSON has no int/long/double distinction). */
   private fun canon(v: Any?): Any? = when (v) {
      is Map<*, *> -> v.entries.associate { it.key.toString() to canon(it.value) }
      is List<*> -> v.map(::canon)
      is Number -> v.toDouble()
      else -> v
   }

   /** Null when [actual] matches; exact = same keys, otherwise expected keys are a subset. */
   private fun mismatch(expected: Any?, actual: Any?, exact: Boolean, path: String): String? = when (expected) {
      is Map<*, *> -> when {
         actual !is Map<*, *> -> "$path: expected a map, got $actual"
         exact && expected.keys != actual.keys -> "$path: expected keys ${expected.keys}, got ${actual.keys}"
         else -> expected.entries.firstNotNullOfOrNull { (k, v) ->
            if (k !in actual) "$path.$k: missing" else mismatch(v, actual[k], exact, "$path.$k")
         }
      }

      is List<*> -> when {
         actual !is List<*> || actual.size != expected.size -> "$path: expected ${expected.size} items, got $actual"
         else -> expected.indices.firstNotNullOfOrNull { mismatch(expected[it], actual[it], exact, "$path[$it]") }
      }

      is Double -> if (actual is Double && abs(actual - expected) <= 1e-9 * max(1.0, abs(expected))) null
      else "$path: expected $expected, got $actual"

      else -> if (expected == actual) null else "$path: expected $expected, got $actual"
   }
}
