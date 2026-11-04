package eu.siacs.conversations.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Small durable transaction over the existing scoped vault; no password is kept in this object. */
public final class MucPasswordChange {
    public enum Reply { RESULT, ERROR, TIMEOUT }
    public enum Outcome { SUCCESS, REJECTED, UNCERTAIN, LOCKED, STORAGE_ERROR }

    public interface Vault {
        Record read() throws IOException;
        boolean write(Record record) throws IOException; // null removes the transaction
        boolean mucPassword(String password) throws IOException;
        boolean bookmarkPassword(String password) throws IOException;
        String previousPassword() throws IOException;
    }

    public static final class Record {
        public final boolean enabled;
        public final String password;
        public final boolean attempted;
        public final boolean disabledBefore;
        public final boolean disabledConfirmed;
        public final boolean manualSelection;
        public final String previousPassword;

        public Record(boolean enabled, String password, boolean attempted,
                boolean disabledBefore, boolean disabledConfirmed) {
            this(enabled, password, attempted, disabledBefore, disabledConfirmed, false);
        }

        public Record(boolean enabled, String password, boolean attempted,
                boolean disabledBefore, boolean disabledConfirmed, boolean manualSelection) {
            this(enabled, password, attempted, disabledBefore, disabledConfirmed, manualSelection, null);
        }

        private Record(boolean enabled, String password, boolean attempted,
                boolean disabledBefore, boolean disabledConfirmed, boolean manualSelection, String previousPassword) {
            if ((enabled && !validPassword(password)) || (!enabled && password != null)
                    || (disabledConfirmed && (enabled || attempted || !disabledBefore))) {
                throw new IllegalArgumentException("Invalid room password operation");
            }
            this.enabled = enabled;
            this.password = password;
            this.attempted = attempted;
            this.disabledBefore = disabledBefore;
            this.disabledConfirmed = disabledConfirmed;
            this.manualSelection = manualSelection;
            this.previousPassword = previousPassword;
        }

        public boolean pending() { return !disabledConfirmed; }
        public static Record disabled() { return new Record(false, null, false, true, true); }
        public Record manual() { return new Record(enabled, password, attempted, disabledBefore, disabledConfirmed, true, previousPassword); }
        public Record sent() { return new Record(enabled, password, true, disabledBefore, false, false, previousPassword); }
        public Record withPrevious(String value) { return new Record(enabled, password, attempted, disabledBefore, disabledConfirmed, manualSelection, value); }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(1);
                output.writeBoolean(enabled);
                output.writeBoolean(attempted);
                output.writeBoolean(disabledBefore);
                output.writeBoolean(disabledConfirmed);
                output.writeBoolean(manualSelection);
                if (enabled) output.writeUTF(password);
                output.writeBoolean(previousPassword != null);
                if (previousPassword != null) output.writeUTF(previousPassword);
            }
            return buffer.toByteArray();
        }

        public static Record decode(byte[] bytes) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != 1) throw new IOException("Unknown room password operation");
                boolean enabled = input.readBoolean();
                boolean attempted = input.readBoolean();
                boolean disabledBefore = input.readBoolean();
                boolean disabledConfirmed = input.readBoolean();
                boolean manualSelection = input.readBoolean();
                String password = enabled ? input.readUTF() : null;
                String previous = input.readBoolean() ? input.readUTF() : null;
                Record record = new Record(enabled, password,
                        attempted, disabledBefore, disabledConfirmed, manualSelection, previous);
                if (input.available() != 0) throw new IOException("Invalid room password operation");
                return record;
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid room password operation");
            }
        }
    }

    private final Vault vault;
    private final BooleanSupplier currentSession;
    private boolean retry;

    public MucPasswordChange(Vault vault, BooleanSupplier currentSession) {
        this.vault = vault;
        this.currentSession = currentSession;
    }

    public static boolean validPassword(String password) {
        if (password == null || password.isEmpty() || password.length() > 1024) return false;
        boolean visible = false;
        for (int i = 0; i < password.length(); ) {
            int cp = password.codePointAt(i);
            if (Character.isISOControl(cp) || (cp >= 0xd800 && cp <= 0xdfff)
                    || cp == 0xfffe || cp == 0xffff) return false;
            visible |= !Character.isWhitespace(cp);
            i += Character.charCount(cp);
        }
        return visible; // Do not trim or otherwise change an opaque XMPP password.
    }

    /** Stage before any remote mutation. A failed write never authorizes SET. */
    public boolean prepare(boolean enabled, String password, boolean retryPending) throws IOException {
        if (!currentSession.getAsBoolean()) return false;
        Record existing = vault.read();
        if (retryPending) {
            retry = existing != null && existing.attempted;
            return existing != null && existing.pending();
        }
        if (existing != null && existing.pending()) return false;
        Record proposed = new Record(enabled, enabled ? password : null, false,
                existing != null && existing.disabledConfirmed, false);
        proposed = proposed.withPrevious(proposed.disabledBefore ? null : vault.previousPassword());
        return vault.write(proposed) && currentSession.getAsBoolean();
    }

    /** Durable send intent precedes the SET; a crash can conservatively leave uncertainty. */
    public Record markSent() throws IOException {
        if (!currentSession.getAsBoolean()) return null;
        Record record = vault.read();
        if (record == null || !record.pending()) return null;
        Record sent = record.sent();
        return vault.write(sent) && currentSession.getAsBoolean() ? sent : null;
    }

    public boolean rollbackUnsent() throws IOException {
        if (!currentSession.getAsBoolean()) return false;
        Record record = vault.read();
        if (record == null || !record.pending() || record.attempted) return false;
        return vault.write(record.disabledBefore ? Record.disabled() : null);
    }

    public Outcome finish(Reply reply) {
        if (!currentSession.getAsBoolean()) return Outcome.LOCKED;
        try {
            Record record = vault.read();
            if (record == null || !record.pending() || !record.attempted) return Outcome.STORAGE_ERROR;
            if (reply == Reply.TIMEOUT) return Outcome.UNCERTAIN;
            if (reply == Reply.ERROR) {
                // A retry error cannot disprove an earlier timed-out successful SET.
                if (retry) return Outcome.UNCERTAIN;
                return vault.write(record.disabledBefore ? Record.disabled() : null)
                        ? Outcome.REJECTED : Outcome.STORAGE_ERROR;
            }
            String password = record.enabled ? record.password : null;
            if (!vault.mucPassword(password) || !currentSession.getAsBoolean()
                    || !vault.bookmarkPassword(password) || !currentSession.getAsBoolean()
                    || !vault.write(record.enabled ? null : Record.disabled())) {
                return Outcome.UNCERTAIN; // Keep durable candidate after partial local completion.
            }
            return currentSession.getAsBoolean() ? Outcome.SUCCESS : Outcome.LOCKED;
        } catch (IOException error) {
            return Outcome.UNCERTAIN;
        }
    }
}
