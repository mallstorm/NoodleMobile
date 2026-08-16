package pro.noodle.mobile.core.catalog

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

object CatalogSignatureVerifier {
    fun verify(payload: ByteArray, signature: ByteArray, key: TrustedCatalogKey): Boolean {
        if (!TrustedKeyCodec.validate(key).valid) return false
        return runCatching {
            val spec = RSAPublicKeySpec(BigInteger(key.modulusHex, 16), BigInteger.valueOf(key.exponent))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
