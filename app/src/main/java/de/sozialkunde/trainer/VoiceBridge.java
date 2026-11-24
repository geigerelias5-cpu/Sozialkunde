package de.sozialkunde.trainer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

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

    VoiceBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        this.tts = new TextToSpeech(act, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        tts.setLanguage(Locale.GERMANY);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { call("done", id, null); }
            @Override public void onError(String id) { call("done", id, null); }
            @Override public void onStop(String id, boolean interrupted) { call("done", id, null); }
        });
        ttsReady = true;
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
        });
    }

    @JavascriptInterface
    public boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(act);
    }

    @JavascriptInterface
    public void listen(String id) {
        act.runOnUiThread(() -> {
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingListen = id;
                act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            startListening(id);
        });
    }

    /** Aufgerufen von MainActivity, wenn der Nutzer die Mikrofon-Abfrage beantwortet hat. */
    void onMicPermission(boolean granted) {
        String id = pendingListen;
        pendingListen = null;
        if (id == null) return;
        if (granted) startListening(id);
        else call("error", id, "denied");
    }

    private void startListening(String id) {
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { call("error", id, "unavailable"); return; }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(act);
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
            @Override public void onError(int error) {
                finish("error", error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "denied" : "e" + error);
            }
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle p) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        recognizer.startListening(i);
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
        tts.shutdown();
        if (recognizer != null) recognizer.destroy();
    }
}
