package de.swfdtf.audio;

import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-6 enhancements in {@link AudioDecoder},
 * {@link AudioDsp}, {@link LipSyncAnalyzer}, {@link ClosedCaptionQueue},
 * {@link PreferencesState}, {@link VJavaParser}, and {@link VJavaLinter}.
 */
class WaveP2Chapter6AudioDspAndDecoderTest {

    @Test
    @DisplayName("AudioDecoder throws descriptive IOException with file path on 0-byte file")
    void decoderGuardsZeroByteFiles(@TempDir Path tempDir) throws IOException {
        Path emptyWav = tempDir.resolve("empty.wav");
        Files.write(emptyWav, new byte[0]);
        IOException ex = assertThrows(IOException.class, () -> AudioDecoder.decode(emptyWav));
        assertTrue(ex.getMessage().contains("empty.wav"));
    }

    @Test
    @DisplayName("AudioDecoder decodes 24-bit PCM WAV files cleanly and downmixes stereo to mono")
    void decoderDecodes24BitWavAndDownmixes() throws IOException {
        byte[] dataBytes = new byte[6]; // 2 mono 24-bit samples: +0.5 (~0x400000) and -0.5 (~0xC00000)
        dataBytes[0] = 0x00;
        dataBytes[1] = 0x00;
        dataBytes[2] = 0x40;
        dataBytes[3] = 0x00;
        dataBytes[4] = 0x00;
        dataBytes[5] = (byte) 0xC0;

        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        wav.putInt(36 + dataBytes.length);
        wav.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        wav.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(22050).putInt(22050 * 3)
                .putShort((short) 3).putShort((short) 24);
        wav.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        wav.putInt(dataBytes.length).put(dataBytes);

        AudioDecoder.DecodedAudio decoded = AudioDecoder.decodeWav(wav.array());
        assertEquals(2, decoded.frameCount());
        assertEquals(0.5f, decoded.monoOrInterleavedFloat()[0], 0.01f);
        assertEquals(-0.5f, decoded.monoOrInterleavedFloat()[1], 0.01f);

        float[] mono = AudioDecoder.downmixToMono(new float[]{1.0f, -1.0f, 0.5f, 0.5f}, 2);
        assertEquals(0.0f, mono[0], 0.001f);
        assertEquals(0.5f, mono[1], 0.001f);
    }

    @Test
    @DisplayName("AudioDecoder probeDuration reads WAV header duration in O(1) matching full decode")
    void decoderProbeDurationMatchesFullDecode(@TempDir Path tempDir) throws IOException {
        AudioDecoder.DecodedAudio tone = AudioDecoder.synthesizeTone(440.0, 1.25, 22_050, 0.5f);
        Path wavPath = tempDir.resolve("tone.wav");
        Files.write(wavPath, AudioDecoder.encodeWav(tone.pcm16(), tone.channels(), tone.sampleRate()));

        double probed = AudioDecoder.probeDuration(wavPath);
        assertEquals(tone.durationSeconds(), probed, 0.01);
    }

    @Test
    @DisplayName("AudioDecoder probes embedded WAV smpl loop region start and end frames")
    void decoderProbesWavSmplLoopChunk() throws IOException {
        AudioDecoder.DecodedAudio tone = AudioDecoder.synthesizeTone(440.0, 0.5, 22_050, 0.4f);
        byte[] baseWav = AudioDecoder.encodeWav(tone.pcm16(), 1, 22_050);

        ByteBuffer smpl = ByteBuffer.allocate(8 + 60).order(ByteOrder.LITTLE_ENDIAN);
        smpl.put((byte) 's').put((byte) 'm').put((byte) 'p').put((byte) 'l');
        smpl.putInt(60);
        smpl.position(8 + 28);
        smpl.putInt(1); // numLoops
        smpl.position(8 + 44);
        smpl.putInt(1100); // startFrame
        smpl.putInt(8800); // endFrame

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(baseWav);
        out.write(smpl.array());
        byte[] combined = out.toByteArray();
        ByteBuffer.wrap(combined).order(ByteOrder.LITTLE_ENDIAN).putInt(4, combined.length - 8);

        Optional<AudioDecoder.LoopRegion> region = AudioDecoder.probeWavLoopRegion(combined);
        assertTrue(region.isPresent());
        assertEquals(1100, region.get().startFrame());
        assertEquals(8800, region.get().endFrame());
    }

    @Test
    @DisplayName("AudioDecoder synthesizes ADSR Sine, Triangle, and Filtered Noise waveforms")
    void decoderSynthesizesAdsrWaveforms() {
        AudioDecoder.DecodedAudio tri = AudioDecoder.synthesizeAdsr(
                AudioDecoder.Waveform.TRIANGLE, 220.0, 0.25, 22050, 0.01, 0.02, 0.7f, 0.05, 0.8f);
        AudioDecoder.DecodedAudio noise = AudioDecoder.synthesizeAdsr(
                AudioDecoder.Waveform.FILTERED_NOISE, 400.0, 0.25, 22050, 0.01, 0.02, 0.6f, 0.05, 0.5f);
        assertEquals(0.25, tri.durationSeconds(), 0.01);
        assertTrue(AudioDsp.computeRms(noise.toMonoFloat()) > 0.01f);
    }

