package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AccountSecretAuthorityPolicyV1Test {

    @Test
    fun emptyMaskRequiresClientCertificateAuthority() {
        assertFalse(AccountSecretAuthorityPolicyV1.emptyMaskCanHydrate(null))
        assertFalse(AccountSecretAuthorityPolicyV1.emptyMaskCanHydrate(""))
        assertTrue(AccountSecretAuthorityPolicyV1.emptyMaskCanHydrate("android-keystore-alias"))
    }

    @Test
    fun nullRuntimeSecretIsKeepNotDelete() {
        assertTrue(
            AccountSecretRuntimeUpdatePolicyV1.decide(null, false) ==
                AccountSecretDurableUpdateV1.KEEP,
        )
        assertTrue(
            AccountSecretRuntimeUpdatePolicyV1.decide("", false) ==
                AccountSecretDurableUpdateV1.KEEP,
        )
    }

    @Test
    fun durableDeleteRequiresExplicitRetirement() {
        assertTrue(
            AccountSecretRuntimeUpdatePolicyV1.decide("old-token", true) ==
                AccountSecretDurableUpdateV1.DELETE,
        )
        assertTrue(
            AccountSecretRuntimeUpdatePolicyV1.decide("new-token", false) ==
                AccountSecretDurableUpdateV1.PUT,
        )
    }

    @Test
    fun onlyCommittedSecretAuthorityPermitsConnection() {
        assertTrue(
            AccountSecretConnectionPolicyV1.permitsConnection(
                AccountSecretMigrationOutcomeV1.COMMITTED,
            ),
        )
        assertTrue(
            AccountSecretConnectionPolicyV1.permitsConnection(
                AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED,
            ),
        )
        assertFalse(
            AccountSecretConnectionPolicyV1.permitsConnection(
                AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE,
            ),
        )
        assertFalse(
            AccountSecretConnectionPolicyV1.permitsConnection(
                AccountSecretMigrationOutcomeV1.FAILED,
            ),
        )
    }

    @Test
    fun transientClearDoesNotRequestFastTokenRetirement() {
        val account = Account(Jid.of("alice@example.test"), "password")
        account.setFastTokenValue("fast-token")

        account.clearTransientAuthenticationSecrets()

        assertFalse(account.isFastTokenRetirementRequested)
    }

    @Test
    fun explicitFastResetRequestsDurableRetirementUntilPersisted() {
        val account = Account(Jid.of("alice@example.test"), "password")
        account.setFastTokenValue("fast-token")

        account.resetFastToken()
        assertTrue(account.isFastTokenRetirementRequested)

        account.markFastTokenRetirementPersisted()
        assertFalse(account.isFastTokenRetirementRequested)
    }

    @Test
    fun newFastTokenCancelsPendingRetirement() {
        val account = Account(Jid.of("alice@example.test"), "password")
        account.resetFastToken()
        assertTrue(account.isFastTokenRetirementRequested)

        account.setFastTokenValue("replacement-token")

        assertFalse(account.isFastTokenRetirementRequested)
    }
}
