package eu.siacs.conversations.persistance;

import java.net.URI;

/** The only body accepted by outgoing attachment persistence: a locator and numeric file params. */
final class AttachmentUploadBody {
    private AttachmentUploadBody() {}

    static boolean isPending(final String body) {
        return isCanonical(body, "");
    }

    static boolean isComplete(final String body, final String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            final URI parsed = URI.create(url);
            final String scheme = parsed.getScheme();
            if (!("https".equals(scheme) || "http".equals(scheme) || "aesgcm".equals(scheme))
                    || parsed.getHost() == null || parsed.getUserInfo() != null) {
                return false;
            }
            if ("aesgcm".equals(scheme)
                    && (parsed.getRawFragment() == null
                            || !parsed.getRawFragment().matches("[A-Fa-f0-9]{88}"))) {
                return false;
            }
            return isCanonical(body, url);
        } catch (final IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean isCanonical(final String body, final String locator) {
        if (body == null) {
            return false;
        }
        final String[] parts = body.split("\\|", -1);
        if ((parts.length != 2 && parts.length != 4 && parts.length != 5)
                || !locator.equals(parts[0])) {
            return false;
        }
        try {
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]*")) {
                    return false;
                }
                final long value = Long.parseLong(parts[i]);
                if (i > 1 && value > Integer.MAX_VALUE) {
                    return false;
                }
            }
            return true;
        } catch (final NumberFormatException error) {
            return false;
        }
    }
}
