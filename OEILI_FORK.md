# wgtunnel-oeili — fork rationale

This is a minimal fork of [wgtunnel/wgtunnel](https://github.com/wgtunnel/wgtunnel)
that adds zero-touch tunnel provisioning for unattended phone fleets.

## What's different from upstream

A single Kotlin patch in `RestartReceiver`:

- On `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, scan
  `/sdcard/Android/data/com.zaneschepke.wireguardautotunnel/files/oeili-configs/`
  for `.conf` files.
- For each file, parse via `TunnelConfig.tunnelConfFromQuick(...)` and import
  via `tunnelRepository.saveTunnelsUniquely(...)`. Idempotent — duplicates
  by name are skipped.
- If `oeili-remote-key.txt` exists in that directory, set its contents as the
  RemoteControl security key and enable RemoteControl in `GeneralSettings`.

That's it. Upstream's RemoteControl receiver, tunnel manager, auto-tunnel logic,
UI — all unchanged.

## Why?

Upstream WG Tunnel (and the official WireGuard Android app) requires UI
interaction to import a config: open the app, tap the FAB, tap "Import from
file or archive", navigate the SAF picker, tap the file. For one phone this is
fine; for a 10+ phone fleet provisioned automatically over ADB, every UI tap
is a fragility multiplier.

This fork moves config import to a deterministic boot-time scan of an
ADB-writable directory. Provisioning becomes:

1. Generate a WG config on the host.
2. `adb push` the config to the watch directory.
3. `adb reboot` (or trigger MY_PACKAGE_REPLACED via package upgrade).
4. WG Tunnel auto-imports on boot. Always-on-VPN takes it from there.

No tapping, no UI automation, no fragility across firmware versions.

## How to consume

The CI workflow `.github/workflows/oeili-release.yml` produces a debug-signed
APK on every push to `oeili-main`. The latest build is always available at:

```
https://github.com/DivMode/wgtunnel-oeili/releases/download/oeili-latest/wgtunnel-oeili.apk
```

The fleet provisioning playbook installs this APK via `adb install -r`, with
SHA256 verified against the GitHub release notes.

## Maintenance contract

- **Upstream sync**: rebase `oeili-main` onto `wgtunnel/wgtunnel:master`
  periodically. The patch is small and isolated to one file, so conflicts
  should be rare.
- **Signing**: debug-signed only. These APKs are sideloaded onto our own
  devices, never distributed publicly. If we ever need release signing for
  some reason, add `KEYSTORE` + `SIGNING_*` secrets to the fork's GitHub repo
  and adapt the workflow.
- **Watch dir**: `/sdcard/Android/data/com.zaneschepke.wireguardautotunnel/files/oeili-configs/`
  is the app's external-files directory. ADB shell can write here on Android
  11+ without `MANAGE_EXTERNAL_STORAGE`. The directory is automatically scoped
  to the app — no global storage permission needed.

## Files changed vs upstream

- `app/src/main/java/com/zaneschepke/wireguardautotunnel/core/broadcast/RestartReceiver.kt`
  — added the `autoImportFromExternalFiles(context)` call and helper.
- `.github/workflows/oeili-release.yml` — new CI workflow for our build pipeline.
- `OEILI_FORK.md` — this file.

That's the entire diff against upstream.
