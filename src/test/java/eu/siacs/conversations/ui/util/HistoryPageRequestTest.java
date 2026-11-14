package eu.siacs.conversations.ui.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

public class HistoryPageRequestTest {
    @Test
    public void replacementFragmentCannotStartWhilePreviousPageIsInFlight() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final Object fragment = new Object();
        final Object activity = new Object();
        final Object view = new Object();
        final Object session = new Object();
        final HistoryPageRequest<Object> first =
                HistoryPageRequest.tryStart(gate, fragment, activity, view, session);
        assertNotNull(first);
        try {
            HistoryPageRequest.resetIfIdle(gate);
            assertFalse(gate.get());
            assertNull(
                    HistoryPageRequest.tryStart(
                            gate, new Object(), new Object(), new Object(), new Object()));
        } finally {
            first.finish();
        }
        final HistoryPageRequest<Object> replacement =
                HistoryPageRequest.tryStart(
                        gate, new Object(), new Object(), new Object(), new Object());
        assertNotNull(replacement);
        replacement.finish();
    }

    @Test
    public void lateOrDuplicateCompletionCannotReleaseNewerPage() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final HistoryPageRequest<Object> old = start(gate);
        old.finish(); // Destroyed view discarded a completed, scroll-deferred publication.
        final HistoryPageRequest<Object> current = start(gate);
        try {
            old.finish(); // The old main-queue publication finally runs.
            assertFalse(gate.get());
            assertFalse(old.isCurrent());
            assertTrue(current.isCurrent());
        } finally {
            current.finish();
        }
        assertTrue(gate.get());
    }

    @Test
    public void resetDoesNotPermitOverlapEvenIfLegacyCodeSetsGateTrue() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final HistoryPageRequest<Object> current = start(gate);
        try {
            gate.set(true);
            assertNull(
                    HistoryPageRequest.tryStart(
                            gate, new Object(), new Object(), new Object(), new Object()));
            assertTrue(current.isCurrent());
        } finally {
            current.finish();
        }
    }

    @Test
    public void completionReleasesOnlyItsOwnConversationAndDirection() {
        final AtomicBoolean backward = new AtomicBoolean(true);
        final AtomicBoolean forward = new AtomicBoolean(true);
        final AtomicBoolean otherChat = new AtomicBoolean(true);
        final HistoryPageRequest<Object> back = start(backward);
        final HistoryPageRequest<Object> ahead = start(forward);
        final HistoryPageRequest<Object> other = start(otherChat);
        try {
            back.finish();
            assertTrue(backward.get());
            assertFalse(forward.get());
            assertFalse(otherChat.get());
        } finally {
            ahead.finish();
            other.finish();
        }
    }

    @Test
    public void staleUiSessionRejectsChatSwitchAndReturnOnSameView() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final Object fragment = new Object();
        final Object activity = new Object();
        final Object view = new Object();
        final Object firstVisit = new Object();
        final HistoryPageRequest<Object> request =
                HistoryPageRequest.tryStart(gate, fragment, activity, view, firstVisit);
        try {
            assertTrue(request.matches(fragment, activity, view, firstVisit));
            assertFalse(request.matches(fragment, activity, view, new Object()));
            assertFalse(gate.get()); // Invalid UI does not itself complete a running query.
        } finally {
            request.finish();
        }
    }

    @Test
    public void recreatedActivityViewOrFragmentCannotPublishOldPage() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final Object fragment = new Object();
        final Object activity = new Object();
        final Object view = new Object();
        final Object session = new Object();
        final HistoryPageRequest<Object> request =
                HistoryPageRequest.tryStart(gate, fragment, activity, view, session);
        try {
            assertFalse(request.matches(fragment, new Object(), view, session));
            assertFalse(request.matches(fragment, activity, new Object(), session));
            assertFalse(request.matches(new Object(), activity, view, session));
            assertFalse(request.matches(fragment, null, null, session));
        } finally {
            request.finish();
        }
    }

    @Test
    public void collectedUiStillAllowsGateCleanup() throws Exception {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final Object fragment = new Object();
        final Object activity = new Object();
        final Object view = new Object();
        final Object session = new Object();
        final HistoryPageRequest<Object> request =
                HistoryPageRequest.tryStart(gate, fragment, activity, view, session);
        try {
            // Clear references deterministically rather than relying on the GC's timing.
            for (String name : new String[] {"target", "activity", "view"}) {
                final Field field = HistoryPageRequest.class.getDeclaredField(name);
                field.setAccessible(true);
                assertTrue(field.get(request) instanceof WeakReference);
                ((WeakReference<?>) field.get(request)).clear();
            }
            assertNull(request.target());
            assertFalse(request.matches(fragment, activity, view, session));
            for (Field field : HistoryPageRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                assertNotSame(fragment, field.get(request));
                assertNotSame(activity, field.get(request));
                assertNotSame(view, field.get(request));
            }
        } finally {
            request.finish();
        }
        assertTrue(gate.get());
    }

    @Test
    public void unavailableUiDoesNotAcquireGate() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        assertNull(
                HistoryPageRequest.tryStart(gate, null, new Object(), new Object(), new Object()));
        assertNull(
                HistoryPageRequest.tryStart(gate, new Object(), null, new Object(), new Object()));
        assertNull(
                HistoryPageRequest.tryStart(gate, new Object(), new Object(), null, new Object()));
        assertNull(
                HistoryPageRequest.tryStart(gate, new Object(), new Object(), new Object(), null));
        assertTrue(gate.get());
    }

    @Test
    public void idleResetKeepsLegacyReinitializationAvailable() {
        final AtomicBoolean gate = new AtomicBoolean(false);
        assertNull(
                HistoryPageRequest.tryStart(
                        gate, new Object(), new Object(), new Object(), new Object()));
        HistoryPageRequest.resetIfIdle(gate);
        final HistoryPageRequest<Object> request = start(gate);
        request.finish();
        assertTrue(gate.get());
    }

    @Test
    public void intermediatePagesKeepOwnershipUntilFinalCompletion() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final HistoryPageRequest<Object> request = start(gate);
        request.finishPublication(false);
        assertFalse(gate.get());
        assertTrue(request.isCurrent());
        request.finishPublication(false);
        assertTrue(request.isCurrent());
        request.finishPublication(true);
        assertTrue(gate.get());
        assertFalse(request.isCurrent());
    }

    @Test
    public void discardedIntermediatePageDoesNotAllowReplacementRequest() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final HistoryPageRequest<Object> request = start(gate);
        try {
            request.finishPublication(false);
            HistoryPageRequest.resetIfIdle(gate);
            assertFalse(gate.get());
            assertNull(
                    HistoryPageRequest.tryStart(
                            gate, new Object(), new Object(), new Object(), new Object()));
        } finally {
            request.finishPublication(true);
        }
    }

    @Test
    public void repeatedTerminalOrStaleProgressCannotReleaseNewOwner() {
        final AtomicBoolean gate = new AtomicBoolean(true);
        final HistoryPageRequest<Object> old = start(gate);
        old.finishPublication(true);
        final HistoryPageRequest<Object> current = start(gate);
        try {
            old.finishPublication(false);
            old.finishPublication(true);
            assertFalse(gate.get());
            assertTrue(current.isCurrent());
        } finally {
            current.finish();
        }
    }

    private static HistoryPageRequest<Object> start(AtomicBoolean gate) {
        final HistoryPageRequest<Object> request =
                HistoryPageRequest.tryStart(
                        gate, new Object(), new Object(), new Object(), new Object());
        assertNotNull(request);
        return request;
    }
}
