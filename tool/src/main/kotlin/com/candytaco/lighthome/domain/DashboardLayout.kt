package com.candytaco.lighthome.domain

import com.candytaco.lighthome.ha.EntityState
import com.candytaco.lighthome.ha.entityDisplayName
import com.candytaco.lighthome.ha.array
import com.candytaco.lighthome.ha.obj
import com.candytaco.lighthome.ha.string
import com.candytaco.lighthome.ha.textOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Text rendering of the user's own dashboard (the card config the web UI shows, from
 * `lovelace/config`): views become screens, card titles and heading cards become section
 * headings, entity cards become rows. Cards with no text form (graphs, maps, cameras) are skipped.
 */

/** One dashboard view (a tab in the web UI). */
data class DashboardView(
    val title: String,
    val path: String,
    /** Subviews are only reached through navigation buttons, not listed as tabs. */
    val subview: Boolean,
    val config: JsonObject,
)

/** Views of a card-based dashboard, or null for an auto-generated/strategy dashboard (no cards). */
fun dashboardViews(config: JsonObject?): List<DashboardView>? {
    val views = config?.array("views") ?: return null
    if (config.obj("strategy") != null) return null
    return views.mapIndexedNotNull { index, element ->
        val view = element as? JsonObject ?: return@mapIndexedNotNull null
        if (view.obj("strategy") != null) return@mapIndexedNotNull null
        val path = view.string("path") ?: index.toString()
        DashboardView(
            title = view.string("title") ?: view.string("path") ?: "View ${index + 1}",
            path = path,
            subview = view.boolean("subview"),
            config = view,
        )
    }.takeIf { it.isNotEmpty() }
}

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

/** Home: a single view's cards directly, or one row per view (tab) when there are several. */
fun HomeSnapshot.dashboardHomeSections(views: List<DashboardView>): List<ListSection> {
    val tabs = views.filter { !it.subview }
    if (tabs.size == 1) return viewSections(tabs.single(), views)
    return listOf(ListSection(null, tabs.map { ListRow.Link(it.title, "", Destination.View(it.path, it.title)) }))
}

fun HomeSnapshot.viewSections(path: String): List<ListSection> {
    val views = dashboardViews(dashboard) ?: return emptyList()
    val view = views.firstOrNull { it.path == path } ?: return emptyList()
    return viewSections(view, views)
}

private fun HomeSnapshot.viewSections(view: DashboardView, views: List<DashboardView>): List<ListSection> {
    val out = SectionBuilder()
    val walker = CardWalker(this, views, out)

    // View badges: entity summaries at the top of the view.
    view.config.array("badges")?.forEach { badge ->
        val entityId = when (badge) {
            is JsonPrimitive -> badge.content
            is JsonObject -> if (walker.visible(badge)) badge.string("entity") else null
            else -> null
        }
        entityId?.let { out.add(walker.entityRow(it, (badge as? JsonObject)?.get("name"))) }
    }
    out.flush()

    // Sections views: each section is its own group (its heading card names it).
    view.config.array("sections")?.forEach { section ->
        val obj = section as? JsonObject ?: return@forEach
        if (!walker.visible(obj)) return@forEach
        out.flush()
        obj.string("title")?.let { out.startSection(it) }
        obj.array("cards")?.forEach { walker.walk(it) }
        out.flush()
    }

    // Masonry and panel views.
    val cards = view.config.array("cards").orEmpty()
    if (view.config.string("type") == "panel") {
        cards.firstOrNull()?.let { walker.walk(it) }
    } else {
        cards.forEach { walker.walk(it) }
    }
    out.flush()
    return out.sections
}

/** Collects rows into titled sections; untitled rows join whatever section is open. */
private class SectionBuilder {
    val sections = mutableListOf<ListSection>()
    private var title: String? = null
    private var rows = mutableListOf<ListRow>()

    fun startSection(newTitle: String?) {
        flush()
        title = newTitle
    }

