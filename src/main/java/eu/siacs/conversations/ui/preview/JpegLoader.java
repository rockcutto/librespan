package eu.siacs.conversations.ui.preview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import okhttp3.*;

/** Isolated, memory-only thumbnails. No app/account HTTP client, disk cache or credentials. */
public final class JpegLoader {
    public static final JpegLoader INSTANCE = new JpegLoader();
    private final Call.Factory fixtureTransport;
    private JpegLoader() { this(null); }
    JpegLoader(Call.Factory transport) { this.fixtureTransport=transport; }
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2));
    static List<InetAddress> checkedDns(String host, Dns resolver) throws UnknownHostException {
        List<InetAddress> addresses=resolver.lookup(host);
        if (addresses.isEmpty()) throw new UnknownHostException("empty");
        for (InetAddress address:addresses) if (!JpegPolicy.publicAddress(address))
            throw new UnknownHostException("non-public");
        return addresses;
    }
    Bitmap previewForTest(HttpUrl source) throws IOException {
        return preview(source,new Job());
    }
    private Bitmap preview(HttpUrl source, Job job) throws IOException {
        if (!JpegPolicy.eligible(source)) throw new IOException("url");
        HttpUrl secure=source.isHttps()?source:source.newBuilder().scheme("https").port(443).build();
        return fetch(secure,job,SystemClock.elapsedRealtime()+8000);
    }
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String,Job> jobs = new HashMap<>();
    private final LruCache<String,Bitmap> cache = new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final LruCache<String,Long> errors = new LruCache<>(64);
    public interface Lease { void cancel(); }
    private long privacyEpoch=1;
    /** Revocable capability; no crypto/privacy calls are made while holding the loader lock. */
    public static final class Permit {
        private final JpegLoader owner;
        private final long epoch;
        private Permit(JpegLoader owner,long epoch) {this.owner=owner;this.epoch=epoch;}
        public boolean isValid() {synchronized(owner) {return epoch==owner.privacyEpoch;}}
        boolean sameEpoch(Permit other) {return other!=null && owner==other.owner && epoch==other.epoch;}
        void runIfValid(Runnable work) {
            synchronized(owner) {if(epoch==owner.privacyEpoch) work.run();}
        }
    }
    synchronized long privacyEpoch() {return privacyEpoch;}
    synchronized Permit permitForEpoch(long expected) {
        return expected==privacyEpoch ? new Permit(this,expected) : null;
    }
    synchronized Permit permitForTests() {
        if (fixtureTransport==null) throw new IllegalStateException("fixture required");
        return new Permit(this,privacyEpoch);
    }
    // Existing transport unit tests do not need an Android crypto profile.
    Lease load(HttpUrl source,Consumer<Bitmap> callback) {
        return load(source,permitForTests(),callback);
    }
    synchronized void invalidateForPrivacy() {
        privacyEpoch++;
        cache.evictAll(); errors.evictAll();
        // Queued callbacks otherwise retain captured Bitmaps even after cache eviction.
        main.removeCallbacksAndMessages(null);
        for(Job job:jobs.values()) {
            cancelForPrivacy(job);
            if(job.next!=null) cancelForPrivacy(job.next);
            job.next=null;
        }
    }
    private void cancelForPrivacy(Job job) {
        job.cancelled=true;
        for(Subscription listener:job.listeners) listener.active.set(false);
        job.listeners.clear();
        if(job.call!=null) job.call.cancel();
        // Keep the job until its transport exits: a fresh epoch must not overlap that URL.
    }

    private static final class Subscription {
        final Consumer<Bitmap> callback;
        final Permit permit;
        final java.util.concurrent.atomic.AtomicBoolean active =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Subscription(Permit permit,Consumer<Bitmap> callback) { this.permit=permit;this.callback=callback; }
        void deliver(Bitmap bitmap) {
            permit.runIfValid(()->{if (active.compareAndSet(true,false)) callback.accept(bitmap);});
        }
    }
    private static final class Job {
        final List<Subscription> listeners = new ArrayList<>();
        volatile Call call;
        volatile boolean cancelled;
        // A new subscription waits for the cancelled transport to exit, never reuses its result.
        Job next;
    }
    public synchronized Lease load(HttpUrl source, Permit permit, Consumer<Bitmap> callback) {
        if(permit==null || permit.owner!=this || permit.epoch!=privacyEpoch) return ()->{};
        HttpUrl secure=source.isHttps()?source:source.newBuilder().scheme("https").port(443).build();
        String key=secure.toString();
        Subscription subscription=new Subscription(permit,callback);
        Bitmap hit=cache.get(key);
        Long until=errors.get(key);
        if (hit!=null || (until!=null && until>SystemClock.elapsedRealtime())) {
            main.post(()->subscription.deliver(hit));
            return ()->subscription.active.set(false);
        }
        Job job=jobs.get(key);
        boolean start=job==null;
        if (start) { job=new Job(); jobs.put(key,job); }
        else if (job.cancelled) {
            if (job.next==null || job.next.cancelled) job.next=new Job();
            job=job.next;
        }
        Job owned=job;
        owned.listeners.add(subscription);
        if (start) start(key,source,owned);
        return ()->{ synchronized (JpegLoader.this) {
            subscription.active.set(false);
            owned.listeners.remove(subscription);
            if (owned.listeners.isEmpty()) {
                owned.cancelled=true;
                if (owned.call!=null) owned.call.cancel();
                // Do not cancel the executor Future: even queued jobs must reach finish(),
                // which releases this URL and starts any fresh waiting subscription.
            }
        }};
    }
    private void start(String key,HttpUrl source,Job job) {
        try { workers.execute(()->run(key,source,job)); }
        catch (RejectedExecutionException full) { finish(key,source,job,null,1000); }
    }
    void closeForTests() { workers.shutdownNow(); }
    private void run(String key,HttpUrl source,Job job) {
        Bitmap bitmap=null;
        try {
            bitmap=preview(source,job);
        } catch (IOException | RuntimeException ignored) { /* Never log URLs or response bodies. */ }
        finally { finish(key,source,job,bitmap,60_000); }
    }
    private synchronized void finish(String key,HttpUrl source,Job job,Bitmap bitmap,long errorTtl) {
        if (jobs.get(key)!=job) return;
        jobs.remove(key);
        if (!job.cancelled) {
            if (bitmap!=null) cache.put(key,bitmap);
            else errors.put(key,SystemClock.elapsedRealtime()+errorTtl);
            for (Subscription listener:job.listeners) main.post(()->listener.deliver(bitmap));
        }
        job.listeners.clear();
        if (job.next!=null && !job.next.cancelled) {
            jobs.put(key,job.next);
            start(key,source,job.next);
        }
    }
    private Bitmap fetch(HttpUrl url,Job job,long deadline) throws IOException {
        for (int redirects=0;;redirects++) {
            if (job.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
            if (!JpegPolicy.safeUrl(url)) throw new IOException("url");
            if (!url.isHttps()) throw new IOException("https required");
            // A proxy can resolve remotely, beyond the checked DNS set. Fail closed, never bypass it.
            ProxySelector selector=ProxySelector.getDefault();
            if (selector!=null) for (Proxy proxy:selector.select(url.uri()))
                if (proxy.type()!=Proxy.Type.DIRECT) throw new IOException("proxy route unsupported");
            long remaining=deadline-SystemClock.elapsedRealtime();
            if (remaining<=0) throw new IOException("deadline");
            OkHttpClient client=new OkHttpClient.Builder()
                    .dns(host->{
                        Future<List<InetAddress>> lookup;
                        try { lookup=DNS.submit(()->checkedDns(host,Dns.SYSTEM)); }
                        catch (RejectedExecutionException busy) { throw new UnknownHostException("dns busy"); }
                        try { return lookup.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS); }
                        catch (Exception failed) { lookup.cancel(true); DNS.purge(); throw new UnknownHostException("dns failure"); }
                    })
                    .proxySelector(new ProxySelector() {
                        @Override public List<Proxy> select(java.net.URI uri) {
                            List<Proxy> route=selector==null ? Collections.singletonList(Proxy.NO_PROXY) : selector.select(uri);
                            for (Proxy proxy:route) if (proxy.type()!=Proxy.Type.DIRECT)
                                throw new IllegalStateException("unsupported proxy route");
                            return route;
                        }
                        @Override public void connectFailed(java.net.URI uri,java.net.SocketAddress address,IOException error) {
                            if (selector!=null) selector.connectFailed(uri,address,error);
                        }
                    })
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                    .proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).callTimeout(remaining,TimeUnit.MILLISECONDS)
                    .connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
                    .connectionPool(new ConnectionPool(0,1,TimeUnit.SECONDS)).build();
            Call call=(fixtureTransport==null?client:fixtureTransport).newCall(new Request.Builder().url(url)
                    .header("Accept","image/jpeg").header("Accept-Encoding","identity")
                    .header("User-Agent","JPEG-Preview").build());
            job.call=call;
            if (job.cancelled) { call.cancel(); throw new IOException("cancelled"); }
            try (Response response=call.execute()) {
                int code=response.code();
                if (code==301 || code==302 || code==303 || code==307 || code==308) {
                    if (redirects>=2) throw new IOException("redirect limit");
                    String location=response.header("Location");
                    HttpUrl next=location==null?null:url.resolve(location);
                    if (!JpegPolicy.safeUrl(next) || !next.isHttps())
                        throw new IOException("redirect");
                    url=next; continue;
                }
                ResponseBody body=response.body();
                if (code!=200 || body==null || body.contentType()==null
                        || !body.contentType().type().equalsIgnoreCase("image")
                        || !body.contentType().subtype().equalsIgnoreCase("jpeg")
                        || !"identity".equalsIgnoreCase(response.header("Content-Encoding","identity"))) throw new IOException("type");
                long declared=body.contentLength();
                if (declared>JpegPolicy.MAX_BYTES) throw new IOException("length");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] chunk=new byte[8192]; int n;
                java.io.InputStream stream=body.byteStream();
                while ((n=stream.read(chunk))!=-1) {
                    if (job.cancelled || bytes.size()+n>JpegPolicy.MAX_BYTES
                            || SystemClock.elapsedRealtime()>deadline) throw new IOException("limit");
                    bytes.write(chunk,0,n);
                }
                if (declared>=0 && declared!=bytes.size()) throw new IOException("truncated");
                byte[] data=bytes.toByteArray();
                if (!JpegPolicy.jpegEnvelope(data)) throw new IOException("jpeg");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                int sample=JpegPolicy.sample(bounds.outWidth,bounds.outHeight);
                if (sample==0 || !"image/jpeg".equals(bounds.outMimeType)) throw new IOException("dimensions");
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
                Bitmap result=BitmapFactory.decodeByteArray(data,0,data.length,options);
                if (result==null || Math.max(result.getWidth(),result.getHeight())>512) throw new IOException("decode");
                return result;
            } finally { client.connectionPool().evictAll(); job.call=null; }
        }
    }
}
