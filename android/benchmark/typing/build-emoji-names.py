#!/usr/bin/env python3
"""Build the emoji panel's search names (typing/emoji.names) from Unicode CLDR 46 annotations.

    python3 build-emoji-names.py CLDR_DIR EMOJI_TXT OUTPUT

CLDR_DIR holds release-46's common/annotations/{en,hi_Latn}.xml and
common/annotationsDerived/{en,hi_Latn}.xml (https://github.com/unicode-org/cldr, Unicode License v3),
saved as annotations_en.xml, annotations_hi_Latn.xml, annotationsDerived_en.xml and
annotationsDerived_hi_Latn.xml. EMOJI_TXT is the panel's assets/emoji.txt: only its emoji are kept,
in its order, so the panel's own order breaks ties between equal matches.

Each line is emoji<TAB>name words<TAB>keyword words: the English short name ("red heart", "flag
india"), then the English keywords and the Hinglish (hi_Latn) name and keywords, as lowercase words
separated by spaces, each once. Hinglish words that are also in the English annotations add nothing
and are left out; so are joining words such as "with", "of", "wala" and "ke".
"""
import html
import re
import sys

STOP = {"a", "an", "and", "the", "of", "with", "in", "on", "at", "for", "to", "or", "by",
        "wala", "wali", "wale", "ke", "ki", "ka", "se", "mein", "me", "aur", "saath", "hua", "hue", "hui"}
WORD = re.compile(r"[a-z0-9]+(?:'[a-z]+)?")


def annotations(path):
    names, keywords = {}, {}
    text = open(path, encoding="utf-8").read()
    for cp, tts, body in re.findall(r'<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>', text):
        body = html.unescape(body)
        if body == "↑↑↑":
            continue
        key = cp.replace("️", "")
        if tts:
            names[key] = body
        else:
            keywords[key] = body
    return names, keywords


def words(text):
    return [w for w in WORD.findall(text.lower().replace("’", "'")) if w not in STOP]


def main(cldr, emoji_txt, output):
    order = []
    for line in open(emoji_txt, encoding="utf-8"):
        if line.strip() and not line.startswith("#"):
            order += line.rstrip("\n").split("\t", 1)[1].split()
    sources = {}
    for lang in ("en", "hi_Latn"):
        names, keywords = {}, {}
        for kind in ("annotations", "annotationsDerived"):
            n, k = annotations(f"{cldr}/{kind}_{lang}.xml")
            names.update(n)
            keywords.update(k)
        sources[lang] = (names, keywords)
    en_names, en_keywords = sources["en"]
    hi_names, hi_keywords = sources["hi_Latn"]
    english = {w for text in list(en_names.values()) + list(en_keywords.values()) for w in words(text)}
    lines = []
    for emoji in order:
        key = emoji.replace("️", "")
        name = en_names.get(key)
        if name is None:
            continue
        named = list(dict.fromkeys(words(name)))
        rest = words(en_keywords.get(key, "")) + [w for w in words(hi_names.get(key, "") + " " + hi_keywords.get(key, ""))
                                                   if w not in english]
        rest = [w for w in dict.fromkeys(rest) if w not in named]
        lines.append(f"{emoji}\t{' '.join(named)}\t{' '.join(rest)}")
    with open(output, "w", encoding="utf-8") as out:
        out.write("# Emoji search names: Unicode CLDR 46 annotations (en, hi_Latn), © Unicode, Inc., Unicode License v3.\n")
        out.write("# One emoji per line: emoji, tab, its English name's words, tab, its other words.\n")
        out.write("\n".join(lines) + "\n")
    print(len(lines), "emoji of", len(order))


if __name__ == "__main__":
    main(*sys.argv[1:])
