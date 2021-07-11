package org.http4k.security.digest

import org.http4k.appendIfNotBlank
import org.http4k.core.Credentials
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.security.Nonce
import org.http4k.security.NonceGeneratorVerifier
import org.http4k.util.Hex
import org.http4k.util.Hex.hex
import java.security.MessageDigest

/**
 * For use in clients.  Generates responses to Digest Auth challenges
 */
class DigestAuthReceiver(private val nonceGeneratorVerifier: NonceGeneratorVerifier, proxy: Boolean) {

    private val authHeader = if (proxy) "Proxy-Authorization" else "Authorization"
    private val challengeHeader = if (proxy) "Proxy-Authenticate" else "WWW-Authenticate"

    private var lastNonce: Nonce? = null
    private var nonceCount: Long = 0
    private var cnonce: Nonce = nonceGeneratorVerifier()

    fun getChallengeHeader(response: Response): DigestChallenge? {
        val headerValue = response.header(challengeHeader) ?: return null
        return DigestChallenge.parse(headerValue)
    }

    /**
     * Response to the challenge with the given credentials, adding them to the request
     */
    fun authorizeRequest(request: Request, challenge: DigestChallenge, credentials: Credentials): Request {
        if (challenge.nonce == lastNonce) {
            nonceCount++
        } else {
            nonceCount = 1
            cnonce = nonceGeneratorVerifier()
            lastNonce = challenge.nonce
        }

        val uri = StringBuilder()
            .appendIfNotBlank(request.uri.path, request.uri.path)
            .appendIfNotBlank(request.uri.query, "?", request.uri.query)
            .toString()

        val digester = when (challenge.algorithm?.toLowerCase()) {
            null, "md5-sess" -> MessageDigest.getInstance("MD5")
            else -> MessageDigest.getInstance(challenge.algorithm)
        }

        val charset = request.header(CREDENTIAL_CHARSET_HEADER)
            ?.let { charset(it) }
            ?: Charsets.ISO_8859_1

        val digestEncoder = DigestCalculator(digester, charset)

        val qop = challenge.qop.firstOrNull()

        val digest = digestEncoder.encode(
            method = request.method,
            realm = challenge.realm,
            qop = qop,
            username = credentials.user,
            password = credentials.password,
            nonce = challenge.nonce,
            cnonce = cnonce,
            nonceCount = nonceCount,
            digestUri = uri
        )

        val digestCredentials = DigestCredential(
            realm = challenge.realm,
            username = credentials.user,
            digestUri = uri,
            nonce = challenge.nonce,
            response = hex(digest),
            opaque = challenge.opaque,
            nonceCount = nonceCount,
            algorithm = challenge.algorithm ?: digester.algorithm,
            cnonce = cnonce,
            qop = qop
        )

        return request.header(authHeader, digestCredentials.toHeaderValue())
    }

    companion object {
        private const val CREDENTIAL_CHARSET_HEADER = "http.auth.credential-charset"
    }
}
