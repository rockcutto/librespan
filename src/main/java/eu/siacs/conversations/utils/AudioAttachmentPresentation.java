package eu.siacs.conversations.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Shared audio-card policy. Duration and message direction/type are deliberately not inputs. */
public final class AudioAttachmentPresentation {
    private AudioAttachmentPresentation() {}

    // Both LibreSpan voice recorders generate RECORDING_yyyyMMdd_HHmmssSSS.m4a/.oga.
    // This presentation-only check must not classify every .m4a/.oga as a voice message.
    private static final Pattern APP_VOICE_RECORDING_NAME =
            Pattern.compile("RECORDING_[0-9]{8}_[0-9]{9}\\.(?:m4a|oga)");

    public static boolean isAppVoiceRecordingName(final String fileName) {
        return fileName != null && APP_VOICE_RECORDING_NAME.matcher(fileName).matches();
    }

    public enum Tracks { UNKNOWN, AUDIO, VIDEO }

    public static boolean isSupportedMime(final String mime) {
        if (mime == null) return false;
        switch (mime) {
            case "audio/mpeg":
            case "audio/aac":
            case "audio/aac-adts":
            case "audio/mp4":
            case "audio/ogg":
            case "audio/opus":
            case "audio/wav":
            case "audio/x-wav":
            case "audio/flac":
            case "audio/x-flac":
            case "audio/amr":
            case "audio/amr-wb":
                return true;
            default:
                return false;
        }
    }

    public static boolean isAudio(final String mime, final Tracks tracks) {
        if (tracks == Tracks.VIDEO) return false;
        return isSupportedMime(mime) || ("application/ogg".equals(mime) && tracks == Tracks.AUDIO);
    }

    public static boolean needsInspection(final String mime) {
        return isSupportedMime(mime) || "application/ogg".equals(mime);
    }

    /**
     * Inspect every Ogg logical stream, including codecs Android's extractor might silently skip.
     * Only Vorbis/Opus BOS packets establish audio; unknown streams fail closed. Payload is skipped
     * rather than buffered. Chained/multiplexed video must not inherit an audio track's identity.
     */
    public static Tracks inspectOgg(final InputStream input) throws IOException {
        boolean audio = false;
        boolean unknown = false;
        final byte[] header = new byte[27];
        while (true) {
            final int first = input.read();
            if (first < 0) return audio && !unknown ? Tracks.AUDIO : Tracks.UNKNOWN;
            header[0] = (byte) first;
            if (!readFully(input, header, 1, 26)
                    || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S'
                    || header[4] != 0) return Tracks.UNKNOWN;
            final int segments = header[26] & 0xff;
            final byte[] lacing = new byte[segments];
            if (!readFully(input, lacing, 0, segments)) return Tracks.UNKNOWN;
            int size = 0;
            for (byte segment : lacing) size += segment & 0xff;
            final int prefixSize = Math.min(size, 8);
            final byte[] prefix = new byte[prefixSize];
            if (!readFully(input, prefix, 0, prefixSize)) return Tracks.UNKNOWN;
            if ((header[5] & 2) != 0) {
                if (prefixSize >= 7 && prefix[0] == (byte) 0x80
                        && "theora".equals(new String(prefix, 1, 6, StandardCharsets.US_ASCII))) {
                    return Tracks.VIDEO;
                }
                final boolean vorbis = prefixSize >= 7 && prefix[0] == 1
                        && "vorbis".equals(new String(prefix, 1, 6, StandardCharsets.US_ASCII));
                final boolean opus = prefixSize == 8
                        && "OpusHead".equals(new String(prefix, StandardCharsets.US_ASCII));
                if (vorbis || opus) audio = true;
                else unknown = true;
            }
            long remaining = size - prefixSize;
            while (remaining > 0) {
                final long skipped = input.skip(remaining);
                if (skipped > 0) remaining -= skipped;
                else if (input.read() < 0) return Tracks.UNKNOWN;
                else remaining--;
            }
        }
    }

    private static boolean readFully(InputStream input, byte[] data, int offset, int count)
            throws IOException {
        while (count > 0) {
            final int read = input.read(data, offset, count);
            if (read < 0) return false;
            if (read == 0) {
                final int one = input.read();
                if (one < 0) return false;
                data[offset++] = (byte) one;
                count--;
            } else {
                offset += read;
                count -= read;
            }
        }
        return true;
    }

    public interface Source extends AutoCloseable {
        Metadata read() throws Exception;
        @Override void close() throws Exception;
    }

    public interface Opener {
        Source open() throws Exception;
    }

