package eu.siacs.conversations.http;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class HttpUploadCompletionTest {
    @Test public void durablePublicationPrecedesHandoffAndDuplicateCannotPublish() throws Exception {
        HttpUploadCompletion completion = new HttpUploadCompletion();
        List<String> events = new ArrayList<>();
        if (completion.publish(() -> events.add("durable"))) events.add("resend");
        assertFalse(completion.publish(() -> fail("duplicate publication")));
        assertFalse(completion.terminate());
        assertEquals(Arrays.asList("durable", "resend"), events);
    }

    @Test public void persistenceFailureNeverAuthorizesResend() throws Exception {
        HttpUploadCompletion completion = new HttpUploadCompletion();
        assertThrows(IOException.class, () -> completion.publish(() -> {
            throw new IOException("DB write rejected");
        }));
        assertFalse(completion.isTerminal());
        assertTrue(completion.terminate());
        assertFalse(completion.publish(() -> fail("failed handoff")));
    }

    @Test public void cancelBeforeSuccessDoesNotWriteFinalMetadata() throws Exception {
        HttpUploadCompletion completion = new HttpUploadCompletion();
        assertTrue(completion.terminate());
        assertFalse(completion.publish(() -> fail("cancelled callback")));
        assertFalse(completion.terminate());
    }

    @Test public void publicationAndCancellationHaveOneTerminalOwner() throws Exception {
        HttpUploadCompletion completion = new HttpUploadCompletion();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                assertTrue(completion.publish(() -> {
                    writing.countDown();
                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }));
            } catch (Throwable error) { failure.set(error); }
        });
        Thread cancel = new Thread(() -> {
            try { assertFalse(completion.terminate()); }
            catch (Throwable error) { failure.set(error); }
        });
        writer.start();
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            cancel.start();
        } finally { allowCommit.countDown(); }
        writer.join(5000); cancel.join(5000);
        assertFalse(writer.isAlive()); assertFalse(cancel.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
