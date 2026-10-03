package eu.siacs.conversations;

import android.graphics.Bitmap;
import android.net.Uri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import java.util.Locale;

public final class Config {
    private static final int UNENCRYPTED = 1;
    private static final int OTR = 4;
    private static final int OMEMO = 8;

    private static final int ENCRYPTION_MASK = UNENCRYPTED | OTR | OMEMO;

    public static boolean supportUnencrypted() {
        return (ENCRYPTION_MASK & UNENCRYPTED) != 0;
    }


    public static boolean supportOmemo() {
        return (ENCRYPTION_MASK & OMEMO) != 0;
    }

    public static boolean supportOtr() {
        return (ENCRYPTION_MASK & OTR) != 0;
    }

    public static boolean omemoOnly() {
        return !multipleEncryptionChoices() && supportOmemo();
    }

    public static boolean multipleEncryptionChoices() {
        return (ENCRYPTION_MASK & (ENCRYPTION_MASK - 1)) != 0;
    }

    public static final String LOGTAG = BuildConfig.APP_NAME.toLowerCase(Locale.US);

    public static final boolean QUICK_LOG = false;

    public static final Uri HELP = null;


    public static final String DOMAIN_LOCK = null; // public build: accept full JIDs from any XMPP domain
    public static final String MAGIC_CREATE_DOMAIN = null;
    public static final Jid QUICKSY_DOMAIN = Jid.of("quicksy.im");

    public static final String CHANNEL_DISCOVERY = "https://search.jabber.network";

    public static final boolean DISALLOW_REGISTRATION_IN_UI = false; // allow standard in-band registration when requested

    public static final boolean USE_RANDOM_RESOURCE_ON_EVERY_BIND = false;

    public static final boolean MESSAGE_DISPLAYED_SYNCHRONIZATION = true;

    public static final boolean ALLOW_NON_TLS_CONNECTIONS =
            false; // very dangerous. you should have a good reason to set this to true

    public static final long CONTACT_SYNC_RETRY_INTERVAL = 1000L * 60 * 5;

    public static final boolean QUICKSTART_ENABLED = true;

    // Notification settings
    public static final boolean HIDE_MESSAGE_TEXT_IN_NOTIFICATION = false;
    public static final boolean ALWAYS_NOTIFY_BY_DEFAULT = false;
    public static final boolean SUPPRESS_ERROR_NOTIFICATION = false;

    public static final boolean DISABLE_BAN = false; // disables the ability to ban users from rooms

    public static final int PING_MAX_INTERVAL = 300;
    public static final int IDLE_PING_INTERVAL = 600; // 540 is minimum according to docs;
    // Only used in true background when XEP-0352 + XEP-0198 + server push are all available.
    // Keeping it aligned with the existing idle wakeup avoids an extra 5-minute alarm.
    public static final int PUSH_BACKGROUND_PING_INTERVAL = 600;
    public static final int PING_MIN_INTERVAL = 30;
    public static final int LOW_PING_TIMEOUT = 1; // used after push received
    public static final int PING_TIMEOUT = 15;
    public static final int SOCKET_TIMEOUT = 15;
    public static final int CONNECT_TIMEOUT = 90;
    public static final int POST_CONNECTIVITY_CHANGE_PING_INTERVAL = 30;
    public static final int CONNECT_DISCO_TIMEOUT = 20;
    public static final long NETWORK_TRANSITION_GRACE_MS = 1500L;
    public static final long NETWORK_PROVISIONAL_READY_GRACE_MS = 800L;
    public static final int SM_RESUME_ACK_TIMEOUT = 8;
    public static final int MINI_GRACE_PERIOD = 750;

    // media file formats. Homogenous Android or Conversations only deployments can switch to opus
    // and webp
    public static final int AVATAR_SIZE = 192;
    public static final Bitmap.CompressFormat AVATAR_FORMAT = Bitmap.CompressFormat.JPEG;
    public static final int AVATAR_CHAR_LIMIT = 9400;

    public static final int IMAGE_SIZE = 1920;
    public static final Bitmap.CompressFormat IMAGE_FORMAT = Bitmap.CompressFormat.JPEG;
    public static final int IMAGE_QUALITY = 75;

    public static final boolean USE_OPUS_VOICE_MESSAGES = false;

    public static final int MESSAGE_MERGE_WINDOW = 30;

    public static final int PAGE_SIZE = 50;
    // User-driven reverse MUC history pages stay deliberately small so historical SCS
    // persistence cannot monopolize the secure-content path. Automatic catch-up uses PAGE_SIZE
    // so reconnecting rooms become current promptly.
    public static final int SECURE_MUC_MAM_PAGE_SIZE = 10;
    public static final int MAX_NUM_PAGES = 3;
    public static final int MAX_SEARCH_RESULTS = 300;

    public static final int REFRESH_UI_INTERVAL = 500;

