package de.swfdtf.e2e.tier2;

import de.swfdtf.audio.AmbientCrossfadeMixer;
import de.swfdtf.audio.AudioChannel;
import de.swfdtf.audio.AudioCueRegistry;
import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.audio.VoicePlaybackBookmark;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaParseException;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.ui.GameRuntimeCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, resilience, and negative E2E tests for Feature Area F4: corrupt/header-only
 * OGG/WAV rejection, out-of-range {@code loop_start_sec}, overlapping ducking restarts,
 * zero-duration crossfades and invalid weights, unknown cues and polyphony bursts,
 * bounded caption queue overflow/validation, and missing voice tracks on bookmark restore.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F04AudioCaptionsBoundaryE2eTest {

    /** Creates a new {@code F04AudioCaptionsBoundaryE2eTest} instance. */
    public F04AudioCaptionsBoundaryE2eTest() {
    }

    /**
     * Verifies baseline audio error handling: 0-byte files, 58-byte {@code OggS} BOS header
     * placeholders, and truncated WAV chunks fail cleanly in {@link AudioDecoder#decode(Path)}
     * and leave {@link AudioEngine} non-playing without uncaught exceptions.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void emptyTruncatedAndHeaderOnlyOggWavRejectedWithoutCrash(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_bad_audio");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Bad Audio", "2D");
        Path audioDir = projectDir.resolve("assets/audio");
        Files.createDirectories(audioDir);

        Path emptyWav = audioDir.resolve("empty.wav");
        Files.write(emptyWav, new byte[0]);
        Path headerOnlyOgg = E2eFixtureFactory.writeMinimalOgg(audioDir.resolve("header_only.ogg"));
        Path truncatedWav = audioDir.resolve("truncated.wav");
        Files.write(truncatedWav, new byte[]{'R', 'I', 'F', 'F', 12, 0, 0, 0, 'W', 'A', 'V', 'E'});

        assertThrows(IOException.class, () -> AudioDecoder.decode(emptyWav));
        assertThrows(IOException.class, () -> AudioDecoder.decode(headerOnlyOgg));
        assertThrows(IOException.class, () -> AudioDecoder.decode(truncatedWav));

        try (AudioEngine engine = new AudioEngine(projectDir, true)) {
            engine.init();
            engine.play(AudioEngine.CHANNEL_MUSIC, "assets/audio/header_only.ogg", true, 0.0, 1.0);
            engine.play(AudioEngine.CHANNEL_SOUND, "assets/audio/truncated.wav", false, 0.0, 1.0);
            engine.update(0.05);
            assertEquals(0.0f, engine.getChannel(AudioEngine.CHANNEL_MUSIC).getCurrentRms(), 1e-6f);
            assertEquals(0.0f, engine.getChannel(AudioEngine.CHANNEL_SOUND).getCurrentRms(), 1e-6f);
        }
    }

    /**
     * Verifies goal {@code #0428} boundary behavior: negative {@code loop_start_sec} is rejected
     * cleanly (without {@code Unknown option}), and {@code loop_start_sec} exceeding track length
     * clamps safely without hanging or producing {@code NaN}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void loopStartSecNegativeOrExceedingDurationHandledSafely(@TempDir Path tempDir) throws Exception {
        Path negDir = tempDir.resolve("f04_neg_loop");
        E2eFixtureFactory.createMinimalPlayableProject(negDir, """
                label start:
                    play music "assets/audio/tone.wav" loop loop_start_sec -0.5
                    return
                """);
        VJavaParseException negEx = assertThrows(VJavaParseException.class, () -> ProjectScanner.scan(negDir));
        assertFalse(negEx.getMessage().contains("Unknown option"),
                "loop_start_sec option must be recognized and reject negative value: " + negEx.getMessage());

        Path clampDir = tempDir.resolve("f04_clamp_loop");
        E2eWorkspaceSupport.createEmptyProject(clampDir, "F04 Loop Clamp", "2D");
        E2eFixtureFactory.writeSineWav(clampDir.resolve("assets/audio/short.wav"), 440.0, 0.5, 22_050);
        E2eWorkspaceSupport.writeScript(clampDir, "main.vjava", """
                label start:
                    play music "assets/audio/short.wav" loop loop_start_sec 99.0
                    "Clamp."
                    return
                """);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(clampDir));
        try (AudioEngine audio = new AudioEngine(clampDir, true)) {
            audio.init();
            runtime.setAudioBridge(audio.createScriptAudioBridge());
            runtime.start();
            audio.update(1.0);
            double pos = audio.getChannel(AudioEngine.CHANNEL_MUSIC).getPlaybackSeconds();
            assertTrue(Double.isFinite(pos) && pos >= 0.0 && pos <= 0.5);
        }
    }

    /**
     * Verifies goal {@code #0429} boundary behavior: restarting voice mid-release during active
     * ducking keeps {@link AudioEngine} music gain strictly attenuated and bounded in {@code [0, 1]}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void duckingWithOverlappingVoiceRestartNeverSpikesOrClips(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_duck_restart");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Duck Restart", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/bgm.wav"), 220.0, 2.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/v1.wav"), 440.0, 0.5, 22_050);

        PreferencesState prefs = new PreferencesState();
        prefs.setMasterVolume(1.0f);
        prefs.setMusicVolume(1.0f);
        try (AudioEngine audio = new AudioEngine(projectDir, prefs, null, new EngineLogger(), true)) {
            audio.init();
            audio.play(AudioEngine.CHANNEL_MUSIC, "assets/audio/bgm.wav", true, 0.0, 1.0);
            audio.voice("assets/audio/v1.wav");
            audio.update(0.2);

            AudioChannel music = audio.getChannel(AudioEngine.CHANNEL_MUSIC);
            float ducked = music.getCurrentEffectiveVolume();
            assertTrue(ducked < 0.6f, "Expected music ducked during voice, got: " + ducked);

            // Stop and immediately restart voice mid-release
            audio.stop(AudioEngine.CHANNEL_VOICE, 0.0);
            audio.update(0.1);
            audio.voice("assets/audio/v1.wav");
            audio.update(0.15);

            float reDucked = music.getCurrentEffectiveVolume();
            assertTrue(Float.isFinite(reDucked) && reDucked >= 0.0f && reDucked < 0.65f,
                    "Expected smooth bounded re-ducking, got: " + reDucked);
        }
    }

    /**
     * Verifies goal {@code #0430} boundary behavior: {@link AudioEngine} enforces equal-power
     * crossfades, handles {@code fadein 0.0} without {@code NaN}, and {@link AmbientCrossfadeMixer}
     * rejects blank layer IDs and {@code NaN} weights.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void ambientCrossfadeZeroDurationAndInvalidWeightSafe(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_crossfade_boundary");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F04 Crossfade Boundary", "2D");
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/a.wav"), 180.0, 1.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/b.wav"), 280.0, 1.0, 22_050);

        try (AudioEngine audio = new AudioEngine(projectDir, true)) {
            audio.init();
            audio.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/a.wav", true, 0.0, 1.0);
            audio.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/b.wav", true, 0.8, 1.0);
            audio.update(0.4);
            AudioChannel ambient = audio.getChannel(AudioEngine.CHANNEL_AMBIENT);
            double power = Math.pow(ambient.getFadeGain(), 2.0) + Math.pow(ambient.getOutgoingFadeGain(), 2.0);
            assertTrue(power >= 0.92 && power <= 1.08, "Equal-power crossfade required, got power=" + power);

            audio.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/a.wav", true, 0.0, 1.0);
            audio.update(0.0);
            assertFalse(ambient.isCrossfading());
            assertEquals(1.0f, ambient.getFadeGain(), 0.001f);
        }

        AmbientCrossfadeMixer mixer = new AmbientCrossfadeMixer(0.0);
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget(" ", 0.5f));
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget("wind", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> mixer.applyPreset(Map.of("wind", 1.5f)));
    }

    /**
     * Verifies goal {@code #0431} boundary behavior: {@link AudioCueRegistry} service is wired
     * in {@link EngineApplication}, unknown cue IDs never throw, and invalid cue parameters fail
     * with {@link IllegalArgumentException}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void unknownAudioCueAndInvalidCueParametersHandledSafely(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_cue_boundary");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    play sound "unknown.cue.id"
                    "Weiter."
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
            assertNotNull(registry, "AudioCueRegistry service must be wired (#0431)");

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            coordinator.getSession().start();
            app.stepOnce(1.0 / 60.0);
            assertFalse(coordinator.getAudioEngine().getChannel(AudioEngine.CHANNEL_SOUND).isPlaying());

            assertThrows(IllegalArgumentException.class, () ->
                    new AudioCueRegistry.AudioCue("bad", "a.wav", "sound", 1.5f, false, 0.0f));
        }
    }

    /**
     * Verifies goal {@code #0432} boundary behavior: {@link ClosedCaptionQueue} service is wired
     * in {@link EngineApplication}, evicts oldest captions when overflowing {@code maxVisibleEntries},
     * expires entries on {@code update}, and rejects blank text or non-positive durations.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void closedCaptionOverflowEvictionAndExpirationBoundary(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_caption_boundary");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    "Dialog."
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
            ClosedCaptionQueue queue = app.getApi().getService(ClosedCaptionQueue.class);
            assertNotNull(queue, "ClosedCaptionQueue service must be wired (#0432)");

            queue.setMaxVisibleEntries(3);
            for (int i = 1; i <= 6; i++) {
                queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "sfx", "Event " + i, 1.0);
            }
            assertEquals(3, queue.getVisibleCaptions().size());
            assertEquals("Event 4", queue.getVisibleCaptions().get(0).text());

            queue.update(1.5);
            assertTrue(queue.getVisibleCaptions().isEmpty());

            assertThrows(IllegalArgumentException.class, () ->
                    queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "sfx", "   ", 1.0));
            assertThrows(IllegalArgumentException.class, () ->
                    queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "sfx", "Valid", 0.0));
        }
    }

    /**
     * Verifies goal {@code #0436} boundary behavior: {@link VoicePlaybackBookmark} service is wired
     * in {@link EngineApplication}, and deleting a bookmarked voice track before {@code quickLoad()}
     * degrades gracefully without aborting save restoration.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or save/load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void voiceBookmarkMissingTrackOnQuickLoadHandledSafely(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f04_bookmark_boundary");
        E2eWorkspaceSupport.copyResourceTree("e2e/f04/captions_bookmark", projectDir);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/thunder.wav"), 120.0, 0.3, 22_050);
        Path monologue = E2eFixtureFactory.writeSineWav(
                projectDir.resolve("assets/audio/monologue.wav"), 320.0, 2.0, 22_050
        );

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            VoicePlaybackBookmark bookmark = app.getApi().getService(VoicePlaybackBookmark.class);
            assertNotNull(bookmark, "VoicePlaybackBookmark service must be wired (#0436)");

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();
            app.stepOnce(0.6);
            session.quickSave();

            Files.deleteIfExists(monologue);
            session.quickLoad();
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        }
    }
}
