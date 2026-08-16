package pro.noodle.mobile.core.adapter

import android.content.Context

data class BundledAdapterState(
    val fileName: String,
    val validation: AdapterValidationResult,
)

class BundledAdapterRepository(private val context: Context) {
    fun loadAll(): List<BundledAdapterState> {
        val names = context.assets.list("adapters").orEmpty().filter { it.endsWith(".json", true) }.sorted()
        return names.map { name ->
            val text = context.assets.open("adapters/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
            BundledAdapterState(name, AdapterValidator.validate(text))
        }
    }
}
