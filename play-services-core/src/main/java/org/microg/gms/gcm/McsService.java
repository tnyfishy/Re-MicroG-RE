/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.gcm;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.PendingIntentCompat;
import androidx.legacy.content.WakefulBroadcastReceiver;

import com.squareup.wire.Message;

import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.Constants;
import org.microg.gms.common.ForegroundServiceContext;
import org.microg.gms.common.ForegroundServiceInfo;
import org.microg.gms.common.PackageUtils;
import org.microg.gms.gcm.mcs.AppData;
import org.microg.gms.gcm.mcs.Close;
import org.microg.gms.gcm.mcs.DataMessageStanza;
import org.microg.gms.gcm.mcs.Extension;
import org.microg.gms.gcm.mcs.HeartbeatAck;
import org.microg.gms.gcm.mcs.HeartbeatPing;
import org.microg.gms.gcm.mcs.IqStanza;
import org.microg.gms.gcm.mcs.LoginRequest;
import org.microg.gms.gcm.mcs.LoginResponse;
import org.microg.gms.gcm.mcs.Setting;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import okio.ByteString;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.os.Build.VERSION.SDK_INT;
import static org.microg.gms.common.PackageUtils.warnIfNotPersistentProcess;
import static org.microg.gms.gcm.ExtensionsKt.ACTION_GCM_CONNECTED;
import static org.microg.gms.gcm.GcmConstants.*;
import static org.microg.gms.gcm.McsConstants.*;

@ForegroundServiceInfo(value = "Cloud messaging", resName = "service_name_mcs", resPackage = "com.google.android.gms")
public class McsService extends Service implements Handler.Callback {
    private static final String TAG = "GmsGcmMcsSvc";

    public static final String SELF_CATEGORY = "com.google.android.gsf.gtalkservice";
    public static final String IDLE_NOTIFICATION = "IdleNotification";
    public static final String FROM_FIELD = "gcm@android.com";

    public static final String SERVICE_HOST = "mtalk.google.com";
    // A few ports are available: 443, 5228-5230 but also 5222-5223
    // See https://github.com/microg/GmsCore/issues/408
    // Likely if the main port 5228 is blocked by a firewall, the other 52xx are blocked as well
    public static final int[] SERVICE_PORTS = {5228, 443};

    private static final int WAKELOCK_TIMEOUT = 5000;
    // On bad mobile network a ping can take >60s, so we wait for an ACK for 90s
    private static final int HEARTBEAT_ACK_AFTER_PING_TIMEOUT_MS = 90000;

    // Dead-connection detection must not wait 90s when the user asked for fast pings
    private static final int MIN_HEARTBEAT_ACK_TIMEOUT_MS = 15000;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int HANDSHAKE_TIMEOUT_MS = 20000;
    private static final int MAX_RECONNECT_DELAY_MS = 30000;
    // Up to this interval (or always, with "keep awake") heartbeats run on a timer inside the
    // process, with the CPU kept awake. Alarms are throttled by the system while the device idles.
    private static final int LIVE_TIMER_MAX_MS = 60000;
    private static final int WATCHDOG_REARM_MS = 20000;
    private static final int PREFS_CACHE_MS = 5000;
    private static final int MSG_NETWORK_CHANGED = 50;
    private static final String PUSH_RUNNING_FILE = "push_running";
    public static final String ACTION_NETWORK_CHANGED = "org.microg.gms.gcm.NETWORK_CHANGED";

    private static long lastHeartbeatPingElapsedRealtime = -1;
    // First ping that still has no sign of life from the server (-1: none pending)
    private static long firstUnackedPingElapsedRealtime = -1;
    private static long lastWatchdogArm = 0;
    private static String connectedNetwork = null;
    private static PowerManager.WakeLock keepLock;
    private static WifiManager.WifiLock wifiLock;
    private static GcmPrefs cachedPrefs;
    private static long cachedPrefsAt;
    private static long lastHeartbeatAckElapsedRealtime = -1;
    private static long lastIncomingNetworkRealtime = 0;
    private static long startTimestamp = 0;
    public static String activeNetworkPref = null;
    private boolean wasTornDown = false;
    private AtomicInteger nextMessageId = new AtomicInteger(0x1000000);

    private static Socket sslSocket;
    private static McsInputStream inputStream;
    private static McsOutputStream outputStream;

    private PendingIntent heartbeatIntent;

    private static HandlerThread handlerThread;
    private static Handler rootHandler;

    private GcmDatabase database;

    private AlarmManager alarmManager;
    private PowerManager powerManager;
    private static PowerManager.WakeLock wakeLock;

    private static long currentDelay = 0;

    private Intent connectIntent;

