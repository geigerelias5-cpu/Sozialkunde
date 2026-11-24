"""Runde 2: echte Fragen, Grammatik genau wie in der App (Buchstaben + Antwortwörter). Liefert die Rohtexte zurück."""
import json, os, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
MODEL, CASES, OUT = sys.argv[1:4]
SetLogLevel(-1)
cases = json.load(open(CASES))['cases']
model = Model(MODEL)
VOICES = [('pico', None, None), ('espeak', 'de', 150), ('espeak', 'de+f2', 150), ('espeak', 'de+m3', 165)]
os.makedirs('w2', exist_ok=True)
def synth(text, eng, voice, speed, path):
    raw = path + '.raw.wav'
    if eng == 'pico': subprocess.run(['pico2wave', '-l', 'de-DE', '-w', raw, text], check=True)
    else: subprocess.run(['espeak-ng', '-v', voice, '-s', str(speed), '-w', raw, text], check=True)
    subprocess.run(['sox', raw, '-r', '16000', '-c', '1', '-b', '16', path, 'pad', '0.4', '0.6'], check=True)
    noisy = path.replace('.wav', '_n.wav')
    subprocess.run(f"sox -m {path} '|sox {path} -p synth whitenoise vol 0.02' {noisy}", shell=True, check=True)
    return [(path, False), (noisy, True)]
def recognize(path, grammar):
    rec = KaldiRecognizer(model, 16000, json.dumps(grammar, ensure_ascii=False))
    rec.SetMaxAlternatives(3)
    wf = wave.open(path, 'rb'); outs = []
    def take(js):
        d = json.loads(js); alts = [a.get('text', '') for a in d.get('alternatives', [])] or [d.get('text', '')]
        alts = [a for a in alts if a.strip()]
        if alts: outs.append(alts)
    while True:
        b = wf.readframes(4000)
        if not b: break
        if rec.AcceptWaveform(b): take(rec.Result())
    take(rec.FinalResult())
    # wie in der App: das erste Ergebnis mit echtem Inhalt (nicht nur [unk]) zählt
    for alts in outs:
        if any(a.replace('[unk]', '').strip() for a in alts): return alts
    return outs[0] if outs else []
res = []
for n, c in enumerate(cases):
    for eng, voice, speed in VOICES:
        for f, noisy in synth(c['say'], eng, voice, speed, f'w2/{n}_{eng}{voice or ""}.wav'):
            res.append({'i': n, 'voice': f'{eng}:{voice or ""}', 'noisy': noisy, 'alts': recognize(f, c['grammar'])})
    if n % 50 == 0: print(n, flush=True)
json.dump(res, open(OUT, 'w'), ensure_ascii=False)
