package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Headless automated QA runner that executes a KAGAYAKI {@code .vjava} project through
 * {@link ScriptRuntime} without opening a window, automatically advancing dialogue,
 * selecting menu choices, submitting text inputs, and advancing timed pauses.
 */
public final class AutomatedPlaythroughRunner {

    private static final String DEFAULT_INPUT_FALLBACK = "Player";
    private static final double PAUSE_STEP_EPSILON_SECONDS = 0.001;

    /**
     * Strategy used to select an enabled choice when encountering {@link ScriptRuntime.Status#WAITING_MENU}.
     */
    public enum ChoiceStrategy {
        /** Always selects the first visible and enabled menu option. */
        FIRST_CHOICE,
        /** Always selects the last visible and enabled menu option. */
        LAST_CHOICE,
        /** Cycles across visible and enabled options based on cumulative choices taken. */
        ROUND_ROBIN
    }

    /**
     * Summary report produced by an automated headless playthrough.
     *
     * @param endedCleanly         {@code true} if execution reached {@link ScriptRuntime.Status#ENDED}
     * @param stepsExecuted        number of interaction steps executed during the run
     * @param dialogueCount        total number of dialogue prompts advanced
     * @param choicesTaken         total number of menu choices selected
     * @param visitedLabels        unmodifiable set of distinct script labels visited during execution
     * @param labelCoveragePercent percentage of project labels visited ({@code 0.0..100.0})
     * @param terminationReason    optional explanation when the playthrough did not end cleanly
     */
    public record PlaythroughReport(
            boolean endedCleanly,
            int stepsExecuted,
            int dialogueCount,
            int choicesTaken,
            Set<String> visitedLabels,
            double labelCoveragePercent,
            Optional<String> terminationReason
    ) {
        public PlaythroughReport {
            if (stepsExecuted < 0) {
                throw new IllegalArgumentException("stepsExecuted must be >= 0, got " + stepsExecuted);
            }
            if (dialogueCount < 0) {
                throw new IllegalArgumentException("dialogueCount must be >= 0, got " + dialogueCount);
            }
            if (choicesTaken < 0) {
                throw new IllegalArgumentException("choicesTaken must be >= 0, got " + choicesTaken);
            }
            if (!Double.isFinite(labelCoveragePercent) || labelCoveragePercent < 0.0) {
                throw new IllegalArgumentException("labelCoveragePercent must be finite and >= 0.0");
            }
            visitedLabels = visitedLabels != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(visitedLabels))
                    : Set.of();
            terminationReason = terminationReason != null ? terminationReason : Optional.empty();
        }
    }

    /**
     * Creates a new {@code AutomatedPlaythroughRunner}.
     */
    public AutomatedPlaythroughRunner() {
    }

    /**
     * Runs an automated headless playthrough against a scanned {@link VJavaAst.ProjectAst}.
     *
     * @param project  parsed project AST (must not be {@code null})
     * @param strategy choice selection strategy (must not be {@code null})
     * @param maxSteps maximum interaction steps before aborting ({@code > 0})
     * @return immutable {@link PlaythroughReport} summarizing execution and label coverage
     */
    public PlaythroughReport run(VJavaAst.ProjectAst project, ChoiceStrategy strategy, int maxSteps) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got " + maxSteps);
        }

        EngineConfig config = EngineConfig.builder()
                .projectDir(project.projectRoot())
                .headless(true)
                .build();
        EngineAPI engine = new EngineAPI(config);
        engine.getLogger().setConsoleOutputEnabled(false);

        ScriptRuntime runtime = new ScriptRuntime(project, engine);
        Set<String> visitedLabels = new LinkedHashSet<>();
        runtime.setCheckpointListener(() -> recordCurrentLabel(runtime, visitedLabels));

        int stepsExecuted = 0;
        int dialogueCount = 0;
        int choicesTaken = 0;

        try {
            runtime.start();
            recordCurrentLabel(runtime, visitedLabels);

            while (runtime.getStatus() != ScriptRuntime.Status.ENDED && stepsExecuted < maxSteps) {
                recordCurrentLabel(runtime, visitedLabels);
                ScriptRuntime.Status status = runtime.getStatus();

                switch (status) {
                    case WAITING_DIALOGUE -> {
                        dialogueCount++;
                        stepsExecuted++;
                        runtime.advance();
                    }
                    case WAITING_MENU -> {
                        ScriptRuntime.MenuPrompt menu = runtime.getActiveMenu();
                        List<ScriptRuntime.ChoiceOption> selectable = menu != null
                                ? menu.options().stream()
                                        .filter(opt -> opt.visible() && opt.enabled())
                                        .toList()
                                : List.of();
                        if (selectable.isEmpty()) {
                            double coverage = computeCoverage(visitedLabels.size(), project.labels().size());
                            String labelInfo = runtime.getCurrentLabel() != null
                                    ? runtime.getCurrentLabel()
                                    : "<unknown>";
                            return new PlaythroughReport(
                                    false,
                                    stepsExecuted,
                                    dialogueCount,
                                    choicesTaken,
                                    visitedLabels,
                                    coverage,
                                    Optional.of("No enabled visible choices in menu at label '" + labelInfo + "'")
                            );
                        }
                        ScriptRuntime.ChoiceOption selected = selectChoice(selectable, strategy, choicesTaken);
                        choicesTaken++;
                        stepsExecuted++;
                        runtime.choose(selected.index());
                    }
                    case WAITING_INPUT -> {
                        ScriptRuntime.InputPrompt input = runtime.getActiveInput();
                        String value = (input != null
                                && input.defaultValue() != null
                                && !input.defaultValue().isBlank())
                                ? input.defaultValue()
                                : DEFAULT_INPUT_FALLBACK;
                        stepsExecuted++;
                        runtime.submitInput(value);
                    }
                    case PAUSING -> {
                        double remaining = runtime.getPauseRemainingSeconds();
                        stepsExecuted++;
                        if (remaining > 0.0) {
                            runtime.update(remaining + PAUSE_STEP_EPSILON_SECONDS);
                        } else {
                            runtime.advance();
                        }
                    }
                    case READY -> {
                        stepsExecuted++;
                        runtime.advance();
                    }
                    case ENDED -> {
                        // Loop condition handles ENDED cleanly.
                    }
                }
                recordCurrentLabel(runtime, visitedLabels);
            }

            boolean endedCleanly = runtime.getStatus() == ScriptRuntime.Status.ENDED;
            double coverage = computeCoverage(visitedLabels.size(), project.labels().size());
            Optional<String> reason = endedCleanly
                    ? Optional.empty()
                    : Optional.of("Reached maxSteps limit (" + maxSteps + ")");

            return new PlaythroughReport(
                    endedCleanly,
                    stepsExecuted,
                    dialogueCount,
                    choicesTaken,
                    visitedLabels,
                    coverage,
                    reason
            );
        } catch (RuntimeException ex) {
            double coverage = computeCoverage(visitedLabels.size(), project.labels().size());
            String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            return new PlaythroughReport(
                    false,
                    stepsExecuted,
                    dialogueCount,
                    choicesTaken,
                    visitedLabels,
                    coverage,
                    Optional.of("Runtime error: " + message)
            );
        }
    }

    /**
     * Scans the KAGAYAKI project at {@code projectDir} and runs an automated headless playthrough.
     *
     * @param projectDir root directory of the KAGAYAKI project (must not be {@code null})
     * @param strategy   choice selection strategy (must not be {@code null})
     * @param maxSteps   maximum interaction steps before aborting ({@code > 0})
     * @return immutable {@link PlaythroughReport} summarizing execution and label coverage
     */
    public PlaythroughReport run(Path projectDir, ChoiceStrategy strategy, int maxSteps) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got " + maxSteps);
        }
        VJavaAst.ProjectAst project = ProjectScanner.scan(projectDir);
        return run(project, strategy, maxSteps);
    }

    private static ScriptRuntime.ChoiceOption selectChoice(
            List<ScriptRuntime.ChoiceOption> selectable,
            ChoiceStrategy strategy,
            int choicesTaken
    ) {
        return switch (strategy) {
            case FIRST_CHOICE -> selectable.get(0);
            case LAST_CHOICE -> selectable.get(selectable.size() - 1);
            case ROUND_ROBIN -> selectable.get(Math.floorMod(choicesTaken, selectable.size()));
        };
    }

    private static void recordCurrentLabel(ScriptRuntime runtime, Set<String> visitedLabels) {
        String label = runtime.getCurrentLabel();
        if (label != null && !label.isBlank()) {
            visitedLabels.add(label.trim());
        }
    }

    private static double computeCoverage(int visitedCount, int totalLabels) {
        if (totalLabels <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (visitedCount * 100.0) / totalLabels);
    }
}
