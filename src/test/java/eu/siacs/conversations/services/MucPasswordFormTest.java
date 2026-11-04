package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.SecretStanzaLog;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucPasswordFormTest {
    private static final Jid ROOM = Jid.of("room@conference.example");

    @Test public void exactGetUsesBareRoomOwnerQuery() {
        Iq get = MucPasswordForm.request(ROOM.withResource("nick"));
        assertEquals(Iq.Type.GET, get.getType());
        assertEquals(ROOM.toString(), get.getAttribute("to"));
        assertNotNull(get.findChild("query", MucPasswordForm.OWNER));
        assertEquals(1, get.getChildren().size());
        assertTrue(get.findChild("query", MucPasswordForm.OWNER).getChildren().isEmpty());
    }

    @Test public void enableAndReplacePreserveAllUnrelatedServerFieldValues() {
        for (String secretType : new String[] {"text-private", "text-single"}) {
            Data original = form(secretType); // ejabberd/Openfire vs Tigase shape
            String snapshot = original.toString();
            for (String secret : new String[] {"first password", "replacement password"}) {
                Iq set = new MucPasswordForm(original).submit(ROOM, true, secret);
                assertEquals(Iq.Type.SET, set.getType());
                assertEquals(ROOM.toString(), set.getAttribute("to"));
                Data sent = Data.parse(set.findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
                assertEquals("submit", sent.getAttribute("type"));
                assertEquals(MucPasswordForm.FORM, sent.getFormType());
                assertEquals("1", sent.getValue(MucPasswordForm.PROTECTED));
                assertEquals(secret, sent.getValue(MucPasswordForm.SECRET));
                for (eu.siacs.conversations.xmpp.forms.Field field : original.getFields()) {
                    if (!field.getFieldName().equals(MucPasswordForm.PROTECTED) && !field.getFieldName().equals(MucPasswordForm.SECRET)) {
                        assertEquals(field.getValues(), sent.getFieldByName(field.getFieldName()).getValues());
                    }
                }
                assertEquals(snapshot, original.toString());
            }
        }
    }

    @Test public void disablingSubmitsFalseAndExplicitEmptySecret() {
        Data sent = Data.parse(new MucPasswordForm(form("text-private")).submit(ROOM, false, null)
                .findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
        assertEquals("0", sent.getValue(MucPasswordForm.PROTECTED));
        assertNotNull(sent.getFieldByName(MucPasswordForm.SECRET));
        assertEquals(1, sent.getFieldByName(MucPasswordForm.SECRET).getValues().size());
        assertTrue(sent.getValue(MucPasswordForm.SECRET) == null || sent.getValue(MucPasswordForm.SECRET).isEmpty());
    }

    @Test public void prosodySecretOnlyFormIsAcceptedWithoutAddingUnsupportedBoolean() {
        Data prosody = prosodyForm();
        assertEquals(MucPasswordForm.State.UNKNOWN, new MucPasswordForm(prosody).state());
        assertNull(submitted(new MucPasswordForm(prosody).submit(ROOM, true, "new secret"))
                .getFieldByName(MucPasswordForm.PROTECTED));
    }

    @Test public void prosodyEnableAndChangeUseExactSecretAndPreserveEveryOtherField() throws Exception {
        Data original = prosodyForm(); original.put(MucPasswordForm.SECRET, "previous secret");
        String snapshot = original.toString();
        for (String secret : new String[] {"new secret", "  replacement <&> 🚀  "}) {
            Iq iq = new MucPasswordForm(original).submit(ROOM.withResource("nick"), true, secret);
            assertEquals(Iq.Type.SET, iq.getType());
            assertEquals(ROOM.toString(), iq.getAttribute("to"));
            Data sent = submitted(iq);
            assertEquals("submit", sent.getAttribute("type"));
            assertEquals(MucPasswordForm.FORM, sent.getFormType());
            assertEquals("hidden", sent.getFieldByName(Data.FORM_TYPE).getType());
            assertEquals(secret, sent.getValue(MucPasswordForm.SECRET));
            assertEquals(secret, wireValue(iq, MucPasswordForm.SECRET));
            assertNull(wireValue(iq, MucPasswordForm.PROTECTED));
            assertNull(sent.getFieldByName(MucPasswordForm.PROTECTED));
            for (var field : original.getFields()) {
                if (!MucPasswordForm.SECRET.equals(field.getFieldName())) {
                    assertEquals(field.getValues(), sent.getFieldByName(field.getFieldName()).getValues());
                }
            }
            assertEquals(snapshot, original.toString());
        }
    }

    @Test public void prosodyDisableUsesExplicitEmptyValueWithoutBoolean() {
        Data original = prosodyForm(); original.put(MucPasswordForm.SECRET, "previous secret");
        Data sent = submitted(new MucPasswordForm(original).submit(ROOM, false, null));
        assertNull(sent.getFieldByName(MucPasswordForm.PROTECTED));
        assertEquals(1, sent.getFieldByName(MucPasswordForm.SECRET).getValues().size());
        String secret = sent.getValue(MucPasswordForm.SECRET);
        assertTrue(secret == null || secret.isEmpty());
        assertEquals("previous secret", original.getValue(MucPasswordForm.SECRET));
        assertEquals(original.getValue("muc#roomconfig_membersonly"), sent.getValue("muc#roomconfig_membersonly"));
    }

    @Test public void absentOrBlankProsodySecretNeverMeansDisabled() {
        Data missingValue = prosodyForm();
        missingValue.getFieldByName(MucPasswordForm.SECRET).setValues(java.util.Collections.emptyList());
        assertEquals(MucPasswordForm.State.UNKNOWN, new MucPasswordForm(missingValue).state());
        assertEquals(MucPasswordForm.State.UNKNOWN, new MucPasswordForm(prosodyForm()).state());
        Data visibleSecret = prosodyForm(); visibleSecret.put(MucPasswordForm.SECRET, "known server secret");
        assertEquals(MucPasswordForm.State.ENABLED, new MucPasswordForm(visibleSecret).state());
        assertEquals(MucPasswordForm.State.ENABLED, MucPasswordForm.stateFromDisco(disco("muc_passwordprotected")));
        assertEquals(MucPasswordForm.State.DISABLED, MucPasswordForm.stateFromDisco(disco("muc_unsecured")));
    }

    @Test public void unavailableOrContradictoryDiscoNeverMeansDisabledOrSuccessfulMutation() {
        assertEquals(MucPasswordForm.State.UNKNOWN, MucPasswordForm.stateFromDisco(disco()));
        assertEquals(MucPasswordForm.State.UNKNOWN,
                MucPasswordForm.stateFromDisco(disco("muc_passwordprotected", "muc_unsecured")));
        assertEquals(MucPasswordForm.State.UNKNOWN, MucPasswordForm.stateFromDisco(new Iq(Iq.Type.TIMEOUT)));
        Iq error = new Iq(Iq.Type.ERROR);
        error.query(Namespace.DISCO_INFO).addChild("feature").setAttribute("var", "muc_unsecured");
        error.addChild("error").addChild("forbidden", "urn:ietf:params:xml:ns:xmpp-stanzas");
        assertEquals(MucPasswordForm.State.UNKNOWN, MucPasswordForm.stateFromDisco(error));
        assertEquals(MucPasswordForm.State.UNKNOWN, MucPasswordForm.stateFromDisco(new Iq(Iq.Type.RESULT)));
        Iq foreignFeature = disco();
        foreignFeature.query(Namespace.DISCO_INFO).addChild("feature", "urn:wrong").setAttribute("var", "muc_unsecured");
        assertEquals(MucPasswordForm.State.UNKNOWN, MucPasswordForm.stateFromDisco(foreignFeature));
    }

    @Test public void twoFieldFormsStillUseAuthoritativeBooleanEvenWhenSecretIsHidden() {
        for (String type : new String[] {"text-private", "text-single"}) {
            Data twoFields = form(type);
            twoFields.getFieldByName(MucPasswordForm.SECRET).setValues(java.util.Collections.emptyList());
            assertEquals(MucPasswordForm.State.DISABLED, new MucPasswordForm(twoFields).state());
            twoFields.put(MucPasswordForm.PROTECTED, "true");
            assertEquals(MucPasswordForm.State.ENABLED, new MucPasswordForm(twoFields).state());
        }
    }

    @Test public void malformedOrUnknownSecretOnlyFormsAreRejected() {
        Data wrongType = prosodyForm(); wrongType.getFieldByName(MucPasswordForm.SECRET).setAttribute("type", "text-single");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongType));
        Data missingType = prosodyForm(); missingType.getFieldByName(MucPasswordForm.SECRET).getAttributes().remove("type");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(missingType));
        Data multiple = prosodyForm(); multiple.put(MucPasswordForm.SECRET, java.util.Arrays.asList("one", "two"));
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(multiple));
        Data duplicate = prosodyForm(); duplicate.addChild("field").setAttribute("var", MucPasswordForm.SECRET);
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(duplicate));
        Data nested = prosodyForm(); nested.getFieldByName(MucPasswordForm.SECRET).findChild("value").addChild("nested");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(nested));
        Data foreign = prosodyForm(); foreign.getFieldByName(MucPasswordForm.SECRET).setAttribute("xmlns", "urn:wrong");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(foreign));
        Data foreignValue = prosodyForm(); foreignValue.getFieldByName(MucPasswordForm.SECRET).findChild("value").setAttribute("xmlns", "urn:wrong");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(foreignValue));
        Data listOption = prosodyForm(); listOption.getFieldByName(MucPasswordForm.SECRET).addChild("option").addChild("value").setContent("unexpected");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(listOption));
        Data unknown = new Data(); unknown.setAttribute("type", "form"); unknown.setFormType(MucPasswordForm.FORM);
        unknown.put("unrelated", "value");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(unknown));
        Data wrongForm = prosodyForm(); wrongForm.setFormType("urn:another-form");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongForm));
        Element wrongNamespace = new Element("x", "urn:not-data"); wrongNamespace.setAttribute("type", "form");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongNamespace));
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(prosodyForm()).submit(ROOM, true, ""));
    }

    private static String wireValue(Iq iq, String variable) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                iq.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var fields = xml.getElementsByTagNameNS(Namespace.DATA, "field");
        for (int i = 0; i < fields.getLength(); i++) {
            var field = (org.w3c.dom.Element) fields.item(i);
            if (variable.equals(field.getAttribute("var"))) {
                return field.getElementsByTagNameNS(Namespace.DATA, "value").item(0).getTextContent();
            }
        }
        return null;
    }

    private static Data submitted(Iq iq) {
        return Data.parse(iq.findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
    }

    private static Data prosodyForm() {
        Data form = form("text-private");
        form.removeChild(form.getChildren().stream()
                .filter(child -> MucPasswordForm.PROTECTED.equals(child.getAttribute("var"))).findFirst().get());
        return form;
    }

    private static Iq disco(String... features) {
        Iq reply = new Iq(Iq.Type.RESULT);
        Element query = reply.query(Namespace.DISCO_INFO);
        for (String feature : features) query.addChild("feature").setAttribute("var", feature);
        return reply;
    }

    @Test public void missingSecretOrWrongFormTypeAndDuplicateFieldsAreRejected() {
        Data missing = form("text-private");
        missing.removeChild(missing.getChildren().stream()
                .filter(child -> MucPasswordForm.SECRET.equals(child.getAttribute("var"))).findFirst().get());
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(missing));
        Data wrong = form("text-private"); wrong.setFormType("urn:unexpected");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrong));
        Data duplicate = form("text-private");
        duplicate.addChild("field").setAttribute("var", MucPasswordForm.PROTECTED).addChild("value").setContent("0");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(duplicate));
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(null));
    }

    @Test public void malformedBooleanAndMultiValueSecretCannotAuthorizeSubmission() {
        Data wrongBoolean = form("text-private");
        wrongBoolean.put(MucPasswordForm.PROTECTED, "yes");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongBoolean));
        Data multipleSecrets = form("text-private");
        multipleSecrets.put(MucPasswordForm.SECRET, java.util.Arrays.asList("one", "two"));
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(multipleSecrets));
        Data wrongFormType = form("text-private");
        wrongFormType.getFieldByName(Data.FORM_TYPE).setAttribute("type", "text-single");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongFormType));
        Data wrongType = form("list-multi");
        assertThrows(IllegalArgumentException.class, () -> new MucPasswordForm(wrongType));
    }

    @Test public void emptyEnableCannotGenerateSetAndOpaqueSpacesAreUnchanged() {
        MucPasswordForm form = new MucPasswordForm(form("text-private"));
        assertThrows(IllegalArgumentException.class, () -> form.submit(ROOM, true, ""));
        Iq set = form.submit(ROOM, true, "  exact  ");
        Data sent = Data.parse(set.findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
        assertEquals("  exact  ", sent.getValue(MucPasswordForm.SECRET));
    }

    @Test public void logsRedactOwnerFormsJoinPasswordsAndDirectInvitePasswords() {
        Iq set = new MucPasswordForm(form("text-private")).submit(ROOM, true, "log-secret");
        assertFalse(SecretStanzaLog.forLog(set).contains("log-secret"));
        Element presence = new Element("presence");
        presence.addChild("x", "http://jabber.org/protocol/muc").addChild("password").setContent("join-secret");
        assertFalse(SecretStanzaLog.forLog(presence).contains("join-secret"));
        Element invite = new Element("message");
        invite.addChild("x", "jabber:x:conference").setAttribute("password", "invite-secret");
        assertFalse(SecretStanzaLog.forLog(invite).contains("invite-secret"));
        Element error = new Element("presence"); error.setAttribute("type", "error");
        error.addChild("error").addChild("text").setContent("server echoed secret");
        assertFalse(SecretStanzaLog.forLog(error).contains("server echoed secret"));
        Element plain = new Element("presence");
        assertEquals(plain.toString(), SecretStanzaLog.forLog(plain));
    }

    static Data form(String secretType) {
        Data form = new Data(); form.setAttribute("type", "form"); form.setFormType(MucPasswordForm.FORM);
        form.put(MucPasswordForm.PROTECTED, "0").setAttribute("type", "boolean");
        form.put(MucPasswordForm.SECRET, "").setAttribute("type", secretType);
        form.put("muc#roomconfig_membersonly", "1");
        form.put("muc#roomconfig_publicroom", "0");
        form.put("muc#roomconfig_whois", "moderators");
        form.put("muc#roomconfig_moderatedroom", "1");
        form.put("muc#roomconfig_persistentroom", "1");
        form.put("muc#roomconfig_allowinvites", "0");
        form.put("muc#roomconfig_mam", "1");
        form.put("server#unknown-list", java.util.Arrays.asList("alpha", "beta"));
        form.put("server#unknown-token", "opaque");
        return form;
    }
}
