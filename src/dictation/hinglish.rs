//! Labs › Hinglish in Latin letters: Hindi that a speech model writes in
//! Devanagari comes out the way people chat in Hindi, in Latin letters, so
//! "क्या हाल है यार" reads "kya haal hai yaar". Latin text, emoji, and every
//! other script pass through as heard, so a dictation that mixes Hindi and
//! English keeps its English.
//!
//! The rewrite is local and deterministic: no model, no network. A short
//! table holds the common words whose chat spelling no rule gives ("नहीं" is
//! "nahi", "हम" is "hum") and English words written in Devanagari ("टीम" is
//! "team"). Every other word is read as syllables and spelled by rule: the
//! inherent "a" Hindi doesn't say is dropped (always at the end, and in the
//! middle between two sounded syllables, so "समझना" is "samajhna"),
//! anusvara and chandrabindu become "n" or "m", nukta letters keep their
//! sound ("ज़" is "z"), and long vowels take their chat spellings ("ee" and
//! "oo" inside a word, "aa" only in a closed syllable, so "यार" is "yaar"
//! and "खाना" is "khana"). Output is lowercase. A danda becomes a full stop
//! and Devanagari digits become ASCII ones.

use std::collections::HashMap;
use std::sync::LazyLock;

const NUKTA: char = '\u{093C}';
const VIRAMA: char = '\u{094D}';
const ANUSVARA: char = '\u{0902}';
const CHANDRABINDU: char = '\u{0901}';
const INVERTED_CHANDRABINDU: char = '\u{0900}';
const VISARGA: char = '\u{0903}';
const ZWNJ: char = '\u{200C}';
const ZWJ: char = '\u{200D}';

/// Words whose chat spelling the rules don't give. Keys are matched after
/// [`override_key`], so a nukta or a chandrabindu written either way finds
/// the same entry.
const OVERRIDES: &[(&str, &str)] = &[
    // Hindi.
    ("नहीं", "nahi"),
    ("हाँ", "haan"),
    ("माँ", "maa"),
    ("क्यों", "kyun"),
    ("क्यूँ", "kyun"),
    ("क्योंकि", "kyunki"),
    ("क्यूंकि", "kyunki"),
    ("वह", "woh"),
    ("वे", "ve"),
    ("हम", "hum"),
    ("हमें", "humein"),
    ("हमको", "humko"),
    ("हमारा", "humara"),
    ("हमारी", "humari"),
    ("हमारे", "humare"),
    ("दीदी", "didi"),
    ("गई", "gayi"),
    ("नई", "nayi"),
    ("आई", "aayi"),
    ("दोनों", "dono"),
    ("चाय", "chai"),
    ("अरे", "arre"),
    ("वक़्त", "waqt"),
    ("रुपये", "rupaye"),
    ("रुपया", "rupaya"),
    ("बारे", "baare"),
    ("धन्यवाद", "dhanyavaad"),
    ("ॐ", "om"),
    // English, as a Hindi model writes it.
    ("मिटिंग", "meeting"),
    ("टीम", "team"),
    ("मैसेज", "message"),
    ("फ़ोन", "phone"),
    ("कॉल", "call"),
    ("ऑफ़िस", "office"),
    ("टाइम", "time"),
    ("प्रोजेक्ट", "project"),
    ("प्लीज़", "please"),
    ("सॉरी", "sorry"),
    ("ओके", "okay"),
    ("थैंक्स", "thanks"),
    ("थैंक", "thank"),
    ("यू", "you"),
    ("हेलो", "hello"),
    ("हैलो", "hello"),
    ("बाय", "bye"),
    ("गुड", "good"),
    ("नाइट", "night"),
    ("लेट", "late"),
    ("बिज़ी", "busy"),
    ("फ़्री", "free"),
    ("प्लान", "plan"),
    ("बॉस", "boss"),
    ("लंच", "lunch"),
    ("डिनर", "dinner"),
    ("पार्टी", "party"),
    ("मूवी", "movie"),
    ("वीडियो", "video"),
    ("लैपटॉप", "laptop"),
    ("कंप्यूटर", "computer"),
    ("कंपनी", "company"),
    ("ईमेल", "email"),
    ("इंटरनेट", "internet"),
    ("ऑनलाइन", "online"),
    ("मोबाइल", "mobile"),
    ("ऐप", "app"),
    ("डॉक्टर", "doctor"),
    ("स्कूल", "school"),
    ("कॉलेज", "college"),
    ("क्लास", "class"),
    ("एग्ज़ाम", "exam"),
    ("बैंक", "bank"),
    ("कार", "car"),
    ("ट्रेन", "train"),
    ("टिकट", "ticket"),
    ("होटल", "hotel"),
    ("ग्रुप", "group"),
    ("चैट", "chat"),
    ("मैच", "match"),
    ("प्रॉब्लम", "problem"),
    ("आइडिया", "idea"),
    ("एक्चुअली", "actually"),
    ("बेसिकली", "basically"),
    ("रियली", "really"),
    ("सीरियस", "serious"),
    ("डन", "done"),
    ("रेडी", "ready"),
    ("वेट", "wait"),
    ("फ़ाइनल", "final"),
    ("फ़ाइल", "file"),
    ("डेडलाइन", "deadline"),
    ("क्लाइंट", "client"),
    ("रिपोर्ट", "report"),
    ("अपडेट", "update"),
    ("चेक", "check"),
    ("शेयर", "share"),
    ("नंबर", "number"),
    ("पासवर्ड", "password"),
    ("कोड", "code"),
    ("बग", "bug"),
    ("फ़िक्स", "fix"),
    ("सर्वर", "server"),
    ("डेटा", "data"),
    ("डाटा", "data"),
    ("स्क्रीन", "screen"),
    ("सेटिंग्स", "settings"),
    ("मैडम", "madam"),
    ("डेट", "date"),
    ("जॉब", "job"),
    ("वर्क", "work"),
    ("फ़्रेंड", "friend"),
    ("फ़ैमिली", "family"),
    ("हॉस्पिटल", "hospital"),
    ("ऑर्डर", "order"),
    ("पेमेंट", "payment"),
    ("कार्ड", "card"),
    ("कैश", "cash"),
    ("बजट", "budget"),
    ("सैलरी", "salary"),
    ("टेंशन", "tension"),
    ("कॉफ़ी", "coffee"),
    ("लाइफ़", "life"),
    ("लाइक", "like"),
    ("लव", "love"),
    ("बट", "but"),
    ("गेम", "game"),
    ("म्यूज़िक", "music"),
    ("सॉन्ग", "song"),
    ("होम", "home"),
    ("कूल", "cool"),
    ("ग्रेट", "great"),
    ("नाइस", "nice"),
    ("प्रेज़ेंटेशन", "presentation"),
    ("डॉक्यूमेंट", "document"),
    ("रिप्लाई", "reply"),
    ("वीकेंड", "weekend"),
    ("डिलीट", "delete"),
    ("स्टार्ट", "start"),
];

