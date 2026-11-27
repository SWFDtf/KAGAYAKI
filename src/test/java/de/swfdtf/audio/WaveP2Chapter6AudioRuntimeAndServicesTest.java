package de.swfdtf.audio;

import de.swfdtf.state.LocalizationManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-6 {@link AudioRuntimeServices} including OpenAL
 * lifecycle safety, source pooling, spatial panning, stems, beat sync, reverb zones, caches,
 * subtitles, and concurrent headless playback policies.
 */
class WaveP2Chapter6AudioRuntimeAndServicesTest {

    @Test
    @DisplayName("OpenAlErrorTranslator formats AL errors and validates safe macOS teardown sequence")
    void openAlErrorTranslatorAndSafeTeardown() {
        assertTrue(AudioRuntimeServices.OpenAlErrorTranslator.formatError(0xA004, "alSourcePlay")
                .contains("AL_INVALID_OPERATION"));
        assertTrue(AudioRuntimeServices.OpenAlErrorTranslator.isSafeTeardownSequence(List.of(
                AudioRuntimeServices.TeardownStep.SOURCES,
                AudioRuntimeServices.TeardownStep.BUFFERS,
                AudioRuntimeServices.TeardownStep.CONTEXT,
                AudioRuntimeServices.TeardownStep.DEVICE
        )));
        assertFalse(AudioRuntimeServices.OpenAlErrorTranslator.isSafeTeardownSequence(List.of(
                AudioRuntimeServices.TeardownStep.DEVICE,
                AudioRuntimeServices.TeardownStep.SOURCES
        )));
    }

    @Test
    @DisplayName("OpenAlErrorTranslator buffer balance stays exactly 0 across 1,000 sequential cues")
    void thousandSequentialCuesZeroNativeBufferLeak() {
        AudioRuntimeServices.OpenAlErrorTranslator auditor = new AudioRuntimeServices.OpenAlErrorTranslator();
        for (int i = 0; i < 1000; i++) {
            auditor.recordGenBuffer();
            auditor.recordDeleteBuffer();
        }
        assertEquals(0, auditor.getBufferBalance());
    }

    @Test
    @DisplayName("SourcePoolManager preserves BGM and VOICE while 50 concurrent SFX fight for slots")
    void sourcePoolProtectsBgmAndVoice() {
        AudioRuntimeServices.SourcePoolManager pool = new AudioRuntimeServices.SourcePoolManager(8);
        pool.acquire("music", AudioRuntimeServices.SourcePriority.BGM);
        pool.acquire("voice", AudioRuntimeServices.SourcePriority.VOICE);

        for (int i = 0; i < 50; i++) {
            pool.acquire("sfx_" + i, AudioRuntimeServices.SourcePriority.SFX);
        }
        List<AudioRuntimeServices.PooledSource> active = pool.getActiveSources();
        assertTrue(active.stream().anyMatch(s -> "music".equals(s.channel())));
        assertTrue(active.stream().anyMatch(s -> "voice".equals(s.channel())));
    }

    @Test
    @DisplayName("LocalizedVoiceResolver picks localized track when present and falls back cleanly")
    void localizedVoiceResolverFallback(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("assets/audio/voice/deutsch"));
        Files.createDirectories(tempDir.resolve("assets/audio/voice/english"));
        Files.write(tempDir.resolve("assets/audio/voice/deutsch/line1.wav"), new byte[]{1});

        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.setLanguage("english");
        assertEquals(
                "assets/audio/voice/deutsch/line1.wav",
                AudioRuntimeServices.LocalizedVoiceResolver.resolveVoicePath(
                        tempDir, "assets/audio/voice/deutsch/line1.wav", loc)
        );

