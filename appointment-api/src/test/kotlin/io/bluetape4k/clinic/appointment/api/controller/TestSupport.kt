package io.bluetape4k.clinic.appointment.api.controller

import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient

fun <S : RestClient.RequestHeadersSpec<S>> RestClient.RequestHeadersSpec<S>.execute(): TestResponse =
    exchange { _, response ->
        TestResponse(
            statusCode = response.statusCode,
            body = response.bodyTo(String::class.java) ?: "",
        )
    }!!

data class TestResponse(
    val statusCode: HttpStatusCode,
    val body: String,
) {
    fun <T> jsonPath(path: String): T = com.jayway.jsonpath.JsonPath.parse(body).read(path)
}
