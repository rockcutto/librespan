package eu.siacs.conversations.ui.preview;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/** No network I/O. Conservative public-unicast policy, including mapped IPv4. */
public final class JpegPolicy {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Pattern LINKS = Pattern.compile("(?i)https?://[^\\s<>\"']+");
    private JpegPolicy() {}
    /** Only verified presentation text, under a current access capability; never a DB fallback. */
    public static HttpUrl firstReadable(eu.siacs.conversations.entities.Message message,JpegLoader.Permit permit) {
        if(permit==null || !permit.isValid()) return null;
        final String text;
        // Do not nest the loader monitor with a Message monitor. Retirement may originate in
        // service/storage code; validate the permit again after taking the text snapshot.
        synchronized(message) {
            if(message.hasProtectedTextPayload()) {
                text=message.hasVerifiedProtectedBody() ? message.getVerifiedProtectedBodyOrNull() : null;
            } else {
                text=message.getBody();
            }
        }
        if(!permit.isValid()) return null;
        HttpUrl url=first(text);
        return permit.isValid() ? url : null;
    }

    public static HttpUrl first(String text) {
        if (text == null) return null;
        var matcher = LINKS.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group().replaceAll("[.,;!?)\\]]+$", "");
            HttpUrl url = HttpUrl.parse(raw);
            if (eligible(url)) return url;
        }
        return null;
    }
    public static boolean eligible(HttpUrl url) {
        if (!safeUrl(url)) return false;
        String path = url.encodedPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".jpg") || path.endsWith(".jpeg");
    }
    public static boolean safeUrl(HttpUrl u) {
        return u != null && u.username().isEmpty() && u.password().isEmpty()
                && u.query() == null && u.fragment() == null
                && u.port() == (u.isHttps() ? 443 : 80)
                && !u.host().endsWith(".onion") && !u.host().endsWith(".local")
                && !u.host().equals("localhost");
    }
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 16) {
            // Global unicast only; exclude transition, protocol, documentation and reserved ranges.
            if ((b[0] & 0xe0) != 0x20) return false;
            if ((b[0]&255)==0x20 && (b[1]&255)==2) return false; // 6to4
            if ((b[0]&255)==0x20 && (b[1]&255)==1
                    && (((b[2]&255)<2) || ((b[2]&255)==0x0d && (b[3]&255)==0xb8))) return false;
            return !((b[0]&255)==0x3f && (b[1]&255)==0xff);
        }
        if (b.length != 4) return false;
        int a=b[0]&255, c=b[1]&255, d=b[2]&255;
        return !(a==0 || a==10 || a==127 || a>=224 || (a==100 && c>=64 && c<=127)
                || (a==169 && c==254) || (a==172 && c>=16 && c<=31)
                || (a==192 && (c==168 || c==0 || (c==88 && d==99)))
                || (a==198 && (c==18 || c==19 || (c==51 && d==100)))
                || (a==203 && c==0 && d==113));
    }
    public static boolean jpegEnvelope(byte[] b) {
        return b.length>=4 && (b[0]&255)==255 && (b[1]&255)==216
                && (b[b.length-2]&255)==255 && (b[b.length-1]&255)==217;
    }
    public static int sample(int width, int height) {
        if (width<=0 || height<=0 || width>16384 || height>16384
                || (long)width*height>40_000_000) return 0;
        int sample=1;
        while ((Math.max(width,height)+sample-1)/sample>512) sample*=2;
        return sample;
    }
}
