package utils

import core.http
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent

fun transport(): http {
    val client = HttpClient(Curl) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
    }
    return { r ->
        val resp = client.request(r.getStr("url")) {
            method = HttpMethod.parse(r.getStr("method"))

            r.getMap<String,String>("headers")
                .forEach { (k, v) -> header(k, v) }
            r.strOrNull("body")?.let { setBody(TextContent(it, ContentType.Application.Json)) }
        }

        mutableMapOf(
            "status" to resp.status.value,
            "body" to resp.bodyAsText()
        )
    }
}
