package eu.siacs.conversations.services;

import android.content.Context;
import android.os.Bundle;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** New-room setup only. Admission is delegated unchanged to P1-F2C; no mediated fallback. */
public final class MucBootstrapCoordinator {
    public enum RoomState { CONFIGURED, NOT_CONFIGURED, UNKNOWN }
    public enum Reason { COMPLETE, REJECTED, MALFORMED, TIMEOUT, STALE, CANCELLED, EXISTING_ROOM }
    public enum Invitation { HANDED_OFF, NOT_SENT, UNCONFIRMED }
    public static final class Recipient {
        public final MucMembershipAdmission.Result admission;
        public final Invitation invitation;
        public final boolean admissionUnconfirmed;
        Recipient(MucMembershipAdmission.Result admission, Invitation invitation) {
            this(admission, invitation, admission != null
                    && admission.writeState == MucMembershipAdmission.WriteState.UNCONFIRMED);
        }
        Recipient(MucMembershipAdmission.Result admission, Invitation invitation, boolean unconfirmed) {
            this.admission = admission; this.invitation = invitation; this.admissionUnconfirmed = unconfirmed;
        }
    }
    public static final class Result {
        public final Conversation conversation;
        public final RoomState roomState;
        public final boolean serverCreated;
        public final boolean current;
        public final Reason reason;
        public final Map<Jid, Recipient> recipients;
        public final Map<Jid, Invitation> ownResources;
        Result(Request request, Reason reason) {
            conversation = request.room; roomState = request.roomState;
            serverCreated = request.serverCreated; current = request.current(); this.reason = reason;
            synchronized (request.results) { recipients = Collections.unmodifiableMap(new LinkedHashMap<>(request.results)); }
            synchronized (request.ownResults) { ownResources = Collections.unmodifiableMap(new LinkedHashMap<>(request.ownResults)); }
        }
        public int handedOff() {
            int count = 0;
            for (Recipient result : recipients.values()) if (result.invitation == Invitation.HANDED_OFF) count++;
            return count;
        }
        public String describe(Context context) {
            if (roomState != RoomState.CONFIGURED) return context.getString(
                    roomState == RoomState.UNKNOWN ? R.string.muc_bootstrap_unknown : R.string.conference_creation_failed);
            int existing = 0, granted = 0, banned = 0, denied = 0, failed = 0, uncertain = 0, own = 0;
            for (Recipient result : recipients.values()) {
                if (result.admission != null) {
                    if (result.admission.status == MucMembershipAdmission.Status.ALREADY_ELIGIBLE) existing++;
                    if (result.admission.status == MucMembershipAdmission.Status.GRANTED_VERIFIED) granted++;
                    if (result.admission.status == MucMembershipAdmission.Status.BANNED) banned++;
                    if (result.admission.status == MucMembershipAdmission.Status.FORBIDDEN) denied++;
                }
                if (result.invitation == Invitation.UNCONFIRMED || result.admissionUnconfirmed) uncertain++;
                else if (result.invitation == Invitation.NOT_SENT && (result.admission == null
                        || (result.admission.status != MucMembershipAdmission.Status.BANNED
                            && result.admission.status != MucMembershipAdmission.Status.FORBIDDEN))) failed++;
            }
            for (Invitation result : ownResources.values()) if (result == Invitation.HANDED_OFF) own++;
            String text = context.getString(R.string.muc_bootstrap_created) + "\n"
                    + context.getString(R.string.muc_safe_invite_summary, handedOff(), recipients.size()) + "\n"
                    + context.getString(R.string.muc_bootstrap_access, existing, granted) + "\n"
                    + context.getString(R.string.muc_bootstrap_other_devices, own, ownResources.size());
            if (handedOff() != recipients.size() || own != ownResources.size() || reason != Reason.COMPLETE) {
                text += "\n" + context.getString(R.string.muc_bootstrap_partial, banned, denied, failed, uncertain);
                if (reason == Reason.STALE) text += "\n" + context.getString(R.string.muc_safe_invite_interrupted);
                if (reason == Reason.CANCELLED) text += "\n" + context.getString(R.string.muc_safe_invite_cancelled);
                if (reason == Reason.TIMEOUT) text += "\n" + context.getString(R.string.muc_safe_invite_timeout);
            }
            return text;
        }
    }

