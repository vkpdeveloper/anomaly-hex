//! A paired phone keyboard's Rewrite (`POST /phone/v1/rewrite`): text from
//! the phone's text box, rewritten by OpenCode in the tone asked for, with
//! Global mode's model, variant, and deadline
//! (`dictation::processor::rewrite_typed_text`); and its Reply
//! (`POST /phone/v1/replies`): replies drafted to the chat on the phone's
//! screen (`dictation::processor::draft_replies`). Only on the phone's tap;
//! the text is never logged or kept. A failure answers a code and never the
//! text it was sent, so the phone leaves its field alone.

use std::net::TcpStream;
use std::sync::RwLock;
use std::sync::atomic::{AtomicBool, Ordering};

use serde::Deserialize;

use crate::app_settings::AppSettings;
use crate::dictation::processor::{
    ChatLine, PostProcessingSettings, RewriteFailure, RewriteStyle, draft_replies,
    rewrite_typed_text,
};
use crate::local_api::http::{HttpRequest, HttpResponse, body_read_response, read_body_bounded};

/// Far more than a phone's text box sends: the selection, or what the
/// keyboard reads around the caret.
const MAX_REWRITE_BYTES: usize = 64 * 1024;

/// Global mode's rewrite settings, recorded by `phone::apply`.
static SETTINGS: RwLock<Option<PostProcessingSettings>> = RwLock::new(None);
/// One rewrite at a time, so a slow model never holds every phone worker.
static BUSY: AtomicBool = AtomicBool::new(false);

pub(super) fn apply(settings: &AppSettings) {
    *SETTINGS.write().unwrap_or_else(|error| error.into_inner()) = Some(
        settings
            .dictation_processing
            .default_mode
            .post_processing
            .clone(),
    );
}

/// A chat is cut to its newest lines this many.
const MAX_CHAT_LINES: usize = 40;

#[derive(Deserialize)]
struct RewriteRequest {
    text: String,
    /// Fix when left out, as every phone before tones sent.
    #[serde(default)]
    style: RewriteStyle,
}

#[derive(Deserialize)]
struct RepliesRequest {
    lines: Vec<ChatLine>,
    #[serde(default)]
    draft: String,
}

/// What a route asks OpenCode, with a request already read and checked.
enum Ask {
    Rewrite(RewriteRequest),
    Replies(RepliesRequest),
}

type Answer<'a> = &'a dyn Fn(Ask, &AtomicBool) -> Result<serde_json::Value, RewriteFailure>;

fn settings() -> PostProcessingSettings {
    SETTINGS
        .read()
        .unwrap_or_else(|error| error.into_inner())
        .clone()
        .unwrap_or_default()
}

fn answer(ask: Ask, cancelled: &AtomicBool) -> Result<serde_json::Value, RewriteFailure> {
    match ask {
        Ask::Rewrite(RewriteRequest { text, style }) => {
            rewrite_typed_text(&text, style, &settings(), cancelled)
                .map(|text| serde_json::json!({ "text": text }))
        }
        Ask::Replies(RepliesRequest { lines, draft }) => {
            draft_replies(&lines, &draft, &settings(), cancelled)
                .map(|replies| serde_json::json!({ "replies": replies }))
        }
    }
}

pub(super) fn handle(
    request: HttpRequest,
    stream: &mut TcpStream,
    stop: &AtomicBool,
) -> HttpResponse {
    respond(request, stream, stop, false, &answer)
}

pub(super) fn handle_replies(
    request: HttpRequest,
    stream: &mut TcpStream,
    stop: &AtomicBool,
) -> HttpResponse {
    respond(request, stream, stop, true, &answer)
}

/// Reads the body as a rewrite, or as a chat to reply to when `replies`.
fn read_ask(body: &[u8], replies: bool) -> Result<Ask, HttpResponse> {
    let invalid = || HttpResponse::code(400, "invalid-request");
    if replies {
        let Ok(mut request) = serde_json::from_slice::<RepliesRequest>(body) else {
            return Err(invalid());
        };
        request.lines.retain(|line| !line.text.trim().is_empty());
        if request.lines.is_empty() {
            return Err(HttpResponse::code(400, "empty-text"));
        }
        let extra = request.lines.len().saturating_sub(MAX_CHAT_LINES);
        request.lines.drain(..extra);
        return Ok(Ask::Replies(request));
    }
    let Ok(request) = serde_json::from_slice::<RewriteRequest>(body) else {
        return Err(invalid());
    };
    if request.text.trim().is_empty() {
        return Err(HttpResponse::code(400, "empty-text"));
    }
    Ok(Ask::Rewrite(request))
}

