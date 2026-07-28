package org.xpra.client.proto

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The response to a server `challenge` packet:
 * `("challenge", server_salt, auth_caps, digest, salt_digest, prompt)`.
 */
object Challenge {

    /** Matches what xpra's own client uses for non-xor digests (`challenge.py:319`). */
    private const val CLIENT_SALT_LEN = 32

    private val random = SecureRandom()

    class UnsupportedDigestException(message: String) : RuntimeException(message)

    data class Reply(val response: String, val clientSalt: ByteArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * The exchange is two nested HMACs, and the operand order differs between them
     * (`xpra/client/base/challenge.py:305`, `xpra/net/digest.py:108`):
     *
     * ```
     * salt     = hex(HMAC-SHA256(key: clientSalt, message: serverSalt))
     * response = hex(HMAC-SHA256(key: password,   message: salt))
     * ```
     *
     * The intermediate salt is used as its 64-character ASCII hex form, not as raw digest
     * bytes. The returned [Reply.clientSalt] must be sent back verbatim, since the server
     * repeats the first step with it.
     */
    fun reply(serverSalt: ByteArray, digest: String, saltDigest: String, password: String): Reply {
        if (serverSalt.isEmpty()) throw UnsupportedDigestException("challenge packet has no server salt")
        // The digest name may carry a colon-separated suffix.
        val actualDigest = digest.substringBefore(':')
        if (actualDigest != Hello.DIGEST || saltDigest != Hello.DIGEST) {
            throw UnsupportedDigestException(
                "server asked for the '$actualDigest'/'$saltDigest' digests, " +
                    "but this client only implements '${Hello.DIGEST}'"
            )
        }

        val clientSalt = ByteArray(CLIENT_SALT_LEN).also { random.nextBytes(it) }
        val salt = hmacHex(clientSalt, serverSalt)
        val response = hmacHex(password.toByteArray(Charsets.UTF_8), salt.toByteArray(Charsets.US_ASCII))
        return Reply(response, clientSalt)
    }

    /** Lowercase hex HMAC-SHA256 of [message] under [key], which is the form xpra puts on the wire. */
    private fun hmacHex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }
}
