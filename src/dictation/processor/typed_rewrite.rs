//! Rewriting text someone already typed, for a paired phone keyboard's
//! Rewrite and its tones (`POST /phone/v1/rewrite`), and drafting replies to
//! the chat on its screen (`POST /phone/v1/replies`): one OpenCode request
//! each, with Global mode's model, variant, and deadline. Unlike a
//! dictation's rewrite, a failure never falls back to the text sent: the
//! phone keeps its own text and says why.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use super::{PostProcessingSettings, Profile, generate_on};
use crate::desktop::opencode_service::{self, Missing};
use crate::dictation::text_replacements::ReplacementSet;

/// The phone waits for the answer, so a longer Global deadline is cut to this.
pub(crate) const MAX_DEADLINE: Duration = Duration::from_secs(30);

const INSTRUCTIONS: &str = "You rewrite text a person typed. Fix grammar, spelling, punctuation, and clarity. Keep the meaning, facts, numbers, names, tone, and language: never translate, add, or drop content. Keep line breaks and lists. Return only the rewritten text, with no explanation, label, quotes, alternative, or Markdown fence. If the text needs no change, return it as it is.";

/// The rules every tone shares: what [`INSTRUCTIONS`] keeps, minus the part
/// the tone changes.
const TONE_RULES: &str = "Keep the meaning, facts, numbers, and names, and fix grammar and spelling as you go. Keep line breaks and lists. Return only the rewritten text, with no explanation, label, quotes, alternative, or Markdown fence.";

const REPLY_INSTRUCTIONS: &str = "You draft replies a person could send next in a chat. The conversation is below, oldest first: lines from \"me\" are the person's own, lines from \"them\" are the other side's. Write three different short replies to the latest messages, as the person would write them: their language and script (Hindi in Latin letters stays in Latin letters), their tone, length, capitals, and punctuation as their own lines show. Answer what was asked; never make up facts, times, or promises the conversation doesn't support, and offer a choice instead (yes, no, or later) when the answer isn't known. If the person started typing a draft, every reply builds on it. Return only a JSON array of three strings, with no explanation or Markdown fence.";

/// How a [`rewrite_typed_text`] changes the text.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Style {
    /// Grammar, spelling, and clarity, keeping the tone and language.
    #[default]
    Fix,
    Shorter,
    Friendlier,
    Formal,
    /// Translated into English.
    English,
}

impl Style {
    fn instructions(self) -> String {
        let change = match self {
            Self::Fix => return INSTRUCTIONS.to_owned(),
            Self::Shorter => {
                "You rewrite text a person typed to be shorter: say the same thing in fewer words, dropping filler and repetition but no point. Keep the tone and language: never translate."
            }
            Self::Friendlier => {
                "You rewrite text a person typed to sound warmer and friendlier, as said to someone they like, without gushing or adding emoji. Keep the language: never translate."
            }
            Self::Formal => {
                "You rewrite text a person typed to sound formal and professional, as written to a client or a manager, without making it longer than it needs to be. Keep the language: never translate."
            }
            Self::English => {
                "You translate text a person typed into natural English, as they would say it. Text that mixes Hindi in Latin letters (Hinglish) or any other language with English becomes all English. Keep the tone."
            }
        };
        format!("{change} {TONE_RULES}")
    }
}

/// One line of a chat, oldest first, for [`draft_replies`].
#[derive(Clone, Debug, Eq, PartialEq, serde::Deserialize)]
pub(crate) struct ChatLine {
    /// `true` for the person's own line, `false` for the other side's.
    pub(crate) mine: bool,
    pub(crate) text: String,
}

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

/// `text` rewritten by OpenCode in `style` through the running service. Hex
/// never starts or searches for OpenCode: without a registered service this
/// is [`RewriteFailure::NotRunning`].
pub(crate) fn rewrite_typed_text(
    text: &str,
    style: Style,
    settings: &PostProcessingSettings,
    cancelled: &AtomicBool,
) -> Result<String, RewriteFailure> {
    generate_typed("rewrite", settings, cancelled, |profile| {
        prompt(text, style, profile)
    })
}

/// Up to three replies the person could send next in the chat `lines`
/// (oldest first), building on what they started typing (`draft`).
pub(crate) fn draft_replies(
    lines: &[ChatLine],
    draft: &str,
    settings: &PostProcessingSettings,
    cancelled: &AtomicBool,
) -> Result<Vec<String>, RewriteFailure> {
    let answer = generate_typed("replies", settings, cancelled, |profile| {
        reply_prompt(lines, draft, profile)
    })?;
    let replies = parse_replies(&answer);
    if replies.is_empty() {
        tracing::warn!("phone replies came back unreadable");
        return Err(RewriteFailure::Failed);
    }
    Ok(replies)
}

/// One OpenCode request for the phone, named `what` in the log, with the
/// prompt `ask` builds from Global's profile.
fn generate_typed(
    what: &'static str,
    settings: &PostProcessingSettings,
    cancelled: &AtomicBool,
    ask: impl FnOnce(&Profile) -> String,
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
        &ask(&profile),
        profile.model.as_ref(),
        started,
        deadline,
        cancelled,
    );
    // The error and the text stay out of the log: either may carry what was typed.
    let latency_ms = started.elapsed().as_millis() as u64;
    match generated {
        Ok(generated) if !generated.text.trim().is_empty() => {
            tracing::info!(latency_ms, what, "phone request completed");
            Ok(generated.text.trim().to_owned())
        }
        Ok(_) => {
            tracing::warn!(latency_ms, what, "phone request came back empty");
            Err(RewriteFailure::Failed)
        }
        Err(_) => {
            let failure = failure();
            tracing::warn!(latency_ms, what, ?failure, "phone request failed");
            Err(failure)
        }
    }
}

