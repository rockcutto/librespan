package eu.siacs.conversations.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.jingle.CallDiagnosticsRuntime;

/** In-app sanitized call lifecycle log that does not depend on Logcat visibility. */
public final class CallDiagnosticsActivity extends ActionBarActivity {

    private TextView reportView;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.call_diagnostics_title);
        setContentView(createContent());
        refreshReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReport();
    }

    private ScrollView createContent() {
        final int padding = dp(20);
        final int gap = dp(12);

        final ScrollView scroll = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(
                root,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView summary = new TextView(this);
        summary.setText(R.string.call_diagnostics_summary);
        summary.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        root.addView(
                summary,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        reportView = new TextView(this);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setTextSize(13f);
        reportView.setPadding(0, gap, 0, gap);
        root.addView(
                reportView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        root.addView(
                actions,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final MaterialButton clear = new MaterialButton(this, null);
        clear.setText(R.string.secure_media_perf_clear);
        clear.setOnClickListener(
                view -> {
                    CallDiagnosticsRuntime.clear(this);
                    refreshReport();
                });
        actions.addView(clear);

        final MaterialButton copy = new MaterialButton(this, null);
        copy.setText(R.string.secure_media_perf_copy);
        copy.setOnClickListener(view -> copyReport());
        actions.addView(copy);

        final MaterialButton share = new MaterialButton(this, null);
        share.setText(R.string.secure_media_perf_share);
        share.setOnClickListener(view -> shareReport());
        actions.addView(share);

        return scroll;
    }

    private void refreshReport() {
        if (reportView != null) {
            reportView.setText(CallDiagnosticsRuntime.report(this));
        }
    }

    private void copyReport() {
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        getString(R.string.call_diagnostics_title),
                        CallDiagnosticsRuntime.report(this)));
        Toast.makeText(this, R.string.call_diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.call_diagnostics_title));
        intent.putExtra(Intent.EXTRA_TEXT, CallDiagnosticsRuntime.report(this));
        startActivity(Intent.createChooser(intent, getString(R.string.secure_media_perf_share)));
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
