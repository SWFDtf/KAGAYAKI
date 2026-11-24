# KAGAYAKI

A Java 21 & LWJGL 3 game engine and dual-window Studio environment.

## Features

- **Dual-Window Studio Mode (`runStudio`)**: Development environment with separate Studio tooling and runtime viewport.
- **Standalone Runtime (`runStandalone`)**: Lightweight game-only execution mode without Studio overhead.
- **Standalone Packaging (`packageStandalone`)**: Builds an executable standalone JAR (`build/dist/kagayaki-standalone-*.jar`) excluding Studio tools.
- **Cross-Platform Natives**: Automatic LWJGL native resolution for Windows, macOS (including Apple Silicon & `-XstartOnFirstThread`), and Linux.

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
./gradlew runStudio -PprojectDir=/path/to/project
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
./gradlew test
```

## Project Structure

- `src/` — Engine, Studio, and runtime source code (`de.swfdtf.*`)
- `demo/` — Demo project assets and scenes
- `docs/` — Architecture and design documentation
- `build.gradle` — Gradle build configuration and custom `kagayaki` tasks

## License

This project is licensed under the [MIT License](LICENSE).
