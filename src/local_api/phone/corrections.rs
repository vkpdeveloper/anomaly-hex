//! What a paired phone's keyboard learned from a fixed dictation
//! (`POST /phone/v1/corrections`): the text around the insertion as the
//! phone dictated it, the dictated words, and the same field after the
//! person edited it. The computer decides which words the fix taught with
//! the same rule as Labs › Learn from corrections on macOS
//! (`correction_terms::learned_terms`) and adds them to Vocabulary, so its
//! next dictation, and the phone's next `GET /phone/v1/words`, know them.
//!
//! Phone dictation's own lab is the opt-in: this does not need Learn from
//! corrections on. The text is read, compared, and dropped: never logged,
//! never stored; the log says only how many terms were learned.

use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use crate::dictation::correction_terms::learned_terms;
use crate::dictation::vocabulary::{self, VocabularyEntry};

/// A few hundred words of text fits well inside this; `learned_terms`
/// itself ignores more than 400 words.
pub(super) const MAX_BODY_BYTES: usize = 64 * 1024;

/// One save at a time, so two corrections arriving together do not read the
/// same settings and overwrite each other's terms.
static SAVING: Mutex<()> = Mutex::new(());

#[derive(Deserialize)]
pub(super) struct Correction {
    baseline: String,
    inserted: String,
    edited: String,
}

#[derive(Debug, PartialEq, Serialize)]
pub(super) struct Learned {
    pub learned: Vec<String>,
}

/// The terms `correction` teaches that `known` lacks.
fn terms(correction: &Correction, known: &[VocabularyEntry]) -> Vec<String> {
    learned_terms(
        &correction.baseline,
        &correction.inserted,
        &correction.edited,
        known,
    )
}

/// Learns from `body` (JSON `{baseline, inserted, edited}`) and saves the
/// new terms through `save`, which returns the ones it added. `None` when
/// the body is not that JSON.
pub(super) fn learn(body: &[u8], save: impl FnOnce(&[String]) -> Vec<String>) -> Option<Learned> {
    let correction: Correction = serde_json::from_slice(body).ok()?;
    let _saving = SAVING.lock().unwrap_or_else(|error| error.into_inner());
    let terms = terms(&correction, &vocabulary::entries());
    let learned = if terms.is_empty() {
        Vec::new()
    } else {
        save(&terms)
    };
    tracing::info!(terms = learned.len(), "phone correction learned");
    Some(Learned { learned })
}

/// [`learn`], saving into the settings in the data folder.
pub(super) fn learn_and_save(body: &[u8]) -> Option<Learned> {
    learn(body, crate::dictation::correction_watch::save)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn body(baseline: &str, inserted: &str, edited: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "baseline": baseline,
            "inserted": inserted,
            "edited": edited,
        }))
        .unwrap()
    }

    #[test]
    fn a_fixed_name_is_saved_and_returned() {
        let saved = Mutex::new(Vec::new());
        let learned = learn(
            &body(
                "hi rowan ak how are you",
                "hi rowan ak how are you",
                "hi Raunak how are you",
            ),
            |terms| {
                saved.lock().unwrap().extend_from_slice(terms);
                terms.to_vec()
            },
        )
        .unwrap();
        assert_eq!(learned.learned, ["Raunak"]);
        assert_eq!(*saved.lock().unwrap(), ["Raunak"]);
    }

    #[test]
    fn an_unchanged_or_unrelated_field_saves_nothing() {
        let never = |_: &[String]| -> Vec<String> { panic!("nothing to save") };
        let same = body("send it now", "send it now", "send it now");
        assert_eq!(learn(&same, never).unwrap().learned, Vec::<String>::new());
        // An edit that does not touch the dictated words teaches nothing.
        let outside = body("Note: send it now", "send it now", "Notes: send it now");
        assert!(learn(&outside, never).unwrap().learned.is_empty());
    }

    #[test]
    fn a_body_that_is_not_a_correction_is_refused() {
        let never = |_: &[String]| -> Vec<String> { panic!("nothing to save") };
        assert!(learn(b"not json", never).is_none());
        assert!(learn(br#"{"baseline":"a","inserted":"b"}"#, never).is_none());
    }

    #[test]
    fn a_huge_field_teaches_nothing() {
        let long = "word ".repeat(500);
        let edited = format!("{long}Raunak");
        let never = |_: &[String]| -> Vec<String> { panic!("nothing to save") };
        assert!(
            learn(&body(&long, &long, &edited), never)
                .unwrap()
                .learned
                .is_empty()
        );
    }
}
