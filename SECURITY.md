# Security Policy (`SECURITY.md`)

## 1. Vertrauensmodell: `.vjava`-Skripte vs. `@->{ ... }<<`-Java-Blöcke vs. `.vpak`-Mods

| Artefakt | Risikoprofil & Schutzmechanismen |
| :--- | :--- |
| Reiner `.vjava`-Dialog & DSL | Läuft über `ConditionEvaluator` mit strikter Reflection-Allowlist (`ClassLoader`, `Runtime`, `ProcessBuilder`, `java.nio.file.*` blockiert). |
| Eingebettete `@->{ ... }<<` Java-Blöcke | Echter Java-21-Code im Engine-Prozess — **nur aus vertrauenswürdigen Projektquellen** kompilieren und ausführen. |
| `.vpak`-Archive & DLCs | `VpakArchiver` prüft Magic-Header (`VPAK`), Entry-Bounds, CRC32 je Datei, Pfadnormalisierung gegen **Zip Slip** (`../`) und optionale AES-256-CTR-Verschlüsselung / Ed25519-Manifestsignaturen. |
| Laufzeit-Plugins (`plugins/*.jar`) | Werden isoliert über `PluginLoader` / `PluginAndRuntimeLifecycleServices` mit deklariertem `plugin.json`-Berechtigungsmodell geladen. |

## 2. Sicherheitslücken melden

Bitte melde Sicherheitsprobleme (z. B. Sandbox-Bypässe in `ConditionEvaluator`, Path-Traversal in `VpakArchiver` oder Ressourcen-Leaks) direkt über das Repository unter <https://github.com/SWFDtf/KAGAYAKI> mit Schritten zur Reproduktion, betroffener Version (`EngineVersion.VERSION`) und erwarteter Auswirkung.
