package eu.siacs.conversations.http;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpDownloadRequestGateTest {
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger head = new AtomicInteger();
    private final AtomicInteger get = new AtomicInteger();
    private final HttpDownloadRequestGate gate = new HttpDownloadRequestGate(active::get, allowed::get);
    private ServerSocket server;
    private Thread serverThread;
    private URL url;

    @Before public void startServer() throws Exception {
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = input.readLine();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    if (first != null && first.startsWith("HEAD ")) head.incrementAndGet();
                    else get.incrementAndGet();
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException error) {
                    if (!server.isClosed()) throw new AssertionError(error);
                }
            }
        }, "p1b-local-http-test");
        serverThread.setDaemon(true);
        serverThread.start();
        url = new URL("http://127.0.0.1:" + server.getLocalPort() + "/attachment");
    }
    @After public void stopServer() throws Exception {
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2000);
    }

    @Test public void disabledDataSaverPolicyAllowsNoAutomaticHeadOrGetForKnownOrUnknownSize() {
        allowed.set(false);
        assertThrows(HttpDownloadRequestGate.Deferred.class, () -> request(false, "HEAD"));
        assertThrows(HttpDownloadRequestGate.Deferred.class, () -> request(false, "GET"));
        assertEquals(0, head.get()); assertEquals(0, get.get());
    }

    @Test public void userActionMayHeadAndGetDespiteAutomaticRestriction() throws Exception {
        allowed.set(false);
        assertEquals(200, request(true, "HEAD"));
        assertEquals(200, request(true, "GET"));
        assertEquals(1, head.get()); assertEquals(1, get.get());
    }

    @Test public void allowedPreflightExecutesAndPolicyChangePreventsNextRequest() throws Exception {
        assertEquals(200, request(false, "HEAD"));
        allowed.set(false);
        assertThrows(HttpDownloadRequestGate.Deferred.class, () -> request(false, "GET"));
        assertEquals(1, head.get()); assertEquals(0, get.get());
    }

    @Test public void queuedWorkRechecksPolicyAndLaterLiveWorkCanRun() throws Exception {
        AutomaticDownloadQueueTest.PausedExecutor executor = new AutomaticDownloadQueueTest.PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 4, 16);
        AtomicInteger deferred = new AtomicInteger();
        for (int i = 0; i < 500; i++) queue.submit(i, () -> {
            try { request(false, "HEAD"); request(false, "GET"); }
            catch (HttpDownloadRequestGate.Deferred policy) { deferred.incrementAndGet(); }
            catch (IOException error) { throw new AssertionError(error); }
        }, deferred::incrementAndGet);
        allowed.set(false);
        executor.drain();
        assertEquals(500, deferred.get());
        assertEquals(0, head.get()); assertEquals(0, get.get());
        allowed.set(true);
        queue.submit("new photo", () -> {
            try { request(false, "HEAD"); request(false, "GET"); }
            catch (IOException error) { throw new AssertionError(error); }
        }, () -> fail());
        executor.drain();
        assertEquals(1, head.get()); assertEquals(1, get.get());
    }

    @Test public void allowedMamBurstHasBoundedRequestsAndManualWorkBypassesFrozenBacklog() throws Exception {
        for (int count : new int[] {100, 500}) {
            head.set(0); get.set(0);
            AutomaticDownloadQueueTest.PausedExecutor executor = new AutomaticDownloadQueueTest.PausedExecutor();
            AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 4, 16);
            AtomicInteger deferred = new AtomicInteger();
            for (int i = 0; i < count; i++) queue.submit(i, () -> {
                try { request(false, "HEAD"); request(false, "GET"); }
                catch (IOException error) { throw new AssertionError(error); }
            }, deferred::incrementAndGet);
            assertEquals(count - 20, deferred.get());
            assertEquals(0, head.get()); assertEquals(0, get.get());
            request(true, "HEAD"); request(true, "GET");
            assertEquals(4, queue.runningCount());
            assertEquals(16, queue.pendingCount());
            executor.drain();
            assertEquals(21, head.get()); assertEquals(21, get.get());
            assertEquals(0, queue.runningCount()); assertEquals(0, queue.pendingCount());
        }
    }

    @Test public void accountCancellationAlsoPreventsInteractiveNetwork() {
        active.set(false);
        assertThrows(HttpDownloadRequestGate.Deferred.class, () -> request(true, "HEAD"));
        assertEquals(0, head.get());
    }

    @Test public void knownAudioAndPhotoSizeLimitsAreUnchanged() {
        assertTrue(HttpDownloadRequestGate.withinAutoLimit(1024, 1024, false));
        assertFalse(HttpDownloadRequestGate.withinAutoLimit(1025, 1024, false));
        assertFalse(HttpDownloadRequestGate.withinAutoLimit(1024, 0, false));
        assertTrue(HttpDownloadRequestGate.withinAutoLimit(16L * 1024 * 1024, 1024, true));
        assertFalse(HttpDownloadRequestGate.withinAutoLimit(16L * 1024 * 1024 + 1, 1024, true));
        assertTrue(HttpDownloadRequestGate.withinAutoLimit(20L * 1024 * 1024, 32L * 1024 * 1024, true));
        assertFalse(HttpDownloadRequestGate.withinAutoLimit(-1, 1024, true));
    }

    private int request(boolean interactive, String method) throws IOException {
        return gate.execute(interactive, () -> {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod(method);
                if ("GET".equals(method)) connection.setRequestProperty("Range", "bytes=0-0");
                return connection.getResponseCode();
            } finally { connection.disconnect(); }
        });
    }
}
