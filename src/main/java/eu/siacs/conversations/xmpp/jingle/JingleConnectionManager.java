package eu.siacs.conversations.xmpp.jingle;

import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Collections2;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.webrtc.PeerConnection;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.stanzas.Content;
import eu.siacs.conversations.xmpp.jingle.stanzas.GenericDescription;
import eu.siacs.conversations.xmpp.jingle.stanzas.Propose;
import eu.siacs.conversations.xmpp.jingle.stanzas.Reason;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import eu.siacs.conversations.xmpp.jingle.transports.InbandBytestreamsTransport;
import eu.siacs.conversations.xmpp.jingle.transports.Transport;
import im.conversations.android.xmpp.model.jingle.Jingle;
import im.conversations.android.xmpp.model.stanza.Iq;

public class JingleConnectionManager extends AbstractConnectionManager {
    public static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE =
            Executors.newSingleThreadScheduledExecutor();
    private final HashMap<RtpSessionProposal, DeviceDiscoveryState> rtpSessionProposals =
            new HashMap<>();
    private final ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>
            connections = new ConcurrentHashMap<>();
    private final Set<String> accountCleanupInProgress = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, IceServerCacheEntry> iceServerCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<
                    String, SettableFuture<List<PeerConnection.IceServer>>>
            iceServerFetches = new ConcurrentHashMap<>();

    private final Cache<PersistableSessionId, TerminatedRtpSession> terminatedSessions =
            CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build();

    public JingleConnectionManager(XmppConnectionService service) {
        super(service);
    }

