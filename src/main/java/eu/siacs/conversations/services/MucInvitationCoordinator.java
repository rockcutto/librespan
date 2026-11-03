package eu.siacs.conversations.services;

import android.content.Context;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Existing-room invitations only. No grants, mediated admission hooks, or bootstrap bypass. */
public final class MucInvitationCoordinator {
    public enum Status {
        HANDED_OFF, BANNED, NEEDS_MEMBERSHIP, FORBIDDEN, UNVERIFIABLE, TIMEOUT,
        STALE, CANCELLED, BUSY, INVALID, UNCONFIRMED
    }
    public static final class Summary {
        public final Map<Jid, Status> outcomes;
        private Summary(Map<Jid, Status> outcomes) {
            this.outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        }
        public int handedOff() {
            int count = 0;
            for (Status status : outcomes.values()) if (status == Status.HANDED_OFF) count++;
            return count;
        }
        public String describe(Context context) {
            final StringBuilder text = new StringBuilder(context.getString(
                    R.string.muc_safe_invite_summary, handedOff(), outcomes.size()));
            if (handedOff() > 0 && handedOff() < outcomes.size()) {
                text.append('\n').append(context.getString(R.string.muc_safe_invite_partial));
            }
            final Map<Status, Integer> failures = new EnumMap<>(Status.class);
            for (Status status : outcomes.values()) if (status != Status.HANDED_OFF) {
                failures.put(status, failures.getOrDefault(status, 0) + 1);
            }
            for (Map.Entry<Status, Integer> entry : failures.entrySet()) {
                text.append('\n').append(context.getString(R.string.muc_safe_invite_reason_count,
                        context.getString(label(entry.getKey())), entry.getValue()));
            }
            return text.toString();
        }
        private static int label(Status status) {
            switch (status) {
                case BANNED: return R.string.muc_invite_banned;
                case NEEDS_MEMBERSHIP: return R.string.muc_safe_invite_membership;
                case FORBIDDEN: return R.string.muc_safe_invite_forbidden;
                case TIMEOUT: return R.string.muc_safe_invite_timeout;
                case STALE: return R.string.muc_safe_invite_interrupted;
                case CANCELLED: return R.string.muc_safe_invite_cancelled;
                case BUSY: return R.string.muc_safe_invite_busy;
                case INVALID: return R.string.invalid_jid;
                case UNCONFIRMED: return R.string.muc_safe_invite_unconfirmed;
                default: return R.string.muc_safe_invite_unverifiable;
            }
        }
    }
    public final class Ticket {
        private final Consumer<Summary> callback;
        private final int total;
        private final Map<Jid, Status> results = new LinkedHashMap<>();
        private final ArrayList<Operation> operations = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean done;
        private Ticket(int total, Consumer<Summary> callback) {
            this.total = total;
            this.callback = callback;
        }
        public synchronized boolean isDone() { return done; }
        private void record(Jid target, Status status) {
            final Summary summary;
            synchronized (this) {
                if (done || results.containsKey(target)) return;
                results.put(target, status);
                if (results.size() != total) return;
                done = true;
                summary = new Summary(results);
            }
            callback.accept(summary);
        }
        public void cancel() {
            cancelled.set(true); // Visible before queued network work, including Activity destruction.
            final ArrayList<Operation> pending;
            synchronized (this) { pending = new ArrayList<>(operations); }
            for (Operation operation : pending) dispatch(operation, () -> operation.finish(Status.CANCELLED));
        }
    }

    private final XmppConnectionService service;
    private final ConcurrentHashMap<String, Operation> inFlight = new ConcurrentHashMap<>();
    public MucInvitationCoordinator(XmppConnectionService service) { this.service = service; }

    public Ticket invite(Conversation room, Collection<Jid> recipients, Consumer<Summary> callback) {
        Objects.requireNonNull(room);
        Objects.requireNonNull(callback);
        final LinkedHashSet<Jid> unique = new LinkedHashSet<>();
        for (Jid jid : recipients) unique.add(jid == null || jid instanceof Jid.Invalid ? null : jid.asBareJid());
        final Ticket ticket = new Ticket(unique.size(), callback);
        if (unique.isEmpty()) {
            ticket.done = true;
            callback.accept(new Summary(Collections.emptyMap()));
            return ticket;
        }
        for (Jid target : unique) {
            if (target == null || target.getLocal() == null) { ticket.record(target, Status.INVALID); continue; }
            final Operation operation = new Operation(room, target, ticket);
            if (inFlight.putIfAbsent(operation.key, operation) != null) { ticket.record(target, Status.BUSY); continue; }
            synchronized (ticket) { ticket.operations.add(operation); }
            dispatch(operation, operation::start);
        }
        return ticket;
    }

    private void dispatch(Operation operation, Runnable work) {
        if (operation.done.get()) return;
        try { service.executeMucAffiliationPreflight(work); }
        catch (RejectedExecutionException closed) { operation.finish(Status.STALE); }
    }

