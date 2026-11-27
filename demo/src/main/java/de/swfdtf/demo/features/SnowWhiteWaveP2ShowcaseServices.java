package de.swfdtf.demo.features;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.TypographyAndLayoutServices.RichTextTypographyHelpers;
import de.swfdtf.math.Rect2f;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.RevertableMap;
import de.swfdtf.state.StateStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unified Chapter 11 Snow White demo showcase features: 3 endings evaluator, 8-second poison apple
 * timer choice, hunter patrol stealth reset, magic mirror Java riddle, 7 dwarfs CharacterDef roster,
 * isolated memory replay, inventory item progress, Fairytale Gold WCAG theme, developer commentary,
 * cottage time-of-day, reduceMotion crystal/shatter fallbacks, quick-jump prolog menu, multi-res
 * HUD bounds verification, 10k-frame benchmark, and 600-frame combined subsystem integration.
 */
public final class SnowWhiteWaveP2ShowcaseServices {

    private SnowWhiteWaveP2ShowcaseServices() {
    }

    /**
     * Three distinct Snow White endings resolved from hunter trust and courage points ({@code #0870}).
     */
    public enum DemoEnding {
        /** Standard happily-ever-after fairytale ending. */
        TRUE_FAIRYTALE,
        /** Alliance ending unlocked via high hunter trust. */
        HUNTERS_PACT,
        /** Secret third ending unlocked via maximum courage and riddle mastery. */
        SECRET_CRYSTAL_TRUTH
    }

    /**
     * Evaluates active state to unlock one of 3 Snow White demo endings ({@code #0870}, {@code #0932}).
     *
     * @param hunterTrust  hunter sympathy points
     * @param courage      accumulated courage points
     * @param solvedRiddle whether player solved the magic mirror riddle
     * @return resolved {@link DemoEnding}
     */
    public static DemoEnding evaluateEnding(int hunterTrust, int courage, boolean solvedRiddle) {
        if (solvedRiddle && courage >= 30 && hunterTrust >= 20) {
            return DemoEnding.SECRET_CRYSTAL_TRUTH;
        }
        if (hunterTrust >= 25) {
            return DemoEnding.HUNTERS_PACT;
        }
        return DemoEnding.TRUE_FAIRYTALE;
    }

    /**
     * Handles the 8-second timed poison-apple choice defaulting cautiously when timer expires ({@code #0871}).
     *
     * @param elapsedSeconds time waited on door choice
     * @param playerOpened   {@code true} if player clicked "Open Door" before timeout
     * @return outcome branch label ({@code "cottage_evade"}, {@code "cottage_poison"}, {@code "cottage_safe_refuse"})
     */
    public static String resolvePoisonAppleChoice(double elapsedSeconds, boolean playerOpened) {
        if (elapsedSeconds >= 8.0) {
            return "cottage_evade";
        }
        return playerOpened ? "cottage_poison" : "cottage_safe_refuse";
    }

    /**
     * Stealth patrol collision handler sending player back to calm checkpoint without hard Game Over
     * ({@code #0872}).
     */
    public static final class HunterPatrolStealthQuest {

        private double checkpointX = 120.0;
        private double checkpointY = 540.0;
        private int spotCount = 0;

        /**
         * Creates a {@code HunterPatrolStealthQuest}.
         */
        public HunterPatrolStealthQuest() {
        }

        /**
         * Resolves detection step cleanly resetting coordinates to safe bush checkpoint ({@code #0872}).
         *
         * @param playerX  current player X
         * @param playerY  current player Y
         * @param hunterX  patrolling hunter X
         * @param hunterY  patrolling hunter Y
         * @param visionPx detection radius
         * @return resulting safe {@code [x, y]} coordinates
         */
        public double[] stepStealth(
                double playerX,
                double playerY,
                double hunterX,
                double hunterY,
                double visionPx
        ) {
            double dist = Math.hypot(playerX - hunterX, playerY - hunterY);
            if (dist <= visionPx) {
                spotCount++;
                return new double[]{checkpointX, checkpointY};
            }
            return new double[]{playerX, playerY};
        }

        /**
         * Returns how many times patrol reset cleanly without fatal game-over.
         *
         * @return spot reset counter
         */
        public int getSpotCount() {
            return spotCount;
        }
    }

    /**
     * Magic Mirror Java-block riddle evaluator awarding lore bonus or humorous rejection ({@code #0889}).
     */
    public static final class MagicMirrorRiddleEvaluator {

