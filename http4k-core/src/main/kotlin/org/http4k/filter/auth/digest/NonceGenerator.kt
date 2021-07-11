package org.http4k.filter.auth.digest

import org.http4k.util.Hex
import java.security.SecureRandom

interface NonceGenerator {
    fun generate(): String

    /**
     * Return whether the given nonce was generated by this manager
     */
    fun verify(nonce: String): Boolean
}

class GenerateOnlyNonceGenerator: NonceGenerator {

    companion object {
        private const val length = 8
    }

    private val random = SecureRandom()

    override fun generate(): String {
        val tmp = ByteArray(length)
        random.nextBytes(tmp)
        return Hex.hex(tmp)
    }

    override fun verify(nonce: String): Boolean {
        return true
    }
}

/**
 * TODO [SecureRandomNonceGenerator] cannot verify nonces.
 * Come up with an implementation that can, such as Ktor's StatelessHmacNonceManager,
 * or one that uses an expiring cache for secure random nonces
 */

