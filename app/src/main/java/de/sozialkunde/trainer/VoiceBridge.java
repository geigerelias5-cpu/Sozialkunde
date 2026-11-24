package de.sozialkunde.trainer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Sprachausgabe (TextToSpeech) und Spracheingabe (SpeechRecognizer) für die WebView.
 * Im JavaScript als window.AndroidVoice erreichbar; Antworten kommen über window.__voice zurück.
 */
public class VoiceBridge implements TextToSpeech.OnInitListener {
    static final int REQ_MIC = 7;

    private final Activity act;
    private final WebView web;
    private final TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private String pendingListen;
    private String activeListen;
    private String pendingGrammar;

    // ---- Offline-Erkennung (Vosk): hört nur auf die erlaubten Wörter der aktuellen Frage ----
    private volatile Model voskModel;
    private volatile String voskState = "loading"; // loading | ready | failed
    private SpeechService voskService;
    private Recognizer voskRec; // gehört immer zu voskService; nur stopVosk() schließt ihn

    VoiceBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        this.tts = new TextToSpeech(act, this);
        loadVosk();
    }

    /** Modell aus der APK entpacken (nur beim ersten Start spürbar) und laden. */
    private void loadVosk() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            StorageService.unpack(act, "model-de", "model",
                    model -> { voskModel = model; voskState = "ready"; jsRaw("window.__voice&&__voice.engine&&__voice.engine('vosk')"); },
                    error -> { voskState = "failed"; jsRaw("window.__voice&&__voice.engine&&__voice.engine('google')"); });
        } catch (Throwable t) {
            voskState = "failed";
        }
    }

    /** "vosk" (offline bereit), "loading" (wird vorbereitet) oder "google" (Ersatz). */
    @JavascriptInterface
    public String engine() {
        if ("ready".equals(voskState)) return "vosk";
        if ("loading".equals(voskState)) return "loading";
        return "google";
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        tts.setLanguage(Locale.GERMANY);
        pickBestVoice();
        tts.setPitch(1.0f);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { call("done", id, null); }
            @Override public void onError(String id) { call("done", id, null); }
            @Override public void onStop(String id, boolean interrupted) { call("done", id, null); }
        });
        ttsReady = true;
    }

    /** Beste installierte deutsche Stimme wählen: hohe Qualität, offline (keine Verzögerung), Deutschland vor AT/CH. */
    private void pickBestVoice() {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Voice v : voices) {
                Locale l = v.getLocale();
                if (l == null || !"de".equals(l.getLanguage())) continue;
                Set<String> f = v.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                int score = v.getQuality();
                if (v.isNetworkConnectionRequired()) score -= 150;
                if ("DE".equals(l.getCountry())) score += 20;
                if (score > bestScore) { bestScore = score; best = v; }
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void speak(String text, float rate, String id) {
        if (!ttsReady) { call("done", id, null); return; }
        tts.setSpeechRate(rate);
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id);
    }

    @JavascriptInterface
    public void stop() {
        if (ttsReady) tts.stop();
        act.runOnUiThread(() -> {
            pendingListen = null;
            activeListen = null;
            if (recognizer != null) recognizer.cancel();
            stopVosk();
        });
    }

    @JavascriptInterface
    public boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(act);
    }

    @JavascriptInterface
    public void listen(String id) { listenWith(id, null); }

    /** grammarJson: JSON-Liste der erlaubten Wörter, z. B. ["a","b",…,"[unk]"]. */
    @JavascriptInterface
    public void listenWith(String id, String grammarJson) {
        act.runOnUiThread(() -> {
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingListen = id;
                pendingGrammar = grammarJson;
                act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            route(id, grammarJson);
        });
    }

    private void route(String id, String grammarJson) {
        if (voskModel != null && grammarJson != null) startVosk(id, grammarJson);
        else startListening(id);
    }

    /** Aufgerufen von MainActivity, wenn der Nutzer die Mikrofon-Abfrage beantwortet hat. */
    void onMicPermission(boolean granted) {
        String id = pendingListen, g = pendingGrammar;
        pendingListen = null;
        pendingGrammar = null;
        if (id == null) return;
        if (granted) route(id, g);
        else call("error", id, "denied");
    }

    private void startListening(String id) {
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { call("error", id, "other"); return; }
        // Jedes Mal ein frischer Erkenner: verhindert „beschäftigt“-Fehler beim schnellen Neustart
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        recognizer = SpeechRecognizer.createSpeechRecognizer(act);
        activeListen = id;
        recognizer.setRecognitionListener(new RecognitionListener() {
            private boolean finished = false;
            private void finish(String fn, String arg) {
                if (finished) return;
                finished = true;
                if (id.equals(activeListen)) activeListen = null;
                call(fn, id, arg);
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                finish("result", r == null ? "[]" : new JSONArray(r).toString());
            }
            @Override public void onPartialResults(Bundle b) {
                if (finished) return;
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) call("partial", id, new JSONArray(r).toString());
            }
            @Override public void onError(int error) {
                String code;
                switch (error) {
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    case SpeechRecognizer.ERROR_NO_MATCH: code = "nomatch"; break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    case SpeechRecognizer.ERROR_CLIENT: code = "busy"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "denied"; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER: code = "net"; break;
                    default: code = "other";
                }
                finish("error", code);
            }
            // Erst jetzt ist das Mikrofon wirklich offen: die App zeigt dann „Jetzt sprechen“
            @Override public void onReadyForSpeech(Bundle p) { if (!finished) call("ready", id, null); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int t, Bundle p) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        if (Build.VERSION.SDK_INT >= 33) {
            // Hinweis an die Erkennung: diese kurzen Wörter sind zu erwarten
            ArrayList<String> bias = new ArrayList<>(Arrays.asList(
                    "A", "B", "C", "D", "E", "Antwort A", "Antwort B", "Antwort C", "Antwort D", "Antwort E",
                    "Anton", "Berta", "Cäsar", "Dora", "Emil", "Stopp", "Wiederholen", "Weiter"));
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
        }
        recognizer.startListening(i);
    }

    private void startVosk(String id, String grammarJson) {
        stopVosk();
        final Recognizer rec;
        try {
            rec = new Recognizer(voskModel, 16000.0f, grammarJson);
            rec.setMaxAlternatives(3);
            voskRec = rec;
            voskService = new SpeechService(rec, 16000.0f);
        } catch (Exception e) {
            stopVosk();
            startListening(id); // Ersatz: Google-Erkennung
            return;
        }
        activeListen = id;
        final SpeechService svc = voskService;
        org.vosk.android.RecognitionListener l = new org.vosk.android.RecognitionListener() {
            private boolean finished = false;
            private void finish(String fn, String arg) {
                if (finished) return;
                finished = true;
                if (id.equals(activeListen)) activeListen = null;
                // nicht im Callback selbst stoppen, sondern direkt danach
                web.post(() -> { if (voskService == svc) stopVosk(); });
                call(fn, id, arg);
            }
            @Override public void onPartialResult(String hyp) {
                if (finished) return;
                String t = clean(optText(hyp, "partial"));
                if (!t.isEmpty()) call("partial", id, new JSONArray().put(t).toString());
            }
            @Override public void onResult(String hyp) {
                // Ende einer Äußerung: nur nehmen, wenn mehr als Rauschen/[unk] erkannt wurde
                JSONArray alts = alternatives(hyp);
                if (alts.length() > 0) finish("result", alts.toString());
            }
            @Override public void onFinalResult(String hyp) {
                JSONArray alts = alternatives(hyp);
                if (alts.length() > 0) finish("result", alts.toString());
                else finish("error", "nomatch");
            }
            @Override public void onError(Exception e) { finish("error", "other"); }
            @Override public void onTimeout() { finish("error", "nomatch"); }
        };
        try {
            svc.startListening(l, 9000);
            call("ready", id, null);
        } catch (Exception e) {
            stopVosk();
            startListening(id);
        }
    }

    private void stopVosk() {
        if (voskService != null) {
            try { voskService.stop(); } catch (Exception ignored) { }      // wartet, bis der Aufnahme-Thread fertig ist
            try { voskService.shutdown(); } catch (Exception ignored) { }
            voskService = null;
        }
        if (voskRec != null) {
            try { voskRec.close(); } catch (Exception ignored) { }
            voskRec = null;
        }
    }

    private static String optText(String json, String key) {
        try { return new JSONObject(json).optString(key, ""); } catch (Exception e) { return ""; }
    }

    private static String clean(String t) {
        return t == null ? "" : t.replace("[unk]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Vosk liefert {"alternatives":[{"text":…},…]} oder {"text":…}; leere/[unk]-Ergebnisse fallen weg. */
    private static JSONArray alternatives(String json) {
        JSONArray out = new JSONArray();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("alternatives");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    String t = clean(a.getJSONObject(i).optString("text", ""));
                    if (!t.isEmpty()) out.put(t);
                }
            } else {
                String t = clean(o.optString("text", ""));
                if (!t.isEmpty()) out.put(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Zuhören abbrechen (z. B. weil ein Zwischenergebnis schon eindeutig war), Vorlesen läuft weiter. */
    @JavascriptInterface
    public void cancelListen() {
        act.runOnUiThread(() -> {
            activeListen = null;
            if (recognizer != null) recognizer.cancel();
            stopVosk();
        });
    }

    private void jsRaw(String js) {
        act.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** Ruft window.__voice.<fn>(id[, arg]) im WebView auf. */
    private void call(String fn, String id, String arg) {
        String js = "window.__voice&&window.__voice." + fn + "(" + JSONObject.quote(id)
                + (arg == null ? "" : "," + JSONObject.quote(arg)) + ")";
        act.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    void pause() {
        // Die Mikrofon-Abfrage selbst pausiert die Activity: dann nicht abbrechen.
        if (pendingListen != null) return;
        act.runOnUiThread(() -> web.evaluateJavascript("window.__voice&&window.__voice.pause()", null));
        stop();
    }

    void destroy() {
        stopVosk();
        if (voskModel != null) try { voskModel.close(); } catch (Exception ignored) { }
        tts.shutdown();
        if (recognizer != null) recognizer.destroy();
    }
}
