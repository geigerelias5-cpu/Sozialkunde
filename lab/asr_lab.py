"""Testlabor: Wie gut erkennt Vosk (deutsches Offline-Modell) die Antwortbuchstaben A–E?
Erzeugt gesprochene Testsätze mit pico2wave und espeak-ng, mit und ohne Rauschen, und vergleicht Grammatiken."""
import json, os, subprocess, sys, wave, itertools, random
from vosk import Model, KaldiRecognizer, SetLogLevel

MODEL = sys.argv[1]
OUT = sys.argv[2]
os.makedirs('wav', exist_ok=True)
SetLogLevel(-1)

# Testsätze: (Text für die Sprachsynthese, erwarteter Buchstabe 0–4)
SAY = []
for i, L in enumerate('ABCDE'):
    SAY += [(L, i), (L.lower(), i), (f'Antwort {L}', i), (['Anton', 'Berta', 'Cäsar', 'Dora', 'Emil'][i], i)]
SAY += [('Be', 1), ('Zeh', 2), ('De', 3), ('Ah', 0), ('Eh', 4)]

VOICES = [('pico', None, None)] + [('espeak', v, s) for v in ['de', 'de+m3', 'de+f2', 'de+m7'] for s in (140, 175)]

def synth(text, eng, voice, speed, path):
    raw = path + '.raw.wav'
    if eng == 'pico':
        subprocess.run(['pico2wave', '-l', 'de-DE', '-w', raw, text], check=True)
    else:
        subprocess.run(['espeak-ng', '-v', voice, '-s', str(speed), '-w', raw, text], check=True)
    # 16 kHz mono, 0,4 s Stille davor/danach
    subprocess.run(['sox', raw, '-r', '16000', '-c', '1', '-b', '16', path, 'pad', '0.4', '0.6'], check=True)
    noisy = path.replace('.wav', '_n.wav')
    subprocess.run(['sox', path, '-p'], check=True, stdout=open(path + '.p', 'wb'))
    subprocess.run(f"sox -m {path} '|sox {path} -p synth whitenoise vol 0.02' {noisy}", shell=True, check=True)
    return [path, noisy]

files = []
for n, ((text, want), (eng, voice, speed)) in enumerate(itertools.product(SAY, VOICES)):
    p = f'wav/{n}.wav'
    for f in synth(text, eng, voice, speed, p):
        files.append((f, text, want, f"{eng}:{voice or ''}:{speed or ''}", f.endswith('_n.wav')))

model = Model(MODEL)

def recognize(path, grammar):
    rec = KaldiRecognizer(model, 16000, json.dumps(grammar, ensure_ascii=False)) if grammar else KaldiRecognizer(model, 16000)
    rec.SetMaxAlternatives(0)
    wf = wave.open(path, 'rb')
    texts = []
    while True:
        d = wf.readframes(4000)
        if not d: break
        if rec.AcceptWaveform(d):
            t = json.loads(rec.Result()).get('text', '')
            if t: texts.append(t)
    t = json.loads(rec.FinalResult()).get('text', '')
    if t: texts.append(t)
    return ' '.join(texts)

# Welche Wörter kennt das Modell überhaupt? (fehlende Wörter ignoriert Vosk mit einer Warnung)
CAND = ['a', 'b', 'c', 'd', 'e', 'ah', 'aa', 'be', 'bee', 'ce', 'zeh', 'ze', 'tse', 'de', 'dee', 'eh', 'ee',
        'anton', 'berta', 'cäsar', 'caesar', 'dora', 'emil', 'antwort', 'buchstabe', 'eins', 'zwei', 'drei', 'vier', 'fünf',
        'stopp', 'stop', 'wiederholen', 'nochmal', 'weiter', 'alpha', 'bravo', 'charlie', 'delta', 'echo', 'die', 'ja', 'nein']
known = []
for w in CAND:
    r = subprocess.run([sys.executable, '-c', f"from vosk import Model,KaldiRecognizer,SetLogLevel;SetLogLevel(0);import json;m=Model({MODEL!r});KaldiRecognizer(m,16000,json.dumps([{w!r},'[unk]']))"],
                       capture_output=True, text=True)
    if 'missing' not in (r.stderr + r.stdout).lower():
        known.append(w)

GRAMMARS = {
    'frei (ohne Grammatik)': None,
    'nur Buchstaben a–e': ['a', 'b', 'c', 'd', 'e', '[unk]'],
    'Buchstaben + Lautschrift': [w for w in ['a', 'b', 'c', 'd', 'e', 'ah', 'be', 'ce', 'zeh', 'de', 'eh'] if w in known] + ['[unk]'],
    'alles bekannte': known + ['[unk]'],
}
LET = {0: {'a', 'ah', 'aa', 'anton', 'alpha', 'eins'}, 1: {'b', 'be', 'bee', 'berta', 'bravo', 'zwei'},
       2: {'c', 'ce', 'zeh', 'ze', 'tse', 'cäsar', 'caesar', 'charlie', 'drei'}, 3: {'d', 'de', 'dee', 'dora', 'delta', 'vier'},
       4: {'e', 'eh', 'ee', 'emil', 'echo', 'fünf'}}
def letter(text):
    for w in reversed(text.split()):
        for i, s in LET.items():
            if w in s: return i
    return None

res = {'known_words': known, 'grammars': {}, 'samples': {}}
for g, gram in GRAMMARS.items():
    ok = bad = none = 0; per = {}; ex = []
    for f, text, want, voice, noisy in files:
        t = recognize(f, gram); got = letter(t)
        k = 'ok' if got == want else ('none' if got is None else 'bad')
        ok += k == 'ok'; bad += k == 'bad'; none += k == 'none'
        pk = text; per.setdefault(pk, [0, 0]); per[pk][0] += k == 'ok'; per[pk][1] += 1
        if k != 'ok' and len(ex) < 40: ex.append([text, voice, noisy, t])
    n = ok + bad + none
    res['grammars'][g] = {'richtig': round(ok / n, 3), 'falsch': round(bad / n, 3), 'nichts': round(none / n, 3), 'n': n,
                          'pro_satz': {k: f'{a}/{b}' for k, (a, b) in per.items()}, 'fehler_beispiele': ex}
    print(g, res['grammars'][g]['richtig'], res['grammars'][g]['falsch'], res['grammars'][g]['nichts'], flush=True)
json.dump(res, open(OUT, 'w'), ensure_ascii=False, indent=1)
