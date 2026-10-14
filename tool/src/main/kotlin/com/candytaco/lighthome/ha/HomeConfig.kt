package com.candytaco.lighthome.ha

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The user's settings for HA's Home panel, stored by the frontend with
 * `frontend/get_system_data` under the `home` key (see frontend src/data/frontend.ts).
 */
data class HomeConfig(
    /** Entity ids pinned to the top of Home, in the user's order. */
    val favoriteEntities: List<String> = emptyList(),
    /** Summary keys in display order, hidden ones already removed. */
    val summaries: List<String> = DEFAULT_SUMMARY_KEYS,
) {
    companion object {
        /** frontend src/data/home_shortcuts.ts DEFAULT_SUMMARY_KEYS. */
        val DEFAULT_SUMMARY_KEYS = listOf("light", "climate", "security", "media_players", "maintenance", "weather", "energy")

        /** [result] is the `frontend/get_system_data` result: `{"value": {...} | null}`. */
        fun parse(result: JsonElement?): HomeConfig {
            val value = (result as? JsonObject)?.obj("value") ?: return HomeConfig()
            return HomeConfig(
                favoriteEntities = value.array("favorite_entities")?.mapNotNull { it.textOrNull() }.orEmpty(),
                summaries = resolveSummaries(value.array("shortcuts")?.mapNotNull { it as? JsonObject }),
            )
        }

        /**
         * Mirrors frontend resolveShortcutItems: saved summary items keep their order, built-ins
         * missing from the saved list are appended in default order, and hidden ones are dropped.
         * Custom shortcuts (links to other dashboards) have no text form here and are skipped.
         */
        private fun resolveSummaries(saved: List<JsonObject>?): List<String> {
            val seen = mutableSetOf<String>()
            val visible = mutableListOf<String>()
            saved.orEmpty()
                .filter { it.string("type") == "summary" }
                .forEach { item ->
                    val key = item.string("key") ?: return@forEach
                    if (key !in DEFAULT_SUMMARY_KEYS || !seen.add(key)) return@forEach
                    if (item.boolean("hidden") != true) visible += key
                }
            DEFAULT_SUMMARY_KEYS.filter { it !in seen }.forEach { visible += it }
            return visible
        }
    }
}
