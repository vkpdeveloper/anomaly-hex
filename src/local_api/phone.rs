//! Labs › Phone dictation: Hex for Android records on the phone, sends the
//! clip here, and gets back the transcript this computer's dictation model
//! heard (`docs/phone-dictation.md`).
//!
//! While the feature is on, one listener serves the phone routes on every
//! IPv4 interface at one port and answers only peers on a private network:
//! Tailscale's 100.64.0.0/10, the RFC 1918 LAN ranges, link-local, and
//! loopback. It advertises itself on the LAN as `_hex-phone._tcp` (DNS-SD)
//! and tells a paired phone every address it has, Tailscale first. Every
//! request carries the pairing code, kept owner-only with the automatic
//! port in `phone-dictation.json` in the data folder.
//!
//! Only the app process serves: each OS's app holds a [`PhoneDictation`],
//! and settings loads and saves hand it the wanted state ([`apply`]), so a
//! CLI process that saves settings never opens a port.

use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, TrySendError};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use color_eyre::eyre::{Result, WrapErr, eyre};
use serde::{Deserialize, Serialize};

use super::http::{HttpRequest, HttpResponse, RequestReadError, read_request, write_response};
use crate::app_settings::AppSettings;
use crate::transcription::models::TranscriptionSelection;
use crate::transcription::service::{TranscriptionService, TranscriptionServiceHandle};

/// The DNS-SD service type the Android app browses for.
pub const SERVICE_TYPE: &str = "_hex-phone._tcp.local.";
/// The port an automatic setup tries first: unassigned, and far from the
/// usual development and media ports.
pub const DEFAULT_PORT: u16 = 47213;
/// Automatic ports tried when [`DEFAULT_PORT`] is taken, in this range.
const FALLBACK_PORTS: std::ops::Range<u16> = 47300..48900;
/// Ports below this need privileges, so a custom port starts here.
pub const MIN_PORT: u16 = 1024;
/// About eight minutes of 16 kHz mono 16-bit audio.
const MAX_PHONE_AUDIO_BYTES: usize = 16 * 1024 * 1024;
const WORKERS: usize = 2;
const QUEUE_CAPACITY: usize = 4;
/// A wrong code waits this long, so guessing one is slow.
const AUTH_FAILURE_DELAY: Duration = Duration::from_millis(400);
/// How often the supervisor rereads the network addresses.
const REFRESH: Duration = Duration::from_secs(5);
/// Pause after a failed accept, such as running out of descriptors.
const ACCEPT_INTERVAL: Duration = Duration::from_millis(50);
const CODE_ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_LENGTH: usize = 12;

/// What the settings ask for; `None` while the feature is off.
#[derive(Clone, Debug, PartialEq)]
struct Wanted {
    /// 0 picks one automatically.
    port: u16,
    selection: TranscriptionSelection,
}

struct Desired {
    wanted: Option<Wanted>,
    generation: u64,
}

static DESIRED: Mutex<Desired> = Mutex::new(Desired {
    wanted: None,
    generation: 0,
});
static CHANGED: Condvar = Condvar::new();
static STATUS: RwLock<Status> = RwLock::new(Status::Off);
static STATUS_GENERATION: AtomicU64 = AtomicU64::new(0);

/// What Settings › Labs shows under Phone dictation.
#[derive(Clone, Debug, Default, PartialEq)]
pub enum Status {
    #[default]
    Off,
    Listening(Listening),
    Failed(String),
}

#[derive(Clone, Debug, PartialEq)]
pub struct Listening {
    pub port: u16,
    pub code: String,
    pub routes: Vec<Route>,
}

/// One address a phone can reach this computer at.
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
pub struct Route {
    pub kind: RouteKind,
    pub host: Ipv4Addr,
}

/// Tailscale sorts first: a phone tries its routes in this order.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum RouteKind {
    Tailscale,
    Lan,
}

/// The owner-only state beside settings: the pairing code, and the port an
/// automatic setup chose so it stays the same across launches.
#[derive(Debug, Default, Deserialize, Serialize)]
struct Saved {
    code: String,
    #[serde(default)]
    port: u16,
}

