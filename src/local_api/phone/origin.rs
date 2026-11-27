//! Where a phone dictation was spoken: the app in front on the phone and the
//! language of its keyboard layout, sent as query parameters on
//! `POST /phone/v1/transcriptions` (`docs/phone-dictation.md`).
//!
//! The app picks the mode, by the same rule a desktop dictation's app does:
//! the phone app's name is read as the application name, so a mode set up
//! for "Slack" on this computer applies to Slack on the phone. The keyboard's
//! language is only a hint: it never changes what the model hears, and it
//! reaches the Output steps only while this computer is set to "auto".

use std::collections::HashMap;

use crate::dictation::context::ContextSnapshot;
use crate::transcription::models::AUTO_LANGUAGE;

/// Longer values are not an app name or a package name; they are dropped.
const MAX_CHARS: usize = 200;

#[derive(Debug, Default, PartialEq)]
pub(super) struct Origin {
    /// The app's name as the phone shows it ("Slack").
    app: Option<String>,
    /// The app's Android package name, used when its name was unreadable.
    package: Option<String>,
    /// The keyboard layout's language, a code this computer lists ("en").
    language: Option<String>,
}

impl Origin {
    /// Reads `app`, `package`, and `language` from a request path's query.
    /// Anything missing, repeated, empty, or malformed reads as unsaid.
    pub(super) fn from_path(path: &str) -> Self {
        let query = path
            .split_once('?')
            .map(|(_, query)| query)
            .unwrap_or_default();
        let mut values: HashMap<String, Option<String>> = HashMap::new();
        for (name, value) in url::form_urlencoded::parse(query.as_bytes()) {
            let value = value.trim();
            let usable = !value.is_empty()
                && value.chars().count() <= MAX_CHARS
                && !value.chars().any(char::is_control);
            values
                .entry(name.into_owned())
                .and_modify(|repeated| *repeated = None)
                .or_insert_with(|| usable.then(|| value.to_owned()));
        }
        let mut take = |name: &str| values.remove(name).flatten();
        Self {
            app: take("app"),
            package: take("package"),
            language: take("language")
                .filter(|language| crate::transcription::languages::is_listed(language)),
        }
    }

    /// The context that picks the mode: the app's name, else its package.
    /// No app reads as no context, so Global applies, as before phones sent one.
    pub(super) fn context(&self) -> ContextSnapshot {
        ContextSnapshot {
            application: self.app.clone().or_else(|| self.package.clone()),
            ..ContextSnapshot::default()
        }
    }

    /// The language the Output steps run with: this computer's, unless it is
    /// "auto", where the keyboard's says more than nothing (English drops
    /// filler words). What the model hears never changes.
    pub(super) fn output_language<'a>(&'a self, computer: &'a str) -> &'a str {
        match &self.language {
            Some(language) if computer == AUTO_LANGUAGE && language != AUTO_LANGUAGE => language,
            _ => computer,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_app_package_and_language() {
        let origin = Origin::from_path(
            "/phone/v1/transcriptions?app=WhatsApp%20Business&package=com.whatsapp.w4b&language=hi",
        );
        assert_eq!(
            origin.context().application.as_deref(),
            Some("WhatsApp Business")
        );
        assert_eq!(origin.output_language("auto"), "hi");
        // A language this computer chose is never overridden.
        assert_eq!(origin.output_language("en"), "en");
    }

    #[test]
    fn falls_back_to_the_package_then_to_global() {
        let package = Origin::from_path("/phone/v1/transcriptions?package=com.Slack");
        assert_eq!(package.context().application.as_deref(), Some("com.Slack"));
        let none = Origin::from_path("/phone/v1/transcriptions");
        assert_eq!(none.context(), ContextSnapshot::default());
        assert_eq!(none.output_language("auto"), "auto");
    }

    #[test]
    fn ignores_unknown_repeated_and_malformed_values() {
        let origin = Origin::from_path(
            "/phone/v1/transcriptions?app=Slack&app=Mail&language=klingon&package=com%0Ax",
        );
        assert_eq!(origin, Origin::default());
        let long = format!("/phone/v1/transcriptions?app={}", "a".repeat(MAX_CHARS + 1));
        assert_eq!(Origin::from_path(&long), Origin::default());
    }

    /// The phone app's name picks a mode exactly as a desktop app's does.
    #[test]
    fn the_app_name_selects_the_mode_for_that_application() {
        use crate::app_settings::{DictationMode, DictationProcessingSettings, TextReplacement};
        use crate::dictation::processor::{Profiles, Source};
        use std::sync::atomic::AtomicBool;
        let settings = DictationProcessingSettings {
            modes: vec![DictationMode {
                name: "Chat".into(),
                applications: vec!["Slack".into()],
                replacements: vec![TextReplacement {
                    matched_phrase: "eta".into(),
                    output: "ETA".into(),
                }],
                ..DictationMode::default()
            }],
            ..DictationProcessingSettings::default()
        };
        let profiles = Profiles::from_settings(&settings);
        let run = |path: &str| {
            let origin = Origin::from_path(path);
            profiles
                .process_api_cancellable(
                    "the eta is noon",
                    "en",
                    &origin.context(),
                    &AtomicBool::new(false),
                    Source::Dictation,
                )
                .text
        };
        assert_eq!(run("/phone/v1/transcriptions?app=slack"), "the ETA is noon");
        assert_eq!(
            run("/phone/v1/transcriptions?app=Messages"),
            "the eta is noon"
        );
        assert_eq!(run("/phone/v1/transcriptions"), "the eta is noon");
    }
}
