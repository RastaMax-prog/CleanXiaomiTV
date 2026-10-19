package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher can read media sessions (what is playing, see NowPlaying): Android
 * gives that to apps with notification access. Granted over adb at install time
 * (cmd notification allow_listener); the launcher never reads or touches notifications.
 */
public class MediaListener extends NotificationListenerService {
}
