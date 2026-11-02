package eu.siacs.conversations.utils;

import org.junit.Test;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import eu.siacs.conversations.utils.AudioAttachmentPresentation.*;

public class AudioAttachmentPresentationTest {
    private final Cache cache = new Cache(2);
    private final AtomicLong epoch = new AtomicLong(4);
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final Metadata music = new Metadata(Tracks.AUDIO, "Song", "Artist", 0);

    @Test public void supportedAudioDoesNotNeedDuration() {
        for (String mime : new String[] {"audio/mpeg", "audio/aac", "audio/aac-adts", "audio/mp4",
                "audio/ogg", "audio/opus", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac",
                "audio/amr", "audio/amr-wb"}) {
            assertTrue(mime, AudioAttachmentPresentation.isAudio(mime, Tracks.UNKNOWN));
        }
    }

    @Test public void oggNeedsActualAudioAndVideoAlwaysWins() {
        assertFalse(AudioAttachmentPresentation.isAudio("application/ogg", Tracks.UNKNOWN));
        assertTrue(AudioAttachmentPresentation.isAudio("application/ogg", Tracks.AUDIO));
        assertFalse(AudioAttachmentPresentation.isAudio("application/ogg", Tracks.VIDEO));
        assertFalse(AudioAttachmentPresentation.isAudio("audio/ogg", Tracks.VIDEO));
    }

    @Test public void realOggHeadersEstablishAudioWithoutADuration() throws Exception {
        assertEquals(Tracks.AUDIO, probe(page("OpusHead")));
        assertEquals(Tracks.AUDIO, probe(page("\u0001vorbis")));
        assertEquals(Tracks.AUDIO, probe(join(page("OpusHead"), page("\u0001vorbis"))));
    }

    @Test public void videoAndUnrecognizedOggStreamsCannotHideBehindAudio() throws Exception {
        // Binary Theora identification packet marker.
        byte[] theora = page(new byte[] {(byte) 0x80, 't', 'h', 'e', 'o', 'r', 'a'});
        assertEquals(Tracks.VIDEO, probe(join(page("OpusHead"), theora)));
        assertEquals(Tracks.UNKNOWN, probe(join(page("OpusHead"), page("unknown"))));
        assertEquals(Tracks.UNKNOWN, probe(join(page("OpusHead"), new byte[] {1, 2})));
        assertEquals(Tracks.UNKNOWN, probe(new byte[0]));
        assertEquals(Tracks.UNKNOWN, probe("not ogg".getBytes(StandardCharsets.US_ASCII)));
    }

