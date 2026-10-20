package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so with the stock launcher
 * disabled the button does nothing. Xiaomi remotes also lose their YouTube button (KEYCODE_BUTTON_3) and Apps
 * button (KEYCODE_ALL_APPS, which Android sends to a Settings screen that doesn't exist); those open YouTube and
 * this launcher. Only these keys are consumed; every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Intent intent;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_TV:
                intent = new Intent(ACTION_VIEW_INPUTS);
                break;
            case KeyEvent.KEYCODE_BUTTON_3:
                intent = getPackageManager().getLeanbackLaunchIntentForPackage(YOUTUBE);
                if (intent == null) return false;   // no YouTube: leave the key to whoever wants it
                break;
            case KeyEvent.KEYCODE_ALL_APPS:
                intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                break;
            default:
                return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