    private static int maxTtl = 24 * 60 * 60;

    @Nullable
    private Method getUserIdMethod;
    @Nullable
    private Object deviceIdleController;
    @Nullable
    private Method addPowerSaveTempWhitelistAppMethod;
    @Nullable
    @RequiresApi(31)
    private Object powerExemptionManager;
    @Nullable
    @RequiresApi(31)
    private Method addToTemporaryAllowListMethod;

    private class HandlerThread extends Thread {

        public HandlerThread() {
            setName("McsHandler");
        }

        @Override
        public void run() {
            Looper.prepare();
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcs");
            wakeLock.setReferenceCounted(false);
            synchronized (McsService.class) {
                rootHandler = new Handler(Looper.myLooper(), McsService.this);
                if (connectIntent != null) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_CONNECT, connectIntent));
                    WakefulBroadcastReceiver.completeWakefulIntent(connectIntent);
                }
            }
            Looper.loop();
        }
    }

    private static void logd(Context context, String msg) {
        if (context == null || GcmPrefs.get(context).isGcmLogEnabled()) Log.d(TAG, msg);
    }

    @Override
    @SuppressLint("PrivateApi")
    public void onCreate() {
        super.onCreate();
        TriggerReceiver.register(this);
        database = new GcmDatabase(this);
        heartbeatIntent = PendingIntentCompat.getService(this, 0, new Intent(ACTION_HEARTBEAT, null, this, McsService.class), 0, false);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        // Root users can opt in to extra keep-alive measures (see RootKeepAlive)
        org.microg.gms.ui.RootKeepAlive.applyIfEnabled(this);
        registerNetworkWake(this);
        // Tells the root watchdog (see RootKeepAlive) that the push service is meant to be running
        try {
            new File(getFilesDir(), PUSH_RUNNING_FILE).createNewFile();
        } catch (Exception e) {
            Log.w(TAG, "Cannot write the running marker", e);
        }
        if (SDK_INT >= 23 && checkSelfPermission("android.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST") == PackageManager.PERMISSION_GRANTED) {
            try {
                if (SDK_INT >= 31) {
                    Class<?> powerExemptionManagerClass = Class.forName("android.os.PowerExemptionManager");
                    powerExemptionManager = getSystemService(powerExemptionManagerClass);
                    addToTemporaryAllowListMethod =
                            powerExemptionManagerClass.getMethod("addToTemporaryAllowList", String.class, int.class, String.class, long.class);
                } else {
                    String deviceIdleControllerName = "deviceidle";
                    try {
                        Field field = Context.class.getField("DEVICE_IDLE_CONTROLLER");
                        deviceIdleControllerName = (String) field.get(null);
                    } catch (Exception ignored) {
                    }
                    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, deviceIdleControllerName);
                    if (binder != null) {
                        deviceIdleController = Class.forName("android.os.IDeviceIdleController$Stub")
                                .getMethod("asInterface", IBinder.class).invoke(null, binder);
                        getUserIdMethod = UserHandle.class.getMethod("getUserId", int.class);
                        addPowerSaveTempWhitelistAppMethod = deviceIdleController.getClass()
                                .getMethod("addPowerSaveTempWhitelistApp", String.class, long.class, int.class, String.class);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }
        synchronized (McsService.class) {
            if (handlerThread == null) {
                handlerThread = new HandlerThread();
                handlerThread.start();
            }
        }
    }

    public static void stop(Context context) {
        new File(context.getFilesDir(), PUSH_RUNNING_FILE).delete();
        context.stopService(new Intent(context, McsService.class));
        closeAll();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        alarmManager.cancel(heartbeatIntent);
        releaseKeepLocks();
        closeAll();
        database.close();
        super.onDestroy();
        // If the system or the user killed the service while push is still enabled, come back at once
        reconnectIfEnabled("service destroyed");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Swiping the app away from Recents ("close all") must not drop the push connection
        reconnectIfEnabled("task removed");
    }

    private void reconnectIfEnabled(String reason) {
        try {
            if (GcmPrefs.get(this).isEnabled()) {
                logd(this, "Scheduling reconnect: " + reason);
                resetCurrentDelay();
                scheduleReconnect(this);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule reconnect after " + reason, e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Settings come from a content provider; the heartbeat must not query it on every tick. */
    private static synchronized GcmPrefs prefs(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (cachedPrefs == null || now - cachedPrefsAt > PREFS_CACHE_MS) {
            cachedPrefs = GcmPrefs.get(context);
            cachedPrefsAt = now;
        }
        return cachedPrefs;
    }

    public synchronized static boolean isConnected(Context context) {
        warnIfNotPersistentProcess(McsService.class);
        if (inputStream == null || !inputStream.isAlive() || outputStream == null || !outputStream.isAlive()) {
            logd(null, "Connection is not enabled or dead.");
            return false;
        }
        // consider connection to be dead if we did not receive an ack within 90s to our ping
        int heartbeatMs = prefs(context).getHeartbeatMsFor(activeNetworkPref);
        // if disabled for active network, heartbeatMs will be -1
        if (heartbeatMs < 0) {
            closeAll();
            return false;
        } else {
            // Measured from the first ping nobody answered: with a short interval a newer ping is
            // always only moments old, which would hide a dead connection forever.
            long timeout = Math.min(HEARTBEAT_ACK_AFTER_PING_TIMEOUT_MS, Math.max(MIN_HEARTBEAT_ACK_TIMEOUT_MS, heartbeatMs * 3L));
            long timeSinceFirstUnacked = firstUnackedPingElapsedRealtime < 0 ? 0 : SystemClock.elapsedRealtime() - firstUnackedPingElapsedRealtime;
            if (firstUnackedPingElapsedRealtime >= 0 && timeSinceFirstUnacked > timeout) {
                logd(null, "No answer for " + timeSinceFirstUnacked / 1000 + "s, connection assumed to be dead after " + timeout / 1000 + "s");
                GcmPrefs.get(context).learnTimeout(context, activeNetworkPref);
                return false;
            }
        }
        return true;
    }

    public static long getStartTimestamp() {
        warnIfNotPersistentProcess(McsService.class);
        return startTimestamp;
    }

    public static void scheduleReconnect(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        long delay = getCurrentDelay();
        logd(context, "Scheduling reconnect in " + delay / 1000 + " seconds...");
        PendingIntent pi = PendingIntentCompat.getBroadcast(context, 1, new Intent(ACTION_RECONNECT, null, context, TriggerReceiver.class), 0, false);
        if (SDK_INT >= 23) {
            // RE changes start
            try {
                if (SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
                    return;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Failed to schedule exact alarm for reconnect", e);
            }
            alarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
            // RE changes end
        } else {
            alarmManager.set(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
        }
    }

    public void scheduleHeartbeat(Context context) {
        int heartbeatMs = prefs(this).getHeartbeatMsFor(activeNetworkPref);
        if (heartbeatMs < 0) {
            releaseKeepLocks();
            closeAll();
            return;
        }
        logd(context, "Scheduling heartbeat in " + heartbeatMs / 1000 + " seconds...");
        boolean live = heartbeatMs <= LIVE_TIMER_MAX_MS || KeepAwake.isEnabled(this);
        if (live && rootHandler != null) {
            holdKeepLocks();
            rootHandler.removeMessages(MSG_HEARTBEAT);
            rootHandler.sendMessageDelayed(rootHandler.obtainMessage(MSG_HEARTBEAT, "timer"), heartbeatMs);
            // Safety net in case the timer is ever lost; it is only re-armed now and then
            long now = SystemClock.elapsedRealtime();
            if (now - lastWatchdogArm > WATCHDOG_REARM_MS) {
                lastWatchdogArm = now;
                armHeartbeatAlarm(context, Math.max(heartbeatMs, 60000));
            }
            return;
        }
        releaseKeepLocks();
        armHeartbeatAlarm(context, heartbeatMs);
    }

    private void armHeartbeatAlarm(Context context, long heartbeatMs) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (SDK_INT >= 23) {
            // This is supposed to work even when running in idle and without battery optimization disabled
            try {
                if (SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs, heartbeatIntent);
                    return;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Failed to schedule exact alarm for heartbeat", e);
            }
            alarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs, heartbeatIntent);
        } else if (SDK_INT >= 19) {
            // With KitKat, the alarms become inexact by default, but with the newly available setWindow we can get inexact alarms with guarantees.
            // Schedule the alarm to fire within the interval [heartbeatMs/3*4, heartbeatMs]
            alarmManager.setWindow(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs / 4 * 3, heartbeatMs / 4,
                    heartbeatIntent);
        } else {
            alarmManager.set(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs, heartbeatIntent);
        }
    }

    /** CPU and Wi-Fi stay awake while the connection is up, so timers and incoming data are not delayed. */
    private void holdKeepLocks() {
        synchronized (McsService.class) {
            holdKeepLocksLocked();
        }
    }

    private void holdKeepLocksLocked() {
        try {
            if (keepLock == null) {
                keepLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcs:live");
                keepLock.setReferenceCounted(false);
            }
            if (!keepLock.isHeld()) keepLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Cannot hold the wake lock", e);
        }
        try {
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mcs:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Cannot hold the Wi-Fi lock", e);
        }
    }

    private static synchronized void releaseKeepLocks() {
        try {
            if (keepLock != null && keepLock.isHeld()) keepLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private static String describeActiveNetwork(Context context) {
        if (SDK_INT < 23) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            return network == null ? null : String.valueOf(network.getNetworkHandle());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Asks the system to wake the push process whenever a network comes or goes, even after the
     * process was killed. The receiver is {@link TriggerReceiver}.
     */
    private static void registerNetworkWake(Context context) {
        if (SDK_INT < 26) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            Intent intent = new Intent(ACTION_NETWORK_CHANGED, null, context, TriggerReceiver.class);
            // The system adds the network to the intent, so it must be mutable
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            cm.registerNetworkCallback(request, PendingIntent.getBroadcast(context, 2, intent, flags));
        } catch (Exception e) {
            Log.w(TAG, "Cannot register the network wake-up", e);
        }
    }

    /** The network changed (Wi-Fi <-> mobile data...): a socket from the old one is dead, so reconnect now. */
    public static void onNetworkChanged() {
        Handler handler = rootHandler;
        if (handler != null) handler.sendMessage(handler.obtainMessage(MSG_NETWORK_CHANGED));
    }

    public synchronized static long getCurrentDelay() {
        // 3s, 6s, 12s, 24s, then every 30s: coming back quickly matters more than saving a few tries
        long delay = currentDelay == 0 ? 3000 : currentDelay;
        currentDelay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
        return delay;
    }

    public synchronized static void resetCurrentDelay() {
        currentDelay = 0;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ForegroundServiceContext.completeForegroundService(this, intent, TAG);
        synchronized (McsService.class) {
            if (rootHandler != null) {
                if (intent == null) return START_REDELIVER_INTENT;
                wakeLock.acquire(WAKELOCK_TIMEOUT);
                Object reason = intent.hasExtra(EXTRA_REASON) ? intent.getExtras().get(EXTRA_REASON) : intent;
                if (ACTION_CONNECT.equals(intent.getAction())) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_CONNECT, reason));
                } else if (ACTION_HEARTBEAT.equals(intent.getAction())) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_HEARTBEAT, reason));
                } else if (ACTION_SEND.equals(intent.getAction())) {
                    handleSendMessage(intent);
                } else if (ACTION_ACK.equals(intent.getAction())) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_ACK, reason));
                }
                WakefulBroadcastReceiver.completeWakefulIntent(intent);
            } else if (connectIntent == null) {
                connectIntent = intent;
            } else if (intent != null) {
                WakefulBroadcastReceiver.completeWakefulIntent(intent);
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void handleSendMessage(Intent intent) {
        String messageId = intent.getStringExtra(EXTRA_MESSAGE_ID);
        String collapseKey = intent.getStringExtra(EXTRA_COLLAPSE_KEY);

        Messenger messenger = intent.getParcelableExtra(EXTRA_MESSENGER);
        intent.removeExtra(EXTRA_MESSENGER);

        Parcelable app = intent.getParcelableExtra(EXTRA_APP);
        String packageName = null;
        if (app instanceof PendingIntent) {
            packageName = PackageUtils.packageFromPendingIntent((PendingIntent) app);
        }
        if (packageName == null) {
            Log.w(TAG, "Failed to send message, missing package name");
            return;
        }
        if (packageName.equals(getPackageName()) && intent.hasExtra(EXTRA_APP_OVERRIDE)) {
            packageName = intent.getStringExtra(EXTRA_APP_OVERRIDE);
            intent.removeExtra(EXTRA_APP_OVERRIDE);
        }
        intent.removeExtra(EXTRA_APP);

        int ttl;
        try {
            if (intent.hasExtra(EXTRA_TTL)) {
                ttl = Integer.parseInt(intent.getStringExtra(EXTRA_TTL));
                if (ttl < 0 || ttl > maxTtl) {
                    ttl = maxTtl;
                }
            } else {
                ttl = maxTtl;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, e);
            ttl = maxTtl;
        }

        String to = intent.getStringExtra(EXTRA_SEND_TO);
        if (to == null) {
            // TODO: error missing_to
            Log.w(TAG, "missing to");
            return;
        }

        String from = intent.getStringExtra(EXTRA_SEND_FROM);
        if (from != null) {
            intent.removeExtra(EXTRA_SEND_FROM);
        } else {
            from = intent.getStringExtra(EXTRA_FROM);
        }
        if (from == null) {
            GcmDatabase.Registration reg = database.getRegistration(packageName, PackageUtils.firstSignatureDigest(this, packageName));
            if (reg != null) from = reg.registerId;
        }
        if (from == null) {
            Log.e(TAG, "Can't send message, missing from!");
            return;
        }

        String registrationId = intent.getStringExtra(EXTRA_REGISTRATION_ID);
        intent.removeExtra(EXTRA_REGISTRATION_ID);

        List<AppData> appData = new ArrayList<>();
        Bundle extras = intent.getExtras();
        for (String key : extras.keySet()) {
            if (!key.startsWith("google.")) {
                Object val = extras.get(key);
                if (val instanceof String) {
                    appData.add(new AppData.Builder().key(key).value_((String) val).build());
                }
            }
        }

        byte[] rawDataArray = intent.getByteArrayExtra("rawData");
        ByteString rawData = rawDataArray != null ? ByteString.of(rawDataArray) : null;

        try {
            DataMessageStanza msg = new DataMessageStanza.Builder()
                    .sent(System.currentTimeMillis() / 1000L)
                    .id(Integer.toHexString(nextMessageId.incrementAndGet()))
                    .persistent_id(messageId)
                    .token(collapseKey)
                    .from(from)
                    .reg_id(registrationId)
                    .to(to)
                    .category(packageName)
                    .raw_data(rawData)
                    .ttl(ttl)
                    .app_data(appData).build();

            send(MCS_DATA_MESSAGE_STANZA_TAG, msg);
            if (messenger != null) {
                messenger.send(android.os.Message.obtain());
            }
            database.noteAppMessage(packageName, DataMessageStanza.ADAPTER.encodedSize(msg));
        } catch (Exception e) {
            Log.w(TAG, e);
        }
    }

    private void startHandshake(SSLSocket sslSocket) throws Exception {
        if (SDK_INT >= 24) {
            SSLParameters params = sslSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(params);
        }
        sslSocket.startHandshake();
        if (SDK_INT < 24) {
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(SERVICE_HOST, sslSocket.getSession())) {
                throw new SSLException("Hostname verification failed for " + SERVICE_HOST);
            }
        }
    }

    private void connect(int port) throws Exception {
        this.wasTornDown = false;

        logd(this, "Starting MCS connection to port " + port + "...");
        Socket socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        // A stuck attempt on a bad network would block the whole connection thread
        socket.connect(new InetSocketAddress(SERVICE_HOST, port), CONNECT_TIMEOUT_MS);
        logd(this, "Connected to " + SERVICE_HOST + ":" + port);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        sslSocket = SSLContext.getDefault().getSocketFactory().createSocket(socket, SERVICE_HOST, port, true);
        startHandshake((SSLSocket) sslSocket);
        socket.setSoTimeout(0);
        logd(this, "Activated SSL with " + SERVICE_HOST + ":" + port);
        inputStream = new McsInputStream(sslSocket.getInputStream(), rootHandler);
        outputStream = new McsOutputStream(sslSocket.getOutputStream(), rootHandler);
        inputStream.start();
        outputStream.start();

        startTimestamp = System.currentTimeMillis();
        lastHeartbeatPingElapsedRealtime = SystemClock.elapsedRealtime();
        lastHeartbeatAckElapsedRealtime = SystemClock.elapsedRealtime();
        lastIncomingNetworkRealtime = SystemClock.elapsedRealtime();
        firstUnackedPingElapsedRealtime = -1;
        connectedNetwork = describeActiveNetwork(this);
        scheduleHeartbeat(this);
    }

    private synchronized void connect() {
        closeAll();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        activeNetworkPref = GcmPrefs.get(this).getNetworkPrefForInfo(activeNetworkInfo);
        if (!GcmPrefs.get(this).isEnabledFor(activeNetworkInfo)) {
            if (activeNetworkInfo != null) {
                logd(this, "Don't connect, because disabled for " + activeNetworkInfo.getTypeName());
            } else {
                logd(this, "Don't connect, no active network");
            }
            scheduleReconnect(this);
            return;
        }

        Exception exception = null;
        for (int port : SERVICE_PORTS) {
            try {
                connect(port);
                return;
            } catch (Exception e) {
                exception = e;
                Log.w(TAG, "Exception while connecting to " + SERVICE_HOST + ":" + port, e);
                closeAll();
            }
        }

        logd(this, "Unable to connect to all different ports, retrying later");
        rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, exception));
    }

    private void handleClose(Close close) {
        throw new RuntimeException("Server requested close!");
    }

    private void handleLoginResponse(LoginResponse loginResponse) {
        if (loginResponse.error == null) {
            GcmPrefs.clearLastPersistedId(this);
            logd(this, "Logged in");
            notifyGcmConnected();
            wakeLock.release();
        } else {
            throw new RuntimeException("Could not login: " + loginResponse.error);
        }
    }

    private void notifyGcmConnected() {
        Intent intent = new Intent(ACTION_GCM_CONNECTED);
        intent.setPackage(Constants.GMS_PACKAGE_NAME);
        sendBroadcast(intent);
    }

    private void handleCloudMessage(DataMessageStanza message) {
        if (message.persistent_id != null) {
            GcmPrefs.get(this).extendLastPersistedId(this, message.persistent_id);
        }
        if (SELF_CATEGORY.equals(message.category)) {
            handleSelfMessage(message);
        } else {
            handleAppMessage(message);
        }
    }

    private void handleHeartbeatPing(HeartbeatPing ping) {
        HeartbeatAck.Builder ack = new HeartbeatAck.Builder().status(ping.status);
        if (inputStream.newStreamIdAvailable()) {
            ack.last_stream_id_received(inputStream.getStreamId());
        }
        send(MCS_HEARTBEAT_ACK_TAG, ack.build());
    }

    private void handleHeartbeatAck(HeartbeatAck ack) {
        prefs(this).learnReached(this, activeNetworkPref, SystemClock.elapsedRealtime() - lastIncomingNetworkRealtime);
        lastHeartbeatAckElapsedRealtime = SystemClock.elapsedRealtime();
        firstUnackedPingElapsedRealtime = -1;
        wakeLock.release();
    }

    private LoginRequest buildLoginRequest() {
        LastCheckinInfo info = LastCheckinInfo.read(this);
        return new LoginRequest.Builder()
                .adaptive_heartbeat(false)
                .auth_service(LoginRequest.AuthService.ANDROID_ID)
                .auth_token(Long.toString(info.getSecurityToken()))
                .id("android-" + SDK_INT)
                .domain("mcs.android.com")
                .device_id("android-" + Long.toHexString(info.getAndroidId()))
                .network_type(1)
                .resource(Long.toString(info.getAndroidId()))
                .user(Long.toString(info.getAndroidId()))
                .use_rmq2(true)
                .setting(Collections.singletonList(new Setting.Builder().name("new_vc").value_("1").build()))
                .received_persistent_id(GcmPrefs.get(this).getLastPersistedIds())
                .build();
    }

    private void handleAppMessage(DataMessageStanza msg) {
        String packageName = msg.category;
        database.noteAppMessage(packageName, DataMessageStanza.ADAPTER.encodedSize(msg));
        GcmDatabase.App app = database.getApp(packageName);

        // Apps patched for the renamed base package listen on the package-scoped action;
        // deliver with whichever action the app actually has a receiver registered for.
        String deliveryAction = ACTION_C2DM_RECEIVE;
        Intent probe = new Intent(ACTION_C2DM_RECEIVE_PACKAGE);
        probe.setPackage(packageName);
        List<ResolveInfo> packageReceivers = getPackageManager().queryBroadcastReceivers(probe, 0);
        if (packageReceivers != null && !packageReceivers.isEmpty()) {
            deliveryAction = ACTION_C2DM_RECEIVE_PACKAGE;
        }

        Intent intent = new Intent();
        intent.setAction(deliveryAction);
        intent.putExtra(EXTRA_FROM, msg.from);
        intent.putExtra(EXTRA_MESSAGE_ID, msg.id);
        if (msg.sent != null && msg.sent != 0) intent.putExtra(EXTRA_SENT_TIME, msg.sent);
        if (msg.ttl != null && msg.ttl != 0) intent.putExtra(EXTRA_TTL, msg.ttl);
        if (msg.persistent_id != null) intent.putExtra(EXTRA_MESSAGE_ID, msg.persistent_id);
        if (msg.token != null) intent.putExtra(EXTRA_COLLAPSE_KEY, msg.token);
        if (msg.raw_data != null) {
            intent.putExtra(EXTRA_RAWDATA_BASE64, Base64.encodeToString(msg.raw_data.toByteArray(), Base64.DEFAULT));
            intent.putExtra(EXTRA_RAWDATA, msg.raw_data.toByteArray());
        }
        // Push messages go through the foreground broadcast queue, so they are not held back
        // behind ordinary background broadcasts
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (app.wakeForDelivery) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        } else {
            intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
        }
        for (AppData appData : msg.app_data) {
            if (appData.key == null) continue;
            String key = appData.key.toLowerCase(Locale.US);
            // Some keys are exclusively set by the client and not the app.
            if (key.equals(EXTRA_FROM) || (key.startsWith("google.") && !key.startsWith("google.c."))) continue;
            intent.putExtra(appData.key, appData.value_);
        }

        String receiverPermission = null;
        try {
            String name = packageName + ".permission.C2D_MESSAGE";
            PermissionInfo info = getPackageManager().getPermissionInfo(name, 0);
            if (info.packageName.equals(packageName)) {
                receiverPermission = name;
            }
        } catch (Exception ignored) {
            // Keep null, no valid permission found
        }

        if (receiverPermission == null) {
            // Without receiver permission, we only restrict by package name
            if (app.wakeForDelivery) addPowerSaveTempWhitelistApp(packageName);
            logd(this, "Deliver message to all receivers in package " + packageName);
            intent.setPackage(packageName);
            sendOrderedBroadcast(intent, null);
        } else {
            List<ResolveInfo> infos = getPackageManager().queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER);
            if (infos == null || infos.isEmpty()) {
                logd(this, "No target for message, wut?");
            } else {
                for (ResolveInfo resolveInfo : infos) {
                    Intent targetIntent = new Intent(intent);
                    targetIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
                    if (resolveInfo.activityInfo.packageName.equals(packageName)) {
                        if (app.wakeForDelivery) addPowerSaveTempWhitelistApp(packageName);
                        // We don't need receiver permission for our own package
                        logd(this, "Deliver message to own receiver " + resolveInfo);
                        sendOrderedBroadcast(targetIntent, null);
                    } else if (resolveInfo.filter.hasCategory(packageName)) {
                        // Permission required
                        logd(this, "Deliver message to third-party receiver (with permission check)" + resolveInfo);
                        sendOrderedBroadcast(targetIntent, receiverPermission);
                    }
                }
            }
        }
    }

    private void addPowerSaveTempWhitelistApp(String packageName) {
        if (SDK_INT >= 31) {
            try {
                if (addToTemporaryAllowListMethod != null && powerExemptionManager != null) {
                    logd(this, "Adding app " + packageName + " to the temp allowlist");
                    addToTemporaryAllowListMethod.invoke(powerExemptionManager, packageName, 0, "GCM Push", 10000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error adding app" + packageName + " to the temp allowlist.", e);
            }
        } else if (SDK_INT >= 23) {
            try {
                if (getUserIdMethod != null && addPowerSaveTempWhitelistAppMethod != null && deviceIdleController != null) {
                    int userId = (int) getUserIdMethod.invoke(null, getPackageManager().getApplicationInfo(packageName, 0).uid);
                    logd(this, "Adding app " + packageName + " for userId " + userId + " to the temp whitelist");
                    addPowerSaveTempWhitelistAppMethod.invoke(deviceIdleController, packageName, 10000, userId, "GCM Push");
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }
    }

    private void handleSelfMessage(DataMessageStanza msg) {
        for (AppData appData : msg.app_data) {
            if (IDLE_NOTIFICATION.equals(appData.key)) {
                DataMessageStanza.Builder msgResponse = new DataMessageStanza.Builder()
                        .id(Integer.toHexString(nextMessageId.incrementAndGet()))
                        .from(FROM_FIELD)
                        .sent(System.currentTimeMillis() / 1000)
                        .ttl(0)
                        .category(SELF_CATEGORY)
                        .app_data(Collections.singletonList(new AppData.Builder().key(IDLE_NOTIFICATION).value_("false").build()));
                if (inputStream.newStreamIdAvailable()) {
                    msgResponse.last_stream_id_received(inputStream.getStreamId());
                }
                send(MCS_DATA_MESSAGE_STANZA_TAG, msgResponse.build());
            }
        }
    }

    private void send(int type, Message message) {
        rootHandler.sendMessage(rootHandler.obtainMessage(MSG_OUTPUT, type, 0, message));
    }

    private void sendOutputStream(int what, int arg, Object obj) {
        McsOutputStream os = outputStream;
        if (os != null && os.isAlive()) {
            Handler outputHandler = os.getHandler();
            if (outputHandler != null)
                outputHandler.sendMessage(outputHandler.obtainMessage(what, arg, 0, obj));
        }
    }

    @Override
    public boolean handleMessage(android.os.Message msg) {
        switch (msg.what) {
            case MSG_INPUT:
                handleInput(msg.arg1, (Message) msg.obj);
                return true;
            case MSG_OUTPUT:
                sendOutputStream(MSG_OUTPUT, msg.arg1, msg.obj);
                return true;
            case MSG_INPUT_ERROR:
            case MSG_OUTPUT_ERROR:
                logd(this, "I/O error: " + msg.obj);
                if (msg.obj instanceof SocketException) {
                    SocketException e = (SocketException) msg.obj;
                    if ("Connection reset".equals(e.getMessage())) {
                        GcmPrefs.get(this).learnTimeout(this, activeNetworkPref);
                    }
                }
                rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, msg.obj));
                return true;
            case MSG_TEARDOWN:
                logd(this, "Teardown initiated, reason: " + msg.obj);
                handleTeardown(msg);
                return true;
            case MSG_CONNECT:
                logd(this, "Connect initiated, reason: " + msg.obj);
                if (!isConnected(this)) {
                    connect();
                }
                return true;
            case MSG_HEARTBEAT:
                logd(this, "Heartbeat initiated, reason: " + msg.obj);
                if (isConnected(this)) {
                    HeartbeatPing.Builder ping = new HeartbeatPing.Builder();
                    if (inputStream.newStreamIdAvailable()) {
                        ping.last_stream_id_received(inputStream.getStreamId());
                    }
                    send(MCS_HEARTBEAT_PING_TAG, ping.build());
                    lastHeartbeatPingElapsedRealtime = SystemClock.elapsedRealtime();
                    if (firstUnackedPingElapsedRealtime < 0) firstUnackedPingElapsedRealtime = lastHeartbeatPingElapsedRealtime;
                    scheduleHeartbeat(this);
                } else {
                    logd(this, "Ignoring heartbeat, not connected!");
                    scheduleReconnect(this);
                }
                return true;
            case MSG_NETWORK_CHANGED: {
                String now = describeActiveNetwork(this);
                if (inputStream != null && now != null && !now.equals(connectedNetwork)) {
                    logd(this, "Network changed from " + connectedNetwork + " to " + now + ", reconnecting");
                    resetCurrentDelay();
                    connect();
                }
                return true;
            }
            case MSG_ACK:
                logd(this, "Ack initiated, reason: " + msg.obj);
                if (isConnected(this)) {
                    IqStanza.Builder iq = new IqStanza.Builder()
                            .type(IqStanza.IqType.SET)
                            .id("")
                            .extension(new Extension.Builder().id(13).data_(ByteString.EMPTY).build()) // StreamAck
                            .status(0L);
                    if (inputStream.newStreamIdAvailable()) {
                        iq.last_stream_id_received(inputStream.getStreamId());
                    }
                    send(MCS_IQ_STANZA_TAG, iq.build());
                } else {
                    logd(this, "Ignoring ack, not connected!");
                }
                return true;
            case MSG_OUTPUT_READY:
                logd(this, "Sending login request...");
                send(MCS_LOGIN_REQUEST_TAG, buildLoginRequest());
                return true;
            case MSG_OUTPUT_DONE:
                handleOutputDone(msg);
                return true;
        }
        Log.w(TAG, "Unknown message (" + msg.what + "): " + msg);
        return false;
    }

    private void handleOutputDone(android.os.Message msg) {
        switch (msg.arg1) {
            case MCS_HEARTBEAT_PING_TAG:
                wakeLock.release();
                break;
            default:
        }
    }

    private void handleInput(int type, Message message) {
        try {
            switch (type) {
                case MCS_DATA_MESSAGE_STANZA_TAG:
                    handleCloudMessage((DataMessageStanza) message);
                    break;
                case MCS_HEARTBEAT_PING_TAG:
                    handleHeartbeatPing((HeartbeatPing) message);
                    break;
                case MCS_HEARTBEAT_ACK_TAG:
                    handleHeartbeatAck((HeartbeatAck) message);
                    break;
                case MCS_CLOSE_TAG:
                    handleClose((Close) message);
                    break;
                case MCS_LOGIN_RESPONSE_TAG:
                    handleLoginResponse((LoginResponse) message);
                    break;
                default:
                    Log.w(TAG, "Unknown message: " + message);
            }
            resetCurrentDelay();
            lastIncomingNetworkRealtime = SystemClock.elapsedRealtime();
            firstUnackedPingElapsedRealtime = -1;
        } catch (Exception e) {
            Log.w(TAG, "Exception when handling input: " + message, e);
            rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, e));
        }
    }

    private static void tryClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeAll() {
        logd(null, "Closing all sockets...");
        tryClose(inputStream);
        tryClose(outputStream);
        if (sslSocket != null) {
            try {
                sslSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleTeardown(android.os.Message msg) {
        if (wasTornDown) {
            // This can get called multiple times from different places via MSG_TEARDOWN
            // this causes the reconnect delay to increase with each call to scheduleReconnect(),
            // increasing the time we are disconnected.
            logd(this, "Was torn down already, not doing it again");
            return;
        }
        wasTornDown = true;
        closeAll();

        scheduleReconnect(this);

        alarmManager.cancel(heartbeatIntent);
        if (rootHandler != null) rootHandler.removeMessages(MSG_HEARTBEAT);
        releaseKeepLocks();
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }
}
