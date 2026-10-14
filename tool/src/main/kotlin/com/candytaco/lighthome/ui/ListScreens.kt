package com.candytaco.lighthome.ui

import com.candytaco.lighthome.domain.Summary
import com.candytaco.lighthome.domain.areaSections
import com.candytaco.lighthome.domain.homeSections
import com.candytaco.lighthome.domain.otherDevicesSections
import com.candytaco.lighthome.domain.summarySections
import com.candytaco.lighthome.domain.viewSections
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity

/**
 * The user's dashboard as text: its cards (one view) or its views (several). Falls back to the
 * generated Home-panel layout when the dashboard has no cards of its own.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SectionedListScreen(
    sealedActivity = sealedActivity,
    title = "Home Assistant",
    showBack = false,
    emptyText = "Empty dashboard",
    build = { homeSections() },
    showSettings = true,
)

/** One view (tab) of the user's dashboard, its cards as sections. */
class DashboardViewScreen(sealedActivity: SealedLightActivity, path: String, title: String) : SectionedListScreen(
    sealedActivity = sealedActivity,
    title = title,
    showBack = true,
    emptyText = "No cards with text content",
    build = { viewSections(path) },
)

/** One area, laid out like HA's area view: lights, climate, security, media, scenes, devices, automations. */
class AreaScreen(sealedActivity: SealedLightActivity, areaId: String, name: String) : SectionedListScreen(
    sealedActivity = sealedActivity,
    title = name,
    showBack = true,
    emptyText = "No devices in this area",
    build = { areaSections(areaId) },
)

/** A summary's entities (all lights, all security sensors, …) grouped by area. */
class SummaryScreen(sealedActivity: SealedLightActivity, summary: Summary) : SectionedListScreen(
    sealedActivity = sealedActivity,
    title = summary.title,
    showBack = true,
    emptyText = "Nothing in this summary",
    build = { summarySections(summary) },
)

/** Devices not assigned to any area, one section per device. */
class OtherDevicesScreen(sealedActivity: SealedLightActivity) : SectionedListScreen(
    sealedActivity = sealedActivity,
    title = "Devices",
    showBack = true,
    emptyText = "No unassigned devices",
    build = { otherDevicesSections() },
)
