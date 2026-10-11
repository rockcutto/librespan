package eu.siacs.conversations.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.primitives.Longs;

import org.json.JSONException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.http.URL;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.storage.secure.MessagePayloadClassification;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.PresenceSelector;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageMarkup;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.json.JSONException;

public class Message extends AbstractEntity implements AvatarService.Avatarable {

    public static final String TABLENAME = "messages";

    public static final int STATUS_RECEIVED = 0;
    public static final int STATUS_UNSEND = 1;
    public static final int STATUS_SEND = 2;
    public static final int STATUS_SEND_FAILED = 3;
    public static final int STATUS_WAITING = 5;
    public static final int STATUS_OFFERED = 6;
    public static final int STATUS_SEND_RECEIVED = 7;
    public static final int STATUS_SEND_DISPLAYED = 8;

    public static final int ENCRYPTION_NONE = 0;
    public static final int ENCRYPTION_PGP = 1;
    public static final int ENCRYPTION_OTR = 2;
    public static final int ENCRYPTION_DECRYPTED = 3;
    public static final int ENCRYPTION_DECRYPTION_FAILED = 4;
    public static final int ENCRYPTION_AXOLOTL = 5;
    public static final int ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE = 6;
    public static final int ENCRYPTION_AXOLOTL_FAILED = 7;

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;
    public static final int TYPE_STATUS = 3;
    public static final int TYPE_PRIVATE = 4;
    public static final int TYPE_PRIVATE_FILE = 5;
    public static final int TYPE_RTP_SESSION = 6;

    public static final String CONVERSATION = "conversationUuid";
    public static final String COUNTERPART = "counterpart";
    public static final String TRUE_COUNTERPART = "trueCounterpart";
    public static final String BODY = "body";
    public static final String BODY_LANGUAGE = "bodyLanguage";
    public static final String TIME_SENT = "timeSent";
    public static final String ENCRYPTION = "encryption";
    public static final String STATUS = "status";
    public static final String TYPE = "type";
    public static final String CARBON = "carbon";
    public static final String OOB = "oob";
    public static final String EDITED = "edited";
    public static final String REMOTE_MSG_ID = "remoteMsgId";
    public static final String MEDIA_GROUP_ID = "mediaGroupId";
    public static final String SERVER_MSG_ID = "serverMsgId";
    public static final String ROOM_STANZA_ID = "roomStanzaId";
    public static final String MODERATED = "moderated";
    public static final String MODERATION_REASON = "moderationReason";
    public static final String MODERATED_BY = "moderatedBy";
    public static final String MODERATED_AT = "moderatedAt";
    public static final String MODERATION_RETIRED = "moderationRetired";
    public static final String RETRACTED = "retracted";
    public static final String RETRACTION_RETIRED = "retractionRetired";
    public static final String RELATIVE_FILE_PATH = "relativeFilePath";
    public static final String FINGERPRINT = "axolotl_fingerprint";
    public static final String READ = "read";
    public static final String ERROR_MESSAGE = "errorMsg";
    public static final String READ_BY_MARKERS = "readByMarkers";
    public static final String MARKABLE = "markable";
    public static final String DELETED = "deleted";
    public static final String OCCUPANT_ID = "occupantId";
    public static final String REACTIONS = "reactions";

    public static final String PAYLOADS = "payloads";
    public static final String ME_COMMAND = "/me ";

    public static final String ERROR_MESSAGE_CANCELLED = "eu.siacs.conversations.cancelled";

    public boolean markable = false;
    protected String conversationUuid;
    protected Jid counterpart;
    protected Jid trueCounterpart;
    protected String body;
    protected String encryptedBody;
    protected long timeSent;
    protected int encryption;
    protected int status;
    protected int type;
    protected boolean deleted = false;
    protected boolean carbon = false;
    protected boolean oob = false;
    protected List<Element> payloads = new ArrayList<>();
    protected List<Edit> edits = new ArrayList<>();
    protected String relativeFilePath;
    protected boolean read = true;
    protected String remoteMsgId = null;
    private String mediaGroupId = null;
    private String bodyLanguage = null;
    protected String serverMsgId = null;
    @Nullable private String roomStanzaId;
    private boolean moderated;
    @Nullable private String moderationReason;
    @Nullable private String moderatedBy;
    private long moderatedAt;
    private boolean moderationRetired;
    private boolean retracted;
    private boolean retractionRetired;
    private final Conversational conversation;
    protected Transferable transferable = null;
    private Message mNextMessage = null;
    private Message mPreviousMessage = null;
    private Message replyMessage = null;
    private boolean replyRestoredFromDb = false;
    private String axolotlFingerprint = null;
    private String errorMessage = null;
    private Set<ReadByMarker> readByMarkers = new CopyOnWriteArraySet<>();
    private String occupantId;
    private volatile Collection<Reaction> reactions = Collections.emptyList();
    private volatile CachedAggregatedReactions aggregatedReactions;
    @Nullable private transient SecureMessagePayloadMode secureMessagePayloadMode;
    @Nullable private transient String verifiedProtectedBody;
    @Nullable private transient String wireUuid;
    @Nullable private transient String secureMediaMimeType;
    @Nullable private transient String secureMediaFileName;
    @Nullable private transient Long secureMediaSizeBytes;
    private transient boolean secureMediaPresentationMetadataResolved = false;

    private Boolean isGeoUri = null;
    private Boolean isEmojisOnly = null;
    private Boolean treatAsDownloadable = null;
    private FileParams fileParams = null;
    private List<MucOptions.User> counterparts;
    private WeakReference<MucOptions.User> user;

    protected Message(Conversational conversation) {
        this.conversation = conversation;
    }

    public Message(Conversational conversation, String body, int encryption) {
        this(conversation, body, encryption, STATUS_UNSEND);
    }

    public Message(Conversational conversation, String body, int encryption, int status) {
        this(
                conversation,
                java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                body,
                System.currentTimeMillis(),
                encryption,
                status,
                TYPE_TEXT,
                false,
                null,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null,
                null,
                Collections.emptyList(),
                null);
    }

    public Message(Conversation conversation, int status, int type, final String remoteMsgId) {
        this(
                conversation,
                java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                null,
                System.currentTimeMillis(),
                Message.ENCRYPTION_NONE,
                status,
                type,
                false,
                remoteMsgId,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null,
                null,
                Collections.emptyList(),
                null);
    }