    public void prefetchCallDependencies(final Account account) {
        if (!isUsingClearNet(account)) {
            return;
        }
        SCHEDULED_EXECUTOR_SERVICE.execute(
                () -> WebRTCWrapper.warmUp(mXmppConnectionService));
        Futures.addCallback(
                getIceServers(account, false),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final List<PeerConnection.IceServer> iceServers) {
                        Log.d(
                                Config.LOGTAG,
                                "call prefetch ready: ICE servers=" + iceServers.size());
                    }

                    @Override
                    public void onFailure(final Throwable throwable) {
                        Log.d(Config.LOGTAG, "call ICE prefetch failed", throwable);
                    }
                },
                MoreExecutors.directExecutor());
    }

    ListenableFuture<List<PeerConnection.IceServer>> getIceServers(
            final Account account, final boolean forceRefresh) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null || !connection.getFeatures().externalServiceDiscovery()) {
            return Futures.immediateFuture(Collections.emptyList());
        }

        final String key = account.getUuid();
        final long now = SystemClock.elapsedRealtime();
        final IceServerCacheEntry stale = iceServerCache.get(key);
        if (!forceRefresh) {
            final IceServerCacheEntry cached = stale;
            if (cached != null && cached.expiresAtElapsedMs > now) {
                Log.d(
                        Config.LOGTAG,
                        "call ICE cache hit: servers=" + cached.iceServers.size());
                return Futures.immediateFuture(cached.iceServers);
            }
        }

        final SettableFuture<List<PeerConnection.IceServer>> created = SettableFuture.create();
        final SettableFuture<List<PeerConnection.IceServer>> inFlight =
                iceServerFetches.putIfAbsent(key, created);
        if (inFlight != null) {
            return inFlight;
        }

        final long started = SystemClock.elapsedRealtime();
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(account.getDomain());
        request.addChild("services", Namespace.EXTERNAL_SERVICE_DISCOVERY);
        try {
            mXmppConnectionService.sendIqPacket(
                    account,
                    request,
                    response -> {
                        final boolean success = response.getType() == Iq.Type.RESULT;
                        final List<PeerConnection.IceServer> parsed;
                        if (success) {
                            parsed =
                                    Collections.unmodifiableList(
                                            new ArrayList<>(IceServers.parse(response)));
                            final long ttl =
                                    parsed.isEmpty()
                                            ? Config.ICE_SERVER_EMPTY_CACHE_TTL
                                            : Config.ICE_SERVER_CACHE_TTL;
                            iceServerCache.put(
                                    key,
                                    new IceServerCacheEntry(
                                            parsed, SystemClock.elapsedRealtime() + ttl));
                        } else if (stale != null && !stale.iceServers.isEmpty()) {
                            // A TURN refresh timing out during a cold-call recovery must not throw
                            // away credentials that were already good enough for the first attempt.
                            parsed = stale.iceServers;
                            Log.d(
                                    Config.LOGTAG,
                                    "call ICE refresh failed; using stale cached services="
                                            + parsed.size());
                        } else {
                            parsed = Collections.emptyList();
                        }
                        iceServerFetches.remove(key, created);
                        created.set(parsed);
                        Log.d(
                                Config.LOGTAG,
                                "call ICE discovery finished: servers="
                                        + parsed.size()
                                        + " in "
                                        + (SystemClock.elapsedRealtime() - started)
                                        + " ms"
                                        + (forceRefresh ? " (refresh)" : "")
                                        + (success ? "" : " result=" + response.getType()));
                    },
                    Config.ICE_SERVER_DISCOVERY_TIMEOUT);
        } catch (final RuntimeException e) {
            iceServerFetches.remove(key, created);
            created.setException(e);
        }
        return created;
    }

    static String nextRandomId() {
        final byte[] id = new byte[16];
        new SecureRandom().nextBytes(id);
        return Base64.encodeToString(id, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    public void deliverPacket(final Account account, final Iq packet) {
        final var jingle = packet.getExtension(Jingle.class);
        Preconditions.checkNotNull(
                jingle, "Passed iq packet w/o jingle extension to Connection Manager");
        final String sessionId = jingle.getSessionId();
        final Jingle.Action action = jingle.getAction();
        if (sessionId == null) {
            respondWithJingleError(account, packet, "unknown-session", "item-not-found", "cancel");
            return;
        }
        if (action == null) {
            respondWithJingleError(account, packet, null, "bad-request", "cancel");
            return;
        }
        final AbstractJingleConnection.Id id =
                AbstractJingleConnection.Id.of(account, packet, jingle);
        final AbstractJingleConnection existingJingleConnection = connections.get(id);
        if (existingJingleConnection != null) {
            existingJingleConnection.deliverPacket(packet);
        } else if (action == Jingle.Action.SESSION_INITIATE) {
            final Jid from = packet.getFrom();
            final Content content = jingle.getJingleContent();
            final String descriptionNamespace =
                    content == null ? null : content.getDescriptionNamespace();
            final AbstractJingleConnection connection;
            if (Namespace.JINGLE_APPS_FILE_TRANSFER.equals(descriptionNamespace)) {
                if (accountCleanupInProgress.contains(account.getUuid())) {
                    sendSessionTerminate(account, packet, id);
                    return;
                }
                connection = new JingleFileTransferConnection(this, id, from);
            } else if (Namespace.JINGLE_APPS_RTP.equals(descriptionNamespace)
                    && isUsingClearNet(account)) {
                final boolean sessionEnded =
                        this.terminatedSessions.asMap().containsKey(PersistableSessionId.of(id));
                final boolean stranger =
                        isWithStrangerAndStrangerNotificationsAreOff(account, id.with);
                final boolean busy = isBusy();
                if (busy || sessionEnded || stranger) {
                    Log.d(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid()
                                    + ": rejected session with "
                                    + id.with
                                    + " because busy. sessionEnded="
                                    + sessionEnded
                                    + ", stranger="
                                    + stranger);
                    sendSessionTerminate(account, packet, id);
                    if (busy || stranger) {
                        writeLogMissedIncoming(
                                account,
                                id.with,
                                id.sessionId,
                                null,
                                System.currentTimeMillis(),
                                stranger);
                    }
                    return;
                }
                connection = new JingleRtpConnection(this, id, from);
            } else {
                respondWithJingleError(
                        account, packet, "unsupported-info", "feature-not-implemented", "cancel");
                return;
            }
            connections.put(id, connection);
            mXmppConnectionService.updateConversationUi();
            connection.deliverPacket(packet);
            if (connection instanceof JingleRtpConnection rtpConnection) {
                addNewIncomingCall(rtpConnection);
            }
        } else {
            Log.d(Config.LOGTAG, "unable to route jingle packet: " + packet);
            respondWithJingleError(account, packet, "unknown-session", "item-not-found", "cancel");
        }
    }

    private void addNewIncomingCall(final JingleRtpConnection rtpConnection) {
        if (rtpConnection.isTerminated()) {
            Log.d(
                    Config.LOGTAG,
                    "skip call integration because something must have gone during initiate");
            return;
        }
        if (CallIntegrationConnectionService.addNewIncomingCall(
                mXmppConnectionService, rtpConnection.getId())) {
            return;
        }
        rtpConnection.integrationFailure();
    }

    private void sendSessionTerminate(
            final Account account, final Iq request, final AbstractJingleConnection.Id id) {
        mXmppConnectionService.sendIqPacket(
                account, request.generateResponse(Iq.Type.RESULT), null);
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(id.with);
        final var sessionTermination =
                iq.addExtension(new Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId));
        sessionTermination.setReason(Reason.BUSY, null);
        mXmppConnectionService.sendIqPacket(account, iq, null);
    }

    private boolean isUsingClearNet(final Account account) {
        return !account.isOnion() && !mXmppConnectionService.useTorToConnect();
    }

    public boolean isBusy() {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (connection.isTerminated() && rtpConnection.getCallIntegration().isDestroyed()) {
                    continue;
                }
                return true;
            }
        }
        synchronized (this.rtpSessionProposals) {
            return this.rtpSessionProposals.containsValue(DeviceDiscoveryState.DISCOVERED)
                    || this.rtpSessionProposals.containsValue(DeviceDiscoveryState.SEARCHING)
                    || this.rtpSessionProposals.containsValue(
                            DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED);
        }
    }

    public boolean hasJingleRtpConnection(final Account account) {
        for (AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (rtpConnection.isTerminated()) {
                    continue;
                }
                if (rtpConnection.id.account == account) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<RtpSessionProposal> findMatchingSessionProposal(
            final Account account, final Jid with, final Set<Media> media) {
        synchronized (this.rtpSessionProposals) {
            for (Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                final DeviceDiscoveryState state = entry.getValue();
                final boolean openProposal =
                        state == DeviceDiscoveryState.DISCOVERED
                                || state == DeviceDiscoveryState.SEARCHING
                                || state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED;
                if (openProposal
                        && proposal.account == account
                        && proposal.with.equals(with.asBareJid())
                        && proposal.media.equals(media)) {
                    return Optional.of(proposal);
                }
            }
        }
        return Optional.absent();
    }

    private boolean hasMatchingRtpSession(
            final Account account, final Jid with, final Set<Media> media) {
        for (AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (rtpConnection.isTerminated()) {
                    continue;
                }
                if (rtpConnection.getId().account == account
                        && rtpConnection.getId().with.asBareJid().equals(with.asBareJid())
                        && rtpConnection.getMedia().equals(media)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWithStrangerAndStrangerNotificationsAreOff(final Account account, Jid with) {
        final boolean notifyForStrangers =
                mXmppConnectionService.getNotificationService().notificationsFromStrangers();
        if (notifyForStrangers) {
            return false;
        }
        final Contact contact = account.getRoster().getContact(with);
        return !contact.showInContactList();
    }

    ScheduledFuture<?> schedule(
            final Runnable runnable, final long delay, final TimeUnit timeUnit) {
        return SCHEDULED_EXECUTOR_SERVICE.schedule(runnable, delay, timeUnit);
    }

    void respondWithJingleError(
            final Account account,
            final Iq original,
            final String jingleCondition,
            final String condition,
            final String conditionType) {
        final Iq response = original.generateResponse(Iq.Type.ERROR);
        final Element error = response.addChild("error");
        error.setAttribute("type", conditionType);
        error.addChild(condition, "urn:ietf:params:xml:ns:xmpp-stanzas");
        if (jingleCondition != null) {
            error.addChild(jingleCondition, Namespace.JINGLE_ERRORS);
        }
        account.getXmppConnection().sendIqPacket(response, null);
    }

    public void deliverMessage(
            final Account account,
            final Jid to,
            final Jid from,
            final Element message,
            String remoteMsgId,
            String serverMsgId,
            long timestamp) {
        Preconditions.checkArgument(Namespace.JINGLE_MESSAGE.equals(message.getNamespace()));
        final String sessionId = message.getAttribute("id");
        if (sessionId == null) {
            return;
        }
        if ("accept".equals(message.getName())) {
            for (AbstractJingleConnection connection : connections.values()) {
                if (connection instanceof JingleRtpConnection rtpConnection) {
                    final AbstractJingleConnection.Id id = connection.getId();
                    if (id.account == account && id.sessionId.equals(sessionId)) {
                        rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
                        return;
                    }
                }
            }
            return;
        }
        final boolean fromSelf = from.asBareJid().equals(account.getJid().asBareJid());
        // XEP version 0.6.0 sends proceed, reject, ringing to bare jid
        final boolean addressedDirectly = to != null && to.equals(account.getJid());
        final AbstractJingleConnection.Id id;
        if (fromSelf) {
            if (to != null && to.isFullJid()) {
                id = AbstractJingleConnection.Id.of(account, to, sessionId);
            } else {
                return;
            }
        } else {
            id = AbstractJingleConnection.Id.of(account, from, sessionId);
        }
        final AbstractJingleConnection existingJingleConnection = connections.get(id);
        if (existingJingleConnection != null) {
            if (existingJingleConnection instanceof JingleRtpConnection) {
                ((JingleRtpConnection) existingJingleConnection)
                        .deliveryMessage(from, message, serverMsgId, timestamp);
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": "
                                + existingJingleConnection.getClass().getName()
                                + " does not support jingle messages");
            }
            return;
        }

        if (fromSelf) {
            if ("proceed".equals(message.getName())) {
                final Conversation c =
                        mXmppConnectionService.findOrCreateConversation(
                                account, id.with, null, false, false, false, null);
                final Message previousBusy = c.findRtpSession(sessionId, Message.STATUS_RECEIVED);
                if (previousBusy != null) {
                    previousBusy.setBody(new RtpSessionStatus(true, 0).toString());
                    if (serverMsgId != null) {
                        previousBusy.setServerMsgId(serverMsgId);
                    }
                    previousBusy.setTime(timestamp);
                    mXmppConnectionService.updateMessage(previousBusy, true);
                    Log.d(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid()
                                    + ": updated previous busy because call got picked up by"
                                    + " another device");
                    mXmppConnectionService.getNotificationService().clearMissedCall(previousBusy);
                    return;
                }
            }
            // TODO handle reject for cases where we don’t have carbon copies (normally reject is to
            // be sent to own bare jid as well)
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": ignore jingle message from self");
            return;
        }

        if ("propose".equals(message.getName())) {
            final Propose propose = Propose.upgrade(message);
            final List<GenericDescription> descriptions = propose.getDescriptions();
            final Collection<RtpDescription> rtpDescriptions =
                    Collections2.transform(
                            Collections2.filter(descriptions, d -> d instanceof RtpDescription),
                            input -> (RtpDescription) input);
            if (rtpDescriptions.size() > 0
                    && rtpDescriptions.size() == descriptions.size()
                    && isUsingClearNet(account)) {
                final Collection<Media> media =
                        Collections2.transform(rtpDescriptions, RtpDescription::getMedia);
                if (media.contains(Media.UNKNOWN)) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": encountered unknown media in session proposal. "
                                    + propose);
                    return;
                }
                final Optional<RtpSessionProposal> matchingSessionProposal =
                        findMatchingSessionProposal(account, id.with, ImmutableSet.copyOf(media));
                if (matchingSessionProposal.isPresent()) {
                    final String ourSessionId = matchingSessionProposal.get().sessionId;
                    final String theirSessionId = id.sessionId;
                    if (ComparisonChain.start()
                                    .compare(ourSessionId, theirSessionId)
                                    .compare(account.getJid().toString(), id.with.toString())
                                    .result()
                            > 0) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": our session lost tie break. automatically accepting"
                                        + " their session. winning Session="
                                        + theirSessionId);
                        // TODO a retract for this reason should probably include some indication of
                        // tie break
                        retractSessionProposal(matchingSessionProposal.get());
                        final JingleRtpConnection rtpConnection =
                                new JingleRtpConnection(this, id, from);
                        this.connections.put(id, rtpConnection);
                        rtpConnection.setProposedMedia(ImmutableSet.copyOf(media));
                        rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
                        addNewIncomingCall(rtpConnection);
                        // TODO actually do the automatic accept?!
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": our session won tie break. waiting for other party to"
                                        + " accept. winningSession="
                                        + ourSessionId);
                        // TODO reject their session with <tie-break/>?
                    }
                    return;
                }
                final boolean stranger =
                        isWithStrangerAndStrangerNotificationsAreOff(account, id.with);
                if (isBusy() || stranger) {
                    writeLogMissedIncoming(
                            account,
                            id.with.asBareJid(),
                            id.sessionId,
                            serverMsgId,
                            timestamp,
                            stranger);
                    if (stranger) {
                        Log.d(
                                Config.LOGTAG,
                                id.account.getJid().asBareJid()
                                        + ": ignoring call proposal from stranger "
                                        + id.with);
                        return;
                    }
                    final int activeDevices = account.activeDevicesWithRtpCapability();
                    Log.d(Config.LOGTAG, "active devices with rtp capability: " + activeDevices);
                    if (activeDevices == 0) {
                        final var reject =
                                mXmppConnectionService
                                        .getMessageGenerator()
                                        .sessionReject(from, sessionId);
                        mXmppConnectionService.sendMessagePacket(account, reject);
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                id.account.getJid().asBareJid()
                                        + ": ignoring proposal because busy on this device but"
                                        + " there are other devices");
                    }
                } else {
                    final JingleRtpConnection rtpConnection =
                            new JingleRtpConnection(this, id, from);
                    this.connections.put(id, rtpConnection);
                    rtpConnection.setProposedMedia(ImmutableSet.copyOf(media));
                    rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
                    addNewIncomingCall(rtpConnection);
                }
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": unable to react to proposed session with "
                                + rtpDescriptions.size()
                                + " rtp descriptions of "
                                + descriptions.size()
                                + " total descriptions");
            }
        } else if (addressedDirectly && "proceed".equals(message.getName())) {
            synchronized (rtpSessionProposals) {
                final RtpSessionProposal proposal =
                        getRtpSessionProposal(account, from.asBareJid(), sessionId);
                if (proposal != null) {
                    rtpSessionProposals.remove(proposal);
                    final JingleRtpConnection rtpConnection =
                            new JingleRtpConnection(
                                    this, id, account.getJid(), proposal.callIntegration);
                    rtpConnection.setProposedMedia(proposal.media);
                    this.connections.put(id, rtpConnection);
                    rtpConnection.transitionOrThrow(AbstractJingleConnection.State.PROPOSED);
                    rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": no rtp session ("
                                    + sessionId
                                    + ") proposal found for "
                                    + from
                                    + " to deliver proceed");
                    if (remoteMsgId == null) {
                        return;
                    }
                    final var errorMessage =
                            new im.conversations.android.xmpp.model.stanza.Message();
                    errorMessage.setTo(from);
                    errorMessage.setId(remoteMsgId);
                    errorMessage.setType(
                            im.conversations.android.xmpp.model.stanza.Message.Type.ERROR);
                    final Element error = errorMessage.addChild("error");
                    error.setAttribute("code", "404");
                    error.setAttribute("type", "cancel");
                    error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas");
                    mXmppConnectionService.sendMessagePacket(account, errorMessage);
                }
            }
        } else if (addressedDirectly && "reject".equals(message.getName())) {
            final RtpSessionProposal proposal =
                    getRtpSessionProposal(account, from.asBareJid(), sessionId);
            synchronized (rtpSessionProposals) {
                if (proposal != null) {
                    setTerminalSessionState(proposal, RtpEndUserState.DECLINED_OR_BUSY);
                    rtpSessionProposals.remove(proposal);
                    proposal.callIntegration.busy();
                    writeLogMissedOutgoing(
                            account, proposal.with, proposal.sessionId, serverMsgId, timestamp);
                    mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                            account,
                            proposal.with,
                            proposal.sessionId,
                            RtpEndUserState.DECLINED_OR_BUSY);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": no rtp session proposal found for "
                                    + from
                                    + " to deliver reject");
                }
            }
        } else if (addressedDirectly && "ringing".equals(message.getName())) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": " + from + " started ringing");
            updateProposedSessionDiscovered(
                    account, from, sessionId, DeviceDiscoveryState.DISCOVERED);
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid()
                            + ": received out of order jingle message from="
                            + from
                            + ", message="
                            + message
                            + ", addressedDirectly="
                            + addressedDirectly);
        }
    }

    private RtpSessionProposal getRtpSessionProposal(
            final Account account, Jid from, String sessionId) {
        for (RtpSessionProposal rtpSessionProposal : rtpSessionProposals.keySet()) {
            if (rtpSessionProposal.sessionId.equals(sessionId)
                    && rtpSessionProposal.with.equals(from)
                    && rtpSessionProposal.account.getJid().equals(account.getJid())) {
                return rtpSessionProposal;
            }
        }
        return null;
    }

    private void writeLogMissedOutgoing(
            final Account account,
            Jid with,
            final String sessionId,
            String serverMsgId,
            long timestamp) {
        final Conversation conversation =
                mXmppConnectionService.findOrCreateConversation(
                        account, with.asBareJid(), null, false, false, false, null);
        final Message message =
                new Message(conversation, Message.STATUS_SEND, Message.TYPE_RTP_SESSION, sessionId);
        message.setBody(new RtpSessionStatus(false, 0).toString());
        message.setServerMsgId(serverMsgId);
        message.setTime(timestamp);
        writeMessage(message);
    }

    private void writeLogMissedIncoming(
            final Account account,
            final Jid with,
            final String sessionId,
            final String serverMsgId,
            final long timestamp,
            final boolean stranger) {
        final Conversation conversation =
                mXmppConnectionService.findOrCreateConversation(
                        account, with.asBareJid(), null, false, false, false, null);
        final Message message =
                new Message(
                        conversation, Message.STATUS_RECEIVED, Message.TYPE_RTP_SESSION, sessionId);
        message.setBody(new RtpSessionStatus(false, 0).toString());
        message.setServerMsgId(serverMsgId);
        message.setTime(timestamp);
        message.setCounterpart(with);
        writeMessage(message);
        if (stranger) {
            return;
        }
        mXmppConnectionService.getNotificationService().pushMissedCallNow(message);
    }

    private void writeMessage(final Message message) {
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation) {
            ((Conversation) conversational).add(message);
            mXmppConnectionService.databaseBackend.createMessage(message);
            mXmppConnectionService.updateConversationUi();
        } else {
            throw new IllegalStateException("Somehow the conversation in a message was a stub");
        }
    }

    public void startJingleFileTransfer(final Message message) {
        if (accountCleanupInProgress.contains(
                message.getConversation().getAccount().getUuid())) {
            return;
        }
        Preconditions.checkArgument(
                message.isFileOrImage(), "Message is not of type file or image");
        final Transferable old = message.getTransferable();
        if (old != null) {
            old.cancel();
        }
        final JingleFileTransferConnection connection =
                new JingleFileTransferConnection(this, message);
        this.connections.put(connection.getId(), connection);
        connection.sendSessionInitialize();
    }

    public void beginAccountCleanup(final Account account) {
        accountCleanupInProgress.add(account.getUuid());
        for (final AbstractJingleConnection connection : new ArrayList<>(connections.values())) {
            if (connection instanceof JingleFileTransferConnection fileTransfer
                    && connection.getId().account == account) {
                fileTransfer.cancel();
            }
        }
    }

    public void endAccountCleanup(final Account account) {
        accountCleanupInProgress.remove(account.getUuid());
    }

    public Optional<OngoingRtpSession> getOngoingRtpConnection(final Contact contact) {
        for (final Map.Entry<AbstractJingleConnection.Id, AbstractJingleConnection> entry :
                this.connections.entrySet()) {
            if (entry.getValue() instanceof JingleRtpConnection jingleRtpConnection) {
                if (jingleRtpConnection.isTerminated()) {
                    continue;
                }
                final AbstractJingleConnection.Id id = entry.getKey();
                if (id.account == contact.getAccount()
                        && id.with.asBareJid().equals(contact.getJid().asBareJid())) {
                    return Optional.of(jingleRtpConnection);
                }
            }
        }
        synchronized (this.rtpSessionProposals) {
            for (final Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (proposal.account == contact.getAccount()
                        && contact.getJid().asBareJid().equals(proposal.with)) {
                    final DeviceDiscoveryState preexistingState = entry.getValue();
                    if (preexistingState != null
                            && preexistingState != DeviceDiscoveryState.FAILED) {
                        return Optional.of(proposal);
                    }
                }
            }
        }
        return Optional.absent();
    }

    public JingleRtpConnection getOngoingRtpConnection() {
        for (final AbstractJingleConnection jingleConnection : this.connections.values()) {
            if (jingleConnection instanceof JingleRtpConnection jingleRtpConnection) {
                if (jingleRtpConnection.isTerminated()) {
                    continue;
                }
                return jingleRtpConnection;
            }
        }
        return null;
    }

    void finishConnectionOrThrow(final AbstractJingleConnection connection) {
        final AbstractJingleConnection.Id id = connection.getId();
        if (this.connections.remove(id) == null) {
            throw new IllegalStateException(
                    String.format("Unable to finish connection with id=%s", id));
        }
        // update chat UI to remove 'ongoing call' icon
        mXmppConnectionService.updateConversationUi();
    }

    public boolean fireJingleRtpConnectionStateUpdates() {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection jingleRtpConnection) {
                if (jingleRtpConnection.isTerminated()) {
                    continue;
                }
                jingleRtpConnection.fireStateUpdate();
                return true;
            }
        }
        return false;
    }

    public void retractSessionProposal(final Account account, final Jid with) {
        synchronized (this.rtpSessionProposals) {
            RtpSessionProposal matchingProposal = null;
            for (RtpSessionProposal proposal : this.rtpSessionProposals.keySet()) {
                if (proposal.account == account && with.asBareJid().equals(proposal.with)) {
                    matchingProposal = proposal;
                    break;
                }
            }
            if (matchingProposal != null) {
                retractSessionProposal(matchingProposal, false);
            }
        }
    }

    private void retractSessionProposal(final RtpSessionProposal rtpSessionProposal) {
        retractSessionProposal(rtpSessionProposal, true);
    }

    private void retractSessionProposal(
            final RtpSessionProposal rtpSessionProposal, final boolean refresh) {
        final Account account = rtpSessionProposal.account;
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": retracting rtp session proposal with "
                        + rtpSessionProposal.with);
        this.rtpSessionProposals.remove(rtpSessionProposal);
        rtpSessionProposal.callIntegration.retracted();
        if (refresh) {
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                    account,
                    rtpSessionProposal.with,
                    rtpSessionProposal.sessionId,
                    RtpEndUserState.RETRACTED);
        }
        final var messagePacket =
                mXmppConnectionService.getMessageGenerator().sessionRetract(rtpSessionProposal);
        writeLogMissedOutgoing(
                account,
                rtpSessionProposal.with,
                rtpSessionProposal.sessionId,
                null,
                System.currentTimeMillis());
        mXmppConnectionService.sendMessagePacket(account, messagePacket);
    }

    public JingleRtpConnection initializeRtpSession(
            final Account account, final Jid with, final Set<Media> media) {
        prefetchCallDependencies(account);
        final AbstractJingleConnection.Id id = AbstractJingleConnection.Id.of(account, with);
        final JingleRtpConnection rtpConnection =
                new JingleRtpConnection(this, id, account.getJid());
        rtpConnection.setProposedMedia(media);
        rtpConnection.getCallIntegration().startAudioRouting();
        this.connections.put(id, rtpConnection);
        rtpConnection.sendSessionInitiate();
        return rtpConnection;
    }

    public @Nullable RtpSessionProposal proposeJingleRtpSession(
            final Account account, final Jid with, final Set<Media> media) {
        synchronized (this.rtpSessionProposals) {
            for (final Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (proposal.account == account && with.asBareJid().equals(proposal.with)) {
                    final DeviceDiscoveryState preexistingState = entry.getValue();
                    if (preexistingState != null
                            && preexistingState != DeviceDiscoveryState.FAILED) {
                        final RtpEndUserState endUserState = preexistingState.toEndUserState();
                        mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                                account, with, proposal.sessionId, endUserState);
                        return proposal;
                    }
                }
            }
            if (isBusy()) {
                if (hasMatchingRtpSession(account, with, media)) {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring request to propose jingle session because the other party"
                                    + " already created one for us");
                    // TODO return something that we can parse the connection of of
                    return null;
                }
                throw new IllegalStateException(
                        "There is already a running RTP session. This should have been caught by"
                                + " the UI");
            }
            prefetchCallDependencies(account);
            final CallIntegration callIntegration =
                    new CallIntegration(mXmppConnectionService.getApplicationContext());
            callIntegration.setVideoState(
                    Media.audioOnly(media)
                            ? VideoProfile.STATE_AUDIO_ONLY
                            : VideoProfile.STATE_BIDIRECTIONAL);
            callIntegration.setAddress(
                    CallIntegration.address(with.asBareJid()), TelecomManager.PRESENTATION_ALLOWED);
            final var contact = account.getRoster().getContact(with);
            callIntegration.setCallerDisplayName(
                    contact.getDisplayName(), TelecomManager.PRESENTATION_ALLOWED);
            callIntegration.setInitialized();
            callIntegration.setInitialAudioDevice(CallIntegration.initialAudioDevice(media));
            callIntegration.startAudioRouting();
            final RtpSessionProposal proposal =
                    RtpSessionProposal.of(account, with.asBareJid(), media, callIntegration);
            callIntegration.setCallback(new ProposalStateCallback(proposal));
            this.rtpSessionProposals.put(proposal, DeviceDiscoveryState.SEARCHING);
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                    account, proposal.with, proposal.sessionId, RtpEndUserState.FINDING_DEVICE);
            final var messagePacket =
                    mXmppConnectionService.getMessageGenerator().sessionProposal(proposal);
            // In privacy preserving environments a proposal is only ACKed when we have presence
            // subscription. Keep the timeout, but give a server-acknowledged or currently-offline
            // device a longer cold-wake window before turning the attempt into a terminal error.
            final boolean triggerTimeout =
                    Config.JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT
                            || contact.mutualPresenceSubscription();
            if (triggerTimeout) {
                SCHEDULED_EXECUTOR_SERVICE.schedule(
                        () -> handleDeviceDiscoverySoftTimeout(account, contact, proposal),
                        Config.DEVICE_DISCOVERY_TIMEOUT,
                        TimeUnit.MILLISECONDS);
            }
            mXmppConnectionService.sendMessagePacket(account, messagePacket);
            return proposal;
        }
    }

    private void handleDeviceDiscoverySoftTimeout(
            final Account account,
            final Contact contact,
            final RtpSessionProposal proposal) {
        final DeviceDiscoveryState state;
        synchronized (this.rtpSessionProposals) {
            state = this.rtpSessionProposals.get(proposal);
        }
        if (!isDeviceDiscoveryPending(state)) {
            return;
        }

        final boolean likelyColdWake =
                state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED
                        || contact.getPresences().isEmpty();
        final long extension =
                Config.DEVICE_DISCOVERY_COLD_WAKE_TIMEOUT - Config.DEVICE_DISCOVERY_TIMEOUT;
        if (likelyColdWake && extension > 0) {
            Log.d(
                    Config.LOGTAG,
                    "call device discovery still pending after "
                            + Config.DEVICE_DISCOVERY_TIMEOUT
                            + " ms; extending cold-wake window to "
                            + Config.DEVICE_DISCOVERY_COLD_WAKE_TIMEOUT
                            + " ms state="
                            + state);
            SCHEDULED_EXECUTOR_SERVICE.schedule(
                    () -> deviceDiscoveryTimeout(account, proposal),
                    extension,
                    TimeUnit.MILLISECONDS);
            return;
        }
        deviceDiscoveryTimeout(account, proposal);
    }

    private static boolean isDeviceDiscoveryPending(
            @Nullable final DeviceDiscoveryState state) {
        return state == DeviceDiscoveryState.SEARCHING
                || state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED;
    }

    private void deviceDiscoveryTimeout(final Account account, final RtpSessionProposal proposal) {
        synchronized (this.rtpSessionProposals) {
            final DeviceDiscoveryState state = this.rtpSessionProposals.get(proposal);
            if (!isDeviceDiscoveryPending(state)) {
                return;
            }
            this.rtpSessionProposals.remove(proposal);
        }

        final var endUserState = RtpEndUserState.CONNECTIVITY_ERROR;
        Log.d(
                Config.LOGTAG,
                "call proposal still in device discovery state after final timeout t="
                        + (SystemClock.elapsedRealtime() - proposal.createdAtElapsedMs)
                        + " ms");
        setTerminalSessionState(proposal, endUserState);
        proposal.callIntegration.error();
        writeLogMissedOutgoing(
                account, proposal.with, proposal.sessionId, null, System.currentTimeMillis());
        mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                account, proposal.with, proposal.sessionId, endUserState);

        final var retraction =
                mXmppConnectionService.getMessageGenerator().sessionRetract(proposal);
        mXmppConnectionService.sendMessagePacket(account, retraction);
    }

    public void sendJingleMessageFinish(
            final Contact contact, final String sessionId, final Reason reason) {
        final var account = contact.getAccount();
        final var messagePacket =
                mXmppConnectionService
                        .getMessageGenerator()
                        .sessionFinish(contact.getJid(), sessionId, reason);
        mXmppConnectionService.sendMessagePacket(account, messagePacket);
    }

    public Optional<RtpSessionProposal> matchingProposal(final Account account, final Jid with) {
        synchronized (this.rtpSessionProposals) {
            for (final Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (proposal.account == account && with.asBareJid().equals(proposal.with)) {
                    return Optional.of(proposal);
                }
            }
        }
        return Optional.absent();
    }

    public boolean hasMatchingProposal(final Account account, final Jid with) {
        synchronized (this.rtpSessionProposals) {
            for (final Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final var state = entry.getValue();
                final RtpSessionProposal proposal = entry.getKey();
                if (proposal.account == account && with.asBareJid().equals(proposal.with)) {
                    // CallIntegrationConnectionService starts RtpSessionActivity with ACTION_VIEW
                    // and an EXTRA_LAST_REPORTED_STATE of DISCOVERING devices. however due to
                    // possible race conditions the state might have already moved on so we are
                    // going
                    // to update the UI
                    final RtpEndUserState endUserState = state.toEndUserState();
                    mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                            account, proposal.with, proposal.sessionId, endUserState);
                    return true;
                }
            }
        }
        return false;
    }

    public void deliverIbbPacket(final Account account, final Iq packet) {
        final String sid;
        final Element payload;
        final InbandBytestreamsTransport.PacketType packetType;
        if (packet.hasChild("open", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.OPEN;
            payload = packet.findChild("open", Namespace.IBB);
            sid = payload.getAttribute("sid");
        } else if (packet.hasChild("data", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.DATA;
            payload = packet.findChild("data", Namespace.IBB);
            sid = payload.getAttribute("sid");
        } else if (packet.hasChild("close", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.CLOSE;
            payload = packet.findChild("close", Namespace.IBB);
            sid = payload.getAttribute("sid");
        } else {
            packetType = null;
            payload = null;
            sid = null;
        }
        if (sid == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": unable to deliver ibb packet. missing sid");
            account.getXmppConnection().sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null);
            return;
        }
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleFileTransferConnection fileTransfer) {
                final Transport transport = fileTransfer.getTransport();
                if (transport instanceof InbandBytestreamsTransport inBandTransport) {
                    if (sid.equals(inBandTransport.getStreamId())) {
                        if (inBandTransport.deliverPacket(packetType, packet.getFrom(), payload)) {
                            account.getXmppConnection()
                                    .sendIqPacket(packet.generateResponse(Iq.Type.RESULT), null);
                        } else {
                            account.getXmppConnection()
                                    .sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null);
                        }
                        return;
                    }
                }
            }
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": unable to deliver ibb packet with sid=" + sid);
        account.getXmppConnection().sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null);
    }

    public void notifyStreamResumed(final Account account) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().account == account
                    && connection instanceof JingleRtpConnection rtpConnection) {
                rtpConnection.notifyStreamResumed();
            }
        }
    }

    public void notifyRebound(final Account account) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().account == account) {
                connection.notifyRebound();
            }
        }
        final XmppConnection xmppConnection = account.getXmppConnection();
        if (xmppConnection != null && xmppConnection.getFeatures().sm()) {
            resendSessionProposals(account);
        }
    }

    public WeakReference<JingleRtpConnection> findJingleRtpConnection(
            Account account, Jid with, String sessionId) {
        final AbstractJingleConnection.Id id =
                AbstractJingleConnection.Id.of(account, with, sessionId);
        final AbstractJingleConnection connection = connections.get(id);
        if (connection instanceof JingleRtpConnection) {
            return new WeakReference<>((JingleRtpConnection) connection);
        }
        return null;
    }

    public JingleRtpConnection findJingleRtpConnection(final Account account, final Jid with) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection instanceof JingleRtpConnection rtpConnection) {
                if (rtpConnection.isTerminated()) {
                    continue;
                }
                final var id = rtpConnection.getId();
                if (id.account == account && account.getJid().equals(with)) {
                    return rtpConnection;
                }
            }
        }
        return null;
    }

    private void resendSessionProposals(final Account account) {
        synchronized (this.rtpSessionProposals) {
            for (final Map.Entry<RtpSessionProposal, DeviceDiscoveryState> entry :
                    this.rtpSessionProposals.entrySet()) {
                final RtpSessionProposal proposal = entry.getKey();
                if (entry.getValue() == DeviceDiscoveryState.SEARCHING
                        && proposal.account == account) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": resending session proposal to "
                                    + proposal.with);
                    final var messagePacket =
                            mXmppConnectionService.getMessageGenerator().sessionProposal(proposal);
                    mXmppConnectionService.sendMessagePacket(account, messagePacket);
                }
            }
        }
    }

    public void updateProposedSessionDiscovered(
            Account account, Jid from, String sessionId, final DeviceDiscoveryState target) {
        synchronized (this.rtpSessionProposals) {
            final RtpSessionProposal sessionProposal =
                    getRtpSessionProposal(account, from.asBareJid(), sessionId);
            final DeviceDiscoveryState currentState =
                    sessionProposal == null ? null : rtpSessionProposals.get(sessionProposal);
            if (currentState == null) {
                Log.d(
                        Config.LOGTAG,
                        "unable to find session proposal for session id "
                                + sessionId
                                + " target="
                                + target);
                return;
            }
            if (currentState == DeviceDiscoveryState.DISCOVERED) {
                Log.d(
                        Config.LOGTAG,
                        "session proposal already at discovered. not going to fall back");
                return;
            }

            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": flagging session "
                            + sessionId
                            + " as "
                            + target);

            final RtpEndUserState endUserState = target.toEndUserState();

            if (target == DeviceDiscoveryState.FAILED) {
                Log.d(Config.LOGTAG, "removing session proposal after failure");
                setTerminalSessionState(sessionProposal, endUserState);
                this.rtpSessionProposals.remove(sessionProposal);
                sessionProposal.getCallIntegration().error();
                mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                        account, sessionProposal.with, sessionProposal.sessionId, endUserState);
                return;
            }

            this.rtpSessionProposals.put(sessionProposal, target);

            if (endUserState == RtpEndUserState.RINGING) {
                sessionProposal.callIntegration.setDialing();
                Log.d(
                        Config.LOGTAG,
                        "call device discovered in "
                                + (SystemClock.elapsedRealtime()
                                        - sessionProposal.createdAtElapsedMs)
                                + " ms");
            }

            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                    account, sessionProposal.with, sessionProposal.sessionId, endUserState);
        }
    }

    public void rejectRtpSession(final String sessionId) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().sessionId.equals(sessionId)) {
                if (connection instanceof JingleRtpConnection) {
                    try {
                        ((JingleRtpConnection) connection).rejectCall();
                        return;
                    } catch (final IllegalStateException e) {
                        Log.w(
                                Config.LOGTAG,
                                "race condition on rejecting call from notification",
                                e);
                    }
                }
            }
        }
    }

    public void endRtpSession(final String sessionId) {
        for (final AbstractJingleConnection connection : this.connections.values()) {
            if (connection.getId().sessionId.equals(sessionId)) {
                if (connection instanceof JingleRtpConnection) {
                    ((JingleRtpConnection) connection).endCall();
                }
            }
        }
    }

    public void failProceed(
            Account account, final Jid with, final String sessionId, final String message) {
        final AbstractJingleConnection.Id id =
                AbstractJingleConnection.Id.of(account, with, sessionId);
        final AbstractJingleConnection existingJingleConnection = connections.get(id);
        if (existingJingleConnection instanceof JingleRtpConnection) {
            ((JingleRtpConnection) existingJingleConnection).deliverFailedProceed(message);
        }
    }

    void ensureConnectionIsRegistered(final AbstractJingleConnection connection) {
        if (connections.containsValue(connection)) {
            return;
        }
        final IllegalStateException e =
                new IllegalStateException(
                        "JingleConnection has not been registered with connection manager");
        Log.e(Config.LOGTAG, "ensureConnectionIsRegistered() failed. Going to throw", e);
        throw e;
    }

    void setTerminalSessionState(
            AbstractJingleConnection.Id id, final RtpEndUserState state, final Set<Media> media) {
        this.terminatedSessions.put(
                PersistableSessionId.of(id), new TerminatedRtpSession(state, media));
    }

    void setTerminalSessionState(final RtpSessionProposal proposal, final RtpEndUserState state) {
        this.terminatedSessions.put(
                PersistableSessionId.of(proposal), new TerminatedRtpSession(state, proposal.media));
    }

    public TerminatedRtpSession getTerminalSessionState(final Jid with, final String sessionId) {
        return this.terminatedSessions.getIfPresent(new PersistableSessionId(with, sessionId));
    }

    private static class PersistableSessionId {
        private final Jid with;
        private final String sessionId;

        private PersistableSessionId(Jid with, String sessionId) {
            this.with = with;
            this.sessionId = sessionId;
        }

        public static PersistableSessionId of(final AbstractJingleConnection.Id id) {
            return new PersistableSessionId(id.with, id.sessionId);
        }

        public static PersistableSessionId of(final RtpSessionProposal proposal) {
            return new PersistableSessionId(proposal.with, proposal.sessionId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PersistableSessionId that = (PersistableSessionId) o;
            return Objects.equal(with, that.with) && Objects.equal(sessionId, that.sessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(with, sessionId);
        }
    }

    public static class TerminatedRtpSession {
        public final RtpEndUserState state;
        public final Set<Media> media;

        TerminatedRtpSession(RtpEndUserState state, Set<Media> media) {
            this.state = state;
            this.media = media;
        }
    }

    private static final class IceServerCacheEntry {
        private final List<PeerConnection.IceServer> iceServers;
        private final long expiresAtElapsedMs;

        private IceServerCacheEntry(
                final List<PeerConnection.IceServer> iceServers,
                final long expiresAtElapsedMs) {
            this.iceServers = iceServers;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
        }
    }

    public enum DeviceDiscoveryState {
        SEARCHING,
        SEARCHING_ACKNOWLEDGED,
        DISCOVERED,
        FAILED;

        public RtpEndUserState toEndUserState() {
            return switch (this) {
                case SEARCHING, SEARCHING_ACKNOWLEDGED -> RtpEndUserState.FINDING_DEVICE;
                case DISCOVERED -> RtpEndUserState.RINGING;
                default -> RtpEndUserState.CONNECTIVITY_ERROR;
            };
        }
    }

    public static class RtpSessionProposal implements OngoingRtpSession {
        public final Jid with;
        public final String sessionId;
        public final Set<Media> media;
        private final Account account;
        private final CallIntegration callIntegration;
        private final long createdAtElapsedMs;

        private RtpSessionProposal(
                Account account,
                Jid with,
                String sessionId,
                Set<Media> media,
                final CallIntegration callIntegration) {
            this.account = account;
            this.with = with;
            this.sessionId = sessionId;
            this.media = media;
            this.callIntegration = callIntegration;
            this.createdAtElapsedMs = SystemClock.elapsedRealtime();
        }

        public static RtpSessionProposal of(
                Account account,
                Jid with,
                Set<Media> media,
                final CallIntegration callIntegration) {
            return new RtpSessionProposal(account, with, nextRandomId(), media, callIntegration);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RtpSessionProposal proposal = (RtpSessionProposal) o;
            return Objects.equal(account.getJid(), proposal.account.getJid())
                    && Objects.equal(with, proposal.with)
                    && Objects.equal(sessionId, proposal.sessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(account.getJid(), with, sessionId);
        }

        @Override
        public Account getAccount() {
            return account;
        }

        @Override
        public Jid getWith() {
            return with;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public CallIntegration getCallIntegration() {
            return this.callIntegration;
        }

        @Override
        public Set<Media> getMedia() {
            return this.media;
        }
    }

    public class ProposalStateCallback implements CallIntegration.Callback {

        private final RtpSessionProposal proposal;

        public ProposalStateCallback(final RtpSessionProposal proposal) {
            this.proposal = proposal;
        }

        @Override
        public void onCallIntegrationShowIncomingCallUi() {}

        @Override
        public void onCallIntegrationDisconnect() {
            Log.d(Config.LOGTAG, "a phone call has just been started. retracting proposal");
            retractSessionProposal(this.proposal);
        }

        @Override
        public void onAudioDeviceChanged(
                final CallIntegration.AudioDevice selectedAudioDevice,
                final Set<CallIntegration.AudioDevice> availableAudioDevices) {
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                    selectedAudioDevice, availableAudioDevices);
        }

        @Override
        public void onCallIntegrationReject() {}

        @Override
        public void onCallIntegrationAnswer() {}

        @Override
        public void onCallIntegrationSilence() {}

        @Override
        public void onCallIntegrationMicrophoneEnabled(boolean enabled) {}
    }
}
