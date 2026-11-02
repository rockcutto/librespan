package eu.siacs.conversations.xml;

/** Do not serialize authentication/configuration secrets into optional or failure stanza logs. */
public final class SecretStanzaLog {
    private SecretStanzaLog() {}

    public static String forLog(Element stanza) {
        return sensitive(stanza) ? "<redacted-password-stanza/>" : stanza.toString();
    }

    private static boolean sensitive(Element element) {
        if ("password".equals(element.getName()) || element.getAttribute("password") != null
                || "muc#roomconfig_roomsecret".equals(element.getAttribute("var"))
                || ("query".equals(element.getName())
                    && "http://jabber.org/protocol/muc#owner".equals(element.getNamespace()))
                || (("iq".equals(element.getName()) || "presence".equals(element.getName())
                    || "message".equals(element.getName())) && "error".equals(element.getAttribute("type")))) {
            return true;
        }
        for (Element child : element.getChildren()) if (sensitive(child)) return true;
        return false;
    }
}
