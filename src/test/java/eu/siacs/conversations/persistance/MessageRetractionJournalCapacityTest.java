package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import org.junit.Test;

public class MessageRetractionJournalCapacityTest {

    @Test
    public void emptyJournalAcceptsEvents() {
        assertTrue(DatabaseBackendImpl.hasRetractionJournalCapacity(
                0, 0, 0));
    }

    @Test
    public void roomLimitIsEnforced() {
        assertTrue(DatabaseBackendImpl.hasRetractionJournalCapacity(
                127, 127, 31));

        assertFalse(DatabaseBackendImpl.hasRetractionJournalCapacity(
                128, 128, 1));
    }

    @Test
    public void senderLimitIsEnforced() {
        assertFalse(DatabaseBackendImpl.hasRetractionJournalCapacity(
                32, 32, 32));
    }

    @Test
    public void accountLimitIsEnforced() {
        assertFalse(DatabaseBackendImpl.hasRetractionJournalCapacity(
                1024, 1, 1));
    }

    @Test
    public void invalidCountersFailClosed() {
        assertFalse(DatabaseBackendImpl.hasRetractionJournalCapacity(
                -1, 0, 0));
    }
}
