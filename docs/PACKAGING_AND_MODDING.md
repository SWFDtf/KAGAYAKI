# KAGAYAKI Packaging, Distribution, Code-Signing & Modding (`#0791`, `#0830`, `#0850`)

## 1. `.vpak` Binary Format (`v1` & `v2`)
- Magic header: ASCII `"VPAK"`, version `1` (plaintext TOC) or `2` (AES-256-CTR encrypted TOC + payloads).
- Deterministic packed entries sort alphabetically by relative Unix path (`UTF-8`), resetting archive timestamps so repeated exports of identical content produce identical SHA-256 checksums (`#0779`).
- Pre-compressed media (`.png`, `.ogg`, `.jpg`, `.jpeg`, `.webp`) skips redundant CPU deflation via Store mode (`#0774`).

## 2. Virtual File System (`GameVfs`) & Mod Loading
Resolution precedence from highest to lowest (`#0771`, `#0773`, `#0781`):
1. Enabled non-quarantined mods in `mods.json` priority order
2. Mounted DLC `.vpak` packs (`mountAdditionalVpak`)
3. Base `game.vpak` / base project directory
4. Embedded engine classpath defaults

All virtual paths pass strict `normalizeSafeRelativePath(...)` traversal sanitization (`SecurityException` on `..` or absolute paths `#0798`).

## 3. Standalone Distribution & Plugin Sandboxing
- Built via `StandaloneBuilder` (`game.vpak`, `run_game.sh` / `run_game.bat`, `NOTICE.txt`, `TROUBLESHOOTING.html`, `store_manifest.json`).
- Studio tooling packages (`de.swfdtf.tools.*`) are excluded from player runtime JARs (`#0783`).
- Plugins declare API compatibility (`minEngineApiVersion`) and permissions (`UI`, `AUDIO`, `STATE`, `FILESYSTEM`) in `plugin.json` (`#0785`).

## 4. macOS & Windows Code-Signing Hooks (`#0791`)
```bash
# macOS hardened runtime + entitlements (allowing LWJGL JIT / OpenGL)
codesign --force --deep --options runtime --entitlements packaging/entitlements.plist -s "Developer ID Application: ..." dist/KagayakiGame.app
xcrun notarytool submit dist/KagayakiGame.zip --apple-id ... --team-id ... --wait

# Windows Authenticode signing
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 dist\KagayakiGame.exe
```

## 5. Docker Headless CI Verification (`#0830`)
```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon check test
```
