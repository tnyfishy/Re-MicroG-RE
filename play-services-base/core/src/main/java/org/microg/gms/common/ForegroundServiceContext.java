package org.microg.gms.common;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;

import org.microg.gms.base.core.R;

import static android.os.Build.VERSION.SDK_INT;

public class ForegroundServiceContext extends ContextWrapper {
    private static final String TAG = "ForegroundService";
    public static final String EXTRA_FOREGROUND = "foreground";

    public ForegroundServiceContext(Context base) {
        super(base);
    }

    // The push connection service. Xiaomi (MIUI/HyperOS) freezes background apps even when they are
    // exempt from battery optimization, so this one stays in the foreground there.
    private static final String PUSH_SERVICE = "org.microg.gms.gcm.McsService";

    // The "keep awake" option is a flag file (the push service runs in its own process, where
    // shared preferences would not be up to date). Absent means on.
    private static final String KEEP_AWAKE_OFF_FLAG = "keep_awake_off";
    private static final String CHANNEL_ID = "push-connection";
    private static final String OLD_CHANNEL_ID = "foreground-service";

    private boolean shouldRunInForeground(Intent service) {
        if (SDK_INT < 26) return false;
        ComponentName component = service.getComponent();
        boolean isPushService = component != null && PUSH_SERVICE.equals(component.getClassName());
        // Like chat apps: the push connection runs as a foreground service with a quiet notification
        if (isPushService && !new File(getFilesDir(), KEEP_AWAKE_OFF_FLAG).exists()) return true;
        if (!isIgnoringBatteryOptimizations()) return true;
        return ForegroundServiceOemUtils.isXiaomi() && isPushService;
    }

    @Override
    public ComponentName startService(Intent service) {
        if (shouldRunInForeground(service)) {
            Log.d(TAG, "Starting in foreground mode.");
            service.putExtra(EXTRA_FOREGROUND, true);
            return super.startForegroundService(service);
        }
        return super.startService(service);
    }

    @RequiresApi(23)
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private static String getServiceName(Service service) {
        String serviceName = null;
        try {
            ForegroundServiceInfo annotation = service.getClass().getAnnotation(ForegroundServiceInfo.class);
            if (annotation != null) {
                serviceName = annotation.value();
                if (annotation.res() != 0) {
                    try {
                        serviceName = service.getString(annotation.res());
                    } catch (Exception ignored) {
                    }
                }
                if (!annotation.resName().isEmpty() && !annotation.resPackage().isEmpty()) {
                    try {
                        serviceName = service.getString(service.getResources().getIdentifier(annotation.resName(), "string", annotation.resPackage()));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (serviceName == null) {
            serviceName = service.getClass().getSimpleName();
        }
        return serviceName;
    }

    public static void completeForegroundService(Service service, Intent intent, String tag) {
        if (intent != null && intent.getBooleanExtra(EXTRA_FOREGROUND, false) && SDK_INT >= 26) {
            String serviceName = getServiceName(service);
            Log.d(tag, "Started " + serviceName + " in foreground mode.");
            try {
                Notification notification = buildForegroundNotification(service, serviceName);
                startForegroundCompat(service, serviceName.hashCode(), notification);
                Log.d(tag, "Notification: " + notification.toString());
            } catch (Exception e) {
                Log.w(tag, e);
            }
        }
    }

    /**
     * Android 14+ rejects startForeground() without a type. Use the type declared for the service in
     * the manifest, if there is one.
     */
    private static void startForegroundCompat(Service service, int id, Notification notification) {
        if (SDK_INT >= 29) {
            try {
                int type = service.getPackageManager()
                        .getServiceInfo(new ComponentName(service, service.getClass()), 0).getForegroundServiceType();
                if (type != 0) {
                    service.startForeground(id, notification, type);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot start with the declared foreground service type", e);
            }
        }
        service.startForeground(id, notification);
    }

    @RequiresApi(26)
    private static Notification buildForegroundNotification(Context context, String serviceName) {
        // Notification channel (high importance, mirrors MicroG-RE)
        String channelName = context.getString(R.string.foreground_service_notification_title);
        // Low importance: always there, but without sound or pop-ups
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(false);
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
        notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID);

        // Title and text
        String appTitle = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        String notifyTitle = context.getString(R.string.foreground_service_notification_title);
        String firstLine = context.getString(R.string.foreground_service_notification_text, serviceName);
        String secondLine = context.getString(R.string.foreground_service_notification_big_text, appTitle);

        // Open battery optimizations settings
        Intent batteryOptimizationIntent = ForegroundServiceOemUtils.getBatteryOptimizationIntent(context);
        PendingIntent batteryPendingIntent = PendingIntent.getActivity(
                context, 0, batteryOptimizationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Open notification settings in foreground service category
        Intent notificationCategoryIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
        PendingIntent notificationCategoryPendingIntent = PendingIntent.getActivity(
                context, 1, notificationCategoryIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Open settings activity when notification is tapped
        Intent mainSettingsIntent = new Intent();
        mainSettingsIntent.setClassName(context.getPackageName(), "org.microg.gms.ui.MainSettingsActivity");
        mainSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent mainSettingsPendingIntent = PendingIntent.getActivity(
                context, 2, mainSettingsIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Notification actions
        Action batteryAction = new Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_battery_action),
                context.getString(R.string.foreground_action_battery_optimization),
                batteryPendingIntent
        ).build();

        Action notificationAction = new Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_notification_action),
                context.getString(R.string.foreground_action_notification_settings),
                notificationCategoryPendingIntent
        ).build();

        Log.d(TAG, notifyTitle + " // " + firstLine + " // " + secondLine);

        return new Notification.Builder(context, channel.getId())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_background_notify)
                .setContentTitle(notifyTitle)
                .setContentText(firstLine)
                .setStyle(new Notification.BigTextStyle().bigText(firstLine + "\n" + secondLine))
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(false)
                .setContentIntent(mainSettingsPendingIntent)
                .addAction(batteryAction)
                .addAction(notificationAction)
                .build();
    }

}
