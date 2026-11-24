"""Welche Wörter aus den Antworten kennt das Vosk-Modell? Unbekannte Wörter in bekannte Teile zerlegen."""
import json, subprocess, sys, re
MODEL, WORDS, OUT = sys.argv[1:4]
words = json.load(open(WORDS))
def missing(cands):
    """Ein Recognizer mit allen Kandidaten – Vosk meldet jedes unbekannte Wort als Warnung."""
    code = ("import json,sys;from vosk import Model,KaldiRecognizer,SetLogLevel;SetLogLevel(0);"
            f"m=Model({MODEL!r});w=json.load(open(sys.argv[1]));KaldiRecognizer(m,16000,json.dumps(w+['[unk]']))")
    json.dump(sorted(cands), open('/tmp/c.json', 'w'), ensure_ascii=False)
    r = subprocess.run([sys.executable, '-c', code, '/tmp/c.json'], capture_output=True, text=True)
    return set(re.findall(r"missing in vocabulary: '([^']+)'", r.stderr + r.stdout))
miss = missing(set(words))
known = set(words) - miss
print(len(words), 'Wörter,', len(miss), 'unbekannt', flush=True)
parts = set()
for w in miss:
    for i in range(3, len(w) - 2):
        a, b = w[:i], w[i:]
        parts.update([a, b])
        for f in ('s', 'n', 'en', 'es', 'e'):
            if a.endswith(f) and len(a) - len(f) >= 3: parts.add(a[:-len(f)])
parts -= known
pmiss = missing(parts)
pknown = (parts - pmiss) | known
def split(w, depth=0):
    """Zerlegung in möglichst wenige, möglichst lange bekannte Teile (Fugen-s usw. werden mitgenommen)."""
    if w in pknown and len(w) >= 3: return [w]
    if depth >= 3: return None
    best = None
    for i in range(len(w) - 3, 2, -1):
        a, b = w[:i], w[i:]
        heads = [a] + [a[:-len(f)] for f in ('s', 'n', 'en', 'es', 'e') if a.endswith(f) and len(a) - len(f) >= 3]
        for h in heads:
            if h in pknown:
                rest = split(b, depth + 1)
                if rest and (best is None or len(rest) + 1 < len(best)): best = [h] + rest
        if best and len(best) == 2: break
    return best
res = {'known': sorted(known), 'split': {}, 'unsplittable': []}
for w in sorted(miss):
    s = split(w)
    if s: res['split'][w] = s
    else: res['unsplittable'].append(w)
print(len(res['split']), 'zerlegt,', len(res['unsplittable']), 'nicht zerlegbar', flush=True)
json.dump(res, open(OUT, 'w'), ensure_ascii=False, indent=0)
