package de.swfdtf.audio;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaLocalizationBridge;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioEngineAndLipSyncTest {

    @Test
    void headlessAudioEngineIntegratesWithScriptRuntimePreferencesLocalizationAndRollback(
            @TempDir Path projectRoot
    ) throws IOException {
        Path audioDir = projectRoot.resolve("assets/audio");
        Path tlAudioDir = projectRoot.resolve("tl/english/audio");
        Files.createDirectories(audioDir);
        Files.createDirectories(tlAudioDir);

        // Write valid WAV assets: theme1 (0.2s), theme2 (0.4s), default voice (300 Hz), english voice (900 Hz)
        writeToneWav(audioDir.resolve("theme1.wav"), 440.0, 0.2, 0.8f);
        writeToneWav(audioDir.resolve("theme2.wav"), 660.0, 0.4, 0.8f);
        writeToneWav(audioDir.resolve("voice_de.wav"), 300.0, 0.5, 0.7f);
        writeToneWav(tlAudioDir.resolve("voice_de.wav"), 900.0, 0.5, 0.7f);

        Files.writeString(projectRoot.resolve("project.vjava.json"), """
                {
                  "title": "Audio Test VN",
                  "defaultLanguage": "deutsch"
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(projectRoot.resolve("main.vjava"), """
                define lena = Character("Lena")

                label start:
                    play music "assets/audio/theme1.wav" noloop volume 1.0
                    queue music ["assets/audio/theme2.wav"]
                    voice "assets/audio/voice_de.wav"
                    lena "Hallo!"
                    stop music fadeout 0.2
                    lena "Stille."
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(projectRoot);
        PreferencesState prefs = new PreferencesState();
        prefs.setMasterVolume(1.0f);
        prefs.setMusicVolume(0.8f);
        prefs.setVoiceVolume(1.0f);

        LocalizationManager loc = new LocalizationManager("deutsch");
        VJavaLocalizationBridge.indexProject(loc, project);

        try (AudioEngine engine = new AudioEngine(projectRoot, prefs, loc, new EngineLogger(), true)) {
            engine.init();
            assertTrue(engine.isInitialized());
            assertFalse(engine.isOpenAlActive());

            ScriptRuntime runtime = new ScriptRuntime(project, new EngineAPI(EngineConfig.defaults()));
            runtime.setAudioBridge(engine.createScriptAudioBridge());
            runtime.start();

            // First dialogue line: theme1 playing on music, theme2 queued, voice_de playing on voice
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
            AudioChannel music = engine.getChannel(AudioEngine.CHANNEL_MUSIC);
            AudioChannel voice = engine.getChannel(AudioEngine.CHANNEL_VOICE);

            assertTrue(music.isPlaying());
            assertEquals("assets/audio/theme1.wav", music.getAssetPath());
            assertEquals(List.of("assets/audio/theme2.wav"), music.getQueuedPaths());
            assertTrue(voice.isPlaying());
            assertTrue(engine.getActiveVoiceRms() > 0.3f);

            // Music Room spectrum bars reflect active 440 Hz tone
            float[] spectrum = engine.getMusicSpectrumBands(16);
            assertEquals(16, spectrum.length);
            float maxBand = 0.0f;
            for (float b : spectrum) {
                maxBand = Math.max(maxBand, b);
            }
            assertTrue(maxBand > 0.3f, "Expected music spectrum bar peak > 0.3, got " + maxBand);

            // PreferencesState scaling propagates immediately
            prefs.setMasterVolume(0.5f);
            prefs.setMusicVolume(0.5f);
            engine.update(0.05);
            assertEquals(0.25f, music.getCurrentEffectiveVolume(), 1e-5f);

            // Capture rollback snapshot before advancing queue & stop
            Map<String, AudioChannel.ChannelSnapshot> rollbackSnapshot = engine.snapshotAllChannels();

            // Advance past theme1.wav (0.2s total) -> queued theme2.wav starts automatically!
            engine.update(0.20);
            assertTrue(music.isPlaying());
            assertEquals("assets/audio/theme2.wav", music.getAssetPath());

            // Advance script to stop music fadeout
            runtime.advance();
            assertTrue(music.isFadingOut());
            engine.update(0.25);
            assertFalse(music.isPlaying());

            // Restore snapshot -> music is back on theme1.wav at 0.05s!
            engine.restoreAllChannels(rollbackSnapshot);
            assertTrue(music.isPlaying());
            assertEquals("assets/audio/theme1.wav", music.getAssetPath());
            assertEquals(0.05, music.getPlaybackSeconds(), 1e-4);

            // Localization tl/<lang>/audio fallback resolution
            assertEquals(
                    audioDir.resolve("voice_de.wav").toAbsolutePath().normalize(),
                    engine.resolveAudioPath("assets/audio/voice_de.wav")
            );
            loc.setLanguage("english");
            assertEquals(
                    tlAudioDir.resolve("voice_de.wav").toAbsolutePath().normalize(),
                    engine.resolveAudioPath("assets/audio/voice_de.wav")
            );

            // Voice bleep test
            prefs.setVoiceVolume(1.0f);
            prefs.setMasterVolume(1.0f);
            engine.playVoiceBleep(540.0);
            assertTrue(voice.isPlaying());
            assertTrue(engine.getActiveVoiceRms() > 0.05f);
        }
    }

    @Test
    void lipSyncAnalyzerSmoothsAttackAndReleaseIntoMouthShapes() {
        LipSyncAnalyzer analyzer = new LipSyncAnalyzer(0.02, 0.15, 0.10f, 0.50f);
        assertEquals(LipSyncAnalyzer.MouthShape.CLOSED, analyzer.getMouthShape());
        assertEquals(0.0f, analyzer.getOpenness(), 1e-6f);

        // Below noise floor remains closed
        assertEquals(LipSyncAnalyzer.MouthShape.CLOSED, analyzer.updateFromRms(0.005f, 0.016));

        // Moderate speech RMS opens into HALF_OPEN
        analyzer.updateFromRms(0.15f, 0.03);
        assertEquals(LipSyncAnalyzer.MouthShape.HALF_OPEN, analyzer.getMouthShape());

        // Loud speech RMS opens quickly into OPEN
        for (int i = 0; i < 4; i++) {
            analyzer.updateFromRms(0.45f, 0.016);
        }
        assertEquals(LipSyncAnalyzer.MouthShape.OPEN, analyzer.getMouthShape());
        assertTrue(analyzer.getOpenness() > 0.6f);

        // Release on silence is smoother/slower than attack
        analyzer.updateFromRms(0.0f, 0.04);
        float opennessAfterShortRelease = analyzer.getOpenness();
        assertTrue(opennessAfterShortRelease > 0.3f, "Expected gradual release, got " + opennessAfterShortRelease);

        for (int i = 0; i < 40; i++) {
            analyzer.updateFromRms(0.0f, 0.02);
        }
        assertEquals(LipSyncAnalyzer.MouthShape.CLOSED, analyzer.getMouthShape());
        assertTrue(analyzer.getOpenness() < 0.05f);

        // Direct integration with AudioEngine.getActiveVoiceRms()
        try (AudioEngine engine = new AudioEngine(null, true)) {
            engine.init();
            engine.playVoiceBleep(480.0, 0.2);
            analyzer.reset();
            for (int i = 0; i < 5; i++) {
                analyzer.update(engine, 0.016);
            }
            assertTrue(analyzer.getOpenness() > 0.0f);
        }
    }

    @Test
    void textToSpeechServiceStripsTagsQueuesAsynchronouslyAndBuildsOsCommands() throws InterruptedException {
        try (TextToSpeechService tts = new TextToSpeechService(new EngineLogger(), false)) {
            // Disabled by default: speaking records nothing
            tts.speak("{b}Ignoriert{/b}");
            assertTrue(tts.awaitIdle(500));
            assertTrue(tts.getSpokenHistory().isEmpty());

            tts.setEnabled(true);
            List<String> heard = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);
            tts.setOnSpeakListener(utterance -> {
                synchronized (heard) {
                    heard.add(utterance);
                }
                latch.countDown();
            });

            tts.speak("{b}Hallo{/b} {w=0.4}{color=#ff8800}Lena{/color}{image=assets/ui/smile.png}!");
            tts.speak("{rb}東京{/rb}{rt}とうきょう{/rt} {fast}geht {nw}los!");
            tts.speak("{w=0.2}{nw}"); // pure cues -> skipped cleanly

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(tts.awaitIdle(1000));

            List<String> expected = List.of("Hallo Lena!", "東京 geht los!");
            assertEquals(expected, tts.getSpokenHistory());
            synchronized (heard) {
                assertEquals(expected, heard);
            }

            // Disabling clears queue & stops cleanly
            tts.stop();
            tts.setEnabled(false);
            assertFalse(tts.isEnabled());
        }

        // Cross-platform OS command construction verification
        List<String> macCmd = TextToSpeechService.buildSpeechCommand("Mac OS X", "Hallo Welt");
        assertEquals(List.of("say", "Hallo Welt"), macCmd);

        List<String> linuxCmd = TextToSpeechService.buildSpeechCommand("Linux", "Hallo Welt");
        assertEquals(List.of("spd-say", "--wait", "Hallo Welt"), linuxCmd);

        List<String> winCmd = TextToSpeechService.buildSpeechCommand("Windows 11", "It's fine");
        assertEquals("powershell.exe", winCmd.get(0));
        assertEquals("-EncodedCommand", winCmd.get(3));
        assertNotNull(winCmd.get(4));
    }

    private static void writeToneWav(Path target, double freqHz, double durationSec, float amplitude)
            throws IOException {
        AudioDecoder.DecodedAudio tone = AudioDecoder.synthesizeTone(freqHz, durationSec, 44100, amplitude);
        byte[] wav = AudioDecoder.encodeWav(tone.pcm16(), tone.channels(), tone.sampleRate());
        Files.write(target, wav);
    }
}
