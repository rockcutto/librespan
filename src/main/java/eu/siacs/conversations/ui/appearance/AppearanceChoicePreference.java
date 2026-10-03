package eu.siacs.conversations.ui.appearance;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import eu.siacs.conversations.R;

/** Compact navigation-like row with a persistent current value and chevron. */
public final class AppearanceChoicePreference extends Preference {

    private CharSequence valueLabel = "";
    private boolean choiceAvailable = true;

    public AppearanceChoicePreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_appearance_choice_row);
    }

    public void setValueLabel(final CharSequence value) {
        final CharSequence normalized = value == null ? "" : value;
        if (normalized.equals(valueLabel)) {
            return;
        }
        valueLabel = normalized;
        notifyChanged();
    }

    public void setValueLabel(@StringRes final int valueRes) {
        setValueLabel(getContext().getText(valueRes));
    }

    public void setChoiceAvailable(final boolean available) {
        if (choiceAvailable == available) {
            return;
        }
        choiceAvailable = available;
        setSelectable(available);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final TextView value =
                (TextView) holder.findViewById(R.id.appearance_choice_value);
        final ImageView chevron =
                (ImageView) holder.findViewById(R.id.appearance_choice_chevron);
        value.setText(valueLabel);
        chevron.setVisibility(choiceAvailable ? View.VISIBLE : View.GONE);
    }
}
