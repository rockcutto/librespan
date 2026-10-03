package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.persistance.DatabaseBackend

/**
 * Process gate for production blind-search composition.
 *
 * Android Application startup enables the factory. Plain JVM coordinator tests do not run
 * Application.onCreate(), so they keep the search dependency absent unless explicitly injected.
 */
object SecureMessageSearchRuntime {
    @Volatile private var productionEnabled = false
    private val macProvider: SecureMessageSearchMacProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreSecureMessageSearchMacProvider()
    }

    @JvmStatic
    fun enableProduction() {
        productionEnabled = true
    }

    fun create(databaseBackend: DatabaseBackend): SecureMessageSearchCoordinator? {
        if (!productionEnabled) return null
        val codec =
            SecureMessageSearchIndexCodec(
                macProvider,
            )
        return SecureMessageSearchCoordinator(
            codec,
            DatabaseSecureMessageSearchIndex(databaseBackend),
        )
    }
}