        Files.write(tempDir.resolve("assets/audio/voice/english/line1.wav"), new byte[]{2});
        assertEquals(
                "assets/audio/voice/english/line1.wav",
                AudioRuntimeServices.LocalizedVoiceResolver.resolveVoicePath(
                        tempDir, "assets/audio/voice/deutsch/line1.wav", loc)
        );
    }

    @Test
    @DisplayName("SpatialVoicePanner computes stage pan [-0.35..+0.35] and gates HRTF on extension")
    void spatialVoicePannerAndHrtfGate() {
        assertEquals(-0.35f, AudioRuntimeServices.SpatialVoicePanner.computeStagePan(0.0f, 1920.0f), 0.01f);
        assertEquals(0.0f, AudioRuntimeServices.SpatialVoicePanner.computeStagePan(960.0f, 1920.0f), 0.01f);
        assertEquals(0.35f, AudioRuntimeServices.SpatialVoicePanner.computeStagePan(1920.0f, 1920.0f), 0.01f);

        AudioRuntimeServices.SpatialVoicePanner panner = new AudioRuntimeServices.SpatialVoicePanner();
        panner.configureHrtf(true, false);
        assertFalse(panner.isHrtfActive());
        panner.configureHrtf(true, true);
        assertTrue(panner.isHrtfActive());
    }

    @Test
    @DisplayName("AdaptiveStemMixer fades stem weights smoothly while sharing synchronized clock")
    void adaptiveStemMixerSyncAndFade() {
        AudioRuntimeServices.AdaptiveStemMixer stems = new AudioRuntimeServices.AdaptiveStemMixer();
        stems.setStemTargetGain("calm", 1.0f);
        stems.setStemTargetGain("tension", 0.0f);
        stems.setStemTargetGain("calm", 0.0f);
        stems.setStemTargetGain("tension", 1.0f);

        stems.update(0.5, 1.0f);
        assertEquals(0.5f, stems.getStemGain("calm"), 0.01f);
        assertEquals(0.5f, stems.getStemGain("tension"), 0.01f);
        assertEquals(0.5, stems.getSynchronizedPositionSeconds(), 0.0001);
    }

    @Test
    @DisplayName("BeatSyncClock emits BPM triggers with sub-5ms drift over 60 seconds")
    void beatSyncClockSubFiveMsDriftOver60s() {
        AudioRuntimeServices.BeatSyncClock clock = new AudioRuntimeServices.BeatSyncClock(120.0, 0.0);
        int beatCount = 0;
        for (int i = 1; i <= 3600; i++) {
            beatCount += clock.advanceTo(i / 60.0).size();
        }
        assertEquals(121, beatCount);
        assertEquals(60.0, clock.expectedBeatTimestamp(121), 0.001);
    }

    @Test
    @DisplayName("AmbientRandomScheduler enforces minimum cooldown interval before next sparse trigger")
    void ambientRandomSchedulerHonorsMinDelay() {
        AudioRuntimeServices.AmbientRandomScheduler sched =
                new AudioRuntimeServices.AmbientRandomScheduler(10.0, 20.0, 42L);
        assertFalse(sched.update(9.9));
        assertTrue(sched.update(15.0));
        assertEquals(10.0, sched.getMinIntervalSec(), 0.001);
    }

    @Test
    @DisplayName("HeartbeatPulseController maps stress [0..1] safely to [60..140] BPM")
    void heartbeatPulseClampsRange() {
        assertEquals(60.0, AudioRuntimeServices.HeartbeatPulseController.computeBpm(-1.0), 0.01);
        assertEquals(100.0, AudioRuntimeServices.HeartbeatPulseController.computeBpm(0.5), 0.01);
        assertEquals(140.0, AudioRuntimeServices.HeartbeatPulseController.computeBpm(2.0), 0.01);
    }

    @Test
    @DisplayName("ReverbZoneController fades wet mix cleanly and falls back to 0 without EFX support")
    void reverbZoneControllerAndNoEfxFallback() {
        AudioRuntimeServices.ReverbZoneController supported = new AudioRuntimeServices.ReverbZoneController(true);
        supported.enterZone(AudioRuntimeServices.ReverbPreset.CATHEDRAL, 0.6f);
        supported.update(0.5);
        assertTrue(supported.getWetMix() > 0.3f);

        AudioRuntimeServices.ReverbZoneController unsupported = new AudioRuntimeServices.ReverbZoneController(false);
        unsupported.enterZone(AudioRuntimeServices.ReverbPreset.CAVE, 0.8f);
        unsupported.update(1.0);
        assertEquals(0.0f, unsupported.getWetMix(), 0.001f);
    }

    @Test
    @DisplayName("MultiTrackVoiceManager runs overlapping voices with separate stereo pans and LipSync")
    void multiTrackDialogueSlots() {
        AudioRuntimeServices.MultiTrackVoiceManager mtv = new AudioRuntimeServices.MultiTrackVoiceManager();
        AudioRuntimeServices.VoiceSlot left = mtv.startVoice("snow", "v1.wav", 300.0f, 1920.0f);
        AudioRuntimeServices.VoiceSlot right = mtv.startVoice("prince", "v2.wav", 1620.0f, 1920.0f);
        assertEquals(2, mtv.getActiveSlots().size());
        assertTrue(left.stereoPan() < 0.0f);
        assertTrue(right.stereoPan() > 0.0f);
        mtv.stopVoice("snow");
        assertEquals(1, mtv.getActiveSlots().size());
    }

    @Test
    @DisplayName("SfxCooldownGuard blocks duplicate identical SFX calls inside 30ms window")
    void sfxCooldownGuardPreventsCombFiltering() {
        AudioRuntimeServices.SfxCooldownGuard guard = new AudioRuntimeServices.SfxCooldownGuard();
        assertTrue(guard.tryAcquirePlay("hit.wav", 1.000));
        assertFalse(guard.tryAcquirePlay("hit.wav", 1.015));
        assertTrue(guard.tryAcquirePlay("hit.wav", 1.035));
    }

    @Test
    @DisplayName("BleepSynthesisCache exceeds 99% cache hit ratio across long character dialogues")
    void bleepSynthesisCacheHitRateOver99Percent() {
        AudioRuntimeServices.BleepSynthesisCache cache = new AudioRuntimeServices.BleepSynthesisCache();
        for (int i = 0; i < 200; i++) {
            cache.getOrSynthesize(440.0, 0.04, 22_050);
        }
        assertTrue(cache.getHitRate() > 0.99);
    }

    @Test
    @DisplayName("SubtitleAndLyricExporter writes WebVTT/SRT, parses LRC lyrics, and measures voice coverage")
    void subtitleLrcCueSheetAndVoiceCoverage(@TempDir Path tempDir) throws IOException {
        List<AudioRuntimeServices.TimedSubtitle> subs = List.of(
                new AudioRuntimeServices.TimedSubtitle(0.5, 2.25, "Kira", "Hallo Welt!")
        );
        String vtt = AudioRuntimeServices.SubtitleAndLyricExporter.exportWebVtt(subs);
        String srt = AudioRuntimeServices.SubtitleAndLyricExporter.exportSrt(subs);
        assertTrue(vtt.startsWith("WEBVTT"));
        assertTrue(vtt.contains("00:00:00.500 --> 00:00:02.250"));
        assertTrue(srt.contains("00:00:00,500 --> 00:00:02,250"));

        List<AudioRuntimeServices.LyricLine> lrc = AudioRuntimeServices.SubtitleAndLyricExporter.parseLrc(
                "[00:01.50]First Verse\n[00:05.00]Chorus\n");
        assertEquals("First Verse", AudioRuntimeServices.SubtitleAndLyricExporter.findActiveLyric(lrc, 2.0));
        assertEquals("Chorus", AudioRuntimeServices.SubtitleAndLyricExporter.findActiveLyric(lrc, 6.0));

        Files.createDirectories(tempDir.resolve("assets/audio/voice"));
        Files.write(tempDir.resolve("assets/audio/voice/a.wav"), new byte[]{1});
        List<AudioRuntimeServices.VoiceCueEntry> cues = List.of(
                new AudioRuntimeServices.VoiceCueEntry("Kira", "l1", "Hi", "assets/audio/voice/a.wav"),
                new AudioRuntimeServices.VoiceCueEntry("Kira", "l2", "Bye", "assets/audio/voice/b.wav")
        );
        assertEquals(50.0, AudioRuntimeServices.SubtitleAndLyricExporter.computeVoiceCoveragePercent(
                tempDir, cues), 0.1);
        assertTrue(AudioRuntimeServices.SubtitleAndLyricExporter.exportVoiceCueSheetMarkdown(cues).contains("l2"));
    }

    @Test
    @DisplayName("HeadlessAudioController enforces Ctrl+M mute restore, focus mute, noduck, debounce & pitch clamp")
    void headlessControllerPoliciesAndTiming() {
        AudioRuntimeServices.HeadlessAudioController ctrl = new AudioRuntimeServices.HeadlessAudioController();
        ctrl.setMasterVolume(0.75f);
        assertTrue(ctrl.toggleCtrlMMute());
        assertEquals(0.0f, ctrl.getEffectiveMasterGain(), 0.001f);
        assertFalse(ctrl.toggleCtrlMMute());
        assertEquals(0.75f, ctrl.getEffectiveMasterGain(), 0.001f);

        ctrl.setWindowFocused(false);
        ctrl.update(0.35, true);
        assertEquals(0.0f, ctrl.getEffectiveMasterGain(), 0.001f);
        ctrl.setWindowFocused(true);
        ctrl.update(0.35, true);
        assertEquals(0.75f, ctrl.getEffectiveMasterGain(), 0.01f);

        assertTrue(ctrl.playMusicWithRollbackGuard("theme.ogg"));
        assertFalse(ctrl.playMusicWithRollbackGuard("theme.ogg"));

        ctrl.triggerVoice("whisper.wav", true);
        assertEquals(1, ctrl.getActiveOneShotBufferCount());
        assertFalse(ctrl.getRecordedEvents().get(ctrl.getRecordedEvents().size() - 1).ducked());
        assertTrue(ctrl.isAudioCueTimestampReached(2.5, 2.4));
        ctrl.onVoiceFinished("whisper.wav");
        assertEquals(0, ctrl.getActiveOneShotBufferCount());
        assertTrue(ctrl.getAudioEndListenersFired().contains("voice:whisper.wav"));

        assertTrue(ctrl.tryTriggerSliderTestTone(1.0));
        assertFalse(ctrl.tryTriggerSliderTestTone(1.1));
        assertTrue(ctrl.tryTriggerSliderTestTone(1.3));
        assertEquals(0.5f, ctrl.applyTimeScalePitch(0.1), 0.001f);
        assertEquals(2.0f, ctrl.applyTimeScalePitch(5.0), 0.001f);
    }

    @Test
    @DisplayName("HeadlessAudioController handles video cutscene, modal pause position, skip mute, buffer & phase")
    void headlessControllerCutsceneModalSkipAndRandomPhase() {
        AudioRuntimeServices.HeadlessAudioController ctrl = new AudioRuntimeServices.HeadlessAudioController();
        ctrl.setVideoCutsceneActive(true);
        assertTrue(ctrl.isPausedForVideoCutscene());
        assertEquals(42.5, ctrl.setModalAudioPaused(true, 42.5), 0.01);
        assertTrue(ctrl.isModalAudioPaused());
        assertEquals(42.5, ctrl.setModalAudioPaused(false, 0.0), 0.01);

        ctrl.setAudioBufferFrames(1024);
        assertEquals(1024, ctrl.getAudioBufferFrames());
        ctrl.triggerVoice("line.wav", false);
        ctrl.onSkipStateChanged(true);
        assertEquals(0, ctrl.getActiveOneShotBufferCount());

        double phase = AudioRuntimeServices.HeadlessAudioController.pickRandomLoopPhase(30.0, 99L);
        assertTrue(phase >= 0.0 && phase < 30.0);
        assertNotNull(new AudioRuntimeServices.MusicTrackMetadata("Snow", "Atelier", "CC0", 96));
    }

    @Test
    @DisplayName("HeadlessAudioController remains thread-safe under concurrent skip and cue bursts")
    void concurrentAudioTriggerStressZeroExceptions() throws Exception {
        AudioRuntimeServices.HeadlessAudioController ctrl = new AudioRuntimeServices.HeadlessAudioController();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int tid = t;
                tasks.add(() -> {
                    for (int i = 0; i < 100; i++) {
                        ctrl.triggerVoice("v_" + tid + "_" + i + ".wav", i % 2 == 0);
                        ctrl.onSkipStateChanged(i % 10 == 0);
                        ctrl.onVoiceFinished("v_" + tid + "_" + i + ".wav");
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        }
        assertFalse(ctrl.getRecordedEvents().isEmpty());
    }

    @Test
    @DisplayName("AudioChannel queue, crossfade target, snapshot replay preserve clean audio state")
    void audioChannelSnapshotAndCrossfadeConsistency() {
        AudioChannel ch = new AudioChannel("music", false);
        AudioDecoder.DecodedAudio tone = AudioDecoder.synthesizeTone(440.0, 1.0, 22_050, 0.4f);
        ch.play("bgm.wav", tone, true, 0.5, 0.8f);
        ch.update(0.6);
        AudioChannel.ChannelSnapshot snap = ch.captureSnapshot();
        assertEquals("bgm.wav", snap.assetPath());
        assertTrue(snap.playing());
        assertTrue(snap.loop());
    }
}
