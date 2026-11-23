package de.swfdtf.core;

/**
 * Execution mode of the KAGAYAKI runtime.
 */
public enum EngineMode {

    /**
     * Development and authoring mode presenting both the Studio tool window (Window A)
     * and the live game execution window (Window B).
     */
    STUDIO_DUAL_WINDOW,

    /**
     * Exported or standalone runtime mode presenting only the game window (Window B)
     * without Studio authoring overhead.
     */
    STANDALONE_GAME
}