    public static final int MAX_DISPLAY_MESSAGE_CHARS = 4096;
    public static final int MAX_STORAGE_MESSAGE_CHARS = 2 * 1024 * 1024; // 2MB

    public static final long MILLISECONDS_IN_DAY = 24 * 60 * 60 * 1000;

    // remove *other* omemo devices from *your* device list announcement after not seeing any
    // activity from them for 42 days. They will automatically add themselves after coming back
    // online.
    public static final long OMEMO_AUTO_EXPIRY = 42 * MILLISECONDS_IN_DAY;

    public static final boolean REMOVE_BROKEN_DEVICES = false;
    public static final boolean OMEMO_PADDING = false;
    public static final boolean PUT_AUTH_TAG_INTO_KEY = true;
    public static final boolean AUTOMATICALLY_COMPLETE_SESSIONS = true;
    public static final boolean DISABLE_PROXY_LOOKUP =
            false; // disables STUN/TURN and Proxy65 look up (useful to debug IBB fallback)
    public static final boolean USE_DIRECT_JINGLE_CANDIDATES = true;
    public static final boolean USE_JINGLE_MESSAGE_INIT = true;

    public static final boolean DISABLE_HTTP_UPLOAD = false;
    public static final boolean EXTENDED_SM_LOGGING = false; // log stanza counts
    public static final boolean BACKGROUND_STANZA_LOGGING =
            false; // log all stanzas that were received while the app is in background
    public static final boolean RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE =
            true; // setting to true might increase power consumption

    public static final boolean ENCRYPT_ON_HTTP_UPLOADED = false;

    /**
     * Product rollout gates for the private Secure Content Store.
     *
     * These switches remain intentionally independent so media and protected-text behaviour can
     * still be isolated during diagnostics. Production/device-test defaults enable both paths.
     */
    public static final boolean SECURE_CONTENT_MEDIA_ROLLOUT = true;
    public static final boolean SECURE_MESSAGE_PAYLOAD_ROLLOUT = true;

    public static final boolean X509_VERIFICATION =
            false; // use x509 certificates to verify OMEMO keys
    public static final boolean REQUIRE_RTP_VERIFICATION =
            false; // require a/v calls to be verified with OMEMO
    public static final boolean JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK = false;
    public static final boolean JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT = false;
    public static final long DEVICE_DISCOVERY_TIMEOUT = 8000; // warm/active device, milliseconds
    public static final long DEVICE_DISCOVERY_COLD_WAKE_TIMEOUT = 18000; // milliseconds
    public static final long INITIAL_ICE_RECOVERY_TIMEOUT = 8000; // milliseconds
    public static final long ICE_SERVER_CACHE_TTL = 120000; // milliseconds
    public static final long ICE_SERVER_EMPTY_CACHE_TTL = 30000; // milliseconds
    public static final long ICE_SERVER_DISCOVERY_TIMEOUT = 4; // seconds

    public static final boolean ONLY_INTERNAL_STORAGE =
            false; // use internal storage instead of sdcard to save attachments

    public static final boolean IGNORE_ID_REWRITE_IN_MUC = true;
    public static final boolean MUC_LEAVE_BEFORE_JOIN = false;

    public static final long MAM_MAX_CATCHUP = MILLISECONDS_IN_DAY * 5;
    public static final int MAM_MAX_MESSAGES = 3000;

    public static final ChatState DEFAULT_CHAT_STATE = ChatState.ACTIVE;
    public static final int TYPING_TIMEOUT = 8;

    public static final int EXPIRY_INTERVAL = 30 * 60 * 1000; // 30 minutes

    public static final String[] ENABLED_CIPHERS = {
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA384",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_DHE_RSA_WITH_CAMELLIA_256_SHA",

        // Fallback.
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_128_GCM_SHA384",
        "TLS_RSA_WITH_AES_256_GCM_SHA256",
        "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA384",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA384",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
    };

    public static final String[] WEAK_CIPHER_PATTERNS = {
        "_NULL_", "_EXPORT_", "_anon_", "_RC4_", "_DES_", "_MD5",
    };

    private Config() {}

    public static final class Map {
        public static final double INITIAL_ZOOM_LEVEL = 4;
        public static final double FINAL_ZOOM_LEVEL = 15;
        public static final int MY_LOCATION_INDICATOR_SIZE = 10;
        public static final int MY_LOCATION_INDICATOR_OUTLINE_SIZE = 3;
        public static final long LOCATION_FIX_TIME_DELTA = 1000 * 10; // ms
        public static final float LOCATION_FIX_SPACE_DELTA = 10; // m
        public static final int LOCATION_FIX_SIGNIFICANT_TIME_DELTA = 1000 * 60 * 2; // ms
    }

    // How deep nested quotes should be displayed. '2' means one quote nested in another.
    public static final int QUOTE_MAX_DEPTH = 7;
    // How deep nested quotes should be created on quoting a message.
    public static final int QUOTING_MAX_DEPTH = 2;
}
