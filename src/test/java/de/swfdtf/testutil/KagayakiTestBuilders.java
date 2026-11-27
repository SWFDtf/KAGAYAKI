package de.swfdtf.testutil;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only fluent builders reducing boilerplate across Wave P2-12 test suites ({@code #0975}).
 */
public final class KagayakiTestBuilders {

    private KagayakiTestBuilders() {
    }

    /**
     * Creates a new {@link SaveSlotBuilder}.
     *
     * @param slotId save slot identifier
     * @return fluent builder instance
     */
    public static SaveSlotBuilder saveSlot(String slotId) {
        return new SaveSlotBuilder(slotId);
    }

    /**
     * Creates a {@link ProjectDescriptorBuilder} with sensible test defaults.
     *
     * @param title project title
     * @return fluent builder instance
     */
    public static ProjectDescriptorBuilder descriptor(String title) {
        return new ProjectDescriptorBuilder(title);
    }

    /**
     * Creates a {@link SpriteNode} positioned cleanly at {@code (x, y)}.
     *
     * @param tag sprite tag
     * @param x   X coordinate
     * @param y   Y coordinate
     * @return configured {@link SpriteNode}
     */
    public static SpriteNode sprite(String tag, float x, float y) {
        SpriteNode node = new SpriteNode("sprite:" + tag, tag);
        node.getTransform().setXpos(x).setYpos(y);
        return node;
    }

    /**
     * Creates a seeded {@link RevertableStateStore} from key-value pairs.
     *
     * @param entries map of initial state keys and values
     * @return initialized {@link RevertableStateStore}
     */
    public static RevertableStateStore stateStore(Map<String, Object> entries) {
        RevertableStateStore store = new RevertableStateStore();
        entries.forEach(store::set);
        return store;
    }

    /** Fluent test builder for {@link SaveSlotData}. */
    public static final class SaveSlotBuilder {

        private final String slotId;
        private String customName = "Test Save";
        private String currentLabel = "start";
        private double playtimeSeconds = 10.0;
        private String gameVersion = "1.0.0";
        private final Map<String, Object> variables = new LinkedHashMap<>();

        private SaveSlotBuilder(String slotId) {
            this.slotId = slotId;
        }

        /** Sets custom save slot name. @param name display name @return this builder */
        public SaveSlotBuilder customName(String name) {
            this.customName = name;
            return this;
        }

        /** Sets current label. @param label label name @return this builder */
        public SaveSlotBuilder label(String label) {
            this.currentLabel = label;
            return this;
        }

        /** Sets game version. @param version semver string @return this builder */
        public SaveSlotBuilder gameVersion(String version) {
            this.gameVersion = version;
            return this;
        }

        /** Adds a variable to the save snapshot. @param key var key @param val value @return this builder */
        public SaveSlotBuilder var(String key, Object val) {
            this.variables.put(key, val);
            return this;
        }

        /** Builds immutable {@link SaveSlotData}. @return built {@link SaveSlotData} */
        public SaveSlotData build() {
            SaveMetadata meta = new SaveMetadata(
                    slotId, customName, Instant.now(), currentLabel,
                    "Preview line", playtimeSeconds, gameVersion, 0, 0, new byte[0]
            );
            SavedScriptPosition pos = new SavedScriptPosition(
                    currentLabel, 0, List.of(), "WAITING_DIALOGUE",
                    "lena", "Lena", "Hello", false, List.of(), null, null
            );
            return new SaveSlotData(meta, Map.copyOf(variables), pos, List.of());
        }
    }

    /** Fluent test builder for {@link ProjectDescriptor}. */
    public static final class ProjectDescriptorBuilder {

        private final String title;
        private String version = "1.0.0";
        private String renderMode = "2D";

        private ProjectDescriptorBuilder(String title) {
            this.title = title;
        }

        /** Sets project version. @param v version string @return this builder */
        public ProjectDescriptorBuilder version(String v) {
            this.version = v;
            return this;
        }

        /** Sets render mode. @param mode 2D or 3D @return this builder */
        public ProjectDescriptorBuilder renderMode(String mode) {
            this.renderMode = mode;
            return this;
        }

        /** Builds normalized {@link ProjectDescriptor}. @return descriptor */
        public ProjectDescriptor build() {
            ProjectDescriptor desc = new ProjectDescriptor();
            desc.setTitle(title);
            desc.setVersion(version);
            desc.setRenderMode(renderMode);
            desc.normalizeDefaults();
            return desc;
        }
    }
}
