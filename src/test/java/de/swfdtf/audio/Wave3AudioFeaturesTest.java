package de.swfdtf.audio;

import de.swfdtf.parser.ScriptRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive JUnit 6 and Mockito tests for Wave 3 audio and accessibility features:
 * {@link AudioDuckingController}, {@link AmbientCrossfadeMixer}, {@link ClosedCaptionQueue},
 * {@link AudioCueRegistry}, and {@link VoicePlaybackBookmark}.
 */
@ExtendWith(MockitoExtension.class)
class Wave3AudioFeaturesTest {

    // ---- 1. AudioDuckingController ----

    @Test
    void duckingControllerSmoothlyAttacksAndReleasesBetweenUnityAndDuckedGain() {
        AudioDuckingController controller = new AudioDuckingController();
        assertEquals(0.35f, AudioDuckingController.DEFAULT_DUCKED_GAIN, 1e-6f);
        assertEquals(0.12, AudioDuckingController.DEFAULT_ATTACK_SECONDS, 1e-9);
        assertEquals(0.45, AudioDuckingController.DEFAULT_RELEASE_SECONDS, 1e-9);
        assertFalse(controller.isVoiceActive());
        assertEquals(1.0f, controller.getDuckMultiplier(), 1e-6f);
        assertEquals(0.8f, controller.applyToGain(0.8f), 1e-6f);

        controller.setVoiceActive(true);
        assertTrue(controller.isVoiceActive());

        // Halfway through 0.12s attack: 1.0 - 0.5 * (1.0 - 0.35) = 0.675
        controller.update(0.06);
        assertEquals(0.675f, controller.getDuckMultiplier(), 1e-5f);
        assertEquals(0.54f, controller.applyToGain(0.8f), 1e-5f);

        // Complete attack
        controller.update(0.06);
        assertEquals(0.35f, controller.getDuckMultiplier(), 1e-5f);
        assertEquals(0.28f, controller.applyToGain(0.8f), 1e-5f);

        // Release halfway through 0.45s: 0.35 + 0.5 * 0.65 = 0.675
        controller.setVoiceActive(false);
        assertFalse(controller.isVoiceActive());
        controller.update(0.225);
        assertEquals(0.675f, controller.getDuckMultiplier(), 1e-5f);

        // Complete release and overshoot clamp check
        controller.update(0.50);
        assertEquals(1.0f, controller.getDuckMultiplier(), 1e-6f);
    }

