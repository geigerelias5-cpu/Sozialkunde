package de.sozialkunde.trainer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Export/Import des Lernfortschritts: Datei speichern/öffnen (Android-Dateiauswahl), Teilen, Kopieren.
 * Im JavaScript als window.AndroidApp erreichbar.
 */
public class AppBridge {
    static final int REQ_SAVE = 21;
    static final int REQ_OPEN = 22;

    private final Activity act;
    private final WebView web;
    private String pendingSave;

    AppBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    @JavascriptInterface
    public void copy(String text) {
        act.runOnUiThread(() -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Sozialkunde-Fortschritt", text));
        });
    }

    @JavascriptInterface
    public void shareText(String text) {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Sozialkunde-Fortschritt");
            i.putExtra(Intent.EXTRA_TEXT, text);
            act.startActivity(Intent.createChooser(i, "Fortschritt teilen"));
        });
    }

    /** Öffnet „Speichern unter“ des Systems; geschrieben wird in onResult. */
    @JavascriptInterface
    public void saveFile(String name, String text) {
        act.runOnUiThread(() -> {
            pendingSave = text;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, name);
            act.startActivityForResult(i, REQ_SAVE);
        });
    }

    @JavascriptInterface
    public void pickFile() {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            act.startActivityForResult(i, REQ_OPEN);
        });
    }

    /** Von MainActivity.onActivityResult aufgerufen. */
    boolean onResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SAVE && requestCode != REQ_OPEN) return false;
        Uri uri = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_SAVE) {
            String text = pendingSave;
            pendingSave = null;
            if (uri == null || text == null) return true;
            try (OutputStream out = act.getContentResolver().openOutputStream(uri, "wt")) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                js("window.__skToast&&__skToast('Datei gespeichert')");
            } catch (Exception e) {
                js("window.__skToast&&__skToast('Speichern fehlgeschlagen')");
            }
        } else {
            if (uri == null) return true;
            try (InputStream in = act.getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && buf.size() < 2_000_000) buf.write(b, 0, n);
                String text = buf.toString("UTF-8");
                js("window.__importText&&__importText(" + JSONObject.quote(text) + ")");
            } catch (Exception e) {
                js("window.__skToast&&__skToast('Datei konnte nicht gelesen werden')");
            }
        }
        return true;
    }

    private void js(String s) {
        act.runOnUiThread(() -> web.evaluateJavascript(s, null));
    }
}
