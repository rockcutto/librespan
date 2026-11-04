package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import android.os.Bundle;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import eu.siacs.conversations.ui.util.MucConfiguration;
import android.content.ContextWrapper;
import android.content.res.Resources;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucRoomConfigurationTest {
    static Element form(String members, String publicity, String whois) {
        Data data = new Data();
        data.setAttribute("type", "form"); data.setFormType(MucPasswordForm.FORM);
        data.put(MucRoomConfiguration.MEMBERS, members).setAttribute("type", "boolean");
        data.put(MucRoomConfiguration.PUBLIC, publicity).setAttribute("type", "boolean");
        var field = data.put(MucRoomConfiguration.WHOIS, whois);
        field.setAttribute("type", "list-single");
        for (String option : List.of("anyone", "moderators")) field.addChild("option").addChild("value").setContent(option);
        data.put(MucRoomConfiguration.MODERATED, "true").setAttribute("type", "boolean");
        data.put("vendor#required", List.of("first", "second"));
        data.getFieldByName("vendor#required").setAttribute("type", "list-multi");
        data.put("muc#roomconfig_persistentroom", "0").setAttribute("type", "boolean");
        data.put(MucPasswordForm.PROTECTED, "1").setAttribute("type", "boolean");
        data.put(MucPasswordForm.SECRET, "synthetic room secret").setAttribute("type", "text-private");
        return data;
    }

    @Test public void booleanLexicalFormsAreReadWithoutDiscoDefaults() {
        for (String value : List.of("1", "true", "0", "false")) {
            MucRoomConfiguration model = new MucRoomConfiguration(form(value, value, "moderators"));
            assertTrue(model.supports(MucRoomConfiguration.MEMBERS));
            assertEquals(value.equals("1") || value.equals("true"), model.enabled(MucRoomConfiguration.MEMBERS));
        }
    }

    @Test public void absentFixedInvalidAndDuplicateFieldsAreUnavailable() {
        for (String variation : List.of("absent", "fixed", "invalid", "duplicate")) {
            Element form = form("0", "0", "moderators");
            Element field = form.getChildren().stream().filter(e -> MucRoomConfiguration.PUBLIC.equals(e.getAttribute("var"))).findFirst().get();
            switch (variation) {
                case "absent": form.removeChild(field); break;
                case "fixed": field.setAttribute("type", "fixed"); break;
                case "invalid": field.findChild("value").setContent("unknown"); break;
                default: form.addChild(new eu.siacs.conversations.xmpp.forms.Field(MucRoomConfiguration.PUBLIC));
            }
            assertFalse(variation, new MucRoomConfiguration(form).supports(MucRoomConfiguration.PUBLIC));
        }
    }

    @Test public void submissionPreservesUnknownValuesAndDoesNotMutateInput() {
        Element original = form("0", "0", "moderators");
        String before = original.toString();
        Bundle changes = change(MucRoomConfiguration.MEMBERS, "1");
        Data sent = MucRoomConfiguration.submission(original, changes);
        assertEquals("1", sent.getValue(MucRoomConfiguration.MEMBERS));
        assertEquals("0", sent.getValue(MucRoomConfiguration.PUBLIC));
        assertEquals("0", sent.getValue("muc#roomconfig_persistentroom"));
        assertEquals("moderators", sent.getValue(MucRoomConfiguration.WHOIS));
        assertEquals("true", sent.getValue(MucRoomConfiguration.MODERATED));
        assertEquals(List.of("first", "second"), sent.getFieldByName("vendor#required").getValues());
        assertEquals("synthetic room secret", sent.getValue(MucPasswordForm.SECRET));
        assertEquals(before, original.toString());
    }

    @Test public void ownerIsRequiredAtQueryAndAgainBeforeSet() {
        Fixture f = new Fixture();
        f.options().getSelf().setAffiliation("admin"); f.options().getSelf().setRole("moderator");
        f.controller.query(f.results::add);
        assertEquals(MucRoomConfigurationController.Status.REJECTED, f.last());
        assertTrue(f.service.sent.isEmpty());
        f.options().getSelf().setAffiliation("owner");
        f.controller.save(new MucRoomConfiguration(form("0", "0", "moderators")), change(MucRoomConfiguration.PUBLIC, "1"), f.results::add);
        f.options().getSelf().setAffiliation("admin"); f.reply(0, form("0", "0", "moderators"));
        assertEquals(1, f.service.sent.size()); assertEquals(MucRoomConfigurationController.Status.REJECTED, f.last());
    }

    @Test public void membersAndPublicChangesAreIndependentAndVerifiedAfterSet() {
        for (String key : List.of(MucRoomConfiguration.MEMBERS, MucRoomConfiguration.PUBLIC)) {
            for (String target : List.of("1", "0")) {
                Fixture f = new Fixture();
                String old = target.equals("1") ? "0" : "1";
                Element original = form(old, old, "moderators");
                f.controller.save(new MucRoomConfiguration(original), change(key, target), f.results::add);
                f.reply(0, original);
                assertEquals(Iq.Type.SET, f.service.sent.get(1).getType());
                Data sent = Data.parse(f.service.sent.get(1).findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
                assertEquals(target, sent.getValue(key));
                assertEquals(old, sent.getValue(key.equals(MucRoomConfiguration.MEMBERS) ? MucRoomConfiguration.PUBLIC : MucRoomConfiguration.MEMBERS));
                assertEquals("moderators", sent.getValue(MucRoomConfiguration.WHOIS));
                f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
                assertTrue(f.results.isEmpty());
                f.reply(2, form(key.equals(MucRoomConfiguration.MEMBERS) ? target : old,
                        key.equals(MucRoomConfiguration.PUBLIC) ? target : old, "moderators"));
                assertEquals(MucRoomConfigurationController.Status.SAVED, f.last());
                assertEquals(1, f.service.discoRefreshes);
            }
        }
    }

    @Test public void successfulSetWithoutAppliedValueIsNotSuccess() {
        Fixture f = new Fixture(); f.savePublic(); f.reply(0, form("0", "0", "moderators"));
        f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
        f.reply(2, form("0", "0", "moderators"));
        assertEquals(MucRoomConfigurationController.Status.UNCONFIRMED, f.last());
        assertEquals(0, f.service.discoRefreshes);
    }

    @Test public void errorAndTimeoutAtEveryStageAreFiniteAndRetryable() {
        for (boolean timeout : new boolean[] {false, true}) {
            for (int stage = 0; stage < 3; stage++) {
                Fixture f = new Fixture(); f.savePublic();
                if (stage > 0) f.reply(0, form("0", "0", "moderators"));
                if (stage > 1) f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
                f.service.callbacks.get(stage).accept(timeout ? Iq.TIMEOUT : new Iq(Iq.Type.ERROR));
                assertEquals(timeout ? MucRoomConfigurationController.Status.TIMEOUT : MucRoomConfigurationController.Status.REJECTED, f.last());
                assertEquals(stage + 1, f.service.sent.size());
                f.controller.query(f.results::add);
                assertEquals(stage + 2, f.service.sent.size());
                f.controller.cancel();
            }
        }
    }

    @Test public void cancelResetArchiveAndOfflineRejectStaleCallbacks() {
        for (String mode : List.of("cancel", "reset", "archive", "offline")) {
            Fixture f = new Fixture(); f.savePublic();
            switch (mode) {
                case "cancel": f.controller.cancel(); break;
                case "reset": f.room.resetMucOptions(); break;
                case "archive": f.room.setStatus(Conversation.STATUS_ARCHIVED); break;
                default: f.account.setStatus(Account.State.OFFLINE);
            }
            f.reply(0, form("0", "0", "moderators"));
            assertEquals(1, f.service.sent.size());
            if (mode.equals("cancel")) assertTrue(f.results.isEmpty());
            f.controller.cancel();
        }
    }

    @Test public void duplicateOldCallbacksAndRepeatedSubmitCannotAdvanceAnotherStage() {
        Fixture f = new Fixture(); f.savePublic();
        f.controller.save(new MucRoomConfiguration(form("0", "0", "moderators")), change(MucRoomConfiguration.PUBLIC, "1"), ignored -> {});
        assertEquals(1, f.service.sent.size());
        f.reply(0, form("0", "0", "moderators")); f.reply(0, form("0", "0", "moderators"));
        assertEquals(2, f.service.sent.size());
        f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
        f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
        assertEquals(3, f.service.sent.size());
        f.reply(2, form("0", "1", "moderators")); f.reply(2, form("0", "1", "moderators"));
        assertEquals(1, f.results.size());
    }

    @Test public void conflictAndUnsupportedChangesNeverSendSet() {
        for (boolean unsupported : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.controller.save(new MucRoomConfiguration(form("0", "0", "moderators")),
                    change(unsupported ? "vendor#arbitrary" : MucRoomConfiguration.PUBLIC, "1"), f.results::add);
            f.reply(0, form("0", unsupported ? "0" : "1", "moderators"));
            assertEquals(1, f.service.sent.size());
            assertEquals(unsupported ? MucRoomConfigurationController.Status.UNSUPPORTED : MucRoomConfigurationController.Status.CONFLICT, f.last());
        }
    }

    @Test public void nonAnonymousOrUnknownWhoisBlocksMembersButNotPublicity() {
        for (String whois : List.of("anyone", "unknown", "moderators")) {
            Fixture f = new Fixture();
            if (whois.equals("moderators")) {
                Iq disco = new Iq(Iq.Type.RESULT);
                disco.addChild("query", Namespace.DISCO_INFO).addChild("feature").setAttribute("var", "muc_nonanonymous");
                f.options().updateConfiguration(new eu.siacs.conversations.entities.ServiceDiscoveryResult(disco));
            }
            Element form = form("0", "0", whois);
            assertFalse(new MucRoomConfiguration(form).canChangeMembers(f.options()));
            f.controller.save(new MucRoomConfiguration(form), change(MucRoomConfiguration.MEMBERS, "1"), f.results::add);
            f.reply(0, form);
            assertEquals(MucRoomConfigurationController.Status.UNSUPPORTED, f.last());
            assertEquals(1, f.service.sent.size());
        }
    }

    @Test public void concurrentOwnerUnknownFieldUpdateIsMergedFromFreshForm() {
        Fixture f = new Fixture(); f.savePublic();
        Element fresh = form("0", "0", "moderators");
        Data.parse(fresh).put("vendor#required", List.of("new remote value"));
        f.reply(0, fresh);
        Data sent = Data.parse(f.service.sent.get(1).findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
        assertEquals(List.of("new remote value"), sent.getFieldByName("vendor#required").getValues());
        f.controller.cancel();
    }

    @Test public void queryReloadReturnsActualOwnerFormNotCachedAttributes() {
        Fixture f = new Fixture();
        f.controller.query(f.results::add); f.reply(0, form("true", "false", "moderators"));
        assertTrue(f.results.get(0).configuration.enabled(MucRoomConfiguration.MEMBERS));
        assertFalse(f.options().membersOnly());
        f.controller.query(f.results::add); f.reply(1, form("false", "true", "moderators"));
        assertTrue(f.results.get(1).configuration.enabled(MucRoomConfiguration.PUBLIC));
    }

    @Test public void readOnlyChoicesAndForeignValuesAreNotEditable() {
        Element raw = form("0", "1", "moderators");
        var whois = Data.parse(raw).getFieldByName(MucRoomConfiguration.WHOIS);
        whois.setValue("moderators"); // No advertised alternatives.
        assertFalse(new MucRoomConfiguration(raw).supports(MucRoomConfiguration.WHOIS));
        Data.parse(raw).getFieldByName(MucRoomConfiguration.PUBLIC).findChild("value").setAttribute("xmlns", "urn:foreign");
        assertFalse(new MucRoomConfiguration(raw).supports(MucRoomConfiguration.PUBLIC));
    }

    @Test public void redactedProtectedSecretCannotBeSubmittedByUnrelatedEditor() {
        Fixture f = new Fixture(); f.savePublic();
        Element redacted = form("0", "0", "moderators");
        Data.parse(redacted).put(MucPasswordForm.SECRET, "");
        f.reply(0, redacted);
        assertEquals(MucRoomConfigurationController.Status.UNSUPPORTED, f.last());
        assertEquals(1, f.service.sent.size());
    }

    @Test public void combiningMembersWithNonAnonymousWhoisIsRejected() {
        Fixture f = new Fixture();
        Bundle changes = change(MucRoomConfiguration.MEMBERS, "1");
        changes.putString(MucRoomConfiguration.WHOIS, "anyone");
        f.controller.save(new MucRoomConfiguration(form("0", "0", "moderators")), changes, f.results::add);
        f.reply(0, form("0", "0", "moderators"));
        assertEquals(MucRoomConfigurationController.Status.UNSUPPORTED, f.last());
        assertEquals(1, f.service.sent.size());
    }

    @Test public void actualUiModelSendsOnlyChangedValuesAndPreservesUnexposedFields() {
        var base = RuntimeEnvironment.getApplication().getResources();
        var resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public String getString(int id) { return Integer.toString(id); }
        };
        var context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Resources getResources() { return resources; }
        };
        Fixture f = new Fixture();
        MucConfiguration ui = MucConfiguration.get(context, false, f.options(), new MucRoomConfiguration(form("0", "0", "moderators")));
        boolean[] values = ui.values.clone();
        assertTrue(ui.toBundle(values).isEmpty());
        int index = List.of(ui.names).indexOf(Integer.toString(eu.siacs.conversations.R.string.muc_public_room_label));
        assertTrue(index >= 0); values[index] = true;
        Bundle delta = ui.toBundle(values);
        assertEquals(1, delta.size()); assertEquals("1", delta.getString(MucRoomConfiguration.PUBLIC));
        f.controller.cancel();
    }

    @Test public void existingProsodyInviteFieldIsUsedOnlyIfAdvertised() {
        Element raw = form("0", "0", "moderators");
        Bundle delta = change(MucRoomConfiguration.MEMBER_INVITES, "1");
        assertFalse(new MucRoomConfiguration(raw).supports(MucRoomConfiguration.MEMBER_INVITES));
        Data.parse(raw).put(MucRoomConfiguration.MEMBER_INVITES, "false").setAttribute("type", "boolean");
        assertTrue(new MucRoomConfiguration(raw).supports(MucRoomConfiguration.MEMBER_INVITES));
        Data sent = MucRoomConfiguration.submission(raw, delta);
        assertEquals("1", sent.getValue(MucRoomConfiguration.MEMBER_INVITES));
        assertNull(sent.getValue(MucRoomConfiguration.INVITES));
    }

    @Test public void formSubmissionCannotBypassSupportedFieldAndPasswordRestrictions() {
        for (String key : List.of("vendor#unknown", MucPasswordForm.SECRET, MucPasswordForm.PROTECTED)) {
            try {
                MucRoomConfiguration.submission(form("0", "0", "moderators"), change(key, "1"));
                fail("arbitrary/protected key must not pass the formatter boundary");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void membersOnlyWhoisChangesAreRejectedInBothDirections() {
        for (String from : List.of("anyone", "moderators")) {
            Fixture f = new Fixture();
            cache(f, "members", from);
            Element original = form("1", "0", from);
            f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.WHOIS, opposite(from)), f.results::add);
            f.reply(0, original);
            assertNoSet(f);
        }
    }

    @Test public void confirmedOpenWhoisChangesInBothDirectionsAreSavedAfterReadback() {
        for (String from : List.of("anyone", "moderators")) {
            Fixture f = new Fixture(); cache(f, "open", from);
            String target = opposite(from);
            Element original = form("0", "0", from);
            MucRoomConfiguration model = new MucRoomConfiguration(original);
            f.controller.save(model, change(MucRoomConfiguration.WHOIS, target), f.results::add);
            f.reply(0, original);
            assertEquals(2, f.service.sent.size());
            Data sent = submission(f);
            assertEquals(target, sent.getValue(MucRoomConfiguration.WHOIS));
            assertEquals("0", sent.getValue(MucRoomConfiguration.MEMBERS));
            assertEquals("0", sent.getValue(MucRoomConfiguration.PUBLIC));
            f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
            assertTrue(f.results.isEmpty());
            f.reply(2, form("0", "0", target));
            assertEquals(MucRoomConfigurationController.Status.SAVED, f.last());
            assertEquals(1, f.service.discoRefreshes);
        }
    }

    @Test public void conflictingOwnerAndCachedMembershipRejectsWhois() {
        for (String from : List.of("anyone", "moderators")) {
            for (String server : List.of("0", "1")) {
                Fixture f = new Fixture(); cache(f, server.equals("0") ? "members" : "open", from);
                Element original = form(server, "0", from);
                f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.WHOIS, opposite(from)), f.results::add);
                f.reply(0, original); assertNoSet(f);
            }
        }
    }

    @Test public void unknownOwnerOrCachedMembershipNeverDefaultsToOpenForWhois() {
        for (String state : List.of("missing", "invalid", "fixed", "duplicate", "no-disco", "unknown-cache", "conflicting-cache")) {
            Fixture f = new Fixture();
            if (!state.equals("no-disco")) cache(f, state.equals("unknown-cache") ? "unknown"
                    : state.equals("conflicting-cache") ? "both" : "open", "moderators");
            Element original = form("0", "0", "moderators");
            Element members = original.getChildren().stream()
                    .filter(e -> MucRoomConfiguration.MEMBERS.equals(e.getAttribute("var"))).findFirst().get();
            switch (state) {
                case "missing": original.removeChild(members); break;
                case "invalid": members.findChild("value").setContent("unknown"); break;
                case "fixed": members.setAttribute("type", "fixed"); break;
                case "duplicate": original.addChild(new eu.siacs.conversations.xmpp.forms.Field(MucRoomConfiguration.MEMBERS)); break;
                default: break;
            }
            f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.WHOIS, "anyone"), f.results::add);
            f.reply(0, original); assertNoSet(f);
        }
    }

    @Test public void combinedMembershipAndWhoisDeltaCannotBypassGuard() {
        for (String from : List.of("anyone", "moderators")) {
            for (String originalMembers : List.of("0", "1")) {
                for (String requestedMembers : List.of("0", "1")) {
                    Fixture f = new Fixture(); cache(f, originalMembers.equals("0") ? "open" : "members", from);
                    Element original = form(originalMembers, "0", from);
                    Bundle changes = change(MucRoomConfiguration.WHOIS, opposite(from));
                    changes.putString(MucRoomConfiguration.MEMBERS, requestedMembers);
                    f.controller.save(new MucRoomConfiguration(original), changes, f.results::add);
                    f.reply(0, original); assertNoSet(f);
                }
            }
        }
    }

    @Test public void freshOwnerMembershipIsRecheckedEvenWhenDeltaContainsOnlyWhois() {
        Fixture f = new Fixture(); cache(f, "open", "moderators");
        Element original = form("0", "0", "moderators");
        f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.WHOIS, "anyone"), f.results::add);
        // The changed field itself did not conflict; membership changed since the editor opened.
        f.reply(0, form("1", "0", "moderators"));
        assertNoSet(f);
    }

    @Test public void cacheBecomingMembersOnlyBeforeFreshReplyRejectsWhois() {
        Fixture f = new Fixture(); cache(f, "open", "moderators");
        Element original = form("0", "0", "moderators");
        f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.WHOIS, "anyone"), f.results::add);
        cache(f, "members", "moderators");
        f.reply(0, original); assertNoSet(f);
    }

    @Test public void forbiddenWhoisHasNoRowOrDeltaInActualUiModel() {
        for (String state : List.of("members", "owner-cache-conflict", "missing", "no-disco")) {
            Fixture f = new Fixture();
            if (!state.equals("no-disco")) cache(f, state.equals("owner-cache-conflict") ? "open" : "members", "anyone");
            Element original = form("1", "0", "anyone");
            if (state.equals("missing")) {
                Element field = original.getChildren().stream()
                        .filter(e -> MucRoomConfiguration.MEMBERS.equals(e.getAttribute("var"))).findFirst().get();
                original.removeChild(field);
            }
            MucConfiguration model = ui(f, original);
            assertFalse(List.of(model.names).contains(Integer.toString(eu.siacs.conversations.R.string.muc_permissions_whois_label)));
            boolean[] checked = model.values.clone();
            int publicIndex = List.of(model.names).indexOf(Integer.toString(eu.siacs.conversations.R.string.muc_public_room_label));
            assertTrue(publicIndex >= 0); checked[publicIndex] = true;
            Bundle delta = model.toBundle(checked);
            assertEquals(1, delta.size());
            assertFalse(delta.containsKey(MucRoomConfiguration.WHOIS));
            assertEquals("1", delta.getString(MucRoomConfiguration.PUBLIC));
        }
    }

    @Test public void confirmedOpenWhoisRemainsInActualUiModelAndDelta() {
        for (String from : List.of("anyone", "moderators")) {
            Fixture f = new Fixture(); cache(f, "open", from);
            MucConfiguration model = ui(f, form("0", "0", from));
            int index = List.of(model.names).indexOf(Integer.toString(eu.siacs.conversations.R.string.muc_permissions_whois_label));
            assertTrue(index >= 0);
            boolean[] checked = model.values.clone(); checked[index] = !checked[index];
            Bundle delta = model.toBundle(checked);
            assertEquals(1, delta.size());
            assertEquals(opposite(from), delta.getString(MucRoomConfiguration.WHOIS));
        }
    }

    @Test public void publicOnlyInMembersRoomPreservesUnchangedWhoisAndMembership() {
        Fixture f = new Fixture(); cache(f, "members", "anyone");
        Element original = form("1", "0", "anyone");
        f.controller.save(new MucRoomConfiguration(original), change(MucRoomConfiguration.PUBLIC, "1"), f.results::add);
        f.reply(0, original);
        assertEquals(2, f.service.sent.size());
        assertEquals("anyone", submission(f).getValue(MucRoomConfiguration.WHOIS));
        assertEquals("1", submission(f).getValue(MucRoomConfiguration.MEMBERS));
        f.service.callbacks.get(1).accept(new Iq(Iq.Type.RESULT));
        f.reply(2, form("1", "1", "anyone"));
        assertEquals(MucRoomConfigurationController.Status.SAVED, f.last());
    }

    private static String opposite(String whois) { return whois.equals("anyone") ? "moderators" : "anyone"; }
    private static void assertNoSet(Fixture f) {
        assertEquals("Only the fresh owner GET may be sent", 1, f.service.sent.size());
        assertEquals(Iq.Type.GET, f.service.sent.get(0).getType());
        assertEquals(MucRoomConfigurationController.Status.UNSUPPORTED, f.last());
    }
    private static Data submission(Fixture f) {
        return Data.parse(f.service.sent.get(1).findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
    }
    private static void cache(Fixture f, String membership, String whois) {
        Iq reply = new Iq(Iq.Type.RESULT);
        var query = reply.addChild("query", Namespace.DISCO_INFO);
        if (membership.equals("open") || membership.equals("both")) query.addChild("feature").setAttribute("var", "muc_open");
        if (membership.equals("members") || membership.equals("both")) query.addChild("feature").setAttribute("var", "muc_membersonly");
        if (whois.equals("anyone")) query.addChild("feature").setAttribute("var", "muc_nonanonymous");
        f.options().updateConfiguration(new eu.siacs.conversations.entities.ServiceDiscoveryResult(reply));
    }
    private static MucConfiguration ui(Fixture f, Element form) {
        var base = RuntimeEnvironment.getApplication().getResources();
        var resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public String getString(int id) { return Integer.toString(id); }
        };
        var context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Resources getResources() { return resources; }
        };
        return MucConfiguration.get(context, false, f.options(), new MucRoomConfiguration(form));
    }

    static Bundle change(String key, String value) { Bundle b = new Bundle(); b.putString(key, value); return b; }
    static final class Fixture {
        final Service service = new Service();
        final Account account = new Account(Jid.of("owner@example.test/device"), "");
        final Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final MucRoomConfigurationController controller;
        final List<MucRoomConfigurationController.Result> results = new ArrayList<>();
        Fixture() {
            account.setStatus(Account.State.ONLINE); account.setXmppConnection(new XmppConnection(account, service));
            options().getSelf().setAffiliation("owner"); options().setOnline();
            controller = new MucRoomConfigurationController(service, room);
        }
        MucOptions options() { return room.getMucOptions(); }
        MucRoomConfigurationController.Status last() { return results.get(results.size() - 1).status; }
        void savePublic() { controller.save(new MucRoomConfiguration(form("0", "0", "moderators")), change(MucRoomConfiguration.PUBLIC, "1"), results::add); }
        void reply(int index, Element form) {
            Iq reply = new Iq(Iq.Type.RESULT); reply.query(MucPasswordForm.OWNER).addChild(form);
            service.callbacks.get(index).accept(reply);
        }
    }
    static final class Service extends XmppConnectionService {
        final List<Iq> sent = new ArrayList<>();
        final List<Consumer<Iq>> callbacks = new ArrayList<>();
        int discoRefreshes;
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long timeout) {
            assertEquals(Long.valueOf(10), timeout); sent.add(iq); callbacks.add(callback);
        }
        @Override public void fetchConferenceConfiguration(Conversation conversation) { discoRefreshes++; }
    }
}
