package eu.siacs.conversations.http;

import java.io.IOException;

/** Serializes durable publication with cancellation; transport teardown stays outside this lock. */
final class HttpUploadCompletion {
    private boolean terminal;

    @FunctionalInterface
    interface Publication {
        void persist() throws IOException;
    }

    synchronized boolean publish(final Publication publication) throws IOException {
        if (terminal) {
            return false;
        }
        publication.persist();
        terminal = true;
        return true;
    }

    synchronized boolean terminate() {
        if (terminal) {
            return false;
        }
        terminal = true;
        return true;
    }

    synchronized boolean isTerminal() {
        return terminal;
    }
}
