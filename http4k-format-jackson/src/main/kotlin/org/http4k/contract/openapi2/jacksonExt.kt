package org.http4k.contract.openapi2

import com.fasterxml.jackson.databind.JsonNode
import org.http4k.contract.ApiInfo
import org.http4k.contract.ApiRenderer.Companion.Auto
import org.http4k.contract.SecurityRenderer
import org.http4k.format.ConfigurableJackson
import org.http4k.format.Jackson
import org.http4k.format.JsonErrorResponseRenderer
import org.http4k.util.JacksonJsonSchemaCreator

operator fun OpenApi2.Companion.invoke(
    apiInfo: ApiInfo,
    json: ConfigurableJackson = Jackson,
    securityRenderer: SecurityRenderer = OpenApi2SecurityRenderer,
    errorResponseRenderer: JsonErrorResponseRenderer<JsonNode> = JsonErrorResponseRenderer(json)
) = OpenApi2(apiInfo, Jackson,
    Auto(json, JacksonJsonSchemaCreator(json, "definitions")),
    securityRenderer, errorResponseRenderer)
