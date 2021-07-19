package org.http4k.contract.openapi.v3

import org.http4k.contract.ContractRenderer
import org.http4k.contract.ContractRoute
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.contract.HttpMessageMeta
import org.http4k.contract.JsonErrorResponseRenderer
import org.http4k.contract.PathSegments
import org.http4k.contract.RouteMeta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.OpenApiExtension
import org.http4k.contract.openapi.Render
import org.http4k.contract.openapi.SecurityRenderer
import org.http4k.contract.openapi.operationId
import org.http4k.contract.openapi.v3.BodyContent.FormContent
import org.http4k.contract.openapi.v3.BodyContent.FormContent.FormSchema
import org.http4k.contract.openapi.v3.BodyContent.NoSchema
import org.http4k.contract.openapi.v3.BodyContent.OneOfSchemaContent
import org.http4k.contract.openapi.v3.BodyContent.SchemaContent
import org.http4k.contract.openapi.v3.RequestParameter.PrimitiveParameter
import org.http4k.contract.openapi.v3.RequestParameter.SchemaParameter
import org.http4k.contract.security.Security
import org.http4k.core.ContentType.Companion.APPLICATION_FORM_URLENCODED
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.MULTIPART_FORM_DATA
import org.http4k.core.HttpMessage
import org.http4k.core.Method
import org.http4k.core.Method.GET
import org.http4k.core.Method.HEAD
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.with
import org.http4k.format.AutoMarshallingJson
import org.http4k.format.Json
import org.http4k.format.JsonType
import org.http4k.lens.Header.CONTENT_TYPE
import org.http4k.lens.ParamMeta
import org.http4k.lens.ParamMeta.ArrayParam
import org.http4k.lens.ParamMeta.FileParam
import org.http4k.lens.ParamMeta.ObjectParam
import org.http4k.lens.ParamMeta.StringParam
import org.http4k.util.JsonSchema

/**
 * Contract renderer for OpenApi3 format JSON. For the JSON schema generation, naming of
 * object models will default to either reflective or hashcode based depending on if a Auto Json
 * is passed.
 */