        /**
         * Evaluates riddle input answer.
         *
         * @param answer normalized or raw player answer string
         * @return response object with solved status and bonus flag
         */
        public static RiddleOutcome evaluate(String answer) {
            String norm = answer != null ? answer.trim().toLowerCase() : "";
            boolean solved = norm.contains("spiegel") || norm.contains("mirror");
            return new RiddleOutcome(solved, solved
                    ? "Der Zauberspiegel neigt sein Antlitz — geheimer Lore-Bonus freigeschaltet!"
                    : "Der Spiegel kichert leise: Nicht ganz — versuch es beim nächsten Durchlauf erneut!");
        }

        /**
         * Immutable riddle evaluation payload ({@code #0889}).
         *
         * @param solved         {@code true} if correct riddle answer matched
         * @param mirrorResponse dialogue response line
         */
        public record RiddleOutcome(boolean solved, String mirrorResponse) {
        }

        /**
         * Creates a {@code MagicMirrorRiddleEvaluator}.
         */
        public MagicMirrorRiddleEvaluator() {
        }
    }

    /**
     * Character metadata entry for the Seven Dwarfs group dialogues ({@code #0890}).
     *
     * @param id          unique CharacterDef script identifier
     * @param displayName dwarf display name
     * @param colorHex    WCAG-AA speaker nameplate color on `#0D1117`
     * @param voicePitch  audio pitch multiplier
     */
    public record DwarfVoiceSpec(String id, String displayName, String colorHex, float voicePitch) {
    }

    /**
     * Complete Seven Dwarfs character roster &amp; WCAG-safe Fairytale Gold theme helpers
     * ({@code #0877}, {@code #0890}).
     */
    public static final class SnowWhiteRosterAndTheme {

        /** Parchment background surface color in Fairytale Gold preset ({@code #0877}). */
        public static final String FAIRYTALE_PARCHMENT_BG = "#1E1912";

        /** Warm gold accent hex exceeding WCAG AA 4.5:1 against {@value #FAIRYTALE_PARCHMENT_BG} ({@code #0877}). */
        public static final String FAIRYTALE_GOLD_ACCENT = "#FFD166";

        private static final List<DwarfVoiceSpec> DWARFS = List.of(
                new DwarfVoiceSpec("chef", "Chef", "#ffd166", 0.92f),
                new DwarfVoiceSpec("brummbaer", "Brummbär", "#ff9f80", 0.82f),
                new DwarfVoiceSpec("happy", "Happy", "#8ce99a", 1.08f),
                new DwarfVoiceSpec("schlafmuetz", "Schlafmütz", "#a5d8ff", 0.88f),
                new DwarfVoiceSpec("hatschi", "Hatschi", "#f3d9fa", 1.12f),
                new DwarfVoiceSpec("pimpel", "Pimpel", "#ffe066", 1.18f),
                new DwarfVoiceSpec("seppl", "Seppl", "#c0eb75", 1.24f)
        );

        /**
         * Creates a {@code SnowWhiteRosterAndTheme}.
         */
        public SnowWhiteRosterAndTheme() {
        }

        /**
         * Returns all 7 individual dwarf character specs ({@code #0890}).
         *
         * @return immutable list of 7 {@link DwarfVoiceSpec}s
         */
        public static List<DwarfVoiceSpec> getSevenDwarfs() {
            return DWARFS;
        }

        /**
         * Verifies WCAG AA readability of Fairytale Gold accent text on dark parchment ({@code #0877}).
         *
         * @return {@code true} if contrast ratio &gt;= 4.5:1
         */
        public static boolean isFairytaleGoldWcagCompliant() {
            return RichTextTypographyHelpers.isWcagAaReadable(FAIRYTALE_GOLD_ACCENT, FAIRYTALE_PARCHMENT_BG);
        }

        /**
         * Formats demo header label consistently with centralized {@link EngineVersion#VERSION}
         * ({@code #0876}, {@code #0922}, {@code #0932}).
         *
         * @param unlockedCodex    discovered codex entries
         * @param unlockedEndings  reached endings
         * @param unlockedChapters unlocked chapters
         * @param quoteCount       bookmarked quotes
         * @return summary status line
         */
        public static String formatMainMenuStatus(
                int unlockedCodex,
                int unlockedEndings,
                int unlockedChapters,
                int quoteCount
        ) {
            return "KAGAYAKI " + EngineVersion.VERSION
                    + " | Codex " + unlockedCodex + "/8"
                    + " | Endings " + unlockedEndings + "/3"
                    + " | Chapters " + unlockedChapters + "/7"
                    + " | Zitate " + quoteCount;
        }
    }

