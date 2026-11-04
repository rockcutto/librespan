package eu.siacs.conversations.services;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Practical XEP-0045 admission, separate from invitations and local roster updates.
 * A verified NONE followed by SET MEMBER is NOT compare-and-set: another actor may ban or
 * promote the target in between. Readback verifies eligibility; it cannot prevent that race.
 */
public final class MucMembershipAdmission {
    public enum Status {
        ALREADY_ELIGIBLE, GRANTED_VERIFIED, ELIGIBLE_VERIFIED, BANNED, NOT_GRANTED,
        UNKNOWN, FORBIDDEN, ERROR, TIMEOUT, STALE, CANCELLED, WRITE_UNCERTAIN, BUSY, INVALID
    }
    public enum WriteState { NOT_ATTEMPTED, UNCONFIRMED, REJECTED, ACKNOWLEDGED }

    public static final class Result {
        public final Status status;
        public final MucAffiliationPreflight.Affiliation affiliation;
        public final MucAffiliationPreflight.Reason reason;
        public final WriteState writeState;

        private Result(Status status, MucAffiliationPreflight.Affiliation affiliation,
                MucAffiliationPreflight.Reason reason, WriteState writeState) {
            this.status = status;
            this.affiliation = affiliation;
            this.reason = reason;
            this.writeState = writeState;
        }

        public boolean isEligible() {
            return status == Status.ALREADY_ELIGIBLE || status == Status.GRANTED_VERIFIED
                    || status == Status.ELIGIBLE_VERIFIED;
        }
    }

    static final long DEADLINE_SECONDS = 2 * MucAffiliationPreflight.DEADLINE_SECONDS
            + MucAffiliationPreflight.IQ_TIMEOUT_SECONDS;
    private enum Phase { PREFLIGHT, WRITE, READBACK, REJECTED, TERMINAL }
    private static final class State {
        final Phase phase;
        final WriteState write;
        State(Phase phase, WriteState write) { this.phase = phase; this.write = write; }
    }

    private final XmppConnectionService service;
    private final ConcurrentHashMap<String, Request> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> accountEpochs = new ConcurrentHashMap<>();

    MucMembershipAdmission(XmppConnectionService service) { this.service = service; }

    Request admit(Account account, Conversation room, Jid bareJid, Consumer<Result> callback) {
        final Request request = new Request(account, room, bareJid, callback);
        if (!request.validInput()) {
            request.finish(Status.INVALID, MucAffiliationPreflight.Affiliation.UNKNOWN,
                    MucAffiliationPreflight.Reason.INVALID_INPUT);
        } else if (!request.current()) {
            request.abort(Status.STALE, MucAffiliationPreflight.Reason.STALE_CONTEXT);
        } else if (inFlight.putIfAbsent(request.key, request) != null) {
            request.finish(Status.BUSY, MucAffiliationPreflight.Affiliation.UNKNOWN,
                    MucAffiliationPreflight.Reason.UNVERIFIABLE);
        } else {
            request.dispatch(request::begin);
        }
        return request;
    }

    // Called on every non-ONLINE service status event, even if the connection object is reused.
    void onAccountUnavailable(Account account) {
        accountEpochs.put(account.getUuid(), new Object());
        for (Request request : inFlight.values()) {
            if (request.account == account) request.abort(Status.STALE,
                    MucAffiliationPreflight.Reason.STALE_CONTEXT);
        }
    }

    public final class Request {
        private final Account account;
        private final Conversation room;
        private final Jid target;
        private final MucOptions options;
        private final XmppConnection connection;
        private final Jid accountJid;
        private final Jid roomJid;
        private final MucOptions.Affiliation actor;
        private final String key;
        private final Object accountEpoch;
        private final Consumer<Result> callback;
        private final AtomicReference<State> state = new AtomicReference<>(
                new State(Phase.PREFLIGHT, WriteState.NOT_ATTEMPTED));
        private volatile Future<?> deadline;
        private volatile MucAffiliationPreflight initial;
        private volatile MucAffiliationPreflight readback;

