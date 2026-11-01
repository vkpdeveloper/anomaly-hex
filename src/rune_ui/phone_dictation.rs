//! The rows under Labs › Phone dictation while it is on: where the phone
//! reaches this computer, the pairing code, and the port
//! (`crate::local_api::phone`).

use std::time::{Duration, Instant};

use rune::{El, TextEdit, Tx, row, text};
use rune_kit::{Button, ButtonVariant, ControlSize, TextInput};

use super::Ui;
use super::controls::{CardRow, Paint, setting_row};
use crate::desktop::i18n::{tr, tr_fill};
use crate::local_api::phone::{self, RouteKind, Status};

/// How long the port field rests after typing before its port is saved
/// (or, when it isn't a port, put back).
const PORT_DELAY: Duration = Duration::from_millis(1500);

#[derive(Default)]
pub(super) struct PhoneView {
    pub(super) status: Status,
    /// The status generation last read, so a change is read once.
    seen: Option<u64>,
    port: TextEdit,
    /// The saved port the field shows, until the user types.
    shown: Option<u16>,
    typed: String,
    edited: Option<Instant>,
}

/// Follows the relay's status and saves the port field once typing pauses.
/// True when anything shown changed.
pub(super) fn poll(ui: &mut Ui, now: Instant) -> bool {
    if !ui.labs_open || !ui.settings.labs.phone_dictation {
        return false;
    }
    let mut changed = false;
    if ui.live() {
        let generation = phone::status_generation();
        if ui.phone.seen != Some(generation) {
            ui.phone.seen = Some(generation);
            ui.phone.status = phone::status();
            changed = true;
        }
    }
    let saved = ui.settings.labs.phone_dictation_port;
    let view = &mut ui.phone;
    let typed = view.port.text.trim().to_owned();
    if typed != view.typed {
        view.typed = typed;
        view.edited = Some(now);
    }
    match view.edited {
        None if view.shown != Some(saved) => {
            view.port = TextEdit::new(port_text(saved));
            view.typed = port_text(saved);
            view.shown = Some(saved);
            changed = true;
        }
        Some(at) if now.duration_since(at) >= PORT_DELAY => {
            view.edited = None;
            match parse_port(&view.typed) {
                Some(port) if port != saved => {
                    view.shown = Some(port);
                    changed |= ui.update(|settings| settings.labs.phone_dictation_port = port);
                }
                Some(_) => {}
                // Not a port: the saved one comes back.
                None => view.shown = None,
            }
        }
        _ => {}
    }
    changed
}

fn port_text(port: u16) -> String {
    if port == 0 {
        String::new()
    } else {
        port.to_string()
    }
}

/// Blank is automatic (0); otherwise a port from 1024 up.
fn parse_port(typed: &str) -> Option<u16> {
    if typed.is_empty() {
        return Some(0);
    }
    typed
        .parse::<u16>()
        .ok()
        .filter(|port| *port >= phone::MIN_PORT)
}

pub(super) fn rows(ui: &Ui, p: Paint) -> Vec<CardRow> {
    let mut rows = vec![status_row(&ui.phone.status, p)];
    if let Status::Listening(listening) = &ui.phone.status {
        rows.push(code_row(&phone::display_code(&listening.code), p));
    }
    rows.push(port_row(ui, p));
    rows
}

fn status_row(status: &Status, p: Paint) -> CardRow {
    let (title, about) = match status {
        Status::Off => (tr("Starting…"), None),
        Status::Failed(why) => (tr("Not listening"), Some(why.clone())),
        Status::Listening(listening) if listening.routes.is_empty() => (
            tr("Addresses"),
            Some(tr_fill(
                "No network yet. Port {}",
                &listening.port.to_string(),
            )),
        ),
        Status::Listening(listening) => {
            let addresses: Vec<String> = listening
                .routes
                .iter()
                .map(|route| {
                    let network = match route.kind {
                        RouteKind::Tailscale => "Tailscale",
                        RouteKind::Lan => tr("Local network"),
                    };
                    format!("{network} {}:{}", route.host, listening.port)
                })
                .collect();
            (tr("Addresses"), Some(addresses.join(" · ")))
        }
    };
    setting_row(p, "lab-phone-status", title, about.as_deref(), row()).into()
}

fn code_row(code: &str, p: Paint) -> CardRow {
    let copy_code = code.to_owned();
    let copy = Button::new(tr("Copy"))
        .variant(ButtonVariant::Ghost)
        .control_size(ControlSize::Small)
        .on_press_tx(move |_: &mut Ui, tx: &mut Tx| tx.copy(copy_code.clone()))
        .build(p.k)
        .key("phone-code-copy");
    let renew = Button::new(tr("New code"))
        .variant(ButtonVariant::Ghost)
        .control_size(ControlSize::Small)
        .on_press(|ui: &mut Ui| {
            if ui.live()
                && let Err(error) = phone::new_pairing_code()
            {
                ui.error = Some(format!("{error:#}"));
            }
        })
        .build(p.k)
        .key("phone-code-new");
    let control: El = row()
        .key("phone-code")
        .gap(4.0)
        .center_y()
        .child(
            text(code.to_owned())
                .key("phone-code-text")
                .font(p.k.ty.mono)
                .color(p.text())
                .mr(4.0),
        )
        .child(copy)
        .child(renew);
    setting_row(p, "lab-phone-code", tr("Pairing code"), None, control).into()
}

fn port_row(ui: &Ui, p: Paint) -> CardRow {
    let field = TextInput::new(&ui.phone.port)
        .placeholder(tr("Automatic"))
        .width(Some(96.0))
        .on_edit(|ui: &mut Ui| &mut ui.phone.port)
        .build(p.k)
        .key("phone-port-field");
    setting_row(p, "lab-phone-port", tr("Port"), None, field).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_is_automatic_and_low_ports_are_refused() {
        assert_eq!(parse_port(""), Some(0));
        assert_eq!(parse_port("47213"), Some(47213));
        assert_eq!(parse_port("80"), None);
        assert_eq!(parse_port("70000"), None);
        assert_eq!(parse_port("port"), None);
    }
}
