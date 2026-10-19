package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import java.util.Collections;

/** Run with connectedDebugAndroidTest; uses real package resources, without test dependencies. */
public final class BannerLoadingTest extends Instrumentation {
    public static final class RasterActivity extends Activity {}
    public static final class VectorActivity extends Activity {}

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        result.putString("class", getClass().getName());
        result.putString("test", "preservesBannerDetailAndPrecedence");
        result.putInt("numtests", 1);
        result.putInt("current", 1);
        sendStatus(1, result);
        try {
            Throwable[] problem = {null};
            runOnMainSync(() -> {
                try {
                    Configuration config = new Configuration(getTargetContext().getResources().getConfiguration());
                    config.densityDpi = 213;
                    Context context = getTargetContext().createConfigurationContext(config);
                    AppAdapter adapter = new AppAdapter(context, null, 4, Config.load(context));
                    adapter.reload(Collections.emptyList());
                    Drawable raster = bannerFor(adapter, context, RasterActivity.class);
                    if (!(raster instanceof BitmapDrawable)) throw new AssertionError("Missing application banner");
                    Bitmap bitmap = ((BitmapDrawable) raster).getBitmap();
                    // A focused 240 x 135 tile must not stretch a downsampled 213 x 120 bitmap.
                    if (bitmap.getWidth() < 264 || bitmap.getHeight() < 149) {
                        throw new AssertionError("Banner loses source detail: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                    if (!(bannerFor(adapter, context, VectorActivity.class) instanceof VectorDrawable)) {
                        throw new AssertionError("Activity vector banner must override the application bitmap");
                    }
                } catch (Throwable failure) {
                    problem[0] = failure;
                }
            });
            if (problem[0] != null) throw problem[0];
            sendStatus(0, result);
            result.putString("stream", "\nPASS: focused banner detail, application fallback, activity vector precedence\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stack", Log.getStackTraceString(failure));
            sendStatus(-2, result);
            result.putString("stream", "\nFAIL: " + Log.getStackTraceString(failure));
            finish(Activity.RESULT_OK, result);
        }
    }

    private Drawable bannerFor(AppAdapter adapter, Context context, Class<?> activity) {
        String key = new ComponentName(getContext().getPackageName(), activity.getName()).flattenToShortString();
        int position = adapter.positionOf(key);
        if (position < 0) throw new AssertionError("Missing fixture tile: " + key);
        AppAdapter.Holder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        adapter.onBindViewHolder(holder, position);
        return holder.banner.getDrawable();
    }
}