static SPELLINGS: LazyLock<HashMap<String, &'static str>> = LazyLock::new(|| {
    OVERRIDES
        .iter()
        .map(|&(word, spelling)| (override_key(&normalize(word)), spelling))
        .collect()
});

/// `text` with every Devanagari word in Latin letters and everything else
/// as it was.
pub fn apply(text: &str) -> String {
    if !text.chars().any(is_devanagari) {
        return text.to_owned();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut index = 0;
    while index < chars.len() {
        let c = chars[index];
        if is_letter(c) {
            let start = index;
            index += 1;
            // A joiner belongs to the word only between two of its letters,
            // so an emoji sequence after a word keeps its own.
            while index < chars.len()
                && (is_letter(chars[index])
                    || (is_joiner(chars[index])
                        && chars.get(index + 1).is_some_and(|&next| is_letter(next))))
            {
                index += 1;
            }
            out.push_str(&word_in_latin(&chars[start..index]));
            continue;
        }
        match c {
            // A danda ends a sentence: a full stop against the last word.
            '\u{0964}' | '\u{0965}' => {
                out.truncate(out.trim_end_matches(' ').len());
                out.push('.');
                if chars
                    .get(index + 1)
                    .is_some_and(|next| next.is_alphanumeric())
                {
                    out.push(' ');
                }
            }
            '\u{0970}' => out.push('.'),
            '\u{0966}'..='\u{096F}' => {
                out.push(char::from_digit(c as u32 - 0x0966, 10).unwrap_or(c));
            }
            _ => out.push(c),
        }
        index += 1;
    }
    out
}

fn is_devanagari(c: char) -> bool {
    ('\u{0900}'..='\u{097F}').contains(&c)
}

/// Part of a Devanagari word: everything in the block but the dandas,
/// digits, and abbreviation sign.
fn is_letter(c: char) -> bool {
    is_devanagari(c) && !matches!(c, '\u{0964}'..='\u{096F}' | '\u{0970}')
}

fn is_joiner(c: char) -> bool {
    c == ZWJ || c == ZWNJ
}

fn word_in_latin(word: &[char]) -> String {
    let normal = normalize_chars(word.iter().copied());
    if let Some(spelling) = SPELLINGS.get(&override_key(&normal)) {
        return (*spelling).to_owned();
    }
    let mut syllables = syllables(&normal);
    drop_silent_schwas(&mut syllables);
    spell(&syllables)
}

fn normalize(word: &str) -> Vec<char> {
    normalize_chars(word.chars())
}

/// Precomposed nukta letters split into letter and nukta, and joiners
/// dropped: they only choose how a conjunct is drawn.
fn normalize_chars(chars: impl Iterator<Item = char>) -> Vec<char> {
    let mut normal = Vec::new();
    for c in chars {
        if let Some(base) = without_nukta(c) {
            normal.push(base);
            normal.push(NUKTA);
        } else if !is_joiner(c) {
            normal.push(c);
        }
    }
    normal
}

fn without_nukta(c: char) -> Option<char> {
    Some(match c {
        '\u{0958}' => 'क',
        '\u{0959}' => 'ख',
        '\u{095A}' => 'ग',
        '\u{095B}' => 'ज',
        '\u{095C}' => 'ड',
        '\u{095D}' => 'ढ',
        '\u{095E}' => 'फ',
        '\u{095F}' => 'य',
        '\u{0929}' => 'न',
        '\u{0931}' => 'र',
        '\u{0934}' => 'ळ',
        _ => return None,
    })
}

/// The table's key for a normalized word: models write "हूँ" and "हूं",
/// "फ़ोन" and "फोन" for the same word.
fn override_key(normal: &[char]) -> String {
    normal
        .iter()
        .filter(|&&c| c != NUKTA)
        .map(|&c| match c {
            CHANDRABINDU | INVERTED_CHANDRABINDU => ANUSVARA,
            c => c,
        })
        .collect()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Vowel {
    /// The inherent "a" (or an independent अ).
    Schwa,
    /// No vowel: a written virama, or a schwa Hindi doesn't say.
    Silent,
    Aa,
    I,
    Ii,
    U,
    Uu,
    R,
    E,
    Ai,
    O,
    Au,
    CandraE,
    CandraO,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Nasal {
    Anusvara,
    Chandrabindu,
}

/// A consonant cluster (empty for an independent vowel) and its vowel.
#[derive(Debug)]
struct Syllable {
    /// Each consonant and whether it carries a nukta.
    consonants: Vec<(char, bool)>,
    vowel: Vowel,
    nasal: Option<Nasal>,
    visarga: bool,
}

impl Syllable {
    fn sounded(&self) -> bool {
        self.vowel != Vowel::Silent
    }
}

fn is_consonant(c: char) -> bool {
    ('\u{0915}'..='\u{0939}').contains(&c)
}

fn independent_vowel(c: char) -> Option<Vowel> {
    Some(match c {
        'अ' | 'ऄ' => Vowel::Schwa,
        'आ' => Vowel::Aa,
        'इ' => Vowel::I,
        'ई' => Vowel::Ii,
        'उ' => Vowel::U,
        'ऊ' => Vowel::Uu,
        'ऋ' | 'ॠ' => Vowel::R,
        'ऍ' | 'ॲ' => Vowel::CandraE,
        'ऎ' | 'ए' => Vowel::E,
        'ऐ' => Vowel::Ai,
        'ऑ' => Vowel::CandraO,
        'ऒ' | 'ओ' => Vowel::O,
        'औ' => Vowel::Au,
        _ => return None,
    })
}

fn vowel_sign(c: char) -> Option<Vowel> {
    Some(match c {
        '\u{093E}' => Vowel::Aa,
        '\u{093F}' => Vowel::I,
        '\u{0940}' => Vowel::Ii,
        '\u{0941}' => Vowel::U,
        '\u{0942}' => Vowel::Uu,
        '\u{0943}' | '\u{0944}' => Vowel::R,
        '\u{0945}' => Vowel::CandraE,
        '\u{0946}' | '\u{0947}' => Vowel::E,
        '\u{0948}' => Vowel::Ai,
        '\u{0949}' => Vowel::CandraO,
        '\u{094A}' | '\u{094B}' => Vowel::O,
        '\u{094C}' => Vowel::Au,
        _ => return None,
    })
}

/// A normalized word as written syllables. Signs Hindi rarely writes
/// (avagraha, Vedic accents) are skipped.
fn syllables(chars: &[char]) -> Vec<Syllable> {
    let mut syllables: Vec<Syllable> = Vec::new();
    let mut index = 0;
    while index < chars.len() {
        let c = chars[index];
        index += 1;
        if is_consonant(c) {
            let mut consonants = Vec::new();
            let mut letter = c;
            let vowel = loop {
                let nukta = chars.get(index) == Some(&NUKTA);
                if nukta {
                    index += 1;
                }
                consonants.push((letter, nukta));
                let Some(&mark) = chars.get(index) else {
                    break Vowel::Schwa;
                };
                if mark == VIRAMA {
                    index += 1;
                    match chars.get(index) {
                        Some(&next) if is_consonant(next) => {
                            letter = next;
                            index += 1;
                        }
                        _ => break Vowel::Silent,
                    }
                } else if let Some(vowel) = vowel_sign(mark) {
                    index += 1;
                    break vowel;
                } else {
                    break Vowel::Schwa;
                }
            };
            syllables.push(Syllable {
                consonants,
                vowel,
                nasal: None,
                visarga: false,
            });
        } else if let Some(vowel) = independent_vowel(c) {
            syllables.push(Syllable {
                consonants: Vec::new(),
                vowel,
                nasal: None,
                visarga: false,
            });
        } else if let Some(last) = syllables.last_mut() {
            match c {
                ANUSVARA => last.nasal = Some(Nasal::Anusvara),
                CHANDRABINDU | INVERTED_CHANDRABINDU => last.nasal = Some(Nasal::Chandrabindu),
                VISARGA => last.visarga = true,
                _ => {}
            }
        }
    }
    syllables
}

/// Hindi's schwa deletion. A word's last schwa goes unless the word is one
/// syllable or ends in a conjunct with र, य, or व ("मित्र" is "mitra").
/// From the end, a middle schwa goes when its single consonant sits between
/// a sounded syllable that no anusvara closes and a single sounded
/// consonant, so "करना" is "karna", "ज़िंदगी" is "zindagi", and "नमस्ते"
/// keeps its "a".
fn drop_silent_schwas(syllables: &mut [Syllable]) {
    let count = syllables.len();
    if count < 2 {
        return;
    }
    let last = &mut syllables[count - 1];
    let ends_in_conjunct =
        last.consonants.len() >= 2 && matches!(last.consonants.last(), Some(('र' | 'य' | 'व', _)));
    if last.vowel == Vowel::Schwa
        && !last.consonants.is_empty()
        && last.nasal.is_none()
        && !last.visarga
        && !ends_in_conjunct
    {
        last.vowel = Vowel::Silent;
    }
    for index in (1..count - 1).rev() {
        let previous = &syllables[index - 1];
        let this = &syllables[index];
        let next = &syllables[index + 1];
        let silent = this.vowel == Vowel::Schwa
            && this.consonants.len() == 1
            && this.nasal.is_none()
            && !this.visarga
            && previous.sounded()
            && previous.nasal != Some(Nasal::Anusvara)
            && !previous.visarga
            && next.consonants.len() == 1
            && next.sounded();
        if silent {
            syllables[index].vowel = Vowel::Silent;
        }
    }
}

fn spell(syllables: &[Syllable]) -> String {
    let mut out = String::new();
    for (index, syllable) in syllables.iter().enumerate() {
        let cluster = &syllable.consonants;
        for (position, &(letter, nukta)) in cluster.iter().enumerate() {
            let before = position.checked_sub(1).map(|p| cluster[p].0);
            let after = cluster.get(position + 1).map(|&(c, _)| c);
            out.push_str(consonant(letter, nukta, before, after, syllable.vowel));
        }
        out.push_str(vowel(syllables, index));
        let next = syllables.get(index + 1);
        if syllable.nasal.is_some() {
            out.push_str(nasal(syllable, next));
        }
        if syllable.visarga && next.is_none() {
            out.push('h');
        }
    }
    out
}

/// One consonant of a cluster, given its neighbours in the cluster.
fn consonant(
    letter: char,
    nukta: bool,
    before: Option<char>,
    after: Option<char>,
    vowel: Vowel,
) -> &'static str {
    match letter {
        'क' => "k",
        'ख' => "kh",
        'ग' => "g",
        'घ' => "gh",
        'ञ' if before == Some('ज') => "y",
        'ङ' | 'ञ' => "n",
        // "अच्छा" is "accha", "बच्चा" is "baccha".
        'च' if matches!(after, Some('च' | 'छ')) => "c",
        'च' | 'छ' => "ch",
        'ज' if nukta => "z",
        // "ज्ञान" is "gyaan".
        'ज' if after == Some('ञ') => "g",
        'ज' => "j",
        'झ' => "jh",
        'ट' | 'त' => "t",
        'ठ' | 'थ' => "th",
        'ड' | 'द' => "d",
        'ढ' | 'ध' => "dh",
        'ण' | 'न' => "n",
        'प' => "p",
        'फ' if nukta => "f",
        'फ' => "ph",
        'ब' => "b",
        'भ' => "bh",
        'म' => "m",
        'य' => "y",
        'र' => "r",
        'ल' | 'ळ' => "l",
        'व' => labial(before, after, vowel),
        'श' | 'ष' => "sh",
        'स' => "s",
        'ह' => "h",
        _ => "",
    }
}

/// व is "w" after a consonant ("स्वाद" is "swaad") and before an open
/// vowel ("वाला" is "wala"), else "v" ("विश्वास" is "vishwaas", "देव" is
/// "dev").
fn labial(before: Option<char>, after: Option<char>, vowel: Vowel) -> &'static str {
    if before.is_some() {
        return "w";
    }
    if after.is_some() {
        return "v";
    }
    match vowel {
        Vowel::Schwa | Vowel::Aa | Vowel::E | Vowel::Ai | Vowel::O | Vowel::Au => "w",
        _ => "v",
    }
}

fn vowel(syllables: &[Syllable], index: usize) -> &'static str {
    let syllable = &syllables[index];
    let next = syllables.get(index + 1);
    let last = next.is_none();
    let independent = syllable.consonants.is_empty();
    match syllable.vowel {
        Vowel::Silent => "",
        // A first schwa before a silent ह is "e": "पहला" is "pehla", "रह"
        // is "reh".
        Vowel::Schwa
            if index == 0
                && !independent
                && syllable.nasal.is_none()
                && next.is_some_and(|next| {
                    next.consonants.as_slice() == [('ह', false)]
                        && !next.sounded()
                        && next.nasal.is_none()
                }) =>
        {
            "e"
        }
        Vowel::Schwa => "a",
        // "आप" is "aap" and "यार" is "yaar", but "खाना" is "khana" and
        // "हुआ" is "hua".
        Vowel::Aa if independent && index == 0 => "aa",
        Vowel::Aa if last => "a",
        Vowel::Aa
            if syllable.nasal.is_some()
                || next.is_some_and(|next| !next.consonants.is_empty() && !next.sounded()) =>
        {
            "aa"
        }
        Vowel::Aa => "a",
        Vowel::I => "i",
        Vowel::Ii if last => "i",
        Vowel::Ii => "ee",
        Vowel::U => "u",
        // "हूँ" is "hoon", "तू" is "tu", "करूँगा" is "karunga".
        Vowel::Uu if last && syllable.nasal.is_some() => "oo",
        Vowel::Uu if last || syllable.nasal.is_some() => "u",
        Vowel::Uu => "oo",
        Vowel::R => "ri",
        // "गए" is "gaye", "चाहिए" is "chahiye".
        Vowel::E if independent && index > 0 && syllables[index - 1].sounded() => "ye",
        Vowel::E | Vowel::CandraE => "e",
        Vowel::Ai => "ai",
        Vowel::O | Vowel::CandraO => "o",
        Vowel::Au => "au",
    }
}

