package org.http4k.contract.openapi.v3

import org.http4k.contract.ContractRenderer
import org.http4k.contract.ContractRoute
import org.http4k.contract.HttpMessageMeta
import org.http4k.contract.PathSegments
import org.http4k.contract.RouteMeta
import org.http4k.contract.Security
import org.http4k.contract.Tag
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.Render
import org.http4k.contract.openapi.SecurityRenderer
import org.http4k.contract.openapi.v3.BodyContent.FormContent
import org.http4k.contract.openapi.v3.BodyContent.FormContent.FormSchema
import org.http4k.contract.openapi.v3.BodyContent.NoSchema
import org.http4k.contract.openapi.v3.BodyContent.SchemaContent
import org.http4k.contract.openapi.v3.RequestParameter.PrimitiveParameter
import org.http4k.contract.openapi.v3.RequestParameter.SchemaParameter
import org.http4k.core.ContentType.Companion.APPLICATION_FORM_URLENCODED
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.HttpMessage
import org.http4k.core.Method
import org.http4k.core.Method.DELETE
import org.http4k.core.Method.GET
import org.http4k.core.Method.HEAD
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.with
import org.http4k.format.Json
import org.http4k.format.JsonErrorResponseRenderer
import org.http4k.lens.Failure
import org.http4k.lens.Header.CONTENT_TYPE
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.http4k.lens.ParamMeta.ObjectParam
import org.http4k.util.JsonSchema
import org.http4k.util.JsonToJsonSchema

private data class Api<NODE>(
    val info: ApiInfo,
    val tags: List<Tag>,
    val paths: Map<String, Map<String, ApiPath<NODE>>>,
    val components: Components<NODE>
) {
    val openapi = "3.0.0"
}

private data class Components<NODE>(
    val schemas: NODE,
    val securitySchemes: NODE
)

private data class ApiPath<NODE>(
    val summary: String,
    val description: String?,
    val tags: List<String>?,
    val parameters: List<RequestParameter>?,
    val requestBody: RequestContents<NODE>?,
    val responses: Map<String, ResponseContents<NODE>>,
    val security: NODE?,
    val operationId: String?
) {
    fun definitions() = listOfNotNull(
        responses.flatMap { it.value.definitions() },
        parameters?.filterIsInstance<HasSchema<NODE>>()?.flatMap { it.definitions() },
        requestBody?.definitions()?.toList()
    ).flatten()
}

private interface HasSchema<NODE> {
    fun definitions(): Iterable<Pair<String, NODE>>
}

private sealed class BodyContent {
    class NoSchema(paramMeta: ParamMeta) : BodyContent() {
        val schema = mapOf("type" to paramMeta.value)
    }

    class SchemaContent<NODE>(private val jsonSchema: JsonSchema<NODE>?, val example: NODE?) : BodyContent(), HasSchema<NODE> {
        val schema = jsonSchema?.node
        override fun definitions() = jsonSchema?.definitions ?: emptySet()
    }

    class FormContent(val schema: FormSchema) : BodyContent() {
        class FormSchema(metas: List<Meta>) {
            val type = "object"
            val properties = metas.map { it.name to mapOf("type" to it.paramMeta.value, "description" to it.description) }.toMap()
            val required = metas.filter(Meta::required).map { it.name }
        }
    }
}

private class RequestContents<NODE>(val content: Map<String, BodyContent>?) : HasSchema<NODE> {
    override fun definitions() = content?.values
        ?.filterIsInstance<HasSchema<NODE>>()
        ?.flatMap { it.definitions() } ?: emptySet<Pair<String, NODE>>()

    val required = content != null
}

private class ResponseContents<NODE>(val description: String?, val content: Map<String, BodyContent>) : HasSchema<NODE> {
    override fun definitions() = content.values
        .filterIsInstance<HasSchema<NODE>>()
        .flatMap { it.definitions() }.toSet()
}

private sealed class RequestParameter(val `in`: String, val name: String, val required: Boolean, val description: String?) {
    class SchemaParameter<NODE>(meta: Meta, private val jsonSchema: JsonSchema<NODE>?) : RequestParameter(meta.location, meta.name, meta.required, meta.description), HasSchema<NODE> {
        val schema: NODE? = jsonSchema?.node
        override fun definitions() = jsonSchema?.definitions ?: emptySet()
    }

    class PrimitiveParameter<NODE>(meta: Meta, val schema: NODE) : RequestParameter(meta.location, meta.name, meta.required, meta.description)
}

