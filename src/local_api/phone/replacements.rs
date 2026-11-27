//! The text replacements a paired phone's keyboard offers as shortcuts
//! (`GET /phone/v1/replacements`): Global's, as Settings › Output keeps
//! them, so typing a phrase's trigger on the phone offers its output, as a
//! dictation here would write it. The phone keeps them on the phone.

use std::sync::RwLock;

use serde::Serialize;

use crate::app_settings::TextReplacement;

/// More than anyone keeps; a phone's strip needs no more.
const MAX_REPLACEMENTS: usize = 2_000;

/// Global's replacements from the saved settings, recorded by `phone::apply`.
pub(super) static GLOBAL: RwLock<Vec<TextReplacement>> = RwLock::new(Vec::new());

#[derive(Debug, PartialEq, Serialize)]
pub(super) struct Replacements {
    /// `[phrase, output]` in the order Settings lists them.
    pub replacements: Vec<(String, String)>,
}

/// The saved replacements that write something: one that only deletes its
/// phrase ("um") has nothing to offer.
pub(super) fn current() -> Replacements {
    let global = GLOBAL.read().unwrap_or_else(|error| error.into_inner());
    shortcuts(&global)
}

fn shortcuts(rules: &[TextReplacement]) -> Replacements {
    Replacements {
        replacements: rules
            .iter()
            .map(|rule| (rule.matched_phrase.trim(), rule.output.trim()))
            .filter(|(phrase, output)| !phrase.is_empty() && !output.is_empty())
            .map(|(phrase, output)| (phrase.to_owned(), output.to_owned()))
            .take(MAX_REPLACEMENTS)
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sends_replacements_that_write_something() {
        let rule = |phrase: &str, output: &str| TextReplacement {
            matched_phrase: phrase.into(),
            output: output.into(),
        };
        let sent = shortcuts(&[
            rule("brb", "be right back"),
            rule("um", ""),
            rule(" ", "nothing"),
            rule("open code", " OpenCode "),
        ]);
        assert_eq!(
            sent.replacements,
            vec![
                ("brb".to_owned(), "be right back".to_owned()),
                ("open code".to_owned(), "OpenCode".to_owned()),
            ]
        );
        assert_eq!(
            serde_json::to_value(&sent).unwrap(),
            serde_json::json!({"replacements": [["brb", "be right back"], ["open code", "OpenCode"]]})
        );
    }
}
