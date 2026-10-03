package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Bounded migration owner for historical Message.body plaintext.
 *
 * No caller is expected to run an unbounded sweep. A batch uses keyset pagination and each row
 * remains LEGACY_PLAINTEXT until an exact committed SCS object is readable and the database
 * atomically promotes the row to PROTECTED while retiring Message.body.
 *
 * A committed relation with no secure mode is valid interrupted-migration evidence. Retry verifies
 * that exact object against the still-authoritative legacy body and finalizes it instead of
 * allocating a second object.
 */
class LegacyPlaintextMessageMigrator(
    private val databaseBackend: DatabaseBackend,
    private val coordinator: SecureMessagePayloadCoordinator,
) {
    data class BatchResult(
        val nextAfterRowId: Long,
        val selected: Int,
        val migrated: Int,
        val skipped: Int,
        val failed: Int,
    )

    enum class ItemResult {
        MIGRATED,
        SKIPPED,
        FAILED,
    }

    @Throws(IllegalArgumentException::class)
    fun migrateBatch(
        accountUuid: String,
        afterRowId: Long = 0L,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): BatchResult {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(afterRowId >= 0L) { "afterRowId must not be negative" }
        require(limit in 1..MAXIMUM_BATCH_SIZE) {
            "legacy plaintext migration batch must be in 1..$MAXIMUM_BATCH_SIZE"
        }

        val batch =
            databaseBackend.getLegacyPlaintextMessageBatch(
                accountUuid,
                afterRowId,
                limit,
            )
        var migrated = 0
        var skipped = 0
        var failed = 0
        batch.forEach { record ->
            when (migrateOne(record)) {
                ItemResult.MIGRATED -> migrated += 1
                ItemResult.SKIPPED -> skipped += 1
                ItemResult.FAILED -> failed += 1
            }
        }
        return BatchResult(
            nextAfterRowId = batch.lastOrNull()?.rowId ?: afterRowId,
            selected = batch.size,
            migrated = migrated,
            skipped = skipped,
            failed = failed,
        )
    }

    fun migrateNewestBackgroundBatch(
        accountUuid: String,
        limit: Int = DEFAULT_BACKGROUND_BATCH_SIZE,
    ): BatchResult {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(limit in MINIMUM_BACKGROUND_BATCH_SIZE..MAXIMUM_BACKGROUND_BATCH_SIZE) {
            "background legacy migration batch must be in " +
                "$MINIMUM_BACKGROUND_BATCH_SIZE..$MAXIMUM_BACKGROUND_BATCH_SIZE"
        }

        val batch = databaseBackend.getNewestLegacyPlaintextMessageBatch(accountUuid, limit)
        var migrated = 0
        var skipped = 0
        var failed = 0
        batch.forEach { record ->
            when (migrateOne(record)) {
                ItemResult.MIGRATED -> migrated += 1
                ItemResult.SKIPPED -> skipped += 1
                ItemResult.FAILED -> failed += 1
            }
        }
        return BatchResult(
            nextAfterRowId = batch.lastOrNull()?.rowId ?: 0L,
            selected = batch.size,
            migrated = migrated,
            skipped = skipped,
            failed = failed,
        )
    }

    fun migrateOne(record: LegacyPlaintextMessageRecord): ItemResult =
        migrateLoadedMessage(
            accountUuid = record.accountUuid,
            messageUuid = record.messageUuid,
            expectedBody = record.body,
        )

    /**
     * Migrates one message that was already loaded as part of a bounded conversation page.
     *
     * The caller supplies only the plaintext it already owns in that resident Message. Durable
     * classification and the exact body are revalidated in the database before plaintext
     * retirement, so a stale or concurrently edited page cannot authorize migration.
     */
    fun migrateLoadedMessage(
        accountUuid: String,
        messageUuid: String,
        expectedBody: String,
    ): ItemResult {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        if (expectedBody.isEmpty()) {
            return ItemResult.SKIPPED
        }
        if (databaseBackend.getMessagePayloadClassification(
                accountUuid,
                messageUuid,
            ) != MessagePayloadClassification.LEGACY_PLAINTEXT
        ) {
            return ItemResult.SKIPPED
        }

        val plaintext =
            try {
                SecureTextPayload.encodeUtf8(expectedBody)
            } catch (_: IOException) {
                return ItemResult.FAILED
            }

        return try {
            val existing =
                databaseBackend.findSecureMessagePayloadReference(
                    accountUuid,
                    messageUuid,
                )
            val publishedNow = existing == null
            val reference =
                if (existing != null) {
                    if (!matchesCommittedPayload(existing, plaintext)) {
                        return ItemResult.FAILED
                    }
                    existing
                } else {
                    publish(accountUuid, messageUuid, plaintext)
                }

            if (databaseBackend.finalizeLegacyPlaintextMigration(
                    accountUuid,
                    messageUuid,
                    expectedBody,
                    reference,
                )
            ) {
                ItemResult.MIGRATED
            } else {
                // A concurrent owner may have completed the same row after our publication.
                val classification =
                    databaseBackend.getMessagePayloadClassification(
                        accountUuid,
                        messageUuid,
                    )
                if (classification == MessagePayloadClassification.PROTECTED) {
                    ItemResult.SKIPPED
                } else {
                    // If our freshly published relation cannot be promoted, do not leave a
                    // searchable orphan beside a still-authoritative legacy body. Retirement is
                    // exact and recovery-backed; failure still preserves the legacy plaintext.
                    if (publishedNow &&
                        databaseBackend.findSecureMessagePayloadReference(
                            accountUuid,
                            messageUuid,
                        ) == reference
                    ) {
                        try {
                            coordinator.retire(reference)
                        } catch (_: Exception) {
                            // Retirement evidence is authoritative; never clear legacy plaintext.
                        }
                    }
                    ItemResult.FAILED
                }
            }
        } catch (_: Exception) {
            // Durable publication/recovery evidence remains authoritative. A later bounded retry
            // either resumes the exact relation or retries the still-legacy row.
            ItemResult.FAILED
        }
    }

    private fun publish(
        accountUuid: String,
        messageUuid: String,
        plaintext: ByteArray,
    ): SecureMessagePayloadReference {
        val context =
            SecureMessagePayloadContext(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
            )
        val session = coordinator.beginWrite(context)
        var completed = false
        try {
            session.openPlaintextOutputStream().use { output ->
                output.write(plaintext)
            }
            val reference = session.commitAndPublish(plaintext)
            completed = true
            return reference
        } finally {
            if (!completed) {
                try {
                    session.abort()
                } catch (_: Exception) {
                    // Coordinator recovery evidence decides whether a committed object survives.
                }
            }
        }
    }

    private fun matchesCommittedPayload(
        reference: SecureMessagePayloadReference,
        expected: ByteArray,
    ): Boolean {
        val session =
            try {
                coordinator.open(reference)
            } catch (_: Exception) {
                return false
            }
        return try {
            val actual =
                session.openPlaintextInputStream().use { input ->
                    val output = ByteArrayOutputStream(expected.size.coerceAtLeast(32))
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > SecureTextPayload.MAXIMUM_BYTES - total) {
                            return false
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.toByteArray()
                }
            session.verifyTerminal()
            if (actual.size != expected.size) {
                false
            } else {
                actual.contentEquals(expected)
            }
        } catch (_: Exception) {
            false
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 8
        const val MAXIMUM_BATCH_SIZE: Int = 16

        const val MINIMUM_BACKGROUND_BATCH_SIZE: Int = 25
        const val DEFAULT_BACKGROUND_BATCH_SIZE: Int = 32
        const val MAXIMUM_BACKGROUND_BATCH_SIZE: Int = 50
    }
}