    static final long IQ_SECONDS = 10L;
    static final long DEADLINE_SECONDS = Config.CONNECT_TIMEOUT + 4 * IQ_SECONDS
            + MucMembershipAdmission.DEADLINE_SECONDS;
    private static final String PERSISTENT = "muc#roomconfig_persistentroom";
    private static final String[] REQUIRED = {PERSISTENT, MucRoomConfiguration.MEMBERS,
            MucRoomConfiguration.PUBLIC, MucRoomConfiguration.WHOIS, MucRoomConfiguration.INVITES};
    private enum Phase { START, JOIN, FORM, SET, VERIFY, DISCO, ADMISSION }
    private final XmppConnectionService service;
    private final ConcurrentHashMap<String, Request> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> epochs = new ConcurrentHashMap<>();
    MucBootstrapCoordinator(XmppConnectionService service) { this.service = service; }

    Request start(Conversation room, String name, Iterable<Jid> jids, Consumer<Result> callback) {
        Request request = new Request(room, name, jids, callback);
        if (!request.current()) {
            request.roomState = RoomState.NOT_CONFIGURED; request.finish(Reason.STALE);
        } else if (active.putIfAbsent(room.getUuid(), request) != null) request.finish(Reason.REJECTED);
        else request.dispatch(request::begin);
        return request;
    }
    void joinStarted(Conversation room, MucOptions options) {
        Request request = active.get(room.getUuid());
        if (request != null) request.dispatch(() -> request.bind(options));
    }
    void selfPresence(MucOptions options, Presence packet, boolean correlated) {
        Request request = active.get(options.getConversation().getUuid());
        if (request == null || !correlated || packet.getAttribute("type") != null) return;
        final Element x = packet.findChild("x", Namespace.MUC_USER);
        boolean self = false, created = false;
        if (x != null) for (Element status : x.getChildren()) if ("status".equals(status.getName())) {
            self |= "110".equals(status.getAttribute("code"));
            created |= "201".equals(status.getAttribute("code"));
        }
        final Element item = x == null ? null : x.findChild("item");
        final boolean owner = item != null && "owner".equals(item.getAttribute("affiliation"));
        final boolean own = self, fresh = created;
        final Jid from = packet.getFrom(); final String id = packet.getId();
        request.dispatch(() -> request.confirm(options, from, id, own, fresh, owner));
    }
    void joinTerminated(MucOptions options) {
        Request request = active.get(options.getConversation().getUuid());
        if (request != null) request.dispatch(() -> {
            if (request.options != options || options.online() || options.getJoinAttempt().isPending()) return;
            if (!request.live()) return;
            if (request.phase == Phase.JOIN) {
                request.roomState = RoomState.NOT_CONFIGURED;
                request.finish(options.getError() == MucOptions.Error.REMOTE_SERVER_TIMEOUT ? Reason.TIMEOUT : Reason.REJECTED);
            } else request.finish(Reason.STALE);
        });
    }

    void unavailable(Account account) {
        epochs.put(account.getUuid(), new Object());
        for (Request request : active.values()) if (request.account == account) {
            request.invalidated.set(true);
            request.dispatch(() -> request.finish(Reason.STALE));
        }
    }

    public final class Request {
        final Conversation room;
        final Account account;
        final XmppConnection connection;
        final Jid roomJid, accountJid;
        final Object epoch;
        final String name;
        final Consumer<Result> callback;
        final LinkedHashSet<Jid> targets = new LinkedHashSet<>();
        final Map<Jid, Recipient> results = Collections.synchronizedMap(new LinkedHashMap<>());
        final Map<Jid, Invitation> ownResults = Collections.synchronizedMap(new LinkedHashMap<>());
        final Map<Jid, MucMembershipAdmission.Request> admissions = Collections.synchronizedMap(new LinkedHashMap<>());
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean invalidated = new AtomicBoolean();
        volatile Future<?> deadline;
        volatile MucOptions options;
        volatile String joinId;
        volatile Phase phase = Phase.START;
        volatile Object pending;
        volatile boolean serverCreated;
        volatile RoomState roomState = RoomState.UNKNOWN;
        Bundle configuration;

