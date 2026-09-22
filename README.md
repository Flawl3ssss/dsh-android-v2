# dsh-android-v2

DSH Android wrapper (fresh, from scratch): proot Debian + Node 24 + DSH web + Zen adapter. APK via GitHub Actions.

- DSH pin: **0.1.7-alpha.1** (alpha). Updates only by button (check → update in idle → rollback).
- Ports: zen `:8787`, DSH `:8081` (loopback only).
- Key: Zen API key only from app Settings (EncryptedSharedPreferences → env). No embedded keys.
- Docs: `docs/` (research/plan/architecture live in Minis shared project, mirrored here on release).

## Build

Workflows: `app.yml` (APK), `payload.yml` (payload-3), `rootfs.yml` (rootfs-bookworm-2).
Secrets for release signing: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
