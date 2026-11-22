//! Rewriting text someone already typed, for a paired phone keyboard's
//! Rewrite (`POST /phone/v1/rewrite`): one OpenCode request with Global
//! mode's model, variant, and deadline. Unlike a dictation's rewrite, a
//! failure never falls back to the text sent: the phone keeps its own text
//! and says why.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use super::{PostProcessingSettings, Profile, generate_on};
use crate::desktop::opencode_service::{self, Missing};
use crate::dictation::text_replacements::ReplacementSet;

/// The phone waits for the answer, so a longer Global deadline is cut to this.
pub(crate) const MAX_DEADLINE: Duration = Duration::from_secs(30);

const INSTRUCTIONS: &str = "You rewrite text a person typed. Fix grammar, spelling, punctuation, and clarity. Keep the meaning, facts, numbers, names, tone, and language: never translate, add, or drop content. Keep line breaks and lists. Return only the rewritten text, with no explanation, label, quotes, alternative, or Markdown fence. If the text needs no change, return it as it is.";

/// Why a rewrite has no text.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RewriteFailure {
    /// A managed policy turned OpenCode processing off.
    Managed,
    NotRunning,
    /// An OpenCode build too old to answer.
    Outdated,
    TimedOut,
    Cancelled,
    /// OpenCode answered with an error or no text.
    Failed,
}

/// `text` rewritten by OpenCode through the running service. Hex never
/// starts or searches for OpenCode: without a registered service this is
/// [`RewriteFailure::NotRunning`].
pub(crate) fn rewrite_typed_text(
    text: &str,
    settings: &PostProcessingSettings,
    cancelled: &AtomicBool,
) -> Result<String, RewriteFailure> {
    if crate::policy::Policy::current().locks(crate::policy::Lock::OpenCodeProcessing) {
        return Err(RewriteFailure::Managed);
    }
    let profile = Profile::configured("Global", ReplacementSet::default(), Vec::new(), settings);
    let deadline = profile.deadline.min(MAX_DEADLINE);
    let started = Instant::now();
    let failure = || {
        if cancelled.load(Ordering::Acquire) {
            RewriteFailure::Cancelled
        } else if started.elapsed() >= deadline {
            RewriteFailure::TimedOut
        } else {
            RewriteFailure::Failed
        }
    };
    let service =
        opencode_service::find_cancellable(deadline, cancelled).map_err(|missing| {
            match (failure(), missing) {
                (RewriteFailure::Failed, Missing::NotRunning) => RewriteFailure::NotRunning,
                (RewriteFailure::Failed, Missing::Outdated) => RewriteFailure::Outdated,
                (other, _) => other,
            }
        })?;
    let generated = generate_on(
        &service,
        &prompt(text, &profile),
        profile.model.as_ref(),
        started,
        deadline,
        cancelled,
    );
    // The error and the text stay out of the log: either may carry what was typed.
    let latency_ms = started.elapsed().as_millis() as u64;
    match generated {
        Ok(generated) if !generated.text.trim().is_empty() => {
            tracing::info!(latency_ms, "phone rewrite completed");
            Ok(generated.text.trim().to_owned())
        }
        Ok(_) => {
            tracing::warn!(latency_ms, "phone rewrite came back empty");
            Err(RewriteFailure::Failed)
        }
        Err(_) => {
            let failure = failure();
            tracing::warn!(latency_ms, ?failure, "phone rewrite failed");
            Err(failure)
        }
    }
}

/// The request: the rules, Global's own instructions while its rewrite is on,
/// and the text.
fn prompt(text: &str, profile: &Profile) -> String {
    let own = if profile.ai_enabled && !profile.prompt.trim().is_empty() {
        format!(
            "The person's own instructions (follow them where they fit typed text):\n{}\n\n",
            profile.prompt.trim()
        )
    } else {
        String::new()
    };
    format!("{INSTRUCTIONS}\n\n{own}Text:\n<text>\n{text}\n</text>")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(enabled: bool, prompt: &str) -> Profile {
        let settings = PostProcessingSettings {
            enabled,
            prompt: prompt.into(),
            model: Some("anthropic/some-model".into()),
            variant: None,
            deadline_seconds: 30,
        };
        Profile::configured_under(
            "Global",
            ReplacementSet::default(),
            Vec::new(),
            &settings,
            crate::policy::Policy::default(),
        )
    }

    /// Global's instructions join only while its rewrite is on; its model
    /// always applies.
    #[test]
    fn asks_with_globals_instructions_only_while_its_rewrite_is_on() {
        let on = profile(true, "Use British spelling.");
        let request = prompt("teh colour", &on);
        assert!(request.starts_with(INSTRUCTIONS));
        assert!(request.contains("Use British spelling."));
        assert!(request.ends_with("<text>\nteh colour\n</text>"));
        assert_eq!(on.model.as_ref().unwrap().id, "some-model");

        let off = profile(false, "Use British spelling.");
        assert!(!prompt("teh colour", &off).contains("British"));
    }
}
