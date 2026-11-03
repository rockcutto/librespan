package eu.siacs.conversations.services;

import android.os.Bundle;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.forms.Data;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** A small allowlist of owner settings, never disco-derived defaults or a generic form editor. */
public final class MucRoomConfiguration {
    public static final String MEMBERS = "muc#roomconfig_membersonly";
    public static final String PUBLIC = "muc#roomconfig_publicroom";
    public static final String WHOIS = "muc#roomconfig_whois";
    public static final String SUBJECT = "muc#roomconfig_changesubject";
    public static final String INVITES = "muc#roomconfig_allowinvites";
    // Existing LibreSpan compatibility key; offered only when actually present in owner form.
    public static final String MEMBER_INVITES = "{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites";
    public static final String MODERATED = "muc#roomconfig_moderatedroom";
    public static final String PM = "muc#roomconfig_allowpm";
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> choices = new HashSet<>();

    public MucRoomConfiguration(final Element form) {
        if (form == null || !"form".equals(form.getAttribute("type"))
                || !Namespace.DATA.equals(form.getNamespace()) || !"x".equals(form.getName())) {
            throw new IllegalArgumentException("Missing owner form");
        }
        final Data data = Data.parse(form);
        if (count(form, Data.FORM_TYPE) != 1
                || !MucPasswordForm.FORM.equals(data.getFormType())
                || !"hidden".equals(data.getFieldByName(Data.FORM_TYPE).getType())
                || data.getFieldByName(Data.FORM_TYPE).getValues().size() != 1
                || !validValues(data.getFieldByName(Data.FORM_TYPE))) {
            throw new IllegalArgumentException("Invalid owner form type");
        }
        for (String name : new String[] {MEMBERS, PUBLIC, WHOIS, SUBJECT, INVITES, MEMBER_INVITES, MODERATED, PM}) {
            final var field = data.getFieldByName(name);
            if (field == null || count(form, name) != 1 || field.getValues().size() != 1
                    || !validValues(field)) continue;
            final boolean list = WHOIS.equals(name) || PM.equals(name);
            final String type = field.getType();
            if (type != null && !(list ? "list-single" : "boolean").equals(type)) continue;
            final String value = field.getValue();
            if (list) {
                if ("anyone".equals(value) || "moderators".equals(value)
                        || (PM.equals(name) && "participants".equals(value))) {
                    values.put(name, value);
                    boolean on = false, off = false;
                    for (Element option : field.getChildren()) {
                        if (!"option".equals(option.getName())) continue;
                        on |= "anyone".equals(option.findChildContent("value"));
                        off |= "moderators".equals(option.findChildContent("value"));
                    }
                    if (on && off) choices.add(name);
                }
            } else if ("1".equals(value) || "true".equals(value)) values.put(name, "1");
            else if ("0".equals(value) || "false".equals(value)) values.put(name, "0");
        }
    }

    private static boolean validValues(Element field) {
        if (field.getNamespace() != null && !Namespace.DATA.equals(field.getNamespace())) return false;
        for (Element child : field.getChildren()) {
            if ("value".equals(child.getName()) && (!child.getChildren().isEmpty()
                    || (child.getNamespace() != null && !Namespace.DATA.equals(child.getNamespace())))) return false;
        }
        return true;
    }

    private static int count(Element form, String name) {
        int count = 0;
        for (Element child : form.getChildren()) {
            if ("field".equals(child.getName()) && name.equals(child.getAttribute("var"))) count++;
        }
        return count;
    }

    public String value(String name) { return values.get(name); }
    public boolean supports(String name) {
        return values.containsKey(name) && (!(WHOIS.equals(name) || PM.equals(name)) || choices.contains(name));
    }
    public boolean enabled(String name) {
        String value = value(name);
        return "1".equals(value) || "anyone".equals(value) || "participants".equals(value);
    }

    /** Non-anonymous rooms require a separate classification/OMEMO policy change first. */
    public boolean canChangeMembers(MucOptions options) {
        return supports(MEMBERS) && "moderators".equals(value(WHOIS)) && !options.nonanonymous();
    }

    /** Changing WHOIS must not switch the private/non-anonymous classification. */
    public boolean canChangeWhois(MucOptions options) {
        return supports(WHOIS) && supports(MEMBERS) && "0".equals(value(MEMBERS))
                && !options.membersOnly() && options.hasFeature("muc_open")
                && !options.hasFeature("muc_membersonly");
    }

    public boolean matches(MucRoomConfiguration other, Bundle changes) {
        for (String name : changes.keySet()) {
            if (!Objects.equals(value(name), other.value(name))) return false;
        }
        return true;
    }

    public boolean verifies(Bundle changes) {
        for (String name : changes.keySet()) {
            if (!Objects.equals(value(name), changes.getString(name))) return false;
        }
        return true;
    }

    public boolean permits(Bundle changes, MucOptions options) {
        if (changes.containsKey(WHOIS)
                && (!canChangeWhois(options) || changes.containsKey(MEMBERS))) return false;
        if (changes.containsKey(MEMBERS) && (!canChangeMembers(options)
                || "anyone".equals(changes.getString(WHOIS)))) return false;
        return validChanges(changes);
    }

    private boolean validChanges(Bundle changes) {
        for (String name : changes.keySet()) {
            final String value = changes.getString(name);
            if (!supports(name)) return false;
            if (WHOIS.equals(name) || PM.equals(name)) {
                if (!"anyone".equals(value) && !"moderators".equals(value)) return false;
            } else if (!"1".equals(value) && !"0".equals(value)) return false;
        }
        return true;
    }

    public static Data submission(Element freshForm, Bundle changes) {
        if (!new MucRoomConfiguration(freshForm).validChanges(changes)) {
            throw new IllegalArgumentException("Unsupported owner configuration change");
        }
        final Data data = Data.parse(copy(freshForm));
        if (("1".equals(data.getValue(MucPasswordForm.PROTECTED))
                        || "true".equals(data.getValue(MucPasswordForm.PROTECTED)))
                && (data.getValue(MucPasswordForm.SECRET) == null
                        || data.getValue(MucPasswordForm.SECRET).isEmpty())) {
            throw new IllegalArgumentException("Cannot preserve an undisclosed room secret");
        }
        data.submit(changes); // Existing form/value preservation, including unknown required fields.
        return data;
    }

    private static Element copy(Element source) {
        Element copy = new Element(source.getName());
        copy.getAttributes().putAll(source.getAttributes());
        if (source.getContent() != null) copy.setContent(source.getContent());
        for (Element child : source.getChildren()) copy.addChild(copy(child));
        return copy;
    }
}