/// Global's own instructions while its rewrite is on, as a paragraph.
fn own_instructions(profile: &Profile) -> String {
    if profile.ai_enabled && !profile.prompt.trim().is_empty() {
        format!(
            "The person's own instructions (follow them where they fit typed text):\n{}\n\n",
            profile.prompt.trim()
        )
    } else {
        String::new()
    }
}

/// The request: the style's rules, Global's own instructions while its
/// rewrite is on, and the text.
fn prompt(text: &str, style: Style, profile: &Profile) -> String {
    let own = own_instructions(profile);
    format!(
        "{}\n\n{own}Text:\n<text>\n{text}\n</text>",
        style.instructions()
    )
}

fn reply_prompt(lines: &[ChatLine], draft: &str, profile: &Profile) -> String {
    let own = own_instructions(profile);
    let chat = lines
        .iter()
        .map(|line| {
            let from = if line.mine { "me" } else { "them" };
            format!("{from}: {}", line.text.trim())
        })
        .collect::<Vec<_>>()
        .join("\n");
    let draft = draft.trim();
    let draft = if draft.is_empty() {
        String::new()
    } else {
        format!("\n\nThe person's draft so far:\n<draft>\n{draft}\n</draft>")
    };
    format!("{REPLY_INSTRUCTIONS}\n\n{own}Conversation:\n<chat>\n{chat}\n</chat>{draft}")
}

/// The replies in a model's answer: a JSON array of strings, also when it
/// came inside a Markdown fence or after a line of chatter. Each is trimmed,
/// empty and repeated ones are dropped, and at most three are kept.
fn parse_replies(answer: &str) -> Vec<String> {
    let (Some(start), Some(end)) = (answer.find('['), answer.rfind(']')) else {
        return Vec::new();
    };
    if end < start {
        return Vec::new();
    }
    let Ok(replies) = serde_json::from_str::<Vec<String>>(&answer[start..=end]) else {
        return Vec::new();
    };
    let mut kept: Vec<String> = Vec::new();
    for reply in replies {
        let reply = reply.trim().to_owned();
        if !reply.is_empty() && !kept.contains(&reply) && kept.len() < 3 {
            kept.push(reply);
        }
    }
    kept
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
        let request = prompt("teh colour", Style::Fix, &on);
        assert!(request.starts_with(INSTRUCTIONS));
        assert!(request.contains("Use British spelling."));
        assert!(request.ends_with("<text>\nteh colour\n</text>"));
        assert_eq!(on.model.as_ref().unwrap().id, "some-model");

        let off = profile(false, "Use British spelling.");
        assert!(!prompt("teh colour", Style::Fix, &off).contains("British"));
    }

    /// Each tone asks for its own change; only English may translate.
    #[test]
    fn each_tone_asks_for_its_change() {
        let off = profile(false, "");
        let shorter = prompt("hey so basically i cant come", Style::Shorter, &off);
        assert!(shorter.starts_with("You rewrite text a person typed to be shorter"));
        assert!(shorter.contains("never translate"));
        assert!(shorter.ends_with("<text>\nhey so basically i cant come\n</text>"));
        assert!(prompt("x", Style::Formal, &off).contains("formal"));
        assert!(prompt("x", Style::Friendlier, &off).contains("friendlier"));
        let english = prompt("kal milte hain", Style::English, &off);
        assert!(english.contains("into natural English"));
        assert!(!english.contains("never translate"));
    }

    /// The chat goes oldest first with who said each line, and a draft only
    /// when there is one.
    #[test]
    fn asks_for_replies_to_the_chat() {
        let off = profile(false, "");
        let lines = [
            ChatLine {
                mine: false,
                text: "are you coming tonight?".into(),
            },
            ChatLine {
                mine: true,
                text: " maybe ".into(),
            },
        ];
        let request = reply_prompt(&lines, "", &off);
        assert!(request.starts_with(REPLY_INSTRUCTIONS));
        assert!(request.ends_with("<chat>\nthem: are you coming tonight?\nme: maybe\n</chat>"));
        let drafted = reply_prompt(&lines, "yes but ", &off);
        assert!(drafted.ends_with("<draft>\nyes but\n</draft>"));
    }

    #[test]
    fn reads_replies_from_what_the_model_answered() {
        assert_eq!(
            parse_replies(r#"["Yes!", "Can't, sorry", "Maybe later"]"#),
            ["Yes!", "Can't, sorry", "Maybe later"]
        );
        assert_eq!(
            parse_replies(
                "Here you go:\n```json\n[\" Sure \", \"Sure\", \"\", \"No\", \"a\", \"b\"]\n```"
            ),
            ["Sure", "No", "a"]
        );
        assert!(parse_replies("Sure, see you").is_empty());
        assert!(parse_replies("] oops [").is_empty());
        assert!(parse_replies("[1, 2]").is_empty());
    }
}
