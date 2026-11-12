package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;

public class PushSettingsConfigurationTest {
    private Context context;
    private static final String KEY="BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    @Before public void context() {
        String prefix=java.util.UUID.randomUUID().toString();
        context=new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
        };
    }
    @Test public void saveReadAndReadAgainDoNotEnableOrGenerateSubscription() {
        assertTrue(PushSettingsConfiguration.save(context,"a","push.example.org",KEY,"embedded",""));
        FossPushStore store=new FossPushStore(context);
        FossPushStore.Record before=store.read("a");
        assertFalse(before.enabled); assertEquals("",before.generation); assertEquals("",before.privateKey);
        assertEquals(KEY,PushSettingsConfiguration.read(context,"a").vapid);
        assertEquals(before.revision,store.read("a").revision);
        assertTrue(PushSettingsConfiguration.save(context,"a","push.example.org",KEY,"embedded",""));
        assertEquals(before.revision,store.read("a").revision);
    }
    @Test public void accountsRemainIndependentAndInvalidInputDoesNotOverwrite() {
        assertTrue(PushSettingsConfiguration.save(context,"a","push.example.org",KEY,"embedded",""));
        assertTrue(PushSettingsConfiguration.save(context,"b","other.example.org",KEY,"external","org.example.delivery"));
        assertFalse(PushSettingsConfiguration.save(context,"a","https://invalid",KEY,"embedded",""));
        assertEquals("push.example.org",PushSettingsConfiguration.read(context,"a").gateway);
        assertEquals("org.example.delivery",PushSettingsConfiguration.read(context,"b").distributor);
    }
    @Test public void activeConfigurationCannotBeEditedBySave() {
        assertTrue(PushSettingsConfiguration.save(context,"a","push.example.org",KEY,"embedded",""));
        FossPushStore store=new FossPushStore(context); FossPushStore.Record r=store.read("a");
        r.enabled=true; r.generation="active"; store.write(r);
        assertFalse(PushSettingsConfiguration.save(context,"a","other.example.org",KEY,"embedded",""));
        assertEquals("active",store.read("a").generation);
        assertEquals("push.example.org",store.read("a").service);
    }
}
