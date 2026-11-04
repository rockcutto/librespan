package eu.siacs.conversations.entities;

import static org.junit.Assert.*;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xml.Element;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucPasswordBookmarkBoundaryTest {
    @Test public void runtimePasswordNeverPopulatesSerializableBookmarkAndNullStillClearsIt() {
        Account account = new Account(Jid.of("me@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        Bookmark bookmark = new Bookmark(account, room.getJid()); account.putBookmark(bookmark);
        room.getMucOptions().setPassword("local secret");
        assertEquals("local secret", room.getMucOptions().getPassword());
        assertNull(bookmark.getPassword());
        assertFalse(room.getAttributes().toString().contains("local secret"));
        bookmark.setPassword("imported secret");
        room.getMucOptions().setPassword(null);
        assertNull(bookmark.getPassword());
        assertNull(room.getMucOptions().getPassword());
    }

    @Test public void pendingCandidateFailureUsesPreviousCredentialInExistingPromptAndLockClearsBoth() {
        Account account = new Account(Jid.of("me@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        MucOptions options = room.getMucOptions();
        options.setPassword("candidate"); options.setPasswordFallback("previous");
        assertEquals("candidate", options.getPassword());
        options.setError(MucOptions.Error.PASSWORD_REQUIRED);
        assertEquals("previous", options.getPassword());
        options.setError(MucOptions.Error.PASSWORD_INCORRECT);
        assertEquals("previous", options.getPassword());
        options.setPassword(null);
        assertNull(options.getPassword());
    }

    @Test public void bookmarks2AndLegacyPublicationCannotExposeImportedPassword() {
        Account account = new Account(Jid.of("me@example.test"), "");
        Bookmark bookmark = new Bookmark(account, Jid.of("room@conference.example"));
        bookmark.setPassword("imported secret"); bookmark.setNick("nick"); bookmark.setAutojoin(true);
        Element modern = new IqGenerator(null).publishBookmarkItem(bookmark);
        Element legacy = bookmark.forPublication();
        assertFalse(modern.toString().contains("imported secret"));
        assertFalse(legacy.toString().contains("imported secret"));
        assertNull(modern.findChild("password")); assertNull(legacy.findChild("password"));
        assertEquals("nick", legacy.findChildContent("nick"));
        assertEquals("imported secret", bookmark.getPassword()); // Publication does not destroy read-only input.
    }
}
