package eu.siacs.conversations.services;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.util.Log;

import androidx.annotation.NonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ReceiptRequest;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnAdvancedStreamFeaturesLoaded;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;

public class MessageArchiveService implements OnAdvancedStreamFeaturesLoaded {

    private final XmppConnectionService mXmppConnectionService;

    private final HashSet<Query> queries = new HashSet<>();
    private final ArrayList<Query> pendingQueries = new ArrayList<>();

    public enum Version {
        MAM_0("urn:xmpp:mam:0", true),
        MAM_1("urn:xmpp:mam:1", false),
        MAM_2("urn:xmpp:mam:2", false);

        public final boolean legacy;
        public final String namespace;

        Version(String namespace, boolean legacy) {
            this.namespace = namespace;
            this.legacy = legacy;
        }

        public static Version get(Account account) {
            return get(account, null);
        }

        public static Version get(Account account, Conversation conversation) {
            if (conversation == null || conversation.getMode() == Conversation.MODE_SINGLE) {
                return get(account.getXmppConnection().getFeatures().getAccountFeatures());
            } else {
                return get(conversation.getMucOptions().getFeatures());
            }
        }

        private static Version get(List<String> features) {
            final Version[] values = values();
            for (int i = values.length - 1; i >= 0; --i) {
                for (String feature : features) {
                    if (values[i].namespace.equals(feature)) {
                        return values[i];
                    }
                }
            }
            return MAM_0;
        }

