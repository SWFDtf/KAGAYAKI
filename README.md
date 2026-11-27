# KAGAYAKI

A **Java 21** & **LWJGL 3** (`OpenGL 3.3 Core`, `GLFW`, `OpenAL`, `STB`) visual novel / 2D-3D hybrid game engine and dual-window Studio environment (`KAGAYAKI v1.0.0`, `vJava 1.1` DSL).

## Features

- **Dual-Window Studio Mode (`runStudio`)**: Project Hub (`EngineVersion`), 9 project templates (`FULL_DEMO`, `STARTER`, `ACTION_ADVENTURE_2D`, `HYBRID_3D_EXPLORATION`, `NVL_LITERARY`, etc.), persistent workspace state (`~/.kagayaki/studio.json`), clickable Recent Projects with live Window B reload, Live Director, Inspectors, multi-monitor panel undocking, Git `HEAD` dialog diff, and lossless `VisualNodeGraphEditor`.
- **vJava 1.1 Hybrid DSL & In-Memory Java 21**: 73 AST statement types covering VN dialogue (`ADV`/`NVL`/`BUBBLE`), inline `@->{ ... }<<` Java compilation, WASD/physics/collision motion, A* pathfinding, 2D/3D skeletal animation, and precompiled `.vpak` script bundles.
- **2D & 3D OpenGL 3.3 Rendering**: Premultiplied-alpha 2D pipeline (`1920x1080` offscreen letterbox FBO, ATL transforms, transitions, particles) plus 3D pipeline (`RenderPipeline3D` with IBL, SSAO, DoF, God-Rays, Light Probes, SSS, glTF skinning, instancing, and OpenAL 3D listener sync).
- **Standalone Runtime & Packaging (`runStandalone` / `packageStandalone` / `jpackageBundle`)**: Builds an executable standalone JAR (`build/dist/kagayaki-standalone-*.jar`) or native app bundle with encrypted/verified `.vpak` VFS & modding support, strictly guarded against `java.awt.*`/`javax.imageio.*` for macOS `-XstartOnFirstThread` safety.

## Tech Stack

- **Language**: Java 21
- **Graphics / Audio / Windowing**: LWJGL 3.3.6 (GLFW, OpenGL, OpenAL, STB)
- **Math**: JOML 1.10.8
- **Serialization**: Gson 2.11.0
- **Testing**: JUnit 6.0.0 & Mockito 5.14.2
- **Build**: Gradle

## Prerequisites

- **JDK 21** or higher

## Quick Start

### Run in Studio Mode
```bash
./gradlew runStudio
```
Optionally pass a project directory:
```bash
./gradlew runStudio -PkagayakiProject=/path/to/project
```

### Run in Standalone Mode
```bash
./gradlew runStandalone
```

### Package Standalone JAR
```bash
./gradlew packageStandalone
```

### Run Tests
```bash
./gradlew check javadoc test e2eTest
```

## Project Structure

- `src/` — Engine, Studio, and runtime source code (`de.swfdtf.*`)
- `demo/` — Demo project assets and scenes
- `docs/` — Architecture, `.vjava` DSL specs, handbooks, and design documentation
- `build.gradle` — Gradle build configuration and custom `kagayaki` tasks

## Documentation

- [Erste Schritte mit vjava](docs/getting-started.md) — Setup, CLI, Hello World, Quellcode-Struktur
- [Entwickler-Handbuch (DE)](docs/HANDBUCH_DE.md) · [Developer Guide (EN)](docs/GUIDE_EN.md)
- [vjava-Sprachreferenz](docs/sprachreferenz.md) — alle Befehle, Argumente und Beispiele
- [vjava-Spezifikation](docs/VJAVA_SPEZIFIKATION.md) · [Architektur](docs/ARCHITEKTUR.md) · [API-Referenz](docs/API_REFERENZ.md)
- [Save Schema](docs/SAVE_SCHEMA.md) · [Packaging & Modding](docs/PACKAGING_AND_MODDING.md) · [WebAssembly Study](docs/WEBASSEMBLY_FEASIBILITY.md)
- [Contributing](CONTRIBUTING.md) · [Security Policy](SECURITY.md)

## License

This project is licensed under the [MIT License](LICENSE).
