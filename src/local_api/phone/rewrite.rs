//! A paired phone keyboard's Rewrite (`POST /phone/v1/rewrite`): text from
//! the phone's text box, rewritten by OpenCode with Global mode's model,
//! variant, and deadline (`dictation::processor::rewrite_typed_text`). Only
//! on the phone's tap; the text is never logged or kept. A failure answers a
//! code and never the text it was sent, so the phone leaves its field alone.

use std::net::TcpStream;
use std::sync::RwLock;
use std::sync::atomic::{AtomicBool, Ordering};

use serde::Deserialize;

use crate::app_settings::AppSettings;
use crate::dictation::processor::{PostProcessingSettings, RewriteFailure, rewrite_typed_text};
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

#[derive(Deserialize)]
struct RewriteRequest {
    text: String,
}

type Rewrite<'a> = &'a dyn Fn(&str, &AtomicBool) -> Result<String, RewriteFailure>;

pub(super) fn handle(
    request: HttpRequest,
    stream: &mut TcpStream,
    stop: &AtomicBool,
) -> HttpResponse {
    respond(request, stream, stop, &|text, cancelled| {
        let settings = SETTINGS
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .clone()
            .unwrap_or_default();
        rewrite_typed_text(text, &settings, cancelled)
    })
}

fn respond(
    request: HttpRequest,
    stream: &mut TcpStream,
    stop: &AtomicBool,
    rewrite: Rewrite,
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
    let Ok(RewriteRequest { text }) = serde_json::from_slice(&body) else {
        return HttpResponse::code(400, "invalid-request");
    };
    if text.trim().is_empty() {
        return HttpResponse::code(400, "empty-text");
    }
    let Some(_busy) = Busy::claim() else {
        return HttpResponse::code(429, "busy");
    };
    match rewrite(&text, stop) {
        Ok(text) => HttpResponse::json(200, &serde_json::json!({ "text": text })),
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

    /// One request through the route with `rewrite` in OpenCode's place.
    fn send(content_type: &str, body: &[u8], rewrite: Rewrite) -> (u16, serde_json::Value) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let head = format!(
            "POST /phone/v1/rewrite HTTP/1.1\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n",
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
        let response = respond(request, &mut stream, &stop, rewrite);
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
    }
}