    protected Message(
            final Conversational conversation,
            final String uuid,
            final String conversationUUid,
            final Jid counterpart,
            final Jid trueCounterpart,
            final String body,
            final long timeSent,
            final int encryption,
            final int status,
            final int type,
            final boolean carbon,
            final String remoteMsgId,
            final String relativeFilePath,
            final String serverMsgId,
            final String fingerprint,
            final boolean read,
            final String edited,
            final boolean oob,
            final String errorMessage,
            final Set<ReadByMarker> readByMarkers,
            final boolean markable,
            final boolean deleted,
            final String bodyLanguage,
            final String occupantId,
            final Collection<Reaction> reactions,
            final List<Element> payloads) {
        this.conversation = conversation;
        this.uuid = uuid;
        this.conversationUuid = conversationUUid;
        this.counterpart = counterpart;
        this.trueCounterpart = trueCounterpart;
        this.body = body == null ? "" : body;
        this.timeSent = timeSent;
        this.encryption = encryption;
        this.status = status;
        this.type = type;
        this.carbon = carbon;
        this.remoteMsgId = remoteMsgId;
        this.relativeFilePath = relativeFilePath;
        this.serverMsgId = serverMsgId;
        this.axolotlFingerprint = fingerprint;
        this.read = read;
        this.edits = Edit.fromJson(edited);
        this.oob = oob;
        this.errorMessage = errorMessage;
        this.readByMarkers = readByMarkers == null ? new CopyOnWriteArraySet<>() : readByMarkers;
        this.markable = markable;
        this.deleted = deleted;
        this.bodyLanguage = bodyLanguage;
        this.occupantId =
                MucOptions.isValidOccupantIdValue(occupantId) ? occupantId : null;
        this.reactions = reactions;
        if (payloads != null) this.payloads = payloads;
    }

