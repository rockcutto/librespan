package eu.siacs.conversations.http;

import java.util.regex.Pattern;

import okhttp3.HttpUrl;

public final class AesGcmURL {

    /**
     * This matches a 48 or 44 byte IV + KEY hex combo, like used in http/aesgcm upload anchors
     */
    public static final Pattern IV_KEY = Pattern.compile("([A-Fa-f0-9]{2}){48}|([A-Fa-f0-9]{2}){44}");
    private static final Pattern XEP_0454_IV_KEY = Pattern.compile("([A-Fa-f0-9]{2}){44}");

    public static final String PROTOCOL_NAME = "aesgcm";

    private AesGcmURL() {

    }

    public static String toAesGcmUrl(final HttpUrl url) {
        if (!url.isHttps()) {
            throw new IllegalArgumentException(
                    "XEP-0454 requires an HTTPS HTTP Upload download URL");
        }
        final String fragment = url.fragment();
        if (fragment == null || !XEP_0454_IV_KEY.matcher(fragment).matches()) {
            throw new IllegalArgumentException(
                    "XEP-0454 requires a 12-byte IV followed by a 32-byte AES key");
        }
        return PROTOCOL_NAME + url.toString().substring(5);
    }

    public static HttpUrl of(final String url) {
        final int end = url.indexOf("://");
        if (end < 0) {
            throw new IllegalArgumentException("Scheme not found");
        }
        final String protocol = url.substring(0, end);
        if (PROTOCOL_NAME.equals(protocol)) {
            return HttpUrl.get("https" + url.substring(PROTOCOL_NAME.length()));
        } else {
            return HttpUrl.get(url);
        }
    }

}
