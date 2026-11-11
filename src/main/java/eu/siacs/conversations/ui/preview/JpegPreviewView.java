package eu.siacs.conversations.ui.preview;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.appcompat.widget.AppCompatImageView;
import okhttp3.HttpUrl;

/** Fixed footprint while loading/successful; failures collapse back to the original text link. */
public final class JpegPreviewView extends AppCompatImageView {
    private HttpUrl source;
    interface Requests {
        JpegLoader.Lease load(HttpUrl url,JpegLoader.Permit permit,
                java.util.function.Consumer<android.graphics.Bitmap> callback);
    }
    private final Requests requests;
    private JpegLoader.Permit permit;
    private static final java.util.Set<JpegPreviewView> instances =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    static void retireInvalidBindings() {
        Runnable clear=()->{
            java.util.List<JpegPreviewView> views;
            synchronized(instances) {views=new java.util.ArrayList<>(instances);}
            for(JpegPreviewView view:views) if(view.permit!=null && !view.permit.isValid()) view.bind(null,null);
        };
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) clear.run();
        else ui.postAtFrontOfQueue(clear);
    }
    private long generation;
    private boolean attempted;
    private boolean failed;
    private JpegLoader.Lease lease;
    private final ViewTreeObserver.OnScrollChangedListener scroll=this::check;
    private final ViewTreeObserver.OnGlobalLayoutListener layout=this::check;
    public JpegPreviewView(Context context) {
        this(context,JpegLoader.INSTANCE::load);
    }
    JpegPreviewView(Context context,Requests requests) {
        super(context); this.requests=requests; setScaleType(ScaleType.FIT_CENTER);
        synchronized(instances) {instances.add(this);}
        GradientDrawable shape=new GradientDrawable();shape.setColor(0x10808080);
        shape.setCornerRadius(12*getResources().getDisplayMetrics().density);
        setBackground(shape); setClipToOutline(true); setVisibility(GONE);
        setContentDescription(context.getString(eu.siacs.conversations.R.string.external_jpeg));
        setOnClickListener(v->{
            if (source==null || permit==null || !permit.isValid()) return;
            try { context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(source.toString()))); }
            catch (android.content.ActivityNotFoundException ignored) {}
        });
    }
    public void bind(HttpUrl url,JpegLoader.Permit access) {
        if(access==null || !access.isValid()) {url=null;access=null;}
        if (java.util.Objects.equals(source,url)
                && (permit==access || (permit!=null && permit.sameEpoch(access)))) {check();return;}
        release(); generation++; source=url; permit=access; attempted=false; failed=false; setImageDrawable(null);
        setVisibility(url==null?GONE:VISIBLE); post(this::check);
    }
    private void release() { if (lease!=null) lease.cancel(); lease=null; }
    private boolean visibleToUser() {
        return isAttachedToWindow() && getWindowVisibility()==VISIBLE && isShown()
                && getGlobalVisibleRect(new Rect());
    }
    @Override public void draw(android.graphics.Canvas canvas) {
        JpegLoader.Permit access=permit;
        if(access!=null) access.runIfValid(()->super.draw(canvas));
    }
    private void check() {
        if(permit!=null && !permit.isValid()) {bind(null,null);return;}
        if (source==null || failed) return;
        if (!visibleToUser()) {
            if (lease!=null) { release(); generation++; attempted=false; }
            return;
        }
        if (attempted) return;
        attempted=true; long token=generation;
        JpegLoader.Permit access=permit;
        lease=requests.load(source,access,bitmap->access.runIfValid(()->{
            if (token!=generation) return;
            if (bitmap==null) {
                // Terminal for this binding: GONE must not trigger a layout/retry loop.
                release(); generation++; failed=true;
                setImageDrawable(null); setVisibility(GONE);
                return;
            }
            if (!visibleToUser()) {
                release(); generation++; attempted=false;
                return;
            }
            setImageBitmap(bitmap);
        }));
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(scroll);
        getViewTreeObserver().addOnGlobalLayoutListener(layout);check();
    }
    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scroll);
        getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        release();generation++;attempted=false;setImageDrawable(null);super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility!=VISIBLE) { release();generation++;attempted=false;setImageDrawable(null); }
        else post(this::check);
    }
}
