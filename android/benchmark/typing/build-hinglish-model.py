#!/usr/bin/env python3
"""Builds the Hinglish layout's typing files: hien.hextm, English's word and word-pair model with
Hindi written in Latin letters mixed in, and hien.capitals.

    python3 build-hinglish-model.py ../../app/src/main/assets/typing WORKDIR

Reads en.hextm and en.capitals from the assets folder (checked against its SHA256SUMS) and writes
hien.hextm and hien.capitals beside them. Downloads PHINC into WORKDIR, pinned by SHA-256, and
takes only word and word-pair counts from its Hinglish side (no sentence is kept):

  PHINC (Srivastava and Singh, 2020): 13,738 code-mixed social media posts, each counted once.
      CC BY 4.0. https://doi.org/10.5281/zenodo.3605597

Words: every word of the English model, and each spelling the corpus uses at least MIN_COUNT
times that English lacks, as written: "nahi", "nahin", "nai" and "nhi" are all words, each as
likely as people write it. Left out: handles, hashtags and links, stretched spellings
("yaarrr"), English typed without its apostrophe ("dont", "im"), and names: a word written with
a capital at least NAME_SHARE of the time inside a sentence, seen there MIN_SEEN times, goes in
the capitals list instead, as English's names outside its model do, unless never-suggest.tsv lists
it for English or Hinglish (then it stays a word, never capitalised). Capitals are read only from
posts that write mostly in lowercase, since many are all lowercase, title case or capitals. Then
chat-words.tsv's Hinglish words that are still missing ("bindaas", "chapri"), each at the cost
add-chat-words.py gives its kind; English's chat words come with en.hextm.

Probabilities mix the two: P(w) = H P_h(w) + (1 - H) P_e(w), with P_h from the corpus's counts
and P_e from en.hextm's costs. A pair's P(b | a) mixes each side's estimate, its stored pair or
its backoff (0.4 P(b)), by the share of P(a) that side gives. A pair is kept only when it says
more than the mixed model's backoff, as TypingModel.languageCost reads it.

hien.capitals is en.capitals without the Hindi spellings English lists as names ("maine",
"hume", "lena"), plus the corpus's names, each with the share they write it with a capital.
Deterministic for the same inputs.
"""
import collections
import csv
import hashlib
import importlib
import math
import re
import struct
import sys
import unicodedata
from pathlib import Path

builder = importlib.import_module("build-typing-model")
capitals_builder = importlib.import_module("build-capitals")

LANGUAGE = "hien"
# The Hinglish corpus's share of the mixture.
H = 0.5
MIN_COUNT = 2
MIN_PAIR = 2
# Taken off each pair's count: two sightings of a pair are weaker evidence than its ratio says.
PAIR_DISCOUNT = 0.5
BACKOFF = math.exp(-0.92)
NAME_SHARE = 0.9
MIN_SEEN = 3

PHINC = ("https://zenodo.org/records/3605597/files/English-Hindi%20code-mixed%20parallel%20corpus.csv?download=1",
         "phinc.csv", "325ba383cd6d73e0c3128885e2a79133e612ec03d18c8b55506d0b4b12ec68ed")

