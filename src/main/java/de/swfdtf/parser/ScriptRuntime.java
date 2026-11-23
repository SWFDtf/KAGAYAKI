package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.math.ScreenShake;
import de.swfdtf.math.Transform2D;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.state.StateStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Non-blocking state-machine interpreter executing a compiled {@link VJavaAst.ProjectAst}.
 *
 * <p>Drives visual novel progression frame-by-frame on the engine update loop without blocking
 * the render thread. Coordinates {@link StateStore}, {@link PersistentAccess}, {@link EngineAPI},
 * {@link SceneController}, {@link JavaBlockCompiler}, {@link ConditionEvaluator}, and optional
 * {@link AudioBridge} and checkpoint hooks for deterministic rollback (M4).</p>
 */
public final class ScriptRuntime {

    private static final int MAX_CONSECUTIVE_STEPS = 100_000;

    /**
     * Execution states of the {@link ScriptRuntime} state machine.
     */
    public enum Status {
        /** Runtime initialized or ready to advance to the next statement. */
        READY,
        /** Paused on a dialogue line awaiting {@link #advance()}. */
        WAITING_DIALOGUE,
        /** Paused on a choice menu awaiting {@link #choose(int)} or timeout. */
        WAITING_MENU,
        /** Paused on a text input prompt awaiting {@link #submitInput(String)}. */
        WAITING_INPUT,
        /** Paused on a timed or click-skipped {@code pause} statement. */
        PAUSING,
        /** Script execution reached top-level {@code return} or end of entry label. */
        ENDED
    }

    /**
     * Active dialogue line presented by the runtime during {@link Status#WAITING_DIALOGUE}.
     *
     * @param lineId           canonical deterministic line identifier for read-tracking
     * @param speakerId        character ID (nullable for narrator lines)
     * @param speakerName      resolved speaker display name (nullable for narrator lines)
     * @param speakerColorHex  optional speaker hex color (nullable)
     * @param rawText          uninterpolated raw VN text
     * @param interpolatedText text after {@code [variable]} interpolation
     * @param voicePath        optional voice file path queued right before this line
     * @param transitionExpr   optional inline {@code with <transition>} expression
     * @param alreadyRead      whether {@code persistent.isLineRead(lineId)} was already {@code true} prior to display
     * @param nvlMode          whether NVL cumulative mode is active
     */
    public record DialoguePrompt(
            String lineId,
            String speakerId,
            String speakerName,
            String speakerColorHex,
            String rawText,
            String interpolatedText,
            String voicePath,
            String transitionExpr,
            boolean alreadyRead,
            boolean nvlMode
    ) {
    }

    /**
     * Evaluated menu choice option presented during {@link Status#WAITING_MENU}.
     *
     * @param index     0-based option index passed to {@link #choose(int)}
     * @param text      interpolated choice label
     * @param enabled   whether the option can be selected
     * @param visible   whether the option should be rendered in the choice list
     * @param astChoice underlying AST choice node
     */
    public record ChoiceOption(
            int index,
            String text,
            boolean enabled,
            boolean visible,
            VJavaAst.MenuChoice astChoice
    ) {
    }

    /**
     * Active menu prompt presented during {@link Status#WAITING_MENU}.
     *
     * @param caption          optional interpolated menu caption
     * @param timeoutSeconds   configured timeout duration in seconds (nullable)
     * @param remainingSeconds remaining seconds before timeout auto-jump
     * @param options          evaluated choice options
     */
    public record MenuPrompt(
            String caption,
            Double timeoutSeconds,
            double remainingSeconds,
            List<ChoiceOption> options
    ) {
        public MenuPrompt {
            options = options != null ? List.copyOf(options) : List.of();
        }
    }

    /**
     * Active text input prompt presented during {@link Status#WAITING_INPUT}.
     *
     * @param targetVar    target {@link StateStore} variable key
     * @param prompt       interpolated user prompt text
     * @param defaultValue interpolated default input text
     * @param allowChars   optional whitelist of allowed characters (nullable/empty allows all)
     * @param maxLength    maximum allowed string length ({@code <= 0} means unlimited)
     */
    public record InputPrompt(
            String targetVar,
            String prompt,
            String defaultValue,
            String allowChars,
            int maxLength
    ) {
    }

    /**
     * Decoupled audio hook invoked when {@code play}, {@code queue}, {@code stop}, or {@code voice}
     * statements execute.
     */
    public interface AudioBridge {
        void play(String ch, String path, boolean loop, double fadeIn, double vol);

        void queue(String ch, List<String> paths);

        void stop(String ch, double fadeOut);

        void voice(String path);
    }

    /**
     * Recorded audio command emitted during script execution (useful for diagnostics and testing).
     *
     * @param action      command name ({@code "play"}, {@code "queue"}, {@code "stop"}, {@code "voice"})
     * @param channel     audio channel (e.g., {@code "music"}, {@code "sound"}, {@code "voice"})
     * @param assetPath   primary asset path (nullable for {@code stop}/{@code queue})
     * @param queuedPaths queued asset paths (for {@code queue})
     * @param loop        loop flag (for {@code play})
     * @param fadeSeconds fade-in or fade-out duration in seconds
     * @param volume      playback volume multiplier
     */
    public record AudioEvent(
            String action,
            String channel,
            String assetPath,
            List<String> queuedPaths,
            boolean loop,
            double fadeSeconds,
            double volume
    ) {
        public AudioEvent {
            queuedPaths = queuedPaths != null ? List.copyOf(queuedPaths) : List.of();
        }
    }

