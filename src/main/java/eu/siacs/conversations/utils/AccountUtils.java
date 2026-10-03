package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AccountUtils {

    public static final Class<?> MANAGE_ACCOUNT_ACTIVITY;

    static {
        MANAGE_ACCOUNT_ACTIVITY = getManageAccountActivityClass();
    }

    public static boolean hasEnabledAccounts(final XmppConnectionService service) {
        final List<Account> accounts = service.getAccounts();
        for (Account account : accounts) {
            if (account.isOptionSet(Account.OPTION_DISABLED)) {
                return false;
            }
        }
        return false;
    }

    public static String publicDeviceId(final Account account, final long installationId) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(account.getUuid());
        } catch (final IllegalArgumentException e) {
            return account.getUuid();
        }
        return createUuid4(uuid.getMostSignificantBits(), installationId).toString();
    }

    public static UUID createUuid4(long mostSigBits, long leastSigBits) {
        final byte[] bytes =
                Bytes.concat(Longs.toByteArray(mostSigBits), Longs.toByteArray(leastSigBits));
        bytes[6] &= 0x0f; /* clear version        */
        bytes[6] |= 0x40; /* set to version 4     */
        bytes[8] &= 0x3f; /* clear variant        */
        bytes[8] |= 0x80; /* set to IETF variant  */
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        return new UUID(byteBuffer.getLong(), byteBuffer.getLong());
    }

    public static List<String> getEnabledAccounts(final XmppConnectionService service) {
        final ArrayList<String> accounts = new ArrayList<>();
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                accounts.add(account.getJid().asBareJid().toString());
            }
        }
        return accounts;
    }

    public static Account getFirstEnabled(XmppConnectionService service) {
        final List<Account> accounts = service.getAccounts();
        for (Account account : accounts) {
            if (!account.isOptionSet(Account.OPTION_DISABLED)) {
                return account;
            }
        }
        return null;
    }

    public static Account getFirst(XmppConnectionService service) {
        final List<Account> accounts = service.getAccounts();
        for (Account account : accounts) {
            return account;
        }
        return null;
    }

    public static Account getPendingAccount(XmppConnectionService service) {
        Account pending = null;
        for (Account account : service.getAccounts()) {
            if (!account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)) {
                pending = account;
            } else {
                return null;
            }
        }
        return pending;
    }

    public static Intent manageAccountsIntent(final Context context) {
        if (MANAGE_ACCOUNT_ACTIVITY == null) {
            throw new IllegalStateException("ManageAccountActivity is not available");
        }
        return new Intent(context, MANAGE_ACCOUNT_ACTIVITY);
    }

    public static void launchManageAccounts(final Activity activity) {
        if (MANAGE_ACCOUNT_ACTIVITY != null) {
            activity.startActivity(manageAccountsIntent(activity));
        } else {
            Toast.makeText(activity, R.string.feature_not_implemented, Toast.LENGTH_SHORT).show();
        }
    }

    private static Class<?> getManageAccountActivityClass() {
        try {
            return Class.forName("eu.siacs.conversations.ui.ManageAccountActivity");
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }

    public static void showHideMenuItems(final Menu menu) {
        final MenuItem manageAccounts = menu.findItem(R.id.action_accounts);
        if (manageAccounts != null) {
            manageAccounts.setVisible(MANAGE_ACCOUNT_ACTIVITY != null);
        }
    }
}
