package core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The real network: request map in, {status, body} out. */
fun transport(): Http {
   val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
   return { r ->
      val body = r["body"] as? String
      val request = HttpRequest.newBuilder(URI.create(r.str("url")))
         .timeout(Duration.ofSeconds(30))
         .method(r.str("method"), body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
         .apply { r.data("headers").forEach { (k, v) -> header(k, v.toString()) } }
         .apply { if (body != null) header("Content-Type", "application/json") }
         .build()
      val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
      mapOf("status" to resp.statusCode(), "body" to resp.body())
   }
}

/** Wraps an [Http] to log every exchange at trace level. */
fun traced(http: Http): Http = { r ->
   http(r).also { trace("http ${r["method"]} ${r["url"]} ${r["body"] ?: ""} -> ${it["status"]} ${it["body"]}") }
}