    /**
     * Call or nested block stack frame captured for subroutine returns and M4 deterministic rollback.
     *
     * @param labelName       enclosing label name
     * @param statementIndex  next statement index to resume within {@code statements}
     * @param statements      statement list of the enclosing context
     * @param childStatements nested block statements entered from this frame (for {@code if}/{@code menu})
     * @param isCallBoundary  {@code true} for {@code call} frames, {@code false} for nested block frames
     */
    public record StackFrame(
            String labelName,
            int statementIndex,
            List<VJavaAst.Statement> statements,
            List<VJavaAst.Statement> childStatements,
            boolean isCallBoundary
    ) {
        public StackFrame {
            statements = statements != null ? List.copyOf(statements) : List.of();
            childStatements = childStatements != null ? List.copyOf(childStatements) : List.of();
        }

        /**
         * Convenience constructor for subroutine call frames.
         *
         * @param labelName      caller label name
         * @param statementIndex resume statement index
         */
        public StackFrame(String labelName, int statementIndex) {
            this(labelName, statementIndex, List.of(), List.of(), true);
        }
    }

    /**
     * Immutable execution state snapshot used for deterministic M4 rollback and save/load restoration.
     *
     * @param currentLabel    currently active label name
     * @param statementIndex  current statement index within active block
     * @param callStack       immutable copy of active stack frames
     * @param status          runtime status at snapshot time
     * @param activeDialogue  active dialogue prompt (nullable)
     * @param activeMenu      active menu prompt (nullable)
     * @param activeInput     active input prompt (nullable)
     * @param nvlMode         whether NVL mode is enabled
     * @param nvlLines        immutable list of accumulated NVL lines
     * @param activeVoicePath pending or active voice path (nullable)
     * @param stageSnapshot   captured {@link SceneController} stage snapshot
     */
    public record ExecutionSnapshot(
            String currentLabel,
            int statementIndex,
            List<StackFrame> callStack,
            Status status,
            DialoguePrompt activeDialogue,
            MenuPrompt activeMenu,
            InputPrompt activeInput,
            boolean nvlMode,
            List<String> nvlLines,
            String activeVoicePath,
            SceneController.StageSnapshot stageSnapshot
    ) {
        public ExecutionSnapshot {
            callStack = callStack != null ? List.copyOf(callStack) : List.of();
            nvlLines = nvlLines != null ? List.copyOf(nvlLines) : List.of();
        }
    }

    private final VJavaAst.ProjectAst projectAst;
    private final EngineAPI engineApi;
    private final StateStore stateStore;
    private final PersistentAccess persistentAccess;
    private final SceneController sceneController;
    private final JavaBlockCompiler javaBlockCompiler = new JavaBlockCompiler();

    private JavaBlockCompiler.CompiledProjectBlocks compiledBlocks;
    private AudioBridge audioBridge;
    private Runnable checkpointListener;

    private Status status = Status.READY;
    private String currentLabel;
    private List<VJavaAst.Statement> currentStatements = List.of();
    private int statementIndex = 0;
    private final List<StackFrame> callStack = new ArrayList<>();

    private DialoguePrompt activeDialogue;
    private MenuPrompt activeMenu;
    private VJavaAst.MenuStatement activeMenuAst;
    private InputPrompt activeInput;

    private boolean nvlMode = false;
    private final List<String> nvlLines = new ArrayList<>();
    private String activeVoicePath;
    private double pauseRemainingSeconds = 0.0;
    private boolean pauseTimed = false;
    private String windowAction = "auto";
    private final List<AudioEvent> audioEvents = new ArrayList<>();

    /**
     * Creates a headless-capable {@code ScriptRuntime} with default in-memory subsystems.
     *
     * @param project parsed project AST
     */
    public ScriptRuntime(VJavaAst.ProjectAst project) {
        this(
                project,
                new EngineAPI(EngineConfig.defaults()),
                new SimpleStateStore(),
                new SimplePersistentAccess(),
                new SceneController(new SceneGraph())
        );
    }