/// Records what the loaded or saved settings want. The app's
/// [`PhoneDictation`] acts on it; any other process ignores it.
pub(crate) fn apply(settings: &AppSettings) {
    let feature = crate::labs::Feature::PhoneDictation;
    let wanted = (feature.on(settings) && feature.shipped()).then(|| Wanted {
        port: settings.labs.phone_dictation_port,
        // An import that can't run stays saved; the phone gets the model
        // the listener runs in its place.
        selection: crate::transcription::custom_models::runnable(&settings.transcription),
    });
    let mut desired = lock(&DESIRED);
    if desired.wanted != wanted {
        desired.wanted = wanted;
        desired.generation += 1;
        CHANGED.notify_all();
    }
}

/// The relay's current state, for the window.
pub fn status() -> Status {
    STATUS
        .read()
        .unwrap_or_else(|error| error.into_inner())
        .clone()
}

/// Bumped whenever [`status`] changes, so the window rereads it only then.
pub fn status_generation() -> u64 {
    STATUS_GENERATION.load(Ordering::Acquire)
}

fn set_status(status: Status) {
    let mut current = STATUS.write().unwrap_or_else(|error| error.into_inner());
    if *current != status {
        *current = status;
        STATUS_GENERATION.fetch_add(1, Ordering::AcqRel);
    }
}

/// Replaces the pairing code: every paired phone has to enter the new one.
pub fn new_pairing_code() -> Result<()> {
    let mut saved = load_saved();
    saved.code = generate_code()?;
    save(&saved)?;
    let mut status = status();
    if let Status::Listening(listening) = &mut status {
        listening.code = saved.code.clone();
    }
    set_status(status);
    // The running server reads the code from the file's copy below.
    *CODE.write().unwrap_or_else(|error| error.into_inner()) = Some(saved.code);
    Ok(())
}

/// The code requests must carry, cached from the saved state.
static CODE: RwLock<Option<String>> = RwLock::new(None);

/// The app's phone relay supervisor: it starts, moves, and stops the
/// listener as [`apply`] asks, and stops it when dropped.
pub struct PhoneDictation {
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}

impl PhoneDictation {
    pub fn start() -> Option<Self> {
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = stop.clone();
        thread::Builder::new()
            .name("phone-dictation".into())
            .spawn(move || supervise(&worker_stop))
            .inspect_err(|error| tracing::error!(%error, "phone dictation could not start"))
            .ok()
            .map(|worker| Self {
                stop,
                worker: Some(worker),
            })
    }
}

impl Drop for PhoneDictation {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        {
            let _desired = lock(&DESIRED);
            CHANGED.notify_all();
        }
        if let Some(worker) = self.worker.take()
            && worker.join().is_err()
        {
            tracing::error!("phone dictation supervisor panicked");
        }
    }
}

fn supervise(stop: &AtomicBool) {
    let mut running: Option<Server> = None;
    let mut seen = u64::MAX;
    while !stop.load(Ordering::Acquire) {
        let (wanted, generation) = {
            let desired = lock(&DESIRED);
            (desired.wanted.clone(), desired.generation)
        };
        reconcile(&mut running, wanted.as_ref(), generation != seen);
        seen = generation;
        let desired = lock(&DESIRED);
        if desired.generation == seen && !stop.load(Ordering::Acquire) {
            let _ = CHANGED
                .wait_timeout(desired, REFRESH)
                .unwrap_or_else(|error| error.into_inner());
        }
    }
    drop(running);
    set_status(Status::Off);
}

/// Brings the listener in line with `wanted`. Called again every
/// [`REFRESH`], it retries a failed start and follows address changes.
fn reconcile(running: &mut Option<Server>, wanted: Option<&Wanted>, changed: bool) {
    let Some(wanted) = wanted else {
        if running.take().is_some() {
            tracing::info!("phone dictation stopped");
        }
        set_status(Status::Off);
        return;
    };
    if let Some(server) = running
        && server.port_setting == wanted.port
    {
        if changed {
            *server
                .shared
                .selection
                .write()
                .unwrap_or_else(|error| error.into_inner()) = wanted.selection.clone();
        }
        server.refresh_routes();
        return;
    }
    // A failed start retries on the next refresh, but says why only once.
    let retrying = !changed && matches!(status(), Status::Failed(_));
    *running = None;
    match Server::start(wanted) {
        Ok(server) => {
            tracing::info!(port = server.shared.port, "phone dictation listening");
            *running = Some(server);
        }
        Err(error) => {
            if !retrying {
                tracing::warn!(error = %format!("{error:#}"), "phone dictation could not start");
            }
            set_status(Status::Failed(format!("{error:#}")));
        }
    }
}

