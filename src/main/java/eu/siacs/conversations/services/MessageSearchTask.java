/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.services;

import android.database.Cursor;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.IndividualMessage;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.StubConversation;
import eu.siacs.conversations.storage.secure.SecureMessageSearchCandidate;
import eu.siacs.conversations.storage.secure.SecureMessageSearchCandidatePage;
import eu.siacs.conversations.storage.secure.SecureMessageSearchCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageSearchCursor;
import eu.siacs.conversations.storage.secure.SecureMessageSearchHydrationResult;
import eu.siacs.conversations.storage.secure.SecureMessageSearchRuntime;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.utils.Cancellable;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.ReplacingSerialSingleThreadExecutor;
import eu.siacs.conversations.xmpp.Jid;

public class MessageSearchTask implements Runnable, Cancellable {

	private static final ReplacingSerialSingleThreadExecutor EXECUTOR = new ReplacingSerialSingleThreadExecutor(MessageSearchTask.class.getName());
	private static final int CANDIDATE_BATCH = 50;
	private static final int MAX_SECURE_CANDIDATES_SCANNED = 1200;
	private static final Comparator<Message> RESULT_ORDER =
			(first, second) -> {
				final int timeOrder = Long.compare(second.getTimeSent(), first.getTimeSent());
				return timeOrder != 0
						? timeOrder
						: second.getUuid().compareTo(first.getUuid());
			};

	private final XmppConnectionService xmppConnectionService;
	private final List<String> term;
	private final String uuid;
	private final OnSearchResultsAvailable onSearchResultsAvailable;

	private volatile boolean isCancelled = false;

	private MessageSearchTask(XmppConnectionService xmppConnectionService, List<String> term, final String uuid, OnSearchResultsAvailable onSearchResultsAvailable) {
		this.xmppConnectionService = xmppConnectionService;
		this.term = term;
		this.uuid = uuid;
		this.onSearchResultsAvailable = onSearchResultsAvailable;
	}

	public static void search(XmppConnectionService xmppConnectionService, List<String> term, final String uuid, OnSearchResultsAvailable onSearchResultsAvailable) {
		new MessageSearchTask(xmppConnectionService, term, uuid, onSearchResultsAvailable).executeInBackground();
	}

	public static void cancelRunningTasks() {
		EXECUTOR.cancelRunningTasks();
	}

	@Override
	public void cancel() {
		this.isCancelled = true;
	}

