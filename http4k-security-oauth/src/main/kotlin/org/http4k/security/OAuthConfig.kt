package org.http4k.security

import org.http4k.core.Credentials
import org.http4k.core.Uri

data class OAuthConfig(
    private val authBase: Uri,
    val authPath: String,
    val tokenPath: String,
    val credentials: Credentials,
    val apiBase: Uri = authBase) {
    val authUri = authBase.path(authPath)
}