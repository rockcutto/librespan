package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.StyledAttributes;

/**
 * Shared account chooser built on the app-wide adaptive bottom-sheet shell.
 *
 * <p>The account group is reusable inside richer sheets, so profile switching, destructive account
 * actions and key export all keep the same account identity hierarchy and selection treatment.
 */
public final class AccountChoiceBottomSheet {

    public interface Listener {
        void onAccountSelected(@NonNull Account account);
    }

    private AccountChoiceBottomSheet() {}

    public static void show(
            @NonNull final Activity activity,
            @NonNull final XmppConnectionService service,
            @StringRes final int titleRes,
            @NonNull final List<Account> accounts,
            @NonNull final Listener listener) {
        if (accounts.isEmpty() || activity.isFinishing()) {
            return;
        }

        final AdaptiveBottomSheet.Sheet sheet =
                AdaptiveBottomSheet.create(activity, titleRes);
        addAccountGroup(
                activity,
                service,
                sheet,
                accounts,
                null,
                account -> {
                    sheet.dismiss();
                    if (!activity.isFinishing()) {
                        listener.onAccountSelected(account);
                    }
                });
        sheet.show();
    }

    public static void addAccountGroup(
            @NonNull final Activity activity,
            @NonNull final XmppConnectionService service,
            @NonNull final AdaptiveBottomSheet.Sheet sheet,
            @NonNull final List<Account> accounts,
            @Nullable final Account selectedAccount,
            @NonNull final Listener listener) {
        final MaterialCardView group = new MaterialCardView(activity);
        group.setRadius(dp(activity, 20));
        group.setCardElevation(0f);
        group.setUseCompatPadding(false);
        group.setCardBackgroundColor(
                StyledAttributes.getColor(
                        activity,
                        com.google.android.material.R.attr.colorSurfaceContainerLow));
        group.setStrokeColor(
                ColorUtils.setAlphaComponent(
                        StyledAttributes.getColor(
                                activity,
                                com.google.android.material.R.attr.colorOutlineVariant),
                        112));
        group.setStrokeWidth(dp(activity, 1));

        final LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        group.addView(
                rows,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final int avatarSize = dp(activity, 44);
        for (int i = 0; i < accounts.size(); ++i) {
            final Account account = accounts.get(i);
            final boolean selected =
                    selectedAccount != null
                            && selectedAccount.getUuid().equals(account.getUuid());
            rows.addView(
                    createAccountRow(
                            activity,
                            service,
                            account,
                            selected,
                            avatarSize,
                            () -> listener.onAccountSelected(account)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            if (i + 1 < accounts.size()) {
                final View divider = new View(activity);
                divider.setBackgroundColor(
                        ColorUtils.setAlphaComponent(
                                StyledAttributes.getColor(
                                        activity,
                                        com.google.android.material.R.attr.colorOutlineVariant),
                                72));
                final LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Math.max(1, dp(activity, 1)));
                dividerParams.setMarginStart(dp(activity, 70));
                dividerParams.setMarginEnd(dp(activity, 12));
                rows.addView(divider, dividerParams);
            }
        }

        final LinearLayout.LayoutParams groupParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        groupParams.setMargins(dp(activity, 4), dp(activity, 2), dp(activity, 4), 0);
        sheet.getContent().addView(group, groupParams);
    }

    private static View createAccountRow(
            @NonNull final Activity activity,
            @NonNull final XmppConnectionService service,
            @NonNull final Account account,
            final boolean selected,
            final int avatarSize,
            @NonNull final Runnable action) {
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 76));
        row.setPadding(
                dp(activity, 12),
                dp(activity, 10),
                dp(activity, 12),
                dp(activity, 10));
        row.setClickable(true);
        row.setFocusable(true);

        final int primary =
                StyledAttributes.getColor(
                        activity, com.google.android.material.R.attr.colorPrimary);
        final int selectedSurface =
                selected
                        ? ColorUtils.setAlphaComponent(
                                StyledAttributes.getColor(
                                        activity,
                                        com.google.android.material.R.attr.colorPrimaryContainer),
                                74)
                        : Color.TRANSPARENT;
        row.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 30)),
                        new ColorDrawable(selectedSurface),
                        null));

        final MaterialCardView avatarCard = new MaterialCardView(activity);
        avatarCard.setRadius(avatarSize / 2f);
        avatarCard.setCardElevation(0f);
        avatarCard.setUseCompatPadding(false);
        avatarCard.setCardBackgroundColor(
                StyledAttributes.getColor(
                        activity,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
        avatarCard.setStrokeWidth(0);

        final ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageBitmap(service.getAvatarService().get(account, avatarSize));
        avatarCard.addView(
                avatar,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(
                avatarCard,
                new LinearLayout.LayoutParams(avatarSize, avatarSize));

        final LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(activity, 14));
        row.addView(text, textParams);

        final String bareJid = account.getJid().asBareJid().toString();
        final String displayName = account.getDisplayName();
        final boolean hasDisplayName = !TextUtils.isEmpty(displayName);

        final TextView title = new TextView(activity);
        title.setText(hasDisplayName ? displayName : bareJid);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(
                StyledAttributes.getColor(
                        activity, com.google.android.material.R.attr.colorOnSurface));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(
                title,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams metaParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(activity, 2);
        text.addView(meta, metaParams);

        if (hasDisplayName) {
            final TextView jid = new TextView(activity);
            jid.setText(bareJid);
            jid.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            jid.setTextColor(
                    StyledAttributes.getColor(
                            activity,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
            jid.setSingleLine(true);
            jid.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            final LinearLayout.LayoutParams jidParams =
                    new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            meta.addView(jid, jidParams);
        }

        final boolean online = account.getStatus() == Account.State.ONLINE;
        final View statusDot = new View(activity);
        final int dotColor =
                online
                        ? primary
                        : StyledAttributes.getColor(
                                activity,
                                com.google.android.material.R.attr.colorOutline);
        statusDot.setBackground(circleDrawable(dotColor));
        final LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(dp(activity, 6), dp(activity, 6));
        dotParams.setMarginStart(hasDisplayName ? dp(activity, 10) : 0);
        dotParams.setMarginEnd(dp(activity, 6));
        meta.addView(statusDot, dotParams);

        final TextView status = new TextView(activity);
        status.setText(account.getStatus().getReadableId());
        status.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        status.setTextColor(
                StyledAttributes.getColor(
                        activity,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
        status.setSingleLine(true);
        meta.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        if (selected) {
            final ImageView check = new ImageView(activity);
            check.setImageResource(R.drawable.ic_check_24dp);
            check.setImageTintList(ColorStateList.valueOf(primary));
            check.setContentDescription(null);
            final LinearLayout.LayoutParams checkParams =
                    new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24));
            checkParams.setMarginStart(dp(activity, 10));
            row.addView(check, checkParams);
        }

        row.setContentDescription(
                title.getText()
                        + ", "
                        + bareJid
                        + ", "
                        + status.getText());
        row.setOnClickListener(view -> action.run());
        return row;
    }

    private static GradientDrawable circleDrawable(final int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private static int dp(@NonNull final Activity activity, final int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
