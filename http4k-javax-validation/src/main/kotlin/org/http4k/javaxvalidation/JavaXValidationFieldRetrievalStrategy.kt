package org.http4k.javaxvalidation

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.swagger.v3.oas.annotations.media.Schema
import org.http4k.contract.openapi.v3.FieldMetadata
import org.http4k.contract.openapi.v3.FieldMetadataRetrievalStrategy
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaGetter

object JavaXValidationFieldRetrievalStrategy : FieldMetadataRetrievalStrategy {
    override fun invoke(target: Any, fieldName: String): FieldMetadata =
        FieldMetadata(
            description = target.javaClass.findPropertyDescription(fieldName),
            extra = extractJavaXValidationAnnotationMetadata(target::class, fieldName),
        )

    private fun Class<Any>.findPropertyDescription(name: String): String? =
        kotlin.constructors.first().parameters
            .firstOrNull { p -> p.kind == KParameter.Kind.VALUE && p.name == name }
            ?.let { p ->
                // Note that http4k expects the description to be given with @JsonPropertyDescription,
                // which is NOT compatible with SpringDoc generating OpenApi3 docs. We need to use
                // Swagger3/Schema to be compatible.
                p.annotations.filterIsInstance<JsonPropertyDescription>().firstOrNull()?.value
                    ?: p.annotations.filterIsInstance<Schema>().firstOrNull()?.description
            } ?: superclass?.findPropertyDescription(name)

    private inline fun <reified T : Annotation> KCallable<*>.findGetterAnnotation(): T? =
        (this as KProperty<*>).javaGetter!!.annotations.find { it.annotationClass == T::class } as T?

    private fun extractJavaXValidationAnnotationMetadata(kClass: KClass<out Any>, name: String): Map<String, Any> {
        val field: KCallable<*> = kClass.members.find { it.name == name }!!
        return mapOf(
            "minLength" to field.findGetterAnnotation<Size>()?.min,
            "maxLength" to field.findGetterAnnotation<Size>()?.max,
            "min" to field.findGetterAnnotation<Min>()?.value,
            "max" to field.findGetterAnnotation<Max>()?.value,
            "pattern" to field.findGetterAnnotation<Pattern>()?.regexp,
        ).filter { it.value != null } as Map<String, Any>
    }
}
