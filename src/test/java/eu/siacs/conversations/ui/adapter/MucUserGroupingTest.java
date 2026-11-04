package eu.siacs.conversations.ui.adapter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import eu.siacs.conversations.entities.MucOptions.Affiliation;
import eu.siacs.conversations.entities.MucOptions.Role;
import org.junit.Test;

public class MucUserGroupingTest {

    @Test
    public void sectionOrderIsStable() {
        assertArrayEquals(
                new MucUserGrouping.Section[] {
                    MucUserGrouping.Section.OWNERS,
                    MucUserGrouping.Section.ADMINS,
                    MucUserGrouping.Section.MODERATORS,
                    MucUserGrouping.Section.PARTICIPANTS,
                    MucUserGrouping.Section.VISITORS,
                    MucUserGrouping.Section.NOT_IN_ROOM,
                    MucUserGrouping.Section.BANNED
                },
                MucUserGrouping.Section.values());
    }

    @Test
    public void ownersRemainOwnersEvenWhenModerators() {
        assertEquals(MucUserGrouping.Section.OWNERS,
                MucUserGrouping.sectionFor(Affiliation.OWNER, Role.MODERATOR));
    }

    @Test
    public void adminsRemainAdminsEvenWhenModerators() {
        assertEquals(MucUserGrouping.Section.ADMINS,
                MucUserGrouping.sectionFor(Affiliation.ADMIN, Role.MODERATOR));
    }

    @Test
    public void moderatorsHaveTheirOwnSectionWithoutPermanentAdminRights() {
        assertEquals(MucUserGrouping.Section.MODERATORS,
                MucUserGrouping.sectionFor(Affiliation.MEMBER, Role.MODERATOR));
        assertEquals(MucUserGrouping.Section.MODERATORS,
                MucUserGrouping.sectionFor(Affiliation.NONE, Role.MODERATOR));
    }

    @Test
    public void membersStayParticipantsWhenOfflineOrOnline() {
        assertEquals(MucUserGrouping.Section.PARTICIPANTS,
                MucUserGrouping.sectionFor(Affiliation.MEMBER, Role.NONE));
        assertEquals(MucUserGrouping.Section.PARTICIPANTS,
                MucUserGrouping.sectionFor(Affiliation.MEMBER, Role.PARTICIPANT));
    }

    @Test
    public void visitorsAreNotMislabelledAsParticipants() {
        assertEquals(MucUserGrouping.Section.VISITORS,
                MucUserGrouping.sectionFor(Affiliation.NONE, Role.VISITOR));
        assertEquals(MucUserGrouping.Section.VISITORS,
                MucUserGrouping.sectionFor(Affiliation.MEMBER, Role.VISITOR));
    }
    @Test
    public void rosterPresenceIsNotDisplayedForUsersOutsideRoom() {
        assertEquals(false, MucUserGrouping.showContactPresence(Role.NONE));
        assertEquals(true, MucUserGrouping.showContactPresence(Role.PARTICIPANT));
        assertEquals(true, MucUserGrouping.showContactPresence(Role.MODERATOR));
    }

    @Test
    public void blockedUsersAreNotMislabelledAsParticipants() {
        assertEquals(MucUserGrouping.Section.BANNED,
                MucUserGrouping.sectionFor(Affiliation.OUTCAST, Role.NONE));
    }

    @Test
    public void unaffiliatedOfflineUsersAreNotMislabelledAsMembers() {
        assertEquals(MucUserGrouping.Section.NOT_IN_ROOM,
                MucUserGrouping.sectionFor(Affiliation.NONE, Role.NONE));
    }
}
