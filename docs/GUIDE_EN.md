# KAGAYAKI Developer Guide (English)

> **Engine:** KAGAYAKI (`de.swfdtf`) · Java 21 LTS · LWJGL 3 (OpenGL 3.3 Core, GLFW, OpenAL, STB) · `.vjava` 1.1 Hybrid DSL

## 1. Architecture Overview

KAGAYAKI separates deterministic state, OpenGL 3.3 Core 2D/3D rendering, OpenAL audio DSP, the `.vjava` parser/runtime, the 16-screen UI framework, and Dual-Window Studio tooling into isolated packages under `src/main/java/de/swfdtf/`:

- `core`, `math` — Clock, input remapping, EventBus, config, runtime diagnostics, and `KagayakiException` hierarchy
- `state` — 4-tier state/rollback (`RevertableStateStore`, `SaveManager`, `SaveMigrationService`, `LocalizationManager`)
- `graphics`, `audio` — OpenGL 3.3 pipelines, animations, transitions, WASD/physics (`ObjectMotionController`), WAV/OGG DSP
- `parser` — Lexer, parser, `ConditionEvaluator` (incl. `in a..b` / `between a and b`), `ScriptRuntime` (recursion depth guard `MAX_CALL_STACK_DEPTH = 128`)
- `ui`, `tools` — Studio Window A (`StudioWindowController`), `VJavaLinter`, 9 templates, `.vpak` packager, and QA release tooling

See [ARCHITEKTUR.md](ARCHITEKTUR.md), [SAVE_SCHEMA.md](SAVE_SCHEMA.md), and [PACKAGING_AND_MODDING.md](PACKAGING_AND_MODDING.md) for full details.

## 2. Quickstart & vJava 1.1 Commands

- Launch Studio (`Window A + Window B`): `./gradlew run`
- Build standalone package: `./gradlew packageStandalone`
- Run Snow White showcase & linter: `./gradlew -p demo check lintDemo packageDemo`
- Beginner tutorial: [TUTORIAL_ERSTES_PROJEKT.md](TUTORIAL_ERSTES_PROJEKT.md) · German handbook: [HANDBUCH_DE.md](HANDBUCH_DE.md)

- **Roadmap Completion (`1,021 / 1,021` goals `[x]`, `KAGAYAKI v1.0.0 / vJava 1.1`):**
  - 2D A* pathfinding (`GridPathfinder2D`), 2D skeletal adapter (`Skeletal2DAdapter`), live label hot-swap, `.vpak`-embedded `CompiledScriptBundle`, cross-device `CloudSyncManifest`, and [WEBASSEMBLY_FEASIBILITY.md](WEBASSEMBLY_FEASIBILITY.md).
  - Advanced 3D rendering (`IblEnvironment3D`, `SsaoPass`, `DepthOfField3D`, `VolumetricGodRaysPass`, `AmbientLightProbeGrid`, `SubsurfaceScatteringModel`, `Scene3DPanoramaBaker`, `RenderDocMarkerScope`), 64-bone `Skeletal3DAnimator`, `InstancedMeshNode`, OpenAL 3D listener sync, vertex wind & rigid-body drop physics.
  - Studio persistence (`~/.kagayaki/studio.json`), clickable Recent Projects with Window B live reload, multi-monitor undocking (`StudioUndockedWindowManager`), Git `HEAD` dialog diff, `DialogPacingLinter`, lossless `VisualNodeGraphEditor`, non-overlapping `useCustomMainMenuScreen` (`template_main_menu` vs. Dark Slate built-in), and `ArchitectureGuardTest` (no cyclic `state` imports, zero `java.awt.*`/`javax.imageio.*`).

## 3. Quality, Testing & Security

- Style checks & tests: `./gradlew check javadoc test e2eTest`
- Security & mod trust boundary: [SECURITY.md](../SECURITY.md)
- Contributing guidelines: [CONTRIBUTING.md](../CONTRIBUTING.md)
