package eu.siacs.conversations.services;

import android.os.Bundle;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Owner GET -> fresh GET/merge -> SET -> owner GET/verify. No optimistic success or stored form. */
public final class MucRoomConfigurationController {
    public enum Status { READY, SAVED, REJECTED, ERROR, TIMEOUT, UNSUPPORTED, CONFLICT, UNCONFIRMED, BUSY }
    public static final class Result {
        public final Status status;
        public final MucRoomConfiguration configuration;
        Result(Status status, MucRoomConfiguration configuration) {
            this.status = status; this.configuration = configuration;
        }
    }
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private final XmppConnectionService service;
    private final Conversation conversation;
    private final MucOptions options;
    private final String key;
    private Object pending;
    private Consumer<Result> callback;
    private boolean busy;
    private boolean cancelled;

    public MucRoomConfigurationController(XmppConnectionService service, Conversation conversation) {
        this.service = service; this.conversation = conversation;
        this.options = conversation.getMucOptions();
        this.key = conversation.getAccount().getUuid() + ":" + conversation.getJid().asBareJid();
    }

    private boolean current() {
        return !cancelled && MucPasswordController.canManage(conversation)
                && conversation.getStatus() != Conversation.STATUS_ARCHIVED
                && conversation.getMucOptions() == options && options.online();
    }

    private synchronized boolean begin(Consumer<Result> callback) {
        if (!current()) { callback.accept(new Result(Status.REJECTED, null)); return false; }
        if (busy || !IN_FLIGHT.add(key)) { callback.accept(new Result(Status.BUSY, null)); return false; }
        busy = true; this.callback = callback;
        return true;
    }

    public void query(Consumer<Result> callback) {
        if (!begin(callback)) return;
        get((form) -> finish(Status.READY, new MucRoomConfiguration(form)));
    }

    public void save(MucRoomConfiguration original, Bundle requested, Consumer<Result> callback) {
        final Bundle changes = new Bundle(requested);
        if (!begin(callback)) return;
        if (changes.isEmpty()) { finish(Status.READY, original); return; }
        get(form -> {
            final MucRoomConfiguration fresh = new MucRoomConfiguration(form);
            if (!original.matches(fresh, changes)) { finish(Status.CONFLICT, fresh); return; }
            if (!fresh.permits(changes, options)) { finish(Status.UNSUPPORTED, fresh); return; }
            final Iq set = new Iq(Iq.Type.SET);
            set.setTo(conversation.getJid().asBareJid());
            set.query(MucPasswordForm.OWNER).addChild(MucRoomConfiguration.submission(form, changes));
            send(set, reply -> {
                if (!result(reply)) return;
                get(readback -> {
                    final MucRoomConfiguration actual = new MucRoomConfiguration(readback);
                    if (!actual.verifies(changes)) { finish(Status.UNCONFIRMED, actual); return; }
                    // Refresh cached disco only after the owner form confirms the requested values.
                    service.fetchConferenceConfiguration(conversation);
                    finish(Status.SAVED, actual);
                });
            });
        });
    }

    private void get(Consumer<Element> next) {
        send(MucPasswordForm.request(conversation.getJid()), reply -> {
            if (!result(reply)) return;
            final Element query = reply.findChild("query", MucPasswordForm.OWNER);
            final Element form = query == null ? null : query.findChild("x", Namespace.DATA);
            try { next.accept(form); }
            catch (IllegalArgumentException malformed) { finish(Status.UNSUPPORTED, null); }
        });
    }

    private void send(Iq iq, Consumer<Iq> next) {
        final Object token = new Object();
        synchronized (this) {
            if (!busy || !current()) { finish(Status.REJECTED, null); return; }
            pending = token;
        }
        try {
            service.sendIqPacket(conversation.getAccount(), iq, reply -> {
                synchronized (this) {
                    if (!busy || pending != token) return;
                    pending = null;
                    if (!current()) { finish(Status.REJECTED, null); return; }
                }
                next.accept(reply);
            }, 10L);
        } catch (RuntimeException unavailable) { finish(Status.ERROR, null); }
    }

    private boolean result(Iq reply) {
        if (reply.getType() == Iq.Type.RESULT) return true;
        finish(reply.getType() == Iq.Type.TIMEOUT ? Status.TIMEOUT
                : reply.getType() == Iq.Type.ERROR ? Status.REJECTED : Status.ERROR, null);
        return false;
    }

    private void finish(Status status, MucRoomConfiguration configuration) {
        final Consumer<Result> listener;
        synchronized (this) {
            if (!busy) return;
            busy = false; pending = null;
            IN_FLIGHT.remove(key);
            listener = callback; callback = null;
        }
        listener.accept(new Result(status, configuration));
    }

    public synchronized void cancel() {
        cancelled = true; pending = null;
        if (busy) { busy = false; IN_FLIGHT.remove(key); }
        callback = null;
    }
}
