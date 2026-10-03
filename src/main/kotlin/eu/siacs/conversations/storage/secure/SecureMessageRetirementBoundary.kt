package eu.siacs.conversations.storage.secure

/**
 * Application deletion gate for protected message content.
 *
 * Callers must complete this boundary before deleting the owning Message/Conversation DB rows or
 * cleaning legacy physical files. Any exception is fail-closed: the owner row must remain so the
 * retirement can be retried/recovered.
 *
 * [payloadCoordinator] is deliberately optional so media retirement can be enabled independently
 * from the protected-text rollout. Media must never remain readable merely because text payload
 * storage is still gated off.
 */
class SecureMessageRetirementBoundary(
    private val payloadCoordinator: SecureMessagePayloadCoordinator?,
    private val store: SecureContentStore,
) {
    private val mediaCoordinator = SecureMessageMediaCoordinator(store)

    /**
     * Retires every enabled protected object owned by one message. Absence is idempotent.
     *
     * The Store's delete path invalidates key material before blob/metadata cleanup, while an
     * enabled payload coordinator maintains its durable DB retirement evidence.
     */
    fun retireMessage(accountUuid: String, messageUuid: String) {
        mediaCoordinator.retire(accountUuid, messageUuid)
        payloadCoordinator?.retire(accountUuid, messageUuid)
    }

    /**
     * Retires protected media for the conversation's known message identities, then retires the
     * payload coordinator's durable conversation relations when protected text is enabled.
     *
     * [mediaMessageUuids] must be captured from the owning DB rows before those rows are removed;
     * the Secure Content Store deliberately has no conversation-wide inference from paths/blobs.
     */
    fun retireConversation(
        accountUuid: String,
        conversationUuid: String,
        mediaMessageUuids: Iterable<String>,
    ) {
        mediaMessageUuids
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { mediaCoordinator.retire(accountUuid, it) }
        payloadCoordinator?.retireAllForConversation(accountUuid, conversationUuid)
    }

    /** Retires every authenticated Store object owned by one retained account. */
    fun retireAccount(accountUuid: String) {
        val ownedObjects = store.findByAccount(accountUuid)
            .filter {
                it.namespace == SecureMessagePayloadContext.NAMESPACE ||
                    it.namespace == SecureMessageMediaCoordinator.NAMESPACE
            }
        ownedObjects.forEach { snapshot ->
            check(snapshot.accountUuid == accountUuid) { "Secure Content account ownership mismatch" }
            store.delete(accountUuid, snapshot.contentId)
            check(store.find(accountUuid, snapshot.contentId) == null) {
                "Secure Content object remains available after account cleanup"
            }
        }
    }
}
