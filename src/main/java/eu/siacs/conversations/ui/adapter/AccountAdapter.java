package eu.siacs.conversations.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.kizitonwose.colorpreference.ColorShape;
import com.kizitonwose.colorpreference.ColorUtils;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemAccountBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class AccountAdapter extends ArrayAdapter<Account> {

    private final XmppActivity activity;
    private final boolean showStateButton;
    private final boolean showColorSelector;
    private final boolean neoManageMode;

    public ColorSelectorListener colorSelectorListener = null;
    public AccountActionListener accountActionListener = null;

    public AccountAdapter(XmppActivity activity, List<Account> objects, boolean showStateButton) {
        super(activity, 0, objects);
        this.activity = activity;
        this.showStateButton = showStateButton;
        this.showColorSelector = false;
        this.neoManageMode = false;
    }

    public AccountAdapter(
            XmppActivity activity,
            List<Account> objects,
            ColorSelectorListener listener,
            AccountActionListener accountActionListener) {
        super(activity, 0, objects);
        this.activity = activity;
        this.showStateButton = true;
        this.showColorSelector = true;
        this.neoManageMode = true;
        this.colorSelectorListener = listener;
        this.accountActionListener = accountActionListener;
    }

    public AccountAdapter(XmppActivity activity, List<Account> objects, ColorSelectorListener listener) {
        super(activity, 0, objects);
        this.activity = activity;
        this.showStateButton = true;
        this.showColorSelector = true;
        this.neoManageMode = false;
        colorSelectorListener = listener;
    }

    @NonNull
    @Override
    public View getView(int position, View view, @NonNull ViewGroup parent) {
        final Account account = getItem(position);
        if (neoManageMode) {
            return getNeoManageView(account, view, parent);
        }

        final ViewHolder viewHolder;
        if (view == null || !(view.getTag() instanceof ViewHolder)) {
            ItemAccountBinding binding =
                    DataBindingUtil.inflate(
                            LayoutInflater.from(parent.getContext()),
                            R.layout.item_account,
                            parent,
                            false);
            view = binding.getRoot();
            viewHolder = new ViewHolder(binding);
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
        }
        viewHolder.binding.accountJid.setText(account.getJid().asBareJid().toString());
        AvatarWorkerTask.loadAvatar(account, viewHolder.binding.accountImage, R.dimen.avatar);
        bindStatus(viewHolder.binding.accountStatus, account);

        final boolean isDisabled = (account.getStatus() == Account.State.DISABLED);
        viewHolder.binding.tglAccountStatus.setOnCheckedChangeListener(null);
        viewHolder.binding.tglAccountStatus.setChecked(!isDisabled);
        viewHolder.binding.tglAccountStatus.setVisibility(
                this.showStateButton ? View.VISIBLE : View.GONE);
        viewHolder.binding.tglAccountStatus.setOnCheckedChangeListener(
                (compoundButton, enabled) -> {
                    if (enabled == isDisabled && activity instanceof OnTglAccountState) {
                        ((OnTglAccountState) activity).onClickTglAccountState(account, enabled);
                    }
                });

        if (this.showColorSelector
                && activity.xmppConnectionService.getAccounts().size() > 1
                && activity.xmppConnectionService
                        .getPreferences()
                        .getBoolean(
                                "show_account_indicator",
                                activity.getResources().getBoolean(R.bool.show_account_indicator))) {
            final int color = UIHelper.getAccountColor(activity, account.getJid());
            viewHolder.binding.colorView.setVisibility(View.VISIBLE);
            ColorUtils.setColorViewValue(
                    viewHolder.binding.colorView, color, false, ColorShape.CIRCLE);
            viewHolder.binding.colorView.setOnClickListener(
                    v -> requestColor(account.getJid(), color));
        } else {
            viewHolder.binding.colorView.setVisibility(View.GONE);
        }
        return view;
    }

    private View getNeoManageView(
            final Account account, final View recycled, final ViewGroup parent) {
        final View view;
        final NeoManageViewHolder holder;
        if (recycled == null || !(recycled.getTag() instanceof NeoManageViewHolder)) {
            view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_manage_account, parent, false);
            holder = new NeoManageViewHolder(view);
            view.setTag(holder);
        } else {
            view = recycled;
            holder = (NeoManageViewHolder) view.getTag();
        }

        final String displayName = account.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            holder.displayName.setText(account.getJid().asBareJid().toString());
            holder.jid.setVisibility(View.GONE);
        } else {
            holder.displayName.setText(displayName);
            holder.jid.setText(account.getJid().asBareJid().toString());
            holder.jid.setVisibility(View.VISIBLE);
        }

        AvatarWorkerTask.loadAvatar(account, holder.avatar, R.dimen.avatar);
        bindStatus(holder.status, account);

        final boolean isDisabled = account.getStatus() == Account.State.DISABLED;
        holder.enabled.setOnCheckedChangeListener(null);
        holder.enabled.setChecked(!isDisabled);
        holder.enabled.setContentDescription(
                account.getJid().asBareJid()
                        + " · "
                        + getContext().getString(
                                isDisabled
                                        ? R.string.mgmt_account_enable
                                        : R.string.mgmt_account_disable));
        holder.enabled.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (enabled == isDisabled && activity instanceof OnTglAccountState) {
                        ((OnTglAccountState) activity).onClickTglAccountState(account, enabled);
                    }
                });

        final boolean showColor =
                showColorSelector && activity.xmppConnectionService.getAccounts().size() > 1;
        holder.colorTouchTarget.setVisibility(showColor ? View.VISIBLE : View.GONE);
        holder.accountColorCircle.setVisibility(showColor ? View.VISIBLE : View.GONE);
        if (showColor) {
            final int color = UIHelper.getAccountColor(activity, account.getJid());
            setAccountColorDecoration(holder.color, color, false);
            setAccountColorDecoration(holder.accountColorCircle, color, true);
            holder.colorTouchTarget.setContentDescription(
                    getContext().getString(R.string.neocont_account_color_accessibility)
                            + " "
                            + account.getJid().asBareJid());
            holder.colorTouchTarget.setOnClickListener(
                    v -> requestColor(account.getJid(), color));
        } else {
            holder.colorTouchTarget.setOnClickListener(null);
        }

        holder.more.setOnClickListener(
                v -> {
                    if (accountActionListener != null) {
                        accountActionListener.onAccountActionsRequested(account, v);
                    }
                });

        return view;
    }

    private static void setAccountColorDecoration(
            final View view, final int color, final boolean circle) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!circle) {
            drawable.setCornerRadius(view.getResources().getDisplayMetrics().density * 99f);
        }
        drawable.setColor(color);
        view.setBackground(drawable);
    }

    private void bindStatus(final TextView statusView, final Account account) {
        statusView.setText(getContext().getString(account.getStatus().getReadableId()));
        switch (account.getStatus()) {
            case ONLINE:
                statusView.setTextColor(
                        MaterialColors.getColor(
                                statusView,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));
                break;
            case DISABLED:
            case LOGGED_OUT:
            case CONNECTING:
                statusView.setTextColor(
                        MaterialColors.getColor(
                                statusView,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));
                break;
            default:
                statusView.setTextColor(
                        MaterialColors.getColor(
                                statusView, androidx.appcompat.R.attr.colorError));
                break;
        }
    }

    private void requestColor(final Jid jid, final int color) {
        if (colorSelectorListener != null) {
            colorSelectorListener.onColorPickerRequested(jid, color);
        }
    }

    private static class NeoManageViewHolder {
        private final eu.siacs.conversations.ui.widget.AvatarView avatar;
        private final View accountColorCircle;
        private final TextView displayName;
        private final TextView jid;
        private final TextView status;
        private final View colorTouchTarget;
        private final View color;
        private final SwitchCompat enabled;
        private final ImageButton more;

        private NeoManageViewHolder(final View view) {
            avatar = view.findViewById(R.id.account_image);
            accountColorCircle = view.findViewById(R.id.account_color_circle);
            displayName = view.findViewById(R.id.account_display_name);
            jid = view.findViewById(R.id.account_jid);
            status = view.findViewById(R.id.account_status);
            colorTouchTarget = view.findViewById(R.id.color_touch_target);
            color = view.findViewById(R.id.color_view);
            enabled = view.findViewById(R.id.tgl_account_status);
            more = view.findViewById(R.id.account_more);
        }
    }

    private static class ViewHolder {
        private final ItemAccountBinding binding;

        private ViewHolder(ItemAccountBinding binding) {
            this.binding = binding;
        }
    }

    public interface OnTglAccountState {
        void onClickTglAccountState(Account account, boolean state);
    }

    public interface ColorSelectorListener {
        void onColorPickerRequested(Jid accountJid, int currentColor);
    }

    public interface AccountActionListener {
        void onAccountActionsRequested(Account account, View anchor);
    }
}
