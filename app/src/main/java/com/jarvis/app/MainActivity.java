package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    WebView web;
    SpeechRecognizer sr;
    Intent recIntent;
    TextToSpeech tts;
    boolean ttsReady = false, listening = false;
    int uid = 0;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);

        web = new WebView(this);
        setContentView(web);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { }
            public void onDone(String id) { js("onSpeakDone()"); }
            public void onError(String id) { js("onSpeakDone()"); }
        });
    }

    void js(String code) { web.post(() -> web.evaluateJavascript(code, null)); }

    void err(String m) { js("onNativeError(" + JSONObject.quote(m) + ")"); }

    void begin() {
        if (!listening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listening = false;
            err("Распознавание речи не поддерживается на этом телефоне (нужно приложение Google).");
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onEvent(int t, Bundle p) { }
                public void onPartialResults(Bundle p) { deliver(p, false); }
                public void onResults(Bundle p) { deliver(p, true); ui.postDelayed(MainActivity.this::begin, 250); }
                public void onError(int e) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listening = false;
                        err("Нет доступа к микрофону. Разреши его в настройках приложения.");
                    } else if (e == 12 || e == 13) {
                        listening = false;
                        err("Нет русского пакета распознавания. Настройки → Google → Голосовой ввод → Офлайн-распознавание → Русский.");
                    } else {
                        ui.postDelayed(MainActivity.this::begin,
                                e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK ? 1200 : 300);
                    }
                }
            });
        }
        if (recIntent == null) {
            recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            recIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        }
        sr.startListening(recIntent);
    }

    void deliver(Bundle p, boolean fin) {
        ArrayList<String> l = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (l == null || l.isEmpty()) ? "" : l.get(0);
        js("onNative(" + JSONObject.quote(t) + "," + fin + ")");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sr != null) sr.destroy();
        if (tts != null) tts.shutdown();
    }

    class Bridge {
        @JavascriptInterface public void startListening() {
            ui.post(() -> { listening = true; begin(); });
        }

        @JavascriptInterface public void stopListening() {
            ui.post(() -> { listening = false; ui.removeCallbacksAndMessages(null); if (sr != null) sr.cancel(); });
        }

        @JavascriptInterface public void speak(String text, double rate) {
            ui.post(() -> {
                if (!ttsReady) { js("onSpeakDone()"); return; }
                tts.setSpeechRate((float) rate);
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + (uid++));
            });
        }

        @JavascriptInterface public void openUrl(String u) {
            ui.post(() -> {
                try {
                    Intent i = u.startsWith("intent:") ? Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.setComponent(null);
                    i.setSelector(null);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) { err("Не удалось открыть: " + u); }
            });
        }

        @JavascriptInterface public String listApps() {
            try {
                PackageManager pm = getPackageManager();
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
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            });
        }

        @JavascriptInterface public void volume(int d) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (d == 0) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            else for (int k = 0; k < 3; k++)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        k == 2 ? AudioManager.FLAG_SHOW_UI : 0);
        }

        @JavascriptInterface public void flash(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                cm.setTorchMode(cm.getCameraIdList()[0], on);
            } catch (Exception e) { err("Фонарик недоступен"); }
        }
    }
}
