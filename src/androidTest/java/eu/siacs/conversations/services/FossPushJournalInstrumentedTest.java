package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real encrypted journal across new instances, without starting a service. */
@RunWith(AndroidJUnit4.class)
public class FossPushJournalInstrumentedTest {
    private Context context;
    private static final String TOKEN = "01234567890123456789012345678901";
    @Before public void clearJournal() {
        Context base = ApplicationProvider.getApplicationContext();
        final String namespace = "test-" + java.util.UUID.randomUUID();
        context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + "-" + name, mode);
            }
        };
        context.getSharedPreferences(FossPushEventJournal.FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void endpointSurvivesWhenNoServiceIsStarted() {
        assertTrue(new FossPushEventJournal(context).enqueue("endpoint", TOKEN, "https://example.test/e"));
        // Re-adding after a simulated restart is idempotent, and may still be acknowledged.
        assertTrue(new FossPushEventJournal(context).enqueue("endpoint", TOKEN, "https://example.test/e"));
        assertFalse(new FossPushEventJournal(context).revoked(TOKEN));
    }
    @Test public void activeRevocationSurvivesRestartAndDuplicates() {
        assertTrue(new FossPushEventJournal(context).enqueue("revoked", TOKEN, ""));
        assertTrue(new FossPushEventJournal(context).revoked(TOKEN));
        assertTrue(new FossPushEventJournal(context).enqueue("revoked", TOKEN, ""));
        assertTrue(new FossPushEventJournal(context).revoked(TOKEN));
        assertFalse(new FossPushEventJournal(context).revoked("another-token"));
    }
    @Test public void pendingWakeAndOldCancellationSurviveNewStoreInstances() {
        final String uuid = "instrumented-account";
        FossPushStore store = new FossPushStore(context);
        FossPushStore.Record current = new FossPushStore.Record();
        current.accountUuid = uuid;
        current.generation = "new-generation";
        current.phase = "connecting";
        current.enabled = true;
        current.pendingWake = true;
        store.write(current);
        FossPushStore.Record old = new FossPushStore.Record();
        old.accountUuid = uuid + "~old-generation";
        old.generation = "old-generation";
        old.phase = "off";
        old.previousService = "push.example.test";
        old.previousNode = "old-node";
        old.pendingDisable = true;
        old.pendingUnregister = true;
        store.write(old);
        FossPushStore restarted = new FossPushStore(context);
        assertTrue(restarted.read(uuid).pendingWake);
        assertTrue(restarted.read(uuid).enabled);
        assertEquals(1, restarted.cancellationRecords(uuid).size());
        assertEquals("old-generation", restarted.cancellationRecords(uuid).get(0).generation);
    }
    @Test public void staleRegistrationWriteCannotEraseNewWake() {
        FossPushStore first = new FossPushStore(context), second = new FossPushStore(context);
        FossPushStore.Record initial = new FossPushStore.Record();
        initial.accountUuid = "atomic"; initial.generation = "g1"; initial.phase = "connected";
        initial.enabled = true; first.write(initial);
        FossPushStore.Record stale = first.read("atomic");
        FossPushStore.Record wake = second.read("atomic");
        wake.pendingWake = true; second.write(wake);
        stale.phase = "off";
        try { first.write(stale); fail("stale write must not overwrite wake"); }
        catch (IllegalStateException expected) { assertTrue(second.read("atomic").pendingWake); }
    }
    @Test public void multipleOldProvidersRemainAfterRestart() {
        FossPushStore store = new FossPushStore(context);
        for (String generation : new String[] {"a", "b"}) {
            FossPushStore.Record old = new FossPushStore.Record();
            old.accountUuid = "account~" + generation; old.generation = generation;
            old.phase = "off"; old.previousService = generation + ".example.test";
            old.pendingUnregister = true; store.write(old);
        }
        FossPushStore.Record active = new FossPushStore.Record();
        active.accountUuid = "account"; active.generation = "c"; active.phase = "connecting";
        active.enabled = true; store.write(active);
        FossPushStore restarted = new FossPushStore(context);
        assertEquals(2, restarted.cancellationRecords("account").size());
        assertEquals("c", restarted.read("account").generation);
    }
    @Test public void retainedJournalSurvivesAnotherInstance() {
        FossPushEventJournal journal = new FossPushEventJournal(context);
        assertTrue(journal.enqueue("endpoint", TOKEN, "https://example.test/new"));
        assertTrue(new FossPushEventJournal(context).hasPending());
        assertTrue(new FossPushEventJournal(context).enqueue("endpoint", TOKEN, "https://example.test/new"));
        assertTrue(new FossPushEventJournal(context).hasPending());
    }
}
