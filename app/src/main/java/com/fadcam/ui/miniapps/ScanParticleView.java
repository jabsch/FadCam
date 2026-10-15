package com.fadcam.ui.miniapps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.fadcam.utils.LoopFrameRate;

import java.util.Random;

/**
 * Custom View that draws animated glowing crimson particles drifting inside the QR scan frame.
 * The glow is one pre-rendered sprite drawn per particle, so the view stays GPU drawn; a
 * software layer with setShadowLayer re-rendered and re-uploaded the whole bitmap on the
 * CPU every frame, next to the camera and the QR decoder. Redraws are capped at 30 fps.
 */
public class ScanParticleView extends View {

    private static final int N = 16;
    private static final long FRAME_MS = 33L;
    /** Particle speeds are per 60 Hz frame; movement follows elapsed time. */
    private static final float BASE_FRAME_MS = 1000f / 60f;
    /** Glow radius relative to the core: the old shadow blur (2.8x) around the core. */
    private static final float GLOW_SCALE = 3.8f;
    private static final int GLOW_SPRITE_PX = 64;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF glowRect = new RectF();
    private Bitmap glowSprite;
    private long lastFrameMs;
    private final Random rng = new Random();

    private final float[] px    = new float[N];
    private final float[] py    = new float[N];
    private final float[] vx    = new float[N];
    private final float[] vy    = new float[N];
    private final float[] alpha = new float[N];
    private final float[] size  = new float[N];

    private boolean running;
    private boolean initialized;

    public ScanParticleView(Context ctx) {
        super(ctx);
        setup();
    }

    public ScanParticleView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setup();
    }

    public ScanParticleView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        setup();
    }

    private void setup() {
        // Crimson glow fading from the core's edge outwards, alpha set per particle
        glowSprite = Bitmap.createBitmap(GLOW_SPRITE_PX, GLOW_SPRITE_PX, Bitmap.Config.ARGB_8888);
        float r = GLOW_SPRITE_PX / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(r, r, r,
                new int[] {0xFFD32F2F, 0xFFD32F2F, 0x00D32F2F},
                new float[] {0f, 1f / GLOW_SCALE, 1f}, Shader.TileMode.CLAMP));
        new Canvas(glowSprite).drawCircle(r, r, r, p);
    }

    private void initParticles() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float dp = getContext().getResources().getDisplayMetrics().density;

        for (int i = 0; i < N; i++) {
            px[i] = rng.nextFloat() * w;
            py[i] = rng.nextFloat() * h;

            float speed = (0.4f + rng.nextFloat() * 1.1f) * dp;
            double angle = rng.nextDouble() * Math.PI * 2.0;
            vx[i] = (float) (Math.cos(angle) * speed);
            vy[i] = (float) (Math.sin(angle) * speed);

            alpha[i] = 0.25f + rng.nextFloat() * 0.75f;
            size[i]  = (1.8f + rng.nextFloat() * 3.2f) * dp;
        }
        initialized = true;
    }

    /** Call to start the particle animation and make the view visible. */
    public void startAnimation() {
        running     = true;
        initialized = false;
        lastFrameMs = 0L;
        setVisibility(VISIBLE);
        LoopFrameRate.request(this, true);
        postInvalidateOnAnimation();
    }

    /** Call to stop the particle animation and hide the view. */
    public void stopAnimation() {
        running = false;
        setVisibility(GONE);
        LoopFrameRate.request(this, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running) return;

        if (!initialized) {
            initParticles();
            if (!initialized) {
                postInvalidateOnAnimation();
                return;
            }
        }

        int w = getWidth();
        int h = getHeight();
        long now = SystemClock.uptimeMillis();
        float step = lastFrameMs == 0L ? 1f
                : Math.min(4f, (now - lastFrameMs) / BASE_FRAME_MS);
        lastFrameMs = now;

        for (int i = 0; i < N; i++) {
            // Move
            px[i] += vx[i] * step;
            py[i] += vy[i] * step;

            // Bounce off edges
            if (px[i] < 0)  { px[i] = 0;  vx[i] =  Math.abs(vx[i]); }
            if (px[i] > w)  { px[i] = w;  vx[i] = -Math.abs(vx[i]); }
            if (py[i] < 0)  { py[i] = 0;  vy[i] =  Math.abs(vy[i]); }
            if (py[i] > h)  { py[i] = h;  vy[i] = -Math.abs(vy[i]); }

            // Pulse alpha randomly
            alpha[i] += (rng.nextFloat() - 0.45f) * 0.06f * step;
            if (alpha[i] < 0.12f) alpha[i] = 0.12f;
            if (alpha[i] > 1.0f)  alpha[i] = 1.0f;

            int a = (int) (alpha[i] * 255f);

            // Outer glow: semi-transparent crimson sprite
            float gr = size[i] * GLOW_SCALE;
            glowRect.set(px[i] - gr, py[i] - gr, px[i] + gr, py[i] + gr);
            glowPaint.setAlpha(a / 2);
            canvas.drawBitmap(glowSprite, null, glowRect, glowPaint);

            // Core: bright warm-red, fully opaque at max alpha
            paint.setARGB(a, 255, 70, 70);

            canvas.drawCircle(px[i], py[i], size[i], paint);
        }

        // Drifting particles look the same at 30 fps
        postInvalidateDelayed(FRAME_MS);
    }
}
