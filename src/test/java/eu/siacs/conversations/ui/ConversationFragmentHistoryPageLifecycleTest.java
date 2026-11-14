package eu.siacs.conversations.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.HistoryPageRequest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConversationFragmentHistoryPageLifecycleTest {
    @Test
    public void emptyCallbackAfterDetachReleasesOnlyBackwardGate() throws Exception {
        assertDetachedCompletion(true, 0);
    }

    @Test
    public void nonEmptyCallbackAfterDetachReleasesOnlyBackwardGate() throws Exception {
        assertDetachedCompletion(true, 12);
    }

    @Test
    public void emptyCallbackAfterDetachReleasesOnlyForwardGate() throws Exception {
        assertDetachedCompletion(false, 0);
    }

    @Test
    public void nonEmptyCallbackAfterDetachReleasesOnlyForwardGate() throws Exception {
        assertDetachedCompletion(false, 12);
    }

    @Test
    public void queuedOldViewPublicationCannotUnlockReplacementRequest() throws Exception {
        final Conversation room = room();
        final ConversationFragment fragment = new ConversationFragment();
        setConversation(fragment, room);
        final HistoryPageRequest<ConversationFragment> old = request(fragment, room, true);
        final XmppConnectionService.OnMoreMessagesLoaded callback = callback(old, room, true);
        final Class<?> type =
                Class.forName(ConversationFragment.class.getName() + "$HistoryPageUiPublish");
        final Constructor<?> constructor =
                type.getDeclaredConstructor(callback.getClass(), int.class, boolean.class);
        constructor.setAccessible(true);
        final Runnable pending = (Runnable) constructor.newInstance(callback, 3, true);
        new Handler(Looper.getMainLooper()).post(pending); // Scroll IDLE transferred work to main.
        HistoryPageRequest.resetIfIdle(room.messagesLoaded); // Replacement fragment reInit.
        assertFalse(room.messagesLoaded.get());
        assertNull(
                HistoryPageRequest.tryStart(
                        room.messagesLoaded,
                        new ConversationFragment(),
                        new Object(),
                        new Object(),
                        new Object()));
        ShadowLooper.idleMainLooper(); // The old View is gone, but Handler still delivers cleanup.
        assertTrue(room.messagesLoaded.get());
        final HistoryPageRequest<ConversationFragment> replacement =
                request(new ConversationFragment(), room, true);
        try {
            pending.run(); // Even a repeated old publication cannot release the new gate.
            assertFalse(room.messagesLoaded.get());
            assertTrue(replacement.isCurrent());
        } finally {
            replacement.finish();
        }
    }

    @Test
    public void callbackAndQueuedPublicationHaveNoImplicitFragmentReference() throws Exception {
        for (String name : new String[] {"HistoryPageCallback", "HistoryPageUiPublish"}) {
            final Class<?> type = Class.forName(ConversationFragment.class.getName() + "$" + name);
            assertTrue(Modifier.isStatic(type.getModifiers()));
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.isSynthetic());
                assertFalse(ConversationFragment.class.isAssignableFrom(field.getType()));
            }
        }
    }

    @Test
    public void detachedProgressDoesNotCloseCallbackOrGateBeforeTerminal() throws Exception {
        for (boolean backward : new boolean[] {true, false}) {
            final Conversation room = room();
            final ConversationFragment fragment = new ConversationFragment();
            setConversation(fragment, room);
            final HistoryPageRequest<ConversationFragment> request =
                    request(fragment, room, backward);
            final XmppConnectionService.OnMoreMessagesLoaded callback =
                    callback(request, room, backward);
            callback.onMoreMessagesLoaded(2, room, false);
            ShadowLooper.idleMainLooper();
            assertTrue(request.isCurrent());
            callback.onMoreMessagesLoaded(5, room, false);
            ShadowLooper.idleMainLooper();
            assertTrue(request.isCurrent());
            callback.onMoreMessagesLoaded(5, room, true);
            ShadowLooper.idleMainLooper();
            assertFalse(request.isCurrent());

            final HistoryPageRequest<ConversationFragment> newer =
                    request(fragment, room, backward);
            try {
                callback.onMoreMessagesLoaded(5, room, true);
                callback.onMoreMessagesLoaded(6, room, false);
                ShadowLooper.idleMainLooper();
                assertTrue(newer.isCurrent());
            } finally {
                newer.finish();
            }
        }
    }

    @Test
    public void discardingDeferredProgressPreservesRunningRequest() throws Exception {
        assertDiscardedPublication(false);
    }

    @Test
    public void discardingDeferredTerminalReleasesOnlyItsOwnRequest() throws Exception {
        assertDiscardedPublication(true);
    }

    private static void assertDiscardedPublication(boolean terminal) throws Exception {
        final Conversation room = room();
        final ConversationFragment fragment = new ConversationFragment();
        setConversation(fragment, room);
        final HistoryPageRequest<ConversationFragment> request = request(fragment, room, true);
        final XmppConnectionService.OnMoreMessagesLoaded callback = callback(request, room, true);
        final Class<?> type =
                Class.forName(ConversationFragment.class.getName() + "$HistoryPageUiPublish");
        final Constructor<?> constructor =
                type.getDeclaredConstructor(callback.getClass(), int.class, boolean.class);
        constructor.setAccessible(true);
        final Runnable pending = (Runnable) constructor.newInstance(callback, 3, terminal);
        final Field field =
                ConversationFragment.class.getDeclaredField("pendingHistoryPageUiPublish");
        field.setAccessible(true);
        field.set(fragment, pending);
        final java.lang.reflect.Method discard =
                ConversationFragment.class.getDeclaredMethod("discardPendingHistoryPageUiPublish");
        discard.setAccessible(true);
        discard.invoke(fragment);
        assertNull(field.get(fragment));
        assertEquals(!terminal, request.isCurrent());
        pending.run();
        assertEquals(!terminal, request.isCurrent());
        if (!terminal) {
            callback.onMoreMessagesLoaded(4, room, true);
            ShadowLooper.idleMainLooper();
            assertFalse(request.isCurrent());
        }
    }

    private static void assertDetachedCompletion(boolean backward, int count) throws Exception {
        final Conversation room = room();
        final ConversationFragment fragment = new ConversationFragment();
        setConversation(fragment, room);
        final HistoryPageRequest<ConversationFragment> request = request(fragment, room, backward);
        final java.util.concurrent.atomic.AtomicBoolean other =
                backward ? room.historyPartLoadedForward : room.messagesLoaded;
        other.set(false);
        final XmppConnectionService.OnMoreMessagesLoaded callback =
                callback(request, room, backward);
        callback.onMoreMessagesLoaded(count, room);
        assertTrue(
                request.isCurrent()); // A detached fragment must still receive main-queue cleanup.
        ShadowLooper.idleMainLooper();
        assertFalse(request.isCurrent());
        assertTrue((backward ? room.messagesLoaded : room.historyPartLoadedForward).get());
        assertFalse(other.get());

        final HistoryPageRequest<ConversationFragment> newer = request(fragment, room, backward);
        try {
            callback.onMoreMessagesLoaded(count, room); // Duplicate transport completion.
            ShadowLooper.idleMainLooper();
            assertTrue(newer.isCurrent());
            assertFalse((backward ? room.messagesLoaded : room.historyPartLoadedForward).get());
        } finally {
            newer.finish();
        }
    }

    private static Conversation room() {
        return new Conversation(
                "history-test",
                "history",
                null,
                "account",
                null,
                0L,
                Conversation.STATUS_AVAILABLE,
                Conversation.MODE_SINGLE,
                "",
                null);
    }

    private static void setConversation(ConversationFragment fragment, Conversation room)
            throws Exception {
        final Field field = ConversationFragment.class.getDeclaredField("conversation");
        field.setAccessible(true);
        field.set(fragment, room);
    }

    private static HistoryPageRequest<ConversationFragment> request(
            ConversationFragment fragment, Conversation room, boolean backward) {
        final HistoryPageRequest<ConversationFragment> request =
                HistoryPageRequest.tryStart(
                        backward ? room.messagesLoaded : room.historyPartLoadedForward,
                        fragment,
                        new Object(),
                        new Object(),
                        new Object());
        assertNotNull(request);
        return request;
    }

    private static XmppConnectionService.OnMoreMessagesLoaded callback(
            HistoryPageRequest<ConversationFragment> request, Conversation room, boolean backward)
            throws Exception {
        final Class<?> type =
                Class.forName(ConversationFragment.class.getName() + "$HistoryPageCallback");
        final Constructor<?> constructor =
                type.getDeclaredConstructor(
                        HistoryPageRequest.class,
                        Conversation.class,
                        boolean.class,
                        boolean.class,
                        String.class);
        constructor.setAccessible(true);
        return (XmppConnectionService.OnMoreMessagesLoaded)
                constructor.newInstance(request, room, backward, !backward, null);
    }
}
