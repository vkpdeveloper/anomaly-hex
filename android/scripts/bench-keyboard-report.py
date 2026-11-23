#!/usr/bin/env python3
"""Reads what android/scripts/bench-keyboard.sh captured and prints numbers.

  bench-keyboard-report.py run <run-dir>      one run's metrics as JSON
  bench-keyboard-report.py table <run-dir>... the median of each metric across runs, as a table
  bench-keyboard-report.py passage <keyboard-top> <warmup-file> <batch-prefix>   writes the shell scripts that type

A run directory holds frames-*.txt (dumpsys gfxinfo framestats, one per typing batch), typing.trace (atrace
text for the typing) and open-*.trace (atrace text around one keyboard open each).
"""
import glob
import json
import os
import re
import statistics
import sys

FRAME_BUDGET_MS = 1000 / 60  # the emulator draws at 60 Hz


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round(p / 100 * (len(ordered) - 1)))]


def frames(run):
    """Frame time (intended vsync to completed) of every frame the keyboard process drew while typing."""
    times = []
    system_total = system_janky = 0
    for path in sorted(glob.glob(os.path.join(run, "frames-*.txt"))):
        text = open(path).read()
        if m := re.search(r"Total frames rendered: (\d+)", text):
            system_total += int(m.group(1))
        if m := re.search(r"Janky frames: (\d+)", text):
            system_janky += int(m.group(1))
        for block in re.findall(r"---PROFILEDATA---\n(.*?)---PROFILEDATA---", text, re.S):
            lines = block.strip().splitlines()
            header = lines[0].split(",")
            flags, intended, done = header.index("Flags"), header.index("IntendedVsync"), header.index("FrameCompleted")
            for row in lines[1:]:
                cols = row.split(",")
                if len(cols) <= done or cols[flags] != "0":
                    continue
                start, end = int(cols[intended]), int(cols[done])
                if end < 2**62 and end > start:
                    times.append((end - start) / 1e6)
    out = {"frames": len(times), "frame_p50_ms": percentile(times, 50), "frame_p90_ms": percentile(times, 90),
           "frame_p95_ms": percentile(times, 95), "frame_p99_ms": percentile(times, 99),
           "janky_pct": 100 * sum(t > FRAME_BUDGET_MS for t in times) / len(times) if times else None,
           "system_janky_pct": 100 * system_janky / system_total if system_total else None}
    return out


TRACE_LINE = re.compile(r"^\s*(.+)-(\d+)\s+\(\s*[\d-]+\)\s+\[\d+\]\s+\S+\s+([\d.]+): tracing_mark_write: ([BE])\|(\d+)(?:\|(.*))?$")


def sections(path):
    """Every closed trace section as (thread, name, start_s, end_s, editor_calls, pid, inside), inside being whether a key, selection or
    suggestion section runs around it, editor_calls being the
    number of hex:ic:* sections inside it (itself included for one)."""
    stacks = {}
    done = []
    for line in open(path, errors="replace"):
        m = TRACE_LINE.match(line)
        if not m:
            continue
        _, tid, ts, kind, pid, name = m.groups()
        stack = stacks.setdefault(tid, [])
        if kind == "B":
            inside = bool(stack) and (stack[-1][4] or stack[-1][0].startswith(("hex:key:", "hex:select", "hex:suggest")))
            stack.append([name or "", float(ts), 0, pid, inside])
        elif stack:
            name, start, calls, pid, inside = stack.pop()
            calls += name.startswith("hex:ic:")
            done.append((tid, name, start, float(ts), calls, pid, inside))
            if stack:
                stack[-1][2] += calls
    return done


def typing(run):
    path = os.path.join(run, "typing.trace")
    if not os.path.exists(path):
        return {}
    all_sections = sections(path)
    durations = {}
    ic_per_key = {}
    ic_kinds = {}
    for _, name, start, end, calls, _, _ in all_sections:
        if not name.startswith("hex:"):
            continue
        durations.setdefault(name, []).append((end - start) * 1000)
        if name.startswith("hex:ic:"):
            ic_kinds[name[7:]] = ic_kinds.get(name[7:], 0) + 1
        if name.startswith("hex:key:") or name == "hex:select":
            ic_per_key.setdefault(name, []).append(calls)
    keys = sum(len(v) for k, v in durations.items() if k.startswith("hex:key:"))
    out = {"keys": keys, "ic_calls_total": sum(ic_kinds.values())}
    out["ic_per_key"] = sum(ic_kinds.values()) / keys if keys else None
    out["ic_kinds"] = ic_kinds
    key_ms = [d for k, v in durations.items() if k.startswith("hex:key:") for d in v]
    out["key_ms"] = {"p50": percentile(key_ms, 50), "p90": percentile(key_ms, 90), "p99": percentile(key_ms, 99), "max": max(key_ms, default=None)}
    for name in ("letter", "space", "delete", "pick", "type", "enter"):
        values = durations.get("hex:key:" + name)
        if values:
            out["key_" + name] = {"n": len(values), "p50_ms": percentile(values, 50), "p90_ms": percentile(values, 90),
                                  "ic_p50": statistics.median(ic_per_key["hex:key:" + name])}
    for name in ("hex:suggest", "hex:select"):
        values = durations.get(name)
        if values:
            out[name[4:]] = {"n": len(values), "p50_ms": percentile(values, 50), "p90_ms": percentile(values, 90)}
    if "hex:select" in ic_per_key:
        out["select"]["ic_p50"] = statistics.median(ic_per_key["hex:select"])
    ic_ms = [d for k, v in durations.items() if k.startswith("hex:ic:") for d in v]
    out["ic_ms_p50"] = percentile(ic_ms, 50)
    # Everything the keyboard does on its main thread for a key: the key's handling, the selection update the app
    # sends back, and the suggestions (inside the key when worked out at once, after it when deferred).
    busy = sum((e - s) * 1000 for _, n, s, e, _, _, inside in all_sections
               if not inside and n.startswith(("hex:key:", "hex:select", "hex:suggest")))
    out["main_ms_per_key"] = busy / keys if keys else None
    return out


