package de.swfdtf.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded undo/redo command history stack for Studio authoring tools and reversible engine operations.
 *
 * <p>Executing a new {@link EngineCommand} clears any pending redo history and trims the oldest
 * undo entry whenever the configured {@code maxDepth} is exceeded.</p>
 */
public final class CommandStack {

    /**
     * Default maximum number of retained undo commands.
     */
    public static final int DEFAULT_MAX_DEPTH = 100;

    private final int maxDepth;
    private final Deque<EngineCommand> undoStack = new ArrayDeque<>();
    private final Deque<EngineCommand> redoStack = new ArrayDeque<>();

    /**
     * Creates a {@link CommandStack} with {@link #DEFAULT_MAX_DEPTH}.
     */
    public CommandStack() {
        this(DEFAULT_MAX_DEPTH);
    }

    /**
     * Creates a {@link CommandStack} with a custom maximum undo depth.
     *
     * @param maxDepth maximum number of undoable commands retained (must be {@code >= 1})
     */
    public CommandStack(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got: " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    /**
     * Returns the configured maximum undo history depth.
     *
     * @return maximum undo stack depth
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Executes {@code command}, pushes it onto the undo stack, clears the redo stack,
     * and trims the oldest undo entry if {@link #getMaxDepth()} is exceeded.
     *
     * @param command command to execute (must not be null)
     */
    public void execute(EngineCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        command.execute();
        redoStack.clear();
        undoStack.addLast(command);
        while (undoStack.size() > maxDepth) {
            undoStack.pollFirst();
        }
    }

    /**
     * Returns whether at least one command is available to undo.
     *
     * @return {@code true} if {@link #undo()} can revert a command
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Returns whether at least one previously undone command is available to redo.
     *
     * @return {@code true} if {@link #redo()} can re-execute a command
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Undoes the most recently executed command and moves it to the redo stack.
     *
     * @return {@code true} if a command was undone, or {@code false} if the undo stack was empty
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        EngineCommand command = undoStack.peekLast();
        command.undo();
        undoStack.pollLast();
        redoStack.addLast(command);
        return true;
    }

    /**
     * Re-executes the most recently undone command and moves it back to the undo stack.
     *
     * @return {@code true} if a command was redone, or {@code false} if the redo stack was empty
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        EngineCommand command = redoStack.peekLast();
        command.execute();
        redoStack.pollLast();
        undoStack.addLast(command);
        return true;
    }

    /**
     * Returns the description of the next command that would be reverted by {@link #undo()}.
     *
     * @return optional undo description
     */
    public Optional<String> nextUndoDescription() {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(undoStack.peekLast().description());
    }

    /**
     * Returns the description of the next command that would be re-applied by {@link #redo()}.
     *
     * @return optional redo description
     */
    public Optional<String> nextRedoDescription() {
        if (redoStack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redoStack.peekLast().description());
    }

    /**
     * Returns an unmodifiable snapshot of undo command descriptions in execution order
     * from oldest to most recent.
     *
     * @return unmodifiable list of undo descriptions
     */
    public List<String> undoHistoryDescriptions() {
        if (undoStack.isEmpty()) {
            return List.of();
        }
        List<String> descriptions = new ArrayList<>(undoStack.size());
        for (EngineCommand command : undoStack) {
            String desc = command.description();
            descriptions.add(desc != null ? desc : "");
        }
        return Collections.unmodifiableList(descriptions);
    }

    /**
     * Returns the number of commands currently on the undo stack.
     *
     * @return undo stack size
     */
    public int undoSize() {
        return undoStack.size();
    }

    /**
     * Returns the number of commands currently on the redo stack.
     *
     * @return redo stack size
     */
    public int redoSize() {
        return redoStack.size();
    }

    /**
     * Clears both the undo and redo stacks.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
