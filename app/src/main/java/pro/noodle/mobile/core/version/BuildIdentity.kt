package pro.noodle.mobile.core.version

/** Runtime identity kept separate from SNP compatibility level. */
object BuildIdentity {
    const val VERSION_NAME = "0.15.5"
    const val VERSION_CODE = 150500
    const val SNP_COMPATIBILITY_VERSION = "1.9.0"
    /**
     * Many sites and WAFs (Cloudflare, DDoS-Guard, nginx bot filters) answer unknown clients with
     * 403/502. Present a regular mobile Chrome, exactly what the same site sees from the phone browser.
     */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
    const val STAGE_LABEL = "0.15 • AI-агент Studio"
}
