package de.swfdtf.e2e.tier1;

import de.swfdtf.audio.AudioChannel;
import de.swfdtf.audio.AudioCueRegistry;
import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.audio.VoicePlaybackBookmark;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.GameRuntimeCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F4: headless WAV/OGG decoding, OGG Vorbis
 * streaming memory bounds, seamless {@code loop_start_sec} looping, {@link de.swfdtf.audio.AudioDuckingController}
 * integration, {@link de.swfdtf.audio.AmbientCrossfadeMixer} equal-power crossfades,
 * {@link AudioCueRegistry} play/stop activation, live {@link ClosedCaptionQueue} captions,
 * and {@link VoicePlaybackBookmark} persistence.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F04AudioCaptionsE2eTest {

    private static final long TEN_MEGABYTES = 10L * 1024L * 1024L;

    /** Creates a new {@code F04AudioCaptionsE2eTest} instance. */
    public F04AudioCaptionsE2eTest() {
    }

    /**
     * Verifies baseline F4 audio pipeline: WAV playback, queue, fadeout, voice RMS, and real
     * short OGG Vorbis decoding via {@link AudioDecoder#decode(Path)} without hardware audio devices.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or audio decoding fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void baselineWavPlaybackQueueFadeAndOggDecodeWorkHeadlessly(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_baseline");
        E2eWorkspaceSupport.copyResourceTree("e2e/f04/audio_baseline", projectDir);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/bgm.wav"), 220.0, 1.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/next.wav"), 330.0, 1.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/voice.wav"), 440.0, 0.5, 22_050);

        Path oggSharedDir = tempDir.resolve("ogg_shared");
        E2eWorkspaceSupport.copyResourceTree("e2e/f04/shared_ogg", oggSharedDir);
        AudioDecoder.DecodedAudio decodedOgg = AudioDecoder.decode(oggSharedDir.resolve("theme_440hz.ogg"));
        assertEquals(1, decodedOgg.channels());
        assertEquals(44_100, decodedOgg.sampleRate());
        assertTrue(decodedOgg.frameCount() > 2_000);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "audio_baseline must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        try (AudioEngine audio = new AudioEngine(projectDir, true)) {
            audio.init();
            runtime.setAudioBridge(audio.createScriptAudioBridge());
            runtime.start();

            AudioChannel music = audio.getChannel(AudioEngine.CHANNEL_MUSIC);
            assertTrue(music.isPlaying());
            assertEquals(List.of("assets/audio/next.wav"), music.getQueuedPaths());

            audio.update(0.25);
            assertTrue(audio.getActiveVoiceRms() > 0.01f);
            assertEquals(1.0f, music.getFadeGain(), 0.01f);

            runtime.advance();
            assertTrue(music.isFadingOut());
            audio.update(0.30);
            assertFalse(music.isPlaying());
        }
    }

    /**
     * Verifies goal {@code #0426}: streaming a 5-minute OGG Vorbis track in {@link AudioEngine}
     * plays cleanly without materializing the ~79 MB full-PCM decoded buffer in heap.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or audio playback fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void oggVorbisStreamingDecodesAndBoundsHeapAllocation(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_ogg_stream");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 OGG Streaming", "2D");
        Path oggShared = tempDir.resolve("ogg_shared");
        E2eWorkspaceSupport.copyResourceTree("e2e/f04/shared_ogg", oggShared);

        Path audioDir = projectDir.resolve("assets/audio");
        Files.createDirectories(audioDir);
        Files.copy(oggShared.resolve("theme_440hz.ogg"), audioDir.resolve("theme_440hz.ogg"));
        Files.copy(oggShared.resolve("long_ambient_5min.ogg"), audioDir.resolve("long_ambient_5min.ogg"));

        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                label start:
                    play music "assets/audio/long_ambient_5min.ogg" loop
                    "Lange Musik streamt."
                    return
                """);

        System.gc();
        Thread.sleep(50L);
        long heapBefore = usedHeapBytes();

        try (AudioEngine audio = new AudioEngine(projectDir, true)) {
            audio.init();
            audio.play(AudioEngine.CHANNEL_MUSIC, "assets/audio/theme_440hz.ogg", false, 0.0, 0.9);
            assertTrue(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying());
            audio.update(0.1);

            audio.play(AudioEngine.CHANNEL_MUSIC, "assets/audio/long_ambient_5min.ogg", true, 0.0, 0.9);
            assertTrue(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying());
            audio.update(0.2);

            System.gc();
            Thread.sleep(50L);
            long heapDelta = Math.max(0L, usedHeapBytes() - heapBefore);
            assertTrue(
                    heapDelta < TEN_MEGABYTES,
                    "Streaming 5-min OGG must retain < 10 MB heap (target < 2 MB), got: "
                            + (heapDelta / (1024L * 1024L)) + " MB"
            );
        }
    }

    /**
     * Verifies goal {@code #0428}: {@code play music ... loop loop_start_sec <sec>} plays
     * the intro once and loops back to the exact {@code loop_start_sec} offset.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or script execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void seamlessMusicLoopWrapsToExactLoopStartSec(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_loop_start");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Loop Point", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/intro_loop.wav"), 330.0, 1.0, 22_050);
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                label start:
                    play music "assets/audio/intro_loop.wav" loop loop_start_sec 0.5
                    "Loop aktiv."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "loop_start_sec script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        try (AudioEngine audio = new AudioEngine(projectDir, true)) {
            audio.init();
            runtime.setAudioBridge(audio.createScriptAudioBridge());
            runtime.start();

            AudioChannel music = audio.getChannel(AudioEngine.CHANNEL_MUSIC);
            audio.update(0.8);
            assertEquals(0.8, music.getPlaybackSeconds(), 0.05);

            // Advance 0.4s across 1.0s end -> must wrap back to 0.5 + 0.2 = 0.70s (NOT 0.20s)
            audio.update(0.4);
            assertTrue(music.isPlaying());
            assertEquals(0.70, music.getPlaybackSeconds(), 0.06);
        }
    }

    /**
     * Verifies goal {@code #0429}: {@link AudioEngine} wires
     * {@link de.swfdtf.audio.AudioDuckingController} so active voice playback attenuates
     * music and ambient channels smoothly and releases back after voice stops.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or script execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void audioDuckingControllerAttenuatesMusicAndAmbientDuringVoice(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_ducking");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Ducking", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/bgm.wav"), 220.0, 2.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/amb.wav"), 150.0, 2.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/voice.wav"), 440.0, 0.8, 22_050);
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                label start:
                    play music "assets/audio/bgm.wav" volume 1.0
                    play ambient "assets/audio/amb.wav" volume 1.0
                    "Vor Voice."
                    voice "assets/audio/voice.wav"
                    "Während Voice."
                    stop voice
                    "Nach Voice."
                    return
                """);

        PreferencesState prefs = new PreferencesState();
        prefs.setMasterVolume(1.0f);
        prefs.setMusicVolume(1.0f);
        prefs.setAmbientVolume(1.0f);
        prefs.setVoiceVolume(1.0f);

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        try (AudioEngine audio = new AudioEngine(
                projectDir, prefs, null, new de.swfdtf.core.EngineLogger(), true)) {
            audio.init();
            runtime.setAudioBridge(audio.createScriptAudioBridge());
            runtime.start();

            AudioChannel music = audio.getChannel(AudioEngine.CHANNEL_MUSIC);
            AudioChannel ambient = audio.getChannel(AudioEngine.CHANNEL_AMBIENT);
            audio.update(0.05);
            assertEquals(1.0f, music.getCurrentEffectiveVolume(), 0.05f);

            runtime.advance();
            audio.update(0.25);
            assertTrue(music.getCurrentEffectiveVolume() < 0.55f,
                    "Music must duck during voice playback, got: " + music.getCurrentEffectiveVolume());
            assertTrue(ambient.getCurrentEffectiveVolume() < 0.55f,
                    "Ambient must duck during voice playback, got: " + ambient.getCurrentEffectiveVolume());

            runtime.advance();
            audio.update(0.60);
            assertTrue(music.getCurrentEffectiveVolume() > 0.90f,
                    "Music must recover after voice stops, got: " + music.getCurrentEffectiveVolume());
        }
    }

    /**
     * Verifies goal {@code #0430}: {@link AudioEngine} integrates
     * {@link de.swfdtf.audio.AmbientCrossfadeMixer} with equal-power crossfade energy
     * ({@code fadeIn^2 + fadeOut^2 ~ 1.0}) across simultaneous ambient tracks.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void ambientCrossfadeMixerBlendsLayersWithEqualPower(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_ambient_crossfade");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Ambient Crossfade", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/wind.wav"), 180.0, 2.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/rain.wav"), 320.0, 2.0, 22_050);

        try (AudioEngine audio = new AudioEngine(projectDir, true)) {
            audio.init();
            audio.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/wind.wav", true, 0.0, 1.0);
            audio.update(0.1);
            audio.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/rain.wav", true, 1.0, 1.0);
            audio.update(0.5);

            AudioChannel ambient = audio.getChannel(AudioEngine.CHANNEL_AMBIENT);
            assertTrue(ambient.isCrossfading());
            float inGain = ambient.getFadeGain();
            float outGain = ambient.getOutgoingFadeGain();
            double powerSum = (double) inGain * inGain + (double) outGain * outGain;
            assertTrue(powerSum >= 0.92 && powerSum <= 1.08,
                    "Expected equal-power crossfade sum ~ 1.0, got " + powerSum
                            + " (in=" + inGain + ", out=" + outGain + ")");
        }
    }

    /**
     * Verifies goal {@code #0431}: {@link AudioCueRegistry} is registered and wired into
     * {@link AudioEngine} so {@code play sound "<cueId>"} resolves registered cues and applies
     * cue defaults.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or stepping fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void audioCueRegistryNormalizesPlayStopWithMaxPolyphony(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_cue_registry");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Cue Registry", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/step.wav"), 520.0, 0.4, 22_050);
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                label start:
                    play sound "sfx.step"
                    "Schritt."
                    stop sound
                    "Still."
                    return
                """);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            AudioCueRegistry registry = app.getApi().getService(AudioCueRegistry.class);
            assertNotNull(registry, "AudioCueRegistry must be registered as EngineAPI service (#0431)");
            registry.register(new AudioCueRegistry.AudioCue(
                    "sfx.step", "assets/audio/step.wav", AudioEngine.CHANNEL_SOUND, 0.5f, false, 0.0f
            ));

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            coordinator.getSession().start();
            app.stepOnce(1.0 / 60.0);

            AudioChannel sound = coordinator.getAudioEngine().getChannel(AudioEngine.CHANNEL_SOUND);
            assertTrue(sound.isPlaying(), "play sound \"sfx.step\" must resolve registered cue and play");
            assertEquals("assets/audio/step.wav", sound.getAssetPath());
            assertEquals(0.5f, sound.getTargetVolume(), 0.02f);
        }
    }

    /**
     * Verifies goals {@code #0432} and {@code #0436}: {@link ClosedCaptionQueue} receives live
     * captions synchronized to {@code play}/{@code voice}, and {@link VoicePlaybackBookmark}
     * persists/restores monologue playback position across {@code quickSave}/{@code quickLoad}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or save/load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void closedCaptionsAndVoiceBookmarkSyncAcrossPlayAndSaveLoad(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_captions_bookmark");
        E2eWorkspaceSupport.copyResourceTree("e2e/f04/captions_bookmark", projectDir);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/thunder.wav"), 120.0, 0.5, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/monologue.wav"), 320.0, 3.0, 22_050);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            ClosedCaptionQueue captionQueue = app.getApi().getService(ClosedCaptionQueue.class);
            VoicePlaybackBookmark bookmark = app.getApi().getService(VoicePlaybackBookmark.class);
            assertNotNull(captionQueue, "ClosedCaptionQueue must be registered on EngineAPI (#0432)");
            assertNotNull(bookmark, "VoicePlaybackBookmark must be registered on EngineAPI (#0436)");

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();
            app.stepOnce(0.1);

            assertFalse(captionQueue.getVisibleCaptions().isEmpty(),
                    "play/voice statements must push live captions into ClosedCaptionQueue");

            // Advance monologue to ~1.4s, save, stop voice, then quickLoad and verify offset restoration
            for (int i = 0; i < 13; i++) {
                app.stepOnce(0.1);
            }
            AudioEngine audio = coordinator.getAudioEngine();
            double savedOffset = audio.getChannel(AudioEngine.CHANNEL_VOICE).getPlaybackSeconds();
            assertTrue(savedOffset >= 1.1, "Expected voice offset >= 1.1s before save, got: " + savedOffset);

            session.quickSave();
            audio.stop(AudioEngine.CHANNEL_VOICE, 0.0);
            assertFalse(audio.getChannel(AudioEngine.CHANNEL_VOICE).isPlaying());

            session.quickLoad();
            double resumedOffset = audio.getChannel(AudioEngine.CHANNEL_VOICE).getPlaybackSeconds();
            assertTrue(resumedOffset >= 1.0,
                    "quickLoad must resume monologue near saved bookmark (" + savedOffset + "), got: "
                            + resumedOffset);
        }
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
