package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;

public class MainActivity extends Activity {
    FrameLayout root;
    Core core;
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        root = new FrameLayout(this);
        setContentView(root);

        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) init();
        else requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        init();
    }

    void init() {
        if (core != null) return;
        core = Core.get(this);
        try {
            Intent s = new Intent(this, JarvisService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
            else startService(s);
        } catch (Exception ignored) { }
        attach();
    }

    void attach() {
        if (core == null || core.web.getParent() == root) return;
        ViewGroup p = (ViewGroup) core.web.getParent();
        if (p != null) p.removeView(core.web);
        core.ctx.setBaseContext(this);
        root.addView(core.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        core.activityVisible = true;
    }

    void detach() {
        if (core == null) return;
        core.activityVisible = false;
        if (core.web.getParent() == root) root.removeView(core.web);
        core.ctx.setBaseContext(getApplicationContext());
    }

    @Override protected void onStart() { super.onStart(); attach(); }

    @Override protected void onStop() { detach(); super.onStop(); }

    @Override protected void onDestroy() { detach(); super.onDestroy(); }

    @Override protected void onResume() { super.onResume(); checkSetup(); }

    /** Один раз просим два разрешения, без которых фоновая работа ненадёжна. */
    void checkSetup() {
        if (core == null) return;
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("askOverlay", false)) {
            prefs.edit().putBoolean("askOverlay", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Работа при закрытом приложении")
                    .setMessage("Чтобы Джарвис мог открывать приложения, когда экран выключен или приложение свёрнуто, включи для него «Поверх других окон» на следующем экране.")
                    .setPositiveButton("Открыть настройки", (d, w) -> startActivity(
                            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Позже", null).show();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("askBattery", false)) {
            prefs.edit().putBoolean("askBattery", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Не засыпать в фоне")
                    .setMessage("Разреши Джарвису работать без ограничений батареи, иначе телефон может отключать его при выключенном экране.")
                    .setPositiveButton("Разрешить", (d, w) -> startActivity(
                            new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Позже", null).show();
        }
    }
}
