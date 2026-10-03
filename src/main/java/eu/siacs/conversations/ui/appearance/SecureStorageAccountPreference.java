package eu.siacs.conversations.ui.appearance;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import eu.siacs.conversations.R;

/** Read-only Material 3 row for one account's approximate committed SCS payload size. */
public final class SecureStorageAccountPreference extends Preference {

    private int accountColor;
    private CharSequence sizeLabel = "";

    public SecureStorageAccountPreference(final Context context) {
        this(context, null);
    }

    public SecureStorageAccountPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_scs_account_row);
        setSelectable(false);
    }

    public void setAccountColor(final int color) {
        accountColor = color;
        notifyChanged();
    }

    public void setSizeLabel(final CharSequence label) {
        sizeLabel = label == null ? "" : label;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View color = holder.findViewById(R.id.scs_account_color);
        final GradientDrawable marker = new GradientDrawable();
        marker.setShape(GradientDrawable.OVAL);
        marker.setColor(accountColor);
        color.setBackground(marker);

        final TextView size = (TextView) holder.findViewById(R.id.scs_account_size);
        size.setText(sizeLabel);
    }
}