/// Anusvara and chandrabindu: "m" before a lip consonant ("लंबा" is
/// "lamba"), nothing before न ("मैंने" is "maine"), "in" after a last e
/// ("में" is "mein"), else "n".
fn nasal(syllable: &Syllable, next: Option<&Syllable>) -> &'static str {
    let Some(next) = next else {
        return if syllable.vowel == Vowel::E {
            "in"
        } else {
            "n"
        };
    };
    match next.consonants.first() {
        Some(('प' | 'फ' | 'ब' | 'भ' | 'म', _)) => "m",
        Some(('न' | 'ण', _)) => "",
        _ => "n",
    }
}

#[cfg(test)]
mod tests {
    use super::apply;

    fn latin(words: &[(&str, &str)]) {
        for &(hindi, expected) in words {
            assert_eq!(apply(hindi), expected, "{hindi}");
        }
    }

    #[test]
    fn writes_everyday_hindi_the_chat_way() {
        latin(&[
            ("नहीं", "nahi"),
            ("क्या", "kya"),
            ("ठीक", "theek"),
            ("है", "hai"),
            ("हूँ", "hoon"),
            ("हूं", "hoon"),
            ("यार", "yaar"),
            ("मैं", "main"),
            ("खुश", "khush"),
            ("भाई", "bhai"),
            ("कैसे", "kaise"),
            ("अच्छा", "accha"),
            ("मुझे", "mujhe"),
            ("मीटिंग", "meeting"),
            ("हाल", "haal"),
            ("आप", "aap"),
            ("खाना", "khana"),
            ("हुआ", "hua"),
            ("बहुत", "bahut"),
            ("ज़रूर", "zaroor"),
            ("थोड़ा", "thoda"),
            ("लड़का", "ladka"),
            ("ज्ञान", "gyaan"),
            ("विश्वास", "vishwaas"),
            ("वाला", "wala"),
            ("गए", "gaye"),
            ("चाहिए", "chahiye"),
            ("यह", "yeh"),
        ]);
    }

