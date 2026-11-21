//! The Phone dictation pane's strings (Labs › Phone dictation), kept beside
//! [`super::TRANSLATIONS`] so the language tables stay under the source cap.

/// Columns: Polish, Chinese (Simplified), Japanese, German, Spanish, Ukrainian.
#[rustfmt::skip]
pub(super) const TRANSLATIONS: &[(&str, [&str; 6])] = &[
    ("Phone dictation", ["Dyktowanie z telefonu", "手机听写", "スマートフォンで音声入力", "Diktieren mit dem Handy", "Dictado desde el teléfono", "Диктування з телефона"]),
    ("Dictate on Hex for Android with this computer's model, over Tailscale or Wi-Fi", ["Dyktuj w Hex na Androida modelem tego komputera, przez Tailscale lub Wi-Fi", "在 Android 版 Hex 上听写，使用这台电脑的模型，通过 Tailscale 或 Wi-Fi", "Android 版 Hex で、このコンピュータのモデルを使って音声入力します（Tailscale または Wi-Fi 経由）", "Mit Hex für Android über das Modell dieses Computers diktieren, per Tailscale oder WLAN", "Dicta en Hex para Android con el modelo de este ordenador, por Tailscale o Wi-Fi", "Диктуйте в Hex для Android моделлю цього комп'ютера через Tailscale або Wi-Fi"]),
    ("Not listening", ["Nie nasłuchuje", "未在监听", "待ち受けていません", "Lauscht nicht", "No está escuchando", "Не слухає"]),
    ("Addresses", ["Adresy", "地址", "アドレス", "Adressen", "Direcciones", "Адреси"]),
    ("No network yet. Port {}", ["Brak sieci. Port {}", "尚无网络。端口 {}", "まだネットワークがありません。ポート {}", "Noch kein Netzwerk. Port {}", "Aún no hay red. Puerto {}", "Мережі ще немає. Порт {}"]),
    ("Local network", ["Sieć lokalna", "局域网", "ローカルネットワーク", "Lokales Netzwerk", "Red local", "Локальна мережа"]),
    ("Pairing code", ["Kod parowania", "配对码", "ペアリングコード", "Kopplungscode", "Código de vinculación", "Код сполучення"]),
    ("New code", ["Nowy kod", "新代码", "新しいコード", "Neuer Code", "Código nuevo", "Новий код"]),
    ("Port", ["Port", "端口", "ポート", "Port", "Puerto", "Порт"]),
];

#[rustfmt::skip]
pub(super) const FRENCH: &[(&str, &str)] = &[
    ("Phone dictation", "Dictée depuis le téléphone"),
    ("Dictate on Hex for Android with this computer's model, over Tailscale or Wi-Fi", "Dictez dans Hex pour Android avec le modèle de cet ordinateur, via Tailscale ou le Wi-Fi"),
    ("Not listening", "N'écoute pas"),
    ("Addresses", "Adresses"),
    ("No network yet. Port {}", "Pas encore de réseau. Port {}"),
    ("Local network", "Réseau local"),
    ("Pairing code", "Code d'appairage"),
    ("New code", "Nouveau code"),
    ("Port", "Port"),
];

#[rustfmt::skip]
pub(super) const PORTUGUESE: &[(&str, &str)] = &[
    ("Phone dictation", "Ditado pelo telefone"),
    ("Dictate on Hex for Android with this computer's model, over Tailscale or Wi-Fi", "Dite no Hex para Android com o modelo deste computador, por Tailscale ou Wi-Fi"),
    ("Not listening", "Não está ouvindo"),
    ("Addresses", "Endereços"),
    ("No network yet. Port {}", "Ainda sem rede. Porta {}"),
    ("Local network", "Rede local"),
    ("Pairing code", "Código de pareamento"),
    ("New code", "Novo código"),
    ("Port", "Porta"),
];

#[rustfmt::skip]
pub(super) const RUSSIAN: &[(&str, &str)] = &[
    ("Phone dictation", "Диктовка с телефона"),
    ("Dictate on Hex for Android with this computer's model, over Tailscale or Wi-Fi", "Диктуйте в Hex для Android с моделью этого компьютера через Tailscale или Wi-Fi"),
    ("Not listening", "Не слушает"),
    ("Addresses", "Адреса"),
    ("No network yet. Port {}", "Сети пока нет. Порт {}"),
    ("Local network", "Локальная сеть"),
    ("Pairing code", "Код сопряжения"),
    ("New code", "Новый код"),
    ("Port", "Порт"),
];
