package de.sozialkunde.trainer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Zeigt den Trainer (assets/index.html) bildschirmfüllend an. Fortschritt bleibt im localStorage der App. */
public class MainActivity extends Activity {
    private WebView web;
    private VoiceBridge voice;
    private AppBridge appBridge;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setBackgroundColor(getColor(R.color.screen));
        voice = new VoiceBridge(this, web);
        web.addJavascriptInterface(voice, "AndroidVoice");
        appBridge = new AppBridge(this, web);
        web.addJavascriptInterface(appBridge, "AndroidApp");
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Zurück-Taste: im Quiz erst zur Übersicht, auf der Übersicht App schließen. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? goBack() : false", value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == VoiceBridge.REQ_MIC && voice != null) {
            voice.onMicPermission(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (appBridge != null && appBridge.onResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** App im Hintergrund: Vorlesen und Zuhören sofort stoppen. */
    @Override
    protected void onPause() {
        super.onPause();
        if (voice != null) voice.pause();
    }

    @Override
    protected void onDestroy() {
        if (voice != null) voice.destroy();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
