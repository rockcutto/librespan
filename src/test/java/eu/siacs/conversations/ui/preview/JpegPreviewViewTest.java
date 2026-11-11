package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class JpegPreviewViewTest {
    private static final HttpUrl A=HttpUrl.get("https://example.org/a.jpg");
    private static final HttpUrl B=HttpUrl.get("https://example.org/b.jpg");
    private ActivityController<Activity> controller;
    private FrameLayout root;
    private Context context;
    private JpegPreviewView view;
    private JpegLoader.Permit access;
    private final List<Request> requests=new ArrayList<>();
    private final List<JpegLoader> loaders=new ArrayList<>();
    private static final class Request {
        final HttpUrl url;
        final Consumer<Bitmap> callback;
        boolean cancelled;
        Request(HttpUrl url,Consumer<Bitmap> callback) {this.url=url;this.callback=callback;}
    }
    @Before public void setup() {
        controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity=controller.get();
        Resources original=activity.getResources();
        // The project does not package app resources for JVM tests. Supply only this label;
        // real attachment, visibility, layout and drawable behavior still run in Robolectric.
        Resources labels=new Resources(original.getAssets(),original.getDisplayMetrics(),original.getConfiguration()) {
            @Override public CharSequence getText(int id) throws NotFoundException {
                if(id==eu.siacs.conversations.R.string.external_jpeg)return "JPEG previews";
                return original.getText(id);
            }
        };
        context=new ContextWrapper(activity) {
            @Override public Resources getResources() {return labels;}
        };
        root=new FrameLayout(activity);activity.setContentView(root);
        JpegLoader scope=new JpegLoader(request->{throw new AssertionError("no network");});
        loaders.add(scope);access=scope.permitForTests();
        view=new JpegPreviewView(context,(url,permit,callback)->{
            Request request=new Request(url,callback);requests.add(request);
            return ()->request.cancelled=true;
        });
    }
    private void bind(HttpUrl url) {view.bind(url,access);}
    private void attach() {
        root.addView(view,new FrameLayout.LayoutParams(200,140));
        layout();
    }
    private void layout() {
        // Robolectric: simulate a foreground, visible Android window.
        Object attachInfo = org.robolectric.util.ReflectionHelpers
                .getField(root, "mAttachInfo");
        if (attachInfo == null) {
            throw new AssertionError("Test root is not attached");
        }
        org.robolectric.util.ReflectionHelpers.setField(
                attachInfo, "mWindowVisibility", View.VISIBLE);
        root.dispatchWindowVisibilityChanged(View.VISIBLE);

        root.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));
        root.layout(0,0,600,1000);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private Bitmap bitmap() {return Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888);}
    @After public void teardown() {
        controller.pause().stop().destroy();
        for (JpegLoader loader:loaders) loader.closeForTests();
    }

    @Test public void sameBindingKeepsActiveRequestAndAlreadyShownBitmap() {
        bind(A);attach();assertEquals("attached="+view.isAttachedToWindow()+" shown="+view.isShown()+" window="+view.getWindowVisibility()+" rect="+view.getGlobalVisibleRect(new android.graphics.Rect())+" size="+view.getWidth()+"x"+view.getHeight()+" rootAttached="+root.isAttachedToWindow()+" rootShown="+root.isShown(),1,requests.size());
        bind(A);layout();assertEquals(1,requests.size());assertFalse(requests.get(0).cancelled);
        int height=view.getHeight(); Bitmap image=bitmap(); requests.get(0).callback.accept(image);
        bind(A);layout();
        assertSame(image,((BitmapDrawable)view.getDrawable()).getBitmap());
        assertEquals(height,view.getHeight());assertEquals(1,requests.size());
        assertFalse(requests.get(0).cancelled);
    }
    @Test public void recycledViewRejectsOldCallbackEvenIfTransportCompletesLate() {
        bind(A);attach();
        bind(B);layout();assertTrue(requests.get(0).cancelled);assertEquals(2,requests.size());
        Bitmap current=bitmap();requests.get(1).callback.accept(current);
        requests.get(0).callback.accept(bitmap());
        assertEquals(B,requests.get(1).url);
        assertSame(current,((BitmapDrawable)view.getDrawable()).getBitmap());
    }
    @Test public void noLoadingOffscreenAndScrollAwayCancelsWithoutRebinding() {
        bind(A);shadowOf(Looper.getMainLooper()).idle();assertTrue(requests.isEmpty());
        attach();assertEquals(1,requests.size());
        view.offsetTopAndBottom(2000);
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root.getViewTreeObserver(),"dispatchOnScrollChanged");
        assertTrue(requests.get(0).cancelled);
        requests.get(0).callback.accept(bitmap());assertNull(view.getDrawable());
        view.offsetTopAndBottom(-2000);org.robolectric.util.ReflectionHelpers.callInstanceMethod(root.getViewTreeObserver(),"dispatchOnScrollChanged");
        assertEquals(2,requests.size());
    }
    @Test public void clearingBindingForUnavailableOrDisabledContentCancelsAndHides() {
        bind(A);attach();requests.get(0).callback.accept(bitmap());
        bind(null);layout();
        assertTrue(requests.get(0).cancelled);assertEquals(View.GONE,view.getVisibility());
        requests.get(0).callback.accept(bitmap());assertNull(view.getDrawable());
        bind(null);assertEquals(1,requests.size());
    }
    @Test public void detachAndSameUrlReattachStartsFreshSubscription() {
        bind(A);attach();root.removeView(view);
        assertTrue(requests.get(0).cancelled);
        bind(A);attach();assertEquals(2,requests.size());
        requests.get(0).callback.accept(bitmap());assertNull(view.getDrawable());
        requests.get(1).callback.accept(bitmap());assertNotNull(view.getDrawable());
    }
    @Test public void clickingUpgradePreviewOpensUnchangedOriginalUrl() {
        HttpUrl original=HttpUrl.get("http://example.org/a.jpg");
        bind(original);attach();view.performClick();
        assertEquals(original.toString(),shadowOf(controller.get()).getNextStartedActivity().getDataString());
    }

    @Test public void httpOnlyFailureLeavesLinkWithoutEmptyCardOrHttpGet() throws Exception {
        assertFailureCollapses("http://example.org/a.jpg",chain->{
            if (chain.request().url().isHttps()) throw new java.io.IOException("HTTPS unavailable");
            return jpegResponse(chain.request(),200,JpegLoaderTest.JPEG);
        });
    }
    @Test public void notFoundLeavesLinkWithoutEmptyCard() throws Exception {
        assertFailureCollapses(A.toString(),chain->jpegResponse(chain.request(),404,JpegLoaderTest.JPEG));
    }
    @Test public void timeoutLeavesLinkWithoutEmptyCard() throws Exception {
        assertFailureCollapses(A.toString(),chain->{throw new java.net.SocketTimeoutException("fixture");});
    }
    @Test public void oversizedJpegLeavesLinkWithoutEmptyCard() throws Exception {
        byte[] oversized=java.util.Arrays.copyOf(JpegLoaderTest.JPEG,JpegPolicy.MAX_BYTES+1);
        assertFailureCollapses(A.toString(),chain->jpegResponse(chain.request(),200,oversized));
    }
    private okhttp3.Response jpegResponse(okhttp3.Request request,int code,byte[] data) {
        return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("fixture").body(okhttp3.ResponseBody.create(data,
                        okhttp3.MediaType.get("image/jpeg"))).build();
    }
    private void assertFailureCollapses(String original,okhttp3.Interceptor fixture) throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var httpCalls=new java.util.concurrent.atomic.AtomicInteger();
        var release=new java.util.concurrent.CountDownLatch(1);
        JpegLoader loader=new JpegLoader(new okhttp3.OkHttpClient.Builder().addInterceptor(chain->{
            calls.incrementAndGet();
            if (!chain.request().url().isHttps()) httpCalls.incrementAndGet();
            try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                throw new java.io.IOException("fixture wait"); }
            catch (InterruptedException interrupted) {throw new java.io.IOException(interrupted);}
            return fixture.intercept(chain);
        }).build());
        loaders.add(loader);
        view=new JpegPreviewView(context,loader::load);
        access=loader.permitForTests();
        var bubble=new android.widget.LinearLayout(context);
        bubble.setOrientation(android.widget.LinearLayout.VERTICAL);
        var text=new android.widget.TextView(context);
        text.setText(original);
        android.text.util.Linkify.addLinks(text,android.text.util.Linkify.WEB_URLS);
        text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        bubble.addView(text,new android.widget.LinearLayout.LayoutParams(200,48));
        bubble.addView(view,new android.widget.LinearLayout.LayoutParams(200,140));
        root.addView(bubble,new FrameLayout.LayoutParams(200,android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        bind(HttpUrl.get(original));layout();
        int loadingHeight=bubble.getHeight();
        assertEquals(View.VISIBLE,view.getVisibility());assertTrue(loadingHeight>text.getHeight());
        release.countDown();
        for(int i=0;i<1000 && view.getVisibility()!=View.GONE;i++) {
            Thread.sleep(5);shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(View.GONE,view.getVisibility());assertNull(view.getDrawable());
        layout();assertEquals(text.getHeight(),bubble.getHeight());
        assertEquals(original,text.getText().toString());assertEquals(View.VISIBLE,text.getVisibility());
        var links=((android.text.Spanned)text.getText()).getSpans(0,text.length(),android.text.style.URLSpan.class);
        assertEquals(1,links.length);assertEquals(original,links[0].getURL());
        // Same bind, repeated layout and reattachment must not resurrect an empty card or retry.
        bind(HttpUrl.get(original));layout();layout();
        root.removeView(bubble);root.addView(bubble);layout();
        bind(HttpUrl.get(original));layout();
        assertEquals(View.GONE,view.getVisibility());assertEquals(text.getHeight(),bubble.getHeight());
        assertEquals(1,calls.get());assertEquals(0,httpCalls.get());
    }
    @Test public void failureIsTerminalOnlyForItsBindingAndLateFailureCannotHideNewImage() {
        bind(A);attach();requests.get(0).callback.accept(null);layout();
        assertEquals(View.GONE,view.getVisibility());
        bind(B);layout();assertEquals(2,requests.size());
        Bitmap image=bitmap();requests.get(1).callback.accept(image);
        requests.get(0).callback.accept(null);
        assertEquals(View.VISIBLE,view.getVisibility());
        assertSame(image,((BitmapDrawable)view.getDrawable()).getBitmap());
    }

    @Test public void highSecurityRetirementClearsShownImageAndRejectsLateResult() {
        JpegPrivacyTest.resetRuntime(context);
        try {
            access=JpegAccess.capture(context);assertNotNull(access);
            bind(A);attach();requests.get(0).callback.accept(bitmap());assertNotNull(view.getDrawable());
            eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1
                    .markRecoveryRequired(context);
            assertFalse(access.isValid());assertEquals(View.GONE,view.getVisibility());
            assertNull(view.getDrawable());assertTrue(requests.get(0).cancelled);
            requests.get(0).callback.accept(bitmap());assertNull(view.getDrawable());
            bind(A);layout();assertEquals(1,requests.size());assertEquals(View.GONE,view.getVisibility());
            assertNull(JpegAccess.capture(context));
            // A fresh authorized session still needs a fresh bind; old callbacks stay revoked.
            JpegPrivacyTest.resetRuntime(context);
            access=JpegAccess.capture(context);assertNotNull(access);
            assertEquals(View.GONE,view.getVisibility());
            bind(A);layout();assertEquals(2,requests.size());
            requests.get(0).callback.accept(bitmap());assertNull(view.getDrawable());
            requests.get(1).callback.accept(bitmap());assertNotNull(view.getDrawable());
        } finally {JpegPrivacyTest.resetRuntime(context);}
    }
    @Test public void backgroundRetirementCannotPaintBeforeUiScrubOrClearNewBinding() throws Exception {
        JpegPrivacyTest.resetRuntime(context);
        try {
            access=JpegAccess.capture(context);bind(A);attach();
            Bitmap image=bitmap();image.eraseColor(android.graphics.Color.RED);
            requests.get(0).callback.accept(image);
            Thread lock=new Thread(()->eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1
                    .markRecoveryRequired(context));
            lock.start();lock.join(2000);assertFalse("lock must not wait for main looper",lock.isAlive());
            assertFalse(access.isValid());
            Bitmap output=Bitmap.createBitmap(200,140,Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(output));
            assertEquals(android.graphics.Color.TRANSPARENT,output.getPixel(100,70));
            requests.get(0).callback.accept(bitmap());
            // Reauthorize before the posted old scrub runs: only invalid bindings may be cleared.
            JpegPrivacyTest.resetRuntime(context);access=JpegAccess.capture(context);
            bind(B);layout();requests.get(requests.size()-1).callback.accept(image);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE,view.getVisibility());
            assertSame(image,((BitmapDrawable)view.getDrawable()).getBitmap());
        } finally {JpegPrivacyTest.resetRuntime(context);}
    }
}