    /** Bounded, single-line presentation strings; never stored on Message or in the database. */
    public static String clean(final String value) {
        if (value == null) return null;
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 200; ) {
            final int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                result.append(' ');
            } else if (Character.getType(codePoint) != Character.FORMAT
                    && !(codePoint >= 0xd800 && codePoint <= 0xdfff)) {
                if (result.length() + Character.charCount(codePoint) > 200) break;
                result.appendCodePoint(codePoint);
            }
        }
        final String cleaned = result.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    public static final class Metadata {
        public final Tracks tracks;
        public final String title;
        public final String artist;
        public final int durationMillis;

        public Metadata(Tracks tracks, String title, String artist, int durationMillis) {
            this.tracks = tracks;
            this.title = clean(title);
            this.artist = clean(artist);
            this.durationMillis = Math.max(0, durationMillis);
        }

        public String titleOrFileName(String fileName) {
            return title == null ? clean(fileName) : title;
        }

        /** Chat UI: generated recorder filenames are not music titles. Preserve real tags. */
        public String titleOrFileNameForChat(String fileName) {
            return title == null && isAppVoiceRecordingName(fileName)
                    ? null
                    : titleOrFileName(fileName);
        }
    }

    /** Epoch-scoped LRU plus in-flight identities, shared by chat and the attachment browser. */
    public static final class Cache {
        private final int limit;
        private long generation;
        private final Map<String, Ticket> inFlight = new LinkedHashMap<>();
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);

        public Cache(int limit) {
            if (limit <= 0) throw new IllegalArgumentException("positive cache limit required");
            this.limit = limit;
        }

        public synchronized Metadata peek(String key, long epoch, boolean available) {
            final Entry entry = entries.get(key);
            return available && entry != null && entry.epoch == epoch ? entry.value : null;
        }

        public synchronized Ticket begin(String key, long epoch, boolean available) {
            if (!available || peek(key, epoch, true) != null) return null;
            final Ticket pending = inFlight.get(key);
            if (pending != null && pending.epoch == epoch) return null;
            // Do not allow a large history bind to queue an unbounded number of decryptions.
            if (inFlight.size() >= limit && pending == null) return null;
            if (pending != null) pending.finished.complete(null);
            final Ticket ticket = new Ticket(key, epoch, generation);
            inFlight.put(key, ticket);
            return ticket;
        }

        public synchronized boolean publish(Ticket ticket, long epoch, boolean available, Metadata value) {
            if (inFlight.get(ticket.key) != ticket) return false;
            inFlight.remove(ticket.key);
            if (!available || ticket.epoch != epoch || ticket.generation != generation) {
                ticket.finished.complete(null);
                return false;
            }
            entries.put(ticket.key, new Entry(epoch, value));
            while (entries.size() > limit) entries.remove(entries.keySet().iterator().next());
            ticket.finished.complete(value);
            return true;
        }

        /** Only browser workers wait; UI binds always use peek/begin without waiting. */
        public void awaitPending(String key, long epoch) {
            final Ticket pending;
            synchronized (this) {
                pending = inFlight.get(key);
                if (pending == null || pending.epoch != epoch) return;
            }
            try {
                pending.finished.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Slow/unavailable media remains conservatively unclassified for this page.
            }
        }

        /** The source is closed before publication, also on errors and epoch changes. */
        public Metadata load(Ticket ticket, LongSupplier epoch, BooleanSupplier available, Opener opener) {
            try {
                synchronized (this) {
                    if (inFlight.get(ticket.key) != ticket) return null;
                }
                if (ticket.epoch != epoch.getAsLong() || !available.getAsBoolean()) return null;
                final Metadata value;
                try (Source source = opener.open()) {
                    if (source == null) return null;
                    value = source.read();
                }
                return value != null && publish(ticket, epoch.getAsLong(), available.getAsBoolean(), value)
                        ? value : null;
            } catch (Exception ignored) {
                return null;
            } finally {
                cancel(ticket);
            }
        }

        public synchronized void cancel(Ticket ticket) {
            if (inFlight.get(ticket.key) == ticket) inFlight.remove(ticket.key);
            ticket.finished.complete(null);
        }

        public synchronized void clear() {
            generation++;
            entries.clear();
            for (Ticket pending : inFlight.values()) pending.finished.complete(null);
            inFlight.clear();
        }
    }

    public static final class Ticket {
        public final String key;
        public final long epoch;
        private final long generation;
        private final CompletableFuture<Metadata> finished = new CompletableFuture<>();

        private Ticket(String key, long epoch, long generation) {
            this.key = key;
            this.epoch = epoch;
            this.generation = generation;
        }
    }

    private static final class Entry {
        final long epoch;
        final Metadata value;
        Entry(long epoch, Metadata value) { this.epoch = epoch; this.value = value; }
    }
}
