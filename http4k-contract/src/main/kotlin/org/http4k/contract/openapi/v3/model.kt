package org.http4k.contract.openapi.v3

import org.http4k.contract.Tag
import org.http4k.contract.openapi.ApiInfo
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.http4k.util.JsonSchema

data class Api<NODE>(
    val info: ApiInfo,
    val tags: List<Tag>,
    val paths: Map<String, Map<String, ApiPath<NODE>>>,
    val components: Components<NODE>
) {
    val openapi = "3.0.0"
}

data class Components<NODE>(
    val schemas: NODE,
    val securitySchemes: NODE
)

data class ServerObject(
    val url: String,
    val description: String?
)

sealed class ApiPath<NODE>(
    val summary: String,
    val description: String?,
    val tags: List<String>,
    val parameters: List<RequestParameter<NODE>>,
    val responses: Map<String, ResponseContents<NODE>>,
    val security: NODE,
    val operationId: String,
    val deprecated: Boolean
) {
    open fun definitions() = listOfNotNull(
        responses.flatMap { it.value.definitions() },
        parameters.filterIsInstance<HasSchema<NODE>>().flatMap { it.definitions() }
    ).flatten()

    class NoBody<NODE>(
        summary: String,
        description: String?,
        tags: List<String>,
        parameters: List<RequestParameter<NODE>>,
        responses: Map<String, ResponseContents<NODE>>,
        security: NODE,
        operationId: String,
        deprecated: Boolean
    ) : ApiPath<NODE>(summary, description, tags, parameters, responses, security, operationId, deprecated)

    class WithBody<NODE>(
        summary: String,
        description: String?,
        tags: List<String>,
        parameters: List<RequestParameter<NODE>>,
        val requestBody: RequestContents<NODE>,
        responses: Map<String, ResponseContents<NODE>>,
        security: NODE,
        operationId: String,
        deprecated: Boolean
    ) : ApiPath<NODE>(summary, description, tags, parameters, responses, security, operationId, deprecated) {
        override fun definitions() = super.definitions() + requestBody.definitions().toList()
    }
}

fun interface HasSchema<NODE> {
    fun definitions(): Iterable<Pair<String, NODE>>
}

sealed class BodyContent {

    data class NoSchema<NODE : Any>(val schema: NODE, val example: String? = null) : BodyContent()

    class SchemaContent<NODE : Any>(private val jsonSchema: JsonSchema<NODE>?, val example: NODE?) : BodyContent(), HasSchema<NODE> {
        val schema = jsonSchema?.node
        override fun definitions() = jsonSchema?.definitions ?: emptySet()
    }

    class OneOfSchemaContent<NODE : Any>(private val schemas: List<BodyContent>) : BodyContent(), HasSchema<NODE> {
        data class OneOf<NODE>(val oneOf: List<NODE>)

        val schema = OneOf(
            schemas.filterIsInstance<NoSchema<NODE>>().map { it.schema } +
                schemas.filterIsInstance<SchemaContent<NODE>>().mapNotNull { it.schema }
        )

        override fun definitions() = schemas
            .filterIsInstance<HasSchema<NODE>>()
            .flatMap { it.definitions() }
    }

    class FormContent(val schema: FormSchema) : BodyContent() {
        class FormSchema(metas: List<Meta>) {
            val type = "object"
            val properties = metas.map {
                it.name to mapOf(
                    "type" to it.paramMeta.value,
                    "format" to it.paramMeta.takeIf { it == ParamMeta.FileParam }?.let { "binary" },
                    "description" to it.description
                ).filter { it.value != null }
            }.toMap()
            val required = metas.filter(Meta::required).map { it.name }
        }
    }
}

class RequestContents<NODE>(val content: Map<String, BodyContent>? = null) : HasSchema<NODE> {
    override fun definitions() = content?.values
        ?.filterIsInstance<HasSchema<NODE>>()
        ?.flatMap { it.definitions() }
        ?: emptyList()

    val required = content != null
}

class ResponseContents<NODE>(val description: String?, val content: Map<String, BodyContent> = emptyMap()) : HasSchema<NODE> {
    override fun definitions() = content.values
        .filterIsInstance<HasSchema<NODE>>()
        .flatMap { it.definitions() }.toSet()
}

sealed class RequestParameter<NODE>(val `in`: String, val name: String, val required: Boolean, val description: String?) {
    class SchemaParameter<NODE>(meta: Meta, private val jsonSchema: JsonSchema<NODE>?) : RequestParameter<NODE>(meta.location, meta.name, meta.required, meta.description), HasSchema<NODE> {
        val schema: NODE? = jsonSchema?.node
        override fun definitions() = jsonSchema?.definitions ?: emptySet()
    }

    class PrimitiveParameter<NODE>(meta: Meta, val schema: NODE) : RequestParameter<NODE>(meta.location, meta.name, meta.required, meta.description)
}