    fun add(row: ListRow?) {
        row?.let { rows += it }
    }

    fun flush() {
        if (rows.isNotEmpty()) sections += ListSection(title, rows.toList())
        rows = mutableListOf()
        title = null
    }
}

/** Card types whose content is a single `entity`. */
private val SINGLE_ENTITY_CARDS = setOf(
    "tile", "button", "entity", "light", "thermostat", "humidifier", "sensor", "gauge",
    "media-control", "alarm-panel", "weather-forecast", "picture-entity", "statistic", "plant-status",
    "area-entity",
)

/** Card types with no text form. */
private val SKIPPED_CARDS = setOf(
    "history-graph", "statistics-graph", "logbook", "map", "iframe", "picture", "calendar",
    "energy-date-selection", "energy-usage-graph", "energy-distribution", "todo-list", "shopping-list",
)

private class CardWalker(
    private val snapshot: HomeSnapshot,
    private val views: List<DashboardView>,
    private val out: SectionBuilder,
) {
    fun walk(element: JsonElement) {
        val card = element as? JsonObject ?: return
        if (!visible(card)) return
        val type = card.string("type").orEmpty()
        when {
            type == "heading" -> out.startSection(card.string("heading"))
            type == "entities" || type == "glance" || type == "picture-glance" -> titled(card) {
                card.array("entities")?.forEach { entitiesEntry(it) }
            }
            type == "vertical-stack" || type == "horizontal-stack" || type == "grid" -> titled(card, keepOpen = true) {
                card.array("cards")?.forEach { walk(it) }
            }
            type == "conditional" -> if (conditionsMet(card.array("conditions"))) card["card"]?.let { walk(it) }
            type == "markdown" -> titled(card) { markdownRow(card.string("content"))?.let(out::add) }
            type == "area" -> card.string("area")?.let { areaId ->
                snapshot.registry.areasById[areaId]?.let { area ->
                    out.add(ListRow.Link(resolveName(card["name"], null) ?: area.name, "", Destination.Area(areaId, area.name)))
                }
            }
            type in SINGLE_ENTITY_CARDS -> singleEntity(card)
            type in SKIPPED_CARDS -> Unit
            // custom:* and anything newer: use whatever entity/entities/cards it carries.
            else -> titled(card, keepOpen = true) {
                singleEntity(card)
                card.array("entities")?.forEach { entitiesEntry(it) }
                card.array("cards")?.forEach { walk(it) }
                card["card"]?.let { walk(it) }
            }
        }
    }

    /** A card with a `title` gets its own section. [keepOpen] lets stacks' later untitled rows continue it. */
    private inline fun titled(card: JsonObject, keepOpen: Boolean = false, body: () -> Unit) {
        val title = card.string("title")
        if (title == null) {
            body()
            return
        }
        out.startSection(title)
        body()
        if (!keepOpen) out.flush()
    }

    private fun singleEntity(card: JsonObject) {
        val entityId = card.string("entity")
        if (entityId != null) {
            out.add(entityRow(entityId, card["name"]))
            return
        }
        // A button with no entity that navigates to another view: link to that view.
        navigationLink(card)?.let(out::add)
    }

    private fun navigationLink(card: JsonObject): ListRow? {
        val action = card.obj("tap_action") ?: return null
        if (action.string("action") != "navigate") return null
        val target = action.string("navigation_path")?.trimEnd('/')?.substringAfterLast('/') ?: return null
        val view = views.firstOrNull { it.path == target } ?: return null
        return ListRow.Link(resolveName(card["name"], null) ?: view.title, "", Destination.View(view.path, view.title))
    }

    /** An `entities` card row: an entity id, `{entity, name}`, or a section/divider label. */
    private fun entitiesEntry(entry: JsonElement) {
        when (entry) {
            is JsonPrimitive -> out.add(entityRow(entry.content, null))
            is JsonObject -> when {
                entry.string("type") == "section" -> entry.string("label")?.let { out.startSection(it) }
                entry.string("entity") != null && visible(entry) -> out.add(entityRow(entry.string("entity")!!, entry["name"]))
            }
            else -> Unit
        }
    }

    /**
     * [nameConfig] is the card's `name` for this entity. Shown name: that, else the name the user
     * gave the entity in HA, else HA's friendly name.
     */
    fun entityRow(entityId: String, nameConfig: JsonElement?): ListRow? {
        val entity: EntityState = snapshot.states[entityId] ?: return null
        return ListRow.Entity(
            entityId = entityId,
            name = resolveName(nameConfig, entityId) ?: snapshot.registry.entityDisplayName(entity, null),
            value = snapshot.pending[entityId] ?: formatState(entity),
            tapOpensDetail = tapOpensDetail(entity),
            longPressOpensDetail = longPressOpensDetail(entity),
        )
    }

    /**
     * A card's `name`: plain text, or HA's structured form, `{type: entity|device|area|text}` or a
     * list of those joined with spaces. Null when the card sets no name.
     */
    fun resolveName(config: JsonElement?, entityId: String?): String? {
        val registry = snapshot.registry
        val entry = entityId?.let { registry.entry(it) }
        val name = when (config) {
            is JsonPrimitive -> if (config.isString) config.content else null
            is JsonArray -> config.mapNotNull { resolveName(it, entityId) }.joinToString(" ")
            is JsonObject -> when (config.string("type")) {
                "text" -> config.string("text")
                "entity" -> entityId?.let { id ->
                    registry.customName(id) ?: entry?.originalName ?: snapshot.states[id]?.friendlyName
                }
                "device" -> registry.device(entry?.deviceId)?.displayName
                "area" -> entityId?.let { registry.areaIdOf(it) }?.let { registry.areasById[it]?.name }
                else -> null
            }
            else -> null
        }
        return name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Plain markdown only; templated content ({{ }}, {% %}) can't be rendered without HA. */
    private fun markdownRow(content: String?): ListRow? {
        val text = content?.trim().orEmpty()
        if (text.isEmpty() || "{{" in text || "{%" in text) return null
        val plain = text.lines().joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("__", "") }.trim()
        return ListRow.Text(plain)
    }

    /** Card/section `visibility` conditions. */
    fun visible(obj: JsonObject): Boolean = conditionsMet(obj.array("visibility"))

    /**
     * Evaluates state conditions (`entity` + `state` / `state_not`, including the legacy
     * conditional-card form) and and/or/not groups. Screen-size and user conditions count as met.
     */
    private fun conditionsMet(conditions: JsonArray?): Boolean =
        conditions.orEmpty().all { (it as? JsonObject)?.let(::conditionMet) ?: true }

    private fun conditionMet(condition: JsonObject): Boolean {
        when (condition.string("condition")) {
            "and" -> return conditionsMet(condition.array("conditions"))
            "or" -> return condition.array("conditions").orEmpty().any { (it as? JsonObject)?.let(::conditionMet) ?: true }
            "not" -> return !conditionsMet(condition.array("conditions"))
            "screen", "user", "time", "location" -> return true
        }
        val entityId = condition.string("entity") ?: return true
        val state = snapshot.states[entityId]?.state
        condition["state"]?.let { expected -> return state in expected.values() }
        condition["state_not"]?.let { excluded -> return state !in excluded.values() }
        val number = state?.toDoubleOrNull()
        condition["above"]?.textOrNull()?.toDoubleOrNull()?.let { if (number == null || number <= it) return false }
        condition["below"]?.textOrNull()?.toDoubleOrNull()?.let { if (number == null || number >= it) return false }
        return true
    }

    private fun JsonElement.values(): Set<String> = when (this) {
        is JsonArray -> mapNotNull { it.textOrNull() }.toSet()
        else -> setOfNotNull(textOrNull())
    }
}
