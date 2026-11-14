package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import android.os.SystemClock;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xml.TagWriter;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Exercises the real thread owner, terminal cleanup, deadline and XML reader with controlled I/O. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = 34)
public class XmppReconnectLifecycleTest {
    private static final class Service extends XmppConnectionService {
        Service() { attachBaseContext(RuntimeEnvironment.getApplication()); }
        @Override public AppSettings getAppSettings() { return new AppSettings(this); }
    }

    private static final class Fixture {
        final Service service = new Service();
        final Account account = new Account(Jid.of("reconnect@example.test/device"), "password");
        final AtomicInteger transitions = new AtomicInteger();
        final Controlled connection;
        volatile Throwable failure;
        Fixture() {
            connection = new Controlled(account, service);
            account.setXmppConnection(connection);
            connection.setOnStatusChangedListener(a -> transitions.incrementAndGet());
        }
        Thread start(Runnable action) {
            connection.action = () -> {
                try { action.run(); }
                catch (Throwable t) { failure = t; throw t; }
            };
            connection.prepareNewConnection();
            final Thread thread = connection.prepareConnectionThread();
            thread.start();
            return thread;
        }
    }

    private static final class Controlled extends XmppConnection {
        volatile Runnable action;
        volatile Consumer<Iq> discoCallback;
        Controlled(Account account, Service service) { super(account, service); }
        @Override protected void connect() { action.run(); }
        @Override public String sendIqPacket(Iq packet, Consumer<Iq> callback) {
            discoCallback = callback;
            return "controlled-iq";
        }
    }

    private static class RecordingSocket extends Socket {
        volatile boolean closed;
        final CountDownLatch closeCompleted = new CountDownLatch(1);
        private final String input;
        RecordingSocket() { this(""); }
        RecordingSocket(String input) { this.input = input; }
        @Override public synchronized void close() {
            closed = true;
            closeCompleted.countDown();
        }
        @Override public boolean isClosed() { return closed; }
        @Override public java.io.InputStream getInputStream() {
            return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        }
        @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    }

    private static final class BlockingSocket extends RecordingSocket {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        @Override public void close() {
            closeEntered.countDown();
            try { allowClose.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            super.close();
        }
    }

    private static final class BlockingTagWriter extends TagWriter {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        @Override public void forceClose() {
            closeEntered.countDown();
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (allowClose.getCount() != 0 && System.nanoTime() < end) {
                try { allowClose.await(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* Model a close that ignores interruption. */ }
            }
            super.forceClose();
        }
    }

