package eu.siacs.conversations.services;

import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.receiver.SystemEventReceiver;
import androidx.core.content.ContextCompat;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.PushGatewayProtocol;
import eu.siacs.conversations.xmpp.WebPushCrypto;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Opt-in receiving side. Existing outbound UnifiedPushDistributor is separate. */
public final class PushManagementService {
    private static final String EMBEDDED = "embedded";
    private static final String EXTERNAL = "external";
    private static final String UP_REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    private static final String UP_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER";
    private static final String EMBEDDED_RECEIVER =
            "eu.siacs.conversations.services.FossEmbeddedDistributor";
    private static final long IQ_TIMEOUT_SECONDS = 12;
    private final XmppConnectionService service;
    private final FossPushStore store;
    private final Map<String, String> inFlight = new HashMap<>();
    private final Map<String, Long> wakePings = new HashMap<>();
    private final HashSet<String> renewedThisProcess = new HashSet<>();

    private boolean busy(final FossPushStore.Record r) {
        return r.generation.equals(inFlight.get(r.accountUuid));
    }

    private void mark(final FossPushStore.Record r) {
        inFlight.put(r.accountUuid, r.generation);
    }

    private void finish(final String uuid, final String generation) {
        if (generation.equals(inFlight.get(uuid))) inFlight.remove(uuid);
    }

    public PushManagementService(final XmppConnectionService service) {
        this.service = service;
        this.store = new FossPushStore(service);
    }

    // A confirmed subscription informs background ping policy; registration stays in onBound().
    public boolean available(final Account account) {
        final FossPushStore.Record r = account == null ? null : safeRead(account.getUuid());
        return r != null && r.enabled && "connected".equals(r.phase)
                && !new FossPushEventJournal(service).revoked(r.token);
    }
    public void drainControlEvents() { new FossPushEventJournal(service).drain(this); }
    public boolean isStub() { return false; }
    public void registerPushTokenOnServer(final Account account) {}

    public String status(final Account account) {
        try {
            final FossPushStore.Record r = store.read(account.getUuid());
            if (r == null) return "off";
            if (r.enabled && "error".equals(r.phase) && !r.failureReason.isEmpty()) {
                return r.failureReason;
            }
            return r.pendingDisable || r.pendingUnregister || r.pendingDistributor
                    ? "cancelling" : r.phase;
        } catch (final RuntimeException e) { return "error"; }
    }

    public synchronized boolean connect(final Account account, final String mode,
            final String serviceInput, final String vapid, final String distributor) {
        if (account == null || !account.isEnabled()) return false;
        try {
            PushGatewayProtocol.serviceJid(serviceInput);
            WebPushCrypto.decodePublicKey(Base64.decode(vapid, Base64.URL_SAFE | Base64.NO_WRAP));
            if (!EMBEDDED.equals(mode) && (!EXTERNAL.equals(mode) || distributor == null
                    || distributor.isEmpty() || distributor.equals(service.getPackageName()))) return false;
            if (EMBEDDED.equals(mode) && distributor != null && !distributor.isEmpty()) return false;
            FossPushStore.Record previous = store.read(account.getUuid());
            if (previous != null && previous.enabled) {
                disconnect(account);
                previous = store.read(account.getUuid());
            }
            final long activeRevision = previous == null ? 0 : previous.revision;
            if (previous != null && (previous.pendingDisable || previous.pendingUnregister
                    || previous.pendingDistributor)) {
                // Persist the old cancellation under its own generation before replacing the active record.
                previous.accountUuid = account.getUuid() + "~" + previous.generation;
                store.write(previous);
            }
            final WebPushCrypto.Keys keys = WebPushCrypto.generate();
            final FossPushStore.Record r = new FossPushStore.Record();
            r.accountUuid = account.getUuid();
            r.revision = activeRevision;
            r.service = serviceInput;
            r.mode = mode;
            r.distributor = EXTERNAL.equals(mode) ? distributor : "";
            r.vapid = vapid;
            r.token = randomToken();
            r.publicKey = Base64.encodeToString(keys.publicKey, Base64.URL_SAFE | Base64.NO_WRAP);
            r.privateKey = Base64.encodeToString(keys.privateKeyPkcs8, Base64.NO_WRAP);
            r.auth = Base64.encodeToString(keys.auth, Base64.URL_SAFE | Base64.NO_WRAP);
            r.enabled = true;
            r.phase = "connecting";
            r.generation = UUID.randomUUID().toString();
            store.write(r); // Consent and keys are durable before contacting Google or a distributor.
            Arrays.fill(keys.privateKeyPkcs8, (byte) 0);
            Arrays.fill(keys.auth, (byte) 0);
            if (sendDistributor(r, false)) return true;
            r.phase = "error";
            store.write(r);
            return false;
        } catch (final RuntimeException | GeneralSecurityException e) { return false; }
    }

