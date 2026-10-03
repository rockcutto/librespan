package eu.siacs.conversations.ui;

import java.util.Set;

import eu.siacs.conversations.xmpp.jingle.ContentAddition;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;

/**
 * Presentation-level call state.
 *
 * <p>The Jingle/WebRTC stack remains the source of truth. This enum deliberately collapses
 * transport/signaling details into the smaller set of states the call screen needs to render.
 */
enum CallUiState {
    INCOMING,
    OUTGOING,
    CONNECTING,
    ACTIVE_AUDIO,
    ADDING_VIDEO,
    INCOMING_VIDEO_OFFER,
    ACTIVE_VIDEO,
    RECONNECTING,
    ENDING,
    ENDED,
    FAILED;

    static CallUiState from(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        return switch (state) {
            case INCOMING_CALL, ACCEPTING_CALL -> INCOMING;
            case FINDING_DEVICE, RINGING -> OUTGOING;
            case CONNECTING -> CONNECTING;
            case CONNECTED -> connected(media, contentAddition);
            case INCOMING_CONTENT_ADD -> INCOMING_VIDEO_OFFER;
            case RECONNECTING -> RECONNECTING;
            case ENDING_CALL -> ENDING;
            case ENDED -> ENDED;
            case DECLINED_OR_BUSY,
                    CONTACT_OFFLINE,
                    CONNECTIVITY_ERROR,
                    CONNECTIVITY_LOST_ERROR,
                    RETRACTED,
                    APPLICATION_ERROR,
                    SECURITY_ERROR -> FAILED;
        };
    }

    private static CallUiState connected(
            final Set<Media> media, final ContentAddition contentAddition) {
        if (contentAddition != null
                && contentAddition.direction == ContentAddition.Direction.OUTGOING
                && contentAddition.media().contains(Media.VIDEO)) {
            return ADDING_VIDEO;
        }
        return media.contains(Media.VIDEO) ? ACTIVE_VIDEO : ACTIVE_AUDIO;
    }

    boolean isEstablished() {
        return this == ACTIVE_AUDIO
                || this == ADDING_VIDEO
                || this == INCOMING_VIDEO_OFFER
                || this == ACTIVE_VIDEO
                || this == RECONNECTING;
    }
}