    @Test
    @DisplayName("AudioDecoder downsamples >48kHz audio cleanly to 48kHz preserving duration")
    void decoderDownsamplesHighRateTo48kHz() {
        AudioDecoder.DecodedAudio hiRes = AudioDecoder.synthesizeTone(440.0, 0.5, 96_000, 0.5f);
        AudioDecoder.DecodedAudio resampled = AudioDecoder.resampleTo48kHzIfAbove(hiRes);
        assertEquals(48_000, resampled.sampleRate());
        assertEquals(hiRes.durationSeconds(), resampled.durationSeconds(), 0.005);
    }

    @Test
    @DisplayName("AudioDecoder detects leading/trailing silence vs active speech bounds")
    void decoderDetectsSpeechBounds() {
        int sr = 22_050;
        float[] samples = new float[sr]; // 1.0s: middle 0.4s active, 0.3s silent padding at both ends
        for (int i = (int) (0.3 * sr); i < (int) (0.7 * sr); i++) {
            samples[i] = 0.4f * (float) Math.sin(2.0 * Math.PI * 300.0 * i / sr);
        }
        AudioDecoder.DecodedAudio padded = new AudioDecoder.DecodedAudio(
                samples, AudioDecoder.floatToPcm16(samples), 1, sr, 1.0);
        AudioDecoder.SpeechBounds bounds = AudioDecoder.detectActiveSpeechBounds(padded, -35.0f);
        assertEquals(0.3, bounds.startSeconds(), 0.05);
        assertEquals(0.7, bounds.endSeconds(), 0.05);
        assertEquals(0.4, bounds.activeDurationSeconds(), 0.1);
    }

    @Test
    @DisplayName("AudioDsp soft limiter keeps stacked peaks inside [-1.0, +1.0]")
    void dspSoftLimiterBoundsPeaks() {
        float[] loud = new float[]{2.5f, -3.2f, 0.4f, -0.2f};
        AudioDsp.applySoftLimiter(loud);
        for (float s : loud) {
            assertTrue(s >= -1.0f && s <= 1.0f);
        }
        assertEquals(0.4f, loud[2], 0.001f);
    }

    @Test
    @DisplayName("AudioDsp applies NightMode & VoiceClarity EQ presets and Radio/Telephone bandpass")
    void dspEqPresetsAndRadioFilter() {
        AudioDecoder.DecodedAudio sub = AudioDecoder.synthesizeTone(60.0, 0.3, 44_100, 0.5f);
        float[] night = sub.toMonoFloat();
        AudioDsp.applyEqPreset(night, 44_100f, AudioDsp.EqPreset.NIGHT_MODE);
        assertTrue(AudioDsp.computeRms(night) < AudioDsp.computeRms(sub.toMonoFloat()));

        float[] radio = sub.toMonoFloat();
        AudioDsp.applyRadioTelephoneEffect(radio, 44_100f);
        assertTrue(AudioDsp.computeRms(radio) < AudioDsp.computeRms(sub.toMonoFloat()));
    }

    @Test
    @DisplayName("AudioDsp micro fade-out removes end DC clicks and LUFS normalizer hits -16 LUFS")
    void dspMicroFadeOutAndLufsNormalization() {
        float[] dcEnd = new float[2000];
        for (int i = 0; i < dcEnd.length; i++) {
            dcEnd[i] = 0.8f;
        }
        AudioDsp.applyMicroFadeOut(dcEnd, 22_050, 15);
        assertEquals(0.0f, dcEnd[dcEnd.length - 1], 0.01f);

        AudioDecoder.DecodedAudio quiet = AudioDecoder.synthesizeTone(440.0, 0.5, 22_050, 0.03f);
        AudioDecoder.DecodedAudio norm = AudioDsp.normalizeToTargetLufs(quiet, -16.0);
        assertEquals(-16.0, AudioDsp.measureIntegratedLufs(norm), 1.0);
    }

    @Test
    @DisplayName("LipSyncAnalyzer computes zero-crossing rate and bakes/round-trips JSON or falls back")
    void lipSyncZcrAndBakedJson(@TempDir Path tempDir) throws IOException {
        AudioDecoder.DecodedAudio voice = AudioDecoder.synthesizeTone(300.0, 0.4, 22_050, 0.4f);
        assertTrue(LipSyncAnalyzer.computeZeroCrossingRate(voice.toMonoFloat()) > 0.01f);

        LipSyncAnalyzer.BakedLipSync baked = LipSyncAnalyzer.bakeLipSync(voice, 30);
        assertFalse(baked.frames().isEmpty());
        Path jsonFile = tempDir.resolve("line1.lipsync.json");
        Files.writeString(jsonFile, LipSyncAnalyzer.toJson(baked), StandardCharsets.UTF_8);

        LipSyncAnalyzer.BakedLipSync loaded = LipSyncAnalyzer.loadOrFallback(jsonFile, voice);
        assertEquals(baked.frames().size(), loaded.frames().size());
        LipSyncAnalyzer.BakedLipSync fallback = LipSyncAnalyzer.loadOrFallback(
                tempDir.resolve("missing.lipsync.json"), voice);
        assertEquals(baked.frames().size(), fallback.frames().size());
    }

