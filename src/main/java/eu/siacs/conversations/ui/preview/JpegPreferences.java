package eu.siacs.conversations.ui.preview;

import android.content.Context;
import android.content.SharedPreferences;

/** Opt-in for every chat, including public rooms. No change to Message or conversation database. */
public final class JpegPreferences {
    private JpegPreferences() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("external_jpeg_previews",Context.MODE_PRIVATE);
    }
    public static boolean enabled(Context context,String chat) { return prefs(context).getBoolean(chat+".enabled",false); }
    public static void set(Context context,String chat,boolean enabled) {
        prefs(context).edit().putBoolean(chat+".enabled",enabled).apply();
    }
}
