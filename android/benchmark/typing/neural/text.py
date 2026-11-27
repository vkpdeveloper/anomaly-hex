"""Shared text normalization: what the keyboard predicts is lowercase words and . , ? ! marks."""
import re

URL = re.compile(r"https?://\S+|www\.\S+|@\w+|#\w+")
TOKEN = re.compile(r"[a-z0-9]+(?:'[a-z]+)?|[.,?!]")
HINGLISH = set("""hai h hain hu hoon tha thi the kya kyu kyun kyon nahi nhi nahin ni mein me mai main mujhe mjhe tujhe
tum tu aap hum ham kar kr karo kro karna raha rha rahi rhi rahe rhe gaya gya gayi gyi wala wali bhai yaar yr accha acha achha
theek thik bhi toh to bas bs abhi abi kal aaj phir fir kuch kch koi sab bahut bht bohot matlab yeh ye woh vo wo uska uski
unka mera meri tera teri apna apni kaise kese kaha kahan kab""".split())
ENGLISH_ONLY = set("the and is are was were you your have has with this that what for not".split())


def words(line):
    line = URL.sub(" ", line.lower().replace("’", "'"))
    return TOKEN.findall(line)


def is_hinglish(tokens):
    """Mostly Hindi-in-Latin-letters markers; English 'to', 'me', 'the' alone don't count."""
    w = [t for t in tokens if t[0].isalpha()]
    if len(w) < 3:
        return False
    hi = sum(t in HINGLISH and t not in ("to", "me", "main", "the") for t in w)
    return hi >= 2 and hi / len(w) >= 0.2