class OpenApi3<out NODE : Any>(
    private val apiInfo: ApiInfo,
    private val json: Json<NODE>,
    private val apiRenderer: ApiRenderer<Any, NODE>,
    private val securityRenderer: SecurityRenderer = org.http4k.contract.openapi.v3.SecurityRenderer,
    private val errorResponseRenderer: JsonErrorResponseRenderer<NODE> = JsonErrorResponseRenderer(json)
) : ContractRenderer {

    private data class PathAndMethod<NODE>(val path: String, val method: Method, val pathSpec: ApiPath<NODE>)

    override fun badRequest(failures: List<Failure>) = errorResponseRenderer.badRequest(failures)

    override fun notFound() = errorResponseRenderer.notFound()

    override fun description(contractRoot: PathSegments, security: Security, routes: List<ContractRoute>): Response {
        val allSecurities = routes.map { it.meta.security } + security
        val paths = routes.map { it.asPath(security, contractRoot) }

        return Response(OK)
            .with(json.body().toLens() of apiRenderer.api(
                Api(
                    apiInfo,
                    routes.map(ContractRoute::tags).flatten().toSet().sortedBy { it.name },
                    paths
                        .groupBy { it.path }
                        .mapValues {
                            it.value.map { pam -> pam.method.name.toLowerCase() to pam.pathSpec }.toMap().toSortedMap()
                        }
                        .toSortedMap(),
                    Components(json.obj(paths.flatMap { it.pathSpec.definitions() }), json(allSecurities.combineFull()))
                )
            ))
    }

    private fun ContractRoute.asPath(contractSecurity: Security, contractRoot: PathSegments) =
        PathAndMethod(describeFor(contractRoot), method,
            ApiPath(
                meta.summary,
                meta.description,
                if (tags.isEmpty()) listOf(contractRoot.toString()) else tags.map { it.name }.toSet().sorted().nullIfEmpty(),
                asOpenApiParameters().nullIfEmpty(),
                when (method) {
                    in setOf(GET, DELETE, HEAD) -> null
                    else -> meta.requestBody().takeIf { it.required }
                },
                meta.responses(),
                json(listOf(meta.security, contractSecurity).combineRef()),
                meta.operationId
            )
        )

    private fun RouteMeta.responses() =
        responses.map { it.message.status.code.toString() to it.asOpenApiResponse() }.toMap()

    private fun ContractRoute.asOpenApiParameters() = nonBodyParams.map {
        when (it.paramMeta) {
            ObjectParam -> SchemaParameter(it, "{}".toSchema())
            else -> PrimitiveParameter(it, json {
                obj("type" to string(it.paramMeta.value))
            })
        }
    }

    private fun RouteMeta.requestBody(): RequestContents<NODE> {
        val noSchema = consumes.map { it.value to NoSchema(ParamMeta.StringParam) }

        val withSchema = requests.mapNotNull {
            when (CONTENT_TYPE(it.message)) {
                APPLICATION_JSON -> APPLICATION_JSON.value to it.toSchemaContent()
                APPLICATION_FORM_URLENCODED -> {
                    APPLICATION_FORM_URLENCODED.value to
                        (body?.metas?.let { FormContent(FormSchema(it)) } ?: SchemaContent("".toSchema(), null))
                }
                else -> null
            }
        }

        return RequestContents((noSchema + withSchema).nullIfEmpty()?.toMap())
    }

    private fun HttpMessageMeta<HttpMessage>.toSchemaContent(): SchemaContent<NODE> {
        val bodyString = message.bodyString()
        val jsonSchema = example?.let { apiRenderer.toSchema(it, definitionId) }
            ?: bodyString.toSchema(definitionId)
        return SchemaContent(jsonSchema, bodyString.safeParse())
    }

    private fun HttpMessageMeta<Response>.asOpenApiResponse(): ResponseContents<NODE> {
        val contentTypes = CONTENT_TYPE(message)
            ?.takeIf { it == APPLICATION_JSON }
            ?.let { mapOf(it.value to toSchemaContent()) }
            ?: emptyMap()
        return ResponseContents(description, contentTypes)
    }

    private fun String.toSchema(definitionId: String? = null) = safeParse()
        ?.let { JsonToJsonSchema(json, "components/schemas").toSchema(it, definitionId) }
        ?: JsonSchema(json.obj(), emptySet())

    private fun List<Security>.combineFull(): Render<NODE> = {
        obj(mapNotNull { securityRenderer.full<NODE>(it) }.flatMap { fields(this(it)) })
    }

    private fun List<Security>.combineRef(): Render<NODE> = {
        array(mapNotNull { securityRenderer.ref<NODE>(it) }.map { this(it) })
    }

    private fun String.safeParse(): NODE? = try {
        json.parse(this)
    } catch (e: Exception) {
        null
    }

    companion object
}

private fun <E : Iterable<T>, T> E.nullIfEmpty(): E? = if (iterator().hasNext()) this else null