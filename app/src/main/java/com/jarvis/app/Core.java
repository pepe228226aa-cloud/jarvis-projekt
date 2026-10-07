package com.jarvis.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Единое ядро Джарвиса: страница (WebView), распознавание речи и озвучка.
 * Живёт в процессе приложения, поэтому работает и когда окно закрыто.
 * Окно (MainActivity) лишь временно показывает готовый WebView.
 */
public class Core {
    private static Core inst;

    static Core get(Context c) {
        if (inst == null) inst = new Core(c.getApplicationContext());
        return inst;
    }

    final Context app;
    final MutableContextWrapper ctx;
    final WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());

    SpeechRecognizer sr;
    Intent recIntent;
    TextToSpeech tts;
    boolean ttsReady = false;
    volatile boolean activityVisible = false;

    boolean wantListen = false;   // хотим ли слушать (команда от страницы)
    int speaking = 0;             // сколько фраз сейчас озвучивается
    int uid = 0;
    long lastActivity = 0;        // последний «признак жизни» распознавателя
    long speakStart = 0;

    private final Runnable beginTask = this::begin;

    private Core(Context a) {
        app = a;
        JarvisService.ensureChannels(a);
        ctx = new MutableContextWrapper(a);
        web = new WebView(ctx);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                js("if(window.autoStart)autoStart()");
            }
        });
        if (Build.VERSION.SDK_INT >= 26)
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(a, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { }
            public void onDone(String id) { speakDone(); }
            public void onError(String id) { speakDone(); }
        });

        lastActivity = System.currentTimeMillis();
        ui.postDelayed(watchdog, 4000);
    }

    /* ---------- связь со страницей ---------- */

    // ВАЖНО: именно ui.post, а не web.post — у отсоединённого от окна WebView
    // View.post не выполняется, пока окно не появится снова.
    void js(String code) { ui.post(() -> web.evaluateJavascript(code, null)); }

    void err(String m) { js("onNativeError(" + JSONObject.quote(m) + ")"); }

    void micOff() { js("if(window.micOff)micOff()"); }

    /* ---------- распознавание речи ---------- */

    void scheduleBegin(long delay) {
        ui.removeCallbacks(beginTask);
        ui.postDelayed(beginTask, delay);
    }

    void destroyRecognizer() {
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) { }
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
    }

    void begin() {
        if (!wantListen || speaking > 0) return;
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            wantListen = false;
            err("Распознавание речи не поддерживается на этом телефоне (нужно приложение Google).");
            return;
        }
        try {
            if (sr == null) createRecognizer();
            lastActivity = System.currentTimeMillis();
            sr.startListening(recIntent);
        } catch (Exception e) {
            destroyRecognizer();
            scheduleBegin(1500);
        }
    }

    void createRecognizer() {
        sr = SpeechRecognizer.createSpeechRecognizer(app);
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) { lastActivity = System.currentTimeMillis(); }
            public void onBeginningOfSpeech() { lastActivity = System.currentTimeMillis(); }
            public void onRmsChanged(float v) { lastActivity = System.currentTimeMillis(); }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { lastActivity = System.currentTimeMillis(); }
            public void onEvent(int t, Bundle p) { }
            public void onPartialResults(Bundle p) { lastActivity = System.currentTimeMillis(); deliver(p, false); }
            public void onResults(Bundle p) {
                lastActivity = System.currentTimeMillis();
                deliver(p, true);
                scheduleBegin(250);
            }
            public void onError(int e) {
                lastActivity = System.currentTimeMillis();
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    wantListen = false;
                    err("Нет доступа к микрофону. Разреши его в настройках приложения.");
                } else if (e == 12 || e == 13) {
                    wantListen = false;
                    err("Нет русского пакета распознавания. Настройки → Google → Голосовой ввод → Офлайн-распознавание → Русский.");
                } else if (e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_NO_MATCH) {
                    scheduleBegin(150);          // тишина — просто слушаем дальше
                } else {
                    destroyRecognizer();         // любой сбой — пересоздаём распознаватель
                    scheduleBegin(e == 10 ? 3000 : 1000);
                }
            }
        });
        recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
    }

    void deliver(Bundle p, boolean fin) {
        ArrayList<String> l = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (l == null || l.isEmpty()) ? "" : l.get(0);
        js("onNative(" + JSONObject.quote(t) + "," + fin + ")");
    }

    // Сторож: если распознаватель молчит дольше 15 секунд — пересоздаём;
    // если озвучка «застряла» — сбрасываем.
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (speaking > 0 && now - speakStart > 30000) {
                speaking = 0;
                js("if(window.onSpeakReset)onSpeakReset()");
            }
            if (wantListen && speaking == 0 && now - lastActivity > 15000) {
                destroyRecognizer();
                scheduleBegin(100);
            }
            ui.postDelayed(this, 4000);
        }
    };

    /* ---------- озвучка ---------- */

    void speakDone() {
        ui.post(() -> {
            speaking = Math.max(0, speaking - 1);
            js("onSpeakDone()");
            if (speaking == 0 && wantListen) scheduleBegin(300);
        });
    }

    void speak(String text, float rate) {
        if (!ttsReady || text == null || text.trim().isEmpty()) { js("onSpeakDone()"); return; }
        if (text.length() > 3500) text = text.substring(0, 3500);
        if (speaking == 0) {                      // не слушаем самих себя
            ui.removeCallbacks(beginTask);
            if (sr != null) { try { sr.cancel(); } catch (Exception ignored) { } }
        }
        speaking++;
        speakStart = System.currentTimeMillis();
        tts.setSpeechRate(rate);
        int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + (uid++));
        if (r != TextToSpeech.SUCCESS) speakDone();   // колбэка не будет — закрываем сами
    }

    /* ---------- запуск других приложений ---------- */

    void startAct(Intent i, String label) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Из фона Android 10+ разрешает запуск окон только при «Поверх других окон».
        boolean direct = activityVisible || Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(app);
        try {
            if (direct) app.startActivity(i);
            else notifyOpen(i, label);
        } catch (Exception e) { err("Не удалось открыть: " + label); }
    }

    void notifyOpen(Intent i, String label) {
        PendingIntent pi = PendingIntent.getActivity(app, (int) (System.currentTimeMillis() & 0xfffffff), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, JarvisService.CH_OPEN) : new Notification.Builder(app);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Джарвис: открыть?")
                .setContentText(label + " — нажми, чтобы открыть")
                .setContentIntent(pi).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(2, b.build());
    }

    /* ---------- мост для страницы ---------- */

    class Bridge {
        @JavascriptInterface public void startListening() {
            ui.post(() -> { wantListen = true; scheduleBegin(0); });
        }

        @JavascriptInterface public void stopListening() {
            ui.post(() -> {
                wantListen = false;
                ui.removeCallbacks(beginTask);
                if (sr != null) { try { sr.cancel(); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface public void speak(String text, double rate) {
            ui.post(() -> Core.this.speak(text, (float) rate));
        }

        @JavascriptInterface public void openUrl(String u) {
            ui.post(() -> {
                try {
                    Intent i = u.startsWith("intent:") ? Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.setComponent(null);
                    i.setSelector(null);
                    startAct(i, u);
                } catch (Exception e) { err("Не удалось открыть: " + u); }
            });
        }

        @JavascriptInterface public String listApps() {
            try {
                PackageManager pm = app.getPackageManager();
                Intent m = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                JSONArray a = new JSONArray();
                for (ResolveInfo r : pm.queryIntentActivities(m, 0)) {
                    JSONObject o = new JSONObject();
                    o.put("label", r.loadLabel(pm).toString());
                    o.put("pkg", r.activityInfo.packageName);
                    a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface public void launch(String pkg) {
            ui.post(() -> {
                Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startAct(i, pkg);
            });
        }

        @JavascriptInterface public void volume(int d) {
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (d == 0) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                else for (int k = 0; k < 3; k++)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            k == 2 ? AudioManager.FLAG_SHOW_UI : 0);
            } catch (Exception e) { err("Не удалось изменить громкость"); }
        }

        @JavascriptInterface public void flash(boolean on) {
            try {
                CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
                cm.setTorchMode(cm.getCameraIdList()[0], on);
            } catch (Exception e) { err("Фонарик недоступен"); }
        }
    }
}
