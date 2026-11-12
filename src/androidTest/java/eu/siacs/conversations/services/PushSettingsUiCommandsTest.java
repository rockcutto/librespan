package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import org.junit.Test;

/** Exercises the same explicit Apply command used by the Activity, with observed side effects. */
public class PushSettingsUiCommandsTest {
    private static final String KEY="BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static class Operations implements PushSettingsUiCommands.Operations {
        FossPushStore.Record record=new FossPushStore.Record();
        int saves, connects;
        public PushSettingsConfiguration.Snapshot read(){return new PushSettingsConfiguration.Snapshot(record);}
        public boolean save(String g,String k,String m,String p){saves++;return true;}
        public boolean connect(String g,String k,String m,String p){connects++;record.enabled=true;
            record.service=g;record.vapid=k;record.mode=m;record.distributor=p;return true;}
    }
    @Test public void firstApplySavesAndConnectsExactlyOnce() {
        Operations operations=new Operations(); PushSettingsUiCommands command=new PushSettingsUiCommands();
        assertTrue(command.apply(operations,"push.example.org",KEY,"embedded",""));
        assertEquals(1,operations.saves);assertEquals(1,operations.connects);
        assertTrue(command.apply(operations,"push.example.org",KEY,"embedded",""));
        assertEquals(1,operations.saves);assertEquals(1,operations.connects);
    }
    @Test public void pendingCancellationAndInvalidFieldsNeverConnect() {
        Operations operations=new Operations(); PushSettingsUiCommands command=new PushSettingsUiCommands();
        assertFalse(command.apply(operations,"invalid/resource",KEY,"embedded",""));
        operations.record.pendingDisable=true;
        assertFalse(command.apply(operations,"push.example.org",KEY,"embedded",""));
        assertEquals(0,operations.saves);assertEquals(0,operations.connects);
    }
    @Test public void activeChangedConfigurationConnectsWithoutWritingActiveRecord() {
        Operations operations=new Operations();operations.record.enabled=true;
        assertTrue(new PushSettingsUiCommands().apply(operations,"push.example.org",KEY,"external","org.example.delivery"));
        assertEquals(0,operations.saves);assertEquals(1,operations.connects);
    }
    @Test public void nestedClickCannotLaunchParallelCommand() {
        PushSettingsUiCommands command=new PushSettingsUiCommands();
        Operations operations=new Operations(){
            @Override public boolean connect(String g,String k,String m,String p){
                assertFalse(command.apply(this,g,k,m,p));return super.connect(g,k,m,p);
            }
        };
        assertTrue(command.apply(operations,"push.example.org",KEY,"embedded",""));
        assertEquals(1,operations.connects);
    }
    @Test public void manualRetryIsExplicitAndDoubleClickIsIgnored() {
        PushSettingsUiCommands commands=new PushSettingsUiCommands();
        int[] calls={0};Runnable retry=()->calls[0]++;
        assertEquals(0,calls[0]);
        assertEquals(PushSettingsUiCommands.RetryResult.REQUESTED,commands.retry(retry,1000));
        assertEquals(PushSettingsUiCommands.RetryResult.IGNORED,commands.retry(retry,1001));
        assertEquals(1,calls[0]);
    }
    @Test public void retryRuntimeExceptionIsReportedAndDoesNotKeepCommandBusy() {
        PushSettingsUiCommands commands=new PushSettingsUiCommands();
        assertEquals(PushSettingsUiCommands.RetryResult.FAILED,
            commands.retry(()->{throw new IllegalStateException("fixture");},1000));
        int[] calls={0};
        assertEquals(PushSettingsUiCommands.RetryResult.REQUESTED,commands.retry(()->calls[0]++,3000));
        assertEquals(1,calls[0]);
    }
    @Test public void revokedSubscriptionUsesSavedConfigurationWithConnect() {
        Operations operations=new Operations();
        operations.record.enabled=false;operations.record.phase="error";
        operations.record.failureReason="UNREGISTERED";
        operations.record.service="push.example.org";operations.record.vapid=KEY;
        operations.record.mode="embedded";
        PushSettingsConfiguration.Snapshot saved=operations.read();
        assertEquals("UNREGISTERED",saved.lastFailure);
        assertTrue(new PushSettingsUiCommands().apply(operations,saved.gateway,saved.vapid,saved.mode,saved.distributor));
        assertEquals(1,operations.connects);
    }
    @Test public void criticalErrorsAreNotHiddenByUiGuard() {
        try {
            new PushSettingsUiCommands().retry(()->{throw new AssertionError("fixture");},1000);
            fail("critical errors must propagate");
        }catch(AssertionError expected){assertEquals("fixture",expected.getMessage());}
    }

    @Test public void revokedReasonSurvivesDisabledRecordButExplicitOffIsNotRevoked() {
        Operations operations=new Operations();operations.record.enabled=false;
        operations.record.phase="error";operations.record.failureReason="UNREGISTERED";
        assertTrue(operations.read().isRevoked());
        operations.record.phase="off";
        assertFalse(operations.read().isRevoked());
        operations.record.phase="error";operations.record.failureReason="";
        assertFalse(operations.read().isRevoked());
    }

    @Test public void unchangedEditorValuesAreNotUnappliedEditsAndNeverReconnect() {
        Operations operations=new Operations();operations.record.enabled=true;
        operations.record.service="push.example.org";operations.record.vapid=KEY;
        operations.record.mode="embedded";operations.record.distributor="";
        PushSettingsConfiguration.Snapshot saved=operations.read();
        assertTrue(saved.matches("push.example.org",KEY,"embedded",""));
        assertFalse(saved.matches("changed.example.org",KEY,"embedded",""));
        assertFalse(saved.matches("push.example.org",KEY,"external","org.example.delivery"));
        assertTrue(new PushSettingsUiCommands().apply(operations,"push.example.org",KEY,"embedded",""));
        assertEquals(0,operations.saves);assertEquals(0,operations.connects);
    }

}
