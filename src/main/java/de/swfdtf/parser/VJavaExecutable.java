package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.StateStore;

/**
 * Executable runtime contract implemented by every in-memory compiled {@code @->{ ... }<<}
 * Java block in KAGAYAKI.
 */
@FunctionalInterface
public interface VJavaExecutable {

    /**
     * Executes the compiled embedded Java block against active VN runtime facades.
     *
     * @param state      mutable variable state store exposed as {@code state}
     * @param persistent cross-save persistent storage exposed as {@code persistent}
     * @param engine     public engine facade exposed as {@code engine}
     * @param scene      stage and sprite controller exposed as {@code scene}
     * @throws Exception if the script snippet throws any checked or unchecked exception
     */
    void execute(StateStore state, PersistentAccess persistent, EngineAPI engine, SceneController scene) throws Exception;
}
