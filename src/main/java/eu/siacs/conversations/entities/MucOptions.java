package eu.siacs.conversations.entities;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.utils.JidHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.forms.Field;
import eu.siacs.conversations.xmpp.pep.Avatar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class MucOptions {

    public static final String STATUS_CODE_SELF_PRESENCE = "110";
    public static final String STATUS_CODE_ROOM_CREATED = "201";
    public static final String STATUS_CODE_BANNED = "301";
    public static final String STATUS_CODE_CHANGED_NICK = "303";
    public static final String STATUS_CODE_KICKED = "307";
    public static final String STATUS_CODE_AFFILIATION_CHANGE = "321";
    public static final String STATUS_CODE_LOST_MEMBERSHIP = "322";
    public static final String STATUS_CODE_SHUTDOWN = "332";
    public static final String STATUS_CODE_TECHNICAL_REASONS = "333";
    public static final long VOICE_REQUEST_COOLDOWN_MILLIS = 45_000L;
    private static final int MAX_OCCUPANT_ID_CODE_POINTS = 128;
    private final Set<User> users = new HashSet<>();
    private final Object affiliationListLock = new Object();
    private final Set<Jid> knownOwners = new HashSet<>();
    private final Set<Jid> knownAdmins = new HashSet<>();
    private boolean ownerAffiliationListKnown = false;
    private boolean adminAffiliationListKnown = false;
    private final Conversation conversation;
    public OnRenameListener onRenameListener = null;
    private boolean mAutoPushConfiguration = true;
    private final Account account;
    private ServiceDiscoveryResult serviceDiscoveryResult;
    private boolean isOnline = false;
    private Error error = Error.NONE;
    private User self;
    private long voiceRequestPendingSince = 0L;
    private String password = null;

    public MucOptions(final Conversation conversation) {
        this.account = conversation.getAccount();
        this.conversation = conversation;
        this.self = new User(this, createJoinJid(getProposedNick()));
        this.self.affiliation = Affiliation.of(conversation.getAttribute("affiliation"));
        this.self.role = Role.of(conversation.getAttribute("role"));
    }

    public Account getAccount() {
        return this.conversation.getAccount();
    }

    public boolean setSelf(final User user) {
        this.self = user;
        if (user.role.ranks(Role.PARTICIPANT)) {
            this.voiceRequestPendingSince = 0L;
        }
        final boolean roleChanged = this.conversation.setAttribute("role", user.role.toString());
        final boolean affiliationChanged =
                this.conversation.setAttribute("affiliation", user.affiliation.toString());
        return roleChanged || affiliationChanged;
    }

    public boolean isVoiceRequestPending() {
        if (voiceRequestPendingSince <= 0L) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - voiceRequestPendingSince
                >= VOICE_REQUEST_COOLDOWN_MILLIS) {
            voiceRequestPendingSince = 0L;
            return false;
        }
        return true;
    }

    public void setVoiceRequestPending(final boolean pending) {
        this.voiceRequestPendingSince = pending ? SystemClock.elapsedRealtime() : 0L;
    }

    public void changeAffiliation(final Jid jid, final Affiliation affiliation) {
        final Jid bare = jid == null ? null : jid.asBareJid();
        if (bare != null && bare.equals(account.getJid().asBareJid())) {
            self.setAffiliation(affiliation.toString());
            setSelf(self);
        }
        final User user = findUserByRealJid(bare);
        synchronized (users) {
            if (user != null && user.getRole() == Role.NONE) {
                users.remove(user);
                if (affiliation.ranks(Affiliation.MEMBER)) {
                    user.affiliation = affiliation;
                    users.add(user);
                }
            }
        }
    }

    public void beginOwnerAdminAffiliationRefresh() {
        synchronized (affiliationListLock) {
            ownerAffiliationListKnown = false;
            adminAffiliationListKnown = false;
            knownOwners.clear();
            knownAdmins.clear();
        }
    }

    public void applyAffiliationListSnapshot(
            final Affiliation affiliation, final Collection<User> snapshot) {
        if (affiliation != Affiliation.OWNER && affiliation != Affiliation.ADMIN) {
            return;
        }
        final Set<Jid> jids = new HashSet<>();
        if (snapshot != null) {
            for (final User user : snapshot) {
                final Jid realJid = user == null ? null : user.getRealJid();
                if (realJid != null) {
                    jids.add(realJid.asBareJid());
                }
            }
        }
        synchronized (affiliationListLock) {
            final Set<Jid> target =
                    affiliation == Affiliation.OWNER ? knownOwners : knownAdmins;
            target.clear();
            target.addAll(jids);
            if (affiliation == Affiliation.OWNER) {
                ownerAffiliationListKnown = true;
            } else {
                adminAffiliationListKnown = true;
            }
        }

        if (snapshot == null) {
            return;
        }
        final Jid ownBare = account.getJid().asBareJid();
        for (final User user : snapshot) {
            if (user == null || user.getRealJid() == null) {
                continue;
            }
            if (ownBare.equals(user.getRealJid().asBareJid())) {
                continue;
            }
            updateUser(user);
        }
    }

    public boolean isOwnerAffiliationListKnown() {
        synchronized (affiliationListLock) {
            return ownerAffiliationListKnown;
        }
    }

    public boolean isAdminAffiliationListKnown() {
        synchronized (affiliationListLock) {
            return adminAffiliationListKnown;
        }
    }

    public int getKnownOwnerCount() {
        synchronized (affiliationListLock) {
            return ownerAffiliationListKnown ? knownOwners.size() : 0;
        }
    }

    public boolean canSelfRevokeOwner() {
        synchronized (affiliationListLock) {
            return ownerAffiliationListKnown
                    && self.getAffiliation() == Affiliation.OWNER
                    && knownOwners.size() > 1
                    && knownOwners.contains(account.getJid().asBareJid());
        }
    }

    public void flagNoAutoPushConfiguration() {
        mAutoPushConfiguration = false;
    }

    public boolean autoPushConfiguration() {
        return mAutoPushConfiguration;
    }

    public boolean isSelf(final Jid counterpart) {
        return counterpart.equals(self.getFullJid());
    }

    public boolean isSelf(final String occupantId) {
        return occupantId != null && occupantId.equals(self.getOccupantId());
    }

    public void resetChatState() {
        synchronized (users) {
            for (User user : users) {
                user.chatState = Config.DEFAULT_CHAT_STATE;
            }
        }
    }

    public boolean mamSupport() {
        return MessageArchiveService.Version.has(getFeatures());
    }

    public boolean updateConfiguration(ServiceDiscoveryResult serviceDiscoveryResult) {
        this.serviceDiscoveryResult = serviceDiscoveryResult;
        String name;
        Field roomConfigName = getRoomInfoForm().getFieldByName("muc#roomconfig_roomname");
        if (roomConfigName != null) {
            name = roomConfigName.getValue();
        } else {
            final var identities = serviceDiscoveryResult.getIdentities();
            final String identityName = !identities.isEmpty() ? identities.get(0).getName() : null;
            final Jid jid = conversation.getJid();
            if (identityName != null && !identityName.equals(jid == null ? null : jid.getLocal())) {
                name = identityName;
            } else {
                name = null;
            }
        }
        boolean changed = conversation.setAttribute("muc_name", name);
        changed |=
                conversation.setAttribute(
                        Conversation.ATTRIBUTE_MEMBERS_ONLY, this.hasFeature("muc_membersonly"));
        changed |=
                conversation.setAttribute(
                        Conversation.ATTRIBUTE_MODERATED, this.hasFeature("muc_moderated"));
        changed |=
                conversation.setAttribute(
                        Conversation.ATTRIBUTE_NON_ANONYMOUS, this.hasFeature("muc_nonanonymous"));
        return changed;
    }

    private Data getRoomInfoForm() {
        final List<Data> forms =
                serviceDiscoveryResult == null
                        ? Collections.emptyList()
                        : serviceDiscoveryResult.forms;
        return forms.isEmpty() ? new Data() : forms.get(0);
    }

    public String getAvatar() {
        return account.getRoster().getContact(conversation.getJid()).getAvatarFilename();
    }

    public boolean hasFeature(String feature) {
        return this.serviceDiscoveryResult != null
                && this.serviceDiscoveryResult.features.contains(feature);
    }

    public boolean hasVCards() {
        return hasFeature("vcard-temp");
    }

    public boolean canInvite() {
        final boolean hasPermission =
                !membersOnly() || self.getRole().ranks(Role.MODERATOR) || allowInvites();
        return hasPermission && online();
    }

    public boolean allowInvites() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowinvites");
        return field != null && "1".equals(field.getValue());
    }

    public boolean canChangeSubject() {
        return online()
                && (self.getRole().ranks(Role.MODERATOR)
                        || (self.getRole().ranks(Role.PARTICIPANT)
                                && participantsCanChangeSubject()));
    }

    public boolean participantsCanChangeSubject() {
        final Field configField = getRoomInfoForm().getFieldByName("muc#roomconfig_changesubject");
        final Field infoField = getRoomInfoForm().getFieldByName("muc#roominfo_changesubject");
        final Field field = configField != null ? configField : infoField;
        return field != null && "1".equals(field.getValue());
    }

    public boolean allowPm() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowpm");
        if (field == null) {
            return true; // fall back if field does not exists
        }
        if ("anyone".equals(field.getValue())) {
            return true;
        } else if ("participants".equals(field.getValue())) {
            return self.getRole().ranks(Role.PARTICIPANT);
        } else if ("moderators".equals(field.getValue())) {
            return self.getRole().ranks(Role.MODERATOR);
        } else {
            return false;
        }
    }

    public boolean allowPmRaw() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowpm");
        return field == null || Arrays.asList("anyone", "participants").contains(field.getValue());
    }

    public boolean participating() {
        return self.getRole().ranks(Role.PARTICIPANT) || !moderated();
    }

    public boolean membersOnly() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MEMBERS_ONLY, false);
    }

    public List<String> getFeatures() {
        return this.serviceDiscoveryResult != null
                ? this.serviceDiscoveryResult.features
                : Collections.emptyList();
    }

    public boolean nonanonymous() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_NON_ANONYMOUS, false);
    }

    public boolean isPrivateAndNonAnonymous() {
        return membersOnly() && nonanonymous();
    }

    public boolean moderated() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MODERATED, false);
    }

    public boolean supportsMessageModeration() {
        return hasFeature(Namespace.MESSAGE_MODERATE);
    }

    public boolean stableId() {
        return getFeatures().contains("http://jabber.org/protocol/muc#stable_id");
    }

    public boolean occupantId() {
        final var features = getFeatures();
        return features.contains(Namespace.OCCUPANT_ID);
    }

    public static boolean isValidOccupantIdValue(@Nullable final String occupantId) {
        return occupantId != null
                && !occupantId.isEmpty()
                && occupantId.codePointCount(0, occupantId.length()) <= MAX_OCCUPANT_ID_CODE_POINTS;
    }

    @Nullable
    public String acceptedOccupantId(@Nullable final String occupantId) {
        return occupantId() && isValidOccupantIdValue(occupantId) ? occupantId : null;
    }

    @Nullable
    public User resolveUser(final Message message) {
        if (message == null || message.getConversation() != conversation) {
            return null;
        }
        return resolveUser(
                message.getOccupantId(),
                message.getTrueCounterpart(),
                message.getCounterpart());
    }

    @Nullable
    public User resolveUser(
            @Nullable final String occupantId,
            @Nullable final Jid trueCounterpart,
            @Nullable final Jid fullJid) {
        if (isValidOccupantIdValue(occupantId)) {
            final User byOccupantId = findUserByOccupantId(occupantId);
            if (byOccupantId != null) {
                return byOccupantId;
            }
        }
        if (trueCounterpart != null) {
            return findOrCreateUserByRealJid(trueCounterpart.asBareJid(), fullJid);
        }
        return fullJid == null ? null : findUserByFullJid(fullJid);
    }

    public User deleteUser(Jid jid) {
        User user = findUserByFullJid(jid);
        if (user != null) {
            synchronized (users) {
                users.remove(user);
                boolean realJidInMuc = false;
                for (User u : users) {
                    if (user.realJid != null && user.realJid.equals(u.realJid)) {
                        realJidInMuc = true;
                        break;
                    }
                }
                boolean self =
                        user.realJid != null && user.realJid.equals(account.getJid().asBareJid());
                if (membersOnly()
                        && nonanonymous()
                        && user.affiliation.ranks(Affiliation.MEMBER)
                        && user.realJid != null
                        && !realJidInMuc
                        && !self) {
                    user.role = Role.NONE;
                    user.avatar = null;
                    user.fullJid = null;
                    users.add(user);
                }
            }
        }
        return user;
    }

    // returns true if real jid was new;
    public boolean updateUser(User user) {
        User old;
        boolean realJidFound = false;

        if (isValidOccupantIdValue(user.occupantId)) {
            final User sameOccupant = findUserByOccupantId(user.occupantId);
            if (sameOccupant != null && sameOccupant != user) {
                realJidFound =
                        user.realJid != null
                                && sameOccupant.realJid != null
                                && user.realJid.equals(sameOccupant.realJid);
                synchronized (users) {
                    users.remove(sameOccupant);
                }
                if (user.avatar == null) {
                    user.avatar = sameOccupant.avatar;
                }
                user.chatState = sameOccupant.chatState;
            }
        }
        if (user.fullJid == null && user.realJid != null) {
            old = findUserByRealJid(user.realJid);
            realJidFound |= old != null;
            if (old != null) {
                if (old.fullJid != null) {
                    return false; // don't add. user already exists
                } else {
                    synchronized (users) {
                        users.remove(old);
                    }
                }
            }
        } else if (user.realJid != null) {
            old = findUserByRealJid(user.realJid);
            realJidFound |= old != null;
            synchronized (users) {
                if (old != null && (old.fullJid == null || old.role == Role.NONE)) {
                    users.remove(old);
                }
            }
        }
        old = findUserByFullJid(user.getFullJid());

        synchronized (this.users) {
            if (old != null) {
                users.remove(old);
            }
            boolean fullJidIsSelf =
                    isOnline
                            && user.getFullJid() != null
                            && user.getFullJid().equals(self.getFullJid());
            if ((!membersOnly() || user.getAffiliation().ranks(Affiliation.MEMBER))
                    && user.getAffiliation().outranks(Affiliation.OUTCAST)
                    && !fullJidIsSelf) {
                this.users.add(user);
                return !realJidFound && user.realJid != null;
            }
        }
        return false;
    }

    public User findUserByFullJid(Jid jid) {
        if (jid == null) {
            return null;
        }
        synchronized (users) {
            for (User user : users) {
                if (jid.equals(user.getFullJid())) {
                    return user;
                }
            }
        }
        return null;
    }

    public User findUserByRealJid(Jid jid) {
        if (jid == null) {
            return null;
        }
        synchronized (users) {
            for (User user : users) {
                if (jid.equals(user.realJid)) {
                    return user;
                }
            }
        }
        return null;
    }

    public User findUserByOccupantId(final String occupantId) {
        synchronized (this.users) {
            return !isValidOccupantIdValue(occupantId)
                    ? null
                    : Iterables.find(this.users, u -> occupantId.equals(u.occupantId), null);
        }
    }

    public User findOrCreateUserByRealJid(Jid jid, Jid fullJid) {
        final User existing = findUserByRealJid(jid);
        if (existing != null) {
            return existing;
        }
        final var user = new User(this, fullJid);
        user.setRealJid(jid);
        return user;
    }

    public User findUser(ReadByMarker readByMarker) {
        if (isValidOccupantIdValue(readByMarker.getOccupantId())) {
            final User byOccupantId = findUserByOccupantId(readByMarker.getOccupantId());
            if (byOccupantId != null) {
                return byOccupantId;
            }
        }
        if (readByMarker.getRealJid() != null) {
            return findOrCreateUserByRealJid(
                    readByMarker.getRealJid().asBareJid(), readByMarker.getFullJid());
        } else if (readByMarker.getFullJid() != null) {
            return findUserByFullJid(readByMarker.getFullJid());
        } else {
            return null;
        }
    }

    private User findUser(final Reaction reaction) {
        final var byOccupantId = findUserByOccupantId(reaction.occupantId);
        if (byOccupantId != null) {
            return byOccupantId;
        }
        if (reaction.trueJid != null) {
            return findOrCreateUserByRealJid(reaction.trueJid.asBareJid(), reaction.from);
        } else if (reaction.from != null) {
            return new User(this, reaction.from);
        } else {
            return null;
        }
    }

    public List<User> findUsers(final Collection<Reaction> reactions) {
        final ImmutableList.Builder<User> builder = new ImmutableList.Builder<>();
        for (final Reaction reaction : reactions) {
            final var user = findUser(reaction);
            if (user != null) {
                builder.add(user);
            }
        }
        return builder.build();
    }

    public boolean isContactInRoom(Contact contact) {
        return contact != null && findUserByRealJid(contact.getJid().asBareJid()) != null;
    }

    public boolean isUserInRoom(Jid jid) {
        return findUserByFullJid(jid) != null;
    }

    public boolean setOnline() {
        boolean before = this.isOnline;
        this.isOnline = true;
        return !before;
    }

    public ArrayList<User> getUsers() {
        return getUsers(true);
    }

    public ArrayList<User> getUsers(boolean includeOffline) {
        synchronized (users) {
            ArrayList<User> users = new ArrayList<>();
            for (User user : this.users) {
                if (!user.isDomain()
                        && (includeOffline || user.getRole().ranks(Role.PARTICIPANT))) {
                    users.add(user);
                }
            }
            return users;
        }
    }

    public ArrayList<User> getUsersWithChatState(ChatState state, int max) {
        synchronized (users) {
            ArrayList<User> list = new ArrayList<>();
            for (User user : users) {
                if (user.chatState == state) {
                    list.add(user);
                    if (list.size() >= max) {
                        break;
                    }
                }
            }
            return list;
        }
    }

    public List<User> getUsers(final int max) {
        final ArrayList<User> subset = new ArrayList<>();
        final HashSet<Jid> addresses = new HashSet<>();
        addresses.add(account.getJid().asBareJid());
        synchronized (users) {
            for (User user : users) {
                if (user.getRealJid() == null
                        || (user.getRealJid().getLocal() != null
                                && addresses.add(user.getRealJid()))) {
                    subset.add(user);
                }
                if (subset.size() >= max) {
                    break;
                }
            }
        }
        return subset;
    }

    public static List<User> sub(List<User> users, int max) {
        ArrayList<User> subset = new ArrayList<>();
        HashSet<Jid> jids = new HashSet<>();
        for (User user : users) {
            jids.add(user.getAccount().getJid().asBareJid());
            if (user.getRealJid() == null
                    || (user.getRealJid().getLocal() != null && jids.add(user.getRealJid()))) {
                subset.add(user);
            }
            if (subset.size() >= max) {
                break;
            }
        }
        return subset;
    }

    public int getUserCount() {
        synchronized (users) {
            return users.size();
        }
    }

    private String getProposedNick() {
        final Bookmark bookmark = this.conversation.getBookmark();
        if (bookmark != null) {
            // if we already have a bookmark we consider this the source of truth
            return getProposedNickPure();
        }
        final var storedJid = conversation.getJid();
        if (storedJid.isBareJid()) {
            return defaultNick(account);
        } else {
            return storedJid.getResource();
        }
    }

    public String getProposedNickPure() {
        final Bookmark bookmark = this.conversation.getBookmark();
        final String bookmarkedNick =
                normalize(account.getJid(), bookmark == null ? null : bookmark.getNick());
        if (bookmarkedNick != null) {
            return bookmarkedNick;
        } else {
            return defaultNick(account);
        }
    }

    public static String defaultNick(final Account account) {
        final String displayName = normalize(account.getJid(), account.getDisplayName());
        if (displayName == null) {
            return JidHelper.localPartOrFallback(account.getJid());
        } else {
            return displayName;
        }
    }

    private static String normalize(final Jid account, final String nick) {
        if (account == null || Strings.isNullOrEmpty(nick)) {
            return null;
        }
        try {
            return account.withResource(nick).getResource();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public String getActualNick() {
        if (this.self.getName() != null) {
            return this.self.getName();
        } else {
            return this.getProposedNick();
        }
    }

    public boolean online() {
        return this.isOnline;
    }

    public Error getError() {
        return this.error;
    }

    public void setError(Error error) {
        this.isOnline = isOnline && error == Error.NONE;
        this.error = error;
    }

    public void setOnRenameListener(OnRenameListener listener) {
        this.onRenameListener = listener;
    }

    public void setOffline() {
        synchronized (users) {
            this.users.clear();
        }
        setVoiceRequestPending(false);
        this.error = Error.NO_RESPONSE;
        this.isOnline = false;
    }

    public User getSelf() {
        return self;
    }

    public boolean setSubject(String subject) {
        if (!Objects.equals(getSubject(), subject)) {
            this.conversation.setAttribute("subjectTs", String.valueOf(System.currentTimeMillis()));
        }
        return this.conversation.setAttribute("subject", subject);
    }

    public String getSubject() {
        return this.conversation.getAttribute("subject");
    }

    public void hideSubject() {
        String subjectTs = this.conversation.getAttribute("subjectTs");

        if (subjectTs == null) {
            this.conversation.setAttribute("subjectTs", String.valueOf(System.currentTimeMillis() - 1));
        }

        this.conversation.setAttribute("subjectHideTs", String.valueOf(System.currentTimeMillis()));
    }

    public boolean subjectHidden() {
        String subjectTs = this.conversation.getAttribute("subjectTs");
        String hideTs = this.conversation.getAttribute("subjectHideTs");

        if (subjectTs == null || hideTs == null) {
            return false;
        } else {
            return Long.parseLong(hideTs) >= Long.parseLong(subjectTs);
        }
    }

    public String getName() {
        return this.conversation.getAttribute("muc_name");
    }

    private List<User> getFallbackUsersFromCryptoTargets() {
        List<User> users = new ArrayList<>();
        for (Jid jid : conversation.getAcceptedCryptoTargets()) {
            User user = new User(this, null);
            user.setRealJid(jid);
            users.add(user);
        }
        return users;
    }

    public List<User> getUsersRelevantForNameAndAvatar() {
        final List<User> users;
        if (isOnline) {
            users = getUsers(5);
        } else {
            users = getFallbackUsersFromCryptoTargets();
        }
        return users;
    }

    String createNameFromParticipants() {
        List<User> users = getUsersRelevantForNameAndAvatar();
        if (users.size() >= 2) {
            StringBuilder builder = new StringBuilder();
            for (User user : users) {
                if (builder.length() != 0) {
                    builder.append(", ");
                }
                String name = UIHelper.getDisplayName(user);
                if (name != null) {
                    builder.append(name.split("\\s+")[0]);
                }
            }
            return builder.toString();
        } else {
            return null;
        }
    }

    public Jid createJoinJid(String nick) {
        try {
            return conversation.getJid().withResource(nick);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public Jid getTrueCounterpart(Jid jid) {
        if (jid.equals(getSelf().getFullJid())) {
            return account.getJid().asBareJid();
        }
        User user = findUserByFullJid(jid);
        return user == null ? null : user.realJid;
    }

    public String getPassword() {
        if (this.password != null) {
            return this.password;
        }
        final Bookmark bookmark = conversation.getBookmark();
        return bookmark == null ? null : bookmark.getPassword();
    }

    public void setPassword(String password) {
        this.password = password;
        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark != null) {
            bookmark.setPassword(password);
        }
    }

    public Conversation getConversation() {
        return this.conversation;
    }

    public List<Jid> getMembers(final boolean includeDomains) {
        ArrayList<Jid> members = new ArrayList<>();
        synchronized (users) {
            for (User user : users) {
                if (user.affiliation.ranks(Affiliation.MEMBER)
                        && user.realJid != null
                        && !user.realJid
                                .asBareJid()
                                .equals(conversation.account.getJid().asBareJid())
                        && (!user.isDomain() || includeDomains)) {
                    members.add(user.realJid);
                }
            }
        }
        return members;
    }

    public enum Affiliation {
        OWNER(4, R.string.owner),
        ADMIN(3, R.string.admin),
        MEMBER(2, R.string.member),
        OUTCAST(0, R.string.outcast),
        NONE(1, R.string.no_affiliation);

        private final int resId;
        private final int rank;

        Affiliation(int rank, int resId) {
            this.resId = resId;
            this.rank = rank;
        }

        public static Affiliation of(@Nullable String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return Affiliation.valueOf(value.toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        public int getResId() {
            return resId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }

        public boolean outranks(Affiliation affiliation) {
            return rank > affiliation.rank;
        }

        public boolean ranks(Affiliation affiliation) {
            return rank >= affiliation.rank;
        }
    }

    public enum Role {
        MODERATOR(R.string.moderator, 3),
        VISITOR(R.string.visitor, 1),
        PARTICIPANT(R.string.participant, 2),
        NONE(R.string.no_role, 0);

        private final int resId;
        private final int rank;

        Role(int resId, int rank) {
            this.resId = resId;
            this.rank = rank;
        }

        public static Role of(@Nullable String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return Role.valueOf(value.toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        public int getResId() {
            return resId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }

        public boolean ranks(Role role) {
            return rank >= role.rank;
        }
    }

    public enum Error {
        NO_RESPONSE,
        SERVER_NOT_FOUND,
        REMOTE_SERVER_TIMEOUT,
        NONE,
        NICK_IN_USE,
        PASSWORD_REQUIRED,
        BANNED,
        MEMBERS_ONLY,
        RESOURCE_CONSTRAINT,
        KICKED,
        SHUTDOWN,
        DESTROYED,
        INVALID_NICK,
        TECHNICAL_PROBLEMS,
        UNKNOWN,
        NON_ANONYMOUS
    }

    private interface OnEventListener {
        void onSuccess();

        void onFailure();
    }

    public interface OnRenameListener extends OnEventListener {}

    public static class User implements Comparable<User>, AvatarService.Avatarable {
        private Role role = Role.NONE;
        private Affiliation affiliation = Affiliation.NONE;
        private Jid realJid;
        private Jid fullJid;
        private Avatar avatar;
        private final MucOptions options;
        private ChatState chatState = Config.DEFAULT_CHAT_STATE;
        private String occupantId;

        public User(MucOptions options, Jid fullJid) {
            this.options = options;
            this.fullJid = fullJid;
        }

        public String getName() {
            return fullJid == null ? null : fullJid.getResource();
        }

        public Role getRole() {
            return this.role;
        }

        public void setRole(String role) {
            this.role = Role.of(role);
        }

        public Affiliation getAffiliation() {
            return this.affiliation;
        }

        public void setAffiliation(String affiliation) {
            this.affiliation = Affiliation.of(affiliation);
        }

        public Contact getContact() {
            if (fullJid != null) {
                return getAccount().getRoster().getContactFromContactList(realJid);
            } else if (realJid != null) {
                return getAccount().getRoster().getContact(realJid);
            } else {
                return null;
            }
        }

        public boolean setAvatar(final Avatar avatar) {
            if (this.avatar != null && this.avatar.equals(avatar)) {
                return false;
            } else {
                this.avatar = avatar;
                return true;
            }
        }

        public String getAvatar() {
            if (avatar != null) {
                return avatar.getFilename();
            }
            Avatar avatar =
                    realJid != null
                            ? getAccount().getRoster().getContact(realJid).getAvatar()
                            : null;
            return avatar == null ? null : avatar.getFilename();
        }

        public Account getAccount() {
            return options.getAccount();
        }

        public Conversation getConversation() {
            return options.getConversation();
        }

        public Jid getFullJid() {
            return fullJid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            User user = (User) o;

            if (role != user.role) return false;
            if (affiliation != user.affiliation) return false;
            if (realJid != null ? !realJid.equals(user.realJid) : user.realJid != null)
                return false;
            return fullJid != null ? fullJid.equals(user.fullJid) : user.fullJid == null;
        }

        public boolean isDomain() {
            return realJid != null && realJid.getLocal() == null && role == Role.NONE;
        }

        @Override
        public int hashCode() {
            int result = role != null ? role.hashCode() : 0;
            result = 31 * result + (affiliation != null ? affiliation.hashCode() : 0);
            result = 31 * result + (realJid != null ? realJid.hashCode() : 0);
            result = 31 * result + (fullJid != null ? fullJid.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "[fulljid:"
                    + fullJid
                    + ",realjid:"
                    + realJid
                    + ",affiliation"
                    + affiliation.toString()
                    + "]";
        }

        public boolean realJidMatchesAccount() {
            return realJid != null && realJid.equals(options.account.getJid().asBareJid());
        }

        @Override
        public int compareTo(@NonNull User another) {
            if (another.getAffiliation().outranks(getAffiliation())) {
                return 1;
            } else if (getAffiliation().outranks(another.getAffiliation())) {
                return -1;
            } else {
                return getComparableName().compareToIgnoreCase(another.getComparableName());
            }
        }

        public String getComparableName() {
            Contact contact = getContact();
            if (contact != null) {
                return contact.getDisplayName();
            } else {
                String name = getName();
                return name == null ? "" : name;
            }
        }

        public Jid getRealJid() {
            return realJid;
        }

        public void setRealJid(Jid jid) {
            this.realJid = jid != null ? jid.asBareJid() : null;
        }

        public boolean setChatState(ChatState chatState) {
            if (this.chatState == chatState) {
                return false;
            }
            this.chatState = chatState;
            return true;
        }

        @Override
        public int getAvatarBackgroundColor() {
            final String seed =
                    realJid != null
                            ? realJid.asBareJid().toString()
                            : (occupantId != null ? occupantId : null);
            return UIHelper.getColorForName(seed == null ? getName() : seed);
        }

        @Override
        public String getAvatarName() {
            return getConversation().getName().toString();
        }

        public void setOccupantId(final String occupantId) {
            this.occupantId =
                    isValidOccupantIdValue(occupantId) ? occupantId : null;
        }

        public String getOccupantId() {
            return this.occupantId;
        }
    }
}
