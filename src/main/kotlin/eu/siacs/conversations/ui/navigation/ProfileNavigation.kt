package eu.siacs.conversations.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.ProfileActivity
import eu.siacs.conversations.ui.ProfileDevicesActivity
import eu.siacs.conversations.utils.AccountUtils

/**
 * Owns the account-context contract for canonical Profile and Profile Devices routes.
 *
 * A global route intentionally has no account identity and keeps the existing default selection
 * policy. A contextual route carries an exact account UUID and must never fall back to another
 * account when that UUID is no longer resolvable.
 */
sealed class ProfileRoute {
    object Global : ProfileRoute()

    data class Contextual(val accountUuid: String) : ProfileRoute()
}

sealed class ProfileAccountResolution {
    data class Resolved(val account: Account) : ProfileAccountResolution()

    object MissingGlobalAccount : ProfileAccountResolution()

    object MissingContextualAccount : ProfileAccountResolution()
}

object ProfileNavigation {
    private const val EXTRA_PROFILE_ACCOUNT_UUID =
        "eu.siacs.conversations.ui.navigation.PROFILE_ACCOUNT_UUID"
    private const val PREFERENCE_LAST_PROFILE_ACCOUNT_UUID =
        "last_profile_account_uuid"
    private const val PREFERENCE_CONVERSATION_LIST_PROFILE_SCOPE =
        "conversation_list_profile_account_only"

    @JvmStatic
    fun globalProfileIntent(context: Context): Intent = Intent(context, ProfileActivity::class.java)

    @JvmStatic
    fun profileIntentForHome(
        context: Context,
        xmppConnectionService: XmppConnectionService
    ): Intent {
        val preferred = preferredProfileAccount(context, xmppConnectionService)
        return if (preferred == null) {
            globalProfileIntent(context)
        } else {
            contextualProfileIntent(context, preferred.uuid)
        }
    }

    @JvmStatic
    fun preferredProfileAccount(
        context: Context,
        xmppConnectionService: XmppConnectionService
    ): Account? {
        val rememberedUuid =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREFERENCE_LAST_PROFILE_ACCOUNT_UUID, null)
        if (!rememberedUuid.isNullOrBlank()) {
            xmppConnectionService.findAccountByUuid(rememberedUuid)?.let { return it }
        }
        return AccountUtils.getFirstEnabled(xmppConnectionService)
            ?: AccountUtils.getFirst(xmppConnectionService)
    }

    @JvmStatic
    fun rememberProfileAccount(context: Context, accountUuid: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREFERENCE_LAST_PROFILE_ACCOUNT_UUID, accountUuid)
            .apply()
    }

    @JvmStatic
    fun isConversationListScopedToProfileAccount(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREFERENCE_CONVERSATION_LIST_PROFILE_SCOPE, false)

    @JvmStatic
    fun setConversationListScopedToProfileAccount(context: Context, scoped: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREFERENCE_CONVERSATION_LIST_PROFILE_SCOPE, scoped)
            .apply()
    }

    @JvmStatic
    fun conversationListScopeAccount(
        context: Context,
        xmppConnectionService: XmppConnectionService
    ): Account? =
        if (isConversationListScopedToProfileAccount(context)) {
            preferredProfileAccount(context, xmppConnectionService)
        } else {
            null
        }

    @JvmStatic
    fun contextualProfileIntent(context: Context, accountUuid: String): Intent =
        profileIntent(context, ProfileRoute.Contextual(accountUuid))

    @JvmStatic
    fun contextualDevicesIntent(context: Context, accountUuid: String): Intent =
        devicesIntent(context, ProfileRoute.Contextual(accountUuid))

    @JvmStatic
    fun routeFrom(intent: Intent): ProfileRoute {
        val accountUuid = intent.getStringExtra(EXTRA_PROFILE_ACCOUNT_UUID)
        return if (accountUuid.isNullOrBlank()) {
            ProfileRoute.Global
        } else {
            ProfileRoute.Contextual(accountUuid)
        }
    }

    @JvmStatic
    fun resolveAccount(
        xmppConnectionService: XmppConnectionService,
        intent: Intent
    ): ProfileAccountResolution =
        resolveAccount(null, xmppConnectionService, intent)

    @JvmStatic
    fun resolveAccount(
        context: Context?,
        xmppConnectionService: XmppConnectionService,
        intent: Intent
    ): ProfileAccountResolution =
        when (val route = routeFrom(intent)) {
            ProfileRoute.Global -> {
                val account =
                    if (context == null) {
                        AccountUtils.getFirstEnabled(xmppConnectionService)
                            ?: AccountUtils.getFirst(xmppConnectionService)
                    } else {
                        preferredProfileAccount(context, xmppConnectionService)
                    }
                if (account == null) {
                    ProfileAccountResolution.MissingGlobalAccount
                } else {
                    ProfileAccountResolution.Resolved(account)
                }
            }

            is ProfileRoute.Contextual -> {
                val account = xmppConnectionService.findAccountByUuid(route.accountUuid)
                if (account == null) {
                    ProfileAccountResolution.MissingContextualAccount
                } else {
                    ProfileAccountResolution.Resolved(account)
                }
            }
        }

    private fun profileIntent(context: Context, route: ProfileRoute): Intent =
        Intent(context, ProfileActivity::class.java).apply {
            putRoute(route)
        }

    private fun devicesIntent(context: Context, route: ProfileRoute): Intent =
        Intent(context, ProfileDevicesActivity::class.java).apply {
            putRoute(route)
        }

    private fun Intent.putRoute(route: ProfileRoute) {
        if (route is ProfileRoute.Contextual) {
            putExtra(EXTRA_PROFILE_ACCOUNT_UUID, route.accountUuid)
        }
    }
}
