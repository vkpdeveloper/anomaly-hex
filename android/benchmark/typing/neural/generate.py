"""Generate synthetic chat threads with DeepSeek V4.1 Flash through `opencode run` (OpenCode Go).

    python3 generate.py OUT_DIR CALLS [PARALLEL]

Each call writes OUT_DIR/<n>.txt: threads separated by blank lines, one message per line as "A: text".
Resumable: existing files are skipped. Deterministic prompt per n (seeded)."""
import concurrent.futures as cf
import random
import subprocess
import sys
from pathlib import Path

TOPICS = """college assignments and deadlines; hostel or PG life; cricket match; IPL fantasy team; new phone purchase;
exam results; job interview; salary and appraisal; office politics; boss being annoying; startup idea; crypto and stocks;
wedding in the family; shaadi shopping; relatives asking about marriage; mummy papa scolding; sibling fights; festival plans
(diwali, holi, eid, christmas); trip to goa or manali; train or flight booking; cab driver drama; food delivery late;
cooking at home; gym and diet; breakup; crush and dating; situationship; group project freeloader; gaming night (bgmi,
valorant, minecraft); movie or web series review; bollywood gossip; cricket team selection; rent and landlord; electricity
bill; wifi down; laptop broken; coding bug at work; client call; meeting reschedule; doctor appointment; fever and medicines;
gym crush; birthday surprise planning; borrowing money; splitting the bill; late night maggi; exam cheating gossip;
teacher being strict; coaching classes; JEE or NEET prep; UPSC aspirant life; moving to bangalore or pune; traffic;
monsoon flooding; power cut; neighbour aunty; society WhatsApp group; pet dog; instagram reels; youtube channel;
meme sharing; viral tweet; politics argument; petrol prices; online shopping return; flipkart sale; scam call;
bank KYC; UPI payment failed; reunion plan; school friends; ex texting again; roommate not cleaning""".replace("\n", " ").split(";")
PEOPLE = ["two close friends (college age)", "best friends who swear a lot", "siblings", "a mother and her son",
          "a father and daughter", "two coworkers", "a manager and an employee", "a couple", "cousins",
          "a group chat of four friends (A, B, C, D)", "roommates", "a student and a senior", "a customer and a shop owner",
          "two gamers", "an aunt and her niece"]
STYLES = {
    "hien-chat": """Hinglish as Indians really type on WhatsApp: Hindi in Latin letters mixed with English, all lowercase
mostly, lazy spellings that vary between people (h/hai, nhi/nahi/ni, kr/kar, rha/raha, pta/pata, kya/kia, kyu/kyun, mjhe/mujhe,
bs/bas, vo/woh, yr/yaar, hn/haan, thk h/theek hai, accha/acha/achha, bhai/bhyi, kal, abhi/abi), English words inside Hindi
sentences, short replies, double texts, emojis written as text are NOT allowed (no emoji), occasional typos, missing
punctuation. Friends swear naturally when it fits (bc, mc, bsdk, chutiya, saala, bhenchod, gandu, fuck, shit) but not in
every line; family and office threads do not swear.""",
    "hien-formal": """polite Hindi written in Latin letters with English terms, the way people write to elders, teachers,
offices, landlords, banks or clients: full sentences, proper spelling (aap, kripya, dhanyavaad, namaste, ji, sir/madam),
capital letters and punctuation.""",
    "en-chat": """casual English texting by young people (India, US, UK mix): lowercase, abbreviations (u, ur, rn, tmrw, idk,
ngl, tbh, fr, ong, imo, lmk, wyd, hbu, omw, brb, lol, lmao, bruh), gen z slang where natural (rizz, mid, cap, no cap, slay,
lowkey, highkey, deadass, bet, sus, vibe, ate, delulu), stretched letters (sooo, noooo), no apostrophes (dont, cant, im),
typos, missing question marks, swearing between friends (fuck, shit, damn, wtf, stfu) but not every line.""",
    "en-formal": """clear, correct English as in work chats and emails: full sentences, capitals, punctuation, polite.""",
}
WEIGHTS = [("hien-chat", 55), ("hien-formal", 12), ("en-chat", 25), ("en-formal", 8)]


def prompt(n):
    rng = random.Random(n)
    style = rng.choices([s for s, _ in WEIGHTS], [w for _, w in WEIGHTS])[0]
    topics = rng.sample(TOPICS, 3)
    who = rng.choice(PEOPLE)
    return style, f"""Write 4 separate realistic chat threads, each between {who}. Thread topics: {'; '.join(t.strip() for t in topics)}.
Language and style: {STYLES[style]}
Each thread has 10 to 18 messages. Vary message length: many 1 to 6 words, some 10 to 30 words. Make it sound like real people,
not a textbook: real reactions, half sentences, changing the subject, inside jokes. Different speakers type differently.
Output ONLY the threads: one message per line as "A: message" (speaker letter, colon, text), a blank line between threads.
No titles, no numbering, no explanations, no emoji, no markdown."""


def run(out, n):
    path = out / f"{n:05d}.txt"
    if path.exists():
        return "skip"
    style, text = prompt(n)
    for _ in range(3):
        try:
            r = subprocess.run(["opencode", "run", "--archived", "--agent", "plan", "-m", "opencode-go/deepseek-v4.1-flash", text],
                               cwd=out / "_cwd", capture_output=True, text=True, timeout=300)
        except subprocess.TimeoutExpired:
            continue
        lines = [l.strip() for l in r.stdout.splitlines()]
        msgs = [l for l in lines if len(l) > 3 and l[1] == ":" and l[0] in "ABCD"]
        if len(msgs) >= 20:
            body = "\n".join(l if (len(l) > 3 and l[1] == ":") else "" for l in lines if l == "" or (len(l) > 3 and l[1] == ":" and l[0] in "ABCD"))
            path.write_text(f"# {style}\n{body.strip()}\n", encoding="utf-8")
            return style
    (out / "_failed.txt").open("a").write(f"{n}\n")
    return "failed"


def main(out, calls, parallel=8):
    out = Path(out); (out / "_cwd").mkdir(parents=True, exist_ok=True)
    done = {}
    with cf.ThreadPoolExecutor(int(parallel)) as pool:
        for i, s in enumerate(pool.map(lambda n: run(out, n), range(int(calls)))):
            done[s] = done.get(s, 0) + 1
            if i % 25 == 0:
                print(i, done, flush=True)
    print("done", done, flush=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
