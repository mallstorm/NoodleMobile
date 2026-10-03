package pro.noodle.mobile.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object AppLockCrypto {
    private val random = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(secret: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest(secret.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    fun verify(secret: String, salt: String, expectedHash: String): Boolean {
        if (salt.isBlank() || expectedHash.isBlank()) return false
        return MessageDigest.isEqual(
            hash(secret, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
    }
}