class OpenApi3<NODE : Any>(
    private val apiInfo: ApiInfo,
    private val json: Json<NODE>,
    private val extensions: List<OpenApiExtension> = emptyList(),
    private val servers: List<ServerObject> = emptyList(),
    // note that this is the basic OpenApi renderer - if you want reflective Schema generation
    // then you want to use ApiRenderer.Auto() instead with a compatible JSON instance
    private val apiRenderer: ApiRenderer<Api<NODE>, NODE> = OpenApi3ApiRenderer(json, servers),
    private val securityRenderer: SecurityRenderer = OpenApi3SecurityRenderer,
    private val errorResponseRenderer: ErrorResponseRenderer = JsonErrorResponseRenderer(json)
) : ContractRenderer, ErrorResponseRenderer by errorResponseRenderer {
    private data class PathAndMethod<NODE>(val path: String, val method: Method, val pathSpec: ApiPath<NODE>)

   constructor(
        apiInfo: ApiInfo,
        json: AutoMarshallingJson<NODE>,
        extensions: List<OpenApiExtension> = emptyList(),
        servers: List<ServerObject> = emptyList()
    ) : this(apiInfo, json, extensions, servers, ApiRenderer.Auto(json))

    override fun description(contractRoot: PathSegments, security: Security?, routes: List<ContractRoute>): Response {
        val allSecurities = routes.map { it.meta.security } + listOfNotNull(security)
        val paths = routes.map { it.asPath(security, contractRoot) }

        val unextended = apiRenderer.api(
            Api(
                apiInfo,
                routes.map(ContractRoute::tags).flatten().toSet().sortedBy { it.name },
                paths
                    .groupBy { it.path }
                    .mapValues {
                        it.value.map { pam -> pam.method.name.toLowerCase() to pam.pathSpec }.toMap().toSortedMap()
                    }
                    .toSortedMap(),
                Components(
                    json.obj(paths.flatMap { it.pathSpec.definitions() }),
                    json(allSecurities.filterNotNull().combineFull())
                )
            )
        )

        return Response(OK)
            .with(json.body().toLens() of extensions.fold(unextended) { acc, next -> json(next(acc)) })
    }

    private fun ContractRoute.asPath(contractSecurity: Security?, contractRoot: PathSegments) =
        PathAndMethod(describeFor(contractRoot), method, apiPath(contractRoot, contractSecurity))

    private fun ContractRoute.apiPath(contractRoot: PathSegments, contractSecurity: Security?): ApiPath<NODE> {
        val tags = if (tags.isEmpty()) listOf(contractRoot.toString()) else tags.map { it.name }.toSet().sorted()

        val security = json(listOfNotNull(meta.security ?: contractSecurity).combineRef())
        val body = meta.requestBody()?.takeIf { it.required }

        return if (method in setOf(GET, HEAD) || body == null) {
            ApiPath.NoBody(
                meta.summary,
                meta.description,
                tags,
                asOpenApiParameters(),
                meta.responses(),
                security,
                operationId(contractRoot),
                meta.deprecated
            )
        } else {
            ApiPath.WithBody(
                meta.summary,
                meta.description,
                tags,
                asOpenApiParameters(),
                body,
                meta.responses(),
                security,
                operationId(contractRoot),
                meta.deprecated
            )
        }
    }

    private fun RouteMeta.responses() = responses
        .groupBy { it.message.status.code.toString() }
        .map {
            it.key to
                ResponseContents<NODE>(
                    it.value
                        .map { it.description }.toSortedSet().joinToString(","), it.value.collectSchemas()
                )
        }.toMap()

    private fun List<HttpMessageMeta<Response>>.collectSchemas() = groupBy { CONTENT_TYPE(it.message) }
        .mapValues {
            when (it.value.size) {
                1 -> it.value.first().toSchemaContent()
                else -> OneOfSchemaContent<NODE>(it.value.map { it.toSchemaContent() })
            }
        }
        .mapNotNull { i -> i.key?.let { it.value to i.value } }
        .toMap()

    private fun ContractRoute.asOpenApiParameters() = nonBodyParams.map {
        when (val paramMeta: ParamMeta = it.paramMeta) {
            ObjectParam -> SchemaParameter(it, "{}".toSchema())
            FileParam -> PrimitiveParameter(it, json {
                obj("type" to string(FileParam.value), "format" to string("binary"))
            })
            is ArrayParam -> PrimitiveParameter(it, json {
                obj(
                    "type" to string("array"),
                    "items" to obj("type" to string(paramMeta.itemType().value))
                )
            })
            else -> PrimitiveParameter(it, json {
                obj("type" to string(paramMeta.value))
            })
        }
    }

    private fun RouteMeta.requestBody(): RequestContents<NODE>? {
        val noSchema = consumes.map { it.value to NoSchema(json { obj("type" to string(StringParam.value)) }) }

        val withSchema = requests.mapNotNull {
            with(CONTENT_TYPE(it.message)) {
                when (this) {
                    APPLICATION_JSON -> APPLICATION_JSON.value to it.toSchemaContent()
                    APPLICATION_FORM_URLENCODED, MULTIPART_FORM_DATA -> value to
                        (body?.metas?.let { FormContent(FormSchema(it)) } ?: SchemaContent("".toSchema(), null))
                    null -> null
                    else -> value to NoSchema(
                        json { obj("type" to string(StringParam.value)) }, it.example?.toString()
                    )
                }
            }
        }

        val collectedWithSchema = withSchema
            .groupBy { it.first }
            .mapValues {
                when (it.value.size) {
                    1 -> it.value.first().second
                    else -> OneOfSchemaContent<NODE>(it.value.map { it.second })
                }
            }.toList()

        return (noSchema + collectedWithSchema)
            .takeIf { it.isNotEmpty() }
            ?.let { RequestContents(it.toMap()) }
    }

    private fun HttpMessageMeta<HttpMessage>.toSchemaContent(): BodyContent {
        fun exampleSchemaIsValid(schema: JsonSchema<NODE>) =
            when (example) {
                is Array<*>, is Iterable<*> -> !json.fields(schema.node).toMap().containsKey("\$ref")
                else -> apiRenderer.toSchema(object {}) != schema
            }

        val jsonSchema = example
            ?.let { apiRenderer.toSchema(it, definitionId) }
            ?.takeIf(::exampleSchemaIsValid)
            ?: message.bodyString().toSchema(definitionId)

        return SchemaContent(jsonSchema, message.bodyString().safeParse())
    }

    private fun String.toSchema(definitionId: String? = null) = safeParse()
        ?.let { JsonToJsonSchema(json, "components/schemas").toSchema(it, definitionId) }
        ?: JsonSchema(json.obj(), emptySet())

    private fun List<Security>.combineFull(): Render<NODE> = {
        obj(mapNotNull { securityRenderer.full<NODE>(it) }.flatMap { fields(this(it)) })
    }

    private fun List<Security>.combineRef(): Render<NODE> = {
        array(mapNotNull { securityRenderer.ref<NODE>(it) }.flatMap {
            this(it).let { if (typeOf(it) == JsonType.Array) elements(it) else listOf(it) }
        })
    }

    private fun String.safeParse(): NODE? = try {
        json.parse(this)
    } catch (e: Exception) {
        null
    }

    companion object
}