        public static boolean has(List<String> features) {
            for (String feature : features) {
                for (Version version : values()) {
                    if (version.namespace.equals(feature)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public static Element findResult(Message packet) {
            for (Version version : values()) {
                Element result = packet.findChild("result", version.namespace);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

    }

    MessageArchiveService(final XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    void cancelQueriesForReconnect(final Account account) {
        final List<Query> interrupted = new ArrayList<>();
        synchronized (this.queries) {
            for (Query query : this.queries) if (query.getAccount() == account) interrupted.add(query);
        }
        synchronized (this.pendingQueries) {
            this.pendingQueries.removeIf(query -> query.getAccount() == account);
        }
        for (Query query : interrupted) {
            query.diagnosticReason = MamDiagnostics.Reason.RECONNECT;
            finishTerminalQuery(query, false);
        }
    }

    private void catchup(final Account account) {
        cancelQueriesForReconnect(account);
        final MamReference durableReceived = mXmppConnectionService.databaseBackend.getLastMessageReceived(account);
        MamReference mamReference = MamReference.max(
                durableReceived,
                mXmppConnectionService.databaseBackend.getLastClearDate(account)
        );
        mamReference = MamReference.max(mamReference, mXmppConnectionService.getAutomaticMessageDeletionDate());
        long endCatchup = account.getXmppConnection().getLastSessionEstablished();
        final boolean longHistoryRecovery = endCatchup - mamReference.getTimestamp() >= Config.MAM_MAX_CATCHUP;
        final Query query;
        if (mamReference.getTimestamp() == 0) {
            return;
        } else if (longHistoryRecovery) {
            long startCatchup = endCatchup - Config.MAM_MAX_CATCHUP;
            List<Conversation> conversations = mXmppConnectionService.getConversations();
            for (Conversation conversation : conversations) {
                if (conversation.getMode() == Conversation.MODE_SINGLE && conversation.getAccount() == account && startCatchup > conversation.getLastMessageTransmitted().getTimestamp()) {
                    this.query(conversation, startCatchup, true);
                }
            }
            query = new Query(account, new MamReference(startCatchup), 0);
        } else {
            query = new Query(account, mamReference, 0);
        }
        // Only the automatic account reconnect owner can admit unseen forward results. MUC
        // bootstrap/manual/missing-range queries never opt into this notification contract.
        final boolean exactDurableCursor = durableReceived != null && mamReference.getReference() != null
                && !mamReference.getReference().isEmpty()
                && mamReference.getReference().equals(durableReceived.getReference())
                && query.getReference() != null
                && query.getReference().equals(mamReference.getReference());
        query.notificationDelivery = longHistoryRecovery ? MamNotificationDelivery.NONE
                : exactDurableCursor ? MamNotificationDelivery.FORWARD_UNSEEN
                : MamNotificationDelivery.SILENT_UNSEEN;
        query.notificationGeneration = mXmppConnectionService.getNotificationService().notificationGeneration();
        synchronized (this.queries) {
            this.queries.add(query);
        }
        this.execute(query);
    }

    void catchupMUC(final Conversation conversation) {
        final MamReference start = conversation.getLastMessageTransmitted();
        catchupMUC(conversation,
                start.getTimestamp() < 0 && conversation.countMessages() == 0 ? new MamReference(0) : start);
    }

    void catchupMUC(final Conversation conversation, final MamReference start) {
        query(conversation, start, 0, true);
    }

    void catchupMUC(final Conversation conversation, final MamReference start,
            java.util.function.Consumer<Boolean> completed) {
        query(conversation, start, 0, true, completed);
    }

    // Only the join owner may opt into a bounded initial page; a zero cursor alone never does.
    void catchupMUC(final Conversation conversation, final MamReference start,
            final MucHistoryPurpose purpose,
            final java.util.function.BiConsumer<Boolean, Boolean> completed) {
        if (query(conversation, start, 0, true, purpose, completed) == null && completed != null) {
            completed.accept(false, false);
        }
    }

    public Query query(final Conversation conversation) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0 && conversation.countMessages() == 0) {
            return query(conversation,
                    new MamReference(0),
                    System.currentTimeMillis(),
                    false);
        } else {
            return query(conversation,
                    conversation.getLastMessageTransmitted(),
                    conversation.getAccount().getXmppConnection().getLastSessionEstablished(),
                    false);
        }
    }

    public boolean isCatchingUp(Conversation conversation) {
        final Account account = conversation.getAccount();
        if (account.getXmppConnection().isWaitingForSmCatchup()) {
            return true;
        } else {
            synchronized (this.queries) {
                for (Query query : this.queries) {
                    if (query.getAccount() == account && query.isCatchup() && ((conversation.getMode() == Conversation.MODE_SINGLE && query.getWith() == null) || query.getConversation() == conversation)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Query query(final Conversation conversation, long end, boolean allowCatchup) {
        return this.query(conversation, conversation.getLastMessageTransmitted(), end, allowCatchup);
    }

    public Query query(Conversation conversation, MamReference start, long end, boolean allowCatchup) {
        return query(conversation, start, end, allowCatchup, null);
    }

    private Query query(Conversation conversation, MamReference start, long end, boolean allowCatchup,
            java.util.function.Consumer<Boolean> completed) {
        return query(conversation, start, end, allowCatchup, MucHistoryPurpose.MISSING_RANGE_RECOVERY,
                completed == null ? null : (success, exhausted) -> completed.accept(success));
    }

    private Query query(Conversation conversation, MamReference start, long end, boolean allowCatchup,
            final MucHistoryPurpose purpose,
            final java.util.function.BiConsumer<Boolean, Boolean> completed) {
        return query(conversation, start, end, allowCatchup, purpose, completed, null, true);
    }

    Query queryHistoryPage(final Conversation conversation, final long end, final boolean hasBoundary,
            final XmppConnectionService.OnMoreMessagesLoaded callback) {
        return query(conversation, new MamReference(0), end, false,
                MucHistoryPurpose.MANUAL_PAGE, null, callback, hasBoundary);
    }

    private Query query(Conversation conversation, MamReference start, long end, boolean allowCatchup,
            final MucHistoryPurpose purpose,
            final java.util.function.BiConsumer<Boolean, Boolean> completed,
            final XmppConnectionService.OnMoreMessagesLoaded callback, final boolean hasBoundary) {
        if (conversation.getNextCounterpart() != null) return null;
        final Query query;
        Query reverseCatchup = null;
        if (end != 0 && start.greaterThan(end)) return null;
        final MamReference startActual = MamReference.max(start, mXmppConnectionService.getAutomaticMessageDeletionDate());
        if (purpose == MucHistoryPurpose.INITIAL_RECENT_BOOTSTRAP) {
            // Always request the latest single page, never an old in-memory scroll cursor.
            // A failed initial attempt must not hand that stale cursor to its strict retry.
            query = new Query(conversation, startActual, end, false);
        } else if (start.getTimestamp() == 0) {
            query = new Query(conversation, startActual, end, false);
            query.reference = completed == null && hasBoundary && conversation.countMessages() > 0
                    ? conversation.getFirstMamReference() : null;
            if (query.reference != null) {
                query.cursorFallbackEnd = purpose == MucHistoryPurpose.MANUAL_PAGE && end > 0
                        ? end : oldestLoadedMessageTimestamp(conversation);
            }
        } else {
            if (allowCatchup) {
                MamReference maxCatchup = MamReference.max(startActual, System.currentTimeMillis() - Config.MAM_MAX_CATCHUP);
                if (maxCatchup.greaterThan(startActual)) {
                    reverseCatchup = new Query(conversation, startActual, maxCatchup.getTimestamp(), false);
                }
                query = new Query(conversation, maxCatchup, end, true);
            } else {
                query = new Query(conversation, startActual, end, false);
            }
        }
        synchronized (this.queries) {
            // Build outside the registry lock: constructors may acquire Conversation.
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (Query active : queries) if (active.conversation == conversation) return null;
            }
            if (purpose == MucHistoryPurpose.INITIAL_RECENT_BOOTSTRAP)
                conversation.setFirstMamReference(null);
            query.mucHistoryPurpose = purpose;
            final CatchupCompletion completion = completed == null ? null
                    : new CatchupCompletion(reverseCatchup == null ? 1 : 2, completed);
            query.mucCatchupCompleted = completion;
            query.callback = callback; // Install before transport can complete synchronously.
            this.queries.add(query);
            if (reverseCatchup != null) {
                reverseCatchup.diagnostics = query.diagnostics.split();
                reverseCatchup.mucCatchupCompleted = completion;
                this.queries.add(reverseCatchup);
                // Reserve both ranges, but send only one MUC range at a time.
                if (query.muc()) reverseCatchup.followingRange = query;
            }
        }
        // Transport and callbacks must never run under the query registry monitor.
        if (reverseCatchup != null) this.execute(reverseCatchup);
        if (reverseCatchup == null || !query.muc()) this.execute(query);
        return query;
    }

    private static long oldestLoadedMessageTimestamp(final Conversation conversation) {
        long oldest = Long.MAX_VALUE;
        for (final eu.siacs.conversations.entities.Message message :
                conversation.snapshotMessages()) {
            if (message.isPrivateMessage()) {
                continue;
            }
            final long timestamp = message.getTimeSent();
            if (timestamp > 0 && timestamp < oldest) {
                oldest = timestamp;
            }
        }
        return oldest == Long.MAX_VALUE ? 0L : oldest;
    }

    void executePendingQueries(final Account account) {
        final List<Query> pending = new ArrayList<>();
        synchronized (this.pendingQueries) {
            for (Iterator<Query> iterator = this.pendingQueries.iterator(); iterator.hasNext(); ) {
                Query query = iterator.next();
                if (query.getAccount() == account) {
                    pending.add(query);
                    iterator.remove();
                }
            }
        }
        for (Query query : pending) {
            this.execute(query);
        }
    }

    private void execute(final Query query) {
        synchronized (this.queries) {
            if (!this.queries.contains(query)) return;
        }
        if (query.muc()) {
            final XmppConnectionService.HistoryActionResult availability =
                    mXmppConnectionService.mucHistoryAvailability(query.conversation);
            if (availability != XmppConnectionService.HistoryActionResult.STARTED
                    || query.account != query.conversation.getAccount()
                    || query.membershipGeneration != query.conversation.getMucOptions().getJoinAttempt().getMembershipGeneration()) {
                query.actionResult = availability == XmppConnectionService.HistoryActionResult.STARTED
                        ? XmppConnectionService.HistoryActionResult.MUC_NOT_JOINED : availability;
                finishTerminalQuery(query, false);
                return;
            }
        }
        final Account account = query.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final Conversation conversation = query.getConversation();
            if (conversation != null && conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                throw new IllegalStateException("Attempted to run MAM query for archived conversation");
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": running mam query " + query.toString());
            final Iq packet = this.mXmppConnectionService.getIqGenerator().queryMessageArchiveManagement(query);
            query.diagnostics.sent(query.getPagingOrder() == PagingOrder.REVERSE, query.getRequestedPageSize());
            this.mXmppConnectionService.sendIqPacket(account, packet, (p) -> {
                final Element fin = p.findChild("fin", query.version.namespace);
                if (p.getType() == Iq.Type.TIMEOUT) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": MAM query timed out; keeping server-history state open");
                    query.diagnosticReason = MamDiagnostics.Reason.TIMEOUT;
                    finishTerminalQuery(query, false);
                } else if (p.getType() == Iq.Type.RESULT && fin != null) {
                    final boolean running;
                    synchronized (this.queries) {
                        running = this.queries.contains(query);
                    }
                    if (running) {
                        processFin(query, fin);
                    } else {
                        query.diagnostics.event(MamDiagnostics.Event.STALE);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": ignoring MAM iq result because query had been killed");
                    }
                } else if (p.getType() == Iq.Type.RESULT && query.isLegacy()) {
                    // An IQ result cancels the transport timeout, but legacy fin is still owed.
                    query.finTimeout = mXmppConnectionService.scheduleMamFinTimeout(() -> {
                        query.diagnosticReason = MamDiagnostics.Reason.TIMEOUT;
                        finishTerminalQuery(query, false);
                    });
                    synchronized (this.queries) {
                        if (!queries.contains(query) && query.finTimeout != null) query.finTimeout.cancel(false);
                    }
                } else if (p.getType() == Iq.Type.ERROR
                        && "item-not-found".equals(p.getErrorCondition())
                        && query.canRetryWithoutReference()) {
                    retryQueryWithoutReference(query);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": error executing mam: "
                                    + p.toString());
                    // An error proves neither archive exhaustion nor history completeness.
                    finishTerminalQuery(query, false);
                }
            });
        } else {
            query.diagnostics.event(MamDiagnostics.Event.DEFERRED);
            synchronized (this.pendingQueries) {
                this.pendingQueries.add(query);
            }
        }
    }

    private void finishTerminalQuery(final Query query, final boolean done) {
        finishTerminalQuery(query, done, false);
    }

    private void finishTerminalQuery(final Query query, final boolean done, final boolean complete) {
        final boolean locallyDurable = query.persistence.finishPage();
        if (!locallyDurable) query.actionResult = XmppConnectionService.HistoryActionResult.PERSISTENCE_FAILURE;
        else if (query.actionResult == null && !complete
                && query.diagnosticReason != MamDiagnostics.Reason.CAP)
            query.actionResult = XmppConnectionService.HistoryActionResult.SERVER_ERROR;
        else if (query.actionResult == null && done)
            query.actionResult = XmppConnectionService.HistoryActionResult.HISTORY_EXHAUSTED;
        synchronized (this.queries) {
            if (!this.queries.remove(query)) return; // stale timeout/error owns no completion
        }
        if (query.finTimeout != null) query.finTimeout.cancel(false);
        synchronized (this.pendingQueries) { this.pendingQueries.remove(query); }
        try {
            publishQuery(query, done && locallyDurable, true);
        } finally {
            query.diagnostics.end(query.diagnosticReason);
            final var notificationBatch = query.takeNotificationCandidates();
            if (complete && locallyDurable && query.diagnosticReason == MamDiagnostics.Reason.COMPLETE) {
                mXmppConnectionService.getNotificationService().publishMamNotifications(
                        notificationBatch, query.notificationDelivery == MamNotificationDelivery.FORWARD_UNSEEN,
                        query.notificationGeneration);
            }
            if (query.isCatchup() && query.getActualMessageCount() > 0) {
                mXmppConnectionService
                        .getNotificationService()
                        .finishArchiveBacklog(query.getAccount());
            }
            final CatchupCompletion completed = query.mucCatchupCompleted;
            query.mucCatchupCompleted = null;
            if (completed != null) completed.accept(complete && locallyDurable,
                    complete && done && query.getPagingOrder() == PagingOrder.REVERSE);
            final Conversation conversation = query.getConversation();
            if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                mXmppConnectionService.onMucHistoryQueryFinished(conversation);
            }
            final Query following = query.followingRange;
            query.followingRange = null;
            if (following != null) {
                if (query.diagnosticReason == MamDiagnostics.Reason.CANCEL
                        || query.diagnosticReason == MamDiagnostics.Reason.RECONNECT) {
                    following.diagnosticReason = query.diagnosticReason;
                    finishTerminalQuery(following, false);
                } else {
                    execute(following);
                }
            }
            processPostponed(query);
        }
    }

    private void retryQueryWithoutReference(final Query query) {
        if (!query.persistence.finishPage()) {
            finishTerminalQuery(query, false);
            return;
        }
        final Query retry = query.retryWithoutReference();
        retry.diagnostics = query.diagnostics.retry();
        synchronized (this.queries) {
            if (!this.queries.remove(query)) {
                return;
            }
            retry.mucCatchupCompleted = query.mucCatchupCompleted;
            query.mucCatchupCompleted = null;
            retry.followingRange = query.followingRange;
            query.followingRange = null;
            this.queries.add(retry);
        }
        query.diagnostics.end(MamDiagnostics.Reason.FALLBACK);
        retry.diagnostics.event(MamDiagnostics.Event.RETRY);
        Log.d(
                Config.LOGTAG,
                query.getAccount().getJid().asBareJid()
                        + ": MAM RSM cursor was not found; retrying from timestamp "
                        + AbstractGenerator.getTimestamp(retry.getFallbackStart()));
        this.execute(retry);
    }

    private void publishQuery(final Query query, final boolean done, final boolean terminal) {
        final long publicationStarted = query.diagnostics.begin();
        try {
        final Conversation conversation = query.getConversation();
        if (conversation != null) {
            if (query.getActualMessageCount() > 0) {
                conversation.sort();
            }
            conversation.setHasMessagesLeftOnServer(!done
                    || mXmppConnectionService.hasPendingMucCatchup(conversation));
            final var displayState = conversation.getDisplayState();
            if (displayState != null) {
                mXmppConnectionService.markReadUpToStanzaId(conversation, displayState);
            }
        } else {
            for (final Conversation tmp : this.mXmppConnectionService.getConversations()) {
                if (tmp.getAccount() == query.getAccount()) {
                    if (query.getActualMessageCount() > 0) {
                        tmp.sort();
                    }
                    final var displayState = tmp.getDisplayState();
                    if (displayState != null) {
                        mXmppConnectionService.markReadUpToStanzaId(tmp, displayState);
                    }
                }
            }
        }
        if (query.hasCallback()) {
            query.callback(done, terminal);
        } else {
            this.mXmppConnectionService.updateConversationUi();
        }
        } finally {
            query.diagnostics.dispatched(publicationStarted);
        }
    }

    public boolean inCatchup(Account account) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.account == account && query.isCatchup() && query.getWith() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isCatchupInProgress(Conversation conversation) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.account == conversation.getAccount() && query.isCatchup()) {
                    final Jid with = query.getWith() == null ? null : query.getWith().asBareJid();
                    if ((conversation.getMode() == Conversational.MODE_SINGLE && with == null) || (conversation.getJid().asBareJid().equals(with))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean queryInProgress(Conversation conversation, XmppConnectionService.OnMoreMessagesLoaded callback) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.conversation == conversation) {
                    if (!query.hasCallback() && callback != null) {
                        query.setCallback(callback);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    public boolean queryInProgress(Conversation conversation) {
        return queryInProgress(conversation, null);
    }

    public void processFinLegacy(Element fin, Jid from) {
        Query query = findQuery(fin.getAttribute("queryid"));
        if (query != null && query.validFrom(from)) {
            processFin(query, fin);
        }
    }

    private void processFin(Query query, Element fin) {
        query.diagnostics.fin();
        final String finQueryId = fin.getAttribute("queryid");
        if ((finQueryId != null && !query.getQueryId().equals(finQueryId))
                || !query.persistence.finishPage()) {
            finishTerminalQuery(query, false);
            return;
        }
        boolean complete = fin.getAttributeAsBoolean("complete");
        if (!complete && query.pageResultCount == 0) {
            query.diagnosticReason = MamDiagnostics.Reason.BOUNDARY;
            finishTerminalQuery(query, false);
            return;
        }
        Element set = fin.findChild("set", "http://jabber.org/protocol/rsm");
        Element last = set == null ? null : set.findChild("last");
        String count = set == null ? null : set.findChildContent("count");
        Element first = set == null ? null : set.findChild("first");
        Element relevant = query.getPagingOrder() == PagingOrder.NORMAL ? last : first;
        if ((first != null && isEmptyBoundary(first)) || (last != null && isEmptyBoundary(last))
                || (!complete && relevant != null && relevant.getContent().equals(query.getReference()))) {
            finishTerminalQuery(query, false);
            return;
        }
        if ((query.mucHistoryPurpose == MucHistoryPurpose.INITIAL_RECENT_BOOTSTRAP
                || (query.muc() && query.mucHistoryPurpose == MucHistoryPurpose.MANUAL_PAGE)) && !complete) {
            // The server may cap a page below our RSM max (XEP-0313 section 4.3.1).
            // A valid nonempty page completes this bounded operation, not the whole archive.
            // Never walk older pages automatically; the P0 barrier remains mandatory.
            final boolean boundedPageComplete = query.getTotalCount() > 0
                    && query.getTotalCount() <= query.getRequestedPageSize()
                    && first != null && !isEmptyBoundary(first)
                    && last != null && !isEmptyBoundary(last);
            if (boundedPageComplete && query.getConversation() != null) {
                query.getConversation().setFirstMamReference(first.getContent());
            }
            query.diagnosticReason = boundedPageComplete ? MamDiagnostics.Reason.CAP : MamDiagnostics.Reason.BOUNDARY;
            finishTerminalQuery(query, false, boundedPageComplete);
            return;
        }
        boolean abort =
                (!query.isCatchup()
                                && query.getTotalCount() >= query.getRequestedPageSize())
                        || query.getTotalCount() >= Config.MAM_MAX_MESSAGES;
        if (query.getConversation() != null && query.getPagingOrder() == PagingOrder.REVERSE) {
            query.getConversation().setFirstMamReference(first == null ? null : first.getContent());
        }
        if (complete || relevant == null || abort) {
            boolean done;
            if (query.isCatchup()) {
                // A forward catch-up being complete says nothing about older archive history.
                done = false;
            } else if (complete) {
                // XEP-0313: complete='true' is authoritative. RSM <count/> is optional and must
                // not be required to decide that paging has reached the end of this query.
                done = true;
            } else {
                done = false; // Only authoritative complete proves exhaustion of this range.
            }
            query.diagnosticReason = complete ? MamDiagnostics.Reason.COMPLETE
                    : abort ? MamDiagnostics.Reason.CAP : MamDiagnostics.Reason.BOUNDARY;
            finishTerminalQuery(query, done, complete);

            Log.d(
                    Config.LOGTAG,
                    query.getAccount().getJid().asBareJid()
                            + ": finished mam after "
                            + query.getTotalCount()
                            + "("
                            + query.getActualMessageCount()
                            + ") messages. messages left="
                            + !done
                            + " count="
                            + count);
        } else {
            final Query nextQuery;
            if (query.getPagingOrder() == PagingOrder.NORMAL) {
                nextQuery = query.next(last == null ? null : last.getContent());
            } else {
                nextQuery = query.prev(first == null ? null : first.getContent());
            }
            nextQuery.diagnostics = query.diagnostics.next();
            synchronized (this.queries) {
                if (!this.queries.remove(query)) return;
                nextQuery.mucCatchupCompleted = query.mucCatchupCompleted;
                query.mucCatchupCompleted = null;
                nextQuery.followingRange = query.followingRange;
                query.followingRange = null;
                this.queries.add(nextQuery);
            }
            if (query.finTimeout != null) query.finTimeout.cancel(false);
            // Ownership transfers before callbacks/transport; no external call holds the registry
            // lock, and a reentrant response can never find an unregistered next page.
            try {
                this.publishQuery(query, false, false);
                query.diagnostics.end(MamDiagnostics.Reason.NEXT);
            } finally {
                this.execute(nextQuery);
            }
        }
    }

    private static boolean isEmptyBoundary(final Element boundary) {
        return boundary.getContent() == null || boundary.getContent().isEmpty();
    }

    void kill(final Conversation conversation) {
        final ArrayList<Query> toBeKilled = new ArrayList<>();
        synchronized (this.pendingQueries) {
            for (final Iterator<Query> iterator = this.pendingQueries.iterator(); iterator.hasNext(); ) {
                final Query query = iterator.next();
                if (query.getConversation() == conversation) {
                    iterator.remove();
                    Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": killed pending MAM query for archived conversation");
                }
            }
        }
        synchronized (this.queries) {
            for (final Query q : queries) {
                if (q.conversation == conversation) {
                    toBeKilled.add(q);
                }
            }
        }
        for (final Query q : toBeKilled) {
            kill(q);
        }
    }

    private void kill(Query query) {
        Log.d(
                Config.LOGTAG,
                query.getAccount().getJid().asBareJid()
                        + ": killing mam query prematurely");
        query.diagnosticReason = MamDiagnostics.Reason.CANCEL;
        finishTerminalQuery(query, false);
    }

    private void processPostponed(Query query) {
        query.account.getAxolotlService().processPostponed();
        query.pendingReceiptRequests.removeAll(query.receiptRequests);
        Log.d(Config.LOGTAG, query.getAccount().getJid().asBareJid() + ": found " + query.pendingReceiptRequests.size() + " pending receipt requests");
        Iterator<ReceiptRequest> iterator = query.pendingReceiptRequests.iterator();
        while (iterator.hasNext()) {
            ReceiptRequest rr = iterator.next();
            mXmppConnectionService.sendMessagePacket(query.account, mXmppConnectionService.getMessageGenerator().received(query.account, rr.getJid(), rr.getId()));
            iterator.remove();
        }
    }

    public Query findQuery(String id) {
        if (id == null) {
            return null;
        }
        synchronized (this.queries) {
            for (Query query : this.queries) {
                if (query.getQueryId().equals(id)) {
                    return query;
                }
            }
            return null;
        }
    }

    @Override
    public void onAdvancedStreamFeaturesAvailable(Account account) {
        if (account.getXmppConnection() != null && account.getXmppConnection().getFeatures().mam()) {
            this.catchup(account);
        }
    }

    public enum PagingOrder {
        NORMAL,
        REVERSE
    }

    public enum MucHistoryPurpose {
        INITIAL_RECENT_BOOTSTRAP,
        MANUAL_PAGE,
        MISSING_RANGE_RECOVERY
    }

    static int requestedPageSize(
            final boolean muc,
            final boolean secureMessagePayloadRollout,
            final boolean catchup) {
        // Catch-up is latency-sensitive: a room that accumulated 100+ messages while the client
        // was away must become current in a few large RSM pages, matching the upstream
        // Conversations behaviour. Keep the smaller secure-MUC page only for user-driven reverse
        // history loading, where limiting one burst still protects the synchronous SCS write path.
        if (catchup) {
            return Config.PAGE_SIZE;
        }
        return muc && secureMessagePayloadRollout
                ? Math.min(Config.PAGE_SIZE, Config.SECURE_MUC_MAM_PAGE_SIZE)
                : Config.PAGE_SIZE;
    }

    /** One terminal result per query chain, including all split ranges and cursor retries. */
    private static final class CatchupCompletion implements java.util.function.Consumer<Boolean> {
        private int remaining;
        private boolean successful = true;
        private boolean historyExhausted = true;
        private final java.util.function.BiConsumer<Boolean, Boolean> completed;
        CatchupCompletion(int parts, java.util.function.BiConsumer<Boolean, Boolean> completed) {
            remaining = parts;
            this.completed = completed;
        }
        @Override public void accept(Boolean complete) { accept(Boolean.TRUE.equals(complete), false); }
        void accept(final boolean complete, final boolean exhausted) {
            final boolean result;
            final boolean allHistoryExhausted;
            synchronized (this) {
                if (remaining == 0) return;
                successful &= complete;
                historyExhausted &= exhausted;
                if (--remaining != 0) return;
                result = successful;
                allHistoryExhausted = historyExhausted;
            }
            completed.accept(result, allHistoryExhausted);
        }
    }

    enum MamNotificationDelivery { NONE, SILENT_UNSEEN, FORWARD_UNSEEN }

    public class Query {
        private MamNotificationDelivery notificationDelivery = MamNotificationDelivery.NONE;
        private long notificationGeneration = -1;
        private java.util.Map<String, java.util.LinkedHashSet<String>> notificationCandidates =
                new java.util.LinkedHashMap<>();

        public void stageNotification(final eu.siacs.conversations.entities.Message message) {
            if (notificationDelivery == MamNotificationDelivery.NONE) return;
            if (message.getServerMsgId() == null || message.getServerMsgId().isEmpty())
                notificationDelivery = MamNotificationDelivery.SILENT_UNSEEN;
            synchronized (notificationCandidates) {
                notificationCandidates.computeIfAbsent(message.getConversationUuid(),
                        ignored -> new java.util.LinkedHashSet<>()).add(message.getUuid());
            }
        }

        private java.util.Map<String, java.util.LinkedHashSet<String>> takeNotificationCandidates() {
            final java.util.Map<String, java.util.LinkedHashSet<String>> batch = new java.util.LinkedHashMap<>();
            synchronized (notificationCandidates) {
                notificationCandidates.forEach((conversation, uuids) ->
                        batch.put(conversation, new java.util.LinkedHashSet<>(uuids)));
                notificationCandidates.clear();
            }
            return batch;
        }
        public MamPersistenceBarrier persistence = new MamPersistenceBarrier();
        public MamDiagnostics.Page diagnostics = MamDiagnostics.page();
        private MamDiagnostics.Reason diagnosticReason = MamDiagnostics.Reason.ERROR;
        private CatchupCompletion mucCatchupCompleted;
        private Query followingRange;
        private volatile java.util.concurrent.ScheduledFuture<?> finTimeout;
        private long membershipGeneration;
        private XmppConnectionService.HistoryActionResult actionResult;
        private MucHistoryPurpose mucHistoryPurpose = MucHistoryPurpose.MISSING_RANGE_RECOVERY;
        public MucHistoryPurpose getMucHistoryPurpose() { return mucHistoryPurpose; }
        private HashSet<ReceiptRequest> pendingReceiptRequests = new HashSet<>();
        private HashSet<ReceiptRequest> receiptRequests = new HashSet<>();
        private int totalCount = 0;
        private int pageResultCount = 0;
        private int actualCount = 0;
        private int actualInThisQuery = 0;
        private long start;
        private final long fallbackStart;
        private final long end;
        private final String queryId;
        private String reference = null;
        private boolean cursorFallbackAttempted = false;
        private long cursorFallbackEnd = 0L;
        private long earliestResultTimestamp = Long.MAX_VALUE;
        private final Account account;
        private Conversation conversation;
        private PagingOrder pagingOrder = PagingOrder.NORMAL;
        private XmppConnectionService.OnMoreMessagesLoaded callback = null;
        private boolean catchup = true;
        public final Version version;


        Query(Conversation conversation, MamReference start, long end, boolean catchup) {
            this(conversation.getAccount(), Version.get(conversation.getAccount(), conversation), catchup ? start : start.timeOnly(), end);
            this.conversation = conversation;
            this.membershipGeneration = conversation.getMode() == Conversation.MODE_MULTI
                    ? conversation.getMucOptions().getJoinAttempt().getMembershipGeneration() : 0;
            this.pagingOrder = catchup ? PagingOrder.NORMAL : PagingOrder.REVERSE;
            this.catchup = catchup;
        }

        Query(Account account, MamReference start, long end) {
            this(account, Version.get(account), start, end);
        }

        Query(Account account, Version version, MamReference start, long end) {
            this.account = account;
            this.fallbackStart = start.getTimestamp();
            if (start.getReference() != null) {
                this.reference = start.getReference();
            } else {
                this.start = start.getTimestamp();
            }
            this.end = end;
            this.queryId = new BigInteger(50, SECURE_RANDOM).toString(32);
            this.version = version;
        }

        private Query page(String reference) {
            Query query =
                    new Query(
                            this.account,
                            this.version,
                            new MamReference(this.fallbackStart, reference),
                            this.end);
            query.conversation = conversation;
            query.membershipGeneration = membershipGeneration;
            query.mucHistoryPurpose = mucHistoryPurpose;
            query.persistence = persistence.nextPage();
            query.notificationDelivery = notificationDelivery;
            query.notificationGeneration = notificationGeneration;
            query.notificationCandidates = notificationCandidates;
            query.totalCount = totalCount;
            query.actualCount = actualCount;
            query.pendingReceiptRequests = pendingReceiptRequests;
            query.receiptRequests = receiptRequests;
            query.callback = callback;
            query.catchup = catchup;
            query.cursorFallbackAttempted = cursorFallbackAttempted;
            query.cursorFallbackEnd = cursorFallbackEnd;
            return query;
        }

        private Query retryWithoutReference() {
            final long retryEnd =
                    pagingOrder == PagingOrder.REVERSE && cursorFallbackEnd > 0
                            ? cursorFallbackEnd
                            : this.end;
            final Query query =
                    new Query(
                            this.account,
                            this.version,
                            new MamReference(this.fallbackStart),
                            retryEnd);
            query.conversation = conversation;
            query.membershipGeneration = membershipGeneration;
            query.mucHistoryPurpose = mucHistoryPurpose;
            query.persistence = persistence.nextPage();
            query.notificationDelivery = notificationDelivery;
            query.notificationGeneration = notificationGeneration;
            query.notificationCandidates = notificationCandidates;
            query.pagingOrder = pagingOrder;
            query.actualCount = actualCount;
            query.pendingReceiptRequests = pendingReceiptRequests;
            query.receiptRequests = receiptRequests;
            query.callback = callback;
            query.catchup = catchup;
            if (query.notificationDelivery == MamNotificationDelivery.FORWARD_UNSEEN)
                query.notificationDelivery = MamNotificationDelivery.SILENT_UNSEEN;
            query.cursorFallbackAttempted = true;
            query.cursorFallbackEnd = cursorFallbackEnd;
            return query;
        }

        boolean canRetryWithoutReference() {
            if (reference == null || cursorFallbackAttempted || !persistence.canFallback()) {
                return false;
            }
            return pagingOrder == PagingOrder.NORMAL
                    ? fallbackStart > 0
                    : cursorFallbackEnd > 0;
        }

        long getFallbackStart() {
            return fallbackStart;
        }

        public void recordResultTimestamp(final long timestamp) {
            if (timestamp > 0 && timestamp < earliestResultTimestamp) {
                earliestResultTimestamp = timestamp;
            }
        }

        public void removePendingReceiptRequest(ReceiptRequest receiptRequest) {
            if (!this.pendingReceiptRequests.remove(receiptRequest)) {
                this.receiptRequests.add(receiptRequest);
            }
        }

        public void addPendingReceiptRequest(ReceiptRequest receiptRequest) {
            this.pendingReceiptRequests.add(receiptRequest);
        }

        public boolean isLegacy() {
            return version.legacy;
        }

        public boolean safeToExtractTrueCounterpart() {
            return muc() && !isLegacy();
        }

        public Query next(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.NORMAL;
            return query;
        }

        Query prev(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.REVERSE;
            if (earliestResultTimestamp != Long.MAX_VALUE) {
                query.cursorFallbackEnd = earliestResultTimestamp;
            }
            return query;
        }

        public String getReference() {
            return reference;
        }

        public PagingOrder getPagingOrder() {
            return this.pagingOrder;
        }

        public String getQueryId() {
            return queryId;
        }

        public Jid getWith() {
            return conversation == null ? null : conversation.getJid().asBareJid();
        }

        public boolean muc() {
            return conversation != null && conversation.getMode() == Conversation.MODE_MULTI;
        }

        public int getRequestedPageSize() {
            return requestedPageSize(
                    muc(),
                    Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT,
                    catchup);
        }

        public long getStart() {
            return start;
        }

        public boolean isCatchup() {
            return catchup;
        }

        public void setCallback(XmppConnectionService.OnMoreMessagesLoaded callback) {
            this.callback = callback;
        }

        public void callback(boolean done) {
            callback(done, true);
        }

        public void callback(boolean done, boolean terminal) {
            if (this.callback != null) {
                if (actionResult != null) {
                    MamDiagnostics.action(actionResult);
                    this.callback.onHistoryActionResult(actionResult, conversation);
                }
                this.callback.onMoreMessagesLoaded(actualCount, conversation, terminal);
            }
        }

        public long getEnd() {
            return end;
        }

        public Conversation getConversation() {
            return conversation;
        }

        public Account getAccount() {
            return this.account;
        }

        public void incrementMessageCount() {
            this.totalCount++;
            this.pageResultCount++;
            this.diagnostics.received();
        }

        public void incrementActualMessageCount() {
            this.actualInThisQuery++;
            this.actualCount++;
        }

        int getTotalCount() {
            return this.totalCount;
        }

        int getActualMessageCount() {
            return this.actualCount;
        }

        public int getActualInThisQuery() {
            return this.actualInThisQuery;
        }

        public boolean validFrom(Jid from) {
            if (muc()) {
                return getWith().equals(from);
            } else {
                return (from == null) || account.getJid().asBareJid().equals(from.asBareJid());
            }
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (this.muc()) {
                builder.append("to=");
                builder.append(this.getWith().toString());
            } else {
                builder.append("with=");
                if (this.getWith() == null) {
                    builder.append("*");
                } else {
                    builder.append(getWith().toString());
                }
            }
            if (this.start != 0) {
                builder.append(", start=");
                builder.append(AbstractGenerator.getTimestamp(this.start));
            }
            if (this.end != 0) {
                builder.append(", end=");
                builder.append(AbstractGenerator.getTimestamp(this.end));
            }
            builder.append(", order=").append(pagingOrder.toString());
            if (this.reference != null) {
                if (this.pagingOrder == PagingOrder.NORMAL) {
                    builder.append(", after=");
                } else {
                    builder.append(", before=");
                }
                builder.append(this.reference);
            }
            builder.append(", catchup=").append(catchup);
            builder.append(", ns=").append(version.namespace);
            return builder.toString();
        }

        boolean hasCallback() {
            return this.callback != null;
        }
    }
}