fn respond(
    request: HttpRequest,
    stream: &mut TcpStream,
    stop: &AtomicBool,
    replies: bool,
    answer: Answer,
) -> HttpResponse {
    if !request.has_content_type("application/json") {
        return HttpResponse::code(415, "unsupported-media-type");
    }
    let Some(content_length) = request.content_length else {
        return HttpResponse::code(411, "length-required");
    };
    let mut body = request.body_prefix;
    if let Err(error) = read_body_bounded(
        stream,
        content_length,
        &mut body,
        stop,
        MAX_REWRITE_BYTES,
        crate::local_api::UPLOAD_DEADLINE,
    ) {
        return body_read_response(error);
    }
    let ask = match read_ask(&body, replies) {
        Ok(ask) => ask,
        Err(refused) => return refused,
    };
    let Some(_busy) = Busy::claim() else {
        return HttpResponse::code(429, "busy");
    };
    match answer(ask, stop) {
        Ok(json) => HttpResponse::json(200, &json),
        Err(RewriteFailure::Managed) => HttpResponse::code(403, "opencode-managed"),
        Err(RewriteFailure::NotRunning) => HttpResponse::code(503, "opencode-not-running"),
        Err(RewriteFailure::Outdated) => HttpResponse::code(503, "opencode-outdated"),
        Err(RewriteFailure::TimedOut) => HttpResponse::code(504, "rewrite-timeout"),
        Err(RewriteFailure::Cancelled) => HttpResponse::code(503, "service-gone"),
        Err(RewriteFailure::Failed) => HttpResponse::code(500, "rewrite-failed"),
    }
}

/// The one rewrite slot, freed when dropped.
struct Busy;

impl Busy {
    fn claim() -> Option<Self> {
        BUSY.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .ok()
            .map(|_| Self)
    }
}

