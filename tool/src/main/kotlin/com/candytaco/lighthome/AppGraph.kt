package com.candytaco.lighthome

import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.storage.CredentialStore
import com.thelightphone.sdk.SealedLightContext

/** Process-wide objects. Screens come and go; the Home Assistant session is shared. */
internal object AppGraph {
    @Volatile
    private var session: HaSession? = null

    fun session(context: SealedLightContext): HaSession =
        session ?: synchronized(this) {
            session ?: HaSession(CredentialStore(context.dataStore), context.connectivity).also { session = it }
        }
}
