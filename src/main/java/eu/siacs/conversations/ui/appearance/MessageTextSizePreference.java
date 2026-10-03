package eu.siacs.conversations.ui.appearance;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.slider.Slider;

import eu.siacs.conversations.R;

/** Material slider backed by the canonical continuous message text size in sp. */
public final class MessageTextSizePreference extends Preference {

    private float valueSp = TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP;

    public MessageTextSizePreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setSelectable(false);
        setLayoutResource(R.layout.preference_message_text_size);
    }

    public void setValueSp(final float value) {
        final float normalized = normalize(value);
        if (Math.abs(valueSp - normalized) < 0.01f) {
            return;
        }
        valueSp = normalized;
        notifyChanged();
    }

    public float getValueSp() {
        return valueSp;
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Slider slider = (Slider) holder.findViewById(R.id.message_text_size_slider);

        slider.clearOnChangeListeners();
        slider.setContentDescription(getContext().getString(R.string.pref_text_scale));
        slider.setLabelFormatter(candidate -> Integer.toString(Math.round(candidate)));
        slider.setValueFrom(TypographyPolicy.MIN_MESSAGE_TEXT_SP);
        slider.setValueTo(TypographyPolicy.MAX_MESSAGE_TEXT_SP);
        slider.setStepSize(1f);
        slider.setTickVisible(false);
        slider.setValue(valueSp);

        slider.addOnChangeListener(
                (control, candidate, fromUser) -> {
                    if (!fromUser) {
                        return;
                    }
                    final float normalized = normalize(candidate);
                    if (callChangeListener(normalized)) {
                        valueSp = normalized;
                    } else {
                        control.setValue(valueSp);
                    }
                });
    }


    private static float normalize(final float value) {
        return Math.round(
                Math.max(
                        TypographyPolicy.MIN_MESSAGE_TEXT_SP,
                        Math.min(TypographyPolicy.MAX_MESSAGE_TEXT_SP, value)));
    }
}
