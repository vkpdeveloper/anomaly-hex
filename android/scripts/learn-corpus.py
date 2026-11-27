#!/usr/bin/env -S python3 -I
"""Builds the writing samples the learning bench (LearningBenchTest) types.

  android/scripts/learn-corpus.py [--out DIR] [--authors 10] [--tarball FILE]

Source: the public Enron email corpus (CMU, 2015-05-07 snapshot, released by the US Federal Energy Regulatory
Commission and widely used for research). Each person's sent folders hold real, informal, first-person writing.
The tarball (about 440 MB) is downloaded once into DIR/download and kept; the cleaned samples are written to
DIR/authors/<person>.txt, one message per line in time order, and the run is skipped when they already exist.

Cleaning: only each person's own sent mail, text/plain only; quoted replies, forwarded blocks, signatures,
headers, links, addresses, numbers and anything the English letters page cannot type are dropped; only whole
sentences of plain words (letters, apostrophes, spaces and . , ? !) are kept; messages repeated across the
sent folders count once. The authors with the most text, sorted by name for ties, are kept, so reruns agree.
"""
import argparse, email, email.policy, email.utils, hashlib, os, re, sys, tarfile, urllib.request

URL = "https://www.cs.cmu.edu/~enron/enron_mail_20150507.tar.gz"
SENT = re.compile(r"^(?:\./)?maildir/([^/]+)/(?:_sent_mail|sent|sent_items)/[^/]+$")
CUT = re.compile(r"^\s*(?:-{2,}\s*(?:original message|forwarded)|_{5,}|={5,}|-{5,}|on .{5,80} wrote:|(?:from|to|cc|sent|subject|date):\s)", re.I)
ALLOWED = re.compile(r"^[A-Za-z' ,.?!]+$")
WORD = re.compile(r"^[A-Za-z][A-Za-z']*[,.?!]?$")
SENTENCES = re.compile(r"(?<=[.!?])\s+")
MIN_WORDS, MAX_WORDS_PER_MESSAGE = 5, 70


def body_of(message):
    part = message.get_body(preferencelist=("plain",))
    if part is None:
        return None
    try:
        return part.get_content()
    except (LookupError, UnicodeDecodeError):
        return None


def clean(text):
    kept = []
    for line in text.replace("\r", "").split("\n"):
        if CUT.match(line) or line.strip() in ("--", "-- "):
            break
        if line.lstrip().startswith(">") or re.search(r"@|https?:|www\.|\.com\b", line):
            continue
        kept.append(line.strip())
    text = " ".join(kept)
    text = text.replace("’", "'").replace("‘", "'").replace("`", "'")
    text = re.sub(r"\s+", " ", text).strip()
    sentences = []
    for s in SENTENCES.split(text):
        words = s.split(" ")
        if not (3 <= len(words) <= 40) or s[-1] not in ".?!" or not ALLOWED.match(s):
            continue
        if any(len(w) > 20 or not WORD.match(w) for w in words):
            continue
        letters = [c for c in s if c.isalpha()]
        if sum(c.isupper() for c in letters) > 0.5 * len(letters):
            continue
        sentences.append(s)
    out, count = [], 0
    for s in sentences:
        n = s.count(" ") + 1
        if count + n > MAX_WORDS_PER_MESSAGE:
            break
        out.append(s); count += n
    return " ".join(out) if count >= MIN_WORDS else None


def main():
    arg = argparse.ArgumentParser()
    arg.add_argument("--out", default=os.path.join(os.environ.get("TMPDIR", "/tmp"), "hex-learn-bench"))
    arg.add_argument("--authors", type=int, default=10)
    arg.add_argument("--tarball")
    a = arg.parse_args()
    authors = os.path.join(a.out, "authors")
    if os.path.isdir(authors) and any(f.endswith(".txt") for f in os.listdir(authors)):
        print("samples already in", authors); return
    tarball = a.tarball or os.path.join(a.out, "download", "enron.tar.gz")
    if not os.path.exists(tarball):
        os.makedirs(os.path.dirname(tarball), exist_ok=True)
        print("downloading", URL, file=sys.stderr)
        urllib.request.urlretrieve(URL, tarball + ".part")
        os.replace(tarball + ".part", tarball)
    people = {}
    seen = set()
    with tarfile.open(tarball, "r|gz") as tar:
        for member in tar:
            m = SENT.match(member.name)
            if not m or not member.isfile():
                continue
            raw = tar.extractfile(member).read()
            message = email.message_from_bytes(raw, policy=email.policy.default)
            body = body_of(message)
            try:
                when = email.utils.parsedate_to_datetime(message["Date"]) if message["Date"] else None
            except (TypeError, ValueError):
                when = None
            if body is None or when is None:
                continue
            text = clean(body)
            if text is None:
                continue
            digest = hashlib.sha1((m.group(1) + text).encode()).digest()
            if digest in seen:
                continue
            seen.add(digest)
            people.setdefault(m.group(1), []).append((when.timestamp(), text))
    ranked = sorted(people.items(), key=lambda p: (-sum(t.count(" ") + 1 for _, t in p[1]), p[0]))[: a.authors]
    os.makedirs(authors, exist_ok=True)
    for name, messages in ranked:
        messages.sort()
        with open(os.path.join(authors, name + ".txt"), "w") as f:
            f.write("\n".join(t for _, t in messages) + "\n")
        print("%-14s %6d messages %8d words" % (name, len(messages), sum(t.count(" ") + 1 for _, t in messages)), file=sys.stderr)


main()
