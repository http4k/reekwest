package org.http4k.contract.openapi.v3

import org.http4k.contract.Tag
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.format.Json
import org.http4k.util.JsonSchema
import org.http4k.util.JsonToJsonSchema

class StandardApiRenderer<NODE>(private val json: Json<NODE>) : ApiRenderer<Api<NODE>, NODE> {
    private val jsonToJsonSchema = JsonToJsonSchema(json)

    override fun api(api: Api<NODE>): NODE =
        with(api) {
            json {
                obj(
                    "openapi" to string(openapi),
                    "info" to info.asJson(),
                    "tags" to array(tags.map { it.asJson() }),
                    "paths" to paths.asJson(),
                    "components" to components.asJson()
                )
            }
        }

    private fun Components<NODE>.asJson() = json {
        obj(
            "schemas" to schemas,
            "securitySchemes" to securitySchemes
        )
    }

    private fun Map<String, Map<String, ApiPath<NODE>>>.asJson(): NODE =
        json {
            obj(
                map {
                    it.key to obj(
                        it.value
                            .map { it.key to it.value.toJson() }.sortedBy { it.first }
                    )
                }.sortedBy { it.first }
            )
        }


    private fun ApiPath<NODE>.toJson(): NODE =
        json {
            obj(
                "summary" to string(summary),
                "description" to (description?.let { string(it) } ?: nullNode()),
                "tags" to (tags?.map { string(it) }?.let { array(it) } ?: nullNode()),
                "parameters" to (parameters?.map { it.asJson() }?.let { array(it) } ?: nullNode()),
                "requestBody" to string(requestBody.toString()),
                "responses" to string(responses.toString()),
                "security" to (security ?: nullNode()),
                "operationId" to (operationId?.let { string(it) } ?: nullNode())
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun RequestParameter.asJson(): NODE = json {
        when (this) {
            is RequestParameter.SchemaParameter<*> -> obj(
                "in" to string(`in`),
                "name" to string(name),
                "required" to boolean(required),
                "description" to (description?.let { string(it) } ?: nullNode()),
                "schema" to (schema as NODE ?: nullNode())
            )
            is RequestParameter.PrimitiveParameter<*> -> obj(
                "in" to string(`in`),
                "name" to string(name),
                "required" to boolean(required),
                "description" to (description?.let { string(it) } ?: nullNode()),
                "schema" to schema as NODE
            )
            else -> nullNode()
        }
    }

    private fun Tag.asJson(): NODE =
        json {
            obj(
                listOf(
                    "description" to (description?.let { json.string(it) } ?: nullNode()),
                    "name" to string(name)
                )
            )
        }

    private fun ApiInfo.asJson() = json {
        obj("title" to string(title), "version" to string(version), "description" to string(description ?: ""))
    }

    @Suppress("UNCHECKED_CAST")
    override fun toSchema(obj: Any, overrideDefinitionId: String?): JsonSchema<NODE> =
        jsonToJsonSchema.toSchema(obj as NODE, overrideDefinitionId)
}
