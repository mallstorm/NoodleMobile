package pro.noodle.mobile.core.source

import pro.noodle.mobile.core.version.BuildIdentity

import pro.noodle.mobile.core.version.SemanticVersion

data class AdapterCompatibilityResult(val compatible: Boolean, val status: String, val errorCode: String = "", val message: String = "")

object AdapterCompatibility {
    const val CURRENT_APP_VERSION = BuildIdentity.SNP_COMPATIBILITY_VERSION // Desktop Stage 9 compatibility contract; Android build version is independent.
    fun evaluate(minVersion: String, maxVersion: String, appVersion: String = CURRENT_APP_VERSION): AdapterCompatibilityResult {
        val app = SemanticVersion.parseOrNull(appVersion) ?: return AdapterCompatibilityResult(false, "app_version_invalid", "APP_VERSION_INVALID", "Некорректная версия приложения.")
        val min = minVersion.takeIf { it.isNotBlank() }?.let { SemanticVersion.parseOrNull(it) ?: return AdapterCompatibilityResult(false, "min_invalid", "ADAPTER_MIN_APP_VERSION_INVALID", "Некорректный min_app_version.") }
        val max = maxVersion.takeIf { it.isNotBlank() }?.let { SemanticVersion.parseOrNull(it) ?: return AdapterCompatibilityResult(false, "max_invalid", "ADAPTER_MAX_APP_VERSION_INVALID", "Некорректный max_app_version.") }
        if (min != null && app < min) return AdapterCompatibilityResult(false, "requires_newer_app", "ADAPTER_APP_TOO_OLD", "Требуется Noodle >= $minVersion.")
        if (max != null && app > max) return AdapterCompatibilityResult(false, "requires_older_app", "ADAPTER_APP_TOO_NEW", "SNP ограничен версией Noodle <= $maxVersion.")
        return AdapterCompatibilityResult(true, "compatible")
    }
}
