package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class JpegLoaderTest {
    static final HttpUrl HTTPS=HttpUrl.get("https://example.org/a.jpg");
    static final byte[] JPEG=Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDl6KKK2Mj/2Q==");
    private Response response(Request request,int code,String mime,byte[] bytes,String location) {
        Response.Builder b=new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(ResponseBody.create(bytes,MediaType.get(mime)));
        if(location!=null)b.header("Location",location);
        return b.build();
    }
    private final List<JpegLoader> loaders=new ArrayList<>();
    @org.junit.After public void closeLoaders() {
        for(JpegLoader loader:loaders) loader.closeForTests();
    }
    private JpegLoader loader(Interceptor fixture) {
        JpegLoader loader=new JpegLoader(new OkHttpClient.Builder().addInterceptor(fixture).build());
        loaders.add(loader);
        return loader;
    }
    @Test public void validJpegAndHttpsUpgradeDoNotSendCredentials() throws Exception {
        var seen=new ArrayList<String>();
        var l=loader(chain->{var r=chain.request();seen.add(r.url().scheme());
            for(String header:List.of("Cookie","Referer","Authorization"))assertNull(r.header(header));
            return response(r,200,"image/jpeg",JPEG,null);});
        assertNotNull(l.previewForTest(HttpUrl.get("http://example.org/a.jpg")));
        assertEquals(List.of("https"),seen);
    }
    @Test public void httpsJpegSucceedsWithoutChangingSource() throws Exception {
        var l=loader(chain->response(chain.request(),200,"image/jpeg",JPEG,null));
        assertNotNull(l.previewForTest(HTTPS));
        assertEquals("https://example.org/a.jpg",HTTPS.toString());
    }
    @Test public void httpOnlyAndHttpsTimeoutNeverSendHttpGet() {
        for (boolean timeout:new boolean[]{false,true}) {
            var seen=new ArrayList<String>();
            var l=loader(chain->{
                seen.add(chain.request().url().scheme());
                if (chain.request().url().isHttps()) {
                    if (timeout) throw new java.net.SocketTimeoutException("fixture");
                    throw new IOException("https unavailable");
                }
                return response(chain.request(),200,"image/jpeg",JPEG,null);
            });
            HttpUrl original=HttpUrl.get("http://example.org/a.jpg");
            assertThrows(IOException.class,()->l.previewForTest(original));
            assertEquals(List.of("https"),seen);
            assertEquals("http://example.org/a.jpg",original.toString());
        }
    }
    @Test public void htmlWrongMimeTruncationAndOversizeAreRejected() {
        for(int kind=0;kind<4;kind++) {
            final int k=kind;
            var l=loader(chain->response(chain.request(),200,k==0?"text/html":"image/jpeg",
                    k==0?JPEG:k==1?"<html>fake</html>".getBytes():k==2?Arrays.copyOf(JPEG,JPEG.length-2):new byte[JpegPolicy.MAX_BYTES+1],null));
            assertThrows(IOException.class,()->l.previewForTest(HTTPS));
        }
    }
    @Test public void errorsAndTimeoutAreTerminal() {
        var failure=loader(chain->response(chain.request(),403,"image/jpeg",JPEG,null));
        assertThrows(IOException.class,()->failure.previewForTest(HTTPS));
        var timeout=loader(chain->{throw new java.net.SocketTimeoutException();});
        assertThrows(IOException.class,()->timeout.previewForTest(HTTPS));
    }
    @Test public void redirectLimitAndDowngradeAndLocalHostRejected() {
        var loop=loader(chain->response(chain.request(),302,"image/jpeg",JPEG,"/next.jpg"));
        assertThrows(IOException.class,()->loop.previewForTest(HTTPS));
        for(String location:List.of("http://example.org/a.jpg","https://localhost/a.jpg")) {
            var l=loader(chain->response(chain.request(),302,"image/jpeg",JPEG,location));
            assertThrows(IOException.class,()->l.previewForTest(HTTPS));
        }
    }
    @Test public void unknownAndFalseLengthsUseActualByteLimit() throws Exception {
        for(long declared:new long[]{-1,1,JpegPolicy.MAX_BYTES+1L}) {
            var l=loader(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(new ResponseBody(){
                        @Override public MediaType contentType(){return MediaType.get("image/jpeg");}
                        @Override public long contentLength(){return declared;}
                        final okio.Buffer buffer=new okio.Buffer().write(JPEG);
                        @Override public okio.BufferedSource source(){return buffer;}
                    }).build());
            if(declared==-1)assertNotNull(l.previewForTest(HTTPS));
            else assertThrows(IOException.class,()->l.previewForTest(HTTPS));
        }
    }
    @Test public void publicRoomsDefaultOffAndSettingIsPerChat() {
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        assertFalse(JpegPreferences.enabled(context,"public-room"));
        JpegPreferences.set(context,"public-room",true);
        assertTrue(JpegPreferences.enabled(context,"public-room"));
        assertFalse(JpegPreferences.enabled(context,"direct-chat"));
    }
    @Test public void concurrentSubscribersShareOneRequestAndCancellationDropsResult() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var calls=new AtomicInteger();var results=new AtomicInteger();
        var l=loader(chain->{calls.incrementAndGet();started.countDown();
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new IOException(ex);}
            finally{finished.countDown();}
            return response(chain.request(),200,"image/jpeg",JPEG,null);});
        var a=l.load(HTTPS,b->results.incrementAndGet());
        assertTrue(started.await(2,TimeUnit.SECONDS));
        var b=l.load(HTTPS,x->results.incrementAndGet());a.cancel();b.cancel();release.countDown();
        assertTrue(finished.await(2,TimeUnit.SECONDS));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1,calls.get());assertEquals(0,results.get());l.closeForTests();
    }
    @Test public void successAndFailureCachesPreventRepeatRequests() throws Exception {
        for(boolean success:new boolean[]{false,true}) {
            var calls=new AtomicInteger();var results=new AtomicInteger();
            var l=loader(chain->{calls.incrementAndGet();
                return response(chain.request(),success?200:503,"image/jpeg",JPEG,null);});
            l.load(HTTPS,b->results.incrementAndGet());
            for(int i=0;i<400&&results.get()==0;i++) {
                Thread.sleep(5);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            }
            assertEquals(1,results.get());
            l.load(HTTPS,b->results.incrementAndGet());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(2,results.get());assertEquals(1,calls.get());l.closeForTests();
        }
    }
    @Test public void cancelledTransportFinishesBeforeFreshSameUrlSubscriptionStarts() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var calls=new AtomicInteger(); var oldResults=new AtomicInteger(); var newResults=new AtomicInteger();
        var l=loader(chain->{
            int attempt=calls.incrementAndGet();
            if (attempt==1) {
                entered.countDown();
                try { if (!release.await(3,TimeUnit.SECONDS)) throw new IOException("fixture wait"); }
                catch (InterruptedException ex) { throw new IOException(ex); }
            }
            return response(chain.request(),200,"image/jpeg",JPEG,null);
        });
        try {
            var old=l.load(HTTPS,b->oldResults.incrementAndGet());
            assertTrue(entered.await(2,TimeUnit.SECONDS)); old.cancel();
            l.load(HTTPS,b->{ assertNotNull(b); newResults.incrementAndGet(); });
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1,calls.get()); assertEquals(0,newResults.get());
            release.countDown(); awaitResults(newResults,1);
            assertEquals(2,calls.get()); assertEquals(0,oldResults.get());
        } finally { release.countDown(); l.closeForTests(); }
    }
    @Test public void cancellingWaitingSuccessorDoesNotPoisonThirdSubscription() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var calls=new AtomicInteger(); var results=new AtomicInteger();
        var l=loader(chain->{
            if (calls.incrementAndGet()==1) {
                entered.countDown();
                try { release.await(3,TimeUnit.SECONDS); }
                catch (InterruptedException ex) { throw new IOException(ex); }
            }
            return response(chain.request(),200,"image/jpeg",JPEG,null);
        });
        try {
            var first=l.load(HTTPS,b->fail("cancelled first"));
            assertTrue(entered.await(2,TimeUnit.SECONDS)); first.cancel();
            var second=l.load(HTTPS,b->fail("cancelled successor")); second.cancel();
            l.load(HTTPS,b->{assertNotNull(b);results.incrementAndGet();});
            release.countDown(); awaitResults(results,1); assertEquals(2,calls.get());
        } finally { release.countDown(); l.closeForTests(); }
    }
    @Test public void concurrentSubscribersIncludingUpgradedUrlReceiveOneResultEach() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();var results=new AtomicInteger();
        var l=loader(chain->{calls.incrementAndGet();entered.countDown();
            try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException ex){throw new IOException(ex);}
            return response(chain.request(),200,"image/jpeg",JPEG,null);});
        try {
            l.load(HTTPS,b->{assertNotNull(b);results.incrementAndGet();});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            l.load(HttpUrl.get("http://example.org/a.jpg"),b->{assertNotNull(b);results.incrementAndGet();});
            release.countDown();awaitResults(results,2);assertEquals(1,calls.get());
        } finally {release.countDown();l.closeForTests();}
    }
    @Test public void cachedCallbackCanBeCancelledBeforeUiDelivery() throws Exception {
        var results=new AtomicInteger();
        var l=loader(chain->response(chain.request(),200,"image/jpeg",JPEG,null));
        try {
            l.load(HTTPS,b->results.incrementAndGet());awaitResults(results,1);
            var cached=l.load(HTTPS,b->fail("cancelled cached result"));cached.cancel();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        } finally {l.closeForTests();}
    }
    private static void awaitResults(AtomicInteger results,int expected) throws Exception {
        for(int i=0;i<600&&results.get()<expected;i++) {
            Thread.sleep(5); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertEquals(expected,results.get());
    }
}