    @Test
    void duckingControllerSupportsInstantTransitionsAndValidatesArguments() {
        AudioDuckingController instant = new AudioDuckingController(0.25f, 0.0, 0.0);
        instant.setVoiceActive(true);
        instant.update(0.0);
        assertEquals(0.25f, instant.getDuckMultiplier(), 1e-6f);

        instant.setVoiceActive(false);
        instant.update(0.0);
        assertEquals(1.0f, instant.getDuckMultiplier(), 1e-6f);

        assertThrows(IllegalArgumentException.class, () -> new AudioDuckingController(-0.1f, 0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new AudioDuckingController(1.1f, 0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new AudioDuckingController(Float.NaN, 0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new AudioDuckingController(0.5f, -0.01, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioDuckingController(0.5f, 0.1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> instant.update(-0.1));
        assertThrows(IllegalArgumentException.class, () -> instant.update(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> instant.applyToGain(-0.01f));
        assertThrows(IllegalArgumentException.class, () -> instant.applyToGain(Float.NaN));
    }

    @Test
    void duckingControllerDrivesMockedGainSinkDuringVoiceActivity() {
        @SuppressWarnings("unchecked")
        Consumer<Float> musicGainSink = mock(Consumer.class);
        AudioDuckingController controller = new AudioDuckingController(0.40f, 0.10, 0.20);

        controller.setVoiceActive(true);
        controller.update(0.05);
        musicGainSink.accept(controller.applyToGain(0.50f));
        controller.update(0.05);
        musicGainSink.accept(controller.applyToGain(0.50f));

        InOrder order = inOrder(musicGainSink);
        order.verify(musicGainSink).accept(0.35f);
        order.verify(musicGainSink).accept(0.20f);
        verify(musicGainSink, times(2)).accept(anyFloat());
    }

    // ---- 2. AmbientCrossfadeMixer ----

    @Test
    void ambientCrossfadeMixerFadesLayersAndCrossfadesPresetsSmoothly() {
        AmbientCrossfadeMixer mixer = new AmbientCrossfadeMixer(2.0);
        assertEquals(2.0, mixer.getFadeDurationSeconds(), 1e-9);

        mixer.addOrSetTarget("rain", 0.8f);
        assertEquals(Optional.of(0.0f), mixer.getWeight("rain"));

        mixer.update(1.0);
        assertEquals(0.4f, mixer.getWeight("rain").orElseThrow(), 1e-5f);

        mixer.update(1.0);
        assertEquals(0.8f, mixer.getWeight("rain").orElseThrow(), 1e-5f);

        // Apply new preset: "rain" is omitted so it fades down to 0 and is pruned; "forest" fades up
        mixer.applyPreset(Map.of("forest", 0.6f, "crickets", 0.3f));
        mixer.update(1.0);

        Map<String, Float> midCrossfade = mixer.getActiveWeights();
        assertEquals(0.4f, midCrossfade.get("rain"), 1e-5f);
        assertEquals(0.3f, midCrossfade.get("forest"), 1e-5f);
        assertEquals(0.15f, midCrossfade.get("crickets"), 1e-5f);
        assertThrows(UnsupportedOperationException.class, () -> midCrossfade.put("wind", 1.0f));

        // Finish crossfade -> rain is pruned once weight reaches 0.0
        mixer.update(1.0);
        assertTrue(mixer.getWeight("rain").isEmpty());
        assertEquals(0.6f, mixer.getWeight("forest").orElseThrow(), 1e-5f);
        assertEquals(0.3f, mixer.getWeight("crickets").orElseThrow(), 1e-5f);

        mixer.removeLayer("crickets");
        assertTrue(mixer.getWeight("crickets").isEmpty());
        assertEquals(1, mixer.getActiveWeights().size());

        mixer.clear();
        assertTrue(mixer.getActiveWeights().isEmpty());
    }

    @Test
    void ambientCrossfadeMixerSupportsZeroDurationAndValidatesInputs() {
        AmbientCrossfadeMixer mixer = new AmbientCrossfadeMixer();
        assertEquals(AmbientCrossfadeMixer.DEFAULT_FADE_DURATION_SECONDS, mixer.getFadeDurationSeconds(), 1e-9);

        mixer.setFadeDurationSeconds(0.0);
        mixer.addOrSetTarget("wind", 0.75f);
        mixer.update(0.0);
        assertEquals(0.75f, mixer.getWeight("wind").orElseThrow(), 1e-6f);

        assertThrows(IllegalArgumentException.class, () -> new AmbientCrossfadeMixer(-1.0));
        assertThrows(IllegalArgumentException.class, () -> mixer.setFadeDurationSeconds(Double.NaN));
        assertThrows(NullPointerException.class, () -> mixer.addOrSetTarget(null, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget("  ", 0.5f));
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget("wind", 1.2f));
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget("wind", -0.1f));
        assertThrows(IllegalArgumentException.class, () -> mixer.addOrSetTarget("wind", Float.NaN));
        assertThrows(NullPointerException.class, () -> mixer.applyPreset(null));

        Map<String, Float> nullWeightMap = new HashMap<>();
        nullWeightMap.put("rain", null);
        assertThrows(IllegalArgumentException.class, () -> mixer.applyPreset(nullWeightMap));
        assertThrows(IllegalArgumentException.class, () -> mixer.update(-0.5));
        assertThrows(IllegalArgumentException.class, () -> mixer.getWeight(""));
        assertThrows(IllegalArgumentException.class, () -> mixer.removeLayer(" "));
    }

    // ---- 3. ClosedCaptionQueue ----

    @Test
    void closedCaptionQueueEnforcesBoundedCapacityExpiresEntriesAndFiltersByCategory() {
        ClosedCaptionQueue queue = new ClosedCaptionQueue();
        assertEquals(ClosedCaptionQueue.DEFAULT_MAX_VISIBLE_ENTRIES, queue.getMaxVisibleEntries());

        ClosedCaptionQueue.CaptionEntry c1 = queue.push(
                ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "Hallo!", 1.0
        );
        ClosedCaptionQueue.CaptionEntry c2 = queue.push(
                ClosedCaptionQueue.CaptionCategory.SFX, "Door", "*knarrt*", 2.5
        );
        ClosedCaptionQueue.CaptionEntry c3 = queue.push(
                ClosedCaptionQueue.CaptionCategory.MUSIC, "BGM", "Sanftes Klavier", 4.0
        );
        ClosedCaptionQueue.CaptionEntry c4 = queue.push(
                ClosedCaptionQueue.CaptionCategory.SFX, "Rain", "*prasseln*", 1.5
        );
        assertNotNull(c1.id());
        assertEquals(4, queue.getVisibleCaptions().size());

        // Pushing a 5th entry evicts the oldest (c1)
        ClosedCaptionQueue.CaptionEntry c5 = queue.push(
                ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Kaito", "Komm rein.", 3.0
        );
        List<ClosedCaptionQueue.CaptionEntry> visible = queue.getVisibleCaptions();
        assertEquals(List.of(c2, c3, c4, c5), visible);
        assertThrows(UnsupportedOperationException.class, () -> visible.remove(0));

        // Category filtering
        List<ClosedCaptionQueue.CaptionEntry> sfxOnly = queue.filterByCategory(ClosedCaptionQueue.CaptionCategory.SFX);
        assertEquals(2, sfxOnly.size());
        assertEquals("Door", sfxOnly.get(0).source());
        assertEquals("Rain", sfxOnly.get(1).source());
        assertThrows(UnsupportedOperationException.class, sfxOnly::clear);

        // Advance 1.6s -> c4 (1.5s) expires, others remain with updated remainingSeconds
        queue.update(1.6);
        List<ClosedCaptionQueue.CaptionEntry> afterFirstTick = queue.getVisibleCaptions();
        assertEquals(3, afterFirstTick.size());
        assertEquals(0.9, afterFirstTick.get(0).remainingSeconds(), 1e-6);
        assertEquals(2.5, afterFirstTick.get(0).totalDurationSeconds(), 1e-6);
        assertEquals(2.4, afterFirstTick.get(1).remainingSeconds(), 1e-6);
        assertEquals(1.4, afterFirstTick.get(2).remainingSeconds(), 1e-6);

        // Shrinking maxVisibleEntries trims oldest immediately
        queue.setMaxVisibleEntries(2);
        assertEquals(2, queue.getVisibleCaptions().size());

        queue.clear();
        assertTrue(queue.getVisibleCaptions().isEmpty());
    }

    @Test
    void closedCaptionQueueValidatesInputs() {
        ClosedCaptionQueue queue = new ClosedCaptionQueue(2);
        assertThrows(IllegalArgumentException.class, () -> new ClosedCaptionQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new ClosedCaptionQueue(-3));
        assertThrows(IllegalArgumentException.class, () -> queue.setMaxVisibleEntries(0));

        assertThrows(NullPointerException.class, () -> queue.push(null, "Lena", "Text", 1.0));
        assertThrows(NullPointerException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, null, "Text", 1.0));
        assertThrows(NullPointerException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "   ", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "Hi", 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "Hi", -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "Hi", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> queue.update(-0.1));
        assertThrows(NullPointerException.class, () -> queue.filterByCategory(null));
    }

    // ---- 4. AudioCueRegistry ----

    @Test
    void audioCueRegistryRegistersQueriesSerializesAndLoadsAtomically() {
        AudioCueRegistry registry = new AudioCueRegistry();
        AudioCueRegistry.AudioCue doorCue = new AudioCueRegistry.AudioCue(
                "sfx.door_open", "assets/audio/door.wav", "sound", 0.85f, false, 0.05f
        );
        AudioCueRegistry.AudioCue themeCue = new AudioCueRegistry.AudioCue(
                "bgm.title", "assets/audio/title.ogg", "music", 0.65f, true, 0.0f
        );
        AudioCueRegistry.AudioCue stepCue = new AudioCueRegistry.AudioCue(
                "sfx.footstep", "assets/audio/step.wav", "SOUND", 0.50f, false, 0.10f
        );

        registry.register(doorCue);
        registry.register(themeCue);
        registry.register(stepCue);

        assertEquals(Optional.of(doorCue), registry.findCue("sfx.door_open"));
        assertTrue(registry.findCue("missing").isEmpty());

        List<AudioCueRegistry.AudioCue> soundCues = registry.listByChannel("sound");
        assertEquals(List.of(doorCue, stepCue), soundCues);
        assertThrows(UnsupportedOperationException.class, soundCues::clear);

        Map<String, AudioCueRegistry.AudioCue> snap = registry.snapshot();
        assertEquals(3, snap.size());
        assertThrows(UnsupportedOperationException.class, snap::clear);

        String json = registry.toJson();
        assertTrue(json.contains("sfx.door_open"));
        assertTrue(json.contains("bgm.title"));

        AudioCueRegistry reloaded = new AudioCueRegistry();
        reloaded.loadJson(json);
        assertEquals(snap, reloaded.snapshot());

        // Atomic failure check: invalid JSON preserves existing cues
        assertThrows(IllegalArgumentException.class, () -> reloaded.loadJson("{not valid json"));
        assertThrows(IllegalArgumentException.class, () -> reloaded.loadJson("{\"cues\": null}"));
        assertEquals(3, reloaded.snapshot().size());

        assertTrue(reloaded.unregister("sfx.door_open"));
        assertFalse(reloaded.unregister("sfx.door_open"));
        assertEquals(2, reloaded.snapshot().size());
    }

    @Test
    void audioCueRegistryPlaysCueThroughMockedAudioBridgeAndValidatesInputs() {
        AudioCueRegistry registry = new AudioCueRegistry();
        AudioCueRegistry.AudioCue rainCue = new AudioCueRegistry.AudioCue(
                "amb.rain", "assets/audio/rain.ogg", "ambient", 0.55f, true, 0.0f
        );
        registry.register(rainCue);

        ScriptRuntime.AudioBridge bridge = mock(ScriptRuntime.AudioBridge.class);
        AudioCueRegistry.AudioCue cue = registry.findCue("amb.rain").orElseThrow();
        bridge.play(cue.channel(), cue.assetPath(), cue.loop(), 1.5, cue.defaultVolume());

        verify(bridge).play("ambient", "assets/audio/rain.ogg", true, 1.5, 0.55f);

        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.findCue(""));
        assertThrows(IllegalArgumentException.class, () -> registry.listByChannel("  "));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioCueRegistry.AudioCue("id", "path", "sound", 1.5f, false, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioCueRegistry.AudioCue("id", "path", "sound", 0.5f, false, -0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioCueRegistry.AudioCue(" ", "path", "sound", 0.5f, false, 0.0f));
    }

    // ---- 5. VoicePlaybackBookmark ----

    @Test
    void voicePlaybackBookmarkMaintainsBoundedHistoryAndQueries() {
        VoicePlaybackBookmark defaultBookmark = new VoicePlaybackBookmark();
        assertEquals(VoicePlaybackBookmark.DEFAULT_MAX_HISTORY, defaultBookmark.getMaxHistory());
        assertTrue(defaultBookmark.findLatest().isEmpty());

        VoicePlaybackBookmark bookmark = new VoicePlaybackBookmark(3);
        VoicePlaybackBookmark.VoiceClipRecord r1 = bookmark.record("line_01", "lena", "assets/voice/l01.wav");
        VoicePlaybackBookmark.VoiceClipRecord r2 = bookmark.record("line_02", "kaito", "assets/voice/k02.wav");
        VoicePlaybackBookmark.VoiceClipRecord r3 = bookmark.record("line_03", "lena", "assets/voice/l03.wav");
        assertNotNull(r1.playedAt());

        // Recording 4th evicts r1
        VoicePlaybackBookmark.VoiceClipRecord r4 = bookmark.record("line_02", "kaito", "assets/voice/k02_alt.wav");
        List<VoicePlaybackBookmark.VoiceClipRecord> all = bookmark.listAll();
        assertEquals(List.of(r2, r3, r4), all);
        assertThrows(UnsupportedOperationException.class, all::clear);

        assertEquals(Optional.of(r4), bookmark.findLatest());
        // Duplicate line_02 resolves to newest occurrence (r4)
        assertEquals(Optional.of(r4), bookmark.findByLineId("line_02"));
        assertTrue(bookmark.findByLineId("line_01").isEmpty());

        List<VoicePlaybackBookmark.VoiceClipRecord> kaitoLines = bookmark.listBySpeaker("kaito");
        assertEquals(List.of(r2, r4), kaitoLines);
        assertThrows(UnsupportedOperationException.class, kaitoLines::clear);

        bookmark.clear();
        assertTrue(bookmark.findLatest().isEmpty());
        assertTrue(bookmark.listAll().isEmpty());
    }

    @Test
    void voicePlaybackBookmarkSupportsMockedReplayAndValidatesInputs() {
        @SuppressWarnings("unchecked")
        Optional<VoicePlaybackBookmark.VoiceClipRecord> stubbed = Optional.of(
                new VoicePlaybackBookmark.VoiceClipRecord(
                        "line_99", "lena", "assets/voice/lena_99.ogg", Instant.parse("2026-11-24T12:00:00Z")
                )
        );
        VoicePlaybackBookmark mockBookmark = mock(VoicePlaybackBookmark.class);
        when(mockBookmark.findByLineId("line_99")).thenReturn(stubbed);

        ScriptRuntime.AudioBridge bridge = mock(ScriptRuntime.AudioBridge.class);
        mockBookmark.findByLineId("line_99").ifPresent(clip -> bridge.voice(clip.assetPath()));

        verify(mockBookmark).findByLineId("line_99");
        verify(bridge).voice("assets/voice/lena_99.ogg");

        VoicePlaybackBookmark real = new VoicePlaybackBookmark();
        assertThrows(IllegalArgumentException.class, () -> new VoicePlaybackBookmark(0));
        assertThrows(IllegalArgumentException.class, () -> new VoicePlaybackBookmark(-5));
        assertThrows(NullPointerException.class, () -> real.record(null, "lena", "a.wav"));
        assertThrows(IllegalArgumentException.class, () -> real.record("  ", "lena", "a.wav"));
        assertThrows(IllegalArgumentException.class, () -> real.record("l1", "", "a.wav"));
        assertThrows(IllegalArgumentException.class, () -> real.record("l1", "lena", " "));
        assertThrows(IllegalArgumentException.class, () -> real.findByLineId(" "));
        assertThrows(IllegalArgumentException.class, () -> real.listBySpeaker(" "));
    }
}
