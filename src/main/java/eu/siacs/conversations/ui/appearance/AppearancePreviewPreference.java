package eu.siacs.conversations.ui.appearance;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.color.DynamicColors;

import eu.siacs.conversations.R;

/** A compact settings preview that reads the same current appearance snapshot as the UI. */
public final class AppearancePreviewPreference extends Preference {

    public AppearancePreviewPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setSelectable(false);
        setLayoutResource(R.layout.preference_appearance_preview);
    }

    public void refresh() {
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View card = holder.itemView;
        final TextView title = (TextView) holder.findViewById(R.id.appearance_preview_title);
        final TextView incoming =
                (TextView) holder.findViewById(R.id.appearance_preview_incoming);
        final TextView outgoing =
                (TextView) holder.findViewById(R.id.appearance_preview_outgoing);
        final TextView composer =
                (TextView) holder.findViewById(R.id.appearance_preview_composer);

        final AppearanceState state = AppearanceSnapshotReader.INSTANCE.from(getContext());
        final Context appearanceContext = appearanceContext(state);
        if (ChatWallpaperPresets.isCustomSelected(getContext())) {
            card.setBackground(
                    bubble(appearanceContext, android.R.attr.colorBackground, dp(20)));
        } else {
            final GradientDrawable wallpaper =
                    ChatWallpaperPresets.resolve(appearanceContext).drawable;
            wallpaper.setCornerRadius(dp(20));
            card.setBackground(wallpaper);
        }
        applyFixedTextColor(
                title,
                appearanceContext,
                com.google.android.material.R.attr.colorOnSurface);
        applyMessageBubble(
                incoming,
                R.attr.neoColorMessageIncomingSurface,
                com.google.android.material.R.attr.colorOnSurface,
                state,
                appearanceContext);
        if (state.getSettings().getColorfulChatBubbles()) {
            applyMessageBubble(
                    outgoing,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                    state,
                    appearanceContext);
        } else {
            applyMessageBubble(
                    outgoing,
                    R.attr.neoColorMessageIncomingElevatedSurface,
                    com.google.android.material.R.attr.colorOnSurface,
                    state,
                    appearanceContext);
        }
        applyFixedBubble(
                composer,
                R.attr.neoColorComposerSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                appearanceContext);
    }

    private void applyMessageBubble(
            final TextView view,
            final int colorAttribute,
            final int foregroundAttribute,
            final AppearanceState state,
            final Context appearanceContext) {
        view.setBackground(bubble(appearanceContext, colorAttribute, dp(16)));
        applyText(view, state, appearanceContext, foregroundAttribute);
    }

    private void applyFixedBubble(
            final TextView view,
            final int colorAttribute,
            final int foregroundAttribute,
            final Context appearanceContext) {
        view.setBackground(bubble(appearanceContext, colorAttribute, dp(16)));
        applyFixedTextColor(view, appearanceContext, foregroundAttribute);
    }

    private void applyText(
            final TextView view,
            final AppearanceState state,
            final Context appearanceContext,
            final int foregroundAttribute) {
        view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                state.getMessageTextSizeSp());
        view.setTextColor(color(appearanceContext, foregroundAttribute));
    }

    private void applyFixedTextColor(
            final TextView view,
            final Context appearanceContext,
            final int foregroundAttribute) {
        view.setTextColor(color(appearanceContext, foregroundAttribute));
    }

    private Context appearanceContext(final AppearanceState state) {
        final ContextThemeWrapper context =
                new ContextThemeWrapper(getContext(), state.getEffectiveThemeStyle());
        final Integer overrideStyle = state.getEffectiveThemeOverrideStyle();
        if (overrideStyle != null) {
            context.getTheme().applyStyle(overrideStyle, true);
        }
        return state.getDynamicColorsActive()
                ? DynamicColors.wrapContextIfAvailable(context)
                : context;
    }

    private GradientDrawable bubble(
            final Context appearanceContext, final int colorAttribute, final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(appearanceContext, colorAttribute));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int color(final Context appearanceContext, final int attribute) {
        final TypedValue value = new TypedValue();
        appearanceContext.getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId == 0
                ? value.data
                : ContextCompat.getColor(appearanceContext, value.resourceId);
    }

    private int dp(final int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
