/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.snackbar.Snackbar;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.SettingsActivity;

public class ThemeHelper {
	public static void applyMaterialColors(Activity activity) {
		DynamicColorsOptions.Builder dynamicColorsOptionsBuilder =
				new DynamicColorsOptions.Builder()
						.setPrecondition((a, t) -> Conversations.isDynamicColorsDesired(a));

		if (ThemeHelper.isOled(activity)) {
			dynamicColorsOptionsBuilder.setOnAppliedCallback(new DynamicColors.OnAppliedCallback() {
				@Override
				public void onApplied(@NonNull Activity activity) {
					activity.getTheme().applyStyle(R.style.DarkOLEDOverlay, true);
				}
			});
		}

		DynamicColors.applyToActivityIfAvailable(activity, dynamicColorsOptionsBuilder.build());
	}

	public static boolean isOled(final Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		final boolean oled = "oledblack".equals(setting);
		return oled;
	}

	public static boolean isAutomatic(final Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		return setting.equals("automatic");
	}

	public static int find(final Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final boolean dark = isDark(sharedPreferences, resources);
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		final boolean oled = "oledblack".equals(setting);

		if (dark) {
			if (oled) {
				return R.style.Theme_Conversations3_DarkOLED;
			} else {
				return R.style.Theme_Conversations3_Dark;
			}
		} else {
			return R.style.Theme_Conversations3;
		}
	}

	@Nullable
	public static Integer findThemeOverrideStyle(final Context context) {
		if (DynamicColors.isDynamicColorAvailable() && Conversations.isDynamicColorsDesired(context)) {
			return null;
		}

		final SharedPreferences sharedPreferences =
				PreferenceManager.getDefaultSharedPreferences(context);
		final int currentColorOverride =
				sharedPreferences.getInt(SettingsActivity.THEME_OVERRIDE_COLOR, -1);
		final int index = findAccentIndex(context, currentColorOverride);
		if (index < 1) {
			return null;
		}

		final Resources resources = context.getResources();
		final String setting =
				sharedPreferences.getString(
						SettingsActivity.THEME, resources.getString(R.string.theme));
		if ("oledblack".equals(setting)) {
			switch (index) {
						case 1: return R.style.OverlayAccentV3_1_OLED;
						case 2: return R.style.OverlayAccentV3_2_OLED;
						case 3: return R.style.OverlayAccentV3_3_OLED;
						case 4: return R.style.OverlayAccentV3_4_OLED;
						case 5: return R.style.OverlayAccentV3_5_OLED;
						case 6: return R.style.OverlayAccentV3_6_OLED;
						case 7: return R.style.OverlayAccentV3_7_OLED;
						case 8: return R.style.OverlayAccentV3_8_OLED;
						case 9: return R.style.OverlayAccentV3_9_OLED;
						case 10: return R.style.OverlayAccentV3_10_OLED;
						case 11: return R.style.OverlayAccentV3_11_OLED;
						case 12: return R.style.OverlayAccentV3_12_OLED;
						case 13: return R.style.OverlayAccentV3_13_OLED;
						case 14: return R.style.OverlayAccentV3_14_OLED;
						case 15: return R.style.OverlayAccentV3_15_OLED;
						case 16: return R.style.OverlayAccentV3_16_OLED;
						case 17: return R.style.OverlayAccentV3_17_OLED;
						case 18: return R.style.OverlayAccentV3_18_OLED;
						default: return null;
					}
		}
		if (isDark(sharedPreferences, resources)) {
			switch (index) {
						case 1: return R.style.OverlayAccentV3_1_Dark;
						case 2: return R.style.OverlayAccentV3_2_Dark;
						case 3: return R.style.OverlayAccentV3_3_Dark;
						case 4: return R.style.OverlayAccentV3_4_Dark;
						case 5: return R.style.OverlayAccentV3_5_Dark;
						case 6: return R.style.OverlayAccentV3_6_Dark;
						case 7: return R.style.OverlayAccentV3_7_Dark;
						case 8: return R.style.OverlayAccentV3_8_Dark;
						case 9: return R.style.OverlayAccentV3_9_Dark;
						case 10: return R.style.OverlayAccentV3_10_Dark;
						case 11: return R.style.OverlayAccentV3_11_Dark;
						case 12: return R.style.OverlayAccentV3_12_Dark;
						case 13: return R.style.OverlayAccentV3_13_Dark;
						case 14: return R.style.OverlayAccentV3_14_Dark;
						case 15: return R.style.OverlayAccentV3_15_Dark;
						case 16: return R.style.OverlayAccentV3_16_Dark;
						case 17: return R.style.OverlayAccentV3_17_Dark;
						case 18: return R.style.OverlayAccentV3_18_Dark;
						default: return null;
					}
		}
		switch (index) {
					case 1: return R.style.OverlayAccentV3_1_Light;
					case 2: return R.style.OverlayAccentV3_2_Light;
					case 3: return R.style.OverlayAccentV3_3_Light;
					case 4: return R.style.OverlayAccentV3_4_Light;
					case 5: return R.style.OverlayAccentV3_5_Light;
					case 6: return R.style.OverlayAccentV3_6_Light;
					case 7: return R.style.OverlayAccentV3_7_Light;
					case 8: return R.style.OverlayAccentV3_8_Light;
					case 9: return R.style.OverlayAccentV3_9_Light;
					case 10: return R.style.OverlayAccentV3_10_Light;
					case 11: return R.style.OverlayAccentV3_11_Light;
					case 12: return R.style.OverlayAccentV3_12_Light;
					case 13: return R.style.OverlayAccentV3_13_Light;
					case 14: return R.style.OverlayAccentV3_14_Light;
					case 15: return R.style.OverlayAccentV3_15_Light;
					case 16: return R.style.OverlayAccentV3_16_Light;
					case 17: return R.style.OverlayAccentV3_17_Light;
					case 18: return R.style.OverlayAccentV3_18_Light;
					default: return null;
				}
	}