impl Drop for Busy {
    fn drop(&mut self) {
        BUSY.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::Mutex;

    use super::*;
    use crate::local_api::http::{read_request, write_response};

    /// One rewrite through the route with `rewrite` in OpenCode's place.
    fn send(
        content_type: &str,
        body: &[u8],
        rewrite: &dyn Fn(&str, &AtomicBool) -> Result<String, RewriteFailure>,
    ) -> (u16, serde_json::Value) {
        let answer = |ask: Ask, stop: &AtomicBool| match ask {
            Ask::Rewrite(request) => {
                rewrite(&request.text, stop).map(|text| serde_json::json!({ "text": text }))
            }
            Ask::Replies(_) => panic!("a rewrite never asks for replies"),
        };
        post(false, content_type, body, &answer)
    }

    /// One request through the rewrite route, or the replies one when
    /// `replies`, with `answer` in OpenCode's place.
    fn post(
        replies: bool,
        content_type: &str,
        body: &[u8],
        answer: Answer,
    ) -> (u16, serde_json::Value) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let path = if replies { "replies" } else { "rewrite" };
        let head = format!(
            "POST /phone/v1/{path} HTTP/1.1\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        let body = body.to_vec();
        let client = std::thread::spawn(move || {
            let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
            stream.write_all(head.as_bytes()).unwrap();
            // A refused body may stop being read part way.
            let _ = stream.write_all(&body);
            let mut reply = String::new();
            stream.read_to_string(&mut reply).unwrap();
            reply
        });
        let (mut stream, _) = listener.accept().unwrap();
        let stop = AtomicBool::new(false);
        let request = read_request(&mut stream, &stop).unwrap();
        let response = respond(request, &mut stream, &stop, replies, answer);
        write_response(&mut stream, response).unwrap();
        // Drain what was left unread, so closing never resets the reply away.
        // Best effort: the client may already have read the reply and closed.
        if stream.shutdown(std::net::Shutdown::Write).is_ok()
            && stream
                .set_read_timeout(Some(std::time::Duration::from_secs(1)))
                .is_ok()
        {
            let _ = std::io::copy(&mut stream, &mut std::io::sink());
        }
        let reply = client.join().unwrap();
        let status = reply[9..12].parse().unwrap();
        let json = serde_json::from_str(reply.split("\r\n\r\n").nth(1).unwrap()).unwrap();
        (status, json)
    }

    /// The rewrite answers the new text; every failure answers a code the
    /// phone names, never the text it was sent. Sequential, since the busy
    /// slot is shared.
    #[test]
    fn answers_the_rewrite_or_why_there_is_none() {
        let seen = Mutex::new(Vec::new());
        let fixed = |text: &str, _: &AtomicBool| {
            seen.lock().unwrap().push(text.to_owned());
            Ok("I'm on my way.".to_owned())
        };
        let (status, json) = send(
            "application/json; charset=utf-8",
            br#"{"text":"im on my way"}"#,
            &fixed,
        );
        assert_eq!(
            (status, json["text"].as_str()),
            (200, Some("I'm on my way."))
        );
        assert_eq!(*seen.lock().unwrap(), ["im on my way"]);

        for (failure, status, code) in [
            (RewriteFailure::NotRunning, 503, "opencode-not-running"),
            (RewriteFailure::TimedOut, 504, "rewrite-timeout"),
            (RewriteFailure::Failed, 500, "rewrite-failed"),
            (RewriteFailure::Managed, 403, "opencode-managed"),
        ] {
            let failing = move |_: &str, _: &AtomicBool| Err(failure);
            let (got, json) = send("application/json", br#"{"text":"hi"}"#, &failing);
            assert_eq!((got, json["code"].as_str()), (status, Some(code)));
            assert!(json.get("text").is_none());
        }

        let unreached = |_: &str, _: &AtomicBool| -> Result<String, RewriteFailure> {
            panic!("a refused request never reaches OpenCode")
        };
        assert_eq!(send("application/json", b"{\"text\":", &unreached).0, 400);
        assert_eq!(
            send("application/json", br#"{"text":"  "}"#, &unreached).0,
            400
        );
        assert_eq!(send("text/plain", br#"{"text":"hi"}"#, &unreached).0, 415);
        let long =
            serde_json::to_vec(&serde_json::json!({ "text": "a".repeat(MAX_REWRITE_BYTES) }))
                .unwrap();
        assert_eq!(send("application/json", &long, &unreached).0, 413);

        let held = Busy::claim().unwrap();
        let (status, json) = send("application/json", br#"{"text":"hi"}"#, &unreached);
        assert_eq!((status, json["code"].as_str()), (429, Some("busy")));
        drop(held);

        // A tone the phone names reaches OpenCode; one this computer doesn't
        // know is refused rather than rewritten some other way.
        let styles = Mutex::new(Vec::new());
        let styled = |ask: Ask, _: &AtomicBool| match ask {
            Ask::Rewrite(request) => {
                styles.lock().unwrap().push(request.style);
                Ok(serde_json::json!({ "text": "ok" }))
            }
            Ask::Replies(_) => unreachable!(),
        };
        let body = br#"{"text":"hi","style":"shorter"}"#;
        assert_eq!(post(false, "application/json", body, &styled).0, 200);
        assert_eq!(
            post(false, "application/json", br#"{"text":"hi"}"#, &styled).0,
            200
        );
        let body = br#"{"text":"hi","style":"sarcastic"}"#;
        assert_eq!(post(false, "application/json", body, &styled).0, 400);
        assert_eq!(
            *styles.lock().unwrap(),
            [RewriteStyle::Shorter, RewriteStyle::Fix]
        );

        // Replies get the chat's newest lines, blank ones left out.
        let seen = Mutex::new(Vec::new());
        let replying = |ask: Ask, _: &AtomicBool| match ask {
            Ask::Replies(request) => {
                seen.lock().unwrap().push((request.lines, request.draft));
                Ok(serde_json::json!({ "replies": ["Yes!", "Can't today"] }))
            }
            Ask::Rewrite(_) => unreachable!(),
        };
        let mut lines: Vec<_> = (0..45)
            .map(|n| serde_json::json!({ "mine": n % 2 == 0, "text": format!("line {n}") }))
            .collect();
        lines.push(serde_json::json!({ "mine": false, "text": "  " }));
        let body =
            serde_json::to_vec(&serde_json::json!({ "lines": lines, "draft": "ye" })).unwrap();
        let (status, json) = post(true, "application/json", &body, &replying);
        assert_eq!(status, 200);
        assert_eq!(json["replies"], serde_json::json!(["Yes!", "Can't today"]));
        let (lines, draft) = seen.lock().unwrap().pop().unwrap();
        assert_eq!(lines.len(), MAX_CHAT_LINES);
        assert_eq!(lines[0].text, "line 5");
        assert!(!lines[0].mine);
        assert_eq!(draft, "ye");
        let blank = br#"{"lines":[{"mine":false,"text":" "}]}"#;
        assert_eq!(post(true, "application/json", blank, &replying).0, 400);
        assert_eq!(
            post(true, "application/json", br#"{"text":"hi"}"#, &replying).0,
            400
        );
    }
}
