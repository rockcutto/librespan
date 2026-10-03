package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TabLayout extends com.google.android.material.tabs.TabLayout {

    private VisibilityChangeListener listener = null;

    public TabLayout(@NonNull Context context) {
        super(context);
    }

    public TabLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TabLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setListener(VisibilityChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (listener != null) {
            listener.onVisibilityChanged(visibility);
        }
    }

    public interface VisibilityChangeListener {
        void onVisibilityChanged(int visibility);
    }
}
