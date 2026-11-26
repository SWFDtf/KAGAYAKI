package de.swfdtf.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Interactive AST-statement script debugger supporting breakpoints, {@code Step Into},
 * {@code Step Over}, {@code Continue}, and call-stack inspection on {@link ScriptRuntime}.
 */
public final class ScriptDebugger {

    /**
     * Debugger execution state.
     */
    public enum DebugMode {
        /** Free execution until breakpoint hit or prompt pause. */
        RUNNING,
        /** Suspended at an instruction or breakpoint. */
        PAUSED
    }

    /**
     * Immutable AST breakpoint target.
     *
     * @param label          target script label name
     * @param statementIndex zero-based statement index inside {@code label}
     */
    public record Breakpoint(String label, int statementIndex) {
        /**
         * Validates breakpoint label and non-negative statement index.
         */
        public Breakpoint {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Breakpoint label must not be null or blank");
            }
            if (statementIndex < 0) {
                throw new IllegalArgumentException("Breakpoint statementIndex must be >= 0, got " + statementIndex);
            }
            label = label.trim();
        }
    }

    private final Set<Breakpoint> breakpoints = new LinkedHashSet<>();
    private DebugMode mode = DebugMode.RUNNING;
    private Breakpoint lastHitBreakpoint;

    /**
     * Creates a {@code ScriptDebugger} in {@link DebugMode#RUNNING}.
     */
    public ScriptDebugger() {
    }

    /**
     * Registers a breakpoint at {@code (label, statementIndex)}.
     *
     * @param label          target script label
     * @param statementIndex zero-based statement index ({@code >= 0})
     * @return registered {@link Breakpoint}
     */
    public synchronized Breakpoint addBreakpoint(String label, int statementIndex) {
        Breakpoint bp = new Breakpoint(label, statementIndex);
        breakpoints.add(bp);
        return bp;
    }

    /**
     * Removes a previously registered breakpoint if present.
     *
     * @param label          target script label
     * @param statementIndex statement index
     * @return {@code true} if removed
     */
    public synchronized boolean removeBreakpoint(String label, int statementIndex) {
        if (label == null || label.isBlank() || statementIndex < 0) {
            return false;
        }
        return breakpoints.remove(new Breakpoint(label, statementIndex));
    }

    /**
     * Removes all registered breakpoints.
     */
    public synchronized void clearBreakpoints() {
        breakpoints.clear();
        lastHitBreakpoint = null;
    }

    /**
     * Checks whether a breakpoint is set at {@code (label, statementIndex)}.
     *
     * @param label          script label
     * @param statementIndex statement index
     * @return {@code true} if active
     */
    public synchronized boolean hasBreakpoint(String label, int statementIndex) {
        if (label == null || label.isBlank() || statementIndex < 0) {
            return false;
        }
        return breakpoints.contains(new Breakpoint(label.trim(), statementIndex));
    }

    /**
     * Returns an unmodifiable snapshot of registered breakpoints.
     *
     * @return set of active breakpoints
     */
    public synchronized Set<Breakpoint> getBreakpoints() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(breakpoints));
    }

    /**
     * Checks whether {@code runtime} is currently positioned right on a registered breakpoint,
     * pausing execution if matched.
     *
     * @param runtime active script runtime (must not be {@code null})
     * @return {@code true} if paused at a breakpoint
     */
    public synchronized boolean checkBreakpointHit(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        String label = runtime.getCurrentLabel();
        int index = runtime.getStatementIndex();
        if (label != null && hasBreakpoint(label, index)) {
            this.mode = DebugMode.PAUSED;
            this.lastHitBreakpoint = new Breakpoint(label, index);
            return true;
        }
        return false;
    }

    /**
     * Performs a single {@code Step Into} advance step and pauses at the resulting state.
     *
     * @param runtime active script runtime
     */
    public synchronized void stepInto(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        if (runtime.getStatus() == ScriptRuntime.Status.READY) {
            runtime.start();
        } else {
            runtime.advance();
        }
        this.mode = DebugMode.PAUSED;
        checkBreakpointHit(runtime);
    }

    /**
     * Advances {@code runtime} across nested calls until execution returns to the same or
     * shallower call stack depth or waits on user input/breakpoint.
     *
     * @param runtime active script runtime
     */
    public synchronized void stepOver(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        int baseDepth = runtime.getCallStackDepth();
        stepInto(runtime);
        int guard = 0;
        while (runtime.getCallStackDepth() > baseDepth
                && runtime.getStatus() != ScriptRuntime.Status.ENDED
                && runtime.getStatus() != ScriptRuntime.Status.WAITING_MENU
                && runtime.getStatus() != ScriptRuntime.Status.WAITING_INPUT
                && guard++ < 512) {
            if (checkBreakpointHit(runtime)) {
                return;
            }
            runtime.advance();
        }
        this.mode = DebugMode.PAUSED;
    }

    /**
     * Resumes execution from {@link DebugMode#PAUSED} until the next breakpoint or end of script.
     *
     * @param runtime active script runtime
     */
    public synchronized void continueExecution(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        this.mode = DebugMode.RUNNING;
        this.lastHitBreakpoint = null;
        if (runtime.getStatus() == ScriptRuntime.Status.READY) {
            runtime.start();
            checkBreakpointHit(runtime);
        } else if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE
                || runtime.getStatus() == ScriptRuntime.Status.PAUSING) {
            runtime.advance();
            checkBreakpointHit(runtime);
        }
    }

    /**
     * Returns whether execution is currently paused in the debugger.
     *
     * @return {@code true} when {@link DebugMode#PAUSED}
     */
    public synchronized boolean isPaused() {
        return mode == DebugMode.PAUSED;
    }

    /**
     * Returns the current {@link DebugMode}.
     *
     * @return active debug mode
     */
    public synchronized DebugMode getMode() {
        return mode;
    }

    /**
     * Returns the most recently triggered breakpoint, if any.
     *
     * @return optional last hit breakpoint
     */
    public synchronized Optional<Breakpoint> getLastHitBreakpoint() {
        return Optional.ofNullable(lastHitBreakpoint);
    }

    /**
     * Captures a human-readable call stack summary for {@code runtime}.
     *
     * @param runtime active script runtime
     * @return immutable list of stack frame labels with active frame last
     */
    public List<String> captureCallStackSummary(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        List<String> frames = new ArrayList<>(runtime.getCallStackLabels());
        String current = runtime.getCurrentLabel();
        if (current != null) {
            frames.add(current + ":" + runtime.getStatementIndex());
        }
        return Collections.unmodifiableList(frames);
    }
}
