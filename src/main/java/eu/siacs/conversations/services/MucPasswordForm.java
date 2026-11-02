package eu.siacs.conversations.services;

import android.os.Bundle;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;

/** Standard owner form, preserving every unrelated field/value supplied by the server. */
public final class MucPasswordForm {
    public static final String OWNER = "http://jabber.org/protocol/muc#owner";
    public static final String FORM = "http://jabber.org/protocol/muc#roomconfig";
    public static final String PROTECTED = "muc#roomconfig_passwordprotectedroom";
    public static final String SECRET = "muc#roomconfig_roomsecret";
    public enum State { ENABLED, DISABLED, UNKNOWN }
    private final Data data;
    private final boolean secretOnly;

    public MucPasswordForm(Element form) {
        if (form == null || !"x".equals(form.getName()) || !Namespace.DATA.equals(form.getNamespace())
                || !"form".equals(form.getAttribute("type"))) throw new IllegalArgumentException();
        data = Data.parse(copy(form));
        if (!FORM.equals(data.getFormType()) || count(Data.FORM_TYPE) != 1
                || count(PROTECTED) > 1 || count(SECRET) != 1) {
            throw new IllegalArgumentException("Server does not expose room password configuration");
        }
        final var formType = data.getFieldByName(Data.FORM_TYPE);
        final var protectedField = data.getFieldByName(PROTECTED);
        final var secretField = data.getFieldByName(SECRET);
        secretOnly = protectedField == null;
        if (!dataNamespace(formType) || !dataNamespace(secretField)
                || (protectedField != null && !dataNamespace(protectedField))
                || !"hidden".equals(formType.getType()) || formType.getValues().size() != 1
                || secretField.getValues().size() > 1
                || (secretOnly ? !"text-private".equals(secretField.getType())
                    : secretField.getType() != null && !"text-private".equals(secretField.getType())
                        && !"text-single".equals(secretField.getType()))) {
            throw new IllegalArgumentException("Unsupported room password form");
        }
        for (Element child : secretField.getChildren()) {
            if (("value".equals(child.getName()) && (!dataNamespace(child) || !child.getChildren().isEmpty()))
                    || (secretOnly && "option".equals(child.getName()))) {
                throw new IllegalArgumentException("Unsupported room password value");
            }
        }
        if (!secretOnly && (protectedField.getValues().size() != 1
                || (protectedField.getType() != null && !"boolean".equals(protectedField.getType()))
                || !("1".equals(protectedField.getValue()) || "0".equals(protectedField.getValue())
                    || "true".equals(protectedField.getValue()) || "false".equals(protectedField.getValue())))) {
            throw new IllegalArgumentException("Unsupported room password protection flag");
        }
    }

    private static boolean dataNamespace(Element element) {
        return element.getNamespace() == null || Namespace.DATA.equals(element.getNamespace());
    }

    private int count(String variable) {
        int count = 0;
        for (Element field : data.getChildren()) {
            if ("field".equals(field.getName()) && variable.equals(field.getAttribute("var"))) count++;
        }
        return count;
    }

    public State state() {
        if (!secretOnly) {
            return "1".equals(data.getValue(PROTECTED)) || "true".equals(data.getValue(PROTECTED))
                    ? State.ENABLED : State.DISABLED;
        }
        final String secret = data.getValue(SECRET);
        // An absent or blank value can be redacted. It never proves that protection is off.
        return secret != null && !secret.isEmpty() ? State.ENABLED : State.UNKNOWN;
    }

    /** Only a fresh room disco reply is used; neither cached MucOptions nor local secrets decide. */
    public static State stateFromDisco(Iq reply) {
        if (reply.getType() != Iq.Type.RESULT) return State.UNKNOWN;
        final Element query = reply.findChild("query", Namespace.DISCO_INFO);
        if (query == null) return State.UNKNOWN;
        boolean enabled = false, disabled = false;
        for (Element feature : query.getChildren()) {
            if (!"feature".equals(feature.getName()) || (feature.getNamespace() != null
                    && !Namespace.DISCO_INFO.equals(feature.getNamespace()))) continue;
            enabled |= "muc_passwordprotected".equals(feature.getAttribute("var"));
            disabled |= "muc_unsecured".equals(feature.getAttribute("var"));
        }
        return enabled == disabled ? State.UNKNOWN : enabled ? State.ENABLED : State.DISABLED;
    }

    public static Iq request(Jid room) {
        Iq iq = new Iq(Iq.Type.GET);
        iq.setTo(room.asBareJid());
        iq.query(OWNER);
        return iq;
    }

    public Iq submit(Jid room, boolean enabled, String password) {
        if (enabled && !MucPasswordChange.validPassword(password)) throw new IllegalArgumentException();
        Data submission = Data.parse(copy(data));
        Bundle changes = new Bundle();
        if (!secretOnly) changes.putString(PROTECTED, enabled ? "1" : "0");
        changes.putString(SECRET, enabled ? password : "");
        submission.submit(changes);
        Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(room.asBareJid());
        iq.query(OWNER).addChild(submission);
        return iq;
    }

    private static Element copy(Element source) {
        Element copy = new Element(source.getName());
        copy.getAttributes().putAll(source.getAttributes());
        if (source.getContent() != null) copy.setContent(source.getContent());
        for (Element child : source.getChildren()) copy.addChild(copy(child));
        return copy;
    }
}
