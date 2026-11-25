#!/usr/bin/env python3
"""Adds chat words (chat-words.tsv: "tbh", "ngl", "rizz", "nhi", "bindaas") to a typing model the
books or PHINC left them out of, so the keyboard suggests them and never corrects them away.

    python3 add-chat-words.py en ../../app/src/main/assets/typing/en.hextm

Rewrites the model in place with every chat word for that language it lacks; words it has keep
their costs, and every stored pair is kept. English's model is the iOS Google Books file
(sha256 14370569…) plus these words; build-hinglish-model.py reads the result and adds Hinglish's own
(`probabilities`), so run this on English first, then that builder. Deterministic: running it on its
own output changes nothing.

chat-words.tsv (lang, word, kind, variants, meaning) was written with a language model, checked by
hand, and lists no swear words (never-suggest.tsv has those). A word has no count, so its cost
comes from its kind: an abbreviation, Gen Z word, interjection or filler (lol, ngl, arre) costs what
the books' 7,000th word does, other slang the 12,000th's, and another spelling of either (nhi for
nahi) the 15,000th's. Left out: spellings with anything but letters ("b4", "aura points"), longer
than 20 letters, or stretched ("awww"), a Hinglish spelling ending in a doubled letter ("aajj",
"khushii": the list's stretched forms; "accha" stays), one never-suggest.tsv lists, and English
typed without its apostrophe ("dont", "im"), which the keyboard corrects.

Swears are words too: never-suggest.tsv's swear rows for the language ("stfu", "bhenchod") are added
at other slang's cost, so the keyboard suggests them while Suggest swear words is on (the default)
and keeps them as typed. Slurs, porn site names and words about children are never added.
"""
import csv
import hashlib
import importlib
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
capitals = importlib.import_module("build-capitals")
hinglish = importlib.import_module("build-hinglish-model")

# -ln P(word) in nats, by kind (en.hextm's 7,000th, 12,000th and 15,000th words).
COMMON, SLANG, VARIANT = 11.5, 12.5, 13.5
CHATTY = {"abbrev", "genz", "interjection", "filler"}
# Listed swears that are not words someone means to type.
NOT_WORDS = set("bangbros bangbus goatcx goatse gokkun jailbait livesex pedobear sexcam shitblimp shota tubgirl "
                "voyeurweb worldsex yiffy".split())
SPELLING = re.compile(r"[a-z]{2,20}")
STRETCHED = re.compile(r"(.)\1\1")


def rows(assets, lang):
    lines = (assets / "never-suggest.tsv").read_text(encoding="utf-8").splitlines()
    return [row for row in (line.split("\t") for line in lines) if len(row) == 3 and row[0] in ("*", "en", lang)]


def swears(assets, lang):
    return {w for language, kind, w in rows(assets, lang) if kind == "swear" and language == lang and w not in NOT_WORDS}


def unpunctuated(assets):
    """English typed without its apostrophe ("dont") and the spellings the keyboard's English fixes
    correct ("im"): left out, so the keyboard still puts the apostrophe back."""
    english = capitals.model_words(str(assets / "en.hextm"))[0]
    fixes = (HERE / "../../app/src/main/java/ly/anoma/hex/typing/EnglishFixes.kt").read_text()
    return {w.replace("'", "") for w in english if "'" in w} | set(re.findall(r'"([a-z]+)" to', fixes))


def probabilities(lang, assets):
    """Each chat spelling for `lang` with P(word) from its kind; a spelling listed twice keeps the likelier."""
    assets = Path(assets)
    allowed = swears(assets, lang)
    blocked = ({w for _, _, w in rows(assets, lang)} - allowed) | unpunctuated(assets)
    out = {}

    def add(w, cost):
        if not SPELLING.fullmatch(w) or STRETCHED.search(w) or w in blocked:
            return
        if lang == "hien" and w[-1] == w[-2]:
            return
        out[w] = max(out.get(w, 0.0), math.exp(-cost))

    with open(HERE / "chat-words.tsv", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["lang"] != lang:
                continue
            add(row["word"].lower(), COMMON if row["kind"] in CHATTY else SLANG)
            for v in row["variants"].split(","):
                add(v.strip().lower(), VARIANT)
    for w in allowed:
        add(w, SLANG)
    return out


def main(lang, model_path):
    model_path = Path(model_path)
    data = model_path.read_bytes()
    unigram, pair = capitals.model_words(str(model_path))
    probability = {w: math.exp(-c) for w, c in unigram.items()}
    added = {w: p for w, p in probabilities(lang, model_path.parent).items() if w not in probability}
    probability.update(added)
    scalars, code, enc, words = hinglish.lexicon(set(probability))
    index = {w: i for i, w in enumerate(words)}
    kept = sorted((index[a], index[b], math.exp(-c)) for (a, b), c in pair.items())
    hinglish.LANGUAGE = data[24:28].rstrip(b"\0").decode("ascii")
    size = hinglish.write_hxtm(scalars, code, enc, words, [probability[w] for w in words], kept, str(model_path))
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    print(f"{model_path.name}: {len(added)} chat words added, {len(words)} words, {len(kept)} pairs, {size / 1e6:.2f} MB; {digest}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
