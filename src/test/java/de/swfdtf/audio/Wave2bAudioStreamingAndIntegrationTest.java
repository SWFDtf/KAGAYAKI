package de.swfdtf.audio;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaParseException;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and integration tests covering Wave 2b audio goals: chunked {@link OggVorbisStream}
 * playback ({@code #0426}), {@code loop_start_sec} wrapping ({@code #0428}), live
 * {@link AudioDuckingController} wiring ({@code #0429}), equal-power ambient crossfade
 * ({@code #0430}), {@link AudioCueRegistry} wiring ({@code #0431}), live
 * {@link ClosedCaptionQueue} ({@code #0432}), and {@link VoicePlaybackBookmark} persistence
 * ({@code #0436}).
 */
final class Wave2bAudioStreamingAndIntegrationTest {

    @Test
    void oggVorbisStreamReadsChunksSeeksClosesAndRejectsCorrupt(@TempDir Path tempDir) throws Exception {
        byte[] validOgg = Base64.getDecoder().decode(AudioDecoderAndChannelTest.SAMPLE_OGG_440HZ_MONO_B64);
        Path oggPath = tempDir.resolve("valid.ogg");
        Files.write(oggPath, validOgg);

        assertTrue(OggVorbisStream.isOggFile(oggPath));
        try (OggVorbisStream stream = OggVorbisStream.open(oggPath)) {
            assertEquals(1, stream.getChannels());
            assertEquals(44_100, stream.getSampleRate());
            assertTrue(stream.getDurationSeconds() > 0.04);

            short[] chunk = new short[256 * stream.getChannels()];
            int readFrames = stream.readInterleavedPcm16(chunk, 256);
            assertTrue(readFrames > 0);

            stream.seekSeconds(0.02);
            float[] window = stream.readMonoAnalysisWindow(512);
            assertEquals(512, window.length);
            assertTrue(AudioDsp.computeRms(window) > 0.01f);
            assertFalse(stream.isClosed());
        }

        Path corrupt = tempDir.resolve("corrupt.ogg");
        Files.write(corrupt, new byte[]{'O', 'g', 'g', 'S', 0, 1, 2, 3});
        assertThrows(IOException.class, () -> OggVorbisStream.open(corrupt));
    }

    @Test
    void loopStartSecParsesValidatesNegativeAndWrapsCleanly(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("project.vjava.json"), "{\"title\":\"Loop\",\"defaultLanguage\":\"de\"}");
        AudioDecoder.DecodedAudio tone = AudioDecoder.synthesizeTone(330.0, 1.0, 22_050, 0.5f);
        Path wavPath = tempDir.resolve("assets/audio/loop_test.wav");
        Files.createDirectories(wavPath.getParent());
        Files.write(wavPath, AudioDecoder.encodeWav(tone.pcm16(), tone.channels(), tone.sampleRate()));

        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    play music "assets/audio/loop_test.wav" loop loop_start_sec 0.5
                    "Playing."
                    return
                """);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(tempDir));
        try (AudioEngine engine = new AudioEngine(tempDir, true)) {
            engine.init();
            runtime.setAudioBridge(engine.createScriptAudioBridge());
            runtime.start();

            AudioChannel music = engine.getChannel(AudioEngine.CHANNEL_MUSIC);
            engine.update(0.8);
            assertEquals(0.8, music.getPlaybackSeconds(), 0.02);
            engine.update(0.4);
            assertEquals(0.7, music.getPlaybackSeconds(), 0.02);
        }

        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    play music "assets/audio/loop_test.wav" loop loop_start_sec -1.0
                    return
                """);
        assertThrows(VJavaParseException.class, () -> ProjectScanner.scan(tempDir));
    }

    @Test
    void audioEngineWiresCueRegistryDuckingEqualPowerCrossfadeCaptionsAndBookmarkSave(@TempDir Path tempDir)
            throws Exception {
        Path audioDir = tempDir.resolve("assets/audio");
        Files.createDirectories(audioDir);
        for (String name : List.of("wind.wav", "rain.wav", "step.wav", "voice.wav")) {
            AudioDecoder.DecodedAudio t = AudioDecoder.synthesizeTone(300.0, 2.0, 22_050, 0.5f);
            Files.write(audioDir.resolve(name), AudioDecoder.encodeWav(t.pcm16(), t.channels(), t.sampleRate()));
        }
        Files.writeString(tempDir.resolve("project.vjava.json"), "{\"title\":\"Mixer\",\"defaultLanguage\":\"de\"}");
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    play ambient "assets/audio/wind.wav" loop volume 1.0
                    play sound "sfx.step"
                    voice "assets/audio/voice.wav"
                    "Test."
                    return
                """);

        try (AudioEngine engine = new AudioEngine(tempDir, true)) {
            engine.init();
            engine.getCueRegistry().register(new AudioCueRegistry.AudioCue(
                    "sfx.step", "assets/audio/step.wav", AudioEngine.CHANNEL_SOUND, 0.45f, false, 0.0f
            ));

            ScriptSession session = new ScriptSession(
                    ProjectScanner.scan(tempDir), new EngineAPI(EngineConfig.defaults())
            );
            session.getRuntime().setAudioBridge(engine.createScriptAudioBridge());
            session.start();

            AudioChannel ambient = engine.getChannel(AudioEngine.CHANNEL_AMBIENT);
            AudioChannel sound = engine.getChannel(AudioEngine.CHANNEL_SOUND);
            assertTrue(sound.isPlaying());
            assertEquals(0.45f, sound.getTargetVolume(), 1e-4f);
            assertFalse(engine.getClosedCaptionQueue().getVisibleCaptions().isEmpty());

            // Equal-power crossfade on ambient
            engine.play(AudioEngine.CHANNEL_AMBIENT, "assets/audio/rain.wav", true, 1.0, 1.0);
            engine.update(0.5);
            double power = Math.pow(ambient.getFadeGain(), 2.0) + Math.pow(ambient.getOutgoingFadeGain(), 2.0);
            assertTrue(power >= 0.95 && power <= 1.05, "Power=" + power);

            // Ducking reduces ambient gain while voice active
            assertTrue(ambient.getDuckGain() < 0.5f);

            // Save slot bookmark round-trip
            engine.update(0.4);
            session.quickSave();
            SaveSlotData loaded = new SaveManager(tempDir.resolve("saves")).quickLoad();
            assertNotNull(loaded.voiceBookmark());
            assertEquals("assets/audio/voice.wav", loaded.voiceBookmark().assetPath());
            assertTrue(loaded.voiceBookmark().offsetSeconds() >= 0.8);

            engine.stop(AudioEngine.CHANNEL_VOICE, 0.0);
            assertFalse(engine.getChannel(AudioEngine.CHANNEL_VOICE).isPlaying());
            session.quickLoad();
            assertTrue(engine.getChannel(AudioEngine.CHANNEL_VOICE).isPlaying());
            assertTrue(engine.getChannel(AudioEngine.CHANNEL_VOICE).getPlaybackSeconds() >= 0.8);
        }
    }
}