        Request(Conversation room, String name, Iterable<Jid> jids, Consumer<Result> callback) {
            this.room = room; this.account = room.getAccount(); this.name = name;
            this.callback = Objects.requireNonNull(callback);
            connection = account.getXmppConnection(); accountJid = account.getJid();
            roomJid = room.getJid().asBareJid(); epoch = epochs.get(account.getUuid());
            for (Jid jid : jids) {
                if (jid == null || jid instanceof Jid.Invalid || jid.getLocal() == null) {
                    targets.add(null);
                } else if (!jid.asBareJid().equals(accountJid.asBareJid())) targets.add(jid.asBareJid());
            }
        }
        public boolean isDone() { return done.get(); }
        public void cancel() {
            invalidated.set(true);
            dispatch(() -> finish(Reason.CANCELLED));
        }
        boolean current() {
            return !invalidated.get() && epoch == epochs.get(account.getUuid())
                    && account.getStatus() == Account.State.ONLINE && connection != null
                    && account.getXmppConnection() == connection && accountJid.equals(account.getJid())
                    && roomJid.equals(room.getJid().asBareJid()) && room.getAccount() == account
                    && service.findAccountByUuid(account.getUuid()) == account
                    && service.findConversationByUuid(room.getUuid()) == room
                    && room.getMode() == Conversation.MODE_MULTI && room.getNextCounterpart() == null
                    && room.getStatus() != Conversation.STATUS_ARCHIVED
                    && (options == null || (room.getMucOptions() == options
                        && (phase == Phase.JOIN || (service.isCurrentMucAffiliationPreflight(account, room, options, connection)
                            && options.getSelf().getAffiliation() == MucOptions.Affiliation.OWNER))));
        }
        boolean live() {
            if (isDone()) return false;
            if (current()) return true;
            finish(Reason.STALE); return false;
        }
        void begin() {
            if (!live()) return;
            try {
                deadline = service.scheduleMucAffiliationPreflight(() -> {
                    if (live()) finish(Reason.TIMEOUT);
                }, DEADLINE_SECONDS);
                if (isDone()) deadline.cancel(false);
            } catch (RuntimeException closed) { finish(Reason.STALE); }
        }
        void bind(MucOptions expected) {
            if (!live()) return;
            if (phase != Phase.START || !expected.getJoinAttempt().isPending()) { finish(Reason.STALE); return; }
            options = expected; joinId = expected.getJoinAttempt().getPresenceId(); phase = Phase.JOIN;
        }
        void confirm(MucOptions expected, Jid from, String id, boolean self, boolean created, boolean owner) {
            if (!live() || phase != Phase.JOIN || options != expected || !Objects.equals(joinId, id)
                    || !Objects.equals(options.getSelf().getFullJid(), from) || !self) return;
            if (!created) { roomState = RoomState.NOT_CONFIGURED; finish(Reason.EXISTING_ROOM); return; }
            if (!owner || !options.online() || options.getJoinAttempt().isPending()
                    || options.getSelf().getAffiliation() != MucOptions.Affiliation.OWNER) {
                roomState = RoomState.NOT_CONFIGURED; finish(Reason.REJECTED); return;
            }
            serverCreated = true;
            configuration = IqGenerator.defaultGroupChatConfiguration();
            if (name != null && !name.isEmpty()) configuration.putString("muc#roomconfig_roomname", name);
            phase = Phase.FORM;
            send(MucPasswordForm.request(roomJid), reply -> {
                final Data form = form(reply);
                final Data submission = Data.parse(copy(form));
                if (("1".equals(form.getValue(MucPasswordForm.PROTECTED))
                        || "true".equals(form.getValue(MucPasswordForm.PROTECTED)))
                        && (form.getValue(MucPasswordForm.SECRET) == null
                            || form.getValue(MucPasswordForm.SECRET).isEmpty())) {
                    throw new IllegalArgumentException("Cannot preserve an undisclosed room secret");
                }
                submission.submit(configuration);
                Iq set = new Iq(Iq.Type.SET); set.setTo(roomJid);
                set.query(MucPasswordForm.OWNER).addChild(submission);
                phase = Phase.SET;
                send(set, accepted -> {
                    phase = Phase.VERIFY;
                    send(MucPasswordForm.request(roomJid), verified -> {
                        Data actual = form(verified);
                        for (String key : REQUIRED) if (!equivalent(configuration.getString(key), actual.getValue(key))) {
                            roomState = RoomState.NOT_CONFIGURED; finish(Reason.REJECTED); return;
                        }
                        phase = Phase.DISCO;
                        send(service.getIqGenerator().queryDiscoInfo(roomJid), this::configured);
                    });
                });
            });
        }
        void configured(Iq reply) {
            final Element query = reply.findChild("query", Namespace.DISCO_INFO);
            if (query == null || reply.getChildren().size() != 1) { finish(Reason.MALFORMED); return; }
            ServiceDiscoveryResult disco = new ServiceDiscoveryResult(reply);
            if (!disco.getFeatures().contains("muc_membersonly")
                    || disco.getFeatures().contains("muc_open")
                    || !disco.getFeatures().contains("muc_nonanonymous")) { finish(Reason.REJECTED); return; }
            if (!live()) return;
            options.updateConfiguration(disco);
            room.setAttribute("accept_non_anonymous", true);
            service.updateConversation(room);
            roomState = RoomState.CONFIGURED; phase = Phase.ADMISSION;
            // Resource JIDs intentionally bypass the bare external-recipient admission path.
            for (Jid jid : new LinkedHashSet<>(service.mucBootstrapOwnResources(account))) {
                if (!jid.isFullJid() || !jid.asBareJid().equals(accountJid.asBareJid()) || jid.equals(accountJid)) continue;
                ownResults.put(jid, Invitation.NOT_SENT);
            }
            final ArrayList<Jid> ownTargets;
            synchronized (ownResults) { ownTargets = new ArrayList<>(ownResults.keySet()); }
            for (Jid jid : ownTargets) {
                if (!live()) return;
                final Invitation invitation = handoff(jid);
                if (isDone()) return;
                ownResults.put(jid, invitation);
            }
            if (targets.isEmpty()) { finish(Reason.COMPLETE); return; }
            for (Jid target : targets) {
                if (!live()) return;
                if (target == null) { results.put(null, new Recipient(null, Invitation.NOT_SENT)); continue; }
                MucMembershipAdmission.Request child = service.admitMucMembership(account, room, target,
                        result -> dispatch(() -> admitted(target, result)));
                if (!child.isDone()) admissions.put(target, child);
                if (isDone()) child.cancel();
            }
            checkComplete();
        }
        void admitted(Jid jid, MucMembershipAdmission.Result result) {
            if (!live() || phase != Phase.ADMISSION || results.containsKey(jid)) return;
            admissions.remove(jid);
            final boolean eligible = result.isEligible()
                    && ((result.status == MucMembershipAdmission.Status.ALREADY_ELIGIBLE
                            && result.writeState == MucMembershipAdmission.WriteState.NOT_ATTEMPTED)
                        || ((result.status == MucMembershipAdmission.Status.GRANTED_VERIFIED
                                || result.status == MucMembershipAdmission.Status.ELIGIBLE_VERIFIED)
                            && result.writeState == MucMembershipAdmission.WriteState.ACKNOWLEDGED));
            Invitation invitation = eligible ? handoff(jid) : Invitation.NOT_SENT;
            if (isDone()) return;
            results.put(jid, new Recipient(result, invitation));
            checkComplete();
        }
        Invitation handoff(Jid jid) {
            if (!live()) return Invitation.NOT_SENT;
            try { service.directInvite(room, jid); return Invitation.HANDED_OFF; }
            catch (RuntimeException uncertain) { return Invitation.UNCONFIRMED; }
        }
        void checkComplete() { if (results.size() == targets.size()) finish(Reason.COMPLETE); }
        void send(Iq iq, Consumer<Iq> next) {
            if (!live()) return;
            final Object token = new Object(); pending = token;
            try {
                service.sendIqPacket(account, iq, reply -> dispatch(() -> {
                    if (!live() || pending != token) return;
                    pending = null;
                    try {
                        if (reply == null) { finish(Reason.MALFORMED); return; }
                        if (reply.getType() == Iq.Type.TIMEOUT) { finish(Reason.TIMEOUT); return; }
                        if (reply.getType() == Iq.Type.ERROR) {
                            if (reply.getErrorCondition() == null) { finish(Reason.MALFORMED); return; }
                            if (phase == Phase.FORM || phase == Phase.SET) roomState = RoomState.NOT_CONFIGURED;
                            finish(Reason.REJECTED); return;
                        }
                        if (reply.getType() != Iq.Type.RESULT) { finish(Reason.MALFORMED); return; }
                        next.accept(reply);
                    } catch (IllegalArgumentException malformed) { finish(Reason.MALFORMED); }
                }), IQ_SECONDS);
            } catch (RuntimeException interrupted) { finish(Reason.STALE); }
        }
        void dispatch(Runnable work) {
            if (isDone()) return;
            try { service.executeMucAffiliationPreflight(work); }
            catch (RejectedExecutionException closed) { finish(Reason.STALE); }
        }
        void finish(Reason reason) {
            if (!done.compareAndSet(false, true)) return;
            active.remove(room.getUuid(), this);
            pending = null;
            Future<?> timer = deadline; if (timer != null) timer.cancel(false);
            if (options != null && room.getMucOptions() == options && phase == Phase.JOIN
                    && options.getJoinAttempt().isPending()) {
                options.getJoinAttempt().finish();
                service.onMucJoinStateChanged(options);
            }
            final ArrayList<Map.Entry<Jid, MucMembershipAdmission.Request>> children;
            synchronized (admissions) { children = new ArrayList<>(admissions.entrySet()); }
            for (Map.Entry<Jid, MucMembershipAdmission.Request> child : children) {
                results.putIfAbsent(child.getKey(), new Recipient(null, Invitation.NOT_SENT, true));
                child.getValue().cancel();
            }
            for (Jid jid : targets) results.putIfAbsent(jid, new Recipient(null, Invitation.NOT_SENT));
            callback.accept(new Result(this, reason));
        }
    }

