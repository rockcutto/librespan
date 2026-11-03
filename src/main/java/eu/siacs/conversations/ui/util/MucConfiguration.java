package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.StringRes;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.MucRoomConfiguration;
import java.util.ArrayList;
import java.util.List;

public class MucConfiguration {

    public final @StringRes int title;
    public final String[] names;
    public final boolean[] values;
    public final Option[] options;

    private MucConfiguration(
            @StringRes int title, String[] names, boolean[] values, Option[] options) {
        this.title = title;
        this.names = names;
        this.values = values;
        this.options = options;
    }

    public static MucConfiguration get(Context context, boolean advanced, MucOptions mucOptions,
            MucRoomConfiguration form) {
        List<String> names = new ArrayList<>();
        List<Boolean> values = new ArrayList<>();
        List<Option> options = new ArrayList<>();
        add(context, form, names, values, options, MucRoomConfiguration.MEMBERS, R.string.muc_members_only_label);
        add(context, form, names, values, options, MucRoomConfiguration.PUBLIC, R.string.muc_public_room_label);
        add(context, form, names, values, options, MucRoomConfiguration.SUBJECT, R.string.allow_participants_to_edit_subject);
        add(context, form, names, values, options,
                form.supports(MucRoomConfiguration.INVITES) ? MucRoomConfiguration.INVITES
                        : MucRoomConfiguration.MEMBER_INVITES,
                R.string.allow_participants_to_invite_others);
        if (form.canChangeWhois(mucOptions)) {
            add(context, form, names, values, options, MucRoomConfiguration.WHOIS, R.string.non_anonymous);
        }
        if (advanced) {
            add(context, form, names, values, options, MucRoomConfiguration.MODERATED, R.string.moderated);
            add(context, form, names, values, options, MucRoomConfiguration.PM, R.string.allow_private_messages);
        }
        boolean[] checked = new boolean[values.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = values.get(i);
        return new MucConfiguration(R.string.conference_options, names.toArray(new String[0]), checked,
                options.toArray(new Option[0]));
    }

    private static void add(Context context, MucRoomConfiguration form, List<String> names,
            List<Boolean> values, List<Option> options, String name, int label) {
        if (!form.supports(name)) return;
        names.add(context.getString(label));
        values.add(form.enabled(name));
        options.add(MucRoomConfiguration.WHOIS.equals(name) || MucRoomConfiguration.PM.equals(name)
                ? new Option(name, "anyone", "moderators") : new Option(name));
    }

    public boolean isMembersOption(int index) {
        return MucRoomConfiguration.MEMBERS.equals(options[index].name);
    }

    public static String describe(final Context context, final MucOptions mucOptions) {
        final StringBuilder builder = new StringBuilder();
        if (mucOptions.isPrivateAndNonAnonymous()) {
            if (mucOptions.participantsCanChangeSubject()) {
                builder.append(context.getString(R.string.anyone_can_edit_subject));
            } else {
                builder.append(context.getString(R.string.owners_can_edit_subject));
            }
            builder.append(' ');
            if (mucOptions.allowInvites()) {
                builder.append(context.getString(R.string.anyone_can_invite_others));
            } else {
                builder.append(context.getString(R.string.owners_can_invite_others));
            }
        } else {
            if (mucOptions.nonanonymous()) {
                builder.append(context.getString(R.string.jabber_ids_are_visible_to_anyone));
            } else {
                builder.append(context.getString(R.string.jabber_ids_are_visible_to_admins));
            }
            builder.append(' ');
            if (mucOptions.participantsCanChangeSubject()) {
                builder.append(context.getString(R.string.anyone_can_edit_subject));
            } else {
                builder.append(context.getString(R.string.admins_can_edit_subject));
            }
        }
        return builder.toString();
    }

    public Bundle toBundle(boolean[] values) {
        Bundle bundle = new Bundle();
        for (int i = 0; i < values.length; ++i) {
            if (values[i] == this.values[i]) continue;
            final Option option = options[i];
            bundle.putString(option.name, option.values[values[i] ? 0 : 1]);
        }
        return bundle;
    }

    private static class Option {
        public final String name;
        public final String[] values;

        private Option(String name) {
            this.name = name;
            this.values = new String[] {"1", "0"};
        }

        private Option(String name, String on, String off) {
            this.name = name;
            this.values = new String[] {on, off};
        }
    }
}