    private static Tracks probe(byte[] data) throws IOException {
        return AudioAttachmentPresentation.inspectOgg(new ByteArrayInputStream(data));
    }
    private static byte[] page(String packet) throws IOException {
        return page(packet.getBytes(StandardCharsets.ISO_8859_1));
    }
    private static byte[] page(byte[] packet) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] header = new byte[27];
        header[0] = 'O'; header[1] = 'g'; header[2] = 'g'; header[3] = 'S';
        header[5] = 2; header[26] = 1;
        data.write(header); data.write(packet.length); data.write(packet);
        return data.toByteArray();
    }
    private static byte[] join(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(a); output.write(b); return output.toByteArray();
    }

    @Test public void documentsPlaylistsMidiAndUnknownAreFiles() {
        for (String mime : new String[] {null, "application/pdf", "application/octet-stream",
                "audio/midi", "audio/x-scpls", "audio/mpegurl", "audio/x-matroska", "video/mp4"}) {
            assertFalse(String.valueOf(mime), AudioAttachmentPresentation.isAudio(mime, Tracks.UNKNOWN));
            assertFalse(String.valueOf(mime), AudioAttachmentPresentation.isAudio(mime, Tracks.AUDIO));
        }
    }

    @Test public void titleAndArtistAreIndependentAndMissingTitleUsesFilename() {
        assertEquals("Song", music.titleOrFileName("file.mp3"));
        assertEquals("Artist", music.artist);
        Metadata missingTitle = new Metadata(Tracks.AUDIO, " \n ", "Artist", 0);
        assertEquals("file.mp3", missingTitle.titleOrFileName("file.mp3"));
        assertNull(new Metadata(Tracks.AUDIO, "Song", null, 0).artist);
        assertNull(new Metadata(Tracks.AUDIO, null, null, 0).titleOrFileName(null));
    }

    @Test public void hostileTagsAreSingleLineBoundedAndHaveNoBidiControls() {
        Metadata data = new Metadata(Tracks.AUDIO, "\u202eSong\n\u0000Title\u202c", "x".repeat(10000), -1);
        assertEquals("Song  Title", data.title);
        assertEquals(200, data.artist.length());
        assertEquals(0, data.durationMillis);
        assertNull(AudioAttachmentPresentation.clean("\u0000\u202e\ud800"));
    }

    @Test public void duplicateBindsLoadOnlyOnceAndReuseCachedResult() {
        Ticket first = cache.begin("a:message", 4, true);
        assertNotNull(first);
        assertNull(cache.begin("a:message", 4, true));
        assertTrue(cache.publish(first, 4, true, music));
        assertSame(music, cache.peek("a:message", 4, true));
        assertNull(cache.begin("a:message", 4, true));
    }

    @Test public void cacheIsBoundedAndAccountScoped() {
        put("a:same", music);
        assertNull(cache.peek("b:same", 4, true));
        put("b:same", new Metadata(Tracks.AUDIO, "Other", null, 0));
        assertSame(music, cache.peek("a:same", 4, true));
        put("a:third", music);
        assertNull(cache.peek("b:same", 4, true));
        assertSame(music, cache.peek("a:same", 4, true));
    }

    @Test public void queuedWorkIsBoundedAndCancellationAllowsRetry() {
        Ticket first = cache.begin("1", 4, true);
        assertNotNull(cache.begin("2", 4, true));
        assertNull(cache.begin("3", 4, true));
        cache.cancel(first);
        assertNotNull(cache.begin("3", 4, true));
    }

    @Test public void highSecurityClearRetiresCacheAndOldWorkerEvenAfterUnlock() {
        put("old", music);
        Ticket old = cache.begin("pending", 4, true);
        cache.clear();
        assertNull(cache.peek("old", 4, true));
        assertFalse(cache.publish(old, 4, true, music));
        Ticket replacement = cache.begin("pending", 5, true);
        cache.cancel(old);
        assertTrue(cache.publish(replacement, 5, true, music));
    }

    @Test public void staleEpochOrLockedResultCannotBePublishedOrRead() {
        Ticket stale = cache.begin("message", 4, true);
        assertFalse(cache.publish(stale, 5, true, music));
        assertNull(cache.begin("message", 5, false));
        Ticket locked = cache.begin("message", 5, true);
        assertFalse(cache.publish(locked, 5, false, music));
        put("other", music);
        assertNull(cache.peek("other", 4, false));
        assertNull(cache.peek("other", 5, true));
    }

    @Test public void recycledMessageCannotReceiveAnotherMessagesMetadata() {
        Ticket first = cache.begin("a:first", 4, true);
        Ticket second = cache.begin("a:second", 4, true);
        assertTrue(cache.publish(second, 4, true, new Metadata(Tracks.AUDIO, "Second", null, 0)));
        assertTrue(cache.publish(first, 4, true, music));
        assertEquals("Second", cache.peek("a:second", 4, true).title);
        assertSame(music, cache.peek("a:first", 4, true));
    }

    @Test public void browserWorkerCanReuseChatInspectionAndClearReleasesWaiters() throws Exception {
        Ticket ticket = cache.begin("shared", 4, true);
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> cache.awaitPending("shared", 4));
        assertTrue(cache.publish(ticket, 4, true, music));
        waiter.get(1, TimeUnit.SECONDS);
        assertSame(music, cache.peek("shared", 4, true));
        cache.begin("pending", 4, true);
        CompletableFuture<Void> retired = CompletableFuture.runAsync(() -> cache.awaitPending("pending", 4));
        cache.clear();
        retired.get(1, TimeUnit.SECONDS);
        assertNull(cache.peek("shared", 4, true));
    }

    @Test public void scopedSourceClosesBeforeSuccessfulPublication() {
        AtomicBoolean closed = new AtomicBoolean();
        Metadata result = load(new Source() {
            public Metadata read() { assertFalse(closed.get()); return music; }
            public void close() { closed.set(true); assertNull(cache.peek("load", 4, true)); }
        });
        assertTrue(closed.get());
        assertSame(music, result);
        assertSame(music, cache.peek("load", 4, true));
    }

    @Test public void metadataErrorClosesSourceAndDoesNotPublish() {
        AtomicBoolean closed = new AtomicBoolean();
        assertNull(load(new Source() {
            public Metadata read() throws IOException { throw new IOException("decoder error"); }
            public void close() { closed.set(true); }
        }));
        assertTrue(closed.get());
        assertNull(cache.peek("load", 4, true));
        assertNotNull(cache.begin("load", 4, true));
    }

    @Test public void lockDuringExtractionClosesLeaseAndDiscardsResult() {
        AtomicBoolean closed = new AtomicBoolean();
        assertNull(load(new Source() {
            public Metadata read() { available.set(false); epoch.incrementAndGet(); cache.clear(); return music; }
            public void close() { closed.set(true); }
        }));
        assertTrue(closed.get());
        assertNull(cache.peek("load", epoch.get(), true));
    }

    @Test public void unavailableSourceDoesNotAttemptAnotherOpener() {
        AtomicInteger opened = new AtomicInteger();
        Ticket ticket = cache.begin("load", 4, true);
        assertNull(cache.load(ticket, epoch::get, available::get, () -> { opened.incrementAndGet(); return null; }));
        assertEquals(1, opened.get());
        assertNull(cache.peek("load", 4, true));
    }

    @Test public void retiredQueuedRequestDoesNotOpenSource() {
        AtomicInteger opened = new AtomicInteger();
        Ticket ticket = cache.begin("load", 4, true);
        epoch.incrementAndGet();
        assertNull(cache.load(ticket, epoch::get, available::get, () -> { opened.incrementAndGet(); return null; }));
        assertEquals(0, opened.get());
    }

    @Test public void closeErrorDoesNotPublishMetadata() {
        assertNull(load(new Source() {
            public Metadata read() { return music; }
            public void close() throws IOException { throw new IOException("close failed"); }
        }));
        assertNull(cache.peek("load", 4, true));
    }

    private Metadata load(Source source) {
        return cache.load(cache.begin("load", epoch.get(), available.get()), epoch::get, available::get, () -> source);
    }
    private void put(String key, Metadata value) {
        assertTrue(cache.publish(cache.begin(key, 4, true), 4, true, value));
    }
}
