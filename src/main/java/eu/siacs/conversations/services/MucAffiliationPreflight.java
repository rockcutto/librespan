package eu.siacs.conversations.services;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Read-only XEP-0045 list observation for one joined room and one bare user JID.
 * Four independent GET replies are not an atomic snapshot or authorization for a later SET.
 * Responses never populate the MUC roster or OMEMO recipients.
 */
public final class MucAffiliationPreflight {
    public enum Affiliation { OWNER, ADMIN, MEMBER, OUTCAST, NONE, UNKNOWN }
    public enum Reason {
        VERIFIED, FORBIDDEN, TIMEOUT, MALFORMED_RESPONSE, CONFLICT, STALE_CONTEXT,
        SERVER_ERROR, UNVERIFIABLE, INVALID_INPUT, CANCELLED
    }
    public static final class Result {
        public final Affiliation affiliation;
        public final Reason reason;

        private Result(Affiliation affiliation, Reason reason) {
            this.affiliation = affiliation;
            this.reason = reason;
        }
    }

    static final long IQ_TIMEOUT_SECONDS = 10L;
    static final long DEADLINE_SECONDS = 4 * IQ_TIMEOUT_SECONDS;
    private static final Affiliation[] LISTS = {
        Affiliation.OWNER, Affiliation.ADMIN, Affiliation.MEMBER, Affiliation.OUTCAST
    };
    private final XmppConnectionService service;
    private final Account account;
    private final Conversation room;
    private final MucOptions options;
    private final XmppConnection connection;
    private final Jid roomJid;
    private final Jid accountJid;
    private final Jid target;
    private final Consumer<Result> callback;
    private final Map<Jid, Affiliation> observed = new HashMap<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile Future<?> deadline;
    // Only the service executor advances stages and reads/writes this observation.
    private Object pending;
    private int index;
    private boolean matchingDomainRule;

    MucAffiliationPreflight(XmppConnectionService service, Account account,
            Conversation room, Jid target, Consumer<Result> callback) {
        this.service = service;
        this.account = account;
        this.room = room;
        this.target = target;
        this.callback = Objects.requireNonNull(callback);
        this.options = room == null ? null : room.getMucOptions();
        this.connection = account == null ? null : account.getXmppConnection();
        this.roomJid = room == null ? null : room.getJid().asBareJid();
        this.accountJid = account == null ? null : account.getJid();
    }

    void start() {
        dispatch(() -> {
            if (finished.get()) return;
            if (account == null || room == null || target == null
                    || target instanceof Jid.Invalid || !target.isBareJid()
                    || target.getLocal() == null || roomJid.getLocal() == null
                    || roomJid.equals(accountJid.asBareJid())) {
                finish(Affiliation.UNKNOWN, Reason.INVALID_INPUT);
                return;
            }
            if (!current()) { finish(Affiliation.UNKNOWN, Reason.STALE_CONTEXT); return; }
            try {
                deadline = service.scheduleMucAffiliationPreflight(() ->
                        finish(Affiliation.UNKNOWN, current() ? Reason.TIMEOUT : Reason.STALE_CONTEXT),
                        DEADLINE_SECONDS);
                if (finished.get()) deadline.cancel(false);
                else next();
            } catch (RuntimeException unavailable) {
                finish(Affiliation.UNKNOWN, Reason.STALE_CONTEXT);
            }
        });
    }

    public boolean isDone() { return finished.get(); }

    public void cancel() { finish(Affiliation.UNKNOWN, Reason.CANCELLED); }

    private boolean current() {
        return roomJid.equals(room.getJid().asBareJid()) && accountJid.equals(account.getJid())
                && service.isCurrentMucAffiliationPreflight(account, room, options, connection);
    }