	private static int findAccentIndex(final Context context, final int color) {
		if (color == -1) {
			return -1;
		}
		final Resources resources = context.getResources();
		final int legacyIndex =
				findColorIndex(resources.getStringArray(R.array.themeColorsOverride), color);
		if (legacyIndex >= 0) {
			return legacyIndex + 1;
		}
		final int v2Index =
				findColorIndex(resources.getStringArray(R.array.themeAccentColorsV2), color);
		if (v2Index >= 0) {
			return v2Index + 1;
		}
		final int v3Index =
				findColorIndex(resources.getStringArray(R.array.themeAccentColorsV3), color);
		return v3Index >= 0 ? v3Index + 1 : -1;
	}

	private static int findColorIndex(final String[] colors, final int color) {
		for (int i = 0; i < colors.length; i++) {
			if (Color.parseColor(colors[i]) == color) {
				return i;
			}
		}
		return -1;
	}

	@Nullable
	@ColorInt
	public static Integer getOverriddenPrimaryColor(final Context context) {
		final SharedPreferences sharedPreferences =
				PreferenceManager.getDefaultSharedPreferences(context);
		final int currentColorOverride =
				sharedPreferences.getInt(SettingsActivity.THEME_OVERRIDE_COLOR, -1);
		final int index = findAccentIndex(context, currentColorOverride);
		if (index < 1) {
			return null;
		}
		final String[] v3 = context.getResources().getStringArray(R.array.themeAccentColorsV3);
		return Color.parseColor(v3[index - 1]);
	}

	/*public static int findDialog(Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final boolean dark = isDark(sharedPreferences, resources);
		final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
		switch (fontSize) {
			case "medium":
				return dark ? R.style.ConversationsTheme_Dark_Dialog_Medium : R.style.ConversationsTheme_Dialog_Medium;
			case "large":
				return dark ? R.style.ConversationsTheme_Dark_Dialog_Large : R.style.ConversationsTheme_Dialog_Large;
			default:
				return dark ? R.style.ConversationsTheme_Dark_Dialog : R.style.ConversationsTheme_Dialog;
		}
	}*/

	private static boolean isDark(final SharedPreferences sharedPreferences, final Resources resources) {
		final String setting = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "automatic".equals(setting)) {
			return (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		} else {
			return "dark".equals(setting) || "oledblack".equals(setting);
		}
	}

	public static boolean isDark(@StyleRes int id) {
		switch (id) {
			case R.style.Theme_Conversations3_Dark:
			case R.style.Theme_Conversations3_DarkOLED:
				return true;
			default:
				return false;
		}
	}
}
