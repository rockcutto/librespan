package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;



import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConferenceDetailsActivity;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.MucUsersActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;


public final class MucDetailsContextMenuHelper {

    public static void onCreateContextMenu(ContextMenu menu, View v) {
        final XmppActivity activity = XmppActivity.find(v);
        final Object tag = v.getTag();
        if (tag instanceof MucOptions.User && activity != null) {
            activity.getMenuInflater().inflate(R.menu.muc_details_context, menu);
            final MucOptions.User user = (MucOptions.User) tag;
            String name;
            final Contact contact = user.getContact();
            if (contact != null && contact.showInContactList()) {
                name = contact.getDisplayName();
            } else if (user.getRealJid() != null) {
                name = user.getRealJid().asBareJid().toString();
            } else {
                name = user.getName();
            }
            menu.setHeaderTitle(name);
            MucDetailsContextMenuHelper.configureMucDetailsContextMenu(activity, menu, user.getConversation(), user);
        }
    }

    public static void configureMucDetailsContextMenu(
            Activity activity, Menu menu, Conversation conversation, User user) {
        final MucOptions mucOptions = conversation.getMucOptions();
        final User self = mucOptions.getSelf();
        final boolean isGroupChat = mucOptions.isPrivateAndNonAnonymous();
        final boolean selfIsAdmin =
                self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN);
        final boolean selfIsOwner =
                self.getAffiliation().ranks(MucOptions.Affiliation.OWNER);
        final boolean selfIsModerator =
                self.getRole().ranks(MucOptions.Role.MODERATOR);

        final MenuItem sendPrivateMessage = menu.findItem(R.id.send_private_message);
        final boolean targetIsSelf =
                user != null
                        && (user.realJidMatchesAccount()
                                || (user.getFullJid() != null
                                        && user.getFullJid().equals(self.getFullJid())));
        if (user != null && (user.getRealJid() != null || targetIsSelf)) {
            final MenuItem showContactDetails = menu.findItem(R.id.action_contact_details);
            final MenuItem startConversation = menu.findItem(R.id.start_conversation);
            final MenuItem giveMembership = menu.findItem(R.id.give_membership);
            final MenuItem removeMembership = menu.findItem(R.id.remove_membership);
            final MenuItem giveAdminPrivileges = menu.findItem(R.id.give_admin_privileges);
            final MenuItem giveOwnerPrivileges = menu.findItem(R.id.give_owner_privileges);
            final MenuItem removeOwnerPrivileges = menu.findItem(R.id.revoke_owner_privileges);
            final MenuItem removeAdminPrivileges = menu.findItem(R.id.remove_admin_privileges);
            final MenuItem giveModeratorRole = menu.findItem(R.id.give_moderator_role);
            final MenuItem removeModeratorRole = menu.findItem(R.id.remove_moderator_role);
            final MenuItem grantVoice = menu.findItem(R.id.grant_voice);
            final MenuItem revokeVoice = menu.findItem(R.id.revoke_voice);
            final MenuItem removeFromRoom = menu.findItem(R.id.remove_from_room);
            final MenuItem managePermissions = menu.findItem(R.id.manage_permissions);
            final MenuItem banFromConference = menu.findItem(R.id.ban_from_conference);
            final MenuItem invite = menu.findItem(R.id.invite);

            removeFromRoom.setTitle(
                    isGroupChat ? R.string.remove_from_room : R.string.remove_from_channel);
            banFromConference.setTitle(
                    isGroupChat ? R.string.ban_from_conference : R.string.ban_from_channel);

            startConversation.setVisible(!targetIsSelf);
            final Contact contact = user.getContact();
            if (!targetIsSelf
                    && ((contact != null && contact.showInRoster())
                            || mucOptions.isPrivateAndNonAnonymous())) {
                showContactDetails.setVisible(contact == null || !contact.isSelf());
            }
            if (!targetIsSelf
                    && (activity instanceof ConferenceDetailsActivity
                            || activity instanceof MucUsersActivity)
                    && user.getRole() == MucOptions.Role.NONE) {
                invite.setVisible(true);
            }

            boolean managePermissionsVisible = false;
            if (targetIsSelf) {
                if (mucOptions.canSelfRevokeOwner()) {
                    removeOwnerPrivileges.setVisible(true);
                    managePermissionsVisible = true;
                }
            } else {
                final boolean canManageAffiliation =
                        selfIsAdmin
                                && (self.getAffiliation().outranks(user.getAffiliation())
                                        || selfIsOwner);

                if (canManageAffiliation) {
                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                        giveMembership.setVisible(true);
                        managePermissionsVisible = true;
                    } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                        removeMembership.setVisible(true);
                        managePermissionsVisible = true;
                    }

                    if (!Config.DISABLE_BAN
                            && user.getAffiliation() != MucOptions.Affiliation.OUTCAST) {
                        banFromConference.setVisible(true);
                        managePermissionsVisible = true;
                    }

                    if (user.getRole() != MucOptions.Role.NONE
                            && (!Config.DISABLE_BAN || mucOptions.membersOnly())) {
                        removeFromRoom.setVisible(true);
                    }
                }

