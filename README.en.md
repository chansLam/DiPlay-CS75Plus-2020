# DiPlay

**Wireless CarPlay for the Changan CS75 Plus 2020 head unit.**

An open-source wireless CarPlay receiver built for the CS75 Plus 2020 head unit (OS_S311-WT · Android 4.4.2 / API 19). Wi‑Fi Direct by default, full-screen immersion, built-in MFi authentication — no jailbreak, dongle, or account required.

> Independent implementation, not Apple-certified, not affiliated with Apple or Changan. Set up while safely parked.

---

## Highlights

- **CarPlay authentication** — built-in MFi auth keys, connects wirelessly out of the box
- **Full-screen immersion** — CarPlay fills the wide head-unit screen, adapted for the left-side nav bar, ~30 fps
- **Floating dot** — tap to return to DiPlay home from CarPlay, long-press to go back to CarPlay
- **Head-unit apps** — open native head-unit apps from inside CarPlay; long-press to uninstall
- **Wheel / Siri keys** — steering-wheel media keys drive CarPlay (next / prev / volume); optional Siri key with window key learning and root(su) auto-enable of accessibility (API 19 compatible, no adb prompt)
- **Wi‑Fi Direct** — connects directly to your iPhone; car hotspot (SoftAP) also supported
- **Hide nav bar** — hide the system nav bar globally (requires root / su)
- **Built-in terminal** — run commands on the head unit (experimental)
- **System settings** — dial `*#518200#*` to open the native system settings

## Install

1. If you have a differently-signed DiPlay build, uninstall it first.
2. Download the APK from [Releases](https://github.com/chansLam/DiPlay-CS75Plus-2020/releases) and install it **on the head unit** (not the iPhone).
3. Open DiPlay, connect your iPhone to the car hotspot, and keep Bluetooth on.
4. Tap **Connect Phone**, then allow CarPlay on your iPhone.

Full setup steps: [docs/INSTALL.md](docs/INSTALL.md)

## Docs

| Doc | About |
| --- | --- |
| [Install & use](docs/INSTALL.md) | Setup, connection, settings overview |
| [Build & release](docs/BUILD.md) | Environment, signing, Actions, in-app updates |
| [Security & privacy](SECURITY.md) | Keys and privacy notes |
| [Changelog](CHANGELOG.md) | Version history |

## Feedback

Open an [Issue](https://github.com/chansLam/DiPlay-CS75Plus-2020/issues) with a diagnostic report (Settings → Diagnostics), firmware version, and repro steps.

## License

GPL-3.0. CarPlay belongs to Apple Inc.
