package com.fadcam.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.fadcam.MainActivity;
import com.fadcam.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import androidx.core.content.res.ResourcesCompat;

/**
 * Dark-themed clock App Widget provider that updates every minute.
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_UPDATE_CLOCK = "com.fadcam.widgets.ACTION_UPDATE_CLOCK";

    /**
     * Per widget: everything the last full update drew except the time. When a minute
     * tick finds it unchanged, only the time bitmap is drawn and sent; the date, AM/PM
     * and Arabic bitmaps were redrawn and re-sent to the launcher every minute.
     */
    private static final java.util.Map<Integer, String> lastFullUpdateKeys =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateClock(context, appWidgetManager, id, false);
        }
        scheduleMinuteUpdates(context);
    }

    @Override
    public void onEnabled(Context context) {
        scheduleMinuteUpdates(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancelMinuteUpdates(context);
        lastFullUpdateKeys.clear();
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        for (int id : appWidgetIds) {
            lastFullUpdateKeys.remove(id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, android.os.Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        // Update widget when size changes
        updateClock(context, appWidgetManager, appWidgetId, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction()) ||
                Intent.ACTION_TIME_TICK.equals(intent.getAction()) ||
                Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()) ||
                ACTION_UPDATE_CLOCK.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWidgetProvider.class));
            boolean minuteTick = ACTION_UPDATE_CLOCK.equals(intent.getAction())
                    || Intent.ACTION_TIME_TICK.equals(intent.getAction());
            for (int id : ids) {
                updateClock(context, mgr, id, minuteTick);
            }
            if (ids.length > 0 && !Intent.ACTION_TIME_TICK.equals(intent.getAction())) {
                scheduleMinuteUpdates(context);
            }
        }
    }

    private void updateClock(Context context, AppWidgetManager manager, int appWidgetId,
                             boolean minuteTick) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        WidgetPreferences prefs = new WidgetPreferences(context);

    // Get widget size for dynamic scaling
    android.util.Size widgetSize = getWidgetSize(context, manager, appWidgetId);
    float scaleFactor = calculateScaleFactor(widgetSize);

    // Compute dynamic text sizes (in sp)
    float timeSizeSp = Math.max(42f, 56f * scaleFactor);

    Date now = new Date();
    boolean use24h = prefs.is24HourFormat();
    String timeText = new SimpleDateFormat(use24h ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
    String fullKey = use24h + "|" + (use24h ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now))
            + "|" + prefs.showDate() + "|" + (prefs.showDate()
                    ? new SimpleDateFormat(prefs.getDateFormat(), Locale.getDefault()).format(now) : "")
            + "|" + prefs.showArabicDate() + "|" + (prefs.showArabicDate()
                    ? ArabicDateUtils.getArabicDate(prefs.getArabicDateFormat()) : "")
            + "|" + prefs.hasBlackBackground() + "|" + prefs.showBranding() + "|" + widgetSize;
    // The text is drawn into bitmaps, so TalkBack needs it spelled out
    String spokenTime = new SimpleDateFormat(use24h ? "HH:mm" : "h:mm a", Locale.getDefault()).format(now)
            + (prefs.showDate()
                    ? ", " + new SimpleDateFormat(prefs.getDateFormat(), Locale.getDefault()).format(now) : "");
    if (minuteTick && fullKey.equals(lastFullUpdateKeys.get(appWidgetId))) {
        RemoteViews timeOnly = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        timeOnly.setContentDescription(R.id.clock_root, spokenTime);
        timeOnly.setImageViewBitmap(R.id.clock_time, renderTextBitmap(context, timeText, timeSizeSp,
                Color.WHITE, true, false, 0, ResourcesCompat.getFont(context, R.font.ubuntu_regular)));
        manager.partiallyUpdateAppWidget(appWidgetId, timeOnly);
        return;
    }
    float ampmSizeSp = Math.max(12f, 14f * scaleFactor);

    final float baseWidth = 250f;
    float widthScale = widgetSize != null ? (float) widgetSize.getWidth() / baseWidth : 1f;
    float dateScale = Math.max(0.85f, Math.min(1.0f, widthScale));
    float dateSizeSp = Math.max(13f, Math.min(18f, 16f * dateScale));
    float arabicDateSizeSp = Math.max(11f, Math.min(16f, 14f * dateScale));

            // Set background color only
            if (prefs.hasBlackBackground()) {
                views.setInt(R.id.clock_root, "setBackgroundResource", R.drawable.widget_black_background);
            } else {
                views.setInt(R.id.clock_root, "setBackgroundResource", android.R.color.transparent);
            }

            // Branding overlay visibility via dedicated ImageView
            views.setViewVisibility(R.id.branding_logo, prefs.showBranding() ? android.view.View.VISIBLE : android.view.View.GONE);

        // Dynamic branding flag sizing: keep it a small band, not full height
        if (prefs.showBranding()) {
            float widgetHeightDp = widgetSize != null ? widgetSize.getHeight() : 110f;
            // Even stronger scaling and larger default band
            float desiredDp = widgetHeightDp * 0.70f; // 70% of height
            float clampedDp = Math.max(72f, Math.min(240f, desiredDp));
            int px = dpToPx(context, clampedDp);
            views.setInt(R.id.branding_logo, "setMaxHeight", px);
            views.setInt(R.id.branding_logo, "setMinimumHeight", px);

            // Slight upward nudge to avoid overlapping the time line visually
            // Note: setTranslationY is not available via RemoteViews, but we can use setY relative adjustment with current position
            // Instead, rely on layout translation set in XML; keep here in case future API allows
        }

        // Prepare colors
        int timeColor = Color.WHITE;
        int ampmColor = Color.parseColor("#B0B0B0");
        int dateColor = Color.parseColor("#E0E0E0");
        int arabicColor = Color.parseColor("#C0C0C0");

        int widgetWidthPx = dpToPx(context, widgetSize != null ? widgetSize.getWidth() : 250);
        // Reserve around 55% of width for date section; keep some gap
        int dateMaxWidth = (int) (widgetWidthPx * 0.55f);

        // Ubuntu regular typeface from res/font
        android.graphics.Typeface ubuntu = ResourcesCompat.getFont(context, R.font.ubuntu_regular);

        // Format time based on preference (default: 12-hour)
        if (prefs.is24HourFormat()) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            Bitmap timeBmp = renderTextBitmap(context, time, timeSizeSp, timeColor, true, false, 0, ubuntu);
            views.setImageViewBitmap(R.id.clock_time, timeBmp);
            views.setViewVisibility(R.id.clock_ampm, android.view.View.GONE);
        } else {
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date());
            String ampm = new SimpleDateFormat("a", Locale.getDefault()).format(new Date());
            Bitmap timeBmp = renderTextBitmap(context, time, timeSizeSp, timeColor, true, false, 0, ubuntu);
            Bitmap ampmBmp = renderTextBitmap(context, ampm, ampmSizeSp, ampmColor, true, false, 0, ubuntu);
            views.setImageViewBitmap(R.id.clock_time, timeBmp);
            views.setImageViewBitmap(R.id.clock_ampm, ampmBmp);
            views.setViewVisibility(R.id.clock_ampm, android.view.View.VISIBLE);
        }
        
        // Show/hide date based on preference
        if (prefs.showDate()) {
            String datePattern = prefs.getDateFormat();
            String date = new SimpleDateFormat(datePattern, Locale.getDefault()).format(new Date());
            Bitmap dateBmp = renderTextBitmap(context, date, dateSizeSp, dateColor, true, false, dateMaxWidth, ubuntu);
            views.setImageViewBitmap(R.id.clock_date, dateBmp);
            views.setViewVisibility(R.id.clock_date, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.clock_date, android.view.View.GONE);
        }
        
        // Arabic date (independent of regular date setting)
        if (prefs.showArabicDate()) {
            String arabicDateFormat = prefs.getArabicDateFormat();
            String arabicDate = ArabicDateUtils.getArabicDate(arabicDateFormat);
            Bitmap arabicBmp = renderTextBitmap(context, arabicDate, arabicDateSizeSp, arabicColor, true, true, dateMaxWidth, ubuntu);
            views.setImageViewBitmap(R.id.clock_date_arabic, arabicBmp);
            views.setViewVisibility(R.id.clock_date_arabic, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.clock_date_arabic, android.view.View.GONE);
        }
        
        // Show date_container if either regular date OR Arabic date is enabled
        if (prefs.showDate() || prefs.showArabicDate()) {
            views.setViewVisibility(R.id.date_container, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.date_container, android.view.View.GONE);
        }

        // Click to open the app at Shortcuts & Widgets screen
        Intent launch = new Intent(context, com.fadcam.MainActivity.class);
        launch.putExtra("open_shortcuts_widgets", true);
        // Own action: notification PendingIntents for a bare MainActivity intent with request code 0
        // would otherwise be the same PendingIntent and swap extras with this one
        launch.setAction("com.fadcam.widgets.OPEN_FROM_CLOCK");
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.clock_root, pi);
        views.setContentDescription(R.id.clock_root, spokenTime);

    manager.updateAppWidget(appWidgetId, views);
    lastFullUpdateKeys.put(appWidgetId, fullKey);
    }

    private static int dpToPx(Context context, float dp) {
        final float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void scheduleMinuteUpdates(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ClockWidgetProvider.class).setAction(ACTION_UPDATE_CLOCK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        // One-shot at the start of the next minute, re-armed on each tick: the old 60 s
        // inexact repeat drifted off the minute, so the clock could lag by most of a minute.
        long now = System.currentTimeMillis();
        long nextMinute = (now / 60_000L + 1) * 60_000L;
        am.cancel(pi);
        // Non-wakeup: a clock nobody can see needs no updates; the pending alarm is
        // delivered as soon as the phone wakes, so the time is fresh on screen-on.
        am.setWindow(AlarmManager.RTC, nextMinute, 1_000L, pi);
    }

    private void cancelMinuteUpdates(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ClockWidgetProvider.class).setAction(ACTION_UPDATE_CLOCK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    private android.util.Size getWidgetSize(Context context, AppWidgetManager manager, int appWidgetId) {
        android.os.Bundle options = manager.getAppWidgetOptions(appWidgetId);
        if (options == null) {
            // Return default size if options not available
            return new android.util.Size(250, 110);
        }
        
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250);
        int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
        
        // Portrait shows the widget at min width x max height, landscape at max width x min height
        boolean portrait = context.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        return portrait ? new android.util.Size(minWidth, maxHeight)
                : new android.util.Size(maxWidth, minHeight);
    }

    private float calculateScaleFactor(android.util.Size widgetSize) {
        // Base size is approximately a 4x1 widget (250x110 dp)
        int baseWidth = 250;
        int baseHeight = 110;
        
        float widthScale = (float) widgetSize.getWidth() / baseWidth;
        float heightScale = (float) widgetSize.getHeight() / baseHeight;
        
        // Use the smaller scale to ensure content fits
        float scale = Math.min(widthScale, heightScale);
        
        // Clamp scale between 0.7 and 2.0 for reasonable sizing
        return Math.max(0.7f, Math.min(2.0f, scale));
    }

    private Bitmap renderTextBitmap(Context context, String text, float textSizeSp, int color,
                                    boolean addShadow, boolean rtl, int maxWidthPx,
                                    android.graphics.Typeface typeface) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp,
                context.getResources().getDisplayMetrics()));
        if (typeface != null) paint.setTypeface(typeface);

        // Density-scaled shadow keeps the white text readable on light wallpapers; the
        // bitmap gets padding for it so the glow isn't clipped at the edges.
        float density = context.getResources().getDisplayMetrics().density;
        int pad = 0;
        if (addShadow) {
            float radius = 3f * density;
            paint.setShadowLayer(radius, 0f, density, 0xCC000000);
            pad = (int) Math.ceil(radius + density);
        }

        // Measure text
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        float textWidth = paint.measureText(text);
        float textHeight = bounds.height();

        int bmpWidth = (int) Math.ceil(textWidth);
        int bmpHeight = (int) Math.ceil(textHeight * 1.3f);

        if (maxWidthPx > 0 && bmpWidth > maxWidthPx - 2 * pad) {
            // Simple ellipsizing to fit max width
            String ellipsized = android.text.TextUtils.ellipsize(text, paint, Math.max(1, maxWidthPx - 2 * pad), android.text.TextUtils.TruncateAt.END).toString();
            text = ellipsized;
            paint.getTextBounds(text, 0, text.length(), bounds);
            textWidth = paint.measureText(text);
            bmpWidth = (int) Math.ceil(textWidth);
            bmpHeight = (int) Math.ceil(bounds.height() * 1.3f);
        }

        if (bmpWidth <= 0) bmpWidth = 1;
        if (bmpHeight <= 0) bmpHeight = (int) Math.ceil(textSizeSp);
        bmpWidth += 2 * pad;
        bmpHeight += 2 * pad;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float x = pad;
        if (rtl) {
            x = bmpWidth - pad; // drawText with align RIGHT for RTL
            paint.setTextAlign(Paint.Align.RIGHT);
        }
        float y = bmpHeight - (bmpHeight - (bounds.bottom - bounds.top)) / 2f - bounds.bottom;
        canvas.drawText(text, x, y, paint);
        return bmp;
    }
}
