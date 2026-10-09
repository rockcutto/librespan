package eu.siacs.conversations.parser;

import android.os.Build;
import android.text.Html;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import net.java.otr4j.session.Session;
import net.java.otr4j.session.SessionStatus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OtrService;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.BrokenSessionException;
import eu.siacs.conversations.crypto.axolotl.NotEncryptedForThisDeviceException;
import eu.siacs.conversations.crypto.axolotl.OutdatedSenderException;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.ReadByMarker;
import eu.siacs.conversations.entities.ReceiptRequest;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.MucModerationProtocol;
import eu.siacs.conversations.xmpp.MessageRetractionProtocol;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.pep.Avatar;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.axolotl.Encrypted;
import im.conversations.android.xmpp.model.carbons.Received;
import im.conversations.android.xmpp.model.carbons.Sent;
import im.conversations.android.xmpp.model.correction.Replace;
import im.conversations.android.xmpp.model.forward.Forwarded;
import im.conversations.android.xmpp.model.markers.Displayed;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.reactions.Reactions;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class MessageParser extends AbstractParser
        implements Consumer<im.conversations.android.xmpp.model.stanza.Message> {
    private static final List<String> CLIENTS_SENDING_HTML_IN_OTR = Arrays.asList("Pidgin", "Adium", "Trillian");

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);

    private static final List<String> JINGLE_MESSAGE_ELEMENT_NAMES =
            Arrays.asList("accept", "propose", "proceed", "reject", "retract", "ringing", "finish");

    public MessageParser(final XmppConnectionService service, final Account account) {
        super(service, account);
    }

    enum RoomStanzaIdentityDisposition {
        NONE,
        ACCEPT,
        SUPPRESS_MODERATED_REPLAY,
        REJECT_COLLISION
    }

    static boolean shouldExecuteInvite(final boolean forwarded, final boolean mamResult) {
        return !forwarded && !mamResult;
    }

    static boolean shouldSuppressTrustedMucMamReplay(
            @Nullable final String mamResultId,
            final boolean hasDurableModerationMarkerForResultId) {
        return mamResultId != null
                && !mamResultId.isEmpty()
                && hasDurableModerationMarkerForResultId;
    }

    static RoomStanzaIdentityDisposition classifyRoomStanzaIdentity(
            @Nullable final String candidateRoomStanzaId,
            final boolean hasDurableModerationMarker,
            final boolean alreadyClaimed) {
        if (candidateRoomStanzaId == null || candidateRoomStanzaId.isEmpty()) {
            return RoomStanzaIdentityDisposition.NONE;
        }
        if (hasDurableModerationMarker) {
            return RoomStanzaIdentityDisposition.SUPPRESS_MODERATED_REPLAY;
        }
        return alreadyClaimed
                ? RoomStanzaIdentityDisposition.REJECT_COLLISION
                : RoomStanzaIdentityDisposition.ACCEPT;
    }

    static String applyLegacyRetractionFallback(
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        if (packet == null) {
            return null;
        }
        final Element fasten = packet.findChild("apply-to", "urn:xmpp:fasten:0");
        if (fasten == null
                || fasten.findChild("retract", "urn:xmpp:message-retract:0") == null) {
            return null;
        }
        return fasten.getAttribute("id");
    }

    private static String extractStanzaId(
            Element packet, boolean isTypeGroupChat, Conversation conversation) {
        final Jid by;
        final boolean safeToExtract;
        if (isTypeGroupChat) {
            by = conversation.getJid().asBareJid();
            safeToExtract = conversation.getMucOptions().hasFeature(Namespace.STANZA_IDS);
        } else {
            Account account = conversation.getAccount();
            by = account.getJid().asBareJid();
            safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        }
        return safeToExtract ? extractStanzaId(packet, by) : null;
    }

    private static String extractStanzaId(Account account, Element packet) {
        final boolean safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        return safeToExtract ? extractStanzaId(packet, account.getJid().asBareJid()) : null;
    }

    private static String extractStanzaId(Element packet, Jid by) {
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("stanza-id")
                    && Namespace.STANZA_IDS.equals(child.getNamespace())
                    && by.equals(Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("by")))) {
                return child.getAttribute("id");
            }
        }
        return null;
    }

    @Nullable
    private static Element parseMessageAttachment(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Conversation conversation,
            final Message message,
            final Jid counterpart,
            final int status,
            final boolean isTypeGroupChat,
            final String replacementId) {
        if (conversation.getMode() != Conversation.MODE_SINGLE
                || isTypeGroupChat
                || message.isPrivateMessage()
                || replacementId != null
                || packet.findChild("reply", "urn:xmpp:reply:0") != null) {
            return null;
        }
        final Element attachTo =
                packet.findChildEnsureSingle("attach-to", Namespace.MESSAGE_ATTACHING);
        if (attachTo == null
                || !attachTo.getChildren().isEmpty()
                || attachTo.getContent() != null) {
            return null;
        }
        final String anchorId = attachTo.getAttribute("id");
        if (Strings.isNullOrEmpty(anchorId) || !anchorId.equals(anchorId.trim())) {
            return null;
        }
        if (!isAttachableMedia(message) && !isPotentialMediaCaption(message)) {
            return null;
        }
        // The anchor can arrive after this stanza (for example while OMEMO/media work is
        // completed asynchronously). Retain only the normalized relation here; the presentation
        // layer validates the anchor, sender, status and relation shape before grouping.
        return new Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId);
    }

    private static boolean isPotentialMediaCaption(final Message message) {
        final String body = message.getBody();
        return message.isTypeText()
                && !message.isFileOrImage()
                && !message.treatAsDownloadable()
                && !message.isOOb()
                && !message.isGeoUri()
                && body != null
                && !body.trim().isEmpty();
    }

    private static boolean hasMessageAttachment(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if ("attach-to".equals(payload.getName())
                    && Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAttachableMedia(final Message message) {
        if (!message.isFileOrImage() && !message.treatAsDownloadable()) {
            return false;
        }
        final String mimeType = message.getMimeType();
        return mimeType != null
                && (mimeType.startsWith("image/") || mimeType.startsWith("video/"));
    }

    private static Jid getTrueCounterpart(Element mucUserElement, Jid fallback) {
        final Element item = mucUserElement == null ? null : mucUserElement.findChild("item");
        Jid result =
                item == null ? null : Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
        return result != null ? result : fallback;
    }

    private static boolean clientMightSendHtml(Account account, Jid from) {
        String resource = from.getResource();
        if (resource == null) {
            return false;
        }
        Presence presence = account.getRoster().getContact(from).getPresences().getPresencesMap().get(resource);
        ServiceDiscoveryResult disco = presence == null ? null : presence.getServiceDiscoveryResult();
        if (disco == null) {
            return false;
        }
        return hasIdentityKnowForSendingHtml(disco.getIdentities());
    }

    private static boolean hasIdentityKnowForSendingHtml(List<ServiceDiscoveryResult.Identity> identities) {
        for (ServiceDiscoveryResult.Identity identity : identities) {
            if (identity.getName() != null) {
                if (CLIENTS_SENDING_HTML_IN_OTR.contains(identity.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean extractChatState(
            Conversation c,
            final boolean isTypeGroupChat,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        ChatState state = ChatState.parse(packet);
        if (state != null && c != null) {
            final Account account = c.getAccount();
            final Jid from = packet.getFrom();
            if (from.asBareJid().equals(account.getJid().asBareJid())) {
                c.setOutgoingChatState(state);
                if (state == ChatState.ACTIVE || state == ChatState.COMPOSING) {
                    if (c.getContact().isSelf()) {
                        return false;
                    }
                    mXmppConnectionService.markRead(c);
                    activateGracePeriod(account);
                }
                return false;
            } else {
                if (isTypeGroupChat) {
                    MucOptions.User user = c.getMucOptions().findUserByFullJid(from);
                    if (user != null) {
                        return user.setChatState(state);
                    } else {
                        return false;
                    }
                } else {
                    return c.setIncomingChatState(state);
                }
            }
        }
        return false;
    }

    private Message parseOtrChat(String body, Jid from, String id, Conversation conversation) {
        String presence;
        if (from.isBareJid()) {
            presence = "";
        } else {
            presence = from.getResource();
        }
        if (body.matches("^\\?OTRv\\d{1,2}\\?.*")) {
            conversation.endOtrIfNeeded();
        }
        if (!conversation.hasValidOtrSession()) {
            conversation.startOtrSession(presence, false);
        } else {
            String foreignPresence = conversation.getOtrSession().getSessionID().getUserID();
            if (!foreignPresence.equals(presence)) {
                conversation.endOtrIfNeeded();
                conversation.startOtrSession(presence, false);
            }
        }
        try {
            conversation.setLastReceivedOtrMessageId(id);
            Session otrSession = conversation.getOtrSession();
            body = otrSession.transformReceiving(body);
            SessionStatus status = otrSession.getSessionStatus();
            if (body == null && status == SessionStatus.ENCRYPTED) {
                mXmppConnectionService.onOtrSessionEstablished(conversation);
                return null;
            } else if (body == null && status == SessionStatus.FINISHED) {
                conversation.resetOtrSession();
                mXmppConnectionService.updateConversationUi();
                return null;
            } else if (body == null || (body.isEmpty())) {
                return null;
            }
            if (body.startsWith(CryptoHelper.FILETRANSFER)) {
                String key = body.substring(CryptoHelper.FILETRANSFER.length());
                conversation.setSymmetricKey(CryptoHelper.hexToBytes(key));
                return null;
            }
            if (clientMightSendHtml(conversation.getAccount(), from)) {
                Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": received OTR message from bad behaving client. escaping HTML…");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
                } else {
                    body = Html.fromHtml(body).toString();
                }
            }

            final OtrService otrService = conversation.getAccount().getOtrService();
            Message finishedMessage = new Message(conversation, body, Message.ENCRYPTION_OTR, Message.STATUS_RECEIVED);
            finishedMessage.setFingerprint(otrService.getFingerprint(otrSession.getRemotePublicKey()));
            conversation.setLastReceivedOtrMessageId(null);

            if (body.startsWith("?OTR")) {
                return null;
            }

            return finishedMessage;
        } catch (Exception e) {
            conversation.resetOtrSession();
            return null;
        }
    }

    private Message parseAxolotlChat(
            final Encrypted axolotlMessage,
            final Jid from,
            final Conversation conversation,
            final int status,
            final boolean checkedForDuplicates,
            final boolean postpone) {
        final AxolotlService service = conversation.getAccount().getAxolotlService();
        final XmppAxolotlMessage xmppAxolotlMessage;
        try {
            xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlMessage, from.asBareJid());
        } catch (final Exception e) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": invalid omemo message received "
                            + e.getMessage());
            return null;
        }
        if (xmppAxolotlMessage.hasPayload()) {
            final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage;
            try {
                plaintextMessage =
                        service.processReceivingPayloadMessage(xmppAxolotlMessage, postpone);
            } catch (BrokenSessionException e) {
                if (checkedForDuplicates) {
                    if (service.trustedOrPreviouslyResponded(from.asBareJid())) {
                        service.reportBrokenSessionException(e, postpone);
                        return new Message(
                                conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                "ignoring broken session exception because contact was not"
                                        + " trusted");
                        return new Message(
                                conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    }
                } else {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring broken session exception because checkForDuplicates failed");
                    return null;
                }
            } catch (NotEncryptedForThisDeviceException e) {
                return new Message(
                        conversation, "", Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE, status);
            } catch (OutdatedSenderException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
            }
            if (plaintextMessage != null) {
                Message finishedMessage =
                        new Message(
                                conversation,
                                plaintextMessage.getPlaintext(),
                                Message.ENCRYPTION_AXOLOTL,
                                status);
                finishedMessage.setFingerprint(plaintextMessage.getFingerprint());
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(finishedMessage.getConversation().getAccount())
                                + " Received Message with session fingerprint: "
                                + plaintextMessage.getFingerprint());
                return finishedMessage;
            }
        } else {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": received OMEMO key transport message");
            service.processReceivingKeyTransportMessage(xmppAxolotlMessage, postpone);
        }
        return null;
    }

    private Invite extractInvite(final Element message) {
        final Element mucUser = message.findChild("x", Namespace.MUC_USER);
        if (mucUser != null) {
            final Element invite = mucUser.findChild("invite");
            if (invite != null) {
                final String password = mucUser.findChildContent("password");
                final Jid from = Jid.Invalid.getNullForInvalid(invite.getAttributeAsJid("from"));
                final Jid to = Jid.Invalid.getNullForInvalid(invite.getAttributeAsJid("to"));
                if (to != null && from == null) {
                    Log.d(Config.LOGTAG, "do not parse outgoing mediated invite " + message);
                    return null;
                }
                final Jid room = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"));
                if (room == null) {
                    return null;
                }
                return new Invite(room, password, false, from);
            }
        }
        final Element conference = message.findChild("x", "jabber:x:conference");
        if (conference != null) {
            Jid from = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"));
            Jid room = Jid.Invalid.getNullForInvalid(conference.getAttributeAsJid("jid"));
            if (room == null) {
                return null;
            }
            return new Invite(room, conference.getAttribute("password"), true, from);
        }
        return null;
    }

    private void parseEvent(final Element event, final Jid from, final Account account) {
        final Element items = event.findChild("items");
        final String node = items == null ? null : items.getAttribute("node");
        if ("urn:xmpp:avatar:metadata".equals(node)) {
            Avatar avatar = Avatar.parseMetadata(items);
            if (avatar != null) {
                avatar.owner = from.asBareJid();
                if (mXmppConnectionService.getFileBackend().isAvatarCached(account, avatar)) {
                    if (account.getJid().asBareJid().equals(from)) {
                        if (account.setAvatar(avatar.getFilename())) {
                            mXmppConnectionService.databaseBackend.updateAccount(account);
                            mXmppConnectionService.notifyAccountAvatarHasChanged(account);
                        }
                        mXmppConnectionService.getAvatarService().clear(account);
                        mXmppConnectionService.updateConversationUi();
                        mXmppConnectionService.updateAccountUi();
                    } else {
                        final Contact contact = account.getRoster().getContact(from);
                        if (contact.setAvatar(avatar)) {
                            mXmppConnectionService.syncRoster(account);
                            mXmppConnectionService.getAvatarService().clear(contact);
                            mXmppConnectionService.updateConversationUi();
                            mXmppConnectionService.updateRosterUi();
                        }
                    }
                } else if (mXmppConnectionService.isDataSaverDisabled()) {
                    mXmppConnectionService.fetchAvatar(account, avatar);
                }
            }
        } else if (Namespace.NICK.equals(node)) {
            final Element i = items.findChild("item");
            final String nick = i == null ? null : i.findChildContent("nick", Namespace.NICK);
            if (nick != null) {
                setNick(account, from, nick);
            }
        } else if (AxolotlService.PEP_DEVICE_LIST.equals(node)) {
            Element item = items.findChild("item");
            final Set<Integer> deviceIds = IqParser.deviceIds(item);
            Log.d(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account)
                            + "Received PEP device list "
                            + deviceIds
                            + " update from "
                            + from
                            + ", processing... ");
            final AxolotlService axolotlService = account.getAxolotlService();
            axolotlService.registerDevices(from, deviceIds);
        } else if (Namespace.BOOKMARKS.equals(node) && account.getJid().asBareJid().equals(from)) {
            final var connection = account.getXmppConnection();
            if (connection.getFeatures().bookmarksConversion()) {
                if (connection.getFeatures().bookmarks2()) {
                    Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": received storage:bookmark notification even though we"
                                    + " opted into bookmarks:1");
                }
                final Element i = items.findChild("item");
                final Element storage =
                        i == null ? null : i.findChild("storage", Namespace.BOOKMARKS);
                final Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                mXmppConnectionService.processBookmarksInitial(account, bookmarks, true);
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": processing bookmark PEP event");
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring bookmark PEP event because bookmark conversion was"
                                + " not detected");
            }
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            final Element retract = items.findChild("retract");
            if (item != null) {
                final Bookmark bookmark = Bookmark.parseFromItem(item, account);
                if (bookmark != null) {
                    account.putBookmark(bookmark);
                    mXmppConnectionService.processModifiedBookmark(bookmark);
                    mXmppConnectionService.updateConversationUi();
                }
            }
            if (retract != null) {
                final Jid id = Jid.Invalid.getNullForInvalid(retract.getAttributeAsJid("id"));
                if (id != null) {
                    account.removeBookmark(id);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid() + ": deleted bookmark for " + id);
                    mXmppConnectionService.processDeletedBookmark(account, id);
                    mXmppConnectionService.updateConversationUi();
                }
            }
        } else if (Config.MESSAGE_DISPLAYED_SYNCHRONIZATION
                && Namespace.MDS_DISPLAYED.equals(node)
                && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            mXmppConnectionService.processMdsItem(account, item);
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + " received pubsub notification for node="
                            + node);
        }
    }

    private void parseDeleteEvent(final Element event, final Jid from, final Account account) {
        final Element delete = event.findChild("delete");
        final String node = delete == null ? null : delete.getAttribute("node");
        if (Namespace.NICK.equals(node)) {
            Log.d(Config.LOGTAG, "parsing nick delete event from " + from);
            setNick(account, from, null);
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted bookmarks node");
            deleteAllBookmarks(account);
        } else if (Namespace.AVATAR_METADATA.equals(node)
                && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted avatar metadata node");
        }
    }

    private void parsePurgeEvent(final Element event, final Jid from, final Account account) {
        final Element purge = event.findChild("purge");
        final String node = purge == null ? null : purge.getAttribute("node");
        if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": purged bookmarks");
            deleteAllBookmarks(account);
        }
    }

    private void deleteAllBookmarks(final Account account) {
        final var previous = account.getBookmarkedJids();
        account.setBookmarks(Collections.emptyMap());
        mXmppConnectionService.processDeletedBookmarks(account, previous);
    }

    private void setNick(final Account account, final Jid user, final String nick) {
        if (user.asBareJid().equals(account.getJid().asBareJid())) {
            account.setDisplayName(nick);
            if (QuickConversationsService.isQuicksy()) {
                mXmppConnectionService.getAvatarService().clear(account);
            }
            mXmppConnectionService.checkMucRequiresRename();
        } else {
            Contact contact = account.getRoster().getContact(user);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateAccountUi();
    }

    private boolean handleErrorMessage(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        if (packet.getType() == im.conversations.android.xmpp.model.stanza.Message.Type.ERROR) {
            if (packet.fromServer(account)) {
                final var forwarded =
                        getForwardedMessagePacket(packet, "received", Namespace.CARBONS);
                if (forwarded != null) {
                    return handleErrorMessage(account, forwarded.first);
                }
            }
            final Jid from = packet.getFrom();
            final String id = packet.getId();
            if (from != null && id != null) {
                final Message message = mXmppConnectionService.markMessage(account,
                        from.asBareJid(),
                        packet.getId(),
                        Message.STATUS_SEND_FAILED,
                        extractErrorMessage(packet));
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    final String sessionId =
                            id.substring(
                                    JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                    mXmppConnectionService
                            .getJingleConnectionManager()
                            .updateProposedSessionDiscovered(
                                    account,
                                    from,
                                    sessionId,
                                    JingleConnectionManager.DeviceDiscoveryState.FAILED);
                    return true;
                }
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX)) {
                    final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX.length());
                    final String errorMessage = extractErrorMessage(packet);
                    mXmppConnectionService.getJingleConnectionManager().failProceed(account, from, sessionId, errorMessage);
                    return true;
                }
                mXmppConnectionService.markMessage(
                        account,
                        from.asBareJid(),
                        id,
                        Message.STATUS_SEND_FAILED,
                        extractErrorMessage(packet));
                final Element error = packet.findChild("error");
                final boolean pingWorthyError =
                        error != null
                                && (error.hasChild("not-acceptable")
                                        || error.hasChild("remote-server-timeout")
                                        || error.hasChild("remote-server-not-found"));
                if (pingWorthyError) {
                    Conversation conversation = mXmppConnectionService.find(account, from, null);
                    if (conversation != null && conversation.getMode() == Conversational.MODE_MULTI) {
                        if (conversation.getMucOptions().online()) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": received ping worthy error for seemingly online"
                                            + " muc at "
                                            + from);
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                        }
                    }
                }

                if (message != null) {
                    if (message.getEncryption() == Message.ENCRYPTION_OTR) {
                        Conversation conversation = (Conversation) message.getConversation();
                        conversation.endOtrIfNeeded();
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void accept(final im.conversations.android.xmpp.model.stanza.Message original) {
        if (handleErrorMessage(account, original)) {
            return;
        }
        final im.conversations.android.xmpp.model.stanza.Message packet;
        Long timestamp = null;
        final boolean isForwarded;
        boolean isCarbon = false;
        String serverMsgId = null;
        final Element fin =
                original.findChild("fin", MessageArchiveService.Version.MAM_0.namespace);
        if (fin != null) {
            mXmppConnectionService
                    .getMessageArchiveService()
                    .processFinLegacy(fin, original.getFrom());
            return;
        }
        final Element result = MessageArchiveService.Version.findResult(original);
        final String queryId = result == null ? null : result.getAttribute("queryid");
        final MessageArchiveService.Query query =
                queryId == null
                        ? null
                        : mXmppConnectionService.getMessageArchiveService().findQuery(queryId);
        if (result != null && query == null) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": ignoring unsolicited or stale MAM result"
                            + (queryId == null ? " without queryid" : " for queryid=" + queryId));
            return;
        }
        final boolean offlineMessagesRetrieved =
                account.getXmppConnection().isOfflineMessagesRetrieved();
        if (query != null && query.validFrom(original.getFrom())) {
            final var f = getForwardedMessagePacket(original, "result", query.version.namespace);
            if (f == null) {
                return;
            }
            timestamp = f.second;
            packet = f.first;
            isForwarded = true;
            serverMsgId = result.getAttribute("id");

            query.incrementMessageCount();
            if (handleErrorMessage(account, packet)) {
                return;
            }
        } else if (query != null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": received mam result with invalid from ("
                            + original.getFrom()
                            + ") or queryId ("
                            + queryId
                            + ")");
            return;
        } else if (original.fromServer(account)
                && original.getType()
                        != im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT) {
            Pair<im.conversations.android.xmpp.model.stanza.Message, Long> f;
            f = getForwardedMessagePacket(original, Received.class);
            f = f == null ? getForwardedMessagePacket(original, Sent.class) : f;
            packet = f != null ? f.first : original;
            if (handleErrorMessage(account, packet)) {
                return;
            }
            timestamp = f != null ? f.second : null;
            isCarbon = f != null;
            isForwarded = isCarbon;
        } else {
            packet = original;
            isForwarded = false;
        }

        if (timestamp == null) {
            timestamp =
                    AbstractParser.parseTimestamp(original, AbstractParser.parseTimestamp(packet));
        }
        if (query != null) {
            query.recordResultTimestamp(timestamp);
        }

        LocalizedContent body = packet.getBody();
        final Element mucUserElement = packet.findChild("x", Namespace.MUC_USER);
        final boolean isTypeGroupChat =
                packet.getType()
                        == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
        final String pgpEncrypted = packet.findChildContent("x", "jabber:x:encrypted");

        final Element oob = packet.findChild("x", Namespace.OOB);
        final String oobUrl = oob != null ? oob.findChildContent("url") : null;
        final var replace = packet.getExtension(Replace.class);
        final boolean hasMessageCorrection = replace != null;
        var replacementId = replace == null ? null : replace.getId();
        if (replacementId == null) {
            replacementId = applyLegacyRetractionFallback(packet);
            if (replacementId != null) {
                // Legacy XEP-0424 fallback text is for clients that do not understand retraction.
                // Do not append another <body/> to the stanza: setBody("") would create a
                // duplicate default-language body, while the LocalizedContent captured above
                // would still contain the fallback text.
                body =
                        new LocalizedContent(
                                "",
                                body == null ? LocalizedContent.STREAM_LANGUAGE : body.language,
                                body == null ? 1 : body.count);
            }
        }
        final var axolotlEncrypted = packet.getOnlyExtension(Encrypted.class);
        int status;
        final Jid counterpart;
        final Jid to = packet.getTo();
        final Jid from = packet.getFrom();
        final Element originId = packet.findChild("origin-id", Namespace.STANZA_IDS);
        final String remoteMsgId;
        if (originId != null && originId.getAttribute("id") != null) {
            remoteMsgId = originId.getAttribute("id");
        } else {
            remoteMsgId = packet.getId();
        }
        boolean notify = false;

        if (from == null || !Jid.Invalid.isValid(from) || !Jid.Invalid.isValid(to)) {
            Log.e(Config.LOGTAG, "encountered invalid message from='" + from + "' to='" + to + "'");
            return;
        }
        final MucModerationProtocol.ModerationTombstone archivedModerationTombstone =
                query != null && query.muc() && isTypeGroupChat
                        ? MucModerationProtocol.archivedTombstone(
                                packet, from, query.getWith())
                        : null;
        // XEP-0425 events are authoritative only when emitted by the bare MUC service.
        // Always consume their fallback body, including forged or unresolved events.
        final Element moderatedRetraction = MucModerationProtocol.moderatedRetraction(packet);
        if (moderatedRetraction != null) {
            if (isTypeGroupChat && from.isBareJid() && (query == null || query.muc())) {
                final Conversation room = mXmppConnectionService.find(account, from, null);
                final MucModerationProtocol.ModerationEvent event =
                        room == null || room.getMode() != Conversation.MODE_MULTI
                                ? null
                                : MucModerationProtocol.authoritativeLiveEvent(
                                        packet, from, room.getJid());
                if (event != null) {
                    mXmppConnectionService.applyMessageModeration(
                            room, event.targetId, event.by, event.reason, timestamp);
                }
            } else {
                Log.d(Config.LOGTAG, "ignoring non-service MUC moderation event");
            }
            return;
        }

        if (MessageRetractionProtocol.isPlainRetractionMessage(packet)) {
            // Always consume fallback, including malformed or rejected events.
            // The MAM wrapper has already been validated above.
            final boolean trustedMucTransport =
                    MessageRetractionProtocol.isTrustedMucDelivery(
                            isTypeGroupChat,
                            from,
                            query != null && query.muc()
                                    ? query.getWith() : null,
                            isForwarded,
                            query != null && query.muc());

            if (trustedMucTransport) {
                final Conversation room =
                        mXmppConnectionService.find(
                                account, from.asBareJid(), null);

                if (room != null
                        && room.getMode() == Conversation.MODE_MULTI
                        && room.getJid().asBareJid()
                                .equals(from.asBareJid())) {
                    final OccupantId occupant =
                            packet.getExtension(OccupantId.class);
                    final String occupantId = occupant == null
                            ? null
                            : room.getMucOptions().acceptedOccupantId(
                                    occupant.getId());

                    final String targetId =
                            MessageRetractionProtocol
                                    .plainRetractionTarget(packet);

                    // UNVERIFIED only. Never scrub or hide the target here.
                    final boolean recorded =
                            mXmppConnectionService.databaseBackend
                                    .recordUnverifiedMucRetraction(
                                            room,
                                            packet.getId(),
                                            targetId,
                                            from,
                                            occupantId,
                                            System.currentTimeMillis());

                    if (recorded) {
                        // Only a verified author retraction may retire content.
                        if (mXmppConnectionService.databaseBackend
                                .verifyUnverifiedMucRetraction(
                                        room, packet.getId())) {
                            mXmppConnectionService.applyVerifiedMucRetraction(
                                    room, packet.getId());
                        }
                    }
                }
            }
            return;
        }

        if (query != null && !query.muc() && isTypeGroupChat) {
            Log.e(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": received groupchat ("
                            + from
                            + ") message on regular MAM request. skipping");
            return;
        }
        final Jid mucTrueCounterPart;
        final OccupantId occupant;
        if (isTypeGroupChat) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid(), null);
            final Jid mucTrueCounterPartByPresence;
            if (conversation != null) {
                final var mucOptions = conversation.getMucOptions();
                final OccupantId candidateOccupant = packet.getExtension(OccupantId.class);
                occupant =
                        candidateOccupant != null
                                        && mucOptions.acceptedOccupantId(candidateOccupant.getId())
                                                != null
                                ? candidateOccupant
                                : null;
                final var user =
                        occupant == null ? null : mucOptions.findUserByOccupantId(occupant.getId());
                mucTrueCounterPartByPresence = user == null ? null : user.getRealJid();
            } else {
                occupant = null;
                mucTrueCounterPartByPresence = null;
            }
            mucTrueCounterPart =
                    getTrueCounterpart(
                            (query != null && query.safeToExtractTrueCounterpart())
                                    ? mucUserElement
                                    : null,
                            mucTrueCounterPartByPresence);
        } else if (mucUserElement != null) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid(), null);
            if (conversation != null) {
                final var mucOptions = conversation.getMucOptions();
                final OccupantId candidateOccupant = packet.getExtension(OccupantId.class);
                occupant =
                        candidateOccupant != null
                                        && mucOptions.acceptedOccupantId(candidateOccupant.getId())
                                                != null
                                ? candidateOccupant
                                : null;
            } else {
                occupant = null;
            }
            mucTrueCounterPart = null;
        } else {
            mucTrueCounterPart = null;
            occupant = null;
        }
        boolean isProperlyAddressed = (to != null) && (!to.isBareJid() || account.countPresences() == 0);
        boolean isMucStatusMessage =
                Jid.Invalid.hasValidFrom(packet)
                        && from.isBareJid()
                        && mucUserElement != null
                        && mucUserElement.hasChild("status");
        boolean selfAddressed;
        if (packet.fromAccount(account)) {
            status = Message.STATUS_SEND;
            selfAddressed = to == null || account.getJid().asBareJid().equals(to.asBareJid());
            if (selfAddressed) {
                counterpart = from;
            } else {
                counterpart = to;
            }
        } else {
            status = Message.STATUS_RECEIVED;
            counterpart = from;
            selfAddressed = false;
        }

        final Invite invite = extractInvite(packet);
        if (invite != null) {
            if (!shouldExecuteInvite(isForwarded, query != null)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring historical/forwarded MUC invite to "
                                + invite.jid);
                return;
            }
            if (invite.jid.asBareJid().equals(account.getJid().asBareJid())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignore invite to "
                                + invite.jid
                                + " because it matches account");
            } else if (isTypeGroupChat) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring invite to "
                                + invite.jid
                                + " because it was received as group chat");
            } else if (invite.direct
                    && (mucUserElement != null
                            || invite.inviter == null
                            || mXmppConnectionService.isMuc(account, invite.inviter))) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring direct invite to "
                                + invite.jid
                                + " because it was received in MUC");
            } else {
                invite.execute(account);
                return;
            }
        }

        Jid nextCounterpart = null;

        final boolean conversationIsProbablyMuc = isTypeGroupChat || mucUserElement != null || account.getXmppConnection().getMucServersWithholdAccount().contains(counterpart.getDomain().toString());

        final boolean isOTR = body != null && body.content.startsWith("?OTR") && Config.supportOtr();
        final boolean correctOTR = !isForwarded && !isTypeGroupChat && isProperlyAddressed;

        if ((conversationIsProbablyMuc && !isTypeGroupChat) || (!Strings.isNullOrEmpty(counterpart.getResource()) && isOTR && correctOTR)) {
            nextCounterpart = counterpart;
        }

        final Element reactions = packet.findChild("reactions", "urn:xmpp:reactions:0");
        /*if (body == null) {
            if (reactions != null && reactions.getAttribute("id") != null) {
                final Conversation conversation = mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart);
                if (conversation != null) {
                    final Message reactionTo = conversation.findMessageWithRemoteIdAndCounterpart(reactions.getAttribute("id"), null);
                    if (reactionTo != null) {
                        String bodyS = reactionTo.reply().getBody();
                        for (Element el : reactions.getChildren()) {
                            if (el.getName().equals("reaction") && el.getNamespace().equals("urn:xmpp:reactions:0")) {
                                bodyS += el.getContent();
                            }
                        }
                        body = new LocalizedContent(bodyS, "en", 1);
                        final Message previousReaction = conversation.findMessageReactingTo(reactions.getAttribute("id"), counterpart);
                        if (previousReaction != null) replacementId = previousReaction.replyId();
                    }
                }
            }
        } */

        if (nextCounterpart == null && conversationIsProbablyMuc && mXmppConnectionService.checkIsArchived(account, counterpart.asBareJid(), nextCounterpart)) {
            return;
        }

        if ((body != null || pgpEncrypted != null || (axolotlEncrypted != null && axolotlEncrypted.hasChild("payload")) || oobUrl != null || archivedModerationTombstone != null) && !isMucStatusMessage) {
            final Conversation conversation = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), null, conversationIsProbablyMuc, nextCounterpart != null, false, nextCounterpart);
            final boolean conversationMultiMode = conversation.getMode() == Conversation.MODE_MULTI;

            // For a validated MUC MAM query, the wrapper <result id> is archive-service
            // controlled. ejabberd (and the XEP-0425 tombstone example) may use the room stanza
            // id here even when the forwarded inner message no longer carries <stanza-id/>.
            // If that id already has a durable moderation marker, the original archived payload
            // must never be re-published.
            if (query != null
                    && query.muc()
                    && shouldSuppressTrustedMucMamReplay(
                            serverMsgId,
                            serverMsgId != null
                                    && mXmppConnectionService.databaseBackend.getMessageModeration(
                                                    conversation, serverMsgId)
                                            != null)) {
                query.incrementActualMessageCount();
                Log.d(
                        Config.LOGTAG,
                        "suppressing trusted MUC MAM replay by durable moderation result id");
                return;
            }

            // A trusted MUC archive result must not resurrect an
            // original already covered by a verified retraction.
            if (query != null
                    && query.muc()
                    && conversationMultiMode
                    && serverMsgId != null
                    && mXmppConnectionService.databaseBackend
                            .hasVerifiedMucRetractionForRoomStanzaId(
                                    conversation, serverMsgId)) {
                query.incrementActualMessageCount();
                Log.d(Config.LOGTAG,
                        "suppressing XEP-0424 MAM replay");
                return;
            }

            if (serverMsgId == null) {
                serverMsgId = extractStanzaId(packet, isTypeGroupChat, conversation);
            }

            if (selfAddressed) {
                // don’t store serverMsgId on reflections for edits
                final var reflectedServerMsgId =
                        Strings.isNullOrEmpty(replacementId) ? serverMsgId : null;
                if (mXmppConnectionService.markMessage(
                        conversation,
                        remoteMsgId,
                        Message.STATUS_SEND_RECEIVED,
                        reflectedServerMsgId)) {
                    return;
                }
                status = Message.STATUS_RECEIVED;
                if (remoteMsgId != null
                        && conversation.findMessageWithRemoteId(remoteMsgId, counterpart) != null) {
                    return;
                }
            }

            if (isTypeGroupChat) {
                if (conversation.getMucOptions().isSelf(counterpart)) {
                    status = Message.STATUS_SEND_RECEIVED;
                    isCarbon = true; // not really carbon but received from another resource
                    final String reflectedRoomStanzaId =
                            MucModerationProtocol.roomStanzaId(packet, conversation.getJid());
                    if (remoteMsgId != null && reflectedRoomStanzaId != null) {
                        final Message reflected =
                                conversation.findSentMessageWithUuid(remoteMsgId);
                        if (reflected != null && reflected.getRoomStanzaId() == null) {
                            // Persist the canonical moderation identity before markMessage() takes
                            // the self-echo early return.
                            reflected.setRoomStanzaId(reflectedRoomStanzaId);
                        }
                    }
                    // don’t store serverMsgId on reflections for edits
                    final var reflectedServerMsgId =
                            Strings.isNullOrEmpty(replacementId) ? serverMsgId : null;
                    if (mXmppConnectionService.markMessage(
                            conversation, remoteMsgId, status, reflectedServerMsgId, body)) {
                        return;
                    } else if (remoteMsgId == null || Config.IGNORE_ID_REWRITE_IN_MUC) {
                        if (body != null) {
                            Message message = conversation.findSentMessageWithBody(body.content);
                            if (message != null) {
                                if (message.getRoomStanzaId() == null
                                        && reflectedRoomStanzaId != null) {
                                    message.setRoomStanzaId(reflectedRoomStanzaId);
                                }
                                mXmppConnectionService.markMessage(message, status);
                                return;
                            }
                        }
                    }
                } else {
                    status = Message.STATUS_RECEIVED;
                }
            }
            final Message message;
            if (archivedModerationTombstone != null) {
                message = new Message(conversation, "", Message.ENCRYPTION_NONE, status);
            } else if (isOTR) {
                if (correctOTR && !conversationMultiMode) {
                    message = parseOtrChat(body.content, from, remoteMsgId, conversation);
                    if (message == null) {
                        return;
                    }
                } else {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring OTR message from " + from + " isForwarded=" + Boolean.toString(isForwarded) + ", isProperlyAddressed=" + Boolean.valueOf(isProperlyAddressed));
                    return;
                }
            } else if (pgpEncrypted != null) {
                message = new Message(conversation, pgpEncrypted, Message.ENCRYPTION_PGP, status);
            } else if (axolotlEncrypted != null && Config.supportOmemo()) {
                Jid origin;
                Set<Jid> fallbacksBySourceId = Collections.emptySet();
                if (conversationMultiMode) {
                    final Jid fallback =
                            conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        try {
                            fallbacksBySourceId =
                                    account.getAxolotlService()
                                            .findCounterpartsBySourceId(
                                                    XmppAxolotlMessage.parseSourceId(
                                                            axolotlEncrypted));
                        } catch (IllegalArgumentException e) {
                            // ignoring
                        }
                    }
                    if (origin == null && fallbacksBySourceId.isEmpty()) {
                        Log.d(
                                Config.LOGTAG,
                                "axolotl message in anonymous conference received and no possible"
                                        + " fallbacks");
                        return;
                    }
                } else {
                    fallbacksBySourceId = Collections.emptySet();
                    origin = from;
                }

                final boolean liveMessage =
                        query == null && !isTypeGroupChat && mucUserElement == null;
                final boolean checkedForDuplicates =
                        liveMessage
                                || (serverMsgId != null
                                        && remoteMsgId != null
                                        && !conversation.possibleDuplicate(
                                                serverMsgId, remoteMsgId));

                if (origin != null) {
                    message =
                            parseAxolotlChat(
                                    axolotlEncrypted,
                                    origin,
                                    conversation,
                                    status,
                                    checkedForDuplicates,
                                    query != null);
                } else {
                    Message trial = null;
                    for (Jid fallback : fallbacksBySourceId) {
                        trial =
                                parseAxolotlChat(
                                        axolotlEncrypted,
                                        fallback,
                                        conversation,
                                        status,
                                        checkedForDuplicates && fallbacksBySourceId.size() == 1,
                                        query != null);
                        if (trial != null) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": decoded muc message using fallback");
                            origin = fallback;
                            break;
                        }
                    }
                    message = trial;
                }
                if (message == null) {
                    if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart), isTypeGroupChat, packet)) {
                        mXmppConnectionService.updateConversationUi();
                    }
                    if (query != null && status == Message.STATUS_SEND && remoteMsgId != null) {
                        Message previouslySent = conversation.findSentMessageWithUuid(remoteMsgId);
                        if (previouslySent != null
                                && previouslySent.getServerMsgId() == null
                                && serverMsgId != null) {
                            previouslySent.setServerMsgId(serverMsgId);
                            mXmppConnectionService.databaseBackend.updateMessage(
                                    previouslySent, false);
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": encountered previously sent OMEMO message without"
                                            + " serverId. updating...");
                        }
                    }
                    return;
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin);
                }
            } else if (body == null && oobUrl != null) {
                message = new Message(conversation, oobUrl, Message.ENCRYPTION_NONE, status);
                message.setOob(true);
            } else {
                message = new Message(conversation, body.content, Message.ENCRYPTION_NONE, status);
                if (body.count > 1) {
                    message.setBodyLanguage(body.language);
                }
            }

            message.setCounterpart(counterpart);
            message.setRemoteMsgId(remoteMsgId);
            message.setServerMsgId(serverMsgId);
            if (conversationMultiMode && isTypeGroupChat) {
                // A room stanza ID is useful only while it remains unambiguous inside this
                // conversation. Do not let a forwarded client-supplied collision become a
                // moderation identity even if the server preserved a matching by=room attribute.
                final String candidateRoomStanzaId =
                        MucModerationProtocol.roomStanzaId(packet, conversation.getJid());

                if (candidateRoomStanzaId != null
                        && mXmppConnectionService.databaseBackend
                                .hasVerifiedMucRetractionForRoomStanzaId(
                                        conversation, candidateRoomStanzaId)) {
                    message.markRetracted();
                    if (query != null) {
                        query.incrementActualMessageCount();
                    }
                    Log.d(Config.LOGTAG,
                            "suppressing durably retracted MUC original");
                    return;
                }

                final var knownModeration =
                        candidateRoomStanzaId == null
                                ? null
                                : mXmppConnectionService.databaseBackend.getMessageModeration(
                                        conversation, candidateRoomStanzaId);
                final boolean roomStanzaIdClaimed =
                        candidateRoomStanzaId != null
                                && knownModeration == null
                                && (conversation.findMessageWithRoomStanzaId(candidateRoomStanzaId)
                                                != null
                                        || mXmppConnectionService.databaseBackend
                                                .hasMessageWithRoomStanzaId(
                                                        conversation, candidateRoomStanzaId));

                switch (classifyRoomStanzaIdentity(
                        candidateRoomStanzaId,
                        knownModeration != null,
                        roomStanzaIdClaimed)) {
                    case SUPPRESS_MODERATED_REPLAY:
                        // A durable moderation marker is an immutable local tombstone. MAM can
                        // legitimately replay the original stanza after the live moderation event;
                        // do not let the collision guard strip its identity and resurrect content.
                        // Scrub transient plaintext now and consume the stanza without persisting a
                        // second row for the same room-issued ID.
                        message.setRoomStanzaId(candidateRoomStanzaId);
                        message.markModerated(
                                knownModeration.by,
                                knownModeration.reason,
                                knownModeration.stamp);
                        if (query != null) {
                            query.incrementActualMessageCount();
                        }
                        Log.d(
                                Config.LOGTAG,
                                "suppressing replay of durably moderated MUC message");
                        return;
                    case ACCEPT:
                        message.setRoomStanzaId(candidateRoomStanzaId);
                        break;
                    case REJECT_COLLISION:
                        Log.w(
                                Config.LOGTAG,
                                "ignoring duplicate MUC room stanza-id in conversation");
                        break;
                    case NONE:
                    default:
                        break;
                }
            }
            message.setCarbon(isCarbon);
            message.setTime(timestamp);
            if (archivedModerationTombstone != null) {
                long moderatedAt = timestamp;
                if (archivedModerationTombstone.stamp != null) {
                    try {
                        moderatedAt =
                                AbstractParser.parseTimestamp(
                                        archivedModerationTombstone.stamp);
                    } catch (java.text.ParseException ignored) {
                        // Preserve archive timestamp if the optional stamp is invalid.
                    }
                }
                message.markModerated(
                        archivedModerationTombstone.by,
                        archivedModerationTombstone.reason,
                        moderatedAt);
            }
            if (body != null && body.content != null && body.content.equals(oobUrl)) {
                message.setOob(true);
            }
            message.markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
            final Element attachment =
                    parseMessageAttachment(
                            packet,
                            conversation,
                            message,
                            counterpart,
                            status,
                            isTypeGroupChat,
                            replacementId);
            if (attachment != null) {
                message.addPayload(attachment);
            }
            if (reactions != null) message.addPayload(reactions);
            for (Element el : packet.getChildren()) {
                String name = el.getName();
                String ns = el.getNamespace();
                if (
                    ("reply".equals(name) && "urn:xmpp:reply:0".equals(ns)) ||
                    ("fallback".equals(name) && "urn:xmpp:fallback:0".equals(ns))
                ) {
                    message.addPayload(el);
                } else if ("unstyled".equals(name) && Namespace.MESSAGE_STYLING.equals(ns)) {
                    message.setStylingDisabled(true);
                } else if ("markup".equals(name) && Namespace.MESSAGE_MARKUP.equals(ns)) {
                    message.setMessageMarkup(el);
                }
            }

            if (conversationMultiMode) {
                final var mucOptions = conversation.getMucOptions();
                if (occupant != null) {
                    message.setOccupantId(occupant.getId());
                }
                final Jid fallback = mucOptions.getTrueCounterpart(counterpart);
                Jid trueCounterpart;
                if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                    trueCounterpart = message.getTrueCounterpart();
                } else if (query != null && query.safeToExtractTrueCounterpart()) {
                    trueCounterpart = getTrueCounterpart(mucUserElement, fallback);
                } else {
                    trueCounterpart = fallback;
                }
                if (trueCounterpart != null && isTypeGroupChat) {
                    if (trueCounterpart.asBareJid().equals(account.getJid().asBareJid())) {
                        status =
                                isTypeGroupChat
                                        ? Message.STATUS_SEND_RECEIVED
                                        : Message.STATUS_SEND;
                    } else {
                        status = Message.STATUS_RECEIVED;
                        message.setCarbon(false);
                    }
                }
                message.setStatus(status);
                message.setTrueCounterpart(trueCounterpart);
                message.setMucUser(mucOptions.resolveUser(message));
                if (!isTypeGroupChat) {
                    message.setType(Message.TYPE_PRIVATE);
                }
            } else {
                updateLastseen(account, from);
            }

            if (archivedModerationTombstone != null) {
                // Archived tombstones must not retain reply, media or fallback payloads.
                message.markModerated(
                        message.getModeratedBy(),
                        message.getModerationReason(),
                        message.getModeratedAt());
            }

            if (replacementId != null && mXmppConnectionService.allowMessageCorrection()) {
                Message replacedMessage =
                        conversation.findMessageWithRemoteIdAndCounterpart(
                                replacementId,
                                counterpart,
                                message.getStatus() == Message.STATUS_RECEIVED,
                                message.isCarbon());
                if (replacedMessage == null) {
                    // Archive corrections must not depend on the current in-memory timeline page.
                    // This is an exact conversation-scoped identity lookup, never a body/time scan.
                    final Message archived =
                            mXmppConnectionService.databaseBackend.getMessageWithRemoteMsgId(
                                    conversation, replacementId);
                    final boolean receivedCorrection =
                            message.getStatus() == Message.STATUS_RECEIVED;
                    if (archived != null
                            && archived.getCounterpart() != null
                            && archived.getCounterpart().equals(counterpart)
                            && ((archived.getStatus() == Message.STATUS_RECEIVED)
                                    == receivedCorrection)
                            && (receivedCorrection
                                    || archived.isCarbon() == message.isCarbon())
                            && replacementId.equals(archived.getRemoteMsgId())
                            && !archived.isFileOrImage()
                            && !archived.treatAsDownloadable()) {
                        replacedMessage = archived;
                        mXmppConnectionService.databaseBackend.loadSecureMessagePayloadModes(
                                account.getUuid(), List.of(replacedMessage));
                    }
                }
                if (replacedMessage != null) {
                    final boolean fingerprintsMatch =
                            replacedMessage.getFingerprint() == null
                                    || replacedMessage
                                            .getFingerprint()
                                            .equals(message.getFingerprint());
                    final boolean trueCountersMatch =
                            replacedMessage.getTrueCounterpart() != null
                                    && message.getTrueCounterpart() != null
                                    && replacedMessage
                                            .getTrueCounterpart()
                                            .asBareJid()
                                            .equals(message.getTrueCounterpart().asBareJid());
                    final boolean occupantIdMatch =
                            replacedMessage.getOccupantId() != null
                                    && replacedMessage
                                            .getOccupantId()
                                            .equals(message.getOccupantId());
                    final boolean mucUserMatches =
                            query == null
                                    && replacedMessage.sameMucUser(
                                            message); // can not be checked when using mam
                    final boolean duplicate = conversation.hasDuplicateMessage(message);
                    if (fingerprintsMatch
                            && (trueCountersMatch
                                    || occupantIdMatch
                                    || !conversationMultiMode
                                    || mucUserMatches)
                            && !duplicate) {
                        synchronized (replacedMessage) {
                            final String uuid = replacedMessage.getUuid();
                            final boolean protectedReplacement =
                                    replacedMessage.hasProtectedTextPayload();
                            if (!protectedReplacement) {
                                replacedMessage.setUuid(UUID.randomUUID().toString());
                            }
                            replacedMessage.setBody(message.getBody());
                            replacedMessage.setStylingDisabled(message.isStylingDisabled());
                            replacedMessage.setMessageMarkup(message.getMessageMarkup());
                            // we store the IDs of the replacing message. This is essentially unused
                            // today (only the fact that there are _some_ edits causes the edit icon
                            // to appear)
                            replacedMessage.putEdited(
                                    message.getRemoteMsgId(), message.getServerMsgId());

                            // we used to call
                            // `replacedMessage.setServerMsgId(message.getServerMsgId());` so during
                            // catchup we could start from the edit; not the original message
                            // however this caused problems for things like reactions that refer to
                            // the serverMsgId

                            replacedMessage.setEncryption(message.getEncryption());
                            if (replacedMessage.getStatus() == Message.STATUS_RECEIVED) {
                                replacedMessage.markUnread();
                            }
                            extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart), isTypeGroupChat, packet);
                            if (protectedReplacement) {
                                if (!mXmppConnectionService.replaceIncomingProtectedMessage(
                                        replacedMessage, uuid)) {
                                    replacedMessage.clearVerifiedProtectedBody();
                                    notify = false;
                                }
                            } else {
                                mXmppConnectionService.updateMessage(replacedMessage, uuid);
                            }
                            if (mXmppConnectionService.confirmMessages()
                                    && replacedMessage.getStatus() == Message.STATUS_RECEIVED
                                    && (replacedMessage.trusted()
                                            || replacedMessage
                                                    .isPrivateMessage()) // TODO do we really want
                                    // to send receipts for all
                                    // PMs?
                                    && remoteMsgId != null
                                    && !selfAddressed
                                    && !isTypeGroupChat) {
                                processMessageReceipts(account, packet, remoteMsgId, query);
                            }
                        }
                        mXmppConnectionService.getNotificationService().updateNotification();
                        return;
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": received message correction but verification didn't"
                                        + " check out");
                    }
                }
            }

            if (hasMessageCorrection) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring unresolved or invalid message correction");
                return;
            }

            long deletionDate = mXmppConnectionService.getAutomaticMessageDeletionDate();
            if (deletionDate != 0 && message.getTimeSent() < deletionDate) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": skipping message from "
                                + message.getCounterpart().toString()
                                + " because it was sent prior to our deletion date");
                return;
            }

            boolean checkForDuplicates =
                    (isTypeGroupChat
                                    && (packet.hasChild("delay", "urn:xmpp:delay")
                                            || message.getRemoteMsgId() != null
                                            || message.getServerMsgId() != null))
                            || message.isPrivateMessage()
                            || message.getServerMsgId() != null
                            || (query == null
                                    && mXmppConnectionService
                                            .getMessageArchiveService()
                                            .isCatchupInProgress(conversation));
            if (message.getRoomStanzaId() != null) {
                final var moderation = mXmppConnectionService.databaseBackend.getMessageModeration(
                        conversation, message.getRoomStanzaId());
                if (moderation != null) {
                    message.markModerated(moderation.by, moderation.reason, moderation.stamp);
                }
            }
            if (checkForDuplicates) {
                Message duplicate = conversation.findDuplicateMessage(message);
                final boolean historicalMucReplay =
                        isTypeGroupChat
                                && (query != null
                                        || packet.hasChild("delay", "urn:xmpp:delay"));
                if (duplicate == null && historicalMucReplay) {
                    if (message.getServerMsgId() != null) {
                        duplicate =
                                mXmppConnectionService.databaseBackend.getMessageWithServerMsgId(
                                        conversation, message.getServerMsgId());
                    }
                    if (duplicate == null && message.getRemoteMsgId() != null) {
                        final Message archivedCandidate =
                                mXmppConnectionService.databaseBackend.getMessageWithRemoteMsgId(
                                        conversation, message.getRemoteMsgId());
                        if (archivedCandidate != null
                                && archivedCandidate.getCounterpart() != null
                                && message.getCounterpart() != null
                                && archivedCandidate
                                        .getCounterpart()
                                        .equals(message.getCounterpart())) {
                            duplicate = archivedCandidate;
                        }
                    }
                }
                if (duplicate != null) {
                    if (message.isModerated() && !duplicate.isModerated()) {
                        mXmppConnectionService.applyArchivedMessageModeration(conversation,
                                duplicate, message.getRoomStanzaId(),
                                message.getModeratedBy(), message.getModerationReason(),
                                message.getModeratedAt());
                    }
                    if (duplicate.getRoomStanzaId() == null
                            && message.getRoomStanzaId() != null) {
                        duplicate.setRoomStanzaId(message.getRoomStanzaId());
                        mXmppConnectionService.databaseBackend.updateMessage(duplicate, false);
                    }
                    final boolean serverMsgIdUpdated;
                    if (duplicate.getStatus() != Message.STATUS_RECEIVED
                            && duplicate.getUuid().equals(message.getRemoteMsgId())
                            && duplicate.getServerMsgId() == null
                            && message.getServerMsgId() != null) {
                        duplicate.setServerMsgId(message.getServerMsgId());
                        if (mXmppConnectionService.databaseBackend.updateMessage(
                                duplicate, false)) {
                            serverMsgIdUpdated = true;
                        } else {
                            serverMsgIdUpdated = false;
                            Log.e(Config.LOGTAG, "failed to update message");
                        }
                    } else {
                        serverMsgIdUpdated = false;
                    }
                    Log.d(
                            Config.LOGTAG,
                            "skipping duplicate message with "
                                    + message.getCounterpart()
                                    + ". serverMsgIdUpdated="
                                    + serverMsgIdUpdated);
                    return;
                }
            }

            // Persist protected incoming text before it becomes resident or visible.
            // publishInitial() switches the Message into protected mode before terminal
            // verification hydrates verifiedProtectedBody. Publishing the row to the live
            // conversation first creates a race where the UI can bind an empty protected body
            // and only show the text on a later refresh.
            if (!mXmppConnectionService.persistIncomingMessage(message)) {
                // Fail closed: transient parser plaintext must never surface when protected
                // publication failed or remains ambiguous.
                return;
            }

            // The original may arrive after its retraction (MAM/live).
            // Verify author identity before suppressing presentation.
            boolean verifiedMucRetraction = false;
            if (conversationMultiMode
                    && isTypeGroupChat
                    && !message.isModerated()
                    && message.getRoomStanzaId() != null) {
                for (final var pending :
                        mXmppConnectionService.databaseBackend
                                .getUnverifiedMucRetractions(
                                        conversation, message.getRoomStanzaId())) {
                    if (mXmppConnectionService.databaseBackend
                            .verifyUnverifiedMucRetraction(
                                    conversation, pending.requestId)) {
                        verifiedMucRetraction = true;
                        mXmppConnectionService.applyVerifiedMucRetraction(
                                conversation, pending.requestId);
                    }
                }
            }

            if (message.isModerated() || verifiedMucRetraction) {
                // Keep the scrubbed row/tombstone durable for deduplication and late MAM replay,
                // but never publish a residual moderation bubble into the visible timeline.
                if (query != null) {
                    query.incrementActualMessageCount();
                }
                return;
            }

            mXmppConnectionService.restoreReplyForMessage(conversation, message);
            if (query != null && query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                conversation.prepend(query.getActualInThisQuery(), message);
            } else {
                conversation.add(message);
            }
            if (query != null) {
                query.incrementActualMessageCount();
            }

            if (query == null || query.isCatchup()) { // either no mam or catchup
                if (status == Message.STATUS_SEND || status == Message.STATUS_SEND_RECEIVED) {
                    mXmppConnectionService.markRead(conversation);
                    if (query == null) {
                        activateGracePeriod(account);
                    }
                } else {
                    message.markUnread();
                    notify = true;
                }
            }
            if (message.isModerated()) {
                notify = false;
            }
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                    || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
                notify = false;
            }

            if (query == null) {
                extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart), isTypeGroupChat, packet);
                mXmppConnectionService.updateConversationUi();
            }

            if (mXmppConnectionService.confirmMessages()
                    && message.getStatus() == Message.STATUS_RECEIVED
                    && (message.trusted() || message.isPrivateMessage())
                    && remoteMsgId != null
                    && !selfAddressed
                    && !isTypeGroupChat) {
                processMessageReceipts(account, packet, remoteMsgId, query);
            }

            if (message.getStatus() == Message.STATUS_RECEIVED
                    && conversation.getOtrSession() != null
                    && !conversation.getOtrSession().getSessionID().getUserID()
                    .equals(message.getCounterpart().getResource())) {
                conversation.endOtrIfNeeded();
            }

            final HttpConnectionManager manager =
                    this.mXmppConnectionService.getHttpConnectionManager();
            final Message.FileParams remoteFileParams =
                    message.isOOb() ? message.getFileParams() : null;
            final boolean hasKnownRemoteAttachment =
                    remoteFileParams != null
                            && remoteFileParams.url != null
                            && remoteFileParams.size != null;
            if (!message.isModerated()
                    && message.trusted()
                    && (message.treatAsDownloadable() || hasKnownRemoteAttachment)
                    && manager.getAutoAcceptFileSize() > 0) {
                // Classification is presentation-only. A trusted remote attachment must enter the
                // same metadata/size policy even when an earlier pass already rewrote the body to
                // the canonical "url|size" form and treatAsDownloadable() no longer sees a bare URL.
                manager.createNewDownloadConnection(message);
            } else if (notify) {
                if (query != null && query.isCatchup()) {
                    mXmppConnectionService.getNotificationService().pushFromBacklog(message);
                } else {
                    mXmppConnectionService.getNotificationService().push(message);
                }
            }
        } else if (!packet.hasChild("body")) { // no body

            final Conversation conversation = mXmppConnectionService.find(account, from.asBareJid(), nextCounterpart);
            if (axolotlEncrypted != null) {
                Jid origin;
                if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                    final Jid fallback =
                            conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin = getTrueCounterpart(query != null ? mucUserElement : null, fallback);
                    if (origin == null) {
                        Log.d(
                                Config.LOGTAG,
                                "omemo key transport message in anonymous conference received");
                        return;
                    }
                } else if (isTypeGroupChat) {
                    return;
                } else {
                    origin = from;
                }
                try {
                    final XmppAxolotlMessage xmppAxolotlMessage =
                            XmppAxolotlMessage.fromElement(axolotlEncrypted, origin.asBareJid());
                    account.getAxolotlService()
                            .processReceivingKeyTransportMessage(xmppAxolotlMessage, query != null);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": omemo key transport message received from "
                                    + origin);
                } catch (Exception e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": invalid omemo key transport message received "
                                    + e.getMessage());
                    return;
                }
            }

            if (query == null && extractChatState(mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart), isTypeGroupChat, packet)) {
                mXmppConnectionService.updateConversationUi();
            }

            if (isTypeGroupChat) {
                if (packet.hasChild("subject")
                        && !packet.hasChild("thread")) { // We already know it has no body per above
                    if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                        conversation.setHasMessagesLeftOnServer(conversation.countMessages() > 0);
                        final LocalizedContent subject =
                                packet.findInternationalizedChildContentInDefaultNamespace(
                                        "subject");
                        if (subject != null
                                && conversation.getMucOptions().setSubject(subject.content)) {
                            mXmppConnectionService.updateConversation(conversation);
                        }
                        mXmppConnectionService.updateConversationUi();
                        return;
                    }
                }
            }
            if (conversation != null
                    && mucUserElement != null
                    && Jid.Invalid.hasValidFrom(packet)
                    && from.isBareJid()) {
                for (Element child : mucUserElement.getChildren()) {
                    if ("status".equals(child.getName())) {
                        try {
                            int code = Integer.parseInt(child.getAttribute("code"));
                            if ((code >= 170 && code <= 174) || (code >= 102 && code <= 104)) {
                                mXmppConnectionService.fetchConferenceConfiguration(conversation);
                                break;
                            }
                        } catch (Exception e) {
                            // ignored
                        }
                    } else if ("item".equals(child.getName())) {
                        final var user = AbstractParser.parseItem(conversation, child);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid()
                                        + ": changing affiliation for "
                                        + user.getRealJid()
                                        + " to "
                                        + user.getAffiliation()
                                        + " in "
                                        + conversation.getJid().asBareJid());
                        if (!user.realJidMatchesAccount()) {
                            final var mucOptions = conversation.getMucOptions();
                            final boolean isNew = mucOptions.updateUser(user);
                            final var avatarService = mXmppConnectionService.getAvatarService();
                            if (Strings.isNullOrEmpty(mucOptions.getAvatar())) {
                                avatarService.clear(mucOptions);
                            }
                            avatarService.clear(user);
                            mXmppConnectionService.updateMucRosterUi();
                            mXmppConnectionService.updateConversationUi();
                            Contact contact = user.getContact();
                            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                                Jid jid = user.getRealJid();
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                if (cryptoTargets.remove(user.getRealJid())) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": removed "
                                                    + jid
                                                    + " from crypto targets of "
                                                    + conversation.getName());
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    mXmppConnectionService.updateConversation(conversation);
                                }
                            } else if (isNew
                                    && user.getRealJid() != null
                                    && conversation.getMucOptions().isPrivateAndNonAnonymous()
                                    && (contact == null || !contact.mutualPresenceSubscription())
                                    && account.getAxolotlService()
                                            .hasEmptyDeviceList(user.getRealJid())) {
                                account.getAxolotlService().fetchDeviceIds(user.getRealJid());
                            }
                        }
                    }
                }
            }
            if (!isTypeGroupChat) {
                for (Element child : packet.getChildren()) {
                    if (Namespace.JINGLE_MESSAGE.equals(child.getNamespace())
                            && JINGLE_MESSAGE_ELEMENT_NAMES.contains(child.getName())) {
                        final String action = child.getName();
                        final String sessionId = child.getAttribute("id");
                        if (sessionId == null) {
                            break;
                        }
                        if (query == null && offlineMessagesRetrieved) {
                            if (serverMsgId == null) {
                                serverMsgId = extractStanzaId(account, packet);
                            }
                            mXmppConnectionService
                                    .getJingleConnectionManager()
                                    .deliverMessage(
                                            account,
                                            packet.getTo(),
                                            packet.getFrom(),
                                            child,
                                            remoteMsgId,
                                            serverMsgId,
                                            timestamp);
                            final Contact contact = account.getRoster().getContact(from);
                            // this is the same condition that is found in JingleRtpConnection for
                            // the 'ringing' response. Responding with delivery receipts predates
                            // the 'ringing' spec'd
                            final boolean sendReceipts =
                                    contact.showInContactList()
                                            || Config.JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK;
                            if (remoteMsgId != null && !contact.isSelf() && sendReceipts) {
                                processMessageReceipts(account, packet, remoteMsgId, null);
                            }
                        } else if ((query != null && query.isCatchup())
                                || !offlineMessagesRetrieved) {
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace =
                                        description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), null, false, false, false, nextCounterpart);
                                    final Message preExistingMessage = c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message =
                                            new Message(
                                                    c, status, Message.TYPE_RTP_SESSION, sessionId);
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(false, 0).toString());
                                    mXmppConnectionService.restoreReplyForMessage(conversation, message);
                                    c.add(message);
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                }
                            } else if ("proceed".equals(action)) {
                                //status needs to be flipped to find the original propose
                                final Conversation c = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), null, false, false, false, nextCounterpart);
                                final int s = packet.fromAccount(account) ? Message.STATUS_RECEIVED : Message.STATUS_SEND;
                                final Message message = c.findRtpSession(sessionId, s);
                                if (message != null) {
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    if (serverMsgId != null) {
                                        message.setServerMsgId(serverMsgId);
                                    }
                                    message.setTime(timestamp);
                                    mXmppConnectionService.updateMessage(message, true);
                                } else {
                                    Log.d(
                                            Config.LOGTAG,
                                            "unable to find original rtp session message for"
                                                    + " received propose");
                                }

                            } else if ("finish".equals(action)) {
                                Log.d(
                                        Config.LOGTAG,
                                        "received JMI 'finish' during MAM catch-up. Can be used to"
                                                + " update success/failure and duration");
                            }
                        } else {
                            // MAM reloads (non catchups
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace =
                                        description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c = mXmppConnectionService.findOrCreateConversation(account, counterpart.asBareJid(), null, false, false, false, nextCounterpart);
                                    final Message preExistingMessage = c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message =
                                            new Message(
                                                    c, status, Message.TYPE_RTP_SESSION, sessionId);
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    mXmppConnectionService.restoreReplyForMessage(conversation, message);
                                    if (query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                                        c.prepend(query.getActualInThisQuery(), message);
                                    } else {
                                        c.add(message);
                                    }
                                    query.incrementActualMessageCount();
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                }
                            }
                        }
                        break;
                    }
                }
            }

            final var received =
                    packet.getExtension(
                            im.conversations.android.xmpp.model.receipts.Received.class);
            if (received != null) {
                processReceived(received, packet, query, from);
            }
            final var displayed = packet.getExtension(Displayed.class);
            if (displayed != null) {
                processDisplayed(
                        displayed,
                        packet,
                        selfAddressed,
                        counterpart,
                        query,
                        isTypeGroupChat,
                        conversation,
                        mucUserElement,
                        occupant,
                        from);
            }
            final Reactions reactionsNew = packet.getExtension(Reactions.class);
            if (reactionsNew != null) {
                processReactions(
                        reactionsNew,
                        conversation,
                        isTypeGroupChat,
                        occupant,
                        counterpart,
                        mucTrueCounterPart,
                        packet);
            }

            // end no body
        }

        final Element event =
                original.findChild("event", "http://jabber.org/protocol/pubsub#event");
        if (event != null && Jid.Invalid.hasValidFrom(original) && original.getFrom().isBareJid()) {
            if (event.hasChild("items")) {
                parseEvent(event, original.getFrom(), account);
            } else if (event.hasChild("delete")) {
                parseDeleteEvent(event, original.getFrom(), account);
            } else if (event.hasChild("purge")) {
                parsePurgeEvent(event, original.getFrom(), account);
            }
        }

        final String nick = packet.findChildContent("nick", Namespace.NICK);
        if (nick != null && Jid.Invalid.hasValidFrom(original)) {
            if (mXmppConnectionService.isMuc(account, from)) {
                return;
            }
            final Contact contact = account.getRoster().getContact(from);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
    }

    private void processReceived(
            final im.conversations.android.xmpp.model.receipts.Received received,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final MessageArchiveService.Query query,
            final Jid from) {
        final var id = received.getId();
        if (packet.fromAccount(account)) {
            if (query != null && id != null && packet.getTo() != null) {
                query.removePendingReceiptRequest(new ReceiptRequest(packet.getTo(), id));
            }
        } else if (id != null) {
            if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                final String sessionId =
                        id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                mXmppConnectionService
                        .getJingleConnectionManager()
                        .updateProposedSessionDiscovered(
                                account,
                                from,
                                sessionId,
                                JingleConnectionManager.DeviceDiscoveryState.DISCOVERED);
            } else {
                mXmppConnectionService.markMessage(
                        account, from.asBareJid(), id, Message.STATUS_SEND_RECEIVED);
            }
        }
    }

    private void processDisplayed(
            final Displayed displayed,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final boolean selfAddressed,
            final Jid counterpart,
            final MessageArchiveService.Query query,
            final boolean isTypeGroupChat,
            Conversation conversation,
            Element mucUserElement,
            final OccupantId occupant,
            Jid from) {
        final var id = displayed.getId();
        // TODO we don’t even use 'sender' any more. Remove this!
        final Jid sender = Jid.Invalid.getNullForInvalid(displayed.getAttributeAsJid("sender"));

        Jid nextCounterpart = null;

        if (conversation != null) {
            nextCounterpart = conversation.getNextCounterpart();
        }

        if (packet.fromAccount(account) && !selfAddressed) {
            final Conversation c = mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart);
            final Message message =
                    (c == null || id == null) ? null : c.findReceivedWithRemoteId(id);
            if (message != null && (query == null || query.isCatchup())) {
                mXmppConnectionService.markReadUpTo(c, message);
            }
            if (query == null) {
                activateGracePeriod(account);
            }
        } else if (isTypeGroupChat) {
            final Message message;
            if (conversation != null && id != null) {
                if (sender != null) {
                    message = conversation.findMessageWithRemoteId(id, sender);
                } else {
                    message = conversation.findMessageWithServerMsgId(id);
                }
            } else {
                message = null;
            }
            if (message != null) {
                final var mucOptions = conversation.getMucOptions();
                final String occupantId =
                        occupant == null
                                ? null
                                : mucOptions.acceptedOccupantId(occupant.getId());
                final var occupantUser =
                        occupantId == null ? null : mucOptions.findUserByOccupantId(occupantId);
                final Jid fallback =
                        occupantUser != null && occupantUser.getRealJid() != null
                                ? occupantUser.getRealJid()
                                : mucOptions.getTrueCounterpart(counterpart);
                final Jid trueJid =
                        getTrueCounterpart(
                                (query != null && query.safeToExtractTrueCounterpart())
                                        ? mucUserElement
                                        : null,
                                fallback);
                final boolean trueJidMatchesAccount =
                        account.getJid()
                                .asBareJid()
                                .equals(trueJid == null ? null : trueJid.asBareJid());
                if (trueJidMatchesAccount
                        || (occupantId != null && mucOptions.isSelf(occupantId))
                        || mucOptions.isSelf(counterpart)) {
                    if (!message.isRead()
                            && (query == null || query.isCatchup())) { // checking if message is
                        // unread fixes race conditions
                        // with reflections
                        mXmppConnectionService.markReadUpTo(conversation, message);
                    }
                } else if (!counterpart.isBareJid()
                        && (trueJid != null || occupantId != null)) {
                    final ReadByMarker readByMarker =
                            ReadByMarker.from(counterpart, trueJid, occupantId);
                    if (message.addReadByMarker(readByMarker)) {
                        final var everyone = ImmutableSet.copyOf(mucOptions.getMembers(false));
                        final var readyBy = message.getReadyByTrue();
                        final var mStatus = message.getStatus();
                        if (mucOptions.isPrivateAndNonAnonymous()
                                && (mStatus == Message.STATUS_SEND_RECEIVED
                                        || mStatus == Message.STATUS_SEND)
                                && readyBy.containsAll(everyone)) {
                            message.setStatus(Message.STATUS_SEND_DISPLAYED);
                        }
                        mXmppConnectionService.updateMessage(message, false);
                    }
                }
            }
        } else {
            final Message displayedMessage =
                    mXmppConnectionService.markMessage(
                            account, from.asBareJid(), id, Message.STATUS_SEND_DISPLAYED);
            Message message = displayedMessage == null ? null : displayedMessage.prev();
            while (message != null
                    && message.getStatus() == Message.STATUS_SEND_RECEIVED
                    && message.getTimeSent() < displayedMessage.getTimeSent()) {
                mXmppConnectionService.markMessage(message, Message.STATUS_SEND_DISPLAYED);
                message = message.prev();
            }
            if (displayedMessage != null && selfAddressed) {
                dismissNotification(account, counterpart, query, id, null);
            }
        }
    }

    private void processReactions(
            final Reactions reactions,
            final Conversation conversation,
            final boolean isTypeGroupChat,
            final OccupantId occupant,
            final Jid counterpart,
            final Jid mucTrueCounterPart,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        final String reactingTo = reactions.getId();
        if (conversation != null && reactingTo != null) {
            if (isTypeGroupChat && conversation.getMode() == Conversational.MODE_MULTI) {
                final var mucOptions = conversation.getMucOptions();
                final var occupantId = occupant == null ? null : occupant.getId();
                if (occupantId != null) {
                    final boolean isReceived = !mucOptions.isSelf(occupantId);
                    final Message message;
                    final var inMemoryMessage = conversation.findMessageWithServerMsgId(reactingTo);
                    if (inMemoryMessage != null) {
                        message = inMemoryMessage;
                    } else {
                        message =
                                mXmppConnectionService.databaseBackend.getMessageWithServerMsgId(
                                        conversation, reactingTo);
                    }
                    if (message != null) {
                        final var combinedReactions =
                                Reaction.withOccupantId(
                                        message.getReactionsNew(),
                                        reactions.getReactions(),
                                        isReceived,
                                        counterpart,
                                        mucTrueCounterPart,
                                        occupantId);
                        message.setReactions(combinedReactions);
                        mXmppConnectionService.updateMessage(message, false);
                    } else {
                        Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                    }
                } else {
                    Log.d(Config.LOGTAG, "received reaction in channel w/o occupant ids. ignoring");
                }
            } else {
                final Message message;
                final var inMemoryMessage = conversation.findMessageWithUuidOrRemoteId(reactingTo);
                if (inMemoryMessage != null) {
                    message = inMemoryMessage;
                } else {
                    message =
                            mXmppConnectionService.databaseBackend.getMessageWithUuidOrRemoteId(
                                    conversation, reactingTo);
                }
                if (message == null) {
                    Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                    return;
                }
                final boolean isReceived;
                final Jid reactionFrom;
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    Log.d(Config.LOGTAG, "received reaction as MUC PM. triggering validation");
                    final var mucOptions = conversation.getMucOptions();
                    final var occupantId = occupant == null ? null : occupant.getId();
                    if (occupantId == null) {
                        Log.d(
                                Config.LOGTAG,
                                "received reaction via PM channel w/o occupant ids. ignoring");
                        return;
                    }
                    isReceived = !mucOptions.isSelf(occupantId);
                    if (isReceived) {
                        reactionFrom = counterpart;
                    } else {
                        if (!occupantId.equals(message.getOccupantId())) {
                            Log.d(
                                    Config.LOGTAG,
                                    "reaction received via MUC PM did not pass validation");
                            return;
                        }
                        reactionFrom = account.getJid().asBareJid();
                    }
                } else {
                    if (packet.fromAccount(account)) {
                        isReceived = false;
                        reactionFrom = account.getJid().asBareJid();
                    } else {
                        isReceived = true;
                        reactionFrom = counterpart;
                    }
                }
                final var combinedReactions =
                        Reaction.withFrom(
                                message.getReactionsNew(),
                                reactions.getReactions(),
                                isReceived,
                                reactionFrom);
                message.setReactions(combinedReactions);
                mXmppConnectionService.updateMessage(message, false);
            }
        }
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message, Long>
            getForwardedMessagePacket(
                    final im.conversations.android.xmpp.model.stanza.Message original,
                    Class<? extends Extension> clazz) {
        final var extension = original.getExtension(clazz);
        final var forwarded = extension == null ? null : extension.getExtension(Forwarded.class);
        if (forwarded == null) {
            return null;
        }
        final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
        final var forwardedMessage = forwarded.getMessage();
        if (forwardedMessage == null) {
            return null;
        }
        return new Pair<>(forwardedMessage, timestamp);
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message, Long>
            getForwardedMessagePacket(
                    final im.conversations.android.xmpp.model.stanza.Message original,
                    final String name,
                    final String namespace) {
        final Element wrapper = original.findChild(name, namespace);
        final var forwardedElement =
                wrapper == null ? null : wrapper.findChild("forwarded", Namespace.FORWARD);
        if (forwardedElement instanceof Forwarded forwarded) {
            final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
            final var forwardedMessage = forwarded.getMessage();
            if (forwardedMessage == null) {
                return null;
            }
            return new Pair<>(forwardedMessage, timestamp);
        }
        return null;
    }

    private void dismissNotification(
            Account account, Jid counterpart, MessageArchiveService.Query query, final String id, Jid nextCounterpart) {
        final Conversation conversation =
                mXmppConnectionService.find(account, counterpart.asBareJid(), nextCounterpart);
        if (conversation != null && (query == null || query.isCatchup())) {
            final String displayableId = conversation.findMostRecentRemoteDisplayableId();
            if (displayableId != null && displayableId.equals(id)) {
                mXmppConnectionService.markRead(conversation);
            } else {
                Log.w(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": received dismissing display marker that did not match our last"
                                + " id in that conversation");
            }
        }
    }

    private void processMessageReceipts(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final String remoteMsgId,
            MessageArchiveService.Query query) {
        final boolean markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
        final boolean request = packet.hasChild("request", "urn:xmpp:receipts");
        if (query == null) {
            final ArrayList<String> receiptsNamespaces = new ArrayList<>();
            if (markable) {
                receiptsNamespaces.add("urn:xmpp:chat-markers:0");
            }
            if (request) {
                receiptsNamespaces.add("urn:xmpp:receipts");
            }
            if (receiptsNamespaces.size() > 0) {
                final var receipt =
                        mXmppConnectionService
                                .getMessageGenerator()
                                .received(
                                        account,
                                        packet.getFrom(),
                                        remoteMsgId,
                                        receiptsNamespaces,
                                        packet.getType());
                mXmppConnectionService.sendMessagePacket(account, receipt);
            }
        } else if (query.isCatchup()) {
            if (request) {
                query.addPendingReceiptRequest(new ReceiptRequest(packet.getFrom(), remoteMsgId));
            }
        }
    }

    private void activateGracePeriod(Account account) {
        long duration =
                mXmppConnectionService.getLongPreference(
                                "grace_period_length", R.integer.grace_period)
                        * 1000;
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": activating grace period till "
                        + TIME_FORMAT.format(new Date(System.currentTimeMillis() + duration)));
        account.activateGracePeriod(duration);
    }

    private class Invite {
        final Jid jid;
        final String password;
        final boolean direct;
        final Jid inviter;

        Invite(Jid jid, String password, boolean direct, Jid inviter) {
            this.jid = jid;
            this.password = password;
            this.direct = direct;
            this.inviter = inviter;
        }

        public boolean execute(final Account account) {
            if (this.jid == null) {
                return false;
            }
            if (mXmppConnectionService.isMucExplicitlyLeft(account, this.jid)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring MUC invite after explicit leave; waiting for user join "
                                + this.jid.asBareJid());
                return false;
            }
            final Contact contact =
                    this.inviter != null ? account.getRoster().getContact(this.inviter) : null;
            if (contact != null && contact.isBlocked()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignore invite from "
                                + contact.getJid()
                                + " because contact is blocked");
                return false;
            }
            final AppSettings appSettings = new AppSettings(mXmppConnectionService);
            if ((contact != null && contact.showInContactList())
                    || appSettings.isAcceptInvitesFromStrangers()) {
                final Conversation conversation =
                        mXmppConnectionService.findOrCreateConversation(account, jid, null, true, false, false, null);
                if (conversation.getMucOptions().online()) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": received invite to "
                                    + jid
                                    + " but muc is considered to be online");
                    mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                } else {
                    mXmppConnectionService.rememberMucPassword(conversation, password);
                    mXmppConnectionService.joinMuc(
                            conversation, contact != null && contact.showInContactList());
                    mXmppConnectionService.updateConversationUi();
                }
                return true;
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring invite from "
                                + this.inviter
                                + " because we are not accepting invites from strangers. direct="
                                + direct);
                return false;
            }
        }
    }
}
