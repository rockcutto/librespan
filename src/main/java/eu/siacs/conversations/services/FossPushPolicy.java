package eu.siacs.conversations.services;

import java.util.List;

/** Transport decisions shared by the Android receiver and its contract tests. */
public final class FossPushPolicy {
    private FossPushPolicy() {}

    public static boolean trustedExternal(final String selected, final String senderPackage) {
        return selected != null && !selected.isEmpty()
                && (senderPackage == null || selected.equals(senderPackage));
    }

    public static String failureReason(final String reason) {
        if ("NETWORK".equals(reason) || "ACTION_REQUIRED".equals(reason)
                || "VAPID_REQUIRED".equals(reason)) return reason;
        return "INTERNAL_ERROR";
    }

    public static boolean acceptRegistrationFailure(final boolean enabled,
            final String endpoint, final boolean knownToken) {
        return enabled && knownToken && (endpoint == null || endpoint.isEmpty());
    }

    public static void queueCancellation(final List<String> pending, final String token) {
        if (token != null && !token.isEmpty() && !pending.contains(token)) pending.add(token);
    }
}