    private void next() {
        if (finished.get()) return;
        if (!current()) { finish(Affiliation.UNKNOWN, Reason.STALE_CONTEXT); return; }
        if (index == LISTS.length) {
            if (matchingDomainRule) finish(Affiliation.UNKNOWN, Reason.UNVERIFIABLE);
            else finish(observed.getOrDefault(target, Affiliation.NONE), Reason.VERIFIED);
            return;
        }
        final Affiliation requested = LISTS[index++];
        final Object token = new Object();
        pending = token;
        // The existing transport correlates ID and exact bare room sender for non-server IQs.
        // TIMEOUT is synthetic and has neither from nor id; do not reject it as malformed.
        try {
            service.sendIqPacket(account, service.getIqGenerator().queryAffiliation(
                    room, requested.name().toLowerCase(Locale.ROOT)),
                    reply -> dispatch(() -> accept(token, requested, reply)), IQ_TIMEOUT_SECONDS);
        } catch (RuntimeException unavailable) {
            finish(Affiliation.UNKNOWN, Reason.SERVER_ERROR);
        }
    }

    private void accept(Object token, Affiliation requested, Iq reply) {
        if (finished.get() || pending != token) return;
        pending = null;
        if (!current()) { finish(Affiliation.UNKNOWN, Reason.STALE_CONTEXT); return; }
        Reason failure;
        try {
            if (reply == null) failure = Reason.MALFORMED_RESPONSE;
            else if (reply.getType() == Iq.Type.TIMEOUT) failure = Reason.TIMEOUT;
            else if (reply.getType() == Iq.Type.ERROR) {
                failure = "forbidden".equals(reply.getErrorCondition())
                        ? Reason.FORBIDDEN : Reason.SERVER_ERROR;
            } else if (reply.getType() != Iq.Type.RESULT) failure = Reason.MALFORMED_RESPONSE;
            else failure = readList(reply, requested);
        } catch (IllegalArgumentException malformed) {
            failure = Reason.MALFORMED_RESPONSE;
        }
        if (failure != null) finish(Affiliation.UNKNOWN, failure);
        else next();
    }

    /** Null denotes a complete, structurally valid list, including an empty list. */
    private Reason readList(Iq reply, Affiliation requested) {
        if (reply.getChildren().size() != 1) return Reason.MALFORMED_RESPONSE;
        final Element query = reply.findChild("query", Namespace.MUC_ADMIN);
        if (query == null) return Reason.MALFORMED_RESPONSE;
        for (String attribute : query.getAttributes().keySet()) {
            if (!"xmlns".equals(attribute) && !"complete".equals(attribute)) {
                return Reason.MALFORMED_RESPONSE; // No supported pagination/partial-list extension.
            }
        }
        final String complete = query.getAttribute("complete");
        if (complete != null && !"true".equals(complete) && !"1".equals(complete)) {
            return Reason.MALFORMED_RESPONSE;
        }
        for (Element item : query.getChildren()) {
            if (!"item".equals(item.getName())
                    || (item.getNamespace() != null && !Namespace.MUC_ADMIN.equals(item.getNamespace()))
                    || !requested.name().toLowerCase(Locale.ROOT).equals(item.getAttribute("affiliation"))) {
                return Reason.MALFORMED_RESPONSE;
            }
            final Jid jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
            if (jid == null || !jid.isBareJid()) return Reason.MALFORMED_RESPONSE;
            if (observed.putIfAbsent(jid, requested) != null) return Reason.CONFLICT;
            // Do not guess server-specific domain ACL precedence over a per-user affiliation.
            if (jid.getLocal() == null && jid.equals(target.getDomain())) matchingDomainRule = true;
        }
        return null;
    }

    private void dispatch(Runnable work) {
        if (finished.get()) return;
        try { service.executeMucAffiliationPreflight(work); }
        catch (RejectedExecutionException closed) { finish(Affiliation.UNKNOWN, Reason.STALE_CONTEXT); }
    }

    private void finish(Affiliation affiliation, Reason reason) {
        if (!finished.compareAndSet(false, true)) return;
        final Future<?> timeout = deadline;
        if (timeout != null) timeout.cancel(false);
        callback.accept(new Result(affiliation, reason));
    }
}