    /**
     * Creates a {@code ScriptRuntime} wired to an existing {@link EngineAPI} with default state/scene subsystems.
     *
     * @param project   parsed project AST
     * @param engineApi active engine API
     */
    public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi) {
        this(
                project,
                engineApi,
                new SimpleStateStore(),
                new SimplePersistentAccess(),
                new SceneController(
                        new SceneGraph(),
                        null,
                        Objects.requireNonNull(engineApi, "engineApi must not be null").getLogger(),
                        Objects.requireNonNull(project, "project must not be null").projectRoot(),
                        false
                )
        );
    }

    /**
     * Creates a fully configured {@code ScriptRuntime}.
     *
     * @param project          unified project AST
     * @param engineApi        public engine facade
     * @param stateStore       mutable state store
     * @param persistentAccess persistent storage
     * @param sceneController  stage/sprite controller
     */
    public ScriptRuntime(
            VJavaAst.ProjectAst project,
            EngineAPI engineApi,
            StateStore stateStore,
            PersistentAccess persistentAccess,
            SceneController sceneController
    ) {
        this.projectAst = Objects.requireNonNull(project, "project must not be null");
        this.engineApi = Objects.requireNonNull(engineApi, "engineApi must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
        this.sceneController = Objects.requireNonNull(sceneController, "sceneController must not be null");
        if (project.projectRoot() != null) {
            this.sceneController.setProjectRoot(project.projectRoot());
        }
        registerServices();
    }

    private void registerServices() {
        engineApi.registerService(StateStore.class, stateStore);
        engineApi.registerService(PersistentAccess.class, persistentAccess);
        engineApi.registerService(SceneController.class, sceneController);
        engineApi.registerService(ScriptRuntime.class, this);
    }

    /**
     * Compiles all embedded {@code @->{ ... }<<} Java blocks across the project in memory.
     *
     * @return compiled project block registry
     * @throws VJavaCompileException if compilation fails
     */
    public JavaBlockCompiler.CompiledProjectBlocks compile() throws VJavaCompileException {
        this.compiledBlocks = javaBlockCompiler.compile(projectAst);
        return this.compiledBlocks;
    }

    /**
     * Starts script execution at the project's configured entry label (default {@code "start"}).
     *
     * <p>Compiles Java blocks if needed, registers images and transforms, initializes
     * {@code default} variables, executes top-level Java blocks, and advances to the first
     * interaction point.</p>
     */
    public void start() {
        String configuredEntry = projectAst.descriptor() != null ? projectAst.descriptor().getEntryLabel() : "start";
        start(configuredEntry);
    }

    /**
     * Starts script execution at {@code entryLabel} and advances to the first interaction point.
     *
     * @param entryLabel target starting label name
     */
    public void start(String entryLabel) {
        if (compiledBlocks == null) {
            compile();
        }
        resetTransientState();
        registerAssetsAndTransforms();
        initializeDefaultVariables();
        executeTopLevelJavaBlocks();

        String resolvedEntry = (entryLabel != null && !entryLabel.isBlank()) ? entryLabel.trim() : "start";
        enterLabel(resolvedEntry);
        stepUntilPause();
    }

    /**
     * Advances execution past the current dialogue line or pause until the next interaction point
     * or script termination.
     */
    public void advance() {
        switch (status) {
            case ENDED, WAITING_MENU, WAITING_INPUT -> {
                // No-op: cannot advance without choice/input or after end
            }
            case PAUSING -> {
                pauseRemainingSeconds = 0.0;
                pauseTimed = false;
                status = Status.READY;
                stepUntilPause();
            }
            case WAITING_DIALOGUE -> {
                activeDialogue = null;
                activeVoicePath = null;
                statementIndex++;
                status = Status.READY;
                stepUntilPause();
            }
            case READY -> stepUntilPause();
        }
    }

    /**
     * Selects a choice option while in {@link Status#WAITING_MENU} and advances to the next
     * interaction point.
     *
     * @param optionIndex 0-based index into {@link MenuPrompt#options()}
     */
    public void choose(int optionIndex) {
        if (status != Status.WAITING_MENU || activeMenu == null) {
            throw new IllegalStateException("Cannot choose menu option when runtime status is " + status);
        }
        List<ChoiceOption> options = activeMenu.options();
        if (optionIndex < 0 || optionIndex >= options.size()) {
            throw new IndexOutOfBoundsException("Menu option index out of range: " + optionIndex);
        }
        ChoiceOption chosen = options.get(optionIndex);
        if (!chosen.visible() || !chosen.enabled()) {
            throw new IllegalStateException("Menu option " + optionIndex + " ('" + chosen.text() + "') is disabled or hidden");
        }

        VJavaAst.MenuChoice astChoice = chosen.astChoice();
        activeMenu = null;
        activeMenuAst = null;
        status = Status.READY;

        String inlineJump = astChoice.inlineJumpTarget();
        boolean hasInlineJump = inlineJump != null && !inlineJump.isBlank();
        List<VJavaAst.Statement> choiceBody = astChoice.body();

        if (!choiceBody.isEmpty()) {
            List<VJavaAst.Statement> effectiveBody = choiceBody;
            if (hasInlineJump) {
                List<VJavaAst.Statement> combined = new ArrayList<>(choiceBody.size() + 1);
                combined.addAll(choiceBody);
                combined.add(new VJavaAst.JumpStatement(inlineJump, false, astChoice.sourceFile(), astChoice.lineNumber()));
                effectiveBody = List.copyOf(combined);
            }
            pushNestedBlock(effectiveBody);
        } else if (hasInlineJump) {
            unwindLocalBlockFrames();
            enterLabel(inlineJump.trim());
        } else {
            statementIndex++;
        }

        stepUntilPause();
    }

    /**
     * Submits player text while in {@link Status#WAITING_INPUT}, validates against character
     * whitelist and maximum length, stores it in {@link StateStore}, and advances execution.
     *
     * @param text submitted player input (falls back to prompt default if {@code null})
     */
    public void submitInput(String text) {
        if (status != Status.WAITING_INPUT || activeInput == null) {
            throw new IllegalStateException("Cannot submit input when runtime status is " + status);
        }

        String raw = text != null ? text : (activeInput.defaultValue() != null ? activeInput.defaultValue() : "");
        String allowChars = activeInput.allowChars();
        if (allowChars != null && !allowChars.isEmpty()) {
            StringBuilder filtered = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (allowChars.indexOf(c) >= 0) {
                    filtered.append(c);
                }
            }
            raw = filtered.toString();
        }
        if (activeInput.maxLength() > 0 && raw.length() > activeInput.maxLength()) {
            raw = raw.substring(0, activeInput.maxLength());
        }

        stateStore.set(activeInput.targetVar(), raw);
        activeInput = null;
        statementIndex++;
        status = Status.READY;
        stepUntilPause();
    }

    /**
     * Advances timed {@link Status#PAUSING} countdowns and {@link Status#WAITING_MENU} timeouts
     * without blocking the render thread.
     *
     * @param dt frame delta time in seconds
     */
    public void update(double dt) {
        if (dt <= 0.0 || !Double.isFinite(dt)) {
            return;
        }
        if (status == Status.PAUSING && pauseTimed) {
            pauseRemainingSeconds -= dt;
            if (pauseRemainingSeconds <= 0.0) {
                pauseRemainingSeconds = 0.0;
                pauseTimed = false;
                status = Status.READY;
                stepUntilPause();
            }
        } else if (status == Status.WAITING_MENU && activeMenu != null && activeMenu.timeoutSeconds() != null) {
            double remaining = Math.max(0.0, activeMenu.remainingSeconds() - dt);
            activeMenu = new MenuPrompt(
                    activeMenu.caption(),
                    activeMenu.timeoutSeconds(),
                    remaining,
                    activeMenu.options()
            );
            if (remaining <= 0.0) {
                String jumpTarget = activeMenuAst != null ? activeMenuAst.timeoutJumpLabel() : null;
                if (jumpTarget != null && !jumpTarget.isBlank()) {
                    activeMenu = null;
                    activeMenuAst = null;
                    unwindLocalBlockFrames();
                    enterLabel(jumpTarget.trim());
                    status = Status.READY;
                    stepUntilPause();
                } else {
                    ChoiceOption fallback = activeMenu.options().stream()
                            .filter(opt -> opt.visible() && opt.enabled())
                            .findFirst()
                            .orElse(null);
                    if (fallback != null) {
                        choose(fallback.index());
                    } else {
                        activeMenu = null;
                        activeMenuAst = null;
                        statementIndex++;
                        status = Status.READY;
                        stepUntilPause();
                    }
                }
            }
        }
    }

    /**
     * Immediately warps execution to {@code label}, clearing active prompts and call stack,
     * and advances to the first interaction point in {@code label}.
     *
     * @param label target label name
     */
    public void jumpToLabel(String label) {
        Objects.requireNonNull(label, "label must not be null");
        if (compiledBlocks == null) {
            compile();
        }
        callStack.clear();
        activeDialogue = null;
        activeMenu = null;
        activeMenuAst = null;
        activeInput = null;
        activeVoicePath = null;
        pauseRemainingSeconds = 0.0;
        pauseTimed = false;
        status = Status.READY;
        enterLabel(label.trim());
        stepUntilPause();
    }

    /**
     * Captures a deterministic {@link ExecutionSnapshot} of the current script position, call stack,
     * active prompt, NVL buffer, and {@link SceneController} stage state.
     *
     * @return immutable execution snapshot
     */
    public ExecutionSnapshot snapshotExecution() {
        return new ExecutionSnapshot(
                currentLabel,
                statementIndex,
                List.copyOf(callStack),
                status,
                activeDialogue,
                activeMenu,
                activeInput,
                nvlMode,
                List.copyOf(nvlLines),
                activeVoicePath,
                sceneController.snapshotState()
        );
    }

    /**
     * Restores the interpreter and stage state cleanly from a previously captured {@link ExecutionSnapshot}.
     *
     * @param snapshot snapshot to restore
     */
    public void restoreExecution(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentLabel = snapshot.currentLabel();
        this.statementIndex = snapshot.statementIndex();
        this.callStack.clear();
        this.callStack.addAll(snapshot.callStack());
        this.status = snapshot.status();
        this.activeDialogue = snapshot.activeDialogue();
        this.activeMenu = snapshot.activeMenu();
        this.activeInput = snapshot.activeInput();
        this.nvlMode = snapshot.nvlMode();
        this.nvlLines.clear();
        this.nvlLines.addAll(snapshot.nvlLines());
        this.activeVoicePath = snapshot.activeVoicePath();
        this.pauseRemainingSeconds = 0.0;
        this.pauseTimed = false;

        if (!this.callStack.isEmpty() && !this.callStack.get(this.callStack.size() - 1).isCallBoundary()) {
            this.currentStatements = this.callStack.get(this.callStack.size() - 1).childStatements();
        } else if (this.currentLabel != null && projectAst.labels().containsKey(this.currentLabel)) {
            this.currentStatements = projectAst.labels().get(this.currentLabel).statements();
        } else {
            this.currentStatements = List.of();
        }

        if (this.status == Status.WAITING_MENU
                && this.statementIndex >= 0
                && this.statementIndex < this.currentStatements.size()
                && this.currentStatements.get(this.statementIndex) instanceof VJavaAst.MenuStatement ms) {
            this.activeMenuAst = ms;
        } else {
            this.activeMenuAst = null;
        }

        if (snapshot.stageSnapshot() != null) {
            sceneController.restoreState(snapshot.stageSnapshot());
        }
    }

    // ---- Core Step Loop ----

    private void stepUntilPause() {
        int steps = 0;
        while (status == Status.READY) {
            if (++steps > MAX_CONSECUTIVE_STEPS) {
                throw new IllegalStateException(
                        "Exceeded maximum consecutive non-blocking steps (" + MAX_CONSECUTIVE_STEPS
                                + ") near label '" + currentLabel + "' index " + statementIndex
                );
            }

            if (statementIndex >= currentStatements.size()) {
                if (!popCompletedFrameOrEnd()) {
                    return;
                }
                continue;
            }

            VJavaAst.Statement stmt = currentStatements.get(statementIndex);
            boolean yielded = executeStatement(stmt);
            if (yielded) {
                return;
            }
        }
    }

    private boolean popCompletedFrameOrEnd() {
        if (callStack.isEmpty()) {
            status = Status.ENDED;
            activeDialogue = null;
            activeMenu = null;
            activeMenuAst = null;
            activeInput = null;
            activeVoicePath = null;
            return false;
        }
        StackFrame top = callStack.remove(callStack.size() - 1);
        this.currentLabel = top.labelName();
        this.statementIndex = top.statementIndex();
        if (!top.statements().isEmpty()) {
            this.currentStatements = top.statements();
        } else if (this.currentLabel != null && projectAst.labels().containsKey(this.currentLabel)) {
            this.currentStatements = projectAst.labels().get(this.currentLabel).statements();
        } else {
            this.currentStatements = List.of();
        }
        return true;
    }

    private boolean executeStatement(VJavaAst.Statement stmt) {
        if (stmt instanceof VJavaAst.JavaBlockStatement jbs) {
            executeJavaBlock(jbs.block());
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.SceneStatement ss) {
            applyTransitionEffects(ss.transitionExpr());
            sceneController.setScene(ss.bgTag(), ss.transitionExpr());
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.ShowStatement show) {
            applyTransitionEffects(show.transitionExpr());
            SpriteNode sprite = sceneController.showSprite(
                    show.tag(),
                    show.attributes(),
                    show.positionExpr(),
                    show.transitionExpr()
            );
            if (!show.inlineAtl().isEmpty()) {
                applyAtlToTransform(show.inlineAtl(), sprite.getTransform());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.HideStatement hide) {
            applyTransitionEffects(hide.transitionExpr());
            sceneController.hideSprite(hide.tag(), hide.transitionExpr());
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.SayStatement say) {
            enterDialoguePrompt(say);
            return true;
        }
        if (stmt instanceof VJavaAst.JumpStatement jump) {
            String target = jump.isExpression()
                    ? String.valueOf(ConditionEvaluator.evaluate(jump.targetLabel(), stateStore, persistentAccess))
                    : jump.targetLabel();
            unwindLocalBlockFrames();
            enterLabel(target.trim());
            return false;
        }
        if (stmt instanceof VJavaAst.CallStatement call) {
            if (!call.args().isEmpty()) {
                List<Object> evaluated = new ArrayList<>(call.args().size());
                for (String argExpr : call.args()) {
                    evaluated.add(ConditionEvaluator.evaluate(argExpr, stateStore, persistentAccess));
                }
                stateStore.set("_call_args", List.copyOf(evaluated));
            }
            callStack.add(new StackFrame(currentLabel, statementIndex + 1, currentStatements, List.of(), true));
            enterLabel(call.targetLabel().trim());
            return false;
        }
        if (stmt instanceof VJavaAst.ReturnStatement ret) {
            if (ret.expression() != null && !ret.expression().isBlank()) {
                Object val = ConditionEvaluator.evaluate(ret.expression(), stateStore, persistentAccess);
                stateStore.set("_return", val);
            }
            unwindLocalBlockFrames();
            if (callStack.isEmpty()) {
                status = Status.ENDED;
                activeDialogue = null;
                activeMenu = null;
                activeMenuAst = null;
                activeInput = null;
                activeVoicePath = null;
                return true;
            }
            popCompletedFrameOrEnd();
            return false;
        }
        if (stmt instanceof VJavaAst.PlayStatement play) {
            recordAudioEvent(new AudioEvent(
                    "play",
                    play.channel(),
                    play.assetPath(),
                    List.of(),
                    play.loop(),
                    play.fadeInSeconds(),
                    play.volume()
            ));
            if ("music".equalsIgnoreCase(play.channel())) {
                persistentAccess.unlockTrack(play.assetPath());
            }
            if (audioBridge != null) {
                audioBridge.play(play.channel(), play.assetPath(), play.loop(), play.fadeInSeconds(), play.volume());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.QueueStatement queue) {
            recordAudioEvent(new AudioEvent(
                    "queue",
                    queue.channel(),
                    null,
                    queue.assetPaths(),
                    false,
                    0.0,
                    1.0
            ));
            if (audioBridge != null) {
                audioBridge.queue(queue.channel(), queue.assetPaths());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.StopStatement stop) {
            recordAudioEvent(new AudioEvent(
                    "stop",
                    stop.channel(),
                    null,
                    List.of(),
                    false,
                    stop.fadeOutSeconds(),
                    0.0
            ));
            if (audioBridge != null) {
                audioBridge.stop(stop.channel(), stop.fadeOutSeconds());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.VoiceStatement voice) {
            this.activeVoicePath = voice.assetPath();
            recordAudioEvent(new AudioEvent(
                    "voice",
                    "voice",
                    voice.assetPath(),
                    List.of(),
                    false,
                    0.0,
                    1.0
            ));
            if (audioBridge != null) {
                audioBridge.voice(voice.assetPath());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.NvlStatement nvl) {
            String action = nvl.action() != null ? nvl.action().trim().toLowerCase(Locale.ROOT) : "";
            switch (action) {
                case "show" -> this.nvlMode = true;
                case "hide" -> this.nvlMode = false;
                case "clear" -> this.nvlLines.clear();
                default -> engineApi.logWarn("Unknown NVL action: " + nvl.action());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.BlockRollbackStatement) {
            stateStore.blockRollback();
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.InputStatement input) {
            enterInputPrompt(input);
            return true;
        }
        if (stmt instanceof VJavaAst.MenuStatement menu) {
            enterMenuPrompt(menu);
            return true;
        }
        if (stmt instanceof VJavaAst.IfStatement ifs) {
            for (VJavaAst.IfBranch branch : ifs.branches()) {
                boolean matched = branch.conditionExpr() == null
                        || branch.conditionExpr().isBlank()
                        || ConditionEvaluator.evaluateBoolean(branch.conditionExpr(), stateStore, persistentAccess);
                if (matched) {
                    if (!branch.body().isEmpty()) {
                        pushNestedBlock(branch.body());
                    } else {
                        statementIndex++;
                    }
                    return false;
                }
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.SetVarStatement setVar) {
            if (!setVar.isDefaultOnly() || !stateStore.has(setVar.varName())) {
                Object value = ConditionEvaluator.evaluate(setVar.expression(), stateStore, persistentAccess);
                stateStore.set(setVar.varName(), value);
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.PauseStatement pause) {
            statementIndex++;
            if (pause.seconds() != null && pause.seconds() > 0.0) {
                this.pauseRemainingSeconds = pause.seconds();
                this.pauseTimed = true;
            } else {
                this.pauseRemainingSeconds = -1.0;
                this.pauseTimed = false;
            }
            this.status = Status.PAUSING;
            return true;
        }
        if (stmt instanceof VJavaAst.WithStatement withStmt) {
            applyTransitionEffects(withStmt.transitionExpr());
            if (withStmt.transitionExpr() != null && !withStmt.transitionExpr().isBlank()) {
                engineApi.setProperty("lastTransition", withStmt.transitionExpr().trim());
            }
            statementIndex++;
            return false;
        }
        if (stmt instanceof VJavaAst.WindowStatement windowStmt) {
            this.windowAction = windowStmt.action() != null ? windowStmt.action().trim() : "auto";
            engineApi.setProperty("dialogueWindowAction", this.windowAction);
            statementIndex++;
            return false;
        }

        throw new IllegalStateException("Unsupported AST statement type: " + stmt.getClass().getName());
    }

    private void enterDialoguePrompt(VJavaAst.SayStatement say) {
        String speakerId = (say.speakerId() != null && !say.speakerId().isBlank()) ? say.speakerId().trim() : null;
        VJavaAst.CharacterDef charDef = speakerId != null ? projectAst.characters().get(speakerId) : null;
        String speakerName = charDef != null
                ? interpolateText(charDef.displayName())
                : speakerId;
        String speakerColor = charDef != null ? charDef.colorHex() : null;

        String rawText = say.text() != null ? say.text() : "";
        String interpolated = interpolateText(rawText);
        String lineId = buildLineId(say);
        boolean alreadyRead = persistentAccess.isLineRead(lineId);

        applyTransitionEffects(say.transitionExpr());
        if (nvlMode) {
            String formatted = (speakerName != null && !speakerName.isBlank())
                    ? speakerName + ": " + interpolated
                    : interpolated;
            nvlLines.add(formatted);
        }

        this.activeDialogue = new DialoguePrompt(
                lineId,
                speakerId,
                speakerName,
                speakerColor,
                rawText,
                interpolated,
                activeVoicePath,
                say.transitionExpr(),
                alreadyRead,
                nvlMode
        );
        this.activeMenu = null;
        this.activeMenuAst = null;
        this.activeInput = null;
        this.status = Status.WAITING_DIALOGUE;

        notifyCheckpoint();
        persistentAccess.markLineRead(lineId);
    }

    private void enterMenuPrompt(VJavaAst.MenuStatement menu) {
        List<ChoiceOption> options = new ArrayList<>(menu.choices().size());
        for (int i = 0; i < menu.choices().size(); i++) {
            VJavaAst.MenuChoice choice = menu.choices().get(i);
            boolean hasCondition = choice.conditionExpr() != null && !choice.conditionExpr().isBlank();
            boolean condPassed = !hasCondition
                    || ConditionEvaluator.evaluateBoolean(choice.conditionExpr(), stateStore, persistentAccess);
            boolean enabled = hasCondition ? condPassed : !choice.disabledWhenFalse();
            boolean visible = condPassed || choice.disabledWhenFalse();
            String label = interpolateText(choice.text());
            options.add(new ChoiceOption(i, label, enabled, visible, choice));
        }

        String caption = menu.caption() != null ? interpolateText(menu.caption()) : null;
        double initialRemaining = menu.timeoutSeconds() != null ? Math.max(0.0, menu.timeoutSeconds()) : 0.0;

        this.activeMenu = new MenuPrompt(caption, menu.timeoutSeconds(), initialRemaining, options);
        this.activeMenuAst = menu;
        this.activeDialogue = null;
        this.activeInput = null;
        this.status = Status.WAITING_MENU;

        notifyCheckpoint();
    }

    private void enterInputPrompt(VJavaAst.InputStatement input) {
        String promptText = input.prompt() != null ? interpolateText(input.prompt()) : "";
        String defaultVal = input.defaultValue() != null ? interpolateText(input.defaultValue()) : "";

        this.activeInput = new InputPrompt(
                input.targetVar(),
                promptText,
                defaultVal,
                input.allowChars(),
                input.maxLength()
        );
        this.activeDialogue = null;
        this.activeMenu = null;
        this.activeMenuAst = null;
        this.status = Status.WAITING_INPUT;

        notifyCheckpoint();
    }

    private void executeTopLevelJavaBlocks() {
        for (VJavaAst.JavaBlockNode block : projectAst.topLevelJavaBlocks()) {
            executeJavaBlock(block);
        }
    }

    private void executeJavaBlock(VJavaAst.JavaBlockNode block) {
        if (compiledBlocks == null) {
            compile();
        }
        VJavaExecutable exec = compiledBlocks.requireExecutable(block.id());
        try {
            exec.execute(stateStore, persistentAccess, engineApi, sceneController);
        } catch (Exception e) {
            String message = "Error executing VJava block '" + block.id() + "' ("
                    + block.sourceFile() + ":" + block.lineNumber() + "): " + e.getMessage();
            engineApi.logError(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private void registerAssetsAndTransforms() {
        for (VJavaAst.ImageDef img : projectAst.images().values()) {
            sceneController.registerImageDefinition(img.fullKey(), img.assetPath());
        }
        for (VJavaAst.LayeredImageDef layered : projectAst.layeredImages().values()) {
            registerLayeredImage(layered);
        }
        for (VJavaAst.TransformDef transformDef : projectAst.transforms().values()) {
            Transform2D transform = new Transform2D();
            applyAtlToTransform(transformDef.steps(), transform);
            sceneController.registerTransform(transformDef.name(), transform);
        }
    }

    private void registerLayeredImage(VJavaAst.LayeredImageDef layered) {
        String basePath = null;
        String defaultFallback = null;
        for (VJavaAst.LayeredEntry entry : layered.entries()) {
            if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                basePath = stripQuotes(always.assetOrExpr());
            } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                for (VJavaAst.AttributeEntry attr : group.attributes()) {
                    String path = stripQuotes(attr.assetOrExpr());
                    if (!path.isBlank()) {
                        sceneController.registerImageDefinition(layered.name() + " " + attr.name(), path);
                        if (attr.isDefault() && defaultFallback == null) {
                            defaultFallback = path;
                        }
                    }
                }
            }
        }
        if (basePath != null && !basePath.isBlank()) {
            sceneController.registerImageDefinition(layered.name(), basePath);
        } else if (defaultFallback != null) {
            sceneController.registerImageDefinition(layered.name(), defaultFallback);
        }
    }

    private void initializeDefaultVariables() {
        for (VJavaAst.SetVarStatement dv : projectAst.defaultVariables()) {
            if (!stateStore.has(dv.varName())) {
                Object val = ConditionEvaluator.evaluate(dv.expression(), stateStore, persistentAccess);
                stateStore.set(dv.varName(), val);
            }
        }
    }

    private void enterLabel(String labelName) {
        VJavaAst.LabelNode label = projectAst.labels().get(labelName);
        if (label == null) {
            throw new IllegalArgumentException("Unknown script label: '" + labelName + "'");
        }
        this.currentLabel = label.name();
        this.currentStatements = label.statements();
        this.statementIndex = 0;
    }

    private void pushNestedBlock(List<VJavaAst.Statement> childBody) {
        callStack.add(new StackFrame(currentLabel, statementIndex + 1, currentStatements, childBody, false));
        this.currentStatements = childBody;
        this.statementIndex = 0;
    }

    private void unwindLocalBlockFrames() {
        while (!callStack.isEmpty() && !callStack.get(callStack.size() - 1).isCallBoundary()) {
            callStack.remove(callStack.size() - 1);
        }
    }

    private void resetTransientState() {
        this.status = Status.READY;
        this.currentLabel = null;
        this.currentStatements = List.of();
        this.statementIndex = 0;
        this.callStack.clear();
        this.activeDialogue = null;
        this.activeMenu = null;
        this.activeMenuAst = null;
        this.activeInput = null;
        this.nvlMode = false;
        this.nvlLines.clear();
        this.activeVoicePath = null;
        this.pauseRemainingSeconds = 0.0;
        this.pauseTimed = false;
        this.windowAction = "auto";
        this.audioEvents.clear();
    }

    private String interpolateText(String raw) {
        if (raw == null) {
            return "";
        }
        return TextTagParser.interpolateVariables(raw, this::lookupInterpolationVariable);
    }

    private Object lookupInterpolationVariable(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (stateStore.has(key)) {
            return stateStore.get(key);
        }
        if (persistentAccess.has(key)) {
            return persistentAccess.get(key);
        }
        if (key.indexOf('.') >= 0 || key.indexOf('[') >= 0 || key.indexOf('(') >= 0) {
            try {
                return ConditionEvaluator.evaluate(key, stateStore, persistentAccess);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private String buildLineId(VJavaAst.SayStatement say) {
        String loc = (say.sourceFile() != null && !say.sourceFile().isBlank() && say.lineNumber() > 0)
                ? say.sourceFile() + ":" + say.lineNumber()
                : (currentLabel + ":" + statementIndex);
        int hash = Objects.hash(say.speakerId(), say.text());
        return loc + ":" + Integer.toHexString(hash);
    }

    private void applyTransitionEffects(String transitionExpr) {
        if (transitionExpr == null || transitionExpr.isBlank()) {
            return;
        }
        String normalized = transitionExpr.trim().toLowerCase(Locale.ROOT);
        if ("vpunch".equals(normalized)) {
            sceneController.getSceneGraph().setScreenShake(ScreenShake.vpunch());
        } else if ("hpunch".equals(normalized)) {
            sceneController.getSceneGraph().setScreenShake(ScreenShake.hpunch());
        }
    }

    private static void applyAtlToTransform(List<VJavaAst.AtlStep> steps, Transform2D target) {
        for (VJavaAst.AtlStep step : steps) {
            for (Map.Entry<String, String> entry : step.properties().entrySet()) {
                Float parsed = tryParseFloat(entry.getValue());
                if (parsed == null) {
                    continue;
                }
                switch (entry.getKey().trim().toLowerCase(Locale.ROOT)) {
                    case "xpos" -> target.setXpos(parsed);
                    case "ypos" -> target.setYpos(parsed);
                    case "xanchor" -> target.setXanchor(parsed);
                    case "yanchor" -> target.setYanchor(parsed);
                    case "xalign" -> target.setXalign(parsed);
                    case "yalign" -> target.setYalign(parsed);
                    case "zoom" -> target.setZoom(parsed);
                    case "xzoom" -> target.setXzoom(parsed);
                    case "yzoom" -> target.setYzoom(parsed);
                    case "rotate" -> target.setRotate(parsed);
                    case "alpha" -> target.setAlpha(parsed);
                    case "blur" -> target.setBlur(parsed);
                    case "parallax_x" -> target.setParallaxX(parsed);
                    case "parallax_y" -> target.setParallaxY(parsed);
                    default -> {
                        // Ignore non-Transform2D properties
                    }
                }
            }
            if (!step.children().isEmpty()) {
                applyAtlToTransform(step.children(), target);
            }
        }
    }

    private static Float tryParseFloat(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void recordAudioEvent(AudioEvent event) {
        audioEvents.add(event);
    }

    private void notifyCheckpoint() {
        if (checkpointListener != null) {
            checkpointListener.run();
        }
    }

    // ---- Getters & Hooks ----

    /** @return current interpreter status */
    public Status getStatus() {
        return status;
    }

    /** @return active dialogue prompt during {@link Status#WAITING_DIALOGUE}, or {@code null} */
    public DialoguePrompt getActiveDialogue() {
        return activeDialogue;
    }

    /** @return active menu prompt during {@link Status#WAITING_MENU}, or {@code null} */
    public MenuPrompt getActiveMenu() {
        return activeMenu;
    }

    /** @return active input prompt during {@link Status#WAITING_INPUT}, or {@code null} */
    public InputPrompt getActiveInput() {
        return activeInput;
    }

    /** @return bound mutable state store */
    public StateStore getStateStore() {
        return stateStore;
    }

    /** @return bound cross-save persistent access */
    public PersistentAccess getPersistentAccess() {
        return persistentAccess;
    }

    /** @return bound visual stage controller */
    public SceneController getSceneController() {
        return sceneController;
    }

    /** @return bound engine API facade */
    public EngineAPI getEngineApi() {
        return engineApi;
    }

    /** @return project AST executed by this runtime */
    public VJavaAst.ProjectAst getProjectAst() {
        return projectAst;
    }

    /** @return immutable view of recorded audio events emitted during execution */
    public List<AudioEvent> getAudioEvents() {
        return Collections.unmodifiableList(new ArrayList<>(audioEvents));
    }

    /**
     * Attaches an {@link AudioBridge} callback for live audio playback integration.
     *
     * @param audioBridge audio bridge implementation (nullable)
     */
    public void setAudioBridge(AudioBridge audioBridge) {
        this.audioBridge = audioBridge;
    }

    /**
     * Sets a listener invoked immediately upon entering each interaction point
     * ({@code WAITING_DIALOGUE}, {@code WAITING_MENU}, {@code WAITING_INPUT}) so M4
     * {@code RevertableStateStore} can record deterministic rollback checkpoints.
     *
     * @param checkpointListener checkpoint callback (nullable)
     */
    public void setCheckpointListener(Runnable checkpointListener) {
        this.checkpointListener = checkpointListener;
    }

    /** @return compiled block registry, or {@code null} if {@link #compile()} has not run yet */
    public JavaBlockCompiler.CompiledProjectBlocks getCompiledBlocks() {
        return compiledBlocks;
    }

    /** @return current active label name, or {@code null} prior to {@link #start()} */
    public String getCurrentLabel() {
        return currentLabel;
    }

    /** @return current statement index within active block */
    public int getStatementIndex() {
        return statementIndex;
    }

    /** @return {@code true} if NVL cumulative mode is active */
    public boolean isNvlMode() {
        return nvlMode;
    }

    /** @return immutable list of accumulated NVL lines */
    public List<String> getNvlLines() {
        return List.copyOf(nvlLines);
    }

    /** @return currently queued/active voice path, or {@code null} */
    public String getActiveVoicePath() {
        return activeVoicePath;
    }

    /** @return remaining timed pause duration in seconds ({@code -1.0} if click-waiting) */
    public double getPauseRemainingSeconds() {
        return pauseRemainingSeconds;
    }

    /** @return current dialogue window action ({@code "auto"}, {@code "show"}, {@code "hide"}) */
    public String getWindowAction() {
        return windowAction;
    }
}