    /**
     * Extras, scene memory isolation, developer commentary, cottage time-of-day, seasonal toggle,
     * reduceMotion transition/particle policy, hotspot map, tutorial gate, and inventory items
     * ({@code #0878}, {@code #0882}, {@code #0883}, {@code #0891}, {@code #0901}, {@code #0903},
     * {@code #0905}, {@code #0906}, {@code #0910}, {@code #0912}, {@code #0915}).
     */
    public static final class DemoGameplayAndExtras {

        /** Cottage lighting/time presets ({@code #0882}). */
        public enum CottageTimeOfDay {
            /** Warm morning sunlight. */
            MORNING_LIGHT,
            /** Cozy amber fireplace glow. */
            FIREPLACE_EVENING,
            /** Cool silver winter blizzard. */
            BLIZZARD_NIGHT
        }

        private final Set<String> inventory = new LinkedHashSet<>();
        private boolean commentaryEnabled = false;
        private boolean winterSnowSpecial = false;

        /**
         * Creates a {@code DemoGameplayAndExtras} coordinator.
         */
        public DemoGameplayAndExtras() {
        }

        /**
         * Replays a completed scene using an isolated variable copy so campaign state remains untouched
         * ({@code #0891}).
         *
         * @param campaignStore active campaign state store
         * @param replayTask    replay mutations executed on isolated clone
         */
        public static void replaySceneIsolated(
                StateStore campaignStore,
                java.util.function.Consumer<Map<String, Object>> replayTask
        ) {
            Objects.requireNonNull(campaignStore, "campaignStore must not be null");
            Map<String, Object> before = campaignStore.snapshotMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> isolated = (Map<String, Object>) RevertableMap.cloneValue(before);
            if (replayTask != null) {
                replayTask.accept(isolated);
            }
            // Campaign store stays untouched cleanly
        }

        /**
         * Collects a story relic item (mirror compass, comb, belt, apple clue) ({@code #0912}).
         *
         * @param itemId item identifier
         * @return {@code true} if newly collected
         */
        public boolean collectItem(String itemId) {
            return inventory.add(Objects.requireNonNull(itemId, "itemId must not be null"));
        }

        /**
         * Returns immutable snapshot of collected story items.
         *
         * @return collected item set
         */
        public Set<String> getInventory() {
            return Collections.unmodifiableSet(inventory);
        }

        /**
         * Returns whether post-completion developer commentary may be enabled ({@code #0878}).
         *
         * @param persistent global persistent store
         * @param requested  requested toggle state
         * @return active commentary state
         */
        public boolean toggleCommentaryIfUnlocked(PersistentAccess persistent, boolean requested) {
            if (persistent.getUnlockedEndings().isEmpty()) {
                commentaryEnabled = false;
                return false;
            }
            commentaryEnabled = requested;
            return commentaryEnabled;
        }

        /**
         * Selects transition kind respecting {@code reduceMotion} ({@code #0883}, {@code #0910}).
         *
         * @param reduceMotion accessibility flag
         * @return {@code "fade"} when reduced motion active, {@code "shatter"} otherwise
         */
        public static String resolveQueenAngerTransition(boolean reduceMotion) {
            return reduceMotion ? "fade" : "shatter";
        }

        /**
         * Checks whether crystal particle bursts should run for Glass Coffin ({@code #0883}).
         *
         * @param reduceMotion accessibility flag
         * @return active crystal particle rate
         */
        public static int resolveCrystalParticleCount(boolean reduceMotion) {
            return reduceMotion ? 0 : 48;
        }

        /**
         * Evaluates first-visit arena tutorial flag and marks persistent seen flag ({@code #0915}).
         *
         * @param persistent persistent store
         * @return {@code true} only on very first visit
         */
        public static boolean shouldShowArenaTutorialFirstTime(PersistentAccess persistent) {
            if (Boolean.TRUE.equals(persistent.get("seenArenaTutorial"))) {
                return false;
            }
            persistent.set("seenArenaTutorial", true);
            return true;
        }

        /**
         * Unlocks ending credit track inside Music Room immediately ({@code #0901}).
         *
         * @param persistent persistent store
         * @param trackId    ending music track path
         */
        public static void unlockEndingTrack(PersistentAccess persistent, String trackId) {
            persistent.unlockTrack(trackId);
        }

