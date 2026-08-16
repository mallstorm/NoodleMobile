package pro.noodle.mobile.core.diagnostics

object DiagnosticsSanitizer {
    private val header = Regex("(?im)^(Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*:\\s*.*$")
    private val kv = Regex("(?i)(token|access_token|refresh_token|session(?:_id)?|password|passwd|secret|api[_-]?key)\\s*([:=])\\s*([^\\s&,;]+)")
    private val query = Regex("(?i)([?&](?:token|access_token|refresh_token|session|session_id|password|secret|api_key)=)[^&#\\s]+")
    fun sanitize(text:String):String = text
        .replace(header) { "${it.groupValues[1]}: <redacted>" }
        .replace(kv) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        .replace(query) { "${it.groupValues[1]}<redacted>" }
}
