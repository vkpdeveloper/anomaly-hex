//! The words a paired phone's keyboard starts from (`GET /phone/v1/words`):
//! how often each word and each pair of neighbouring words appears in this
//! computer's dictation History, so the keyboard knows the names and words
//! its person says before they type them. Counts only, never a dictation;
//! the phone keeps them on the phone.

use std::collections::HashMap;
use std::sync::RwLock;

use serde::Serialize;

use crate::history::HistoryRetention;

/// The commonest words and pairs sent; a phone's keyboard needs no more.
const MAX_WORDS: usize = 5_000;
const MAX_PAIRS: usize = 10_000;
/// Longer runs of letters are not words a keyboard learns (links, hashes).
const MAX_WORD_CHARS: usize = 32;

/// The saved History retention, recorded by `phone::apply`, so an entry
/// past it never reaches the phone while it waits for cleanup.
pub(super) static RETENTION: RwLock<Option<HistoryRetention>> = RwLock::new(None);

#[derive(Debug, Default, PartialEq, Serialize)]
pub(super) struct Words {
    /// `[spelling, count]`, commonest first, spelled as said mid-sentence.
    pub words: Vec<(String, u32)>,
    /// `[first, second, count]` in lowercase, commonest first.
    pub pairs: Vec<(String, String, u32)>,
    /// This computer's Vocabulary terms (Settings › Output), so the phone
    /// can type the names a dictation here is biased toward. Older phones
    /// ignore the field.
    pub vocabulary: Vec<String>,
}

/// The counts for the History in the data folder; nothing when it can't be read.
pub(super) fn from_history(now_ms: u64) -> Words {
    Words {
        vocabulary: crate::dictation::vocabulary::terms(),
        ..history_counts(now_ms)
    }
}

fn history_counts(now_ms: u64) -> Words {
    let Ok(root) = crate::app_paths::support_dir() else {
        return Words::default();
    };
    let entries = crate::history::read_snapshot(&root.join("history.json")).unwrap_or_default();
    let retention = *RETENTION.read().unwrap_or_else(|error| error.into_inner());
    let cutoff = retention.unwrap_or_default().cutoff(now_ms);
    count(
        entries
            .iter()
            .filter(|entry| cutoff.is_none_or(|cutoff| entry.timestamp_ms >= cutoff))
            .map(|entry| entry.final_text.as_str()),
    )
}

#[derive(Default)]
struct Seen {
    count: u32,
    /// Spellings seen anywhere but a sentence's start, where every word has a capital.
    spellings: HashMap<String, u32>,
}

/// Words are runs of letters with inner apostrophes ("don't"); pairs never
/// cross a sentence or clause mark.
pub(super) fn count<'a>(texts: impl Iterator<Item = &'a str>) -> Words {
    let mut words: HashMap<String, Seen> = HashMap::new();
    let mut pairs: HashMap<(String, String), u32> = HashMap::new();
    for text in texts {
        let mut previous: Option<String> = None;
        let mut sentence_start = true;
        let mut word = String::new();
        let mut flush =
            |word: &mut String, previous: &mut Option<String>, sentence_start: &mut bool| {
                let spelling = word.trim_matches('\'').to_string();
                word.clear();
                let chars = spelling.chars().count();
                if chars < 2 && spelling != "I" && spelling != "a" || chars > MAX_WORD_CHARS {
                    *previous = None;
                    return;
                }
                let lower = spelling.to_lowercase();
                let seen = words.entry(lower.clone()).or_default();
                seen.count += 1;
                if !*sentence_start {
                    *seen.spellings.entry(spelling).or_default() += 1;
                }
                if let Some(first) = previous.take() {
                    *pairs.entry((first, lower.clone())).or_default() += 1;
                }
                *previous = Some(lower);
                *sentence_start = false;
            };
        for c in text.chars() {
            let c = if c == '\u{2019}' { '\'' } else { c };
            if c.is_alphabetic() || (c == '\'' && !word.is_empty()) {
                word.push(c);
                continue;
            }
            if !word.is_empty() {
                flush(&mut word, &mut previous, &mut sentence_start);
            }
            if c.is_ascii_digit()
                || matches!(c, ',' | ';' | ':' | '(' | ')' | '"' | '/' | '@' | '#')
            {
                previous = None;
            }
            if matches!(c, '.' | '!' | '?' | '\n') {
                previous = None;
                sentence_start = true;
            }
        }
        if !word.is_empty() {
            flush(&mut word, &mut previous, &mut sentence_start);
        }
    }
    let mut words: Vec<(String, u32)> = words
        .into_iter()
        .map(|(lower, seen)| {
            let spelling = seen
                .spellings
                .into_iter()
                .max_by(|a, b| a.1.cmp(&b.1).then_with(|| b.0.cmp(&a.0)))
                .map(|(spelling, _)| spelling)
                .unwrap_or(lower);
            (spelling, seen.count)
        })
        .collect();
    words.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    words.truncate(MAX_WORDS);
    let mut pairs: Vec<(String, String, u32)> = pairs
        .into_iter()
        .filter(|(_, count)| *count >= 2)
        .map(|((first, second), count)| (first, second, count))
        .collect();
    pairs.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| (&a.0, &a.1).cmp(&(&b.0, &b.1))));
    pairs.truncate(MAX_PAIRS);
    Words {
        words,
        pairs,
        vocabulary: Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_words_and_neighbours_within_a_clause() {
        let words = count(
            [
                "Ask Vaibhav about the build. Vaibhav said the build is fine",
                "the build, then ship",
            ]
            .into_iter(),
        );
        let count_of = |w: &str| words.words.iter().find(|(s, _)| s == w).map(|(_, n)| *n);
        assert_eq!(count_of("Vaibhav"), Some(2));
        assert_eq!(count_of("build"), Some(3));
        // Seen only at a sentence's start: lowercase.
        assert_eq!(count_of("ask"), Some(1));
        assert_eq!(
            words.pairs.first(),
            Some(&("the".to_string(), "build".to_string(), 3))
        );
        // A comma ends the pair; one-off pairs stay home.
        assert!(
            !words
                .pairs
                .iter()
                .any(|(a, b, _)| a == "build" && b == "then")
        );
        assert!(!words.pairs.iter().any(|(a, _, _)| a == "ask"));
    }

    #[test]
    fn vocabulary_is_a_plain_list_of_terms_beside_the_counts() {
        let words = Words {
            vocabulary: vec!["Raunak".into(), "OpenCode".into()],
            ..count(["the build"].into_iter())
        };
        let json = serde_json::to_value(&words).unwrap();
        assert_eq!(
            json["vocabulary"],
            serde_json::json!(["Raunak", "OpenCode"])
        );
        assert!(json["words"].is_array() && json["pairs"].is_array());
    }

    #[test]
    fn apostrophes_stay_inside_words_only() {
        let words = count(["don\u{2019}t 'quote' x"].into_iter());
        let spellings: Vec<&str> = words.words.iter().map(|(s, _)| s.as_str()).collect();
        assert!(spellings.contains(&"don't"));
        assert!(spellings.contains(&"quote"));
        assert!(!spellings.contains(&"x"));
    }
}