struct Shared {
    port: u16,
    name: String,
    selection: RwLock<TranscriptionSelection>,
    routes: RwLock<Vec<Route>>,
    transcription: TranscriptionServiceHandle,
    stop: AtomicBool,
}

struct Server {
    /// The settings' port this server was started for (0 is automatic).
    port_setting: u16,
    shared: Arc<Shared>,
    accept: Option<JoinHandle<()>>,
    advert: Option<Advert>,
    _transcription: TranscriptionService,
}

impl Server {
    /// Starts serving what `wanted` asks for, advertised on the LAN.
    fn start(wanted: &Wanted) -> Result<Self> {
        let mut saved = load_saved();
        if saved.code.is_empty() {
            saved.code = generate_code()?;
            save(&saved)?;
        }
        let listener = bind(wanted.port, saved.port)?;
        let port = listener.local_addr()?.port();
        if wanted.port == 0 && saved.port != port {
            saved.port = port;
            if let Err(error) = save(&saved) {
                tracing::warn!(%error, "could not remember the phone dictation port");
            }
        }
        *CODE.write().unwrap_or_else(|error| error.into_inner()) = Some(saved.code.clone());
        let routes = routes();
        let mut server = Self::serve(listener, wanted, computer_name(), routes.clone())?;
        // A phone can still be added by address without the advertisement.
        server.advert = Advert::start(&server.shared.name, port, &routes)
            .inspect_err(|error| {
                tracing::warn!(error = %format!("{error:#}"), "phone dictation is not advertised on the LAN");
            })
            .ok();
        set_status(Status::Listening(Listening {
            port,
            code: saved.code,
            routes,
        }));
        Ok(server)
    }

    fn serve(
        listener: TcpListener,
        wanted: &Wanted,
        name: String,
        routes: Vec<Route>,
    ) -> Result<Self> {
        let port = listener.local_addr()?.port();
        let (transcription_service, transcription) = TranscriptionService::start()?;
        let shared = Arc::new(Shared {
            port,
            name,
            selection: RwLock::new(wanted.selection.clone()),
            routes: RwLock::new(routes),
            transcription,
            stop: AtomicBool::new(false),
        });
        let accept_shared = shared.clone();
        let accept = thread::Builder::new()
            .name("phone-dictation-accept".into())
            .spawn(move || serve(&listener, &accept_shared))
            .wrap_err("could not start the phone dictation listener")?;
        Ok(Self {
            port_setting: wanted.port,
            shared,
            accept: Some(accept),
            advert: None,
            _transcription: transcription_service,
        })
    }

    /// Follows network changes: new Tailscale or Wi-Fi addresses reach the
    /// status, the next hello, and the advertisement.
    fn refresh_routes(&mut self) {
        let fresh = routes();
        let mut routes = self
            .shared
            .routes
            .write()
            .unwrap_or_else(|error| error.into_inner());
        if *routes == fresh {
            return;
        }
        routes.clone_from(&fresh);
        drop(routes);
        self.advert = None;
        self.advert = Advert::start(&self.shared.name, self.shared.port, &fresh).ok();
        if let Status::Listening(mut listening) = status() {
            listening.routes = fresh;
            set_status(Status::Listening(listening));
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.advert = None;
        self.shared.stop.store(true, Ordering::Release);
        // The listener blocks in accept so a phone's request is taken at
        // once; this connection wakes it to see the stop.
        let _ = TcpStream::connect_timeout(
            &SocketAddr::from((Ipv4Addr::LOCALHOST, self.shared.port)),
            Duration::from_millis(500),
        );
        if let Some(accept) = self.accept.take()
            && accept.join().is_err()
        {
            tracing::error!("phone dictation listener panicked");
        }
    }
}

/// Binds the configured port, or for an automatic setup the remembered one,
/// then [`DEFAULT_PORT`], then a free one from [`FALLBACK_PORTS`].
fn bind(port: u16, remembered: u16) -> Result<TcpListener> {
    let listen = |port: u16| TcpListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, port)));
    if port != 0 {
        return listen(port).wrap_err_with(|| format!("port {port} is unavailable"));
    }
    let mut candidates = vec![remembered, DEFAULT_PORT];
    candidates.retain(|candidate| *candidate != 0);
    let mut random = [0_u8; 16];
    getrandom::fill(&mut random).map_err(|error| eyre!("no randomness for a port: {error}"))?;
    let span = FALLBACK_PORTS.end - FALLBACK_PORTS.start;
    candidates.extend(
        random
            .chunks_exact(2)
            .map(|pair| FALLBACK_PORTS.start + u16::from_le_bytes([pair[0], pair[1]]) % span),
    );
    candidates
        .into_iter()
        .find_map(|candidate| listen(candidate).ok())
        .ok_or_else(|| eyre!("no free port for phone dictation"))
}