# Handles, hashtags, links and HTML entities; a sentence may start after one.
DROP = re.compile(r"@\w+|#\w+|https?://\S+|www\.\S+|\S+\.(?:com|in|co|org|net)\b\S*|&\w+;")
SEPARATOR = re.compile(r"([^A-Za-z' ]+)")
# Marks after which a capital says nothing: a sentence's end, or speech ("Ram: Arre", "\"Arey yaar").
SENTENCE_END = re.compile(r"[.!?\n|:\"\u201c\u201d]")
WORD = re.compile(r"[a-z]{2,32}")
STRETCHED = re.compile(r"(.)\1\1")
# Twitter's own tokens and links' leftovers, not words anyone types.
NOISE = {"rt", "rts", "http", "https", "www", "url", "amp", "com"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(work, url, name, digest):
    path = work / name
    builder.fetch(url, str(path))
    if sha256(path) != digest:
        raise ValueError(f"Unexpected {name}: delete it and check the source")
    return path


def lexicon(vocab):
    """HXTM's letter codes (1..A by Unicode scalar) and `vocab`'s words in code order, as
    build-typing-model.py's write_model orders them."""
    scalars = sorted({c for w in vocab for c in w})
    code = {c: i + 1 for i, c in enumerate(scalars)}
    enc = {w: bytes(code[c] for c in w) for w in vocab}
    return scalars, code, enc, sorted(vocab, key=lambda w: enc[w])


def write_hxtm(scalars, code, enc, words, probabilities, kept, out_path):
    """HXTM v3 as build-typing-model.py's write_model writes it, from `lexicon`'s words with P(word)
    for each in `probabilities` and `kept`, the pairs as sorted (first index, second index,
    P(second | first)). That builder is iOS's, byte for byte, so it stays as it is. Returns the size."""
    def base(c):
        b = unicodedata.normalize("NFD", c)[0]
        return code.get(b, code[c])
    blob = b"".join(enc[w] for w in words)
    rows = [0] * (len(words) + 1)
    for a, _, _ in kept:
        rows[a + 1] += 1
    for i in range(len(words)):
        rows[i + 1] += rows[i]
    offsets, at = [], 0
    for w in words:
        offsets.append(at)
        at += len(enc[w])
    offsets.append(at)
    out = bytearray(b"HXTM")
    out += struct.pack("<IIIII", 3, len(words), len(kept), len(blob), len(scalars))
    out += LANGUAGE.encode("ascii").ljust(4, b"\0")
    for c in scalars:
        out += struct.pack("<IBBH", ord(c), code[c], base(c), 0)
    out += struct.pack(f"<{len(offsets)}I", *offsets)
    out += bytes(builder.cost(p) for p in probabilities)
    out += b"\0" * (-len(out) % 4)
    out += struct.pack(f"<{len(rows)}I", *rows)
    out += struct.pack(f"<{len(kept)}I", *(b for _, b, _ in kept))
    out += bytes(builder.cost(p) for _, _, p in kept)
    out += blob
    Path(out_path).write_bytes(out)
    return len(out)


def texts(work):
    """Each post once: a repeated one counts once."""
    seen = set()
    with open(fetch(work, *PHINC), encoding="utf-8", errors="replace", newline="") as f:
        for row in csv.DictReader(f):
            key = " ".join(row["Sentence"].lower().split())
            if key not in seen:
                seen.add(key)
                yield row["Sentence"]


def runs(text):
    """The text's runs of words between marks: each word as written, and whether it starts a sentence."""
    parts = SEPARATOR.split(DROP.sub(" | ", text))
    start = True
    for i in range(0, len(parts), 2):
        words = [w for w in (w.strip("'") for w in parts[i].split()) if w]
        if words:
            yield [(w, start and j == 0) for j, w in enumerate(words)]
            start = False
        if i + 1 < len(parts) and SENTENCE_END.search(parts[i + 1]):
            start = True


def main(assets, work):
    assets, work = Path(assets), Path(work)
    work.mkdir(parents=True, exist_ok=True)
    pins = dict(reversed(line.split()) for line in (assets / "SHA256SUMS").read_text().splitlines())
    for name in ("en.hextm", "en.capitals"):
        if sha256(assets / name) != pins[name]:
            raise ValueError(f"{name} differs from SHA256SUMS")
    english_costs, english_pair_costs = capitals_builder.model_words(str(assets / "en.hextm"))
    english = {w: math.exp(-c) for w, c in english_costs.items()}
    english_pairs = {k: math.exp(-c) for k, c in english_pair_costs.items()}
    # English words without their apostrophe ("dont") and the spellings the keyboard's English fixes
    # correct ("im", which the books split into "i" and "'m") are English typed fast, not Hindi.
    fixes = (Path(__file__).parent / "../../app/src/main/java/ly/anoma/hex/typing/EnglishFixes.kt").read_text()
    unpunctuated = {w.replace("'", "") for w in english} | set(re.findall(r'"([a-z]+)" to', fixes))

    counts = collections.Counter()
    pair_counts = collections.Counter()
    upper = collections.Counter()
    inside = collections.Counter()
    starters = collections.Counter()
    for text in texts(work):
        text_runs = list(runs(text))
        # Only a text that writes its words in lowercase but capitalises some inside a sentence says
        # which take a capital: not one in all lowercase, title case ("Hum Tamam Hindu") or capitals.
        capitals = [written[0].isupper() for run in text_runs for written, starts in run
                    if not starts and not written.isupper()]
        cased = 0 < sum(capitals) < len(capitals) / 2
        for run in text_runs:
            last = None
            for written, starts in run:
                w = written.lower()
                if len(w) < 2 or w in NOISE or STRETCHED.search(w):
                    last = None
                    continue
                counts[w] += 1
                if starts:
                    starters[w] += 1
                elif cased and not written.isupper():
                    inside[w] += 1
                    upper[w] += written[0].isupper()
                if last is not None:
                    pair_counts[(last, w)] += 1
                last = w
    total = sum(counts.values())

    def capital_share(w):
        return upper[w] / inside[w] if inside[w] else 0.0
    candidates = [w for w, c in counts.items()
                  if c >= MIN_COUNT and w not in unpunctuated and WORD.fullmatch(w)]
    # As build-capitals.py's names, a word nearly always written with a capital is a name ("modi",
    # "delhi"), listed in the capitals; seen inside a sentence fewer than MIN_SEEN times, it's left out.
    # Below that share, it's a word: tweets write many in title case ("Kyunki", "Ishq"). A word the
    # keyboard never suggests ("paki") is never given a capital either: it stays a word as typed.
    listed = {row[2] for row in (line.split("\t") for line in (assets / "never-suggest.tsv").read_text(encoding="utf-8").splitlines())
              if len(row) == 3 and row[0] in ("*", "en", LANGUAGE)}
    names = {w: capital_share(w) for w in candidates
             if capital_share(w) >= NAME_SHARE and inside[w] >= MIN_SEEN and w not in listed}
    added = {w for w in candidates if capital_share(w) < NAME_SHARE or w in listed} | set(names)
    vocab = set(english) | added

    def p_h(w):
        return counts[w] / total

    probability = {w: H * p_h(w) + (1 - H) * english.get(w, 0.0) for w in vocab}
    # Hinglish chat words PHINC lacks ("bindaas", "chapri"), each at its kind's cost (add-chat-words.py).
    chat = {w: p for w, p in importlib.import_module("add-chat-words").probabilities(LANGUAGE, assets).items() if w not in vocab}
    vocab |= set(chat)
    probability.update(chat)
    scalars, code, enc, words = lexicon(vocab)
    index = {w: i for i, w in enumerate(words)}
    kept = []
    for a, b in set(english_pairs) | {k for k, c in pair_counts.items() if c >= MIN_PAIR and k[0] in vocab and k[1] in vocab}:
        share = H * p_h(a) / probability[a]
        c = pair_counts[(a, b)]
        hinglish = (c - PAIR_DISCOUNT) / counts[a] if c >= MIN_PAIR else BACKOFF * p_h(b)
        p = share * hinglish + (1 - share) * english_pairs.get((a, b), BACKOFF * english.get(b, 0.0))
        # TypingModel.languageCost: min(0.92 + unigram cost, pair cost), in eighths of a nat.
        if builder.cost(p) <= builder.cost(probability[b]) + 7:
            kept.append((index[a], index[b], p))
    kept.sort()
    size = write_hxtm(scalars, code, enc, words, [probability[w] for w in words], kept,
                              str(assets / f"{LANGUAGE}.hextm"))
    print(f"{LANGUAGE}.hextm: {len(words)} words ({len(added)} Hinglish), {len(kept)} pairs, {size / 1e6:.2f} MB; "
          f"{total:,} corpus words, {len(names)} names to capitals")

    # English's names minus the words that are mostly Hindi here and written in lowercase ("maine",
    # "hume", "lena"), plus the corpus's names.
    capitals, dropped = {}, []
    for line in (assets / "en.capitals").read_text(encoding="utf-8").splitlines():
        w, percent = line.split("\t")
        if inside[w] >= MIN_SEEN and capital_share(w) < 0.5 and H * p_h(w) > (1 - H) * english.get(w, 0.0):
            dropped.append(w)
        else:
            capitals[w] = int(percent)
    for w, share in names.items():
        capitals.setdefault(w, int(100 * share + 0.5))
    lines = sorted(f"{w}\t{p}\n".encode("utf-8") for w, p in capitals.items())
    (assets / f"{LANGUAGE}.capitals").write_bytes(b"".join(lines))
    print(f"{LANGUAGE}.capitals: {len(capitals)} words, without English's {' '.join(dropped)}")
    print("names:", " ".join(sorted(names)))
    print("sentence starters:", " ".join(w for w, _ in starters.most_common(20)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
