"""Build the training corpus and held-out sets as normalized lines (one message per line).

    venv/bin/python scripts/prepare.py ROOT

ROOT holds data/ (soda, wildchat), gen/ (synthetic chats) and writes corpus/. Held out, never trained on:
threads.tsv (a different generator run), 10% of PHINC, 10% of the synthetic files, the learning bench's Enron
authors, and SODA's last 5,000 dialogues."""
import ast
import csv
import json
import random
import sys
from pathlib import Path

import pyarrow.parquet as pq

sys.path.insert(0, str(Path(__file__).parent))
from text import is_hinglish, words  # noqa: E402

rng = random.Random(7)


def line(ws):
    return " ".join(ws)


def soda(root, train, held):
    t = pq.read_table(root / "data/soda/train.parquet", columns=["dialogue"])
    dialogues = t.column("dialogue").to_pylist()
    for i, d in enumerate(dialogues):
        if i >= len(dialogues) - 5000:
            out = held["en-soda"]
        elif i % 4 == 0:  # a quarter of SODA is plenty; more would drown the Hinglish
            out = train["soda"]
        else:
            continue
        for turn in d:
            ws = words(turn)
            if 2 <= len(ws) <= 60:
                out.append(line(ws))


def wildchat(root, train):
    for f in sorted((root / "data/wildchat").glob("*.parquet")):
        t = pq.read_table(f, columns=["conversation", "toxic"])
        for conv, toxic in zip(t.column("conversation").to_pylist(), t.column("toxic").to_pylist()):
            if toxic:
                continue
            for turn in conv:
                c = turn["content"]
                if turn["role"] != "user" or len(c) > 400 or any(ch in c for ch in "{}<>=;`\\|[]"):
                    continue
                ws = words(c)
                if not 2 <= len(ws) <= 60:
                    continue
                if is_hinglish(ws):
                    train["wildchat-hien"].append(line(ws))
                elif turn["language"] == "English":
                    train["wildchat-en"].append(line(ws))


def phinc(train, held):
    with open(root / "data/phinc.csv", encoding="utf-8", newline="") as f:
        for i, row in enumerate(csv.DictReader(f)):
            ws = words(row["Sentence"])
            if len(ws) >= 2:
                (held["hien-phinc"] if i % 10 == 0 else train["phinc"]).append(line(ws))


def generated(root, train, held):
    for i, f in enumerate(sorted((root / "gen").glob("*.txt"))):
        style = f.read_text(encoding="utf-8").split("\n", 1)[0][2:]
        for l in f.read_text(encoding="utf-8").splitlines()[1:]:
            if len(l) > 3 and l[1] == ":":
                ws = words(l[2:])
                if ws:
                    (held[f"gen-{style}"] if i % 10 == 0 else train[f"gen-{style.split('-')[0]}"]).append(line(ws))


def threads(held):
    with open(root / "data/threads.tsv", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            ws = words(row["text"])
            if ws:
                held[f"{row['lang']}-{row['register']}"].append(line(ws))


def enron(held):
    for f in sorted((root / "data/authors").glob("*.txt")):
        for l in f.read_text(encoding="utf-8").splitlines()[:300]:
            ws = words(l)
            if len(ws) >= 3:
                held["en-email"].append(line(ws[:60]))


def main(root):
    root = Path(root)
    from collections import defaultdict
    train, held = defaultdict(list), defaultdict(list)
    soda(root, train, held); wildchat(root, train); phinc(train, held); generated(root, train, held)
    threads(held); enron(held)
    # Repeat the small Hinglish and chat sources as far as repeating them stays useful.
    weight = {"soda": 1, "wildchat-en": 1, "wildchat-hien": 8, "phinc": 10, "gen-hien": 6, "gen-en": 3}
    out = root / "corpus"; out.mkdir(exist_ok=True)
    stats = {}
    lines = []
    for k, v in train.items():
        stats[k] = {"lines": len(v), "words": sum(l.count(" ") + 1 for l in v), "weight": weight.get(k, 1)}
        lines += v * weight.get(k, 1)
    rng.shuffle(lines)
    (out / "train.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    for k, v in held.items():
        rng.shuffle(v)
        (out / f"eval-{k}.txt").write_text("\n".join(v[:3000]) + "\n", encoding="utf-8")
        stats[f"eval-{k}"] = {"lines": min(len(v), 3000)}
    stats["train-total-words"] = sum(l.count(" ") + 1 for l in lines)
    (out / "stats.json").write_text(json.dumps(stats, indent=1))
    print(json.dumps(stats, indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
