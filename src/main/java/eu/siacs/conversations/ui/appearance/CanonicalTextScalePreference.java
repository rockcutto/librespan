package eu.siacs.conversations.ui.appearance;

import android.content.Context;
import androidx.preference.ListPreference;
import android.util.AttributeSet;

/**
 * A ListPreference view adapter whose value is owned by AppearanceController.
 * Framework persistence is intentionally disabled; callers bind the canonical value explicitly.
 */
public final class CanonicalTextScalePreference extends ListPreference {
    public CanonicalTextScalePreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setCanonicalValue(final String value) {
        setValue(value);
    }

    @Override
    protected boolean persistString(final String value) {
        return true;
    }
}
