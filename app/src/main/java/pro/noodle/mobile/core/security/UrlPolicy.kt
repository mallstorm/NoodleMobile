package pro.noodle.mobile.core.security

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

fun interface HostResolver { fun resolve(host: String): List<InetAddress> }

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

data class UrlDecision(val allowed: Boolean, val errorCode: String = "", val reason: String = "", val normalizedUrl: String = "")

object UrlPolicy {
    private val blockedSchemes = setOf("javascript", "data", "vbscript", "file", "ftp")

    fun evaluateNavigation(url: String, allowHttp: Boolean = false, allowPrivateHosts: Boolean = false, resolveDns: Boolean = false, resolver: HostResolver = SystemHostResolver): UrlDecision {
        val raw = url.trim()
        if (raw.isEmpty()) return deny("URL_EMPTY", "URL пуст.", raw)
        val uri = try { URI(raw) } catch (_: Exception) { return deny("URL_INVALID", "Некорректный URL.", raw) }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme in blockedSchemes) return deny("URL_SCHEME_BLOCKED", "Схема $scheme запрещена.", raw)
        if (scheme != "https" && !(scheme == "http" && allowHttp)) return deny("URL_SCHEME_UNSAFE", "Разрешён HTTPS; HTTP допускается только явной legacy-политикой.", raw)
        val hostRaw = uri.host?.trim()?.lowercase().orEmpty()
        if (hostRaw.isEmpty()) return deny("URL_HOST_MISSING", "Домен отсутствует.", raw)
        if (hostRaw.contains("..") || hostRaw.startsWith('.') || hostRaw.endsWith('.')) return deny("URL_HOST_INVALID", "Некорректный домен.", raw)
        val host = try { IDN.toASCII(hostRaw).lowercase() } catch (_: Exception) { return deny("URL_HOST_INVALID", "Некорректный IDN-домен.", raw) }
        if (!allowPrivateHosts && isPrivateHostLiteral(host)) return deny("URL_PRIVATE_HOST_BLOCKED", "Локальные/private адреса запрещены.", raw)
        if (!allowPrivateHosts && resolveDns) {
            val addresses = try { resolver.resolve(host) } catch (_: Exception) { return deny("URL_DNS_FAILED", "Не удалось безопасно разрешить DNS.", raw) }
            if (addresses.isEmpty()) return deny("URL_DNS_EMPTY", "DNS не вернул адресов.", raw)
            if (addresses.any(::isUnsafeAddress)) return deny("URL_DNS_PRIVATE_BLOCKED", "Домен разрешается в локальный/private адрес.", raw)
        }
        val normalized = try { URI(scheme, uri.userInfo, host, uri.port, uri.path, uri.query, uri.fragment).toASCIIString() } catch (_: Exception) { raw }
        return UrlDecision(true, normalizedUrl = normalized)
    }

    fun evaluateMedia(url: String, allowHttp: Boolean = false, resolveDns: Boolean = false, resolver: HostResolver = SystemHostResolver) =
        evaluateNavigation(url, allowHttp, allowPrivateHosts = false, resolveDns = resolveDns, resolver = resolver)

    fun isHttpsDowngrade(fromUrl: String, toUrl: String): Boolean = scheme(fromUrl) == "https" && scheme(toUrl) == "http"

    fun isHostAllowed(host: String, allowedHosts: List<String>): Boolean {
        val candidate = host.trim().lowercase().trimEnd('.')
        if (candidate.isBlank()) return false
        return allowedHosts.any { patternRaw ->
            val pattern = patternRaw.trim().lowercase().trimEnd('.')
            if (pattern.startsWith("*.")) {
                val root = pattern.removePrefix("*.")
                candidate == root || candidate.endsWith(".$root")
            } else candidate == pattern
        }
    }

    private fun scheme(url: String): String = try { URI(url.trim()).scheme?.lowercase().orEmpty() } catch (_: Exception) { "" }
    private fun deny(code: String, reason: String, url: String) = UrlDecision(false, code, reason, url)

    private fun isPrivateHostLiteral(host: String): Boolean {
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::" || host == "::1") return true
        return try {
            if (host.matches(Regex("^[0-9.]+$")) || host.contains(':')) isUnsafeAddress(InetAddress.getByName(host)) else false
        } catch (_: Exception) { false }
    }

    fun isUnsafeAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        return when (address) {
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xff }
                b[0] == 10 || b[0] == 127 || (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || b[0] == 0 || b[0] >= 224
            }
            is Inet6Address -> {
                val b0 = address.address[0].toInt() and 0xff
                val b1 = address.address[1].toInt() and 0xff
                (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
            }
            else -> true
        }
    }
}
