package org.http4k.javaxvalidation

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.swagger.v3.oas.annotations.media.Schema
import org.http4k.contract.ContractRoute
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.v3.AutoJsonToJsonSchema
import org.http4k.contract.openapi.v3.FieldRetrieval
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.contract.openapi.v3.SimpleLookup
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.format.Jackson.auto
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.routing.webJars
import org.http4k.server.Jetty
import org.http4k.server.asServer
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

// https://gitlab.com/gooutnet/backend/uploads/cc83a4874817194b05d844e61748fef4/Screenshot_2020-12-22_at_12.43.24.png
// https://gitlab.com/gooutnet/issuetracker/-/merge_requests/15337/diffs
fun main() {
    val API_DESCRIPTION_PATH = "/v3/api-docs"
    routes(
        routes(
            "/docs" bind Method.GET to {
                Response(Status.FOUND).header("Location", "/docs/index.html?url=$API_DESCRIPTION_PATH")
            },
            "/docs" bind static(ResourceLoader.Classpath("META-INF/resources/webjars/swagger-ui/3.44.0")),
            webJars()
        ),
        contract {
            renderer = OpenApi3(
                ApiInfo("GoOut Locations API", "1.0"),
                Jackson,
                listOf(),
                ApiRenderer.Auto(
                    Jackson,
                    AutoJsonToJsonSchema(
                        Jackson,
                        FieldRetrieval.compose(
                            SimpleLookup(metadataRetrievalStrategy = JavaXValidationFieldRetrievalStrategy),
                        ),
                    )
                )
            )
            descriptionPath = API_DESCRIPTION_PATH
            routes += demo()
        }
    )
        .asServer(Jetty(8080))
        .start()
        .block()
}

data class DemoBody(
    @Schema(description = "Name of the entity.")
    @get:Size(min = 0, max = 64)
    val name: String,
    @get:Size(min = 0, max = 512)
    val bio: String,
    @get:Size(min = 2, max = 32)
    val path: String,
    @get:Min(30)
    @get:Max(50)
    val num: Int,
    @get:Pattern(regexp = "[a-z]+@[a-z+.]")
    val email: String,
)

val bodyLens = Body.auto<DemoBody>().toLens()

fun demo(): ContractRoute = "/demo" meta {
    receiving(bodyLens to DemoBody("Foo", "Bar", "path", 40, "foo@bar.cz"))
} bindContract Method.POST to { request -> Response(Status.OK) }
