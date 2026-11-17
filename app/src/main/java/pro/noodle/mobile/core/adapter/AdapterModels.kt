package pro.noodle.mobile.core.adapter

data class AdapterDefinition(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val adapterVersion: String = "0.0.0",
    val publisher: String = "",
    val homepage: String = "",
    val minAppVersion: String = "",
    val maxAppVersion: String = "",
    val baseUrl: String,
    val engine: String,
    val engineDependencies: List<String> = emptyList(),
    val enabled: Boolean = true,
    val capabilities: List<String> = emptyList(),
    val allowedHosts: List<String> = emptyList(),
    val allowJavaScript: Boolean = false,
    val allowCookies: Boolean = false,
    val viewStrategies: List<String> = emptyList(),
    /** Learned by SNP Studio: how to tell the real stream from previews on this site. */
    val streamPrefer: String = "",
    val streamReject: String = "",
    val streamMinSeconds: Int = 0,
    val streamExtract: List<String> = emptyList(),
    val searchUrlTemplate: String = "",
    /** Optional template used for page >= 1; must contain {query} and {page}. */
    val searchPageTemplate: String = "",
    /** Path regex for auto_catalog: which links on a search page are video cards. */
    val linkPattern: String = "",
    val itemSelector: String = "", val titleSelector: String = "", val urlSelector: String = "",
    val thumbnailSelector: String = "", val thumbnailAttribute: String = "", val durationSelector: String = "", val viewsSelector: String = "",
    val itemsPath: String = "", val idPath: String = "", val titlePath: String = "", val urlPath: String = "", val thumbnailPath: String = "", val durationPath: String = "", val viewsPath: String = "",
    val updateFeedUrl: String = "", val autoUpdate: Boolean = false,
    val healthQuery: String = "video", val healthMinResults: Int = 1, val healthRequirePlayback: Boolean = true, val healthAutoRepair: Boolean = false,
    val repairEnabled: Boolean = false, val repairMode: String = "conservative", val repairStrategies: List<String> = emptyList(),
    val repairFailureThreshold: Int = 3, val repairCooldownMinutes: Int = 60, val repairAutoQuarantine: Boolean = false,
)

data class AdapterLoadResult(val success: Boolean, val errorCode: String = "", val errorMessage: String = "", val definition: AdapterDefinition? = null)
data class AdapterValidationResult(val isValid: Boolean, val errorCode: String = "", val errorMessage: String = "", val definition: AdapterDefinition? = null)
