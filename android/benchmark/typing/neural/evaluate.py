"""Next-word prediction on the held-out sets: the trained GPT against the keyboard's word-pair models.

    venv/bin/python scripts/evaluate.py ROOT RUN [positions per set]

For each held-out message and each word in it (letters only), both predictors see the words before it and
offer 3 words, with nothing of the word typed (next word) and with its first letter typed. A hit is the
word among the 3. English sets use en.hextm, Hinglish sets hien.hextm; the word-pair model scores a word as
the keyboard does, min(0.92 + word cost, pair cost), and has no sentence-start context (word costs alone)."""
import json
import math
import random
import sys
from collections import defaultdict
from pathlib import Path

import sentencepiece as spm
import torch

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from model import GPT  # noqa: E402

import os
REPO = Path(os.environ.get("HEX_ANDROID", Path(__file__).resolve().parents[3]))
sys.path.insert(0, str(REPO / "benchmark/typing"))
capitals = __import__("importlib").import_module("build-capitals")
LABEL = os.environ.get("HEX_LABEL", "")


class Ngram:
    def __init__(self, path):
        self.uni, pair = capitals.model_words(str(path))
        self.after = defaultdict(list)
        for (a, b), c in pair.items():
            self.after[a].append((c, b))
        self.top = sorted((c, w) for w, c in self.uni.items() if w.isalpha() or "'" in w)
        self.by_letter = defaultdict(list)
        for c, w in self.top:
            if len(self.by_letter[w[0]]) < 50:
                self.by_letter[w[0]].append((c, w))

    def predict(self, prev, prefix, k=3):
        scores = {}
        for c, w in (self.by_letter[prefix[0]] if prefix else self.top[:50]):
            scores[w] = 0.92 + c
        for c, w in self.after.get(prev, []):
            if w.startswith(prefix) and w[0].isalpha():
                scores[w] = min(scores.get(w, 1e9), c)
        return [w for w, _ in sorted(scores.items(), key=lambda x: x[1])[:k]]


BEAM, DEPTH = int(os.environ.get("HEX_BEAM", 12)), int(os.environ.get("HEX_DEPTH", 5))
APOSTROPHES = os.environ.get("HEX_APOSTROPHES") == "1"


def piece_ok(t, first):
    """A piece that can be part of a predicted word: letters (and, with HEX_APOSTROPHES, an apostrophe
    after the first letter)."""
    if APOSTROPHES:
        return all(c.isalpha() or c == "'" for c in t) and not (first and t.startswith("'"))
    return t.isalpha()


class Neural:
    def __init__(self, root, run, dev, quantized=os.environ.get("HEX_QUANT") == "1"):
        ck = torch.load(root / "runs" / run / "model.pt", map_location=dev)
        cfg = ck["config"]
        self.model = GPT(cfg["vocab"], cfg["ctx"], cfg["d"], cfg["layers"], cfg["heads"]).to(dev).eval()
        self.model.load_state_dict(ck["model"])
        if quantized:
            from export import dequantized
            dequantized(self.model)
        self.sp = spm.SentencePieceProcessor(model_file=str(root / f"corpus/sp{cfg['vocab']}.model"))
        self.ctx, self.dev = cfg["ctx"], dev
        pieces = [self.sp.id_to_piece(i) for i in range(cfg["vocab"])]
        self.text = [p.lstrip("▁") for p in pieces]
        self.starts = torch.tensor([p.startswith("▁") for p in pieces], device=dev)
        self.starts[2] = True  # end of message ends a word too
        self.first = [p.startswith("▁") and (t == "" or piece_ok(t, True)) for t, p in zip(self.text, pieces)]
        self.more = [not p.startswith("▁") and t != "" and piece_ok(t, False) for t, p in zip(self.text, pieces)]

    @torch.no_grad()
    def logp(self, seqs):
        x = torch.tensor([s[-self.ctx:] for s in seqs], device=self.dev)
        return torch.log_softmax(self.model(x)[:, -1].float(), -1)

    def predict(self, context, prefix, k=3, beam=None, depth=None):
        """The k likeliest words that start with `prefix` after `context`: a beam over word pieces, a word
        scored as its pieces plus the chance a new word (or the message's end) follows. A hypothesis that
        can't beat the k-th word found is dropped; extending only lowers a score, so the result is the
        same as without that cut."""
        beam, depth = beam or BEAM, depth or DEPTH
        base = [1] + self.sp.encode(" ".join(context)) if context else [1]
        lp = self.logp([base])[0]
        hyps = []
        for i in torch.topk(lp + torch.where(self.starts, 0.0, -1e9), 400).indices.tolist():
            t = self.text[i]
            if self.first[i] and (t.startswith(prefix) or prefix.startswith(t)):
                hyps.append((lp[i].item(), [i], t))
            if len(hyps) >= beam:
                break
        done = {}
        for _ in range(depth):
            if not hyps:
                break
            lps = self.logp([base + ids for _, ids, _ in hyps])
            boundary = torch.logsumexp(lps.masked_fill(~self.starts, -1e9), -1)
            for (s, ids, w), b in zip(hyps, boundary.tolist()):
                if w and w.startswith(prefix) and not w.endswith("'"):
                    done[w] = max(done.get(w, -1e9), s + b)
            floor = sorted(done.values(), reverse=True)[k - 1] if len(done) >= k else -1e9
            nxt = []
            for (s, ids, w), row in zip(hyps, lps):
                if s <= floor:
                    continue
                for i in torch.topk(row.masked_fill(self.starts, -1e9), 8).indices.tolist():
                    t = w + self.text[i]
                    if self.more[i] and s + row[i].item() > floor and (t.startswith(prefix) or prefix.startswith(t)):
                        nxt.append((s + row[i].item(), ids + [i], t))
            hyps = sorted(nxt, key=lambda h: -h[0])[:beam]
        return [w for w, _ in sorted(done.items(), key=lambda x: -x[1])[:k]]


def main(root, run, per_set=1500):
    root, per_set = Path(root), int(per_set)
    dev = "cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu"
    nn = Neural(root, run, dev)
    grams = {"en": Ngram(REPO / "app/src/main/assets/typing/en.hextm"), "hien": Ngram(REPO / "app/src/main/assets/typing/hien.hextm")}
    results = {}
    for f in sorted((root / "corpus").glob("eval-*.txt")):
        name = f.stem[5:]
        lang = "hien" if "hien" in name else "en"
        rng = random.Random(3)
        positions = [(ws, j) for l in f.read_text(encoding="utf-8").splitlines() if (ws := l.split())
                     for j in range(len(ws)) if ws[j].isalpha()]
        rng.shuffle(positions)
        hits = defaultdict(int)
        for ws, j in positions[:per_set]:
            target, prev = ws[j], (ws[j - 1] if j else "")
            for typed in (0, 1):
                prefix = target[:typed]
                hits[f"ngram{typed}"] += target in grams[lang].predict(prev, prefix)
                hits[f"neural{typed}"] += target in nn.predict(ws[:j], prefix)
        n = min(per_set, len(positions))
        results[name] = {k: round(100 * v / n, 1) for k, v in sorted(hits.items())} | {"n": n}
        print(name, results[name], flush=True)
    (root / "runs" / run / f"eval{LABEL}.json").write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main(*sys.argv[1:])
