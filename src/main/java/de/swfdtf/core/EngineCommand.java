package de.swfdtf.core;

/**
 * Reversible command contract for Studio editing and engine state mutations managed by {@link CommandStack}.
 */
public interface EngineCommand {

    /**
     * Returns a concise human-readable description of this command for undo/redo history UI.
     *
     * @return command description
     */
    String description();

    /**
     * Applies (or re-applies) the command action.
     */
    void execute();

    /**
     * Reverts the changes previously applied by {@link #execute()}.
     */
    void undo();
}