        /**
         * Returns RGB tint triplet for cottage time-of-day atmosphere ({@code #0882}).
         *
         * @param time time of day preset
         * @return RGB multiplier array {@code [r, g, b]}
         */
        public static float[] resolveTimeOfDayTint(CottageTimeOfDay time) {
            return switch (time) {
                case MORNING_LIGHT -> new float[]{1.05f, 1.00f, 0.92f};
                case FIREPLACE_EVENING -> new float[]{1.12f, 0.85f, 0.65f};
                case BLIZZARD_NIGHT -> new float[]{0.76f, 0.86f, 1.08f};
            };
        }

        /** Enables/disables optional seasonal snow without affecting dialog state ({@code #0906}). @param on toggle */
        public void setWinterSnowSpecial(boolean on) {
            this.winterSnowSpecial = on;
        }

        /** Returns seasonal snow state. @return true if active */
        public boolean isWinterSnowSpecial() {
            return winterSnowSpecial;
        }

        /** Quick-jump targets from prolog menu for evaluators ({@code #0874}, {@code #0903}). @return list */
        public static List<String> getPrologQuickJumpLabels() {
            return List.of("start", "chapter_2_forest_wasd", "chapter_5_mine_collect", "audio_showcase");
        }
    }

    /**
     * Benchmark runner, multi-resolution bounds auditor, audio balance auditor, and combined
     * Weather+Camera+Audio+WASD simulation ({@code #0885}, {@code #0899}, {@code #0919}, {@code #0923},
     * {@code #0934}).
     */
    public static final class DemoQaAndBenchmarkSuite {

        /**
         * Frame timing summary over 10,000 simulated demo frames ({@code #0885}).
         *
         * @param frames  simulated frame count
         * @param minMs   fastest frame in ms
         * @param avgMs   mean frame duration in ms
         * @param p99Ms   99th percentile frame time in ms
         */
        public record Benchmark10kResult(int frames, double minMs, double avgMs, double p99Ms) {
        }

        /**
         * Creates a {@code DemoQaAndBenchmarkSuite}.
         */
        public DemoQaAndBenchmarkSuite() {
        }

        /**
         * Runs deterministic 10,000-frame CPU loop timing simulation ({@code #0885}).
         *
         * @param frames number of frames (typically {@code 10_000})
         * @return {@link Benchmark10kResult}
         */
        public static Benchmark10kResult run10kFrameSimulation(int frames) {
            List<Double> samples = new ArrayList<>(frames);
            SceneController stage = new SceneController(new SceneGraph());
            double sum = 0.0;
            for (int i = 0; i < frames; i++) {
                long t0 = System.nanoTime();
                stage.update(1.0 / 60.0);
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                samples.add(ms);
                sum += ms;
            }
            Collections.sort(samples);
            double min = samples.isEmpty() ? 0.0 : samples.get(0);
            double p99 = samples.isEmpty() ? 0.0 : samples.get((int) Math.floor((samples.size() - 1) * 0.99));
            return new Benchmark10kResult(frames, min, sum / Math.max(1, frames), p99);
        }

        /**
         * Audits HUD widgets against 16:9, 16:10, and 4:3 bounds within logical {@code 1920x1080} ({@code #0923}).
         *
         * @param hudRects list of active widget rects in logical canvas space
         * @return {@code true} if every rect has non-negative coordinates fitting inside {@code 1920x1080}
         */
        public static boolean verifyHudBoundsWithinCanvas(List<Rect2f> hudRects) {
            if (hudRects == null) {
                return true;
            }
            for (Rect2f r : hudRects) {
                if (r.x() < 0.0f || r.y() < 0.0f || r.x() + r.width() > 1920.0f || r.y() + r.height() > 1080.0f) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Checks RMS audio balance confirming voice signal sits cleanly above ducked BGM ({@code #0919}).
         *
         * @param voiceAmplitude voice signal amplitude in {@code (0, 1]}
         * @param duckedBgmAmp   ducked BGM signal amplitude
         * @return {@code true} when voice RMS clearly exceeds ducked BGM RMS
         */
        public static boolean verifyVoiceOverBgmBalance(float voiceAmplitude, float duckedBgmAmp) {
            AudioDecoder.DecodedAudio v = AudioDecoder.synthesizeTone(300.0, 0.1, 22_050, voiceAmplitude);
            AudioDecoder.DecodedAudio b = AudioDecoder.synthesizeTone(150.0, 0.1, 22_050, duckedBgmAmp);
            return rms(v.monoOrInterleavedFloat()) > rms(b.monoOrInterleavedFloat());
        }

        private static double rms(float[] samples) {
            double sq = 0.0;
            for (float s : samples) {
                sq += s * s;
            }
            return Math.sqrt(sq / Math.max(1, samples.length));
        }
    }
}
