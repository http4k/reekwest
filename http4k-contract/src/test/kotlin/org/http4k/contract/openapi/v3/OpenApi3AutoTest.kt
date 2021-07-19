package org.http4k.contract.openapi.v3

import com.fasterxml.jackson.databind.JsonNode
import org.http4k.contract.ContractRendererContract
import org.http4k.contract.openapi.AddSimpleFieldToRootNode
import org.http4k.contract.openapi.ApiInfo
import org.http4k.format.Jackson

class OpenApi3AutoTest : ContractRendererContract<JsonNode>(Jackson,
    OpenApi3(ApiInfo("title", "1.2", "module description"), Jackson, listOf(AddSimpleFieldToRootNode), listOf(ServerObject("http://localhost", "server description"))))
