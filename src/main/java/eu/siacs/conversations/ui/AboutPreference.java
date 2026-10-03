package eu.siacs.conversations.ui;

import android.content.Context;
import androidx.preference.Preference;
import android.util.AttributeSet;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;

public class AboutPreference extends Preference {
    public AboutPreference(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        setSummaryAndTitle(context);
        setSelectable(false);
    }

    public AboutPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setSummaryAndTitle(context);
        setSelectable(false);
    }

    private void setSummaryAndTitle(final Context context) {
        setTitle(BuildConfig.APP_NAME);
        setSummary(
                context.getString(
                        R.string.neocont_about_card_summary,
                        BuildConfig.VERSION_NAME));
    }

}