    public static Message fromCursor(final Cursor cursor, final Conversation conversation) {
        List<Element> payloads = new ArrayList<>();
        try {
            String payloadsStr = cursor.getString(cursor.getColumnIndexOrThrow(PAYLOADS));
            if (payloadsStr != null) {
                final XmlReader xmlReader = new XmlReader();
                xmlReader.setInputStream(ByteSource.wrap(payloadsStr.getBytes()).openStream());
                Tag tag;
                while ((tag = xmlReader.readTag()) != null) {
                    payloads.add(xmlReader.readElement(tag));
                }
            }
        } catch (IOException e) {}

        final Message message = new Message(
                conversation,
                cursor.getString(cursor.getColumnIndexOrThrow(UUID)),
                cursor.getString(cursor.getColumnIndexOrThrow(CONVERSATION)),
                fromString(cursor.getString(cursor.getColumnIndexOrThrow(COUNTERPART))),
                fromString(cursor.getString(cursor.getColumnIndexOrThrow(TRUE_COUNTERPART))),
                cursor.getString(cursor.getColumnIndexOrThrow(BODY)),
                cursor.getLong(cursor.getColumnIndexOrThrow(TIME_SENT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(ENCRYPTION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(TYPE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(CARBON)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(REMOTE_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(RELATIVE_FILE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(SERVER_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(FINGERPRINT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(READ)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(EDITED)),
                cursor.getInt(cursor.getColumnIndexOrThrow(OOB)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(ERROR_MESSAGE)),
                ReadByMarker.fromJsonString(
                        cursor.getString(cursor.getColumnIndexOrThrow(READ_BY_MARKERS))),
                cursor.getInt(cursor.getColumnIndexOrThrow(MARKABLE)) > 0,
                cursor.getInt(cursor.getColumnIndexOrThrow(DELETED)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(BODY_LANGUAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(OCCUPANT_ID)),
                Reaction.fromString(cursor.getString(cursor.getColumnIndexOrThrow(REACTIONS))),
                payloads
          );
        message.mediaGroupId = cursor.getString(cursor.getColumnIndexOrThrow(MEDIA_GROUP_ID));
        message.roomStanzaId = cursor.getString(cursor.getColumnIndexOrThrow(ROOM_STANZA_ID));
        message.moderated = cursor.getInt(cursor.getColumnIndexOrThrow(MODERATED)) != 0;
        message.moderationReason = cursor.getString(cursor.getColumnIndexOrThrow(MODERATION_REASON));
        message.moderatedBy = cursor.getString(cursor.getColumnIndexOrThrow(MODERATED_BY));
        message.moderatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(MODERATED_AT));
        message.moderationRetired = cursor.getInt(cursor.getColumnIndexOrThrow(MODERATION_RETIRED)) != 0;
        message.retracted = cursor.getInt(cursor.getColumnIndexOrThrow(RETRACTED)) != 0;
        message.retractionRetired = cursor.getInt(cursor.getColumnIndexOrThrow(RETRACTION_RETIRED)) != 0;
        return message;
    }

    private static Jid fromString(String value) {
        try {
            if (value != null) {
                return Jid.of(value);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    public static Message createStatusMessage(Conversation conversation, String body) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.setStatus(Message.STATUS_RECEIVED);
        message.body = body;
        return message;
    }

    public static Message createLoadMoreMessage(Conversation conversation) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.body = "LOAD_MORE";
        return message;
    }

    @Override
    public ContentValues getContentValues() {
        final var values = new ContentValues();
        values.put(UUID, uuid);
        values.put(CONVERSATION, conversationUuid);
        if (counterpart == null) {
            values.putNull(COUNTERPART);
        } else {
            values.put(COUNTERPART, counterpart.toString());
        }
        if (trueCounterpart == null) {
            values.putNull(TRUE_COUNTERPART);
        } else {
            values.put(TRUE_COUNTERPART, trueCounterpart.toString());
        }
        values.put(
                BODY,
                body.length() > Config.MAX_STORAGE_MESSAGE_CHARS
                        ? body.substring(0, Config.MAX_STORAGE_MESSAGE_CHARS)
                        : body);
        values.put(TIME_SENT, timeSent);
        values.put(ENCRYPTION, encryption);
        values.put(STATUS, status);
        values.put(TYPE, type);
        values.put(CARBON, carbon ? 1 : 0);
        values.put(REMOTE_MSG_ID, remoteMsgId);
        values.put(MEDIA_GROUP_ID, mediaGroupId);
        values.put(RELATIVE_FILE_PATH, relativeFilePath);
        values.put(SERVER_MSG_ID, serverMsgId);
        values.put(ROOM_STANZA_ID, roomStanzaId);
        values.put(MODERATED, moderated ? 1 : 0);
        values.put(MODERATION_REASON, moderationReason);
        values.put(MODERATED_BY, moderatedBy);
        values.put(MODERATED_AT, moderatedAt);
        values.put(MODERATION_RETIRED, moderationRetired ? 1 : 0);
        values.put(RETRACTED, retracted ? 1 : 0);
        values.put(RETRACTION_RETIRED, retractionRetired ? 1 : 0);
        values.put(FINGERPRINT, axolotlFingerprint);
        values.put(READ, read ? 1 : 0);
        try {
            values.put(EDITED, Edit.toJson(edits));
        } catch (JSONException e) {
            Log.e(Config.LOGTAG, "error persisting json for edits", e);
        }
        values.put(OOB, oob ? 1 : 0);
        values.put(ERROR_MESSAGE, errorMessage);
        values.put(READ_BY_MARKERS, ReadByMarker.toJson(readByMarkers).toString());
        values.put(MARKABLE, markable ? 1 : 0);
        values.put(DELETED, deleted ? 1 : 0);
        values.put(BODY_LANGUAGE, bodyLanguage);
        values.put(OCCUPANT_ID, occupantId);
        values.put(REACTIONS, Reaction.toString(this.reactions));

        StringBuilder payloadsValue = null;

        for (Element element : payloads) {
            if (payloadsValue == null) {
                payloadsValue = new StringBuilder();
            }

            payloadsValue.append(element.toString());
        }

        values.put(PAYLOADS, payloads.size() < 1 ? null : payloadsValue.toString());

        return values;
    }

    public String replyId() {
        if (conversation.getMode() == Conversation.MODE_MULTI) return getServerMsgId();
        final String remote = getRemoteMsgId();
        if (remote == null && getStatus() > STATUS_RECEIVED) return getUuid();
        return remote;
    }

    public Message reply() {
        Message m;
        String name = getAvatarName();

        if (name != null && !name.isEmpty() && conversation != null && conversation.getMode() == Conversational.MODE_MULTI) {
            m = new Message(conversation, QuoteHelper.quote("<" + name + ">" + "\n" + MessageUtils.prepareQuote(this)) + "\n", ENCRYPTION_NONE);
        } else {
            m = new Message(conversation, QuoteHelper.quote(MessageUtils.prepareQuote(this)) + "\n", ENCRYPTION_NONE);
        }

        m.addPayload(
                new Element("reply", "urn:xmpp:reply:0")
                        .setAttribute("to", getCounterpart())
                        .setAttribute("id", replyId())
        );
        final Element fallback = new Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reply:0");
        fallback.addChild("body", "urn:xmpp:fallback:0")
                .setAttribute("start", "0")
                .setAttribute("end", "" + m.body.codePointCount(0, m.body.length()));
        m.addPayload(fallback);
        return m;
    }

    public Message react(String emoji) {
        Set<String> emojis = new HashSet<>();
        if (conversation instanceof Conversation) emojis = ((Conversation) conversation).findReactionsTo(replyId(), null);
        emojis.add(emoji);
        final Message m = reply();
        m.appendBody(emoji);
        final Element fallback = new Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reactions:0");
        fallback.addChild("body", "urn:xmpp:fallback:0");
        m.addPayload(fallback);
        final Element reactions = new Element("reactions", "urn:xmpp:reactions:0").setAttribute("id", replyId());
        for (String oneEmoji : emojis) {
            reactions.addChild("reaction", "urn:xmpp:reactions:0").setContent(oneEmoji);
        }
        m.addPayload(reactions);
        return m;
    }

    public void setReactions(Element reactions) {
        if (this.payloads != null) {
            this.payloads.remove(getReactions());
        }
        addPayload(reactions);
    }

    public Element getReactions() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("reactions") && el.getNamespace().equals("urn:xmpp:reactions:0")) {
                return el;
            }
        }

        return null;
    }

    public Element getReplyOrReaction() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("reply") && el.getNamespace().equals("urn:xmpp:reply:0")) {
                return el;
            }
        }

        for (Element el : this.payloads) {
            if (el.getName().equals("reactions") && el.getNamespace().equals("urn:xmpp:reactions:0")) {
                return el;
            }
        }

        return null;
    }

    @Nullable
    public Message getReplyMessage() {
       if (replyMessage != null && replyMessage.deleted) {
           replyMessage = null;
           replyRestoredFromDb = false;
       }

        return replyMessage;
    }

    public boolean isReplyRestoredFromDb() {
        return replyRestoredFromDb;
    }

    public void setReplyMessage(Message replyMessage, boolean restoredFromDb) {
        this.replyMessage = replyMessage;
        this.replyRestoredFromDb = restoredFromDb;
    }

    public String getConversationUuid() {
        return conversationUuid;
    }

    public Conversational getConversation() {
        return this.conversation;
    }

    public Jid getCounterpart() {
        return counterpart;
    }

    public void setCounterpart(final Jid counterpart) {
        this.counterpart = counterpart;
    }

    public Contact getContact() {
        if (this.conversation.getMode() == Conversation.MODE_SINGLE) {
            return this.conversation.getContact();
        } else {
            if (this.trueCounterpart == null) {
                return null;
            } else {
                return this.conversation
                        .getAccount()
                        .getRoster()
                        .getContactFromContactList(this.trueCounterpart);
            }
        }
    }

    public synchronized String getBody() {
        if (moderated || retracted) return "";
        if (secureMessagePayloadMode != null) {
            return verifiedProtectedBody == null ? "" : verifiedProtectedBody;
        }
        return body;
    }

    /**
     * Protected text is kept only in the transient verified presentation slot. The durable body
     * field remains empty once a protected classification is attached.
     */
    public synchronized void setBody(String body) {
        if (body == null) {
            throw new Error("You should not set the message body to null");
        }
        if (moderated || retracted) return;
        if (secureMessagePayloadMode == null) {
            this.body = body;
        } else {
            this.verifiedProtectedBody = body;
            this.body = "";
        }
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
        this.fileParams = null;
    }

    public synchronized void appendBody(String append) {
        if (moderated || retracted) return;
        if (secureMessagePayloadMode == null) {
            this.body += append;
        } else {
            this.verifiedProtectedBody =
                    (this.verifiedProtectedBody == null ? "" : this.verifiedProtectedBody) + append;
            this.body = "";
        }
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
    }

    public void setMucUser(MucOptions.User user) {
        this.user = new WeakReference<>(user);
    }

    public boolean sameMucUser(Message otherMessage) {
        final MucOptions.User thisUser = this.user == null ? null : this.user.get();
        final MucOptions.User otherUser =
                otherMessage.user == null ? null : otherMessage.user.get();
        return thisUser != null && thisUser == otherUser;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean setErrorMessage(String message) {
        boolean changed =
                (message != null && !message.equals(errorMessage))
                        || (message == null && errorMessage != null);
        this.errorMessage = message;
        return changed;
    }

    public long getTimeSent() {
        return timeSent;
    }

    public int getEncryption() {
        return encryption;
    }

    public void setEncryption(int encryption) {
        this.encryption = encryption;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getRelativeFilePath() {
        return this.relativeFilePath;
    }

    public void setRelativeFilePath(String path) {
        this.relativeFilePath = path;
    }

    public String getRemoteMsgId() {
        return this.remoteMsgId;
    }

    public void setRemoteMsgId(String id) {
        this.remoteMsgId = id;
    }

    public String getServerMsgId() {
        return this.serverMsgId;
    }

    public void setServerMsgId(String id) {
        this.serverMsgId = id;
    }

    @Nullable
    public String getRoomStanzaId() {
        return roomStanzaId;
    }

    public void setRoomStanzaId(@Nullable final String id) {
        roomStanzaId = id;
    }

    public synchronized boolean isModerated() {
        return moderated;
    }

    public synchronized boolean isRetracted() {
        return retracted;
    }

    /** Clear resident content after durable XEP-0424 retirement begins. */
    public synchronized void markRetracted() {
        if (!retracted) retractionRetired = false;
        retracted = true;
        body = "";
        encryptedBody = null;
        verifiedProtectedBody = null;
        payloads.clear();
        relativeFilePath = null;
        mediaGroupId = null;
        type = TYPE_TEXT;
        oob = false;
        transferable = null;
        reactions = Collections.emptyList();
        secureMediaMimeType = null;
        secureMediaFileName = null;
        secureMediaSizeBytes = null;
        secureMediaPresentationMetadataResolved = false;
        replyMessage = null;
        isGeoUri = null;
        isEmojisOnly = null;
        treatAsDownloadable = null;
        fileParams = null;
    }

    public synchronized boolean isRetractionRetired() {
        return retractionRetired;
    }

    public synchronized void markModerated(
            @Nullable final String by, @Nullable final String reason, final long stamp) {
        if (!moderated) moderationRetired = false;
        moderated = true;
        moderatedBy = by;
        moderationReason = reason;
        moderatedAt = stamp;
        body = "";
        encryptedBody = null;
        verifiedProtectedBody = null;
        payloads.clear();
        relativeFilePath = null;
        mediaGroupId = null;
        type = TYPE_TEXT;
        oob = false;
        transferable = null;
        reactions = Collections.emptyList();
        secureMediaMimeType = null;
        secureMediaFileName = null;
        secureMediaSizeBytes = null;
        secureMediaPresentationMetadataResolved = false;
        replyMessage = null;
        isGeoUri = null;
        isEmojisOnly = null;
        treatAsDownloadable = null;
        fileParams = null;
    }

    @Nullable public String getModerationReason() { return moderationReason; }
    @Nullable public String getModeratedBy() { return moderatedBy; }
    public long getModeratedAt() { return moderatedAt; }
    public void setModerationRetired(boolean retired) { moderationRetired = retired; }

    public boolean isRead() {
        return this.read;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public void markRead() {
        this.read = true;
    }

    public void markUnread() {
        this.read = false;
    }

    public void setTime(long time) {
        this.timeSent = time;
    }

    public String getEncryptedBody() {
        return this.encryptedBody;
    }

    public void setEncryptedBody(String body) {
        this.encryptedBody = body;
    }

    public int getType() {
        return this.type;
    }

    public void setType(int type) {
        this.type = type;
    }

    @Nullable
    public String getMediaGroupId() {
        return mediaGroupId;
    }

    public void setMediaGroupId(@Nullable final String mediaGroupId) {
        this.mediaGroupId = mediaGroupId;
    }

    public boolean isCarbon() {
        return carbon;
    }

    public void setCarbon(boolean carbon) {
        this.carbon = carbon;
    }

    public void putEdited(String edited, String serverMsgId) {
        final Edit edit = new Edit(edited, serverMsgId);
        if (this.edits.size() < 128 && !this.edits.contains(edit)) {
            this.edits.add(edit);
        }
    }

    boolean remoteMsgIdMatchInEdit(String id) {
        for (Edit edit : this.edits) {
            if (id.equals(edit.getEditedId())) {
                return true;
            }
        }
        return false;
    }

    public String getBodyLanguage() {
        return this.bodyLanguage;
    }

    public void setBodyLanguage(String language) {
        this.bodyLanguage = language;
    }

    public boolean edited() {
        return !this.edits.isEmpty();
    }

    public void setTrueCounterpart(Jid trueCounterpart) {
        this.trueCounterpart = trueCounterpart;
    }

    public Jid getTrueCounterpart() {
        return this.trueCounterpart;
    }

    public Transferable getTransferable() {
        return this.transferable;
    }

    public synchronized void setTransferable(Transferable transferable) {
        this.fileParams = null;
        this.transferable = transferable;
    }

    public boolean addReadByMarker(final ReadByMarker readByMarker) {
        if (readByMarker.getOccupantId() != null
                && readByMarker.getOccupantId().equals(occupantId)) {
            return false;
        }
        if (readByMarker.getRealJid() != null) {
            if (readByMarker.getRealJid().asBareJid().equals(trueCounterpart)) {
                return false;
            }
        } else if (readByMarker.getOccupantId() == null
                && readByMarker.getFullJid() != null) {
            if (readByMarker.getFullJid().equals(counterpart)) {
                return false;
            }
        }
        if (ReadByMarker.contains(readByMarker, this.readByMarkers)) {
            return false;
        }
        if (this.readByMarkers.add(readByMarker)) {
            if (readByMarker.getRealJid() != null && readByMarker.getFullJid() != null) {
                Iterator<ReadByMarker> iterator = this.readByMarkers.iterator();
                while (iterator.hasNext()) {
                    ReadByMarker marker = iterator.next();
                    if (marker.getRealJid() == null
                            && readByMarker.getFullJid().equals(marker.getFullJid())) {
                        iterator.remove();
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public Set<ReadByMarker> getReadByMarkers() {
        return ImmutableSet.copyOf(this.readByMarkers);
    }

    public Set<Jid> getReadyByTrue() {
        return ImmutableSet.copyOf(
                Collections2.transform(
                        Collections2.filter(this.readByMarkers, m -> m.getRealJid() != null),
                        ReadByMarker::getRealJid));
    }

    boolean similar(Message message) {
        if (!isPrivateMessage() && this.serverMsgId != null && message.getServerMsgId() != null) {
            return this.serverMsgId.equals(message.getServerMsgId())
                    || Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId());
        } else if (Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId())) {
            return true;
        } else if (this.body == null || this.counterpart == null) {
            return false;
        } else {
            String body, otherBody;
            if (this.hasFileOnRemoteHost()) {
                body = getFileParams().url;
                otherBody = message.body == null ? null : message.body.trim();
            } else {
                body = this.body;
                otherBody = message.body;
            }
            final boolean matchingCounterpart = this.counterpart.equals(message.getCounterpart());
            if (message.getRemoteMsgId() != null) {
                final boolean hasUuid =
                        CryptoHelper.UUID_PATTERN.matcher(message.getRemoteMsgId()).matches();
                if (hasUuid
                        && matchingCounterpart
                        && Edit.wasPreviouslyEditedRemoteMsgId(edits, message.getRemoteMsgId())) {
                    return true;
                }
                return (message.getRemoteMsgId().equals(this.remoteMsgId)
                                || message.getRemoteMsgId().equals(this.uuid))
                        && matchingCounterpart
                        && (body.equals(otherBody)
                                || (message.getEncryption() == Message.ENCRYPTION_PGP && hasUuid));
            } else {
                return this.remoteMsgId == null
                        && matchingCounterpart
                        && body.equals(otherBody)
                        && Math.abs(this.getTimeSent() - message.getTimeSent()) < 20_000;
            }
        }
    }

    public Message next() {
        if (this.conversation instanceof Conversation c) {
            synchronized (c.messages) {
                if (this.mNextMessage == null) {
                    int index = c.messages.indexOf(this);
                    if (index < 0 || index >= c.messages.size() - 1) {
                        this.mNextMessage = null;
                    } else {
                        this.mNextMessage = c.messages.get(index + 1);
                    }
                }
                return this.mNextMessage;
            }
        } else {
            throw new AssertionError("Calling next should be disabled for stubs");
        }
    }

    public Message prev() {
        if (this.conversation instanceof Conversation c) {
            synchronized (c.messages) {
                if (this.mPreviousMessage == null) {
                    int index = c.messages.indexOf(this);
                    if (index <= 0 || index > c.messages.size()) {
                        this.mPreviousMessage = null;
                    } else {
                        this.mPreviousMessage = c.messages.get(index - 1);
                    }
                }
            }
            return this.mPreviousMessage;
        } else {
            throw new AssertionError("Calling prev should be disabled for stubs");
        }
    }

    /**
     * Returns the immutable XMPP identity corrected by this logical message, or null when the
     * message cannot be identified safely. Local database UUIDs are never preferred over an
     * archived origin/remote ID; once a message has corrections, the first correction retains the
     * original target for every subsequent edit.
     */
    @Nullable
    public synchronized String getCorrectionTargetId() {
        if (!isCorrectionIdentityEligible()) {
            return null;
        }
        if (!edits.isEmpty()) {
            final String original = edits.get(0).getEditedId();
            return Strings.isNullOrEmpty(original) ? null : original;
        }
        return Strings.isNullOrEmpty(remoteMsgId) ? uuid : remoteMsgId;
    }

    /** Explicit UI/send policy for one safely identifiable own text message. */
    public synchronized boolean isCorrectableMessage() {
        return getCorrectionTargetId() != null;
    }

    private boolean isCorrectionIdentityEligible() {
        return conversation instanceof Conversation
                && (type == TYPE_TEXT || type == TYPE_PRIVATE)
                && status != STATUS_RECEIVED
                && !carbon
                && !deleted
                && !isGeoUri()
                && getReactions() == null
                && !Strings.isNullOrEmpty(uuid);
    }

    /**
     * Kept for Ctrl+Up convenience only. Action surfaces must use isCorrectableMessage().
     */
    public boolean isLastCorrectableMessage() {
        Message next = next();
        while (next != null) {
            if (next.isCorrectableMessage()) {
                return false;
            }
            next = next.next();
        }
        return isCorrectableMessage();
    }

    public boolean isEditable() {
        return isCorrectableMessage();
    }

    public boolean mergeable(final Message message) {
        return message != null &&
                ((message.getType() == Message.TYPE_TEXT || message.getType() == Message.TYPE_PRIVATE) &&
                        this.getTransferable() == null &&
                        message.getTransferable() == null &&
                        message.getEncryption() != Message.ENCRYPTION_PGP &&
                        message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED &&
                        this.getType() == message.getType() &&
                        isStatusMergeable(this.getStatus(), message.getStatus()) &&
                        isEncryptionMergeable(this.getEncryption(),message.getEncryption()) &&
                        this.getCounterpart() != null &&
                        this.getCounterpart().equals(message.getCounterpart()) &&
                        this.edited() == message.edited() &&
                        Math.abs(message.getTimeSent() - this.getTimeSent()) <= (Config.MESSAGE_MERGE_WINDOW * 1000) &&
                        this.getBody().length() + message.getBody().length() <= Config.MAX_DISPLAY_MESSAGE_CHARS &&
                        !message.isGeoUri() &&
                        !this.isGeoUri() &&
                        !message.isOOb() &&
                        !this.isOOb() &&
                        !message.treatAsDownloadable() &&
                        !this.treatAsDownloadable() &&
                        !message.hasMeCommand() &&
                        !this.hasMeCommand() &&
                        !this.bodyIsOnlyEmojis() &&
                        !message.bodyIsOnlyEmojis() &&
                        ((this.axolotlFingerprint == null && message.axolotlFingerprint == null) || this.axolotlFingerprint.equals(message.getFingerprint())) &&
                        UIHelper.sameDay(message.getTimeSent(), this.getTimeSent()) &&
                        this.getReadByMarkers().equals(message.getReadByMarkers())
                );
    }

    private static boolean isStatusMergeable(int a, int b) {
        return a == b || (
                (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_SEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_WAITING)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_WAITING)
        );
    }

    private static boolean isEncryptionMergeable(final int a, final int b) {
        return a == b
                && Arrays.asList(ENCRYPTION_NONE, ENCRYPTION_DECRYPTED, ENCRYPTION_AXOLOTL)
                        .contains(a);
    }

    public void setCounterparts(List<MucOptions.User> counterparts) {
        this.counterparts = counterparts;
    }

    public List<MucOptions.User> getCounterparts() {
        return this.counterparts;
    }

    @Override
    public int getAvatarBackgroundColor() {
        if (type == Message.TYPE_STATUS
                && getCounterparts() != null
                && getCounterparts().size() > 1) {
            return Color.TRANSPARENT;
        } else {
            return UIHelper.getColorForName(UIHelper.getMessageDisplayName(this));
        }
    }

    @Override
    public String getAvatarName() {
        return UIHelper.getMessageDisplayName(this);
    }

    public boolean isOOb() {
        return oob;
    }

    public static class MergeSeparator {
    }

    public void setOccupantId(final String id) {
        this.occupantId = MucOptions.isValidOccupantIdValue(id) ? id : null;
    }

    public String getOccupantId() {
        return this.occupantId;
    }

    public Collection<Reaction> getReactionsNew() {
        return this.reactions;
    }

    public Reaction.Aggregated getAggregatedReactions() {
        final Collection<Reaction> reactions = this.reactions;
        final CachedAggregatedReactions cached = this.aggregatedReactions;
        if (cached != null && cached.source == reactions) {
            return cached.aggregated;
        }

        final Reaction.Aggregated aggregated = Reaction.aggregated(reactions);
        this.aggregatedReactions = new CachedAggregatedReactions(reactions, aggregated);
        return aggregated;
    }

    public void setReactions(final Collection<Reaction> reactions) {
        this.reactions = reactions;
        this.aggregatedReactions = null;
    }

    private static final class CachedAggregatedReactions {
        private final Collection<Reaction> source;
        private final Reaction.Aggregated aggregated;

        private CachedAggregatedReactions(
                final Collection<Reaction> source, final Reaction.Aggregated aggregated) {
            this.source = source;
            this.aggregated = aggregated;
        }
    }

    public SpannableStringBuilder getBodyForDisplaying() {
        return getBodyForDisplaying(false);
    }

    public SpannableStringBuilder getBodyForDisplaying(boolean omitReplyText) {
        if (isModerated() || isRetracted()) return new SpannableStringBuilder();
        if (replyMessage != null) {
            if (omitReplyText) {
                return new SpannableStringBuilder(MessageUtils.filterLtrRtl(removeReplyFallback(this).toString()).trim());
            }

            try {
                String replyPreview =
                        MessageUtils.filterLtrRtl(
                                        removeReplyFallback(replyMessage).toString())
                                .trim();

                int newline = replyPreview.indexOf('\n');
                if (newline >= 0) {
                    replyPreview = replyPreview.substring(0, newline).trim() + "...";
                }

                return new SpannableStringBuilder(
                        "> " + replyPreview
                                + "\n"
                                + MessageUtils.filterLtrRtl(
                                        removeReplyFallback(this).toString())
                                .trim()
                );
            } catch (IndexOutOfBoundsException e) {
                return new SpannableStringBuilder(MessageUtils.filterLtrRtl(getBody()).trim());
            }
        } else {
            return new SpannableStringBuilder(MessageUtils.filterLtrRtl(getBody()).trim());
        }
    }

    public SpannableStringBuilder getBodyForReplyPreview(XmppConnectionService xmppConnectionService) {
        if (isModerated()) {
            return SpannableStringBuilder.valueOf(xmppConnectionService.getString(R.string.message_deleted));
        }
        if (isFileOrImage()) {
            return SpannableStringBuilder.valueOf(StringUtils.capitalize(UIHelper.getFileDescriptionString(xmppConnectionService, this)));
        }

        try {
            final SpannableStringBuilder preview;
            final Element markup = getMessageMarkup();
            if (markup != null) {
                // XEP-0394 offsets address the canonical body, including an XEP-0461 fallback
                // prefix when this message itself is a reply. Apply spans first, then remove the
                // fallback from the Spannable so style ranges move with their text.
                preview =
                        MessageMarkup.presentationText(
                                getBody(), markup, isStylingDisabled());
                removeReplyFallbackFromSpannable(preview, getBody());
                trimReplyPreviewInPlace(preview);
            } else {
                // XEP-0393 carries formatting directives in the body. Remove reply fallback
                // before parsing so the directives are interpreted only on the visible content.
                final String visible =
                        MessageUtils.filterLtrRtl(removeReplyFallback(this).toString()).trim();
                preview =
                        MessageMarkup.presentationText(
                                visible, null, isStylingDisabled());
                trimReplyPreviewInPlace(preview);
            }
            return preview;
        } catch (IndexOutOfBoundsException e) {
            final String fallback = MessageUtils.filterLtrRtl(getBody()).trim();
            return MessageMarkup.presentationText(
                    fallback, getMessageMarkup(), isStylingDisabled());
        }
    }

    private void removeReplyFallbackFromSpannable(
            final SpannableStringBuilder body, final String originalBody) {
        final List<Element> replyFallbacks = getFallbacks("urn:xmpp:reply:0");
        if (replyFallbacks.isEmpty()) {
            return;
        }
        final Element bodyFallback = replyFallbacks.get(0).findChild("body");
        if (bodyFallback == null) {
            stripBrokenLeadingReplyQuote(body);
            return;
        }

        final int startCodePoint;
        final int endCodePoint;
        try {
            startCodePoint = Integer.parseInt(bodyFallback.getAttribute("start"));
            endCodePoint = Integer.parseInt(bodyFallback.getAttribute("end"));
        } catch (final NumberFormatException ignored) {
            stripBrokenLeadingReplyQuote(body);
            return;
        }

        final int totalCodePoints =
                originalBody.codePointCount(0, originalBody.length());
        if (startCodePoint < 0 || endCodePoint < startCodePoint || endCodePoint > totalCodePoints) {
            stripBrokenLeadingReplyQuote(body);
            return;
        }

        final int start = originalBody.offsetByCodePoints(0, startCodePoint);
        final int end = originalBody.offsetByCodePoints(0, endCodePoint);
        if (start <= body.length() && end <= body.length()) {
            body.delete(start, end);
        }
    }

    private static void trimReplyPreviewInPlace(final SpannableStringBuilder preview) {
        trimSpannableInPlace(preview);
        final int newline = preview.toString().indexOf('\n');
        if (newline >= 0) {
            preview.delete(newline, preview.length());
            trimSpannableInPlace(preview);
            preview.append("...");
        }
    }

    private static void trimSpannableInPlace(final SpannableStringBuilder text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end < text.length()) {
            text.delete(end, text.length());
        }
        if (start > 0) {
            text.delete(0, start);
        }
    }

    private static void stripBrokenLeadingReplyQuote(final SpannableStringBuilder body) {
        int i = 0;
        while (i < body.length()
                && Character.isWhitespace(body.charAt(i))
                && body.charAt(i) != '\n') {
            i++;
        }
        if (i < body.length() && body.charAt(i) == '>') {
            final int newline = body.toString().indexOf('\n', i);
            if (newline >= 0) {
                body.delete(0, newline + 1);
            }
        }
    }

    private StringBuilder getReplyText(Message message) {
        StringBuilder reply = removeReplyFallback(message);
        reply.insert(0, '>');
        for (int i=0;i<reply.length();i++) {
            char c = reply.charAt(i);
            if (c == '\n') {
                reply.insert(i+1, ">");
                i++;
            }
        }

        return reply;
    }

    private StringBuilder removeReplyFallback(Message message) {
        final String presentationBody = message.getBody();
        StringBuilder sb = new StringBuilder(presentationBody);

        List<Element> replyFallback = message.getFallbacks("urn:xmpp:reply:0");
        if (replyFallback.isEmpty()) {
            return sb;
        }

        Element bodyFallback = replyFallback.get(0).findChild("body");
        if (bodyFallback == null) {
            return stripBrokenLeadingReplyQuote(sb);
        }

        final int startCodePoint;
        final int endCodePoint;
        try {
            startCodePoint = Integer.parseInt(bodyFallback.getAttribute("start"));
            endCodePoint = Integer.parseInt(bodyFallback.getAttribute("end"));
        } catch (final NumberFormatException ignored) {
            return stripBrokenLeadingReplyQuote(sb);
        }

        final int totalCodePoints =
                presentationBody.codePointCount(0, presentationBody.length());
        if (startCodePoint < 0 || endCodePoint < startCodePoint || endCodePoint > totalCodePoints) {
            return stripBrokenLeadingReplyQuote(sb);
        }

        sb.replace(
                presentationBody.offsetByCodePoints(0, startCodePoint),
                presentationBody.offsetByCodePoints(0, endCodePoint),
                "");

        return sb;
    }

    private StringBuilder stripBrokenLeadingReplyQuote(final StringBuilder body) {
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i)) && body.charAt(i) != '\n') {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '>') {
            return body;
        }

        int cursor = i;
        boolean consumedAnyQuoteLine = false;
        while (cursor < body.length()) {
            int lineEnd = body.indexOf("\n", cursor);
            if (lineEnd == -1) {
                lineEnd = body.length();
            }
            int lineStart = cursor;
            while (lineStart < lineEnd && Character.isWhitespace(body.charAt(lineStart)) && body.charAt(lineStart) != '\n') {
                lineStart++;
            }
            if (lineStart < lineEnd && body.charAt(lineStart) == '>') {
                consumedAnyQuoteLine = true;
                cursor = lineEnd == body.length() ? body.length() : lineEnd + 1;
                continue;
            }
            break;
        }
        if (!consumedAnyQuoteLine) {
            return body;
        }

        int removeUntil = cursor;
        while (removeUntil < body.length() && (body.charAt(removeUntil) == '\n' || body.charAt(removeUntil) == '\r')) {
            removeUntil++;
        }
        body.delete(0, removeUntil);

        return body;
    }

    public boolean hasMeCommand() {
        return getBody().trim().startsWith(ME_COMMAND);
    }

    public long getMergedTimeSent() {
        long time = this.timeSent;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            time = current.timeSent;
        }
        return time;
    }

    public int getMergedStatus() {
        int status = this.status;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            status = current.status;
        }
        return status;
    }

    public boolean trusted() {
        final var contact = this.getContact();
        return status > STATUS_RECEIVED
                || (contact != null && (contact.showInContactList() || contact.isSelf()));
    }

    public boolean fixCounterpart() {
        final Presences presences = conversation.getContact().getPresences();
        if (counterpart != null && presences.has(Strings.nullToEmpty(counterpart.getResource()))) {
            return true;
        } else if (presences.isEmpty()) {
            counterpart = null;
            return false;
        } else {
            counterpart =
                    PresenceSelector.getNextCounterpart(
                            getContact(), presences.toResourceArray()[0]);
            return true;
        }
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public synchronized void setSecureMessagePayloadMode(
            @Nullable final SecureMessagePayloadMode mode) {
        this.secureMessagePayloadMode = mode;
        if (mode != null) {
            this.body = "";
            if (mode != SecureMessagePayloadMode.PROTECTED) {
                this.verifiedProtectedBody = null;
            }
        }
    }

    @Nullable
    public synchronized SecureMessagePayloadMode getSecureMessagePayloadMode() {
        return secureMessagePayloadMode;
    }

    public synchronized MessagePayloadClassification getPayloadClassification() {
        return MessagePayloadClassification.classify(
                secureMessagePayloadMode,
                type == TYPE_TEXT || type == TYPE_PRIVATE,
                body != null && !body.isEmpty());
    }

    public synchronized boolean hasLegacyPlaintextBody() {
        return getPayloadClassification() == MessagePayloadClassification.LEGACY_PLAINTEXT;
    }

    public synchronized boolean hasProtectedTextPayload() {
        return secureMessagePayloadMode != null;
    }

    public synchronized boolean hasVerifiedProtectedBody() {
        return !moderated && secureMessagePayloadMode == SecureMessagePayloadMode.PROTECTED
                && verifiedProtectedBody != null;
    }

    @Nullable
    public synchronized String getVerifiedProtectedBodyOrNull() {
        return !moderated && secureMessagePayloadMode == SecureMessagePayloadMode.PROTECTED
                ? verifiedProtectedBody
                : null;
    }

    public synchronized void setVerifiedProtectedBody(final String text) {
        if (moderated || retracted) return;
        if (secureMessagePayloadMode != SecureMessagePayloadMode.PROTECTED) {
            throw new IllegalStateException("Protected text is not readable");
        }
        this.body = "";
        this.verifiedProtectedBody = text;
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
        this.fileParams = null;
    }

    /**
     * Atomically switches an already-migrated resident legacy Message to verified protected
     * presentation. The caller must have completed terminal SCS verification before invoking this.
     */
    public synchronized void promoteLegacyToVerifiedProtectedBody(final String text) {
        if (moderated || retracted) return;
        if (text == null) {
            throw new IllegalArgumentException("Verified protected text must not be null");
        }
        if (secureMessagePayloadMode != null
                && secureMessagePayloadMode != SecureMessagePayloadMode.PROTECTED) {
            throw new IllegalStateException("Protected text migration conflicts with message mode");
        }
        this.secureMessagePayloadMode = SecureMessagePayloadMode.PROTECTED;
        this.body = "";
        this.verifiedProtectedBody = text;
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
        this.fileParams = null;
    }

    public synchronized void clearVerifiedProtectedBody() {
        this.verifiedProtectedBody = null;
    }

    public synchronized String getBodyForSecurePublication() {
        if (moderated || retracted) return "";
        return secureMessagePayloadMode == null ? body : verifiedProtectedBody;
    }

    public synchronized void setWireUuid(@Nullable final String wireUuid) {
        this.wireUuid = wireUuid;
    }

    public synchronized String getWireUuid() {
        return wireUuid == null ? uuid : wireUuid;
    }

    public String getEditedId() {
        if (this.edits.isEmpty()) {
            throw new IllegalStateException("Attempting to access unedited message");
        }
        return edits.get(edits.size() - 1).getEditedId();
    }

    public String getEditedIdWireFormat() {
        if (this.edits.isEmpty()) {
            throw new IllegalStateException("Attempting to access unedited message");
        }
        return edits.get(0).getEditedId();
    }

    public void clearPayloads() {
        this.payloads.clear();
    }

    public void addPayload(Element el) {
        if (el == null) return;

        this.payloads.add(el);
    }

    public List<Element> getPayloads() {
        return new ArrayList<>(this.payloads);
    }

    public synchronized boolean isStylingDisabled() {
        for (final Element element : this.payloads) {
            if ("unstyled".equals(element.getName())
                    && Namespace.MESSAGE_STYLING.equals(element.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    public synchronized void setStylingDisabled(final boolean disabled) {
        this.payloads.removeIf(
                element ->
                        "unstyled".equals(element.getName())
                                && Namespace.MESSAGE_STYLING.equals(element.getNamespace()));
        if (disabled) {
            this.payloads.add(new Element("unstyled", Namespace.MESSAGE_STYLING));
        }
    }

    @Nullable
    public synchronized Element getMessageMarkup() {
        for (final Element element : this.payloads) {
            if ("markup".equals(element.getName())
                    && Namespace.MESSAGE_MARKUP.equals(element.getNamespace())) {
                return element;
            }
        }
        return null;
    }

    public synchronized void setMessageMarkup(@Nullable final Element markup) {
        this.payloads.removeIf(
                element ->
                        "markup".equals(element.getName())
                                && Namespace.MESSAGE_MARKUP.equals(element.getNamespace()));
        if (markup != null) {
            this.payloads.add(markup);
        }
    }

    public List<Element> getFallbacks(String... includeFor) {
        List<Element> fallbacks = new ArrayList<>();

        if (this.payloads == null) return fallbacks;

        for (Element el : this.payloads) {
            if (el.getName().equals("fallback") && el.getNamespace().equals("urn:xmpp:fallback:0")) {
                final String fallbackFor = el.getAttribute("for");
                if (fallbackFor == null) continue;
                for (String includeOne : includeFor) {
                    if (fallbackFor.equals(includeOne)) {
                        fallbacks.add(el);
                        break;
                    }
                }
            }
        }

        return fallbacks;
    }

    public synchronized void clearFallbacks(String... includeFor) {
        this.payloads.removeAll(getFallbacks(includeFor));
    }

    public void setOob(boolean isOob) {
        this.oob = isOob;
    }

    public synchronized void setSecureMediaPresentationMetadata(
            @Nullable final String mimeType,
            @Nullable final String fileName,
            @Nullable final Long sizeBytes) {
        this.secureMediaMimeType = mimeType;
        this.secureMediaFileName = fileName;
        this.secureMediaSizeBytes = sizeBytes;
        this.secureMediaPresentationMetadataResolved = true;
    }

    public synchronized boolean isSecureMediaPresentationMetadataResolved() {
        return secureMediaPresentationMetadataResolved;
    }

    @Nullable
    public synchronized String getSecureMediaMimeType() {
        return secureMediaMimeType;
    }

    @Nullable
    public synchronized String getSecureMediaFileName() {
        return secureMediaFileName;
    }

    @Nullable
    public synchronized Long getSecureMediaSizeBytes() {
        return secureMediaSizeBytes;
    }

    public synchronized String getMimeType() {
        final String securePresentationMime =
                MimeUtils.resolvePresentationMime(secureMediaMimeType, secureMediaFileName);
        if (securePresentationMime != null && !securePresentationMime.isEmpty()) {
            return securePresentationMime;
        }
        String extension;
        if (relativeFilePath != null) {
            extension = MimeUtils.extractRelevantExtension(relativeFilePath);
        } else {
            final String url = URL.tryParse(body.split("\n")[0]);
            if (url == null) {
                return null;
            }
            extension = MimeUtils.extractRelevantExtension(url);
        }
        return MimeUtils.guessMimeTypeFromExtension(extension);
    }

    public synchronized boolean treatAsDownloadable() {
        if (treatAsDownloadable == null) {
            treatAsDownloadable = MessageUtils.treatAsDownloadable(getBody(), this.oob);
        }
        return treatAsDownloadable;
    }

    public synchronized boolean bodyIsOnlyEmojis() {
        if (isEmojisOnly == null) {
            isEmojisOnly = Emoticons.isOnlyEmoji(getBody().replaceAll("\\s", ""));
        }
        return isEmojisOnly;
    }

    public synchronized boolean isGeoUri() {
        if (isGeoUri == null) {
            isGeoUri = GeoHelper.GEO_URI.matcher(getBody()).matches();
        }
        return isGeoUri;
    }

    public synchronized void resetFileParams() {
        this.fileParams = null;
    }

    public synchronized FileParams getFileParams() {
        if (fileParams == null) {
            fileParams = new FileParams();
            if (this.transferable != null) {
                fileParams.size = this.transferable.getFileSize();
            }
            final String[] parts = body == null ? new String[0] : body.split("\\|");
            switch (parts.length) {
                case 1:
                    try {
                        fileParams.size = Long.parseLong(parts[0]);
                    } catch (final NumberFormatException e) {
                        fileParams.url = URL.tryParse(parts[0]);
                    }
                    break;
                case 5:
                    fileParams.runtime = parseInt(parts[4]);
                case 4:
                    fileParams.width = parseInt(parts[2]);
                    fileParams.height = parseInt(parts[3]);
                case 2:
                    fileParams.url = URL.tryParse(parts[0]);
                    fileParams.size = Longs.tryParse(parts[1]);
                    break;
                case 3:
                    fileParams.size = Longs.tryParse(parts[0]);
                    fileParams.width = parseInt(parts[1]);
                    fileParams.height = parseInt(parts[2]);
                    break;
            }
        }
        return fileParams;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void untie() {
        this.mNextMessage = null;
        this.mPreviousMessage = null;
    }

    public boolean isPrivateMessage() {
        return type == TYPE_PRIVATE || type == TYPE_PRIVATE_FILE;
    }

    public boolean isFileOrImage() {
        return type == TYPE_FILE || type == TYPE_IMAGE || type == TYPE_PRIVATE_FILE;
    }

    public boolean isTypeText() {
        return type == TYPE_TEXT || type == TYPE_PRIVATE;
    }

    public boolean hasFileOnRemoteHost() {
        return isFileOrImage() && getFileParams().url != null;
    }

    public boolean needsUploading() {
        return isFileOrImage() && getFileParams().url == null;
    }

    public static class FileParams {
        public String url;
        public Long size = null;
        public int width = 0;
        public int height = 0;
        public int runtime = 0;

        public long getSize() {
            return size == null ? 0 : size;
        }
    }

    public void setFingerprint(String fingerprint) {
        this.axolotlFingerprint = fingerprint;
    }

    public String getFingerprint() {
        return axolotlFingerprint;
    }

    public boolean isTrusted() {
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        final FingerprintStatus s =
                axolotlService != null
                        ? axolotlService.getFingerprintTrust(axolotlFingerprint)
                        : null;
        return s != null && s.isTrusted();
    }

    private int getPreviousEncryption() {
        for (Message iterator = this.prev(); iterator != null; iterator = iterator.prev()) {
            if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                continue;
            }
            return iterator.getEncryption();
        }
        return ENCRYPTION_NONE;
    }

    private int getNextEncryption() {
        if (this.conversation instanceof Conversation c) {
            for (Message iterator = this.next(); iterator != null; iterator = iterator.next()) {
                if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                    continue;
                }
                return iterator.getEncryption();
            }
            return c.getNextEncryption();
        } else {
            throw new AssertionError(
                    "This should never be called since isInValidSession should be disabled for"
                            + " stubs");
        }
    }

    public boolean isValidInSession() {
        int pastEncryption = getCleanedEncryption(this.getPreviousEncryption());
        int futureEncryption = getCleanedEncryption(this.getNextEncryption());

        boolean inUnencryptedSession =
                pastEncryption == ENCRYPTION_NONE
                        || futureEncryption == ENCRYPTION_NONE
                        || pastEncryption != futureEncryption;

        return inUnencryptedSession || getCleanedEncryption(this.getEncryption()) == pastEncryption;
    }

    private static int getCleanedEncryption(int encryption) {
        if (encryption == ENCRYPTION_DECRYPTED || encryption == ENCRYPTION_DECRYPTION_FAILED) {
            return ENCRYPTION_PGP;
        }
        if (encryption == ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                || encryption == ENCRYPTION_AXOLOTL_FAILED) {
            return ENCRYPTION_AXOLOTL;
        }
        return encryption;
    }

    public static void configurePrivateMessage(final Message message) {
        configurePrivateMessage(message, false);
    }

    public static boolean configurePrivateFileMessage(final Message message) {
        return configurePrivateMessage(message, true);
    }

    private static boolean configurePrivateMessage(final Message message, final boolean isFile) {
        if (message.conversation instanceof Conversation conversation) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                final Jid nextCounterpart = conversation.getNextCounterpart();
                return configurePrivateMessage(conversation, message, nextCounterpart, isFile);
            }
        }
        return false;
    }

    public static void configurePrivateMessage(final Message message, final Jid counterpart) {
        if (message.conversation instanceof Conversation conversation) {
            configurePrivateMessage(conversation, message, counterpart, false);
        }
    }

    private static boolean configurePrivateMessage(
            final Conversation conversation,
            final Message message,
            final Jid counterpart,
            final boolean isFile) {
        if (counterpart == null) {
            return false;
        }
        message.setCounterpart(counterpart);
        final var mucOptions = conversation.getMucOptions();
        if (counterpart.equals(mucOptions.getSelf().getFullJid())) {
            message.setTrueCounterpart(conversation.getAccount().getJid().asBareJid());
        } else {
            final var user = mucOptions.findUserByFullJid(counterpart);
            if (user != null) {
                message.setTrueCounterpart(user.getRealJid());
                message.setOccupantId(user.getOccupantId());
            }
        }
        message.setType(isFile ? Message.TYPE_PRIVATE_FILE : Message.TYPE_PRIVATE);
        return true;
    }
}
