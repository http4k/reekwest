package org.http4k.javaxvalidation

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.swagger.v3.oas.annotations.media.Schema
import org.http4k.contract.openapi.v3.FieldMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

internal class JavaXValidationFieldRetrievalStrategyTest {

    @Test
    fun testJacksonDescription() {
        data class NameWithJacksonDescription(@JsonPropertyDescription("Just a description") val name: String)
        val result = JavaXValidationFieldRetrievalStrategy.invoke(NameWithJacksonDescription("Jane Doe"), NameWithJacksonDescription::name.name)
        assertEquals(FieldMetadata("Just a description", emptyMap()), result)
    }

    @Test
    fun testSwagger3Description() {
        data class NameWithSwagger3Description(@Schema(description = "Just a description") val name: String)
        val result = JavaXValidationFieldRetrievalStrategy.invoke(NameWithSwagger3Description("Jane Doe"), NameWithSwagger3Description::name.name)
        assertEquals(FieldMetadata("Just a description", emptyMap()), result)
    }

    @Test
    fun testLength() {
        data class NameWithSize(@get:Size(min = 2, max = 64) val name: String)
        val result = JavaXValidationFieldRetrievalStrategy.invoke(NameWithSize("Jane Doe"), NameWithSize::name.name)
        assertEquals(mapOf("minLength" to 2, "maxLength" to 64), result.extra)
    }

    @Test
    fun testMinMaxNumber() {
        data class IntBounded(@get:Min(30) @get:Max(50) val count: Int)
        val result = JavaXValidationFieldRetrievalStrategy.invoke(IntBounded(40), IntBounded::count.name)
        assertEquals(mapOf("min" to 30L, "max" to 50L), result.extra)
    }

    @Test
    fun testPattern() {
        data class StringWithPattern(@get:Pattern(regexp = "[a-z]+@[a-z+.]") val email: String)
        val result = JavaXValidationFieldRetrievalStrategy.invoke(StringWithPattern("jane@doe.com"), StringWithPattern::email.name)
        assertEquals(mapOf("pattern" to "[a-z]+@[a-z+.]"), result.extra)
    }
}