    #[test]
    fn drops_the_schwas_hindi_does_not_say() {
        latin(&[
            ("करना", "karna"),
            ("समझना", "samajhna"),
            ("समझ", "samajh"),
            ("अपना", "apna"),
            ("बचपन", "bachpan"),
            ("मतलब", "matlab"),
            ("दोस्त", "dost"),
            ("मित्र", "mitra"),
            ("नमस्ते", "namaste"),
            ("ज़िंदगी", "zindagi"),
            ("पहले", "pehle"),
            ("रहना", "rehna"),
            ("कल", "kal"),
            ("न", "na"),
        ]);
    }

    #[test]
    fn spells_nasals_by_what_follows() {
        latin(&[
            ("में", "mein"),
            ("मैंने", "maine"),
            ("संबंध", "sambandh"),
            ("करेंगे", "karenge"),
            ("करूँगा", "karunga"),
            ("लोगों", "logon"),
            ("हैं", "hain"),
            ("वहाँ", "wahan"),
        ]);
    }

    #[test]
    fn reads_a_word_however_the_model_encodes_it() {
        // Precomposed ज़ (U+095B) and ज + nukta are the same letter.
        assert_eq!(apply("\u{095B}रूर"), apply("ज\u{093C}रूर"));
        assert_eq!(apply("फ़ोन"), "phone");
        assert_eq!(apply("फोन"), "phone");
        // A joiner only picks a conjunct's shape.
        assert_eq!(apply("क्\u{200D}या"), "kya");
    }

