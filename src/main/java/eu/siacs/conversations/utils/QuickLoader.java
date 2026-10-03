/*
 * Copyright (c) 2014 - 2017 Daniel Gultsch
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import eu.siacs.conversations.entities.Conversation;

public class QuickLoader {

    private static final String PREFERENCES = "quick_loader_v2";
    private static final String PERSISTED_CONVERSATION_MARKER = "conversation_marker";
    private static String CONVERSATION_UUID = null;
    private static final Object LOCK = new Object();

    /** Process-local fast path retained for same-process navigation. */
    public static void set(final String uuid) {
        synchronized (LOCK) {
            CONVERSATION_UUID = uuid;
        }
    }

    /**
     * Records the currently presented conversation for process-death restore.
     *
     * Only a one-way SHA-256 marker is persisted; the conversation UUID itself remains process
     * local. The marker is sufficient because startup already owns the bounded list of candidate
     * conversations and can compare their hashes.
     */
    public static void set(final Context context, final String uuid) {
        set(uuid);
        if (context == null || uuid == null) {
            return;
        }
        preferences(context)
                .edit()
                .putString(PERSISTED_CONVERSATION_MARKER, marker(uuid))
                .apply();
    }

    /** Clears both the live target and the process-death hint when the overview becomes active. */
    public static void clear(final Context context) {
        synchronized (LOCK) {
            CONVERSATION_UUID = null;
        }
        if (context != null) {
            preferences(context).edit().remove(PERSISTED_CONVERSATION_MARKER).apply();
        }
    }

    public static boolean hasInMemoryTarget() {
        synchronized (LOCK) {
            return CONVERSATION_UUID != null;
        }
    }

    public static Conversation get(final Context context, final List<Conversation> haystack) {
        synchronized (LOCK) {
            if (CONVERSATION_UUID != null) {
                for (final Conversation conversation : haystack) {
                    if (conversation.getUuid().equals(CONVERSATION_UUID)) {
                        return conversation;
                    }
                }
            }

            if (context == null) {
                return null;
            }
            final String persistedMarker =
                    preferences(context).getString(PERSISTED_CONVERSATION_MARKER, null);
            if (persistedMarker == null) {
                return null;
            }
            for (final Conversation conversation : haystack) {
                if (persistedMarker.equals(marker(conversation.getUuid()))) {
                    CONVERSATION_UUID = conversation.getUuid();
                    return conversation;
                }
            }

            // A deleted/archived conversation must not keep causing startup work forever.
            preferences(context).edit().remove(PERSISTED_CONVERSATION_MARKER).apply();
            return null;
        }
    }

    public static Conversation get(final List<Conversation> haystack) {
        synchronized (LOCK) {
            if (CONVERSATION_UUID == null) {
                return null;
            }
            for (final Conversation conversation : haystack) {
                if (conversation.getUuid().equals(CONVERSATION_UUID)) {
                    return conversation;
                }
            }
        }
        return null;
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String marker(final String uuid) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(uuid.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder(digest.length * 2);
            for (final byte value : digest) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