	@Override
	public void run() {
		long startTimestamp = SystemClock.elapsedRealtime();
		Cursor cursor = null;
		try {
			final HashMap<String, Conversational> conversationCache = new HashMap<>();
			final List<Message> result = new ArrayList<>();
			cursor = xmppConnectionService.databaseBackend.getLegacyMessageSearchCursor(term, uuid);
			long legacyFtsStopTimestamp = SystemClock.elapsedRealtime();
			if (isCancelled) {
				Log.d(Config.LOGTAG, "canceled search task");
				return;
			}
			if (cursor != null && cursor.getCount() > 0) {
				cursor.moveToFirst();
				final int indexBody = cursor.getColumnIndex(Message.BODY);
				final int indexOob = cursor.getColumnIndex(Message.OOB);
				final int indexConversation = cursor.getColumnIndex(Message.CONVERSATION);
				final int indexAccount = cursor.getColumnIndex(Conversation.ACCOUNT);
				final int indexContact = cursor.getColumnIndex(Conversation.CONTACTJID);
				final int indexMode = cursor.getColumnIndex(Conversation.MODE);
				final int indexNextCounterpart = cursor.getColumnIndex(Conversation.NEXT_COUNTERPART);
				do {
					if (isCancelled) {
						Log.d(Config.LOGTAG, "canceled search task");
						return;
					}
					final String body = cursor.getString(indexBody);
					final boolean oob = cursor.getInt(indexOob) > 0;
					if (MessageUtils.treatAsDownloadable(body,oob)) {
						continue;
					}
					final String conversationUuid = cursor.getString(indexConversation);
					Conversational conversation = conversationCache.get(conversationUuid);
					if (conversation == null) {
						String accountUuid = cursor.getString(indexAccount);
						String contactJid = cursor.getString(indexContact);
						String nextCounterpart = cursor.getString(indexNextCounterpart);
						int mode = cursor.getInt(indexMode);
						conversation = findOrGenerateStub(conversationUuid, accountUuid, contactJid, mode, nextCounterpart);
						conversationCache.put(conversationUuid, conversation);
					}
					Message message = IndividualMessage.fromCursor(cursor, conversation);
					if (isSearchVisible(message)) {
						result.add(message);
					}
				} while (cursor.moveToNext());
			}
			if (Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT && !isCancelled) {
				try {
					final SecureMessageSearchCoordinator secureSearch =
							SecureMessageSearchRuntime.INSTANCE.create(
									xmppConnectionService.databaseBackend);
					if (secureSearch != null
							&& !appendSecureSearchResults(result, secureSearch)) {
						Log.d(Config.LOGTAG, "canceled search task");
						return;
					}
				} catch (final Exception exception) {
					Log.w(
							Config.LOGTAG,
							"secure message search failed; returning legacy results",
							exception);
				}
			}
			sortAndTrimResults(result);
			Collections.reverse(result);
			if (isCancelled) {
				Log.d(Config.LOGTAG, "canceled search task before publishing results");
				return;
			}
			long stopTimestamp = SystemClock.elapsedRealtime();
			Log.d(Config.LOGTAG, "found " + result.size() + " messages in " + (stopTimestamp - startTimestamp) + "ms"+ " (legacy FTS was "+(legacyFtsStopTimestamp - startTimestamp)+"ms)");
			onSearchResultsAvailable.onSearchResultsAvailable(term, result);
		} catch (Exception e) {
			Log.d(Config.LOGTAG, "exception while searching ", e);
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
	}

	private boolean appendSecureSearchResults(
			final List<Message> result,
			final SecureMessageSearchCoordinator secureSearch) {
		sortAndTrimResults(result);
		final Set<String> resultUuids = new HashSet<>();
		for (final Message message : result) {
			resultUuids.add(message.getUuid());
		}

		final Map<String, Conversational> conversationsByUuid = new HashMap<>();
		final Map<String, String> accountScopes = new LinkedHashMap<>();
		for (final Conversation conversation : xmppConnectionService.getConversations()) {
			conversationsByUuid.put(conversation.getUuid(), conversation);
		}
		if (!appendResidentProtectedSearchResults(result, resultUuids, secureSearch)) {
			return false;
		}
		sortAndTrimResults(result);
		resultUuids.clear();
		for (final Message message : result) {
			resultUuids.add(message.getUuid());
		}
		if (uuid == null) {
			for (final Account account : xmppConnectionService.getAccounts()) {
				accountScopes.put(account.getUuid(), null);
			}
		} else {
			final Conversational conversation = conversationsByUuid.get(uuid);
			final String accountUuid =
					conversation == null
							? secureSearch.resolveAccountUuid(uuid)
							: conversation.getAccount().getUuid();
			if (accountUuid != null) {
				accountScopes.put(accountUuid, uuid);
			}
		}
		if (accountScopes.isEmpty()) {
			return true;
		}

		final PriorityQueue<SecureCandidateStream> streams =
				new PriorityQueue<>(
						(first, second) ->
								compareCandidates(first.peek(), second.peek()));
		for (final Map.Entry<String, String> scope : accountScopes.entrySet()) {
			final SecureCandidateStream stream =
					new SecureCandidateStream(scope.getKey(), scope.getValue());
			if (refillCandidateStream(stream, secureSearch)) {
				streams.add(stream);
			}
			if (isCancelled) {
				return false;
			}
		}

		int processedSecureCandidates = 0;
		while (!streams.isEmpty()) {
			if (isCancelled) {
				return false;
			}
			if (processedSecureCandidates >= MAX_SECURE_CANDIDATES_SCANNED) {
				return true;
			}
			final List<SecureMessageSearchCandidate> candidateBatch =
					new ArrayList<>(CANDIDATE_BATCH);
			boolean reachedResultCutoff = false;
			while (candidateBatch.size() < CANDIDATE_BATCH && !streams.isEmpty()) {
				final SecureCandidateStream stream = streams.remove();
				final SecureMessageSearchCandidate candidate = stream.remove();
				if (!canDisplaceResultCutoff(candidate, result)) {
					reachedResultCutoff = true;
					break;
				}
				candidateBatch.add(candidate);
				processedSecureCandidates++;
				if (processedSecureCandidates >= MAX_SECURE_CANDIDATES_SCANNED) {
					break;
				}
				if (refillCandidateStream(stream, secureSearch)) {
					streams.add(stream);
				}
				if (isCancelled) {
					return false;
				}
			}
			if (candidateBatch.isEmpty()) {
				return true;
			}
			if (!processCandidateBatch(
					candidateBatch, conversationsByUuid, result, resultUuids, secureSearch)) {
				return false;
			}
			sortAndTrimResults(result);
			resultUuids.clear();
			for (final Message message : result) {
				resultUuids.add(message.getUuid());
			}
			if (reachedResultCutoff) {
				return true;
			}
		}
		return true;
	}

	private boolean appendResidentProtectedSearchResults(
			final List<Message> result,
			final Set<String> resultUuids,
			final SecureMessageSearchCoordinator secureSearch) {
		for (final Conversation conversation : xmppConnectionService.getConversations()) {
			if (uuid != null && !uuid.equals(conversation.getUuid())) {
				continue;
			}
			for (final Message message : conversation.snapshotMessages()) {
				if (isCancelled) {
					return false;
				}
				final String plaintext = message.getVerifiedProtectedBodyOrNull();
				if (plaintext != null
						&& secureSearch.matchesPlaintext(plaintext, term)
						&& resultUuids.add(message.getUuid())) {
					result.add(message);
				}
			}
		}
		return true;
	}

	private boolean refillCandidateStream(
			final SecureCandidateStream stream,
			final SecureMessageSearchCoordinator secureSearch) {
		if (stream.hasCandidate()) {
			return true;
		}
		if (stream.exhausted || isCancelled) {
			return false;
		}
		final SecureMessageSearchCandidatePage page;
		try {
			page =
					secureSearch.searchCandidatePage(
							stream.accountUuid,
							stream.conversationUuid,
							term,
							stream.cursor,
							CANDIDATE_BATCH);
		} catch (final Exception exception) {
			stream.exhausted = true;
			Log.w(
					Config.LOGTAG,
					"secure search scope unavailable for one account",
					exception);
			return false;
		}
		if (isCancelled) {
			return false;
		}
		stream.apply(page);
		return stream.hasCandidate();
	}

	private boolean processCandidateBatch(
			final List<SecureMessageSearchCandidate> candidates,
			final Map<String, Conversational> conversationsByUuid,
			final List<Message> result,
			final Set<String> resultUuids,
			final SecureMessageSearchCoordinator secureSearch) {
		final Map<Conversational, Set<String>> unresolvedByConversation = new LinkedHashMap<>();
		for (final SecureMessageSearchCandidate candidate : candidates) {
			Conversational conversation = conversationsByUuid.get(candidate.getConversationUuid());
			if (conversation == null) {
				final Conversation resident =
						xmppConnectionService.findConversationByUuid(
								candidate.getConversationUuid());
				if (resident != null) {
					conversation = resident;
				} else {
					try {
						conversation =
								findOrGenerateStub(
										candidate.getConversationUuid(),
										candidate.getAccountUuid(),
										candidate.getContactJid(),
										candidate.getConversationMode(),
										candidate.getNextCounterpart());
					} catch (final Exception exception) {
						Log.w(
								Config.LOGTAG,
								"unable to resolve secure search conversation "
										+ candidate.getConversationUuid(),
								exception);
						continue;
					}
				}
				conversationsByUuid.put(conversation.getUuid(), conversation);
			}
			if (conversation == null
					|| !candidate.getAccountUuid().equals(
							conversation.getAccount().getUuid())) {
				continue;
			}
			final Message residentMessage =
					conversation instanceof Conversation
							? ((Conversation) conversation)
									.findResidentMessageWithUuid(candidate.getMessageUuid())
							: null;
			if (residentMessage != null && !isSearchVisible(residentMessage)) {
				continue;
			}
			final String residentPlaintext =
					residentMessage == null
							? null
							: residentMessage.getVerifiedProtectedBodyOrNull();
			if (residentPlaintext != null) {
				if (secureSearch.matchesPlaintext(residentPlaintext, term)
						&& resultUuids.add(residentMessage.getUuid())) {
					result.add(residentMessage);
				}
				continue;
			}
			unresolvedByConversation
					.computeIfAbsent(conversation, ignored -> new LinkedHashSet<>())
					.add(candidate.getMessageUuid());
		}
		if (isCancelled) {
			return false;
		}

		final var repository = xmppConnectionService.getSecureMessageTextRepository();
		for (final Map.Entry<Conversational, Set<String>> unresolved :
				unresolvedByConversation.entrySet()) {
			if (isCancelled) {
				return false;
			}
			final List<Message> protectedMessages =
					xmppConnectionService.databaseBackend.getMessagesByLocalUuids(
							unresolved.getKey(), unresolved.getValue());
			if (isCancelled) {
				return false;
			}
			protectedMessages.removeIf(message -> !isSearchVisible(message));
			if (repository != null) {
				final SecureMessageSearchHydrationResult hydration =
						repository.hydrateSearchPage(
								protectedMessages,
								() -> isCancelled);
				if (hydration.getFailed() > 0) {
					Log.w(
							Config.LOGTAG,
							"secure search hydration skipped "
									+ hydration.getFailed()
									+ " of "
									+ hydration.getAttempted()
									+ " protected candidates");
				}
				if (hydration.getCancelled()) {
					return false;
				}
			}
			if (isCancelled) {
				return false;
			}
			for (final Message message : protectedMessages) {
				final String plaintext = message.getVerifiedProtectedBodyOrNull();
				if (plaintext != null
						&& secureSearch.matchesPlaintext(plaintext, term)
						&& resultUuids.add(message.getUuid())) {
					result.add(message);
				}
			}
		}
		return !isCancelled;
	}

	static boolean isSearchVisible(final Message message) {
		return message != null && !message.isModerated();
	}

	private static int compareCandidates(
			final SecureMessageSearchCandidate first,
			final SecureMessageSearchCandidate second) {
		final int timeOrder = Long.compare(second.getTimeSent(), first.getTimeSent());
		if (timeOrder != 0) {
			return timeOrder;
		}
		final int uuidOrder = second.getMessageUuid().compareTo(first.getMessageUuid());
		if (uuidOrder != 0) {
			return uuidOrder;
		}
		return second.getAccountUuid().compareTo(first.getAccountUuid());
	}

	private static boolean canDisplaceResultCutoff(
			final SecureMessageSearchCandidate candidate,
			final List<Message> result) {
		if (result.size() < Config.MAX_SEARCH_RESULTS) {
			return true;
		}
		final Message cutoff = result.get(Config.MAX_SEARCH_RESULTS - 1);
		if (candidate.getTimeSent() != cutoff.getTimeSent()) {
			return candidate.getTimeSent() > cutoff.getTimeSent();
		}
		return candidate.getMessageUuid().compareTo(cutoff.getUuid()) > 0;
	}

	private static void sortAndTrimResults(final List<Message> result) {
		result.sort(RESULT_ORDER);
		if (result.size() > Config.MAX_SEARCH_RESULTS) {
			result.subList(Config.MAX_SEARCH_RESULTS, result.size()).clear();
		}
	}

	private static final class SecureCandidateStream {
		private final String accountUuid;
		private final String conversationUuid;
		private final ArrayDeque<SecureMessageSearchCandidate> candidates =
				new ArrayDeque<>();
		private SecureMessageSearchCursor cursor;
		private boolean exhausted;

		private SecureCandidateStream(
				final String accountUuid,
				final String conversationUuid) {
			this.accountUuid = accountUuid;
			this.conversationUuid = conversationUuid;
		}

		private boolean hasCandidate() {
			return !candidates.isEmpty();
		}

		private SecureMessageSearchCandidate peek() {
			return candidates.peekFirst();
		}

		private SecureMessageSearchCandidate remove() {
			return candidates.removeFirst();
		}

		private void apply(final SecureMessageSearchCandidatePage page) {
			candidates.addAll(page.getCandidates());
			cursor = page.getNextCursor();
			exhausted = page.getExhausted() || candidates.isEmpty();
		}
	}

	private Conversational findOrGenerateStub(String conversationUuid, String accountUuid, String contactJid, int mode, String nextCounterpart) throws Exception {
		Conversation conversation = xmppConnectionService.findConversationByUuid(conversationUuid);
		if (conversation != null) {
			return conversation;
		}
		Account account = xmppConnectionService.findAccountByUuid(accountUuid);
		Jid jid = Jid.of(contactJid);
		if (account != null && jid != null) {
			return new StubConversation(account, conversationUuid, jid.asBareJid(), mode, Jid.of(nextCounterpart));
		}
		throw new Exception("Unable to generate stub for " + contactJid);
	}

	private void executeInBackground() {
		EXECUTOR.execute(this);
	}
}
