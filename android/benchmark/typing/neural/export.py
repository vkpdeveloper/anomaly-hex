"""Export a trained run as the keyboard's on-device next-word model (HXNN v1) and a test fixture.

    venv/bin/python scripts/export.py ROOT RUN OUT.hexnn [FIXTURE.tsv]

Weight matrices are int8 with one float scale per output row (symmetric, max |w| / 127); layer norms,
position embeddings and piece scores stay float32. Little-endian, every section 4-byte aligned:

    "HXNN", u32 version (1), vocab, ctx, d, layers, heads
    u32 byte count, then each piece as u8 length + UTF-8 ("▁" starts a word), zero-padded to 4
    f32 piece score [vocab]                       (SentencePiece unigram log probabilities)
    f32 position embedding [ctx][d]
    i8 token embedding [vocab][d], f32 scale [vocab]   (input lookup and tied output layer)
    per layer: f32 ln1 weight [d], bias [d]; qkv i8 [3d][d] + scale [3d]; proj i8 [d][d] + scale [d];
               f32 ln2 weight [d], bias [d]; ff1 i8 [4d][d] + scale [4d]; ff2 i8 [d][4d] + scale [d]
    f32 final norm weight [d], bias [d]

The fixture (tab-separated) holds tokenizer cases and the int8 model's predictions (evaluate.Neural.predict) for the
app's unit test to match."""
import struct
import sys
from pathlib import Path

import numpy as np
import sentencepiece as spm
import torch

sys.path.insert(0, str(Path(__file__).parent))
from model import GPT  # noqa: E402


def quantize(w):
    """Per-row symmetric int8: (int8 rows, float32 scales)."""
    w = w.detach().float().cpu().numpy()
    scale = np.abs(w).max(axis=1) / 127
    scale[scale == 0] = 1
    return np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8), scale.astype(np.float32)


def dequantized(model):
    """The model as the phone runs it: every matrix through int8 and back."""
    with torch.no_grad():
        for name, p in model.named_parameters():
            if p.dim() == 2 and name != "pos.weight":
                q, s = quantize(p)
                p.copy_(torch.from_numpy(q.astype(np.float32) * s[:, None]))
    return model


def load(root, run, dev="cpu"):
    ck = torch.load(Path(root) / "runs" / run / "model.pt", map_location=dev)
    cfg = ck["config"]
    model = GPT(cfg["vocab"], cfg["ctx"], cfg["d"], cfg["layers"], cfg["heads"]).to(dev).eval()
    model.load_state_dict(ck["model"])
    return model, cfg


def write(model, cfg, sp, out):
    chunks = []

    def raw(b):
        chunks.append(b + b"\0" * (-len(b) % 4))

    def f32(a):
        raw(np.ascontiguousarray(a.detach().cpu().numpy() if torch.is_tensor(a) else a, dtype="<f4").tobytes())

    def matrix(w):
        q, s = quantize(w)
        raw(q.tobytes()); f32(s)

    raw(b"HXNN" + struct.pack("<6I", 1, cfg["vocab"], cfg["ctx"], cfg["d"], cfg["layers"], cfg["heads"]))
    pieces = b"".join(bytes([len(p)]) + p for p in (sp.id_to_piece(i).encode() for i in range(cfg["vocab"])))
    raw(struct.pack("<I", len(pieces)) + pieces)
    f32(np.array([sp.get_score(i) for i in range(cfg["vocab"])]))
    f32(model.pos.weight)
    matrix(model.tok.weight)
    for b in model.blocks:
        f32(b.ln1.weight); f32(b.ln1.bias); matrix(b.qkv.weight); matrix(b.proj.weight)
        f32(b.ln2.weight); f32(b.ln2.bias); matrix(b.ff[0].weight); matrix(b.ff[2].weight)
    f32(model.ln.weight); f32(model.ln.bias)
    Path(out).write_bytes(b"".join(chunks))


def fixture(root, run, path):
    sys.argv = sys.argv[:1]
    import evaluate
    nn = evaluate.Neural(Path(root), run, "cpu", quantized=True)
    texts = ["hi how are you", "kya kar rha hai", "i will call you", "bhai kal", "thanks for the", "see you", "ok , let me",
             "mujhe nahi pata", "what are you doing", "", "can you send me the", "aaj office", "lol that's so"]
    cases = []
    for t in texts:
        ws = t.split()
        for prefix in ("", "s", "k", "th"):
            cases.append({"context": ws, "prefix": prefix, "words": nn.predict(ws, prefix, k=6)})
    pieces = ["hello world", "kya kar rha hai bhai", "don't , won't ?", "it's 2026 ! ok", "zzqx"]
    lines = ["# pieces: text, ids"] + [f"{t}\t{' '.join(map(str, nn.sp.encode(t)))}" for t in pieces]
    lines += ["# predictions: context, prefix, words"] + [f"{' '.join(c['context'])}\t{c['prefix']}\t{' '.join(c['words'])}" for c in cases]
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(root, run, out, fixture_path=None):
    model, cfg = load(root, run)
    sp = spm.SentencePieceProcessor(model_file=str(Path(root) / f"corpus/sp{cfg['vocab']}.model"))
    write(model, cfg, sp, out)
    print(out, Path(out).stat().st_size, "bytes")
    if fixture_path:
        fixture(root, run, fixture_path)


if __name__ == "__main__":
    main(*sys.argv[1:])
