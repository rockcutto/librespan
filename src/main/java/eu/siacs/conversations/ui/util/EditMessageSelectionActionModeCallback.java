package eu.siacs.conversations.ui.util;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Keeps Android's text selection lifecycle/handles alive while suppressing the floating
 * selection toolbar. LibreSpan renders its own compact Material text-actions surface.
 */
public final class EditMessageSelectionActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(final ActionMode mode, final Menu menu) {
        menu.clear();
        return true;
    }

    @Override
    public boolean onPrepareActionMode(final ActionMode mode, final Menu menu) {
        if (menu.size() != 0) {
            menu.clear();
            return true;
        }
        return false;
    }

    @Override
    public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
        return false;
    }

    @Override
    public void onDestroyActionMode(final ActionMode mode) {}
}
