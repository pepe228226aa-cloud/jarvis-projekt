package com.jarvis.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Сервис переднего плана: держит процесс, микрофон и процессор, пока Джарвис слушает. */
public class JarvisService extends Service {
    static final String CH_RUN = "jarvis_run";
    static final String CH_OPEN = "jarvis_open";
    static final String ACTION_STOP = "com.jarvis.app.STOP";
    static final int ID = 1;

    private PowerManager.WakeLock wl;

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Джарвис работает", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_OPEN, "Открыть по команде", NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, JarvisService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_RUN) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Джарвис")
                .setContentText("Слушаю. Скажи «Джарвис» и команду")
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", sp);
        return b.build();
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannels(this);
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(ID, n);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen");
        wl.acquire();
        Core.get(this);   // создаём ядро в процессе, чтобы слушать без окна
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            Core.get(this).micOff();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
