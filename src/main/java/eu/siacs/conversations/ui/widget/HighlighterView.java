package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

public class HighlighterView extends View {

    private Runnable hideHighlight = () -> {
        if (getVisibility() == View.INVISIBLE) return;

        animate()
                .alpha(0.0f)
                .setInterpolator(new FastOutSlowInInterpolator())
                .setDuration(300L)
                .withEndAction(() -> setVisibility(View.INVISIBLE))
                .start();
    };

    private Handler handler = new Handler(Looper.getMainLooper());

    public HighlighterView(Context context) {
        super(context);
    }

    public HighlighterView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HighlighterView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public HighlighterView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        if (visibility != View.VISIBLE) {
            handler.removeCallbacks(hideHighlight);
            animate().cancel();
            setAlpha(0);
        } else {
            setAlpha(0.5f);

            handler.removeCallbacks(hideHighlight);
            handler.postDelayed(hideHighlight, 2000);
        }
    }
}