        private Request(Account account, Conversation room, Jid target, Consumer<Result> callback) {
            this.account = account;
            this.room = room;
            this.target = target;
            this.callback = Objects.requireNonNull(callback);
            options = room == null ? null : room.getMucOptions();
            connection = account == null ? null : account.getXmppConnection();
            accountJid = account == null ? null : account.getJid();
            roomJid = room == null ? null : room.getJid().asBareJid();
            actor = options == null ? null : options.getSelf().getAffiliation();
            accountEpoch = account == null ? null : accountEpochs.get(account.getUuid());
            key = account == null || room == null || target == null ? null
                    : account.getUuid() + ":" + room.getUuid() + ":" + target;
        }

        public boolean isDone() { return state.get().phase == Phase.TERMINAL; }

        /** A cancellation cannot undo a SET already handed to the transport. */
        public void cancel() { abort(Status.CANCELLED, MucAffiliationPreflight.Reason.CANCELLED); }

        private boolean validInput() {
            return account != null && room != null && target != null
                    && !(target instanceof Jid.Invalid) && target.isBareJid()
                    && target.getLocal() != null && roomJid.getLocal() != null
                    && !roomJid.equals(accountJid.asBareJid());
        }

        private boolean current() {
            return validInput() && accountEpoch == accountEpochs.get(account.getUuid())
                    && roomJid.equals(room.getJid().asBareJid())
                    && accountJid.equals(account.getJid())
                    && actor == options.getSelf().getAffiliation()
                    && service.isCurrentMucAffiliationPreflight(account, room, options, connection);
        }

        private boolean live() {
            if (isDone()) return false;
            if (current()) return true;
            abort(Status.STALE, MucAffiliationPreflight.Reason.STALE_CONTEXT);
            return false;
        }

        private void begin() {
            if (!live()) return;
            try {
                deadline = service.scheduleMucAffiliationPreflight(() -> {
                    if (live()) abort(Status.TIMEOUT, MucAffiliationPreflight.Reason.TIMEOUT);
                }, DEADLINE_SECONDS);
                if (isDone()) { deadline.cancel(false); return; }
                initial = service.preflightMucAffiliation(account, room, target,
                        result -> dispatch(() -> observed(result, false)));
                if (isDone()) initial.cancel();
            } catch (RuntimeException unavailable) {
                abort(Status.ERROR, MucAffiliationPreflight.Reason.SERVER_ERROR);
            }
        }

        private void observed(MucAffiliationPreflight.Result result, boolean afterWrite) {
            if (!live() || state.get().phase != (afterWrite ? Phase.READBACK : Phase.PREFLIGHT)) return;
            switch (result.affiliation) {
                case OWNER:
                case ADMIN:
                case MEMBER:
                    finish(!afterWrite ? Status.ALREADY_ELIGIBLE
                                    : result.affiliation == MucAffiliationPreflight.Affiliation.MEMBER
                                            ? Status.GRANTED_VERIFIED : Status.ELIGIBLE_VERIFIED,
                            result.affiliation, result.reason);
                    return;
                case OUTCAST:
                    finish(Status.BANNED, result.affiliation, result.reason);
                    return;
                case NONE:
                    if (afterWrite) finish(Status.NOT_GRANTED, result.affiliation, result.reason);
                    else grant();
                    return;
                default:
                    abort(failureStatus(result.reason), result.reason);
            }
        }

        private void grant() {
            if (!live()) return;
            if (actor != MucOptions.Affiliation.OWNER && actor != MucOptions.Affiliation.ADMIN) {
                abort(Status.FORBIDDEN, MucAffiliationPreflight.Reason.FORBIDDEN);
                return;
            }
            final MucOptions.User cached = options.findUserByRealJid(target);
            if (cached != null && cached.getAffiliation() == MucOptions.Affiliation.OUTCAST) {
                // Extra veto only. A cache miss never proves NONE.
                finish(Status.BANNED, MucAffiliationPreflight.Affiliation.OUTCAST,
                        MucAffiliationPreflight.Reason.UNVERIFIABLE);
                return;
            }
            final Iq packet = service.getIqGenerator().changeAffiliation(room, target, "member");
            final State before = state.get();
            if (!live() || before.phase != Phase.PREFLIGHT) return;
            final State writing = new State(Phase.WRITE, WriteState.UNCONFIRMED);
            // Local send commitment: cancel before this CAS prevents the SET. Anything after
            // it is conservatively uncertain, including an exception during transport handoff.
            if (!state.compareAndSet(before, writing) || state.get() != writing) return;
            try {
                service.sendIqPacket(account, packet, reply -> dispatch(() -> written(reply)),
                        MucAffiliationPreflight.IQ_TIMEOUT_SECONDS);
            } catch (RuntimeException interrupted) {
                abort(Status.ERROR, MucAffiliationPreflight.Reason.SERVER_ERROR);
            }
        }

