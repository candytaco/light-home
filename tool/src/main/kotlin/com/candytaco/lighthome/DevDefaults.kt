package com.candytaco.lighthome

import com.candytaco.lighthome.net.ServerConfig

/**
 * Connection values compiled into debug builds from tool/dev.properties (see
 * dev.properties.example). All empty in release builds, so [server] is null there.
 */
internal object DevDefaults {
    val server: ServerConfig?
        get() = BuildConfig.DEV_HA_URL.takeIf { it.isNotBlank() }?.let {
            ServerConfig.create(it, BuildConfig.DEV_CF_CLIENT_ID, BuildConfig.DEV_CF_CLIENT_SECRET)
        }
}