def opening(run):
    """Keyboard open: from the first lifecycle call the editor makes on the keyboard (onCreateInputView,
    onStartInput, onStartInputView; onCreate too when the trace caught it) to the first frame its process hands
    to the GPU after that. A cold open starts the process, whose first moments the trace misses."""
    lifecycle = ("hex:onCreate", "hex:onCreateInputView", "hex:onStartInput", "hex:onStartInputView")
    results = {"warm": [], "cold": []}
    for path in sorted(glob.glob(os.path.join(run, "open-*.trace"))):
        kind = "cold" if "-cold-" in path else "warm"
        mine = [s for s in sections(path) if s[1] in lifecycle]
        if not mine:
            continue
        pid = mine[-1][5]
        mine = [s for s in mine if s[5] == pid]
        start = min(s[2] for s in mine)
        ends = [e for _, n, s, e, _, p, _ in sections(path) if p == pid and n == "flush commands" and s > start]
        if ends:
            entry = {"open_ms": (min(ends) - start) * 1000}
            for _, n, s, e, _, _, _ in mine:
                entry[n[4:] + "_ms"] = (e - s) * 1000
            results[kind].append(entry)
    return results


def run_metrics(run):
    return {**frames(run), **typing(run), "open": opening(run)}


def flat(metrics):
    out = {}
    for k, v in metrics.items():
        if isinstance(v, dict) and k != "open" and k != "ic_kinds":
            for kk, vv in v.items():
                out[f"{k}.{kk}"] = vv
        elif k == "open":
            for kind, entries in v.items():
                for entry in entries:
                    for kk, vv in entry.items():
                        out.setdefault(f"open_{kind}.{kk}", []).append(vv)
        elif k != "ic_kinds":
            out[k] = v
    return out


def table(runs):
    per_run = [flat(run_metrics(r)) for r in runs]
    keys = sorted({k for m in per_run for k in m})
    print(f"{len(runs)} runs; median across runs (min..max)")
    for k in keys:
        values = []
        for m in per_run:
            v = m.get(k)
            values += v if isinstance(v, list) else [v] if v is not None else []
        if values:
            print(f"{k:32s} {statistics.median(values):10.2f}   ({min(values):.2f}..{max(values):.2f})")


# Key centres on the 1080 px wide screen, and each row's offset below the keyboard window's top edge.
KEY_ROWS = [("qwertyuiop", 190, 58, 106.9), ("asdfghjkl", 323, 112, 106.9), ("zxcvbnm", 456, 218, 107.2)]
SPACE, DELETE = (593, 590), (995, 456)
# One letter or "_" (space) or "D" (delete) per touch; "<word>" glides across the letters of the word.
WARMUP = "hello_world_this_is_a_<keyboard>_DDDDD_test_"
PASSAGE = ("the_quick_brown_fox_wrold_DDDDDDworld_"
           "how_are_you_doing_today_<thanks>_see_you_soon_DDDDD_"
           "i_will_be_there_by_noon_<meeting>_with_the_team_DDDDDDDDD_"
           "<tomorrow>_morning_works_for_me_can_you_send_it_<please>_ok_")
BATCH_TOUCHES = 25


def passage(top, warmup_path, batch_prefix):
    keys = {}
    for letters, offset, x0, step in KEY_ROWS:
        for i, c in enumerate(letters):
            keys[c] = (round(x0 + i * step), top + offset)
    keys["_"], keys["D"] = (SPACE[0], top + SPACE[1]), (DELETE[0], top + DELETE[1])

    def commands(text):
        out = []
        for word, tap in re.findall(r"<(\w+)>|(.)", text):
            if tap:
                out.append("input tap %d %d" % keys[tap])
                continue
            points = [keys[word[0]]]
            for c in word[1:]:
                (x0, y0), (x1, y1) = points[-1], keys[c]
                n = max(1, int(max(abs(x1 - x0), abs(y1 - y0)) / 120))
                points += [(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) for i in range(1, n + 1)]
            out.append("input motionevent DOWN %d %d" % points[0])
            out += ["input motionevent MOVE %d %d" % p for p in points[1:]]
            out.append("input motionevent UP %d %d" % points[-1])
        return out

    open(warmup_path, "w").write("; ".join(commands(WARMUP)) + "\n")
    touches = commands(PASSAGE)
    for i in range(0, len(touches), BATCH_TOUCHES):
        open("%s%02d.sh" % (batch_prefix, i // BATCH_TOUCHES), "w").write("; ".join(touches[i:i + BATCH_TOUCHES]) + "\n")
    print("%d keystrokes and glides, %d touch commands" % (len(re.findall(r"<\w+>|.", PASSAGE)), len(touches)), file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "run":
        print(json.dumps(run_metrics(sys.argv[2]), indent=1))
    elif len(sys.argv) >= 3 and sys.argv[1] == "table":
        table(sys.argv[2:])
    elif len(sys.argv) == 5 and sys.argv[1] == "passage":
        passage(int(sys.argv[2]), sys.argv[3], sys.argv[4])
    else:
        sys.exit(__doc__)