fn serve(listener: &TcpListener, shared: &Arc<Shared>) {
    let (connections, pending) = mpsc::sync_channel::<TcpStream>(QUEUE_CAPACITY);
    let pending = Arc::new(Mutex::new(pending));
    let workers: Vec<_> = (0..WORKERS)
        .filter_map(|index| {
            let pending = pending.clone();
            let shared = shared.clone();
            thread::Builder::new()
                .name(format!("phone-dictation-http-{index}"))
                .spawn(move || work(&pending, &shared))
                .ok()
        })
        .collect();
    while !shared.stop.load(Ordering::Acquire) {
        match listener.accept() {
            Ok(_) if shared.stop.load(Ordering::Acquire) => break,
            Ok((stream, peer)) => {
                // Hex's model answers only its own networks.
                if !admitted(peer.ip()) {
                    tracing::debug!(%peer, "phone dictation refused a public address");
                    continue;
                }
                match connections.try_send(stream) {
                    Ok(()) => {}
                    Err(TrySendError::Full(mut stream)) => {
                        let _ = write_response(&mut stream, HttpResponse::code(429, "busy"));
                    }
                    Err(TrySendError::Disconnected(_)) => break,
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
            Err(error) => {
                tracing::warn!(%error, "phone dictation accept failed");
                thread::sleep(ACCEPT_INTERVAL);
            }
        }
    }
    drop(connections);
    for worker in workers {
        let _ = worker.join();
    }
}

fn work(pending: &Mutex<Receiver<TcpStream>>, shared: &Shared) {
    loop {
        let stream = pending
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .recv();
        let Ok(mut stream) = stream else { return };
        if stream
            .set_read_timeout(Some(super::CONNECTION_TIMEOUT))
            .is_err()
            || stream
                .set_write_timeout(Some(super::UPLOAD_DEADLINE))
                .is_err()
        {
            continue;
        }
        let _ = stream.set_nodelay(true);
        let response = match read_request(&mut stream, &shared.stop) {
            Ok(request) => handle(request, &mut stream, shared),
            Err(RequestReadError::TooLarge) => Some(HttpResponse::empty(431)),
            Err(RequestReadError::Invalid) => Some(HttpResponse::empty(400)),
            Err(RequestReadError::Deadline) => Some(HttpResponse::empty(408)),
            Err(RequestReadError::Shutdown) => return,
            Err(RequestReadError::Io(_)) => None,
        };
        if let Some(response) = response {
            if let Err(error) = write_response(&mut stream, response) {
                tracing::debug!(%error, "phone disconnected before its reply");
            }
            linger(&mut stream);
        }
    }
}

/// Closes after the reply without resetting it: a body refused unread
/// (a wrong code, an unsupported clip) would otherwise make the close a
/// reset that can discard the reply before the phone reads it.
fn linger(stream: &mut TcpStream) {
    use std::io::Read;
    if stream.shutdown(std::net::Shutdown::Write).is_err()
        || stream
            .set_read_timeout(Some(Duration::from_millis(200)))
            .is_err()
    {
        return;
    }
    let mut buffer = [0_u8; 16 * 1024];
    let mut drained = 0;
    while drained < 512 * 1024 {
        match stream.read(&mut buffer) {
            Ok(0) | Err(_) => return,
            Ok(read) => drained += read,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Hello<'a> {
    app: &'static str,
    version: &'static str,
    name: &'a str,
    port: u16,
    routes: Vec<Route>,
    model: &'a str,
}

fn handle(request: HttpRequest, stream: &mut TcpStream, shared: &Shared) -> Option<HttpResponse> {
    let path = request.path.split('?').next().unwrap_or("/");
    let expected = CODE
        .read()
        .unwrap_or_else(|error| error.into_inner())
        .clone()
        .unwrap_or_default();
    let presented = request
        .authorization
        .as_deref()
        .and_then(|value| value.strip_prefix("Bearer "))
        .map(normalize_code)
        .unwrap_or_default();
    if expected.is_empty() || !same_code(&presented, &expected) {
        thread::sleep(AUTH_FAILURE_DELAY);
        return Some(HttpResponse::code(401, "wrong-code"));
    }
    match (request.method.as_str(), path) {
        ("GET", "/phone/v1/hello") => {
            let selection = shared
                .selection
                .read()
                .unwrap_or_else(|error| error.into_inner())
                .clone();
            let model = crate::transcription::models::definition(selection.model);
            Some(HttpResponse::json(
                200,
                &Hello {
                    app: "hex",
                    version: env!("CARGO_PKG_VERSION"),
                    name: &shared.name,
                    port: shared.port,
                    routes: shared
                        .routes
                        .read()
                        .unwrap_or_else(|error| error.into_inner())
                        .clone(),
                    model: model.name,
                },
            ))
        }
        ("POST", "/phone/v1/transcriptions") => transcribe(request, stream, shared),
        _ => Some(HttpResponse::code(404, "not_found")),
    }
}

fn transcribe(
    request: HttpRequest,
    stream: &mut TcpStream,
    shared: &Shared,
) -> Option<HttpResponse> {
    if !request.has_content_type("audio/wav") {
        return Some(HttpResponse::code(415, "unsupported-audio"));
    }
    let Some(content_length) = request.content_length else {
        return Some(HttpResponse::code(411, "length-required"));
    };
    if content_length > MAX_PHONE_AUDIO_BYTES {
        return Some(HttpResponse::code(413, "resource-exhausted"));
    }
    let selection = shared
        .selection
        .read()
        .unwrap_or_else(|error| error.into_inner())
        .clone();
    if !super::model_ready(&selection) {
        return Some(HttpResponse::code(409, "model-not-ready"));
    }
    let Some(admission) = shared.transcription.admit_audio() else {
        return Some(HttpResponse::code(429, "busy"));
    };
    super::transcribe_audio(
        stream,
        selection,
        content_length,
        request.body_prefix,
        &shared.transcription,
        &shared.stop,
        admission,
    )
}

/// Whether `peer` is on a network Hex answers: loopback, link-local, the
/// RFC 1918 ranges, or Tailscale's CGNAT range.
fn admitted(peer: IpAddr) -> bool {
    let peer = match peer {
        IpAddr::V4(peer) => peer,
        IpAddr::V6(peer) => match peer.to_ipv4_mapped() {
            Some(peer) => peer,
            None => return peer.is_loopback(),
        },
    };
    peer.is_loopback() || peer.is_private() || peer.is_link_local() || is_tailscale(peer)
}

/// Tailscale gives each device an address in 100.64.0.0/10.
fn is_tailscale(address: Ipv4Addr) -> bool {
    let [first, second, ..] = address.octets();
    first == 100 && second & 0xC0 == 64
}

fn route(address: Ipv4Addr) -> Option<Route> {
    let kind = if is_tailscale(address) {
        RouteKind::Tailscale
    } else if address.is_private() {
        RouteKind::Lan
    } else {
        return None;
    };
    Some(Route {
        kind,
        host: address,
    })
}

/// This computer's private IPv4 addresses, Tailscale first.
fn routes() -> Vec<Route> {
    let interfaces = if_addrs::get_if_addrs().unwrap_or_default();
    let mut routes: Vec<Route> = interfaces
        .iter()
        .filter_map(|interface| match interface.ip() {
            IpAddr::V4(address) if !interface.is_loopback() => route(address),
            _ => None,
        })
        .collect();
    routes.sort();
    routes.dedup();
    routes
}

/// The name a phone lists this computer by.
fn computer_name() -> String {
    #[cfg(unix)]
    let name = {
        let mut buffer = [0_u8; 256];
        // SAFETY: the buffer is valid for its length, and gethostname
        // writes at most that many bytes.
        let result = unsafe { libc::gethostname(buffer.as_mut_ptr().cast(), buffer.len()) };
        let end = buffer.iter().position(|byte| *byte == 0).unwrap_or(0);
        if result == 0 {
            String::from_utf8_lossy(&buffer[..end]).into_owned()
        } else {
            String::new()
        }
    };
    #[cfg(windows)]
    let name = std::env::var("COMPUTERNAME").unwrap_or_default();
    let name = name.split('.').next().unwrap_or_default().trim().to_owned();
    if name.is_empty() { "Hex".into() } else { name }
}

/// The LAN advertisement, withdrawn when dropped.
struct Advert {
    daemon: mdns_sd::ServiceDaemon,
    fullname: String,
}

impl Advert {
    fn start(name: &str, port: u16, routes: &[Route]) -> Result<Self> {
        let daemon = mdns_sd::ServiceDaemon::new()?;
        // The phone connects over IPv4, and Tailscale has no multicast.
        daemon.disable_interface(mdns_sd::IfKind::IPv6)?;
        for route in routes
            .iter()
            .filter(|route| route.kind == RouteKind::Tailscale)
        {
            daemon.disable_interface(mdns_sd::IfKind::Addr(IpAddr::V4(route.host)))?;
        }
        let host_label: String = name
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
            .collect();
        let info = mdns_sd::ServiceInfo::new(
            SERVICE_TYPE,
            name,
            &format!("hex-{host_label}.local."),
            "",
            port,
            &[("v", "1")][..],
        )?
        .enable_addr_auto();
        let fullname = info.get_fullname().to_owned();
        daemon.register(info)?;
        Ok(Self { daemon, fullname })
    }
}

impl Drop for Advert {
    fn drop(&mut self) {
        if let Ok(done) = self.daemon.unregister(&self.fullname) {
            let _ = done.recv_timeout(Duration::from_secs(1));
        }
        if let Ok(done) = self.daemon.shutdown() {
            let _ = done.recv_timeout(Duration::from_secs(1));
        }
    }
}

/// Uppercase, without separators, with the letters people mistake for
/// digits read as those digits (Crockford's base32).
pub fn normalize_code(code: &str) -> String {
    code.chars()
        .filter(char::is_ascii_alphanumeric)
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        })
        .collect()
}

/// Groups of four, as the window shows the code.
pub fn display_code(code: &str) -> String {
    code.as_bytes()
        .chunks(4)
        .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
        .collect::<Vec<_>>()
        .join("-")
}

/// Compares without stopping at the first difference.
fn same_code(presented: &str, expected: &str) -> bool {
    presented.len() == expected.len()
        && presented
            .bytes()
            .zip(expected.bytes())
            .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
            == 0
}

fn generate_code() -> Result<String> {
    let mut random = [0_u8; CODE_LENGTH];
    getrandom::fill(&mut random).map_err(|error| eyre!("no randomness for a code: {error}"))?;
    Ok(random
        .iter()
        .map(|byte| char::from(CODE_ALPHABET[usize::from(byte % 32)]))
        .collect())
}

fn state_file() -> Result<PathBuf> {
    Ok(crate::app_paths::support_dir()?.join("phone-dictation.json"))
}

fn load_saved() -> Saved {
    state_file()
        .ok()
        .and_then(|path| std::fs::read(path).ok())
        .and_then(|data| serde_json::from_slice::<Saved>(&data).ok())
        .map(|mut saved| {
            saved.code = normalize_code(&saved.code);
            if saved.code.len() != CODE_LENGTH {
                saved.code.clear();
            }
            saved
        })
        .unwrap_or_default()
}

fn save(saved: &Saved) -> Result<()> {
    let path = state_file()?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    super::discovery::write_private_json(&path, saved)
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn answers_only_private_networks() {
        for allowed in [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.9",
            "192.168.1.20",
            "100.101.102.103",
            "169.254.3.4",
        ] {
            assert!(admitted(allowed.parse().unwrap()), "{allowed}");
        }
        for refused in ["8.8.8.8", "100.128.0.1", "172.32.0.1", "2001:db8::1"] {
            assert!(!admitted(refused.parse().unwrap()), "{refused}");
        }
        assert!(admitted("::ffff:192.168.1.2".parse().unwrap()));
    }

    #[test]
    fn tailscale_routes_sort_first() {
        let mut routes: Vec<Route> = ["192.168.1.20", "100.64.0.7", "8.8.8.8", "10.0.0.2"]
            .iter()
            .filter_map(|address| route(address.parse().unwrap()))
            .collect();
        routes.sort();
        assert_eq!(
            routes
                .iter()
                .map(|route| (route.kind, route.host.to_string()))
                .collect::<Vec<_>>(),
            vec![
                (RouteKind::Tailscale, "100.64.0.7".into()),
                (RouteKind::Lan, "10.0.0.2".into()),
                (RouteKind::Lan, "192.168.1.20".into()),
            ]
        );
    }

    fn request(port: u16, head: &str, body: &[u8]) -> String {
        use std::io::{Read, Write};
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        stream.write_all(head.as_bytes()).unwrap();
        stream.write_all(body).unwrap();
        let mut reply = String::new();
        stream.read_to_string(&mut reply).unwrap();
        reply
    }

    /// A phone with the code gets the computer's name and routes; one
    /// without it, or with a clip the model can't take yet, gets a reason.
    #[test]
    fn serves_a_paired_phone_only() {
        *CODE.write().unwrap() = Some("7KQ2M9XD4FTA".into());
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let wanted = Wanted {
            port: 0,
            selection: TranscriptionSelection::default(),
        };
        let routes = vec![Route {
            kind: RouteKind::Tailscale,
            host: Ipv4Addr::new(100, 64, 0, 7),
        }];
        let server = Server::serve(listener, &wanted, "studio".into(), routes).unwrap();
        let port = server.shared.port;

        let refused = request(port, "GET /phone/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n", b"");
        assert!(refused.starts_with("HTTP/1.1 401"), "{refused}");
        let wrong = request(
            port,
            "GET /phone/v1/hello HTTP/1.1\r\nAuthorization: Bearer 7KQ2-M9XD-4FTB\r\n\r\n",
            b"",
        );
        assert!(wrong.starts_with("HTTP/1.1 401"), "{wrong}");

        let hello = request(
            port,
            "GET /phone/v1/hello HTTP/1.1\r\nAuthorization: Bearer 7kq2-m9xd-4fta\r\n\r\n",
            b"",
        );
        assert!(hello.starts_with("HTTP/1.1 200"), "{hello}");
        let body: serde_json::Value =
            serde_json::from_str(hello.split("\r\n\r\n").nth(1).unwrap()).unwrap();
        assert_eq!(body["app"], "hex");
        assert_eq!(body["name"], "studio");
        assert_eq!(body["port"], port);
        assert_eq!(body["routes"][0]["kind"], "tailscale");
        assert_eq!(body["routes"][0]["host"], "100.64.0.7");

        let text = request(
            port,
            "POST /phone/v1/transcriptions HTTP/1.1\r\nAuthorization: Bearer 7KQ2M9XD4FTA\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\n",
            b"hi",
        );
        assert!(text.starts_with("HTTP/1.1 415"), "{text}");
        drop(server);
    }

    #[test]
    fn codes_read_the_way_people_type_them() {
        let code = generate_code().unwrap();
        assert_eq!(code.len(), CODE_LENGTH);
        assert_eq!(normalize_code(&display_code(&code)), code);
        assert_eq!(normalize_code(" 7kq2-m9xd-4fta "), "7KQ2M9XD4FTA");
        assert_eq!(normalize_code("oil0"), "0110");
        assert!(same_code("ABC", "ABC"));
        assert!(!same_code("ABD", "ABC"));
        assert!(!same_code("AB", "ABC"));
    }
}
