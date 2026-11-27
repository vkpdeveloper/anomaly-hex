"""Train the word-piece tokenizer and the small GPT on corpus/train.txt.

    venv/bin/python scripts/train.py ROOT RUN STEPS [d layers heads vocab init_run peak_lr]
"""
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import sentencepiece as spm
import torch
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).parent))
from model import GPT  # noqa: E402


def tokenizer(root, vocab):
    prefix = root / f"corpus/sp{vocab}"
    if not prefix.with_suffix(".model").exists():
        spm.SentencePieceTrainer.train(input=str(root / "corpus/train.txt"), model_prefix=str(prefix), vocab_size=vocab,
                                       model_type="unigram", character_coverage=1.0, input_sentence_size=2_000_000,
                                       shuffle_input_sentence=True, bos_id=1, eos_id=2, pad_id=-1, unk_id=0,
                                       split_digits=True, num_threads=8)
    return spm.SentencePieceProcessor(model_file=str(prefix.with_suffix(".model")))


def encoded(root, sp, vocab):
    path = root / f"corpus/train{vocab}.npy"
    if not path.exists():
        lines = (root / "corpus/train.txt").read_text(encoding="utf-8").splitlines()
        parts = []
        for chunk in range(0, len(lines), 200_000):
            for seq in sp.encode(lines[chunk:chunk + 200_000]):
                parts.append(np.array([1] + seq + [2], dtype=np.uint16))
        np.save(path, np.concatenate(parts))
    return np.load(path)


def main(root, run, steps, d=256, layers=4, heads=4, vocab=16000, init=None, top=3e-3):
    root, steps, d, layers, heads, vocab, top = Path(root), int(steps), int(d), int(layers), int(heads), int(vocab), float(top)
    sp = tokenizer(root, vocab)
    data = torch.from_numpy(encoded(root, sp, vocab).astype(np.int64))
    dev = "cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu"
    ctx, batch = 48, 256
    data = data.to(dev)  # small enough for the GPU; batches are gathered there
    offsets = torch.arange(ctx + 1, device=dev)
    torch.manual_seed(1)
    model = GPT(vocab, ctx, d, layers, heads).to(dev)
    if init:  # continue from another run's weights (same shape)
        model.load_state_dict(torch.load(root / "runs" / init / "model.pt", map_location=dev)["model"])
    params = sum(p.numel() for p in model.parameters())
    print(f"{len(data):,} tokens, {params:,} parameters, {dev}", flush=True)
    decay = [p for n, p in model.named_parameters() if p.dim() >= 2]
    rest = [p for n, p in model.named_parameters() if p.dim() < 2]
    opt = torch.optim.AdamW([{"params": decay, "weight_decay": 0.1}, {"params": rest, "weight_decay": 0.0}], lr=top, betas=(0.9, 0.95))
    warm = 500
    out = root / "runs" / run; out.mkdir(parents=True, exist_ok=True)
    g = torch.Generator().manual_seed(2)
    start, seen = time.time(), 0
    for step in range(steps + 1):
        lr = top * min(1, step / warm) * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * min(1, step / steps))))
        for group in opt.param_groups:
            group["lr"] = lr
        ix = torch.randint(len(data) - ctx - 1, (batch,), generator=g).to(dev)
        xy = data[ix[:, None] + offsets]
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=dev == "cuda"):
            logits = model(xy[:, :-1])
        loss = F.cross_entropy(logits.float().reshape(-1, vocab), xy[:, 1:].reshape(-1))
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        seen += batch * ctx
        if step % 200 == 0:
            print(f"step {step} loss {loss.item():.3f} lr {lr:.5f} {seen / (time.time() - start):,.0f} tok/s epochs {seen / len(data):.2f}", flush=True)
        if step % 2000 == 0 or step == steps:
            torch.save({"model": model.state_dict(), "config": dict(vocab=vocab, ctx=ctx, d=d, layers=layers, heads=heads),
                        "step": step}, out / "model.pt")
    (out / "config.json").write_text(json.dumps(dict(vocab=vocab, ctx=ctx, d=d, layers=layers, heads=heads, steps=steps, params=params)))


if __name__ == "__main__":
    main(*sys.argv[1:])
