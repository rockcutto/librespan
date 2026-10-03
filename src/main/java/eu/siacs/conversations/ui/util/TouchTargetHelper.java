package eu.siacs.conversations.ui.util;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/** Expands small visual controls to an accessible touch target without changing their geometry. */
public final class TouchTargetHelper {

    private TouchTargetHelper() {}

    public static void ensureMinTouchTargets(
            final ViewGroup parent,
            final int minSizePx,
            final View... children) {
        if (parent == null || children == null || children.length == 0) {
            return;
        }
        parent.post(
                () -> {
                    final TouchDelegateGroup group = new TouchDelegateGroup(parent);
                    for (final View child : children) {
                        if (child == null) {
                            continue;
                        }
                        final Rect bounds = new Rect();
                        child.getHitRect(bounds);
                        final int extraWidth = Math.max(0, minSizePx - bounds.width());
                        final int extraHeight = Math.max(0, minSizePx - bounds.height());
                        bounds.left -= extraWidth / 2;
                        bounds.right += extraWidth - (extraWidth / 2);
                        bounds.top -= extraHeight / 2;
                        bounds.bottom += extraHeight - (extraHeight / 2);
                        group.add(new TouchDelegate(bounds, child));
                    }
                    parent.setTouchDelegate(group);
                });
    }

    private static final class TouchDelegateGroup extends TouchDelegate {
        private final List<TouchDelegate> delegates = new ArrayList<>();

        TouchDelegateGroup(final View delegateView) {
            super(new Rect(), delegateView);
        }

        void add(final TouchDelegate delegate) {
            delegates.add(delegate);
        }

        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            for (final TouchDelegate delegate : delegates) {
                if (delegate.onTouchEvent(event)) {
                    return true;
                }
            }
            return false;
        }
    }
}
