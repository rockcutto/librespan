package eu.siacs.conversations.ui.adapter;

import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.common.base.Strings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemContactBinding;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.ui.MucUsersActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** A single sectioned list, preserving MUC member actions while removing repeated role labels. */
public final class MucUsersGroupedAdapter
        extends ListAdapter<MucUsersGroupedAdapter.Row, RecyclerView.ViewHolder>
        implements View.OnCreateContextMenuListener {

    private static final int HEADER = 0;
    private static final int PERSON = 1;

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override
        public boolean areItemsTheSame(@NonNull Row left, @NonNull Row right) {
            if (left.section != null || right.section != null) {
                return left.section != null && left.section == right.section;
            }
            return UserAdapter.DIFF.areItemsTheSame(left.user, right.user);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Row left, @NonNull Row right) {
            if (left.section != null || right.section != null) {
                return left.section != null && left.section == right.section;
            }
            return UserAdapter.DIFF.areContentsTheSame(left.user, right.user);
        }
    };

    static final class Row {
        final MucUserGrouping.Section section;
        final MucOptions.User user;

        private Row(MucUserGrouping.Section section, MucOptions.User user) {
            this.section = section;
            this.user = user;
        }

        static Row header(MucUserGrouping.Section section) {
            return new Row(section, null);
        }

        static Row person(MucOptions.User user) {
            return new Row(null, user);
        }
    }

    private MucOptions.User selectedUser;

    public MucUsersGroupedAdapter() {
        super(DIFF);
    }

    public MucOptions.User getSelectedUser() {
        return selectedUser;
    }

    public void submitUsers(final List<MucOptions.User> users, final String search) {
        final String needle = search == null ? "" : search.toLowerCase(Locale.getDefault());
        final EnumMap<MucUserGrouping.Section, List<MucOptions.User>> groups =
                new EnumMap<>(MucUserGrouping.Section.class);
        for (MucUserGrouping.Section section : MucUserGrouping.Section.values()) {
            groups.put(section, new ArrayList<>());
        }
        for (MucOptions.User user : users) {
            if (!matches(user, needle)) {
                continue;
            }
            groups.get(MucUserGrouping.sectionFor(user.getAffiliation(), user.getRole()))
                    .add(user);
        }
        final List<Row> rows = new ArrayList<>();
        for (MucUserGrouping.Section section : MucUserGrouping.Section.values()) {
            final List<MucOptions.User> members = groups.get(section);
            if (members.isEmpty()) {
                continue;
            }
            members.sort((left, right) ->
                    left.getComparableName().compareToIgnoreCase(right.getComparableName()));
            rows.add(Row.header(section));
            for (MucOptions.User member : members) {
                rows.add(Row.person(member));
            }
        }
        submitList(rows);
    }

    private static boolean matches(final MucOptions.User user, final String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        final String name = user.getName();
        final Contact contact = user.getContact();
        return (name != null && name.toLowerCase(Locale.getDefault()).contains(needle))
                || (contact != null && contact.getDisplayName()
                        .toLowerCase(Locale.getDefault()).contains(needle));
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).section != null ? HEADER : PERSON;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_muc_users_section, parent, false));
        }
        return new PersonHolder(DataBindingUtil.inflate(
                inflater, R.layout.item_contact, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final Row row = getItem(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(sectionTitle(row.section));
        } else {
            bindPerson((PersonHolder) holder, row.user);
        }
    }

    @StringRes
    private static int sectionTitle(MucUserGrouping.Section section) {
        switch (section) {
            case OWNERS:
                return R.string.muc_users_owners;
            case ADMINS:
                return R.string.muc_users_admins;
            case MODERATORS:
                return R.string.muc_users_moderators;
            case VISITORS:
                return R.string.muc_users_visitors;
            case NOT_IN_ROOM:
                return R.string.muc_users_not_in_room;
            case BANNED:
                return R.string.muc_users_banned;
            default:
                return R.string.muc_users_participants;
        }
    }

    private void bindPerson(PersonHolder holder, MucOptions.User user) {
        final ItemContactBinding binding = holder.binding;
        AvatarWorkerTask.loadAvatar(user, binding.contactPhoto, R.dimen.avatar);
        final View root = binding.getRoot();
        root.setOnClickListener(v -> {
            final XmppActivity activity = XmppActivity.find(v);
            if (activity instanceof MucUsersActivity) {
                ((MucUsersActivity) activity).onUserClicked(v, user);
            }
        });
        root.setTag(user);
        root.setOnCreateContextMenuListener(this);
        root.setOnLongClickListener(v -> {
            selectedUser = user;
            return false;
        });

        final String nick = user.getName();
        final Contact contact = user.getContact();
        if (contact != null) {
            final String displayName = contact.getDisplayName();
            binding.contactDisplayName.setText(displayName);
            binding.presenceIndicator.setStatus(
                    MucUserGrouping.showContactPresence(user.getRole()) ? contact : null);
            if (nick != null && !nick.equals(displayName)) {
                binding.contactJid.setText(nick);
                binding.contactJid.setVisibility(View.VISIBLE);
            } else {
                binding.contactJid.setVisibility(View.GONE);
            }
        } else {
            binding.contactDisplayName.setText(Strings.nullToEmpty(nick));
            binding.contactJid.setVisibility(View.GONE);
            binding.presenceIndicator.setStatus(null);
        }
        binding.key.setVisibility(View.GONE);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        MucDetailsContextMenuHelper.onCreateContextMenu(menu, view);
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(View view) {
            super(view);
            title = (TextView) view;
            ViewCompat.setAccessibilityHeading(view, true);
        }
    }

    private static final class PersonHolder extends RecyclerView.ViewHolder {
        final ItemContactBinding binding;

        PersonHolder(ItemContactBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