                if (selfIsOwner) {
                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                        giveOwnerPrivileges.setVisible(true);
                        managePermissionsVisible = true;
                    } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER) {
                        removeOwnerPrivileges.setVisible(true);
                        managePermissionsVisible = true;
                    }

                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                        giveAdminPrivileges.setVisible(true);
                        managePermissionsVisible = true;
                    } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                        removeAdminPrivileges.setVisible(true);
                        managePermissionsVisible = true;
                    }
                }

                final boolean targetIsOnline = user.getRole() != MucOptions.Role.NONE;
                final boolean targetHasPermanentAdmin =
                        user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN);

                if (selfIsModerator
                        && targetIsOnline
                        && user.getRole() != MucOptions.Role.MODERATOR
                        && !targetHasPermanentAdmin) {
                    removeFromRoom.setVisible(true);
                }

                if (selfIsAdmin && selfIsModerator && targetIsOnline && !targetHasPermanentAdmin) {
                    if (user.getRole() == MucOptions.Role.MODERATOR) {
                        removeModeratorRole.setVisible(true);
                    } else {
                        giveModeratorRole.setVisible(true);
                    }
                    managePermissionsVisible = true;
                }

                if (selfIsModerator && targetIsOnline && !targetHasPermanentAdmin && mucOptions.moderated()) {
                    if (user.getRole() == MucOptions.Role.VISITOR) {
                        grantVoice.setVisible(true);
                        managePermissionsVisible = true;
                    } else if (user.getRole() == MucOptions.Role.PARTICIPANT) {
                        revokeVoice.setVisible(true);
                        managePermissionsVisible = true;
                    }
                }
            }

            managePermissions.setVisible(managePermissionsVisible);
            sendPrivateMessage.setVisible(
                    !targetIsSelf
                            && !isGroupChat
                            && mucOptions.allowPm()
                            && user.getRole().ranks(MucOptions.Role.VISITOR));
        } else {
            sendPrivateMessage.setVisible(true);
            sendPrivateMessage.setEnabled(
                    user != null
                            && mucOptions.allowPm()
                            && user.getRole().ranks(MucOptions.Role.VISITOR));

            if (user != null) {
                final MenuItem managePermissions = menu.findItem(R.id.manage_permissions);
                final MenuItem giveModeratorRole = menu.findItem(R.id.give_moderator_role);
                final MenuItem removeModeratorRole = menu.findItem(R.id.remove_moderator_role);
                final MenuItem grantVoice = menu.findItem(R.id.grant_voice);
                final MenuItem revokeVoice = menu.findItem(R.id.revoke_voice);
                final MenuItem removeFromRoom = menu.findItem(R.id.remove_from_room);
                final boolean targetIsOnline = user.getRole() != MucOptions.Role.NONE;
                boolean managePermissionsVisible = false;

                if (selfIsModerator
                        && targetIsOnline
                        && user.getRole() != MucOptions.Role.MODERATOR) {
                    removeFromRoom.setTitle(
                            isGroupChat ? R.string.remove_from_room : R.string.remove_from_channel);
                    removeFromRoom.setVisible(true);
                }

                if (selfIsAdmin && selfIsModerator && targetIsOnline) {
                    if (user.getRole() == MucOptions.Role.MODERATOR) {
                        removeModeratorRole.setVisible(true);
                    } else {
                        giveModeratorRole.setVisible(true);
                    }
                    managePermissionsVisible = true;
                }

                if (selfIsModerator && targetIsOnline && mucOptions.moderated()) {
                    if (user.getRole() == MucOptions.Role.VISITOR) {
                        grantVoice.setVisible(true);
                        managePermissionsVisible = true;
                    } else if (user.getRole() == MucOptions.Role.PARTICIPANT) {
                        revokeVoice.setVisible(true);
                        managePermissionsVisible = true;
                    }
                }

                managePermissions.setVisible(managePermissionsVisible);
            }
        }
    }

    public static boolean configureMessageModerationMenu(
            Activity activity, Menu menu, Conversation conversation, User user) {
        if (conversation == null || user == null) {
            return false;
        }
        final MucOptions mucOptions = conversation.getMucOptions();
        final User self = mucOptions.getSelf();
        final boolean selfIsAdmin =
                self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN);
        final boolean selfIsOwner =
                self.getAffiliation().ranks(MucOptions.Affiliation.OWNER);
        final boolean selfIsModerator =
                self.getRole().ranks(MucOptions.Role.MODERATOR);
        final boolean targetIsSelf =
                user.realJidMatchesAccount()
                        || (user.getFullJid() != null
                                && user.getFullJid().equals(self.getFullJid()));
        if (targetIsSelf || (!selfIsModerator && !selfIsAdmin)) {
            return false;
        }

        final MenuItem giveModeratorRole = menu.findItem(R.id.give_moderator_role);
        final MenuItem removeModeratorRole = menu.findItem(R.id.remove_moderator_role);
        final MenuItem grantVoice = menu.findItem(R.id.grant_voice);
        final MenuItem revokeVoice = menu.findItem(R.id.revoke_voice);
        final MenuItem giveAdminPrivileges = menu.findItem(R.id.give_admin_privileges);
        final MenuItem removeAdminPrivileges = menu.findItem(R.id.remove_admin_privileges);
        final MenuItem removeFromRoom = menu.findItem(R.id.remove_from_room);
        final MenuItem banFromConference = menu.findItem(R.id.ban_from_conference);

        final boolean targetIsOnline = user.getRole() != MucOptions.Role.NONE;
        final boolean targetIsModerator = user.getRole() == MucOptions.Role.MODERATOR;
        final boolean targetHasPermanentAdmin =
                user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN);
        boolean visible = false;

        if (selfIsAdmin
                && selfIsModerator
                && targetIsOnline
                && !targetHasPermanentAdmin) {
            if (targetIsModerator) {
                removeModeratorRole.setVisible(true);
            } else {
                giveModeratorRole.setVisible(true);
            }
            visible = true;
        }

        if (selfIsModerator
                && mucOptions.moderated()
                && targetIsOnline
                && !targetHasPermanentAdmin) {
            if (user.getRole() == MucOptions.Role.VISITOR) {
                grantVoice.setVisible(true);
                visible = true;
            } else if (user.getRole() == MucOptions.Role.PARTICIPANT) {
                revokeVoice.setVisible(true);
                visible = true;
            }
        }

        if (selfIsModerator
                && targetIsOnline
                && !targetIsModerator
                && !targetHasPermanentAdmin) {
            removeFromRoom.setTitle(
                    mucOptions.isPrivateAndNonAnonymous()
                            ? R.string.remove_from_room
                            : R.string.remove_from_channel);
            removeFromRoom.setVisible(true);
            visible = true;
        }

        if (selfIsOwner && user.getRealJid() != null) {
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                giveAdminPrivileges.setVisible(true);
                visible = true;
            } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                removeAdminPrivileges.setVisible(true);
                visible = true;
            }
        }

        final boolean canBan =
                user.getRealJid() != null
                        && !Config.DISABLE_BAN
                        && user.getAffiliation() != MucOptions.Affiliation.OUTCAST
                        && selfIsAdmin
                        && (self.getAffiliation().outranks(user.getAffiliation()) || selfIsOwner);
        if (canBan) {
            banFromConference.setTitle(
                    mucOptions.isPrivateAndNonAnonymous()
                            ? R.string.ban_from_conference
                            : R.string.ban_from_channel);
            banFromConference.setVisible(true);
            visible = true;
        }

        return visible;
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity) {
        return onContextItemSelected(item, user, activity, null);
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity, final String fingerprint) {
        final Conversation conversation = user.getConversation();
        final XmppConnectionService.OnAffiliationChanged onAffiliationChanged = activity instanceof XmppConnectionService.OnAffiliationChanged ? (XmppConnectionService.OnAffiliationChanged) activity : null;
        Jid jid = user.getRealJid();
        if (jid == null && user == conversation.getMucOptions().getSelf()) {
            jid = conversation.getAccount().getJid().asBareJid();
        }
        switch (item.getItemId()) {
            case R.id.action_contact_details:
                final Jid realJid = user.getRealJid();
                final Account account = conversation.getAccount();
                final Contact contact = realJid == null ? null : account.getRoster().getContact(realJid);
                if (contact != null) {
                    activity.switchToContactDetails(contact, fingerprint);
                }
                return true;
            case R.id.start_conversation:
                startConversation(user, activity);
                return true;
            case R.id.give_moderator_role:
                if (user.getName() != null) {
                    activity.xmppConnectionService.changeRoleInConference(
                            conversation, user.getName(), MucOptions.Role.MODERATOR);
                }
                return true;
            case R.id.remove_moderator_role:
            case R.id.grant_voice:
                if (user.getName() != null) {
                    activity.xmppConnectionService.changeRoleInConference(
                            conversation, user.getName(), MucOptions.Role.PARTICIPANT);
                }
                return true;
            case R.id.revoke_voice:
                if (user.getName() != null) {
                    activity.xmppConnectionService.changeRoleInConference(
                            conversation, user.getName(), MucOptions.Role.VISITOR);
                }
                return true;
            case R.id.give_admin_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.ADMIN, onAffiliationChanged);
                return true;
            case R.id.give_membership:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.MEMBER, onAffiliationChanged);
                return true;
            case R.id.remove_admin_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(
                        conversation,
                        jid,
                        conversation.getMucOptions().membersOnly()
                                ? MucOptions.Affiliation.MEMBER
                                : MucOptions.Affiliation.NONE,
                        onAffiliationChanged);
                return true;
            case R.id.revoke_owner_privileges:
                // XEP-0045 owner revocation is a demotion from owner to admin. Keep the user's
                // administrative affiliation while removing only owner-level room control.
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.ADMIN, onAffiliationChanged);
                return true;
            case R.id.give_owner_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.OWNER, onAffiliationChanged);
                return true;
            case R.id.remove_membership:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.NONE, onAffiliationChanged);
                return true;
            case R.id.remove_from_room:
                removeFromRoom(user, activity, onAffiliationChanged);
                return true;
            case R.id.ban_from_conference:
                confirmBan(user, activity, onAffiliationChanged);
                return true;
            case R.id.send_private_message:
                if (activity instanceof ConversationsActivity) {
                    ConversationFragment conversationFragment = ConversationFragment.get(activity);
                    if (conversationFragment != null) {
                        conversationFragment.privateMessageWith(user.getFullJid());
                        return true;
                    }
                }
                activity.privateMsgInMuc(conversation, user.getName());
                return true;
            case R.id.invite:
                // TODO use direct invites for public conferences
                if (user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                    activity.xmppConnectionService.directInvite(conversation, jid.asBareJid());
                } else {
                    activity.xmppConnectionService.invite(conversation, jid);
                }
                return true;
            default:
                return false;
        }
    }

    private static void confirmBan(
            final User user,
            final XmppActivity activity,
            final XmppConnectionService.OnAffiliationChanged onAffiliationChanged) {
        final Jid jid = user.getRealJid();
        if (jid == null) {
            return;
        }
        final Conversation conversation = user.getConversation();
        final String displayName =
                user.getContact() != null
                        ? user.getContact().getDisplayName()
                        : user.getName() != null
                                ? user.getName()
                                : jid.asBareJid().toString();

        new MaterialAlertDialogBuilder(activity)
                .setTitle(
                        conversation.getMucOptions().isPrivateAndNonAnonymous()
                                ? R.string.ban_from_conference
                                : R.string.ban_from_channel)
                .setMessage(activity.getString(R.string.muc_ban_confirm_message, displayName))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.ban_now,
                        (dialog, which) ->
                                activity.xmppConnectionService.changeAffiliationInConference(
                                        conversation,
                                        jid,
                                        MucOptions.Affiliation.OUTCAST,
                                        onAffiliationChanged))
                .show();
    }

    private static void removeFromRoom(
            final User user,
            XmppActivity activity,
            XmppConnectionService.OnAffiliationChanged onAffiliationChanged) {
        final Conversation conversation = user.getConversation();
        if (user.getName() != null && user.getRole() != MucOptions.Role.NONE) {
            activity.xmppConnectionService.changeRoleInConference(
                    conversation, user.getName(), MucOptions.Role.NONE);
        }
    }

    private static void startConversation(User user, XmppActivity activity) {
        if (user.getRealJid() != null) {
            Conversation newConversation = activity.xmppConnectionService.findOrCreateConversation(user.getAccount(), user.getRealJid().asBareJid(), null, false, false, true, null);
            activity.switchToConversation(newConversation);
        }
    }
}