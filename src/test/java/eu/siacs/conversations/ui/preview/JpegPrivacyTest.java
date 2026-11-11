package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import eu.siacs.conversations.security.cryptolock.AppMasterKey;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionStateV1;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class JpegPrivacyTest {
    private final List<JpegLoader> loaders=new ArrayList<>();
    private final List<Runnable> listeners=new ArrayList<>();
    private Context context;
    @Before public void setup() {context=RuntimeEnvironment.getApplication();resetRuntime(context);}
    @After public void teardown() {
        for(Runnable listener:listeners) SecureContentCryptoSessionRuntimeV1.removePresentationInvalidationListener(listener);
        for(JpegLoader loader:loaders) loader.closeForTests();
        resetRuntime(context);
    }
    // Test isolation only: no production reset/unlock bypass is added. A real biometric/keystore
    // unlock must be exercised on device; these tests exercise the real retirement entry points.
    static void resetRuntime(Context context) {
        Class<?> runtime=SecureContentCryptoSessionRuntimeV1.class;
        AppMasterKey key=ReflectionHelpers.getStaticField(runtime,"activeMasterKey");
        if(key!=null) key.close();
        ReflectionHelpers.setStaticField(runtime,"activeMasterKey",null);
        ReflectionHelpers.setStaticField(runtime,"initialized",false);
        ReflectionHelpers.setStaticField(runtime,"activationStore",null);
        ReflectionHelpers.setStaticField(runtime,"activationTransactionId",null);
        ReflectionHelpers.setStaticField(runtime,"state",SecureContentCryptoSessionStateV1.INACTIVE);
        ReflectionHelpers.setStaticField(runtime,"epoch",1L);
        context.getSharedPreferences("inactive_device_activation_v1",Context.MODE_PRIVATE).edit().clear().commit();
        JpegLoader.INSTANCE.invalidateForPrivacy();JpegPreviewView.retireInvalidBindings();
    }
    private JpegLoader loader(Interceptor fixture) {
        JpegLoader loader=new JpegLoader(new OkHttpClient.Builder().addInterceptor(fixture).build());
        loaders.add(loader);return loader;
    }
    private void observeRetirement(JpegLoader loader) {
        Runnable listener=()->{loader.invalidateForPrivacy();JpegPreviewView.retireInvalidBindings();};
        listeners.add(listener);SecureContentCryptoSessionRuntimeV1.addPresentationInvalidationListener(listener);
    }
    private Response response(Request request,int status) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("fixture").body(ResponseBody.create(JpegLoaderTest.JPEG,MediaType.get("image/jpeg"))).build();
    }
    private void awaitResults(AtomicInteger count,int expected) throws Exception {
        for(int i=0;i<1000 && count.get()<expected;i++) {Thread.sleep(5);shadowOf(Looper.getMainLooper()).idle();}
        assertEquals(expected,count.get());
    }
    private void awaitWorkerCompletion(JpegLoader loader) throws Exception {
        for(int i=0;i<1000;i++) {
            synchronized(loader) {
                Map<?,?> jobs=ReflectionHelpers.getField(loader,"jobs");
                if(jobs.isEmpty()) return; // finish() and callback posting share this monitor.
            }
            Thread.sleep(5);
        }
        fail("worker did not finish");
    }
    @Test public void lockBetweenAuthorizationAndPermitIssuanceFailsClosed() {
        JpegLoader loader=loader(chain->{throw new AssertionError("no request");});
        assertNull(JpegAccess.capture(loader,()->{loader.invalidateForPrivacy();return true;}));
        assertNull(JpegAccess.capture(loader,()->false));
        assertNotNull(JpegAccess.capture(loader,()->true));
    }
    @Test public void everyUnavailableCryptoStateDeniesPreviewAccess() {
        assertNotNull(JpegAccess.capture(context)); // SCS without an active High Security profile.
        for(SecureContentCryptoSessionStateV1 state:new SecureContentCryptoSessionStateV1[]{
                SecureContentCryptoSessionStateV1.LOCKED,SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
                SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED,
                SecureContentCryptoSessionStateV1.CRYPTO_ERASED,SecureContentCryptoSessionStateV1.CORRUPT}) {
            ReflectionHelpers.setStaticField(SecureContentCryptoSessionRuntimeV1.class,"state",state);
            assertNull(state.toString(),JpegAccess.capture(context));
        }
    }
    @Test public void realSuspensionInvalidatesEvenWithoutMasterKeyOrEpochBump() {
        JpegLoader.Permit permit=JpegAccess.capture(context);assertNotNull(permit);
        long epoch=SecureContentCryptoSessionRuntimeV1.snapshot(context).getEpoch();
        SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context);
        assertEquals(epoch,SecureContentCryptoSessionRuntimeV1.snapshot(context).getEpoch());
        assertFalse(permit.isValid());
    }
    @Test public void keyRetirementBumpsEpochAndInvalidatesBeforeReturning() {
        JpegLoader.Permit permit=JpegAccess.capture(context);assertNotNull(permit);
        long epoch=SecureContentCryptoSessionRuntimeV1.snapshot(context).getEpoch();
        ReflectionHelpers.setStaticField(SecureContentCryptoSessionRuntimeV1.class,"activeMasterKey",
                AppMasterKey.Companion.generate(new java.security.SecureRandom()));
        ReflectionHelpers.setStaticField(SecureContentCryptoSessionRuntimeV1.class,"state",
                SecureContentCryptoSessionStateV1.ACTIVE);
        SecureContentCryptoSessionRuntimeV1.markRecoveryRequired(context);
        assertFalse(permit.isValid());
        assertTrue(SecureContentCryptoSessionRuntimeV1.snapshot(context).getEpoch()>epoch);
        assertNull(JpegAccess.capture(context));
    }
    @Test public void queuedSuccessAndCachedCallbackCannotPublishAfterLock() throws Exception {
        JpegAccess.capture(context); // Initialize before installing the fixture listener.
        AtomicInteger calls=new AtomicInteger();
        JpegLoader loader=loader(chain->{calls.incrementAndGet();return response(chain.request(),200);});observeRetirement(loader);
        JpegLoader.Permit permit=loader.permitForTests();
        loader.load(JpegLoaderTest.HTTPS,permit,b->fail("queued network result crossed lock"));
        awaitWorkerCompletion(loader); // Callback is posted but the main looper is paused.
        loader.load(JpegLoaderTest.HTTPS,permit,b->fail("queued cache result crossed lock"));
        SecureContentCryptoSessionRuntimeV1.markRecoveryRequired(context);
        assertFalse(permit.isValid());shadowOf(Looper.getMainLooper()).idle();
        loader.load(JpegLoaderTest.HTTPS,permit,b->fail("old permit accepted"));
        awaitWorkerCompletion(loader);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls.get());
    }
    @Test public void lockEvictsBothSuccessAndFailureCaches() throws Exception {
        JpegAccess.capture(context);
        for(boolean success:new boolean[]{false,true}) {
            AtomicInteger calls=new AtomicInteger();AtomicInteger results=new AtomicInteger();
            JpegLoader loader=loader(chain->{calls.incrementAndGet();return response(chain.request(),success?200:404);});
            observeRetirement(loader);
            loader.load(JpegLoaderTest.HTTPS,b->results.incrementAndGet());awaitResults(results,1);
            SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context);
            // Fixture represents a newly authorized epoch. Old cached pixels/errors must not survive.
            loader.load(JpegLoaderTest.HTTPS,b->results.incrementAndGet());awaitResults(results,2);
            assertEquals(2,calls.get());
        }
    }
    @Test public void lockCancelsActiveRequestAndFreshEpochWaitsForOldTransport() throws Exception {
        JpegAccess.capture(context);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger(),results=new AtomicInteger();
        JpegLoader loader=loader(chain->{
            if(calls.incrementAndGet()==1) {
                entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new IOException("fixture wait");}
                catch(InterruptedException ex){throw new IOException(ex);}
            }
            return response(chain.request(),200);
        });observeRetirement(loader);
        JpegLoader.Permit old=loader.permitForTests();
        try {
            loader.load(JpegLoaderTest.HTTPS,old,b->fail("old epoch result"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Call call;
            synchronized(loader) {
                Map<?,?> jobs=ReflectionHelpers.getField(loader,"jobs");
                call=ReflectionHelpers.getField(jobs.values().iterator().next(),"call");
            }
            SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context);
            assertTrue(call.isCanceled());assertFalse(old.isValid());
            JpegLoader.Permit fresh=JpegAccess.capture(loader,()->true);
            loader.load(JpegLoaderTest.HTTPS,fresh,b->{assertNotNull(b);results.incrementAndGet();});
            assertEquals(1,calls.get());release.countDown();awaitResults(results,1);
            assertEquals(2,calls.get());
        } finally {release.countDown();}
    }

    @Test public void retirementCannotDeadlockAgainstAccessEvaluation() throws Exception {
        JpegAccess.capture(context);
        JpegLoader loader=loader(chain->{throw new AssertionError("no network");});observeRetirement(loader);
        CountDownLatch checking=new CountDownLatch(1),resume=new CountDownLatch(1);
        var result=new java.util.concurrent.atomic.AtomicReference<JpegLoader.Permit>();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread capture=new Thread(()->{
            try {result.set(JpegAccess.capture(loader,()->{
                checking.countDown();
                try {if(!resume.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture wait");}
                catch(InterruptedException ex){throw new AssertionError(ex);}
                return SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(context);
            }));} catch(Throwable error) {failure.set(error);}
        });
        Thread retirement=new Thread(()->SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context));
        try {
            capture.start();assertTrue(checking.await(2,TimeUnit.SECONDS));
            retirement.start();retirement.join(1500);
            assertFalse("runtime -> loader must not wait for the access evaluator",retirement.isAlive());
        } finally {resume.countDown();capture.join(2000);retirement.join(2000);}
        assertFalse(capture.isAlive());assertNull(failure.get());assertNull(result.get());
    }
    @Test public void failingObserverDoesNotPreventKeyRetirementOrOtherConsumers() {
        JpegAccess.capture(context);
        Runnable broken=()->{throw new IllegalStateException("fixture");};
        AtomicInteger notified=new AtomicInteger();Runnable healthy=notified::incrementAndGet;
        listeners.add(broken);listeners.add(healthy);
        SecureContentCryptoSessionRuntimeV1.addPresentationInvalidationListener(broken);
        SecureContentCryptoSessionRuntimeV1.addPresentationInvalidationListener(healthy);
        SecureContentCryptoSessionRuntimeV1.markCryptoErased(context);
        assertEquals(SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
                SecureContentCryptoSessionRuntimeV1.snapshot(context).getState());
        assertEquals(1,notified.get());assertNull(JpegAccess.capture(context));
    }

    @Test public void deferredServiceSuspensionRevokesPreviewWithoutRetiringCryptoKey() {
        JpegLoader.Permit permit=JpegAccess.capture(context);assertNotNull(permit);
        AppMasterKey key=AppMasterKey.Companion.generate(new java.security.SecureRandom());
        ReflectionHelpers.setStaticField(SecureContentCryptoSessionRuntimeV1.class,"activeMasterKey",key);
        ReflectionHelpers.setStaticField(SecureContentCryptoSessionRuntimeV1.class,"state",SecureContentCryptoSessionStateV1.ACTIVE);
        var before=SecureContentCryptoSessionRuntimeV1.snapshot(context);
        SecureContentCryptoSessionRuntimeV1.invalidatePresentationForPrivacy();
        assertFalse(permit.isValid());
        var after=SecureContentCryptoSessionRuntimeV1.snapshot(context);
        assertEquals(before.getState(),after.getState());assertEquals(before.getEpoch(),after.getEpoch());
        assertSame(key,ReflectionHelpers.getStaticField(SecureContentCryptoSessionRuntimeV1.class,"activeMasterKey"));
    }
}