    private static void field(Object object, String name, Object value) {
        try {
            final Field f = XmppConnection.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(object, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void ageAttempt(XmppConnection connection) {
        field(connection, "connectionAttemptStarted",
                SystemClock.elapsedRealtime() - (Config.CONNECT_TIMEOUT + 1L) * 1000L);
    }

    private static void stream(XmppConnection connection, String xml) {
        try {
            final XmlReader reader = new XmlReader();
            reader.setInputStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            assertNotNull(reader.readTag()); // Opening stream was consumed by startXmpp.
            field(connection, "tagReader", reader);
            final Method process = XmppConnection.class.getDeclaredMethod("processStream");
            process.setAccessible(true);
            process.invoke(connection);
        } catch (ReflectiveOperationException | IOException e) { throw new AssertionError(e); }
    }

    private static void eof(XmppConnection connection) {
        field(connection, "tagReader", new XmlReader() {
            @Override public eu.siacs.conversations.xml.Tag readTag() { return null; }
        });
        try {
            Method process = XmppConnection.class.getDeclaredMethod("processStream");
            process.setAccessible(true);
            process.invoke(connection);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void joined(Fixture fixture, Thread thread) throws InterruptedException {
        thread.join(2000);
        assertFalse("connection thread still running", thread.isAlive());
        if (fixture.failure != null) throw new AssertionError("connection thread failed", fixture.failure);
    }

    @Test public void normalClosingTagTerminatesConnectingAndDoesNotPublishLateDisco() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch readerEnded = new CountDownLatch(1), leaveConnect = new CountDownLatch(1);
        Thread thread = f.start(() -> {
            stream(f.connection,
                    "<stream:stream xmlns:stream='http://etherx.jabber.org/streams'></stream:stream>");
            readerEnded.countDown();
            try { leaveConnect.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(readerEnded.await(2, TimeUnit.SECONDS));
        f.connection.sendDiscoTimeout();
        assertEquals(Account.State.OFFLINE, f.account.getStatus());
        leaveConnect.countDown();
        joined(f, thread);
        assertEquals(Account.State.OFFLINE, f.account.getStatus());
    }

    @Test public void eofBeforeAuthenticationTerminatesConnecting() throws Exception {
        Fixture f = new Fixture();
        joined(f, f.start(() -> eof(f.connection)));
        assertEquals(Account.State.OFFLINE, f.account.getStatus());
    }

    @Test public void eofAfterOnlineClosesFalseOnlineAndSocket() throws Exception {
        Fixture f = new Fixture();
        RecordingSocket socket = new RecordingSocket();
        joined(f, f.start(() -> {
            field(f.connection, "socket", socket);
            f.account.setStatus(Account.State.ONLINE);
            eof(f.connection);
        }));
        assertTrue(socket.closeCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(Account.State.OFFLINE, f.account.getStatus());
    }

    @Test public void readerIOExceptionAlsoTerminatesWithoutLeavingOnline() throws Exception {
        Fixture f = new Fixture();
        joined(f, f.start(() -> {
            f.account.setStatus(Account.State.ONLINE);
            field(f.connection, "tagReader", new XmlReader() {
                @Override public eu.siacs.conversations.xml.Tag readTag() throws IOException {
                    throw new IOException("broken stream");
                }
            });
            try {
                Method process = XmppConnection.class.getDeclaredMethod("processStream");
                process.setAccessible(true);
                process.invoke(f.connection);
                fail("expected IOException");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IOException);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }));
        assertEquals(Account.State.OFFLINE, f.account.getStatus());
    }

    @Test public void openingStreamDoesNotRestartNinetySecondBudget() throws Exception {
        Fixture f = new Fixture();
        RecordingSocket socket = new RecordingSocket(
                "<stream:stream xmlns:stream='http://etherx.jabber.org/streams' from='example.test'>");
        joined(f, f.start(() -> {
            field(f.connection, "connectionAttemptStarted", SystemClock.elapsedRealtime() - 10_000L);
            final long before = f.connection.getConnectionDeadlineRemaining();
            try {
                Method start = XmppConnection.class.getDeclaredMethod("startXmpp", Socket.class);
                start.setAccessible(true);
                assertEquals(Boolean.TRUE, start.invoke(f.connection, socket));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            assertTrue("opening stream extended the attempt", f.connection.getConnectionDeadlineRemaining() <= before);
        }));
        assertTrue(socket.closed);
    }

    @Test public void expiredAttemptStopsBlockedReaderWithoutAlarmAndOnlyOnce() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RecordingSocket socket = new RecordingSocket();
        Thread thread = f.start(() -> {
            field(f.connection, "socket", socket);
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long generation = f.connection.getConnectionAttemptGeneration();
        ageAttempt(f.connection); // monotonic fake clock equivalent for an already running attempt
        int before = f.transitions.get();
        assertTrue(f.connection.triggerConnectionTimeout(generation));
        assertFalse(f.connection.triggerConnectionTimeout(generation));
        assertTrue(socket.closeCompleted.await(2, TimeUnit.SECONDS));
        f.connection.sendDiscoTimeout();
        assertEquals(Account.State.CONNECTION_TIMEOUT, f.account.getStatus());
        assertEquals(before + 1, f.transitions.get());
        release.countDown(); joined(f, thread);
        assertEquals(Account.State.CONNECTION_TIMEOUT, f.account.getStatus());
    }

    @Test public void deadlineClosesSocketBeforeXmlStreamDuringNegotiation() throws Exception {
        Fixture f = new Fixture();
        RecordingSocket pending = new RecordingSocket();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread thread = f.start(() -> {
            try {
                Method track = XmppConnection.class.getDeclaredMethod("trackConnectingSocket", Socket.class);
                track.setAccessible(true);
                track.invoke(f.connection, pending);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ageAttempt(f.connection);
        assertTrue(f.connection.triggerConnectionTimeout(f.connection.getConnectionAttemptGeneration()));
        assertTrue("pending TCP/TLS socket stayed open",
                pending.closeCompleted.await(2, TimeUnit.SECONDS));
        release.countDown(); joined(f, thread);
    }

    @Test public void staleDeadlineAndOldFinallyCannotTouchNewAttempt() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch enteredA = new CountDownLatch(1), releaseA = new CountDownLatch(1);
        CountDownLatch enteredB = new CountDownLatch(1), releaseB = new CountDownLatch(1);
        Thread a = f.start(() -> {
            enteredA.countDown();
            while (releaseA.getCount() != 0) {
                try { releaseA.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException expected) { /* Model a late callback despite cancellation. */ }
            }
            try {
                Method online = XmppConnection.class.getDeclaredMethod("changeStatusToOnline");
                online.setAccessible(true);
                online.invoke(f.connection); // A must not publish ONLINE for B's resumed transport.
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertTrue(enteredA.await(2, TimeUnit.SECONDS));
        long old = f.connection.getConnectionAttemptGeneration();
        RecordingSocket socketB = new RecordingSocket();
        Thread b = f.start(() -> {
            field(f.connection, "socket", socketB);
            enteredB.countDown();
            try { releaseB.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(enteredB.await(2, TimeUnit.SECONDS));
        ageAttempt(f.connection);
        assertFalse(f.connection.triggerConnectionTimeout(old));
        releaseA.countDown(); joined(f, a);
        assertFalse(socketB.closed);
        assertEquals(Account.State.CONNECTING, f.account.getStatus());
        releaseB.countDown(); joined(f, b);
        assertTrue(socketB.closed);
    }

    @Test public void intentionalDisconnectDoesNotEmitTerminalRetry() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread thread = f.start(() -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        int transitions = f.transitions.get();
        f.connection.disconnect(true);
        release.countDown(); joined(f, thread);
        assertEquals(transitions, f.transitions.get());
    }

    @Test public void blockedSocketCloseDoesNotHoldDeadlineOrNextAttempt() throws Exception {
        Fixture f = new Fixture();
        BlockingSocket blocked = new BlockingSocket();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread a = f.start(() -> {
            field(f.connection, "socket", blocked);
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ageAttempt(f.connection);
        final long old = f.connection.getConnectionAttemptGeneration();
        CountDownLatch deadlineFinished = new CountDownLatch(1);
        Thread deadline = new Thread(() -> {
            if (f.connection.triggerConnectionTimeout(old)) deadlineFinished.countDown();
        });
        deadline.start();
        try {
            assertTrue("timeout waited for Socket.close()",
                    deadlineFinished.await(1, TimeUnit.SECONDS));
            assertEquals(Account.State.CONNECTION_TIMEOUT, f.account.getStatus());
            assertTrue(blocked.closeEntered.await(1, TimeUnit.SECONDS));
            RecordingSocket bSocket = new RecordingSocket();
            CountDownLatch bReady = new CountDownLatch(1), bRelease = new CountDownLatch(1);
            Thread b = f.start(() -> {
                field(f.connection, "socket", bSocket);
                bReady.countDown();
                try { bRelease.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            assertTrue("B could not acquire the ownership monitor", bReady.await(1, TimeUnit.SECONDS));
            blocked.allowClose.countDown();
            assertTrue(blocked.closeCompleted.await(2, TimeUnit.SECONDS));
            assertFalse("old closer touched B", bSocket.closed);
            bRelease.countDown(); joined(f, b);
        } finally {
            blocked.allowClose.countDown();
            release.countDown();
            deadline.join(2000);
            joined(f, a);
        }
    }

    @Test public void blockedOldTagWriterCloseDoesNotBlockTimeout() throws Exception {
        Fixture f = new Fixture();
        BlockingTagWriter oldWriter = new BlockingTagWriter();
        field(f.connection, "tagWriter", oldWriter);
        RecordingSocket socket = new RecordingSocket(
                "<stream:stream xmlns:stream='http://etherx.jabber.org/streams' from='example.test'>");
        Thread a = f.start(() -> {
            try {
                Method start = XmppConnection.class.getDeclaredMethod("startXmpp", Socket.class);
                start.setAccessible(true);
                start.invoke(f.connection, socket);
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof InterruptedException);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertTrue(oldWriter.closeEntered.await(2, TimeUnit.SECONDS));
        ageAttempt(f.connection);
        final long generation = f.connection.getConnectionAttemptGeneration();
        CountDownLatch deadlineFinished = new CountDownLatch(1);
        Thread deadline = new Thread(() -> {
            if (f.connection.triggerConnectionTimeout(generation)) deadlineFinished.countDown();
        });
        deadline.start();
        try {
            assertTrue("deadline blocked on the writer monitor",
                    deadlineFinished.await(1, TimeUnit.SECONDS));
            assertEquals(Account.State.CONNECTION_TIMEOUT, f.account.getStatus());
        } finally {
            oldWriter.allowClose.countDown();
            deadline.join(2000);
            joined(f, a);
        }
    }

    @Test public void eofPublishesOfflineBeforeNestedTransportCleanupCanBlock() throws Exception {
        Fixture f = new Fixture();
        BlockingSocket cleanup = new BlockingSocket();
        Thread a = f.start(() -> {
            f.account.setStatus(Account.State.ONLINE);
            eof(f.connection); // Equivalent terminal read inside the nested STARTTLS stream.
            cleanup.close(); // The enclosing switchOverToTls still closes its SSL socket.
        });
        try {
            assertTrue(cleanup.closeEntered.await(2, TimeUnit.SECONDS));
            assertEquals("dead transport stayed ONLINE during blocked TLS cleanup",
                    Account.State.OFFLINE, f.account.getStatus());
            ageAttempt(f.connection);
            assertFalse("terminal state must not be finished twice",
                    f.connection.triggerConnectionTimeout(f.connection.getConnectionAttemptGeneration()));
        } finally {
            cleanup.allowClose.countDown();
            joined(f, a);
        }
    }

    @Test public void supersededStartTlsCannotReadReplacementReaderOrSocket() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch aReady = new CountDownLatch(1), aContinue = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1), bContinue = new CountDownLatch(1);
        XmlReader readerA = new XmlReader();
        AtomicInteger bReads = new AtomicInteger();
        XmlReader readerB = new XmlReader() {
            @Override public <T extends im.conversations.android.xmpp.model.StreamElement> T readElement(
                    Tag tag, Class<T> clazz) throws IOException {
                bReads.incrementAndGet();
                throw new IOException("old attempt read B's TLS proceed");
            }
        };
        Thread a = f.start(() -> {
            field(f.connection, "tagReader", readerA);
            field(f.connection, "tagWriter", new TagWriter());
            field(f.connection, "socket", new RecordingSocket());
            aReady.countDown();
            while (aContinue.getCount() != 0) {
                try { aContinue.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* The old callback runs after replacement. */ }
            }
            try {
                Method tls = XmppConnection.class.getDeclaredMethod(
                        "switchOverToTls", Tag.class, XmlReader.class);
                tls.setAccessible(true);
                tls.invoke(f.connection, Tag.start("proceed").setAttribute("xmlns", "urn:ietf:params:xml:ns:xmpp-tls"), readerA);
                fail("stale STARTTLS was accepted");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IOException);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertTrue(aReady.await(2, TimeUnit.SECONDS));
        RecordingSocket socketB = new RecordingSocket();
        Thread b = f.start(() -> {
            field(f.connection, "tagReader", readerB);
            field(f.connection, "tagWriter", new TagWriter());
            field(f.connection, "socket", socketB);
            bReady.countDown();
            try { bContinue.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(bReady.await(2, TimeUnit.SECONDS));
            aContinue.countDown();
            joined(f, a);
            assertEquals("A consumed B's XML reader", 0, bReads.get());
            assertFalse("A closed B's socket", socketB.closed);
            assertEquals(Account.State.CONNECTING, f.account.getStatus());
        } finally {
            aContinue.countDown();
            bContinue.countDown();
            joined(f, b);
        }
    }

    @Test public void oldDiscoCallbackCannotFinalizeReplacementAttempt() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch aReady = new CountDownLatch(1), aRelease = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1), bRelease = new CountDownLatch(1);
        Thread a = f.start(() -> {
            field(f.connection, "socket", new RecordingSocket());
            try {
                Method discover = XmppConnection.class.getDeclaredMethod(
                        "sendServiceDiscoveryInfo", Jid.class, long.class);
                discover.setAccessible(true);
                discover.invoke(f.connection, f.account.getDomain(), f.connection.getConnectionAttemptGeneration());
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            aReady.countDown();
            while (aRelease.getCount() != 0) {
                try { aRelease.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Async callback outlives A. */ }
            }
        });
        assertTrue(aReady.await(2, TimeUnit.SECONDS));
        Consumer<Iq> oldCallback = f.connection.discoCallback;
        assertNotNull(oldCallback);
        Thread b = f.start(() -> {
            field(f.connection, "socket", new RecordingSocket());
            bReady.countDown();
            try { bRelease.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(bReady.await(2, TimeUnit.SECONDS));
            Field pending = XmppConnection.class.getDeclaredField("mPendingServiceDiscoveries");
            pending.setAccessible(true);
            AtomicInteger bPending = (AtomicInteger) pending.get(f.connection);
            bPending.set(1);
            Field wait = XmppConnection.class.getDeclaredField("mWaitForDisco");
            wait.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean bWait =
                    (java.util.concurrent.atomic.AtomicBoolean) wait.get(f.connection);
            bWait.set(true);
            int transitions = f.transitions.get();
            oldCallback.accept(new Iq(Iq.Type.ERROR));
            assertEquals(1, bPending.get());
            assertTrue(bWait.get());
            assertEquals(transitions, f.transitions.get());
            assertEquals(Account.State.CONNECTING, f.account.getStatus());
        } finally {
            aRelease.countDown();
            bRelease.countDown();
            joined(f, a);
            joined(f, b);
        }
    }

    @Test public void discoCallbackPausedAfterGenerationCheckCannotWriteBDiscovery() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch aReady = new CountDownLatch(1), aRelease = new CountDownLatch(1);
        CountDownLatch callbackChecked = new CountDownLatch(1), releaseCallback = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1), bRelease = new CountDownLatch(1);
        Thread a = f.start(() -> {
            field(f.connection, "socket", new RecordingSocket());
            try {
                Method discover = XmppConnection.class.getDeclaredMethod(
                        "sendServiceDiscoveryInfo", Jid.class, long.class);
                discover.setAccessible(true);
                discover.invoke(f.connection, f.account.getDomain(), f.connection.getConnectionAttemptGeneration());
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            aReady.countDown();
            while (aRelease.getCount() != 0) {
                try { aRelease.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Keep A alive for the delayed IQ. */ }
            }
        });
        assertTrue(aReady.await(2, TimeUnit.SECONDS));
        Consumer<Iq> oldCallback = f.connection.discoCallback;
        assertNotNull(oldCallback);
        Iq delayedError = new Iq(Iq.Type.ERROR);
        // Iq cannot be subclassed: Extension requires its concrete class to match Iq.class.
        // Pause the first getType() attribute lookup AFTER the callback owner check.
        delayedError.setAttributes(new java.util.Hashtable<String, String>(
                delayedError.getAttributes()) {
            final AtomicInteger reads = new AtomicInteger();
            @Override public synchronized String get(Object key) {
                if ("type".equals(key) && reads.getAndIncrement() == 0) {
                    callbackChecked.countDown();
                    try { releaseCallback.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return super.get(key);
            }
        });
        Thread callback = new Thread(() -> oldCallback.accept(delayedError));
        callback.start();
        Thread b = null;
        try {
            assertTrue("callback never passed the initial owner check",
                    callbackChecked.await(2, TimeUnit.SECONDS));
            b = f.start(() -> {
                field(f.connection, "socket", new RecordingSocket());
                bReady.countDown();
                try { bRelease.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(bReady.await(2, TimeUnit.SECONDS));
            Field discoField = XmppConnection.class.getDeclaredField("disco");
            discoField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Jid, ServiceDiscoveryResult> disco =
                    (Map<Jid, ServiceDiscoveryResult>) discoField.get(f.connection);
            ServiceDiscoveryResult bResult = ServiceDiscoveryResult.empty();
            synchronized (disco) { disco.put(f.account.getDomain(), bResult); }
            Field pending = XmppConnection.class.getDeclaredField("mPendingServiceDiscoveries");
            pending.setAccessible(true);
            AtomicInteger bPending = (AtomicInteger) pending.get(f.connection);
            bPending.set(1);
            releaseCallback.countDown();
            callback.join(2000);
            assertFalse("old disco callback is blocked", callback.isAlive());
            synchronized (disco) { assertSame("A replaced B's discovery", bResult,
                    disco.get(f.account.getDomain())); }
            assertEquals("A consumed B's pending discovery", 1, bPending.get());
            assertEquals(Account.State.CONNECTING, f.account.getStatus());
        } finally {
            releaseCallback.countDown();
            aRelease.countDown();
            bRelease.countDown();
            callback.join(2000);
            joined(f, a);
            if (b != null) joined(f, b);
        }
    }

    @Test public void errorOfflineListenerCannotResurrectTransportViaDisco() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch registered = new CountDownLatch(1), failConnection = new CountDownLatch(1);
        CountDownLatch listenerEntered = new CountDownLatch(1), releaseListener = new CountDownLatch(1);
        Thread connectionThread = f.start(() -> {
            field(f.connection, "socket", new RecordingSocket());
            try {
                Method discover = XmppConnection.class.getDeclaredMethod(
                        "sendServiceDiscoveryInfo", Jid.class, long.class);
                discover.setAccessible(true);
                discover.invoke(f.connection, f.account.getDomain(),
                        f.connection.getConnectionAttemptGeneration());
                registered.countDown();
                failConnection.await(3, TimeUnit.SECONDS);
                // The IOException catch in connect() uses this same terminal status transition.
                Method status = XmppConnection.class.getDeclaredMethod("changeStatus", Account.State.class);
                status.setAccessible(true);
                status.invoke(f.connection, Account.State.OFFLINE);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(registered.await(2, TimeUnit.SECONDS));
        Consumer<Iq> pendingCallback = f.connection.discoCallback;
        assertNotNull(pendingCallback);
        f.connection.setOnStatusChangedListener(account -> {
            f.transitions.incrementAndGet();
            if (account.getStatus() == Account.State.OFFLINE) {
                listenerEntered.countDown();
                try { releaseListener.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            failConnection.countDown();
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            Field pendingField = XmppConnection.class.getDeclaredField("mPendingServiceDiscoveries");
            pendingField.setAccessible(true);
            AtomicInteger pending = (AtomicInteger) pendingField.get(f.connection);
            pending.set(2);
            Field discoField = XmppConnection.class.getDeclaredField("disco");
            discoField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Jid, ServiceDiscoveryResult> disco =
                    (Map<Jid, ServiceDiscoveryResult>) discoField.get(f.connection);
            pendingCallback.accept(new Iq(Iq.Type.ERROR));
            f.connection.sendDiscoTimeout();
            assertEquals("disco changed an already failed connection", Account.State.OFFLINE,
                    f.account.getStatus());
            assertEquals("disco consumed a failed attempt's result", 2, pending.get());
            synchronized (disco) {
                assertFalse("disco wrote after OFFLINE", disco.containsKey(f.account.getDomain()));
            }
        } finally {
            failConnection.countDown();
            releaseListener.countDown();
            joined(f, connectionThread);
        }
    }
}
