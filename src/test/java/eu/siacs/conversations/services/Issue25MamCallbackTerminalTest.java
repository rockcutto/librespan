package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.mam.MamReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class Issue25MamCallbackTerminalTest {
    @Test
    public void nextPageKeepsCallbackAndDistinguishesProgressFromTerminal() {
        final List<Boolean> terminalEvents = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final MessageArchiveService.Query first = query();
        first.setCallback(
                new XmppConnectionService.OnMoreMessagesLoaded() {
                    @Override
                    public void onMoreMessagesLoaded(int count, Conversation room) {
                        throw new AssertionError("MAM must pass its terminal flag");
                    }

                    @Override
                    public void onMoreMessagesLoaded(
                            int count, Conversation room, boolean terminal) {
                        terminalEvents.add(terminal);
                        counts.add(count);
                    }
                });
        first.incrementActualMessageCount();
        first.callback(false, false);
        final MessageArchiveService.Query next = first.next("next-cursor");
        next.incrementActualMessageCount();
        // A capped/error/incomplete operation can be terminal without exhausting the archive.
        next.callback(false, true);
        assertEquals(Arrays.asList(false, true), terminalEvents);
        assertEquals(Arrays.asList(1, 2), counts);
    }

    @Test
    public void legacyLambdaStillReceivesEveryPage() {
        final List<Integer> counts = new ArrayList<>();
        final MessageArchiveService.Query first = query();
        first.setCallback((count, room) -> counts.add(count));
        first.incrementActualMessageCount();
        first.callback(false, false);
        final MessageArchiveService.Query next = first.next("next-cursor");
        next.incrementActualMessageCount();
        next.callback(true, true);
        assertEquals(Arrays.asList(1, 2), counts);
    }

    @Test
    public void oldQueryCallbackEntryRemainsTerminalRegardlessOfDone() {
        final List<Boolean> terminalEvents = new ArrayList<>();
        final MessageArchiveService.Query query = query();
        query.setCallback(
                new XmppConnectionService.OnMoreMessagesLoaded() {
                    @Override
                    public void onMoreMessagesLoaded(int count, Conversation room) {
                        throw new AssertionError("Expected the terminal-aware overload");
                    }

                    @Override
                    public void onMoreMessagesLoaded(
                            int count, Conversation room, boolean terminal) {
                        terminalEvents.add(terminal);
                    }
                });
        query.callback(false);
        assertEquals(Arrays.asList(true), terminalEvents);
    }

    private static MessageArchiveService.Query query() {
        final MessageArchiveService service = new MessageArchiveService(null);
        return service
        .new Query(null, MessageArchiveService.Version.MAM_2, new MamReference(0), 0L);
    }
}
