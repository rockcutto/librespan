package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Small sanitized in-app call lifecycle trace.
 *
 * The trace deliberately excludes JIDs, SDP, ICE candidates/addresses, message content and
 * server identifiers. Persistence is best-effort and must never affect call behavior.
 */
public final class CallDiagnosticsRuntime {

    private static final String DIRECTORY = "call_diagnostics";
    private static final String FILE_NAME = "latest.txt";
    private static final int MAX_RECORDS = 400;
    private static final int MAX_EVENT_CHARS = 480;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RECORDS = new ArrayDeque<>(MAX_RECORDS);
    private static final ExecutorService PERSISTENCE_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        final Thread thread = new Thread(runnable, "CallDiagnostics");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final Pattern JID_PATTERN = Pattern.compile("[^\\s@]+@[^\\s]+");
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])(?:\\d{1,3}\\.){3}\\d{1,3}(?![A-Za-z0-9_])");

    private static volatile Context applicationContext;

    private CallDiagnosticsRuntime() {}

    public static void initialize(final Context context) {
        applicationContext = context.getApplicationContext();
    }

    public static void record(final String event) {
        if (event == null) {
            return;
        }
        final String line =
                timestamp()
                        + " +"
                        + SystemClock.elapsedRealtime()
                        + "ms "
                        + sanitize(event);
        synchronized (LOCK) {
            RECORDS.addLast(line);
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeFirst();
            }
        }
        final Context context = applicationContext;
        if (context != null) {
            PERSISTENCE_EXECUTOR.execute(() -> persist(context, inMemoryReport()));
        }
    }

    public static String report(final Context context) {
        synchronized (LOCK) {
            if (!RECORDS.isEmpty()) {
                return buildReportLocked();
            }
        }
        final File file = reportFile(context);
        if (!file.isFile()) {
            return emptyReport();
        }
        try {
            return readText(file);
        } catch (final IOException e) {
            return emptyReport();
        }
    }

    public static void clear(final Context context) {
        synchronized (LOCK) {
            RECORDS.clear();
        }
        try {
            reportFile(context).delete();
        } catch (final RuntimeException ignored) {
            // Diagnostics cleanup is best effort only.
        }
    }

    private static String inMemoryReport() {
        synchronized (LOCK) {
            return buildReportLocked();
        }
    }

    private static String buildReportLocked() {
        final StringBuilder builder = new StringBuilder();
        builder.append("LibreSpan call diagnostics\n");
        builder.append("records=").append(RECORDS.size()).append('\n');
        builder.append("sanitized=true\n\n");
        for (final String line : RECORDS) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static String emptyReport() {
        return "LibreSpan call diagnostics\nrecords=0\nsanitized=true\n\nNo call events recorded.\n";
    }

    private static String sanitize(final String raw) {
        String value =
                raw.replace('\n', ' ')
                        .replace('\r', ' ')
                        .replace('\t', ' ')
                        .trim();
        value = JID_PATTERN.matcher(value).replaceAll("<jid>");
        value = IPV4_PATTERN.matcher(value).replaceAll("<ip>");
        if (value.length() > MAX_EVENT_CHARS) {
            return value.substring(0, MAX_EVENT_CHARS) + "...";
        }
        return value;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void persist(final Context context, final String snapshot) {
        try {
            final File file = reportFile(context);
            final File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (OutputStreamWriter writer =
                    new OutputStreamWriter(
                            new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
                writer.write(snapshot);
            }
        } catch (final IOException | RuntimeException ignored) {
            // Diagnostics must never affect call behavior.
        }
    }

    private static String readText(final File file) throws IOException {
        final StringBuilder builder = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static File reportFile(final Context context) {
        return new File(
                new File(context.getApplicationContext().getCacheDir(), DIRECTORY),
                FILE_NAME);
    }
}
