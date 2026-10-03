package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.Intent;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.ProfileActivity;
import eu.siacs.conversations.ui.StartConversationActivity;

/** Shared navigation for NeoCont's three root destinations. */
public final class RootNavigation {

    private RootNavigation() {}

    public static void configure(
            final Activity activity,
            final BottomNavigationView navigationView,
            final int selectedItemId) {
        navigationView.setSelectedItemId(selectedItemId);
        navigationView.setOnItemSelectedListener(
                item -> {
                    final int itemId = item.getItemId();
                    if (itemId == selectedItemId) {
                        return true;
                    }
                    final Intent intent;
                    if (itemId == R.id.chats) {
                        intent = new Intent(activity, ConversationsActivity.class);
                    } else if (itemId == R.id.contactslist) {
                        intent = new Intent(activity, StartConversationActivity.class);
                    } else if (itemId == R.id.manageaccounts) {
                        // Keep the legacy resource id for compatibility. This item is the profile
                        // destination now; account management lives inside ProfileActivity.
                        intent = new Intent(activity, ProfileActivity.class);
                    } else {
                        return false;
                    }
                    intent.putExtra("show_nav_bar", true);
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    activity.startActivity(intent);
                    activity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                });
        navigationView.setOnItemReselectedListener(item -> {});
    }
}
