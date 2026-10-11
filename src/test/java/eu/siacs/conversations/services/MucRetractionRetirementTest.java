package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.persistance.DatabaseBackend;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MucRetractionRetirementTest {

    private static DatabaseBackend.PendingRetractionRetirement job(
            String messageUuid) {
        return new DatabaseBackend.PendingRetractionRetirement(
                "account-a",
                "room-a",
                "request-" + messageUuid,
                messageUuid);
    }

    @Test
    public void successfulRecoveryFollowsRequiredOrder() {
        final List<String> calls = new ArrayList<>();

        final int completed =
                MucRetractionRetirement.recoverPending(
                        List.of(job("message-a")),
                        (account, message) ->
                                calls.add("retire"),
                        (account, message) ->
                                calls.add("invalidate"),
                        entry -> {
                            calls.add("mark");
                            return true;
                        });

        assertEquals(1, completed);
        assertEquals(
                List.of("retire", "invalidate", "mark"),
                calls);
    }

    @Test
    public void failedScsRetirementCannotBeAcknowledged() {
        final List<String> calls = new ArrayList<>();

        final int completed =
                MucRetractionRetirement.recoverPending(
                        List.of(job("message-a")),
                        (account, message) -> {
                            calls.add("retire");
                            throw new IOException("SCS unavailable");
                        },
                        (account, message) ->
                                calls.add("invalidate"),
                        entry -> {
                            calls.add("mark");
                            return true;
                        });

        assertEquals(0, completed);
        assertEquals(List.of("retire"), calls);
    }

    @Test
    public void cacheFailureLeavesJobPending() {
        final List<String> calls = new ArrayList<>();

        final int completed =
                MucRetractionRetirement.recoverPending(
                        List.of(job("message-a")),
                        (account, message) ->
                                calls.add("retire"),
                        (account, message) -> {
                            calls.add("invalidate");
                            throw new IOException("cache unavailable");
                        },
                        entry -> {
                            calls.add("mark");
                            return true;
                        });

        assertEquals(0, completed);
        assertEquals(
                List.of("retire", "invalidate"),
                calls);
    }

    @Test
    public void failedSqlAcknowledgementStopsRecovery() {
        final List<String> marked = new ArrayList<>();

        final int completed =
                MucRetractionRetirement.recoverPending(
                        List.of(job("message-a"), job("message-b")),
                        (account, message) -> {},
                        (account, message) -> {},
                        entry -> {
                            marked.add(entry.messageUuid);
                            return false;
                        });

        assertEquals(0, completed);
        assertEquals(List.of("message-a"), marked);
    }

    @Test
    public void successfulFirstJobSurvivesFailureOfSecond() {
        final List<String> marked = new ArrayList<>();

        final int completed =
                MucRetractionRetirement.recoverPending(
                        List.of(job("message-a"), job("message-b")),
                        (account, message) -> {
                            if ("message-b".equals(message)) {
                                throw new IOException("retry later");
                            }
                        },
                        (account, message) -> {},
                        entry -> {
                            marked.add(entry.messageUuid);
                            return true;
                        });

        assertEquals(1, completed);
        assertEquals(List.of("message-a"), marked);
    }
}
