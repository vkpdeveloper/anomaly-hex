//! The `POST /transcriptions` body: reading the uploaded WAV, waiting for the
//! service's transcription, and answering with the raw fields, the model's
//! native timings, and (when asked) Global mode's processed text.
//!
//! The query, the lab gates, and the model selection stay in
//! [`super::transcription_action`]; this module owns what happens once the
//! request is admitted.

use std::io::Cursor;
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::transcription::transcript::TimestampLevel;

use super::http::{BodyReadError, HttpResponse};
use super::{
    HttpContext, MAX_AUDIO_BYTES, TRANSCRIPTION_POLL_INTERVAL, UPLOAD_DEADLINE, client_aborted,
    processing, read_body_bounded, timestamps,
};

/// Everything `/transcriptions` admitted for one request before its audio
/// arrives: the model selection, the audio-memory slot, Global mode's
/// processing slot, and the timing level the lab allowed.
pub(super) struct Admitted {
    pub selection: crate::transcription::models::TranscriptionSelection,
    pub admission: crate::transcription::service::AudioAdmission,
    pub guard: Option<processing::Guard>,
    pub timings: TimestampLevel,
}

pub(super) fn transcribe_audio(
    stream: &mut TcpStream,
    admitted: Admitted,
    content_length: usize,
    body: Vec<u8>,
    context: &HttpContext,
) -> Option<HttpResponse> {
    let Admitted {
        selection,
        admission,
        guard,
        timings,
    } = admitted;
    let language = selection.language.clone();
    let upload = Upload {
        selection,
        admission,
        timings,
    };
    let heard = match transcribe_upload(
        stream,
        upload,
        content_length,
        body,
        &context.transcription,
        &context.shutdown,
    ) {
        Ok(heard) => heard,
        Err(response) => return response,
    };
    let mut response = serde_json::json!({
        "transcript": &heard.transcript.text,
        "durationMs": heard.duration_ms,
    });
    timestamps::attach(&mut response, timings, &heard.transcript, heard.duration_ms);
    processing::attach(
        &mut response,
        guard.as_ref(),
        &heard.transcript.text,
        &language,
        &context.shutdown,
    );
    Some(HttpResponse::json(200, &response))
}

/// What an admitted upload is transcribed with.
pub(super) struct Upload {
    pub selection: crate::transcription::models::TranscriptionSelection,
    pub admission: crate::transcription::service::AudioAdmission,
    pub timings: TimestampLevel,
}

/// A finished upload's transcript as the model heard it.
pub(super) struct Heard {
    pub transcript: crate::transcription::transcript::Transcript,
    pub duration_ms: u64,
}

/// Reads, decodes, and transcribes an uploaded WAV (`/transcriptions` and
/// the phone relay). `Err` carries the reply for a failure, or `None` when
/// there is no one left to answer.
pub(super) fn transcribe_upload(
    stream: &mut TcpStream,
    upload: Upload,
    content_length: usize,
    mut body: Vec<u8>,
    transcription: &crate::transcription::service::TranscriptionServiceHandle,
    shutdown: &AtomicBool,
) -> Result<Heard, Option<HttpResponse>> {
    if let Err(error) = read_body_bounded(
        stream,
        content_length,
        &mut body,
        shutdown,
        MAX_AUDIO_BYTES,
        UPLOAD_DEADLINE,
    ) {
        return Err(Some(match error {
            BodyReadError::ResourceExhausted => HttpResponse::code(413, "resource-exhausted"),
            BodyReadError::Invalid => HttpResponse::code(400, "invalid-audio"),
            BodyReadError::Io(error) => {
                tracing::debug!(%error, "audio upload stopped before completion");
                HttpResponse::code(400, "invalid-audio")
            }
            BodyReadError::Deadline => HttpResponse::code(408, "upload-timeout"),
            BodyReadError::Shutdown => return Err(None),
        }));
    }
    let clip = match crate::transcription::service::AudioClip::decode_wav(Cursor::new(body)) {
        Ok(clip) => clip,
        Err(crate::transcription::service::AudioError::ResourceExhausted) => {
            return Err(Some(HttpResponse::code(413, "resource-exhausted")));
        }
        Err(crate::transcription::service::AudioError::Unsupported) => {
            return Err(Some(HttpResponse::code(415, "unsupported-audio")));
        }
        Err(crate::transcription::service::AudioError::Invalid) => {
            return Err(Some(HttpResponse::code(400, "invalid-audio")));
        }
    };
    let duration_ms = clip.duration_ms;
    let Upload {
        selection,
        admission,
        timings,
    } = upload;
    let job = match transcription.transcribe(selection, clip, admission, timings) {
        Ok(job) => job,
        Err(error) => return Err(Some(transcription_error(error))),
    };
    loop {
        match job.wait_timeout(TRANSCRIPTION_POLL_INTERVAL) {
            Ok(Some(Ok(transcript))) => {
                return Ok(Heard {
                    transcript,
                    duration_ms,
                });
            }
            Ok(Some(Err(error))) | Err(error) => return Err(Some(transcription_error(error))),
            Ok(None) => {
                if shutdown.load(Ordering::Acquire) || client_aborted(stream) {
                    job.cancel();
                    return Err(None);
                }
            }
        }
    }
}

pub(super) fn transcription_error(
    error: crate::transcription::service::TranscriptionServiceError,
) -> HttpResponse {
    match error {
        crate::transcription::service::TranscriptionServiceError::QueueFull => {
            HttpResponse::code(429, "queue-full")
        }
        crate::transcription::service::TranscriptionServiceError::Unavailable => {
            HttpResponse::code(503, "service-gone")
        }
        crate::transcription::service::TranscriptionServiceError::Cancelled => {
            HttpResponse::code(409, "cancelled")
        }
        crate::transcription::service::TranscriptionServiceError::Model(error) => {
            tracing::error!(%error, "prepared transcription model could not load");
            HttpResponse::code(500, "model-load-failed")
        }
        crate::transcription::service::TranscriptionServiceError::Inference(error) => {
            tracing::error!(%error, "local transcription failed");
            HttpResponse::code(500, "transcription-failed")
        }
        crate::transcription::service::TranscriptionServiceError::Timestamps => {
            HttpResponse::code(422, "unsupported-timestamps")
        }
    }
}
