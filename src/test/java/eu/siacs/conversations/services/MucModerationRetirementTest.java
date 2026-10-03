package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MucModerationRetirementTest {

    @Test
    public void successfulRetirementMarksContentRetiredAndInvalidatesCache() {
        final List<String> calls = new ArrayList<>();

        final boolean retired =
                MucModerationRetirement.retireNow(
                        "account-a",
                        "message-a",
                        (account, message) -> calls.add("retire:" + account + ":" + message),
                        (account, message) -> calls.add("invalidate:" + account + ":" + message));

        assertTrue(retired);
        assertEquals(
                List.of(
                        "retire:account-a:message-a",
                        "invalidate:account-a:message-a"),
                calls);
    }

    @Test
    public void failedRetirementStaysPendingButStillInvalidatesResidentCache() {
        final List<String> calls = new ArrayList<>();

        final boolean retired =
                MucModerationRetirement.retireNow(
                        "account-a",
                        "message-a",
                        (account, message) -> {
                            calls.add("retire");
                            throw new IOException("store unavailable");
                        },
                        (account, message) -> calls.add("invalidate"));

        assertFalse(retired);
        assertEquals(List.of("retire", "invalidate"), calls);
    }

    @Test
    public void cacheInvalidationFailureKeepsDurableRetirementPending() {
        final boolean retired =
                MucModerationRetirement.retireNow(
                        "account-a",
                        "message-a",
                        (account, message) -> {},
                        (account, message) -> {
                            throw new IOException("cache unavailable");
                        });

        assertFalse(retired);
    }

    @Test
    public void recoveryMarksOnlyEntriesWhoseRetirementAndInvalidationComplete() {
        final List<String> retired = new ArrayList<>();
        final List<String> invalidated = new ArrayList<>();
        final List<String> marked = new ArrayList<>();
        final List<String[]> pending =
                List.of(
                        new String[] {"account-a", "message-a"},
                        new String[] {"account-a", "message-b"},
                        new String[] {"account-a", "message-c"});

        final int recovered =
                MucModerationRetirement.recoverPending(
                        pending,
                        (account, message) -> {
                            retired.add(message);
                            if ("message-b".equals(message)) {
                                throw new IOException("retry later");
                            }
                        },
                        (account, message) -> invalidated.add(message),
                        (account, message) -> marked.add(message));

        assertEquals(1, recovered);
        assertEquals(List.of("message-a", "message-b"), retired);
        assertEquals(List.of("message-a"), invalidated);
        assertEquals(List.of("message-a"), marked);
    }

    @Test
    public void recoveryRequiresCacheInvalidationBeforeDurableRetiredMarker() {
        final List<String> marked = new ArrayList<>();

        final int recovered =
                MucModerationRetirement.recoverPending(
                        List.<String[]>of(new String[] {"account-a", "message-a"}),
                        (account, message) -> {},
                        (account, message) -> {
                            throw new IOException("cache unavailable");
                        },
                        (account, message) -> marked.add(message));

        assertEquals(0, recovered);
        assertTrue(marked.isEmpty());
    }

    @Test
    public void malformedPendingEntryIsIgnoredWithoutBlockingValidEntry() {
        final List<String> marked = new ArrayList<>();

        final int recovered =
                MucModerationRetirement.recoverPending(
                        List.of(
                                new String[] {"broken"},
                                new String[] {"account-a", "message-a"}),
                        (account, message) -> {},
                        (account, message) -> {},
                        (account, message) -> marked.add(message));

        assertEquals(1, recovered);
        assertEquals(List.of("message-a"), marked);
    }
}
