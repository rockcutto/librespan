package eu.siacs.conversations.xmpp;

import com.google.common.io.BaseEncoding;
import eu.siacs.conversations.xml.Element;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;

/** The gateway registration protocol is separate from standard XEP-0357 enable/disable. */
public final class PushGatewayProtocol {

    public static final String NAMESPACE = "urn:librespan:push:registration:1";

    private PushGatewayProtocol() {}

    public static Jid serviceJid(final String input) {
        if (input == null || !input.equals(input.trim()) || input.isEmpty()) {
            throw new IllegalArgumentException("A push service JID is required");
        }
        final Jid jid = Jid.ofUserInput(input);
        if (jid.getResource() != null || !jid.equals(jid.asBareJid())) {
            throw new IllegalArgumentException("A bare push service JID is required");
        }
        return jid;
    }

    public static Iq register(
            final Jid service,
            final String appPackage,
            final String endpoint,
            final String p256dh,
            final String auth) {
        requireService(service);
        if (appPackage == null || !appPackage.matches("[a-zA-Z][a-zA-Z0-9_.]{1,127}")) {
            throw new IllegalArgumentException("Invalid application ID");
        }
        requireEndpoint(endpoint);
        requireKey(p256dh, 65, true);
        requireKey(auth, 16, false);
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(service);
        final Element registration = iq.addChild("register", NAMESPACE);
        registration.setAttribute("app", appPackage);
        registration.addChild("endpoint", NAMESPACE).setContent(endpoint);
        registration.addChild("p256dh", NAMESPACE).setContent(p256dh);
        registration.addChild("auth", NAMESPACE).setContent(auth);
        return iq;
    }

    public static Iq unregister(final Jid service, final String appPackage, final String node) {
        requireService(service);
        if (appPackage == null || appPackage.isEmpty() || !validNode(node)) {
            throw new IllegalArgumentException("Invalid gateway registration");
        }
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(service);
        final Element command = iq.addChild("unregister", NAMESPACE);
        command.setAttribute("app", appPackage);
        command.setAttribute("node", node);
        return iq;
    }

    /** Revoke a registration whose IQ reply (and thus node) was lost. Requires the same XMPP JID. */
    public static Iq unregisterEndpoint(final Jid service, final String appPackage,
            final String endpoint) {
        requireService(service);
        if (appPackage == null || appPackage.isEmpty()) {
            throw new IllegalArgumentException("Invalid application ID");
        }
        requireEndpoint(endpoint);
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(service);
        final Element command = iq.addChild("unregister", NAMESPACE);
        command.setAttribute("app", appPackage);
        command.setAttribute("endpoint", endpoint);
        return iq;
    }

    /** An endpoint alone is never success: only a matching server IQ result is accepted. */
    public static Registration confirmedRegistration(
            final Iq response, final Jid service, final String requestId) {
        requireService(service);
        if (response == null
                || response.getType() != Iq.Type.RESULT
                || requestId == null
                || !requestId.equals(response.getId())
                || !service.equals(response.getFrom())
                || response.getChildren().size() != 1) {
            return null;
        }
        final Element registered = response.findChild("registered", NAMESPACE);
        if (registered == null || !registered.getChildren().isEmpty()) {
            return null;
        }
        final String node = registered.getAttribute("node");
        final String secret = registered.getAttribute("secret");
        long expiresAtMillis = 0;
        final String expires = registered.getAttribute("expires");
        if (expires != null) {
            try {
                long seconds = Long.parseLong(expires);
                if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) return null;
                expiresAtMillis = seconds * 1000;
            } catch (NumberFormatException invalid) { return null; }
        }
        return validNode(node) && secret != null && !secret.isEmpty()
                ? new Registration(node, secret, expiresAtMillis) : null;
    }

    public static boolean confirmedUnregistration(
            final Iq response, final Jid service, final String requestId) {
        requireService(service);
        return response != null
                && response.getType() == Iq.Type.RESULT
                && requestId != null
                && requestId.equals(response.getId())
                && service.equals(response.getFrom())
                && response.getChildren().size() == 1
                && response.findChild("unregistered", NAMESPACE) != null;
    }

    private static void requireService(final Jid service) {
        if (service == null || service.getResource() != null) {
            throw new IllegalArgumentException("A bare push service JID is required");
        }
    }

    private static boolean validNode(final String node) {
        return node != null && node.length() >= 16 && node.length() <= 200;
    }

    private static void requireEndpoint(final String endpoint) {
        if (endpoint == null || endpoint.length() > 1000) {
            throw new IllegalArgumentException("Invalid endpoint");
        }
        try {
            final URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || !endpoint.equals(endpoint.trim())) {
                throw new IllegalArgumentException("Invalid endpoint");
            }
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint", e);
        }
    }

    private static void requireKey(final String key, final int length, final boolean publicKey) {
        if (key == null || !key.matches("[A-Za-z0-9_-]+={0,2}")) {
            throw new IllegalArgumentException("Invalid Web Push key");
        }
        try {
            final byte[] bytes = BaseEncoding.base64Url().decode(key);
            if (bytes.length != length || (publicKey && bytes[0] != 4)) {
                throw new IllegalArgumentException("Invalid Web Push key");
            }
            if (publicKey) {
                WebPushCrypto.decodePublicKey(bytes);
            }
        } catch (final GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Web Push key", e);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Web Push key", e);
        }
    }

    public static final class Registration {
        public final String node;
        public final String secret;
        public final long expiresAtMillis;

        private Registration(final String node, final String secret, final long expiresAtMillis) {
            this.node = node;
            this.secret = secret;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
