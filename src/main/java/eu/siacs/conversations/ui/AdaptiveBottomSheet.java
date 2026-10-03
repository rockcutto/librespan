package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;

import eu.siacs.conversations.ui.util.StyledAttributes;

/**
 * Shared adaptive Material bottom-sheet shell for short contextual choices.
 *
 * <p>The shell intentionally owns only the presentation contract (padding, title hierarchy,
 * Material surface and motion). Feature code keeps its existing action/pipeline logic and can
 * either add arbitrary content to {@link Sheet#getContent()} or use {@link Sheet#addAction} for a
 * compact action list. {@link Sheet#getRoot()} is reserved for shell-level composition.</p>
 */
public final class AdaptiveBottomSheet {

    private AdaptiveBottomSheet() {}

    public static Sheet create(@NonNull final Activity activity, @StringRes final int titleRes) {
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 24));

        final LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView navigation = new ImageView(activity);
        navigation.setImageResource(eu.siacs.conversations.R.drawable.ic_arrow_back_24dp);
        navigation.setImageTintList(
                ColorStateList.valueOf(
                        StyledAttributes.getColor(
                                activity, com.google.android.material.R.attr.colorOnSurface)));
        navigation.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        navigation.setVisibility(View.GONE);
        final LinearLayout.LayoutParams navigationParams =
                new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40));
        navigationParams.setMarginEnd(dp(activity, 2));
        header.addView(navigation, navigationParams);

        final TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout.LayoutParams headerParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 10));
        root.addView(header, headerParams);

        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(
                content,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        return new Sheet(activity, dialog, root, content, title, navigation);
    }

    public static final class Sheet {
        private final Activity activity;
        private final BottomSheetDialog dialog;
        private final LinearLayout root;
        private final LinearLayout content;
        private final TextView title;
        private final ImageView navigation;

        private Sheet(
                @NonNull final Activity activity,
                @NonNull final BottomSheetDialog dialog,
                @NonNull final LinearLayout root,
                @NonNull final LinearLayout content,
                @NonNull final TextView title,
                @NonNull final ImageView navigation) {
            this.activity = activity;
            this.dialog = dialog;
            this.root = root;
            this.content = content;
            this.title = title;
            this.navigation = navigation;
        }

        public BottomSheetDialog getDialog() {
            return dialog;
        }

        public LinearLayout getRoot() {
            return root;
        }

        public LinearLayout getContent() {
            return content;
        }

        public void resetContent(@StringRes final int titleRes) {
            title.setText(titleRes);
            navigation.setVisibility(View.GONE);
            navigation.setOnClickListener(null);
            content.removeAllViews();
        }

        public void showBackAction(
                @StringRes final int contentDescriptionRes,
                @NonNull final Runnable action) {
            navigation.setContentDescription(activity.getString(contentDescriptionRes));
            navigation.setVisibility(View.VISIBLE);
            navigation.setOnClickListener(view -> action.run());
        }

        /**
         * Switch this sheet to a single-surface compact action list.
         *
         * <p>This intentionally differs from {@link #addAction}: the containing sheet owns the
         * surface, while actions are lightweight list rows separated by subtle inset dividers.
         * It is useful for dense attachment-style menus where nested cards create too much visual
         * weight.</p>
         */
        public void useCompactActionList() {
            content.setBackground(
                    roundedDrawable(
                            StyledAttributes.getColor(
                                    activity,
                                    com.google.android.material.R.attr.colorSurfaceContainerLow),
                            dp(activity, 18)));
            content.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        }

        public void addCompactAction(
                @DrawableRes final int iconRes,
                @StringRes final int labelRes,
                @NonNull final Runnable action) {
            if (content.getChildCount() > 0) {
                final View divider = new View(activity);
                final int outline =
                        StyledAttributes.getColor(
                                activity,
                                com.google.android.material.R.attr.colorOutlineVariant);
                divider.setBackgroundColor(ColorUtils.setAlphaComponent(outline, 112));
                final LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1));
                dividerParams.setMarginStart(dp(activity, 62));
                dividerParams.setMarginEnd(dp(activity, 12));
                content.addView(divider, dividerParams);
            }

            final int primary =
                    StyledAttributes.getColor(
                            activity, com.google.android.material.R.attr.colorPrimary);

            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(activity, 64));
            row.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 12), dp(activity, 4));
            row.setClickable(true);
            row.setFocusable(true);

            final GradientDrawable rippleMask = roundedDrawable(0xffffffff, dp(activity, 14));
            row.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)),
                            roundedDrawable(0x00000000, dp(activity, 14)),
                            rippleMask));

            final FrameLayout iconContainer = new FrameLayout(activity);
            iconContainer.setBackground(
                    roundedDrawable(
                            ColorUtils.setAlphaComponent(primary, 24),
                            dp(activity, 12)));
            final LinearLayout.LayoutParams iconContainerParams =
                    new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40));
            iconContainerParams.setMarginEnd(dp(activity, 14));
            row.addView(iconContainer, iconContainerParams);

            final ImageView icon = new ImageView(activity);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(primary));
            icon.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            iconContainer.addView(
                    icon,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

            final TextView label = new TextView(activity);
            label.setText(labelRes);
            label.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            label.setTextColor(
                    StyledAttributes.getColor(
                            activity, com.google.android.material.R.attr.colorOnSurface));
            row.addView(
                    label,
                    new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setContentDescription(activity.getString(labelRes));
            row.setOnClickListener(
                    view -> {
                        dialog.dismiss();
                        if (!activity.isFinishing()
                                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                                        || !activity.isDestroyed())) {
                            action.run();
                        }
                    });

            final LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            content.addView(row, rowParams);
        }

        public void addAction(
                @DrawableRes final int iconRes,
                @StringRes final int labelRes,
                @NonNull final Runnable action) {
            final MaterialCardView card = new MaterialCardView(activity);
            card.setRadius(dp(activity, 18));
            card.setCardElevation(0f);
            card.setUseCompatPadding(false);
            card.setClickable(true);
            card.setFocusable(true);

            final int primary =
                    StyledAttributes.getColor(
                            activity, com.google.android.material.R.attr.colorPrimary);
            card.setCardBackgroundColor(
                    StyledAttributes.getColor(
                            activity,
                            com.google.android.material.R.attr.colorSurfaceContainerLow));
            card.setRippleColor(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)));

            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(activity, 60));
            row.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 14), dp(activity, 6));

            final FrameLayout iconContainer = new FrameLayout(activity);
            iconContainer.setBackground(
                    roundedDrawable(
                            StyledAttributes.getColor(
                                    activity,
                                    com.google.android.material.R.attr.colorPrimaryContainer),
                            dp(activity, 14)));
            final LinearLayout.LayoutParams iconContainerParams =
                    new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40));
            iconContainerParams.setMarginEnd(dp(activity, 14));
            row.addView(iconContainer, iconContainerParams);

            final ImageView icon = new ImageView(activity);
            icon.setImageResource(iconRes);
            icon.setImageTintList(
                    ColorStateList.valueOf(
                            StyledAttributes.getColor(
                                    activity,
                                    com.google.android.material.R.attr.colorOnPrimaryContainer)));
            icon.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            final FrameLayout.LayoutParams iconParams =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
            iconContainer.addView(icon, iconParams);

            final TextView label = new TextView(activity);
            label.setText(labelRes);
            label.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            label.setTextColor(
                    StyledAttributes.getColor(
                            activity, com.google.android.material.R.attr.colorOnSurface));
            row.addView(
                    label,
                    new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.addView(
                    row,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setContentDescription(activity.getString(labelRes));
            card.setOnClickListener(
                    view -> {
                        dialog.dismiss();
                        if (!activity.isFinishing()
                                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                                        || !activity.isDestroyed())) {
                            action.run();
                        }
                    });

            final LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3));
            content.addView(card, cardParams);
        }

        public void show() {
            dialog.show();
        }

        public void dismiss() {
            dialog.dismiss();
        }
    }

    private static GradientDrawable roundedDrawable(final int color, final float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(@NonNull final Activity activity, final int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
