package eu.siacs.conversations.services;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.storage.secure.MucPasswordVault;
import eu.siacs.conversations.storage.secure.ScopedAccountSecretVaultV1;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;

/** Owner-only room password workflow. Callbacks contain status only, never a secret or form. */
public final class MucPasswordController {
    public enum Status { ENABLED, DISABLED, SAVED_ENABLED, SAVED_DISABLED, UNSUPPORTED, UNKNOWN,
        PENDING, REJECTED, LOCKED, STORAGE_ERROR, ERROR, BUSY }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "muc-password-vault");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private final XmppConnectionService service;

    public MucPasswordController(XmppConnectionService service) { this.service = service; }

    public static boolean canManage(Conversation conversation) {
        return conversation != null && conversation.getAccount() != null && conversation.getJid() != null
                && conversation.getMode() == Conversation.MODE_MULTI
                && conversation.getNextCounterpart() == null
                && conversation.getAccount().getStatus() == Account.State.ONLINE
                && conversation.getAccount().getXmppConnection() != null
                && conversation.getMucOptions().getSelf().getAffiliation() == MucOptions.Affiliation.OWNER;
    }

    public void query(Conversation conversation, Consumer<Status> callback) {
        begin(conversation, null, null, false, callback);
    }

    public void change(Conversation conversation, boolean enabled, String password,
            boolean retryPending, Consumer<Status> callback) {
        if (!retryPending && enabled && !MucPasswordChange.validPassword(password)) {
            callback.accept(Status.ERROR);
            return;
        }
        begin(conversation, enabled, password, retryPending, callback);
    }

    private void begin(Conversation conversation, Boolean enabled, String password,
            boolean retry, Consumer<Status> callback) {
        if (!canManage(conversation)) { callback.accept(Status.REJECTED); return; }
        final String key = conversation.getAccount().getUuid() + ":" + conversation.getJid().asBareJid();
        final long epoch = SecureContentCryptoSessionRuntimeV1.snapshot(service).getEpoch();
        if (!IN_FLIGHT.add(key)) { callback.accept(Status.BUSY); return; }
        final Session session = new Session(conversation, key, epoch, callback);
        IO.execute(() -> {
            try {
                if (!session.current()) { session.finish(Status.LOCKED); return; }
                final MucPasswordVault vault = new MucPasswordVault(service, conversation);
                final MucPasswordChange transaction = new MucPasswordChange(vault, session::current);
                final boolean mutation = enabled != null;
                if (mutation) {
                    if (!transaction.prepare(enabled, password, retry)) {
                        if (!session.current()) { session.finish(Status.LOCKED); return; }
                        final MucPasswordChange.Record pending = vault.read();
                        session.finish(pending != null && pending.pending() ? Status.PENDING : Status.STORAGE_ERROR);
                        return;
                    }
                } else {
                    vault.read(); // Verify protected state is readable before requesting an owner form.
                }
                if (!session.current()) { session.finish(Status.LOCKED); return; }
                service.sendIqPacket(conversation.getAccount(), MucPasswordForm.request(conversation.getJid()),
                        response -> IO.execute(() -> onForm(session, transaction, vault, mutation, response)), 10L);
            } catch (Exception error) {
                // Never log exception messages or IQ payloads: either can contain a room secret.
                session.finish(session.current() ? Status.STORAGE_ERROR : Status.LOCKED);
            }
        });
    }

    private void onForm(Session session, MucPasswordChange transaction, MucPasswordVault vault,
            boolean mutation, Iq response) {
        try {
            if (!session.current()) { session.finish(Status.LOCKED); return; }
            if (response.getType() != Iq.Type.RESULT) {
                if (mutation) transaction.rollbackUnsent(); // Never rolls back a sent/uncertain retry.
                session.finish(response.getType() == Iq.Type.ERROR ? Status.REJECTED : Status.ERROR);
                return;
            }
            final Element query = response.findChild("query", MucPasswordForm.OWNER);
            final MucPasswordForm form;
            try {
                form = new MucPasswordForm(query == null ? null : query.findChild("x", Namespace.DATA));
            } catch (IllegalArgumentException unsupported) {
                if (mutation) transaction.rollbackUnsent();
                session.finish(Status.UNSUPPORTED);
                return;
            }
            if (!mutation) {
                final MucPasswordChange.Record record = vault.read();
                if (record != null && record.pending()) session.finish(Status.PENDING);
                else if (form.state() == MucPasswordForm.State.UNKNOWN) discoverState(session);
                else session.finish(status(form.state()));
                return;
            }
            if (!session.current()) { session.finish(Status.LOCKED); return; }
            final MucPasswordChange.Record sent = transaction.markSent();
            if (sent == null || !session.current()) {
                session.finish(session.current() ? Status.STORAGE_ERROR : Status.LOCKED); return;
            }
            final Iq set = form.submit(session.conversation.getJid(), sent.enabled, sent.password);
            if (!session.current()) { session.finish(Status.LOCKED); return; }
            final boolean enabled = sent.enabled;
            try {
                service.sendIqPacket(session.conversation.getAccount(), set,
                        result -> IO.execute(() -> onResult(session, transaction, enabled, result)), 10L);
            } catch (RuntimeException uncertainSend) {
                session.finish(Status.PENDING); // Intent is durable; do not guess whether SET was sent.
            }
        } catch (Exception error) {
            session.finish(session.current() ? Status.STORAGE_ERROR : Status.LOCKED);
        }
    }

    private static Status status(MucPasswordForm.State state) {
        return state == MucPasswordForm.State.ENABLED ? Status.ENABLED
                : state == MucPasswordForm.State.DISABLED ? Status.DISABLED : Status.UNKNOWN;
    }

    private void discoverState(Session session) {
        if (!session.current()) { session.finish(Status.LOCKED); return; }
        try {
            service.sendIqPacket(session.conversation.getAccount(),
                    service.getIqGenerator().queryDiscoInfo(session.conversation.getJid().asBareJid()),
                    reply -> IO.execute(() -> {
                        if (!session.current()) { session.finish(Status.LOCKED); return; }
                        try { session.finish(status(MucPasswordForm.stateFromDisco(reply))); }
                        catch (RuntimeException malformed) { session.finish(Status.UNKNOWN); }
                    }), 10L);
        } catch (RuntimeException unavailable) {
            session.finish(Status.UNKNOWN);
        }
    }

    private void onResult(Session session, MucPasswordChange transaction, boolean enabled, Iq result) {
        try {
            final MucPasswordChange.Reply reply = result.getType() == Iq.Type.RESULT
                    ? MucPasswordChange.Reply.RESULT : result.getType() == Iq.Type.ERROR
                    ? MucPasswordChange.Reply.ERROR : MucPasswordChange.Reply.TIMEOUT;
            final MucPasswordChange.Outcome outcome = transaction.finish(reply);
            if (outcome != MucPasswordChange.Outcome.SUCCESS) {
                session.finish(outcome == MucPasswordChange.Outcome.REJECTED ? Status.REJECTED
                        : outcome == MucPasswordChange.Outcome.LOCKED ? Status.LOCKED
                        : outcome == MucPasswordChange.Outcome.STORAGE_ERROR ? Status.STORAGE_ERROR : Status.PENDING);
                return;
            }
            try {
                if (!session.current()) { session.finish(Status.LOCKED); return; }
                final String password = ScopedAccountSecretVaultV1.readString(service,
                        session.conversation.getAccount().getUuid(), "MUC_PASSWORD", session.conversation.getUuid());
                if (!session.current()) { session.finish(Status.LOCKED); return; }
                if (enabled != (password != null)) { session.finish(Status.STORAGE_ERROR); return; }
                session.conversation.getMucOptions().setPassword(password);
                if (session.conversation.getBookmark() != null) session.conversation.getBookmark().setPassword(null);
                if (session.conversation.clearLegacyMucPassword()) service.updateConversation(session.conversation);
                session.finish(enabled ? Status.SAVED_ENABLED : Status.SAVED_DISABLED);
                if (session.current()) service.fetchConferenceConfiguration(session.conversation);
            } catch (Exception error) {
                session.finish(Status.STORAGE_ERROR);
            }
        } catch (Exception error) {
            session.finish(session.current() ? Status.STORAGE_ERROR : Status.LOCKED);
        }
    }

    private final class Session {
        final Conversation conversation;
        final String key;
        final long epoch;
        final Consumer<Status> callback;
        boolean finished;
        Session(Conversation conversation, String key, long epoch, Consumer<Status> callback) {
            this.conversation = conversation; this.key = key; this.epoch = epoch; this.callback = callback;
        }
        boolean current() {
            return canManage(conversation)
                    && SecureContentCryptoSessionRuntimeV1.snapshot(service).getEpoch() == epoch
                    && SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(service);
        }
        void finish(Status status) {
            if (finished) return;
            finished = true;
            IN_FLIGHT.remove(key);
            // Only status crosses into UI; epoch is checked again by the Activity before display.
            callback.accept(current() ? status : Status.LOCKED);
        }
    }
}