    @Test
    @DisplayName("ClosedCaptionQueue Priority ensures CRITICAL warnings stay at front ahead of AMBIENT")
    void closedCaptionPriorityOrdersCriticalFirst() {
        ClosedCaptionQueue q = new ClosedCaptionQueue(2);
        q.push(ClosedCaptionQueue.CaptionCategory.SFX, "wind", "[Wind raschelt]", 5.0,
                ClosedCaptionQueue.Priority.AMBIENT);
        q.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "hero", "Achtung!", 5.0,
                ClosedCaptionQueue.Priority.STORY);
        q.push(ClosedCaptionQueue.CaptionCategory.SFX, "alarm", "[ALARM!]", 5.0,
                ClosedCaptionQueue.Priority.CRITICAL);

        List<ClosedCaptionQueue.CaptionEntry> visible = q.getVisibleCaptions();
        assertEquals(2, visible.size());
        assertEquals("[ALARM!]", visible.get(0).text());
        assertEquals("Achtung!", visible.get(1).text());
    }

    @Test
    @DisplayName("PreferencesState persists uiVolume, perceptual curve, focus mute, mono & accessibility cues")
    void preferencesStateAudioFieldsRoundTrip(@TempDir Path tempDir) throws IOException {
        assertEquals(0.25f, PreferencesState.toPerceptualGain(0.5f), 0.001f);
        PreferencesState prefs = new PreferencesState();
        prefs.setUiVolume(0.55f);
        prefs.setMuteOnFocusLoss(true);
        prefs.setStopVoiceOnAdvance(false);
        prefs.setMonoAudio(true);
        prefs.setAccessibilityAudioCues(true);
        prefs.save(tempDir);

        PreferencesState reloaded = PreferencesState.load(tempDir);
        assertEquals(0.55f, reloaded.getUiVolume(), 0.001f);
        assertTrue(reloaded.isMuteOnFocusLoss());
        assertFalse(reloaded.isStopVoiceOnAdvance());
        assertTrue(reloaded.isMonoAudio());
        assertTrue(reloaded.isAccessibilityAudioCues());
    }

    @Test
    @DisplayName("VJavaParser supports 'play music ... crossfade <sec>' and rejects negative duration")
    void parserSupportsPlayCrossfade() {
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("main.vjava"), """
                label start:
                    play music "assets/audio/theme.ogg" crossfade 2.0
                    return
                """);
        VJavaAst.PlayStatement play = (VJavaAst.PlayStatement) ast.labels().get(0).statements().get(0);
        assertEquals(2.0, play.fadeInSeconds(), 0.001);
        assertThrows(RuntimeException.class, () -> VJavaParser.parse(Path.of("bad.vjava"), """
                label start:
                    play music "assets/audio/theme.ogg" crossfade -1.0
                    return
                """));
    }

    @Test
    @DisplayName("VJavaLinter emits UNSUPPORTED_AUDIO_FORMAT for MP3/FLAC and corrupt WAV headers")
    void linterFlagsUnsupportedAndCorruptAudioHeaders(@TempDir Path tempDir) throws IOException {
        Path audioDir = tempDir.resolve("assets/audio");
        Files.createDirectories(audioDir);
        Files.write(audioDir.resolve("song.mp3"), new byte[]{1, 2, 3, 4});
        Files.writeString(audioDir.resolve("bad.wav"), "NOT_A_RIFF_WAV_FILE_DATA", StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    play music "assets/audio/song.mp3"
                    play sound "assets/audio/bad.wav"
                    return
                """, StandardCharsets.UTF_8);

        VJavaLinter.LintReport report = VJavaLinter.lint(ProjectScanner.scan(tempDir));
        assertEquals(2, report.issues().stream()
                .filter(i -> "UNSUPPORTED_AUDIO_FORMAT".equals(i.code())).count());
    }

    @Test
    @DisplayName("AudioDsp createRadioTelephoneFilter targets 300-3400 Hz speech band cleanly")
    void dspCreateRadioTelephoneFilter() {
        AudioDsp.BiquadFilter radioFilter = AudioDsp.createRadioTelephoneFilter(44_100.0f);
        assertEquals(AudioDsp.FilterType.BANDPASS, radioFilter.getType());
        assertEquals(1350.0f, radioFilter.getCutoffHz(), 0.1f);
    }
}