    private static Data form(Iq iq) {
        Element query = iq.findChild("query", MucPasswordForm.OWNER);
        Element form = query == null ? null : query.findChild("x", Namespace.DATA);
        if (query == null || iq.getChildren().size() != 1 || query.getChildren().size() != 1) throw new IllegalArgumentException();
        MucRoomConfiguration model = new MucRoomConfiguration(form); // Existing strict FORM_TYPE and known-field validation.
        Data data = Data.parse(form);
        for (String required : REQUIRED) {
            int count = 0;
            for (Element field : form.getChildren()) if ("field".equals(field.getName())
                    && required.equals(field.getAttribute("var"))) count++;
            if (count != 1 || data.getFieldByName(required).getValues().size() != 1
                    || (data.getFieldByName(required).getNamespace() != null
                        && !Namespace.DATA.equals(data.getFieldByName(required).getNamespace()))
                    || (!PERSISTENT.equals(required) && model.value(required) == null)) throw new IllegalArgumentException();
            for (Element value : data.getFieldByName(required).getChildren()) {
                if ("value".equals(value.getName()) && (!value.getChildren().isEmpty()
                        || (value.getNamespace() != null && !Namespace.DATA.equals(value.getNamespace())))) throw new IllegalArgumentException();
            }
            if (PERSISTENT.equals(required) && data.getFieldByName(required).getType() != null
                    && !"boolean".equals(data.getFieldByName(required).getType())) throw new IllegalArgumentException();
            if (PERSISTENT.equals(required) && !("1".equals(data.getValue(required))
                    || "0".equals(data.getValue(required)) || "true".equals(data.getValue(required))
                    || "false".equals(data.getValue(required)))) throw new IllegalArgumentException();
        }
        return data;
    }
    private static boolean equivalent(String wanted, String actual) {
        return Objects.equals(wanted, actual) || ("1".equals(wanted) && "true".equals(actual))
                || ("0".equals(wanted) && "false".equals(actual));
    }
    private static Element copy(Element source) {
        Element result = new Element(source.getName()); result.getAttributes().putAll(source.getAttributes());
        if (source.getContent() != null) result.setContent(source.getContent());
        for (Element child : source.getChildren()) result.addChild(copy(child));
        return result;
    }
}