    private static String randomToken() {
        final byte[] data = new byte[24];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private boolean sendDistributor(final FossPushStore.Record r, final boolean unregister) {
        return sendDistributor(r, unregister, unregister ? r.previousToken : r.token);
    }

    private boolean sendDistributor(final FossPushStore.Record r, final boolean unregister,
            final String token) {
        final String mode = unregister ? r.previousMode : r.mode;
        final String target = unregister ? r.previousDistributor : r.distributor;
        if (token == null || token.isEmpty()) return false;
        final Intent intent = new Intent(unregister ? UP_UNREGISTER : UP_REGISTER);
        intent.putExtra("token", token);
        if (!unregister) intent.putExtra("vapid", r.vapid);
        if (EMBEDDED.equals(mode)) {
            intent.setComponent(new ComponentName(service.getPackageName(), EMBEDDED_RECEIVER));
        } else if (EXTERNAL.equals(mode)) {
            intent.setPackage(target);
        } else return false;
        final PendingIntent proof = PendingIntent.getBroadcast(service, 0,
                new Intent().setPackage("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE);
        intent.putExtra("pi", proof);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setShareIdentityEnabled(true);
                service.sendBroadcast(intent, null, options.toBundle());
            } else service.sendBroadcast(intent);
            return true;
        } catch (final RuntimeException e) { return false; }
    }

    public synchronized void onBound(final Account account) {
        confirmPendingWake(account);
        for (final FossPushStore.Record old : store.cancellationRecords(account.getUuid())) {
            for (final String token : old.pendingTokens) sendDistributor(old, true, token);
            if (old.pendingDisable || old.pendingUnregister) revoke(account, old);
        }
        final FossPushStore.Record r = safeRead(account.getUuid());
        if (r == null || !account.isEnabled()) return;
        if (r.enabled && r.renewConfirmedAt > 0)
            FossPushRenewWorker.schedule(service, r.accountUuid, r.generation, r.expiresAtMillis);
        if (r.pendingDisable || r.pendingUnregister) revoke(account, r);
        else if (r.enabled && "connected".equals(r.phase)
                && renewedThisProcess.add(r.accountUuid)) {
            if (!sendDistributor(r, false)) renewedThisProcess.remove(r.accountUuid);
        }
        else if (r.enabled && !r.endpoint.isEmpty() && !"connected".equals(r.phase)) {
            registerGateway(account, r);
        }
    }

    public synchronized void onEndpoint(final String token, final String endpoint) {
        final FossPushStore.Record r = safeFind(token);
        if (r == null || !r.enabled || endpoint == null || !endpoint.startsWith("https://")
                || endpoint.length() > 1000) return;
        if (endpoint.equals(r.endpoint)) {
            final Account account = service.findAccountByUuid(r.accountUuid);
            if (account != null && account.isOnlineAndConnected()
                    && !r.pendingDisable && !r.pendingUnregister) registerGateway(account, r);
            return;
        }
        if (!r.node.isEmpty() && !r.pendingDisable && !r.pendingUnregister) {
            savePrevious(r);
            r.node = "";
            r.secret = "";
        }
        r.endpoint = endpoint;
        r.phase = "connecting";
        r.generation = UUID.randomUUID().toString();
        inFlight.remove(r.accountUuid);
        renewedThisProcess.remove(r.accountUuid);
        r.attempts = 0;
        store.write(r);
        final Account account = service.findAccountByUuid(r.accountUuid);
        if (account != null && account.isOnlineAndConnected()) onBound(account);
    }

    public synchronized void onFailed(final String token, final String reason) {
        final FossPushStore.Record r = safeFind(token);
        // A failure following NEW_ENDPOINT is stale under AND_3.1.0.
        if (!FossPushPolicy.acceptRegistrationFailure(
                r != null && r.enabled, r == null ? null : r.endpoint, r != null)) return;
        final String oldToken = r.token;
        r.token = randomToken();
        r.failureReason = FossPushPolicy.failureReason(reason);
        r.phase = "error";
        r.generation = UUID.randomUUID().toString();
        inFlight.remove(r.accountUuid);
        r.previousMode = r.mode;
        r.previousDistributor = r.distributor;
        r.previousToken = oldToken;
        FossPushPolicy.queueCancellation(r.pendingTokens, oldToken);
        r.pendingDistributor = true;
        store.write(r);
        sendDistributor(r, true);
    }

    public synchronized void onUnavailable(final String token) {
        final FossPushStore.Record r = safeFind(token);
        if (r != null && r.enabled) {
            r.phase = "error";
            r.failureReason = "NETWORK";
            store.write(r);
        }
    }

    public synchronized void onActiveUnregistered(final String token) {
        final FossPushStore.Record r = safeFind(token);
        if (r == null || !r.enabled) return;
        r.enabled = false;
        r.pendingWake = false;
        r.phase = "error";
        r.failureReason = "UNREGISTERED";
        r.generation = UUID.randomUUID().toString();
        inFlight.remove(r.accountUuid);
        savePrevious(r);
        r.pendingDistributor = !r.pendingTokens.isEmpty();
        r.token = r.endpoint = r.node = r.secret = "";
        store.write(r);
        final Account account = service.findAccountByUuid(r.accountUuid);
        if (account != null && account.isOnlineAndConnected()) revoke(account, r);
    }

    public synchronized void disconnect(final Account account) {
        FossPushRenewWorker.cancel(service, account.getUuid());
        final FossPushStore.Record r = safeRead(account.getUuid());
        if (r == null) return;
        r.enabled = false;
        r.pendingWake = false;
        r.phase = "off";
        r.generation = UUID.randomUUID().toString();
        inFlight.remove(r.accountUuid);
        r.attempts = 0;
        if (!r.pendingDisable && !r.pendingUnregister) savePrevious(r);
        renewedThisProcess.remove(r.accountUuid);
        FossPushPolicy.queueCancellation(r.pendingTokens, r.previousToken);
        r.pendingDistributor = !r.pendingTokens.isEmpty();
        r.node = "";
        r.secret = "";
        r.token = "";
        r.endpoint = "";
        store.write(r); // Close local channel before asynchronous remote cancellation.
        for (final String pending : r.pendingTokens) sendDistributor(r, true, pending);
        if (account.isOnlineAndConnected()) revoke(account, r);
    }

    private static void savePrevious(final FossPushStore.Record r) {
        r.previousService = r.service;
        r.previousNode = r.node;
        r.previousEndpoint = r.endpoint;
        r.previousToken = r.token;
        r.previousMode = r.mode;
        r.previousDistributor = r.distributor;
        r.pendingDisable = !r.node.isEmpty();
        // The gateway may have committed a registration whose IQ reply was lost.
        r.pendingUnregister = !r.endpoint.isEmpty();
    }

    private static void clearPrevious(final FossPushStore.Record r) {
        r.previousService = r.previousNode = r.previousEndpoint = "";
        if (!r.pendingDistributor) {
            r.previousToken = r.previousMode = r.previousDistributor = "";
        }
    }

    public synchronized void onDistributorUnregistered(final String token) {
            final FossPushStore.Record r = store.findByPreviousToken(token);
            if (r == null) return;
            r.pendingTokens.remove(token);
            r.pendingDistributor = !r.pendingTokens.isEmpty();
            if (!r.pendingDisable && !r.pendingUnregister) clearPrevious(r);
            store.write(r);
    }

    private FossPushStore.Record safeRead(final String uuid) {
        try { return store.read(uuid); }
        catch (final RuntimeException e) { return null; }
    }

    private FossPushStore.Record safeFind(final String token) {
        try { return store.findByToken(token); }
        catch (final RuntimeException e) { return null; }
    }

    private FossPushStore.Record current(final String uuid, final String generation) {
        final FossPushStore.Record r = safeRead(uuid);
        return r != null && generation.equals(r.generation) ? r : null;
    }

    private void fail(final FossPushStore.Record r) {
        r.phase = "error";
        store.write(r);
    }

    private static boolean accepted(final Iq response, final Iq request) {
        return response != null && response.getType() == Iq.Type.RESULT
                && request.getId().equals(response.getId());
    }

    private synchronized void revoke(final Account account, final FossPushStore.Record r) {
        if (!account.isOnlineAndConnected() || r.attempts >= 3
                || busy(r)) return;
        final String generation = r.generation;
        if (r.pendingDisable) {
            mark(r);
            r.attempts++;
            store.write(r);
            final Iq iq = service.getIqGenerator().disablePush(
                    Jid.of(r.previousService), r.previousNode);
            iq.setId(UUID.randomUUID().toString());
            service.sendIqPacket(account, iq, response -> {
                synchronized (PushManagementService.this) {
                    finish(r.accountUuid, generation);
                    final FossPushStore.Record active = current(r.accountUuid, generation);
                    if (active == null || !active.pendingDisable) return;
                    if (!accepted(response, iq)) { fail(active); return; }
                    active.pendingDisable = false;
                    active.attempts = 0;
                    store.write(active);
                    revoke(account, active);
                }
            }, IQ_TIMEOUT_SECONDS);
        } else if (r.pendingUnregister) {
            mark(r);
            r.attempts++;
            store.write(r);
            final Jid gateway = Jid.of(r.previousService);
            final Iq iq = r.previousNode.isEmpty()
                    ? PushGatewayProtocol.unregisterEndpoint(gateway, service.getPackageName(),
                            r.previousEndpoint)
                    : PushGatewayProtocol.unregister(gateway, service.getPackageName(), r.previousNode);
            iq.setId(UUID.randomUUID().toString());
            service.sendIqPacket(account, iq, response -> {
                synchronized (PushManagementService.this) {
                    finish(r.accountUuid, generation);
                    final FossPushStore.Record active = current(r.accountUuid, generation);
                    if (active == null || !active.pendingUnregister) return;
                    if (!PushGatewayProtocol.confirmedUnregistration(
                            response, gateway, iq.getId())) { fail(active); return; }
                    active.pendingUnregister = false;
                    active.attempts = 0;
                    clearPrevious(active);
                    store.write(active);
                    if (active.enabled) registerGateway(account, active);
                }
            }, IQ_TIMEOUT_SECONDS);
        } else if (r.enabled && !r.endpoint.isEmpty()) registerGateway(account, r);
    }

    private synchronized void registerGateway(final Account account, final FossPushStore.Record r) {
        if (!r.enabled || !account.isOnlineAndConnected() || r.endpoint.isEmpty()
                || r.pendingDisable || r.pendingUnregister || r.attempts >= 3
                || busy(r)) return;
        mark(r);
        final Jid gateway = Jid.of(r.service);
        final String generation = r.generation;
        r.attempts++;
        r.phase = "connecting";
        store.write(r);
        final Iq disco = new Iq(Iq.Type.GET);
        disco.setTo(gateway);
        disco.setId(UUID.randomUUID().toString());
        disco.addChild("query", "http://jabber.org/protocol/disco#info");
        service.sendIqPacket(account, disco, response -> {
            synchronized (PushManagementService.this) {
                finish(r.accountUuid, generation);
                final FossPushStore.Record active = current(r.accountUuid, generation);
                if (active == null || !active.enabled) return;
                if (!accepted(response, disco) || !gateway.equals(response.getFrom())
                        || !supportsPush(response)) { fail(active); return; }
                sendGatewayRegistration(account, active, gateway);
            }
        }, IQ_TIMEOUT_SECONDS);
    }

    private static boolean supportsPush(final Iq response) {
        final Element query = response.findChild("query", "http://jabber.org/protocol/disco#info");
        if (query == null) return false;
        for (final Element child : query.getChildren()) {
            if ("feature".equals(child.getName())
                    && Namespace.PUSH.equals(child.getAttribute("var"))) return true;
        }
        return false;
    }

    private void sendGatewayRegistration(
            final Account account, final FossPushStore.Record r, final Jid gateway) {
        mark(r);
        final String generation = r.generation;
        final Iq iq;
        try {
            iq = PushGatewayProtocol.register(gateway, service.getPackageName(), r.endpoint,
                    r.publicKey, r.auth);
        } catch (final IllegalArgumentException e) { fail(r); return; }
        iq.setId(UUID.randomUUID().toString());
        service.sendIqPacket(account, iq, response -> {
            synchronized (PushManagementService.this) {
                finish(r.accountUuid, generation);
                final PushGatewayProtocol.Registration confirmed =
                        PushGatewayProtocol.confirmedRegistration(response, gateway, iq.getId());
                final FossPushStore.Record active = current(r.accountUuid, generation);
                if (active == null || !active.enabled) {
                    // A late success is never allowed to re-enable push.
                    if (confirmed != null && account.isOnlineAndConnected()) {
                        // Pending endpoint cancellation was persisted before this callback.
                        final Iq undo = PushGatewayProtocol.unregister(
                                gateway, service.getPackageName(), confirmed.node);
                        service.sendIqPacket(account, undo, ignored -> {}, IQ_TIMEOUT_SECONDS);
                    }
                    return;
                }
                if (confirmed == null) { fail(active); return; }
                active.node = confirmed.node;
                active.secret = confirmed.secret;
                active.expiresAtMillis = confirmed.expiresAtMillis;
                active.phase = "enabling";
                store.write(active); // Never lose a confirmed node before XEP-0357 enable.
                final Iq enable = service.getIqGenerator().enablePush(
                        gateway, confirmed.node, confirmed.secret);
                enable.setId(UUID.randomUUID().toString());
                mark(r);
                service.sendIqPacket(account, enable, result -> {
                    synchronized (PushManagementService.this) {
                        finish(r.accountUuid, generation);
                        final FossPushStore.Record current = current(r.accountUuid, generation);
                        if (current == null || !current.enabled) return;
                        if (accepted(result, enable)) {
                            current.phase = "connected";
                            current.attempts = 0;
                            current.renewPending = false;
                            current.renewConfirmedAt = System.currentTimeMillis();
                            store.write(current);
                            FossPushRenewWorker.schedule(service, current.accountUuid,
                                    current.generation, current.expiresAtMillis);
                        } else fail(current);
                    }
                }, IQ_TIMEOUT_SECONDS);
            }
        }, IQ_TIMEOUT_SECONDS);
    }

    public synchronized void retry(final Account account) {
        final FossPushStore.Record r = safeRead(account.getUuid());
        if (r == null) return;
        r.attempts = 0;
        r.generation = UUID.randomUUID().toString();
        inFlight.remove(r.accountUuid);
        store.write(r);
        for (final String pending : r.pendingTokens) sendDistributor(r, true, pending);
        if (r.pendingDisable || r.pendingUnregister) revoke(account, r);
        else if (r.enabled && !r.endpoint.isEmpty()) registerGateway(account, r);
        else if (r.enabled) sendDistributor(r, false);
    }

    public synchronized void onRenewal(final String uuid, final String generation) {
        if (uuid == null || generation == null) return;
        final FossPushStore.Record r = current(uuid, generation);
        final Account account = service.findAccountByUuid(uuid);
        if (r == null || !r.enabled || account == null) return;
        r.attempts = 0;
        r.renewPending = true;
        store.write(r);
        if (account.isOnlineAndConnected()) registerGateway(account, r);
        // When offline the normal connection lifecycle invokes onBound.
    }

    public synchronized boolean consumePendingWake(final Account account) {
        final FossPushStore.Record r = safeRead(account.getUuid());
        if (r != null && !account.isEnabled() && r.pendingWake) {
            r.pendingWake = false;
            store.write(r);
            return false;
        }
        if (r == null || !r.enabled || !"connected".equals(r.phase) || !r.pendingWake) return false;
        return true;
    }

    /** A server IQ reply proves that the restored stream is usable. */
    public synchronized void confirmPendingWake(final Account account) {
        final FossPushStore.Record r = safeRead(account.getUuid());
        if (r == null || !r.pendingWake || !r.enabled || !account.isOnlineAndConnected()) return;
        final long wakeVersion = r.lastWake;
        if (wakePings.containsKey(r.accountUuid)) return;
        wakePings.put(r.accountUuid, wakeVersion);
        final String generation = r.generation;
        final Iq ping = new Iq(Iq.Type.GET);
        ping.setId(UUID.randomUUID().toString());
        ping.addChild("ping", "urn:xmpp:ping");
        service.sendIqPacket(account, ping, response -> {
            synchronized (PushManagementService.this) {
                wakePings.remove(r.accountUuid);
                FossPushStore.Record latest = current(r.accountUuid, generation);
                if (latest != null && latest.enabled && latest.pendingWake
                        && latest.lastWake == wakeVersion && response != null
                        && (response.getType() == Iq.Type.RESULT || response.getType() == Iq.Type.ERROR)
                        && ping.getId().equals(response.getId())) {
                    latest.pendingWake = false;
                    store.write(latest);
                } else if (latest != null && latest.enabled && latest.pendingWake
                        && latest.lastWake != wakeVersion && response != null
                        && (response.getType() == Iq.Type.RESULT || response.getType() == Iq.Type.ERROR)) {
                    confirmPendingWake(account);
                }
            }
        }, IQ_TIMEOUT_SECONDS);
    }

    public static boolean wake(final Context context, final String token, final byte[] body) {
        try {
            final FossPushStore store = new FossPushStore(context);
            final FossPushStore.Record r = store.findByToken(token);
            if (r == null || !r.enabled || !"connected".equals(r.phase)) return false;
            if (new FossPushEventJournal(context).revoked(token)) return false;
            final byte[] clear = WebPushCrypto.decrypt(body,
                    Base64.decode(r.privateKey, Base64.NO_WRAP),
                    Base64.decode(r.publicKey, Base64.URL_SAFE | Base64.NO_WRAP),
                    Base64.decode(r.auth, Base64.URL_SAFE | Base64.NO_WRAP));
            final boolean valid = "{\"type\":\"wakeup\",\"v\":1}".equals(
                    new String(clear, StandardCharsets.UTF_8));
            Arrays.fill(clear, (byte) 0);
            if (!valid) return false;
            synchronized (PushManagementService.class) {
                FossPushStore.Record fresh = store.findByToken(token);
                final long now = System.currentTimeMillis();
                if (fresh == null || !fresh.enabled || !"connected".equals(fresh.phase)) return false;
                boolean recent = false;
                boolean persisted = false;
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (fresh == null || !fresh.enabled || !"connected".equals(fresh.phase)) return false;
                    recent = now - fresh.lastWake < 5000L;
                    fresh.lastWake = Math.max(now, fresh.lastWake + 1);
                    fresh.pendingWake = true;
                    try { store.write(fresh); persisted = true; break; }
                    catch (IllegalStateException conflict) { fresh = store.findByToken(token); }
                }
                if (!persisted) return false;
                FossPushRecoveryWorker.schedule(context, fresh.accountUuid);
                if (recent) return true;
                final Intent wake = new Intent(context, XmppConnectionService.class);
                wake.setAction(XmppConnectionService.ACTION_FOSS_PUSH_WAKE);
                wake.putExtra("push_account_uuid", fresh.accountUuid);
                wake.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true);
                try {
                    ContextCompat.startForegroundService(context, wake);
                    return true;
                } catch (final RuntimeException denied) {
                    // Keep the authenticated wake pending for the next normal service start.
                    return false;
                }
            }
        } catch (final RuntimeException | GeneralSecurityException e) { return false; }
    }
}
