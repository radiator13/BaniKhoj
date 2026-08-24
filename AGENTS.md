# BaniKhoj agent notes

## Build rules
- NEVER build or compile locally — not Gradle, not `cargo check`/`cargo build`. Ever.
- GitHub Actions is the only build/verification gate: push, then
  `gh workflow run "Build APK" -R radiator13/BaniKhoj --ref main` and watch it.
- Rust changes are only validated by CI too; review code carefully before pushing.

## Signing
- Release APKs are signed with a persistent keystore stored in GitHub secrets:
  BK_KEYSTORE_B64, BK_STORE_PASSWORD, BK_KEY_PASSWORD (alias: banikhoj).
- Local fallback is the debug key; never change release back to debug-only.

## Install on device
- Use Shizuku rish (`~/.rish/rish`): copy APK to /data/local/tmp (system_server
  cannot read /sdcard), then `pm install -r`. Shizuku drops out often; retry.

## Data notes (Shabad OS master.sqlite)
- sections.name JSON has ONLY {"Latn": ...} — no Gurmukhi section names exist.
- Two Rehras Sahib entries are intentional upstream (Taksal + SGPC variants).
