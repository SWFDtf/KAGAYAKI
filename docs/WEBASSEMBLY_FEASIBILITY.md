# KAGAYAKI WebAssembly / TeaVM / Browser-Machbarkeitsstudie (`#1018`)

> **Zielsetzung:** Bewertung der Portierbarkeit des headless `.vjava`-Kernels (`de.swfdtf.math`, `de.swfdtf.core`, `de.swfdtf.parser`, `de.swfdtf.state`) auf WebAssembly (TeaVM / WasmGC / CheerpJ) im Browser sowie lückenlose Erfassung sämtlicher Abhängigkeiten **jenseits von LWJGL**.

## 1. Architektur-Ausgangslage

KAGAYAKI trennt durch `ArchitectureGuardTest` strikt zwischen:

1. **Headless Kernel (`math`, `core`, `parser`, `state`):** Enthält Mathematik (`Transform2D`, `ColorMatrix4f`, `Rect2f`), DSL-Lexer/Parser/AST (`VJavaLexer`, `VJavaParser`, `VJavaAst`), State-Machine (`ScriptRuntime`, `ScriptSession`) und 4-Tier-Save/Localization (`RevertableStateStore`, `SaveManager`, `CloudSyncManifest`).
2. **Plattform- & Medien-Backends (`graphics`, `audio`, `tools`, `git`):** Kapselt LWJGL 3 (GLFW, OpenGL 3.3 Core, OpenAL, STB) und Desktop-Werkzeuge (`StudioWindowController`).

## 2. Abhängigkeiten jenseits von LWJGL (Kern-Blocker & Shim-Ziele)

Eine WebAssembly-Portierung betrifft nicht nur LWJGL (OpenGL -> WebGL2, OpenAL -> WebAudio, GLFW -> HTML5 Canvas Events). Folgende **nicht-LWJGL-Abhängigkeiten** wurden per `WebAssemblyFeasibilityAnalyzer` identifiziert:

| Kategorie | Konkrete API / Klasse | Betroffene Stellen | Klassifizierung | WebAssembly-/Browser-Mitigation |
| :--- | :--- | :--- | :--- | :--- |
| **Runtime-Compiler** | `javax.tools.ToolProvider`, `JavaCompiler`, `ForwardingJavaFileManager` | `JavaBlockCompiler` | `NATIVE_BLOCKED` | Vorauskompilierung im Release-Build (`CompiledScriptBundle` `#0080` in `.vpak`) bzw. JS/Wasm-Transpilation statischer Logik |
| **Dynamischer ClassLoader** | `java.net.URLClassLoader`, `ClassLoader.defineClass` | `JavaBlockCompiler`, `PluginLoader` | `NATIVE_BLOCKED` | Statische Verlinkung zum Build-Zeitpunkt (TeaVM/Graal AOT) statt dynamischer Bytecode-Ladung zur Laufzeit |
| **Subprozess-Ausführung** | `java.lang.ProcessBuilder`, CLI-Aufrufe | `GitController`, `VideoPlayer`, `StudioWindowController` | `NATIVE_BLOCKED` | Nur im Desktop-Studio verfügbar; im Web-Target über Capability-Flags deaktivieren |
| **Dateisystem-I/O** | `java.nio.file.Files`, `FileChannel`, `WatchService` | `ProjectScanner`, `SaveManager`, `HotReloadWatcher`, `VpakArchiver` | `SHIM_REQUIRED` | Virtualisierung auf In-Memory-`.vpak`-Buffer (`ResourceProvider`) und Persistenz auf Browser-`IndexedDB` + `CloudSyncManifest` |
| **Reflexion** | `java.lang.reflect.*`, Gson-TypeToken-Reflexion | `ConditionEvaluator`, `SaveValueCodec`, `AstJsonSerializer` | `SHIM_REQUIRED` | Compile-time Codegen / registrierte Type-Adapter statt dynamischer Member-Reflexion |
| **GZIP / PNG-Codec** | `java.util.zip.GZIPOutputStream`, `STBImageWrite` | `SaveManager`, `VpakArchiver` | `SHIM_REQUIRED` | Browser-CompressionStream / reine Java-`Deflater`-Wasm-Implementierung und Canvas-WebP/PNG-Export |

## 3. Empfohlener Migrationspfad (Stufenplan)

1. **Phase A (Bereits durch `#0080` & `#0530` vorbereitet):** Alle `.vjava`-Skripte vorab parsen (`AstJsonSerializer`) und `.vpak` über `VpakResourceProvider` im Speicher lesen; Speicherstände plattformneutral per `CloudSyncManifest` formatieren.
2. **Phase B (Headless Runner in TeaVM / WasmGC):** `de.swfdtf.math`, `VJavaLexer`, `VJavaParser`, `ScriptRuntime` (ohne `ToolProvider`) und `SimpleStateStore` als `< 400 KB` `.wasm`-Modul für interaktive Browser-Story-Previews compilieren.
3. **Phase C (WebGL2 + WebAudio Adapter):** Thin Browser-Backend hinter `IRenderPipeline` und `ScriptRuntime.AudioBridge` implementieren, ohne den Desktop-LWJGL-Kern anzutasten.

## 4. Fazit

Der zustandslose DSL-Parser, die mathematischen Typen sowie große Teile von `ScriptRuntime` und `StateStore` sind ohne strukturelle Umbrüche TeaVM-/WasmGC-tauglich. Die einzigen echten WebAssembly-Blocker außerhalb von LWJGL liegen in der dynamischen `javax.tools`-/`URLClassLoader`-Ausführung beliebiger `@->{ ... }<<`-Blöcke sowie direktem `Files`/`ProcessBuilder`-Zugriff.
