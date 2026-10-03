package eu.siacs.conversations.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;

/**
 * Explicit, one-shot network diagnostics for developer builds/settings.
 *
 * No periodic work is scheduled. The test runs only after the user presses the button and stops
 * when this Activity is destroyed.
 */
public final class NetworkDiagnosticsActivity extends XmppActivity {

    private static final int TCP_PROBES = 3;
    private static final int TCP_TIMEOUT_MS = 3500;
    private static final int XMPP_PING_PROBES = 3;
    private static final long XMPP_PING_TIMEOUT_SECONDS = 5L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView reportView;
    private MaterialButton runButton;
    private volatile boolean destroyed;
    private String lastReport = "";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.network_diagnostics_title);
        setContentView(createContent());
        showIdleReport();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onBackendConnected() {
        if (runButton != null) {
            runButton.setEnabled(true);
        }
    }

    @Override
    protected void refreshUiReal() {
        // This screen is intentionally snapshot-based. Live service/UI refreshes do not mutate
        // the last diagnostic result.
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
        summary.setText(R.string.network_diagnostics_summary);
        summary.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        root.addView(
                summary,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, gap, 0, 0);
        root.addView(
                actions,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        runButton = new MaterialButton(this, null);
        runButton.setText(R.string.network_diagnostics_run);
        runButton.setEnabled(false);
        runButton.setOnClickListener(view -> runDiagnostics());
        actions.addView(runButton);

        final MaterialButton copyButton = new MaterialButton(this, null);
        copyButton.setText(R.string.secure_media_perf_copy);
        copyButton.setOnClickListener(view -> copyReport());
        actions.addView(copyButton);

        reportView = new TextView(this);
        reportView.setTextIsSelectable(true);
        reportView.setTextSize(14f);
        reportView.setPadding(0, gap, 0, gap);
        root.addView(
                reportView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        return scroll;
    }

    private void showIdleReport() {
        lastReport = getString(R.string.network_diagnostics_idle);
        if (reportView != null) {
            reportView.setText(lastReport);
        }
    }

    private void runDiagnostics() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            Toast.makeText(this, R.string.network_diagnostics_backend_unavailable, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        runButton.setEnabled(false);
        reportView.setText(R.string.network_diagnostics_running);

        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        final boolean useTor = xmppConnectionService.useTorToConnect();
        final boolean extended = xmppConnectionService.showExtendedConnectionOptions();

        executor.execute(
                () -> {
                    final String report = buildReport(accounts, useTor, extended);
                    if (destroyed) {
                        return;
                    }
                    runOnUiThread(
                            () -> {
                                if (destroyed) {
                                    return;
                                }
                                lastReport = report;
                                reportView.setText(report);
                                runButton.setEnabled(true);
                            });
                });
    }

    private String buildReport(
            final List<Account> accounts,
            final boolean useTor,
            final boolean extended) {
        final StringBuilder report = new StringBuilder(1024);
        report.append(getString(R.string.network_diagnostics_report_header)).append('\n');
        appendNetworkSnapshot(report);

        if (accounts.isEmpty()) {
            report.append("\n")
                    .append(getString(R.string.network_diagnostics_no_accounts))
                    .append('\n');
            return report.toString().trim();
        }

        for (final Account account : accounts) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            report.append("\n")
                    .append("— ")
                    .append(account.getJid().asBareJid())
                    .append(" —\n");
            report.append(getString(R.string.network_diagnostics_xmpp_state))
                    .append(": ")
                    .append(account.getStatus())
                    .append('\n');

            final boolean routedThroughTor = useTor || account.isOnion();
            report.append(getString(R.string.network_diagnostics_route))
                    .append(": ")
                    .append(
                            routedThroughTor
                                    ? getString(R.string.network_diagnostics_route_tor)
                                    : getString(R.string.network_diagnostics_route_direct))
                    .append('\n');

            Endpoint endpoint = null;
            if (routedThroughTor) {
                report.append(getString(R.string.network_diagnostics_dns))
                        .append(": ")
                        .append(getString(R.string.network_diagnostics_dns_skipped_tor))
                        .append('\n');
            } else {
                endpoint = resolveEndpoint(account, extended, report);
                if (endpoint != null) {
                    appendTcpProbe(report, endpoint);
                }
            }

            if (account.getStatus() == Account.State.ONLINE) {
                appendXmppPingProbe(report, account);
            } else {
                report.append(getString(R.string.network_diagnostics_xmpp_ping))
                        .append(": ")
                        .append(getString(R.string.network_diagnostics_not_online))
                        .append('\n');
            }
        }
        return report.toString().trim();
    }

    private void appendNetworkSnapshot(final StringBuilder report) {
        final ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        final Network activeNetwork = connectivity == null ? null : connectivity.getActiveNetwork();
        final NetworkCapabilities caps =
                connectivity == null || activeNetwork == null
                        ? null
                        : connectivity.getNetworkCapabilities(activeNetwork);

        report.append(getString(R.string.network_diagnostics_network))
                .append(": ")
                .append(caps == null ? getString(R.string.network_diagnostics_none) : transports(caps))
                .append('\n');

        if (caps == null) {
            report.append(getString(R.string.network_diagnostics_internet))
                    .append(": ")
                    .append(getString(R.string.network_diagnostics_unavailable))
                    .append('\n');
            return;
        }

        final boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        report.append(getString(R.string.network_diagnostics_internet))
                .append(": ")
                .append(
                        validated
                                ? getString(R.string.network_diagnostics_validated)
                                : getString(R.string.network_diagnostics_not_validated))
                .append('\n');

        if (connectivity != null) {
            report.append(getString(R.string.network_diagnostics_metered))
                    .append(": ")
                    .append(
                            connectivity.isActiveNetworkMetered()
                                    ? getString(R.string.yes)
                                    : getString(R.string.no))
                    .append('\n');
        }

        final int down = caps.getLinkDownstreamBandwidthKbps();
        final int up = caps.getLinkUpstreamBandwidthKbps();
        if (down > 0 || up > 0) {
            report.append(getString(R.string.network_diagnostics_bandwidth))
                    .append(": ↓")
                    .append(formatMbps(down))
                    .append(" / ↑")
                    .append(formatMbps(up))
                    .append(" Mbit/s\n");
        }
    }

    private Endpoint resolveEndpoint(
            final Account account,
            final boolean extended,
            final StringBuilder report) {
        final String customHostname = account.getHostname().trim();
        final int customPort = account.getPort();
        final boolean hardcoded = extended && !customHostname.isEmpty();

        final long started = SystemClock.elapsedRealtime();
        final List<Resolver.Result> results;
        if (hardcoded) {
            results = Resolver.fromHardCoded(customHostname, customPort);
        } else {
            results = Resolver.resolve(account.getServer());
        }
        final long elapsed = SystemClock.elapsedRealtime() - started;

        if (results.isEmpty()) {
            report.append(getString(R.string.network_diagnostics_dns))
                    .append(": ")
                    .append(getString(R.string.network_diagnostics_failed))
                    .append(" (")
                    .append(elapsed)
                    .append(" ms)\n");
            return null;
        }

        final Resolver.Result first = results.get(0);
        report.append(getString(R.string.network_diagnostics_dns))
                .append(": ")
                .append(getString(R.string.network_diagnostics_ok))
                .append(" · ")
                .append(elapsed)
                .append(" ms")
                .append(" · ")
                .append(results.size())
                .append(" endpoint(s)\n");

        final String destination = first.asDestination();
        report.append(getString(R.string.network_diagnostics_endpoint))
                .append(": ")
                .append(destination)
                .append(':')
                .append(first.getPort())
                .append(first.isDirectTls() ? " · TLS" : " · STARTTLS")
                .append('\n');
        return new Endpoint(destination, first.getPort());
    }

    private void appendTcpProbe(final StringBuilder report, final Endpoint endpoint) {
        final List<Long> samples = new ArrayList<>(TCP_PROBES);
        int failures = 0;
        for (int i = 0; i < TCP_PROBES; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            final long started = SystemClock.elapsedRealtime();
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(endpoint.host, endpoint.port),
                        TCP_TIMEOUT_MS);
                samples.add(SystemClock.elapsedRealtime() - started);
            } catch (final IOException | RuntimeException e) {
                failures++;
            }
        }

        report.append(getString(R.string.network_diagnostics_tcp))
                .append(": ");
        if (samples.isEmpty()) {
            report.append(getString(R.string.network_diagnostics_failed))
                    .append(" (")
                    .append(failures)
                    .append('/')
                    .append(TCP_PROBES)
                    .append(")\n");
            return;
        }
        appendLatencySummary(report, samples);
        if (failures > 0) {
            report.append(" · ")
                    .append(failures)
                    .append('/')
                    .append(TCP_PROBES)
                    .append(' ')
                    .append(getString(R.string.network_diagnostics_failed_lower));
        }
        report.append('\n');
    }

    private void appendXmppPingProbe(final StringBuilder report, final Account account) {
        final List<Long> samples = new ArrayList<>(XMPP_PING_PROBES);
        int failures = 0;

        for (int i = 0; i < XMPP_PING_PROBES; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            final Iq ping = new Iq(Iq.Type.GET);
            ping.addChild("ping", Namespace.PING);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Iq> response = new AtomicReference<>();
            final long started = SystemClock.elapsedRealtime();

            xmppConnectionService.sendIqPacket(
                    account,
                    ping,
                    iq -> {
                        response.set(iq);
                        latch.countDown();
                    },
                    XMPP_PING_TIMEOUT_SECONDS);
            try {
                final boolean completed =
                        latch.await(XMPP_PING_TIMEOUT_SECONDS + 1L, TimeUnit.SECONDS);
                final Iq iq = response.get();
                if (completed && iq != null && iq.getType() != Iq.Type.TIMEOUT) {
                    // Even an IQ error proves that the existing XMPP transport completed a
                    // round-trip. Treat only timeout/no response as a network failure.
                    samples.add(SystemClock.elapsedRealtime() - started);
                } else {
                    failures++;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        report.append(getString(R.string.network_diagnostics_xmpp_ping))
                .append(": ");
        if (samples.isEmpty()) {
            report.append(getString(R.string.network_diagnostics_failed))
                    .append(" (")
                    .append(failures)
                    .append('/')
                    .append(XMPP_PING_PROBES)
                    .append(")\n");
            return;
        }

        appendLatencySummary(report, samples);
        if (failures > 0) {
            report.append(" · ")
                    .append(failures)
                    .append('/')
                    .append(XMPP_PING_PROBES)
                    .append(' ')
                    .append(getString(R.string.network_diagnostics_failed_lower));
        }
        report.append(" · ")
                .append(quality(samples, failures))
                .append('\n');
    }

    private void appendLatencySummary(final StringBuilder report, final List<Long> samples) {
        final long min = Collections.min(samples);
        final long max = Collections.max(samples);
        long total = 0L;
        for (final long sample : samples) {
            total += sample;
        }
        final long average = Math.round((double) total / samples.size());
        report.append("min ")
                .append(min)
                .append(" / avg ")
                .append(average)
                .append(" / max ")
                .append(max)
                .append(" ms");
    }

    private String quality(final List<Long> samples, final int failures) {
        if (samples.isEmpty() || failures >= 2) {
            return getString(R.string.network_diagnostics_quality_poor);
        }
        long total = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (final long sample : samples) {
            total += sample;
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        final long average = Math.round((double) total / samples.size());
        final long spread = max - min;
        if (failures == 0 && average <= 150L && spread <= 100L) {
            return getString(R.string.network_diagnostics_quality_good);
        }
        if (average <= 500L && failures <= 1) {
            return getString(R.string.network_diagnostics_quality_fair);
        }
        return getString(R.string.network_diagnostics_quality_poor);
    }

    private String transports(final NetworkCapabilities caps) {
        final List<String> transports = new ArrayList<>();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transports.add("Wi-Fi");
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transports.add(getString(R.string.network_diagnostics_cellular));
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transports.add("Ethernet");
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transports.add("VPN");
        }
        if (transports.isEmpty()) {
            transports.add(getString(R.string.network_diagnostics_other));
        }
        return TextUtils.join(" + ", transports);
    }

    private static String formatMbps(final int kbps) {
        return String.format(Locale.US, "%.1f", kbps / 1000.0);
    }

    private void copyReport() {
        if (lastReport == null || lastReport.isEmpty()) {
            return;
        }
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        getString(R.string.network_diagnostics_title),
                        lastReport));
        Toast.makeText(this, R.string.network_diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(final String host, final int port) {
            this.host = host;
            this.port = port;
        }
    }
}