    private final class Operation {
        final Conversation room;
        final Account account;
        final MucOptions options;
        final XmppConnection connection;
        final Jid roomJid;
        final Jid accountJid;
        final Jid target;
        final MucOptions.Affiliation actor;
        final Ticket ticket;
        final String key;
        final AtomicBoolean done = new AtomicBoolean();
        Object discoToken;
        Future<?> deadline;
        MucAffiliationPreflight preflight;
        Operation(Conversation room, Jid target, Ticket ticket) {
            this.room = room;
            this.target = target;
            this.ticket = ticket;
            account = room.getAccount();
            options = room.getMucOptions();
            connection = account.getXmppConnection();
            roomJid = room.getJid().asBareJid();
            accountJid = account.getJid();
            actor = options.getSelf().getAffiliation();
            key = account.getUuid() + ":" + roomJid + ":" + target;
        }
        boolean current() {
            return roomJid.equals(room.getJid().asBareJid()) && accountJid.equals(account.getJid())
                    && options.getSelf().getAffiliation() == actor
                    && service.isCurrentMucAffiliationPreflight(account, room, options, connection);
        }
        boolean live() {
            if (done.get()) return false;
            if (ticket.cancelled.get()) { finish(Status.CANCELLED); return false; }
            if (!current()) { finish(Status.STALE); return false; }
            return true;
        }
        boolean cachedBan() {
            final MucOptions.User user = options.findUserByRealJid(target);
            return user != null && user.getAffiliation() == MucOptions.Affiliation.OUTCAST;
        }
        void start() {
            if (!live()) return;
            if (cachedBan()) { finish(Status.BANNED); return; }
            try {
                deadline = service.scheduleMucAffiliationPreflight(() -> {
                    if (live()) finish(Status.TIMEOUT);
                }, 50L); // One disco budget plus the unchanged F2A whole-request budget.
                final Object token = new Object();
                discoToken = token;
                service.sendIqPacket(account, service.getIqGenerator().queryDiscoInfo(roomJid),
                        reply -> dispatch(this, () -> disco(token, reply)), 10L);
            } catch (RuntimeException unavailable) { finish(Status.STALE); }
        }
        void disco(Object token, Iq reply) {
            if (!live() || discoToken != token) return;
            discoToken = null;
            if (reply == null) { finish(Status.UNVERIFIABLE); return; }
            final Iq.Type type;
            try { type = reply.getType(); }
            catch (IllegalArgumentException malformed) { finish(Status.UNVERIFIABLE); return; }
            if (type == Iq.Type.TIMEOUT) { finish(Status.TIMEOUT); return; }
            if (type != Iq.Type.RESULT) {
                finish("forbidden".equals(reply.getErrorCondition()) ? Status.FORBIDDEN : Status.UNVERIFIABLE);
                return;
            }
            final Element query = reply.findChild("query", Namespace.DISCO_INFO);
            if (query == null || reply.getChildren().size() != 1) { finish(Status.UNVERIFIABLE); return; }
            boolean open = false, members = false;
            for (Element feature : query.getChildren()) if ("feature".equals(feature.getName())
                    && (feature.getNamespace() == null || Namespace.DISCO_INFO.equals(feature.getNamespace()))) {
                open |= "muc_open".equals(feature.getAttribute("var"));
                members |= "muc_membersonly".equals(feature.getAttribute("var"));
            }
            if (open == members) { finish(Status.UNVERIFIABLE); return; }
            if (open) handoff(); // Direct user-to-user invitation cannot run a room membership hook.
            else {
                preflight = service.preflightMucAffiliation(account, room, target,
                        result -> dispatch(this, () -> checked(result)));
                if (done.get()) preflight.cancel();
            }
        }
        void checked(MucAffiliationPreflight.Result result) {
            if (!live()) return;
            switch (result.affiliation) {
                case OWNER:
                case ADMIN:
                case MEMBER: handoff(); return;
                case OUTCAST: finish(Status.BANNED); return;
                case NONE: finish(Status.NEEDS_MEMBERSHIP); return;
                default:
                    switch (result.reason) {
                        case FORBIDDEN: finish(Status.FORBIDDEN); return;
                        case TIMEOUT: finish(Status.TIMEOUT); return;
                        case STALE_CONTEXT:
                        case CANCELLED: finish(Status.STALE); return;
                        default: finish(Status.UNVERIFIABLE);
                    }
            }
        }
        void handoff() {
            if (!live()) return;
            if (cachedBan()) { finish(Status.BANNED); return; }
            try {
                service.directInvite(room, target); // Reuse existing room JID/password packet generation.
                finish(Status.HANDED_OFF); // Neither delivery nor server admission is acknowledged here.
            } catch (RuntimeException uncertain) { finish(Status.UNCONFIRMED); }
        }
        void finish(Status status) {
            if (!done.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            if (preflight != null) preflight.cancel();
            inFlight.remove(key, this);
            ticket.record(target, status);
        }
    }
}