    #[test]
    fn rewrites_whole_sentences() {
        assert_eq!(
            apply("क्या है यार मैं खुश नहीं हूँ, ठीक है"),
            "kya hai yaar main khush nahi hoon, theek hai"
        );
        assert_eq!(
            apply("भाई कैसे हो? सब अच्छा है।"),
            "bhai kaise ho? sab accha hai."
        );
        assert_eq!(
            apply("मुझे लगता है कि हमें कल मिलना चाहिए ।"),
            "mujhe lagta hai ki humein kal milna chahiye."
        );
        assert_eq!(apply("ठीक है।अब चलो"), "theek hai. ab chalo");
    }

    #[test]
    fn keeps_english_numbers_and_emoji_as_heard() {
        assert_eq!(
            apply("कल की मीटिंग 5 बजे है, please confirm करो"),
            "kal ki meeting 5 baje hai, please confirm karo"
        );
        assert_eq!(apply("मुझे ३ बजे call करना है"), "mujhe 3 baje call karna hai");
        let english = "Hello, the 3:30 sync moved 👨\u{200D}👩\u{200D}👧 (OK?)";
        assert_eq!(apply(english), english);
        assert_eq!(apply("है\u{200D}👍"), "hai\u{200D}👍");
        assert_eq!(apply("पहली बात\nदूसरी बात"), "pehli baat\ndoosri baat");
    }
}
