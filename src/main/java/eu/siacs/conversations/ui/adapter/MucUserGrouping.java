package eu.siacs.conversations.ui.adapter;

import eu.siacs.conversations.entities.MucOptions;

/** Presentation-only grouping. Permanent affiliation takes precedence over a session role. */
public final class MucUserGrouping {

    public enum Section {
        OWNERS,
        ADMINS,
        MODERATORS,
        PARTICIPANTS,
        VISITORS,
        NOT_IN_ROOM,
        BANNED
    }

    private MucUserGrouping() {}

    public static Section sectionFor(
            final MucOptions.Affiliation affiliation, final MucOptions.Role role) {
        if (affiliation == MucOptions.Affiliation.OUTCAST) {
            return Section.BANNED;
        }
        if (affiliation == MucOptions.Affiliation.OWNER) {
            return Section.OWNERS;
        }
        if (affiliation == MucOptions.Affiliation.ADMIN) {
            return Section.ADMINS;
        }
        if (role == MucOptions.Role.MODERATOR) {
            return Section.MODERATORS;
        }
        if (role == MucOptions.Role.VISITOR) {
            return Section.VISITORS;
        }
        if (affiliation == MucOptions.Affiliation.NONE && role == MucOptions.Role.NONE) {
            return Section.NOT_IN_ROOM;
        }
        return Section.PARTICIPANTS;
    }

    /** Contact roster presence must not be mistaken for an active MUC occupant. */
    public static boolean showContactPresence(final MucOptions.Role role) {
        return role != MucOptions.Role.NONE;
    }
}