        private void written(Iq reply) {
            if (!live()) return;
            final State writing = state.get();
            if (writing.phase != Phase.WRITE) return;
            try {
                if (reply == null) {
                    abort(Status.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
                } else if (reply.getType() == Iq.Type.TIMEOUT) {
                    abort(Status.TIMEOUT, MucAffiliationPreflight.Reason.TIMEOUT);
                } else if (reply.getType() == Iq.Type.ERROR) {
                    final String condition = reply.getErrorCondition();
                    if (condition == null) {
                        abort(Status.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
                        return;
                    }
                    final boolean forbidden = "forbidden".equals(condition);
                    if (state.compareAndSet(writing, new State(Phase.REJECTED, WriteState.REJECTED))) {
                        abort(forbidden ? Status.FORBIDDEN : Status.ERROR, forbidden
                                ? MucAffiliationPreflight.Reason.FORBIDDEN
                                : MucAffiliationPreflight.Reason.SERVER_ERROR);
                    }
                } else if (reply.getType() != Iq.Type.RESULT) {
                    abort(Status.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
                } else if (state.compareAndSet(writing, new State(Phase.READBACK, WriteState.ACKNOWLEDGED))) {
                    if (!live()) return;
                    readback = service.preflightMucAffiliation(account, room, target,
                            result -> dispatch(() -> observed(result, true)));
                    if (isDone()) readback.cancel();
                }
            } catch (IllegalArgumentException malformed) {
                abort(Status.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
            } catch (RuntimeException unavailable) {
                abort(Status.ERROR, MucAffiliationPreflight.Reason.SERVER_ERROR);
            }
        }

        private void dispatch(Runnable work) {
            if (isDone()) return;
            try { service.executeMucAffiliationPreflight(work); }
            catch (RejectedExecutionException closed) {
                abort(Status.STALE, MucAffiliationPreflight.Reason.STALE_CONTEXT);
            }
        }

        private void abort(Status status, MucAffiliationPreflight.Reason reason) {
            finish(status, MucAffiliationPreflight.Affiliation.UNKNOWN, reason);
        }

        private void finish(Status status, MucAffiliationPreflight.Affiliation affiliation,
                MucAffiliationPreflight.Reason reason) {
            Result result;
            while (true) {
                final State previous = state.get();
                if (previous.phase == Phase.TERMINAL) return;
                Status outcome = status;
                MucAffiliationPreflight.Affiliation actual = affiliation;
                MucAffiliationPreflight.Reason cause = reason;
                if ((status == Status.ALREADY_ELIGIBLE || status == Status.GRANTED_VERIFIED
                        || status == Status.ELIGIBLE_VERIFIED) && !current()) {
                    outcome = Status.STALE;
                    actual = MucAffiliationPreflight.Affiliation.UNKNOWN;
                    cause = MucAffiliationPreflight.Reason.STALE_CONTEXT;
                }
                if (previous.write == WriteState.UNCONFIRMED) {
                    outcome = Status.WRITE_UNCERTAIN;
                    actual = MucAffiliationPreflight.Affiliation.UNKNOWN;
                }
                final State terminal = new State(Phase.TERMINAL, previous.write);
                result = new Result(outcome, actual, cause, previous.write);
                if (state.compareAndSet(previous, terminal)) break;
            }
            if (key != null) inFlight.remove(key, this);
            final Future<?> timer = deadline;
            if (timer != null) timer.cancel(false);
            final MucAffiliationPreflight first = initial;
            if (first != null) first.cancel();
            final MucAffiliationPreflight last = readback;
            if (last != null) last.cancel();
            callback.accept(result);
        }
    }

    private static Status failureStatus(MucAffiliationPreflight.Reason reason) {
        switch (reason) {
            case FORBIDDEN: return Status.FORBIDDEN;
            case TIMEOUT: return Status.TIMEOUT;
            case STALE_CONTEXT: return Status.STALE;
            case CANCELLED: return Status.CANCELLED;
            case INVALID_INPUT: return Status.INVALID;
            default: return Status.UNKNOWN;
        }
    }
}
