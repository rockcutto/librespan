package eu.siacs.conversations.http;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.apache.http.conn.ssl.StrictHostnameVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.TrustManagers;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.TLSSocketFactory;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public class HttpConnectionManager extends AbstractConnectionManager {

    private final List<HttpDownloadConnection> downloadConnections = new ArrayList<>();
    private final List<HttpUploadConnection> uploadConnections = new ArrayList<>();
    private final Set<String> accountCleanupInProgress = ConcurrentHashMap.newKeySet();

    public static final Executor EXECUTOR = Executors.newFixedThreadPool(4);

    private static final OkHttpClient OK_HTTP_CLIENT;

    static {
        OK_HTTP_CLIENT = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    final Request original = chain.request();
                    final Request modified = original.newBuilder()
                            .header("User-Agent", getUserAgent())
                            .build();
                    return chain.proceed(modified);
                })
                .build();
    }


    public static String getUserAgent() {
        return String.format("%s/%s", BuildConfig.APP_NAME, BuildConfig.VERSION_NAME);
    }

    public HttpConnectionManager(XmppConnectionService service) {
        super(service);
    }

    public static Proxy getProxy() {
        final InetAddress localhost;
        try {
            localhost = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(localhost, 9050));
        } else {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(localhost, 8118));
        }
    }

    public void createNewDownloadConnection(Message message) {
        this.createNewDownloadConnection(message, false);
    }

    public void createNewDownloadConnection(final Message message, boolean interactive) {
        synchronized (this.downloadConnections) {
            if (accountCleanupInProgress.contains(
                    message.getConversation().getAccount().getUuid())) {
                return;
            }
            for (HttpDownloadConnection connection : this.downloadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": download already in progress");
                    return;
                }
            }
            final HttpDownloadConnection connection =
                    Config.SECURE_CONTENT_MEDIA_ROLLOUT
                            ? new SecureHttpDownloadConnection(message, this)
                            : new HttpDownloadConnection(message, this);
            // Register before init. Secure restart recovery can complete synchronously when the
            // exact committed media relation already exists and finishConnection() must be able
            // to remove this instance instead of leaving a stale entry behind.
            this.downloadConnections.add(connection);
            connection.init(interactive);
        }
    }

    public void createNewUploadConnection(final Message message, boolean delay) {
        synchronized (this.uploadConnections) {
            if (accountCleanupInProgress.contains(
                    message.getConversation().getAccount().getUuid())) {
                return;
            }
            for (HttpUploadConnection connection : this.uploadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": upload already in progress");
                    return;
                }
            }
            HttpUploadConnection connection = new HttpUploadConnection(message, Method.determine(message.getConversation().getAccount()), this);
            // Register before init: init performs local/server preflight checks and may fail
            // synchronously. finishUploadConnection() can then remove the connection cleanly.
            this.uploadConnections.add(connection);
            connection.init(delay);
        }
    }

    public void beginAccountCleanup(final Account account) {
        accountCleanupInProgress.add(account.getUuid());
        synchronized (this.downloadConnections) {
            for (final HttpDownloadConnection connection
                    : new ArrayList<>(this.downloadConnections)) {
                if (connection.getMessage().getConversation().getAccount() == account) {
                    connection.cancel();
                    this.downloadConnections.remove(connection);
                }
            }
        }
        synchronized (this.uploadConnections) {
            for (final HttpUploadConnection connection
                    : new ArrayList<>(this.uploadConnections)) {
                if (connection.getMessage().getConversation().getAccount() == account) {
                    connection.cancel();
                    this.uploadConnections.remove(connection);
                }
            }
        }
    }

    public void endAccountCleanup(final Account account) {
        accountCleanupInProgress.remove(account.getUuid());
    }

    void finishConnection(HttpDownloadConnection connection) {
        synchronized (this.downloadConnections) {
            this.downloadConnections.remove(connection);
        }
    }

    void finishUploadConnection(HttpUploadConnection httpUploadConnection) {
        synchronized (this.uploadConnections) {
            this.uploadConnections.remove(httpUploadConnection);
        }
    }

    OkHttpClient buildHttpClient(final HttpUrl url, final Account account, boolean interactive) {
        return buildHttpClient(url, account, 30, interactive);
    }

    OkHttpClient buildHttpClient(final HttpUrl url, final Account account, int readTimeout, boolean interactive) {
        final String slotHostname = url.host();
        final boolean onionSlot = slotHostname.endsWith(".onion");
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        builder.writeTimeout(120, TimeUnit.SECONDS);
        builder.readTimeout(readTimeout, TimeUnit.SECONDS);
        setupTrustManager(builder, interactive);
        if (mXmppConnectionService.useTorToConnect() || account.isOnion() || onionSlot) {
            builder.proxy(HttpConnectionManager.getProxy()).build();
        }
        return builder.build();
    }

    private void setupTrustManager(final OkHttpClient.Builder builder, final boolean interactive) {
        final X509TrustManager trustManager;
        if (interactive) {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getInteractive();
        } else {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getNonInteractive();
        }
        try {
            final SSLSocketFactory sf = new TLSSocketFactory(new X509TrustManager[]{trustManager}, SECURE_RANDOM);
            builder.sslSocketFactory(sf, trustManager);
            builder.hostnameVerifier(new StrictHostnameVerifier());
        } catch (final KeyManagementException | NoSuchAlgorithmException ignored) {
        }
    }

    public static InputStream open(final String url, final boolean tor) throws IOException {
        return open(HttpUrl.get(url), tor);
    }

    public static InputStream open(final HttpUrl httpUrl, final boolean tor) throws IOException {
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        if (tor) {
            builder.proxy(HttpConnectionManager.getProxy()).build();
        }
        final OkHttpClient client = builder.build();
        final Request request = new Request.Builder().get().url(httpUrl).build();
        final ResponseBody body = client.newCall(request).execute().body();
        if (body == null) {
            throw new IOException("No response body found");
        }
        return body.byteStream();
    }


    public static OkHttpClient okHttpClient(final Context context) {
        final OkHttpClient.Builder builder = HttpConnectionManager.OK_HTTP_CLIENT.newBuilder();
        try {
            final X509TrustManager trustManager;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                trustManager = TrustManagers.defaultWithBundledLetsEncrypt(context);
            } else {
                trustManager = TrustManagers.createDefaultTrustManager();
            }
            final SSLSocketFactory socketFactory =
                    new TLSSocketFactory(new X509TrustManager[] {trustManager}, SECURE_RANDOM);
            builder.sslSocketFactory(socketFactory, trustManager);
        } catch (final IOException
                       | KeyManagementException
                       | NoSuchAlgorithmException
                       | KeyStoreException
                       | CertificateException e) {
            Log.d(Config.LOGTAG, "not reconfiguring service to work with bundled LetsEncrypt");
            throw new RuntimeException(e);
        }
        return builder.build();
    }
}
