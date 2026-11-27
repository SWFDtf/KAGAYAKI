package de.swfdtf.tools;

import de.swfdtf.graphics.WindowManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages optional undocked secondary/tertiary Studio windows (Flow Graph, Live Variables,
 * Script Linter, Console) with strict OpenGL context isolation from Window B ({@code #0163}).
 */
public final class StudioUndockedWindowManager {

    /** Studio tool panels eligible for multi-monitor undocking. */
    public enum DockablePanel {
        /** Interactive Story Flow Graph panel. */
        FLOW_GRAPH("KAGAYAKI Studio - Flow Graph", 960, 720),
        /** Live StateStore / Persistent variable inspector panel. */
        LIVE_VARIABLES("KAGAYAKI Studio - Live Variables", 680, 640),
        /** Project lint diagnostics panel. */
        SCRIPT_LINTER("KAGAYAKI Studio - Script Linter", 760, 560),
        /** Engine log and live Java evaluation console panel. */
        CONSOLE("KAGAYAKI Studio - Console", 820, 520);

        private final String defaultTitle;
        private final int defaultWidth;
        private final int defaultHeight;

        DockablePanel(String defaultTitle, int defaultWidth, int defaultHeight) {
            this.defaultTitle = defaultTitle;
            this.defaultWidth = defaultWidth;
            this.defaultHeight = defaultHeight;
        }

        /**
         * Returns the window title for this undocked panel.
         *
         * @return panel window title
         */
        public String getDefaultTitle() {
            return defaultTitle;
        }

        /**
         * Returns the default window width in screen coordinates.
         *
         * @return width
         */
        public int getDefaultWidth() {
            return defaultWidth;
        }

        /**
         * Returns the default window height in screen coordinates.
         *
         * @return height
         */
        public int getDefaultHeight() {
            return defaultHeight;
        }
    }

    /**
     * Explicit OpenGL 3.3 context isolation descriptor enforcing context-current switching and
     * separate VAO/FBO namespaces between Window B (Live Game) and each undocked panel.
     *
     * @param contextId                  unique logical OpenGL context identifier
     * @param shareGroupOwnerHandle      optional shared texture-namespace owner handle (Window B)
     * @param isolatedFromGameContext    always {@code true} to enforce context isolation
     * @param requiresContextSwitchOnDraw always {@code true} so callers bind this window context before draw
     */
    public record GlContextIsolationToken(
            String contextId,
            long shareGroupOwnerHandle,
            boolean isolatedFromGameContext,
            boolean requiresContextSwitchOnDraw
    ) {
        /** Enforces strict context isolation invariants. */
        public GlContextIsolationToken {
            Objects.requireNonNull(contextId, "contextId must not be null");
            if (!isolatedFromGameContext || !requiresContextSwitchOnDraw) {
                throw new IllegalArgumentException("Undocked panels must retain strict OpenGL context isolation");
            }
        }
    }

    /**
     * State descriptor for an active undocked Studio panel window.
     *
     * @param panel        dockable panel type
     * @param title        native window title
     * @param monitorIndex clamped target display monitor index
     * @param x            logical screen X offset on target monitor
     * @param y            logical screen Y offset on target monitor
     * @param width        window width in logical screen coordinates
     * @param height       window height in logical screen coordinates
     * @param glContext    isolated OpenGL context token
     * @param open         whether this undocked window is currently open
     */
    public record UndockedPanelWindow(
            DockablePanel panel,
            String title,
            int monitorIndex,
            int x,
            int y,
            int width,
            int height,
            GlContextIsolationToken glContext,
            boolean open
    ) {
    }

    private final Map<DockablePanel, UndockedPanelWindow> undockedWindows = new LinkedHashMap<>();

    /** Creates a {@code StudioUndockedWindowManager}. */
    public StudioUndockedWindowManager() {
    }

    /**
     * Undocks {@code panel} onto the target monitor index while preserving strict OpenGL context
     * isolation from {@code gameWindowHandle}.
     *
     * @param panel              panel to detach into a dedicated window
     * @param monitors           available OS display monitors (may be empty in headless mode)
     * @param targetMonitorIndex preferred zero-based monitor index (clamped safely)
     * @param gameWindowHandle   Window B GLFW handle used only as shared texture owner reference
     * @return descriptor of the opened {@link UndockedPanelWindow}
     */
    public UndockedPanelWindow undock(
            DockablePanel panel,
            List<WindowManager.MonitorInfo> monitors,
            int targetMonitorIndex,
            long gameWindowHandle
    ) {
        Objects.requireNonNull(panel, "panel must not be null");
        List<WindowManager.MonitorInfo> safeMonitors = monitors != null ? monitors : List.of();

        int resolvedMonitor = 0;
        int originX = 40;
        int originY = 40;
        if (!safeMonitors.isEmpty()) {
            resolvedMonitor = Math.clamp(targetMonitorIndex, 0, safeMonitors.size() - 1);
            int offsetX = 0;
            for (int i = 0; i < resolvedMonitor; i++) {
                offsetX += safeMonitors.get(i).width();
            }
            originX = offsetX + 40;
            originY = 40;
        }

        GlContextIsolationToken token = new GlContextIsolationToken(
                "studio_ctx_" + panel.name().toLowerCase(java.util.Locale.ROOT) + "_mon" + resolvedMonitor,
                gameWindowHandle,
                true,
                true
        );

        UndockedPanelWindow window = new UndockedPanelWindow(
                panel,
                panel.getDefaultTitle(),
                resolvedMonitor,
                originX,
                originY,
                panel.getDefaultWidth(),
                panel.getDefaultHeight(),
                token,
                true
        );
        undockedWindows.put(panel, window);
        return window;
    }

    /**
     * Re-docks {@code panel} back into the main Studio window and closes its detached descriptor.
     *
     * @param panel panel to re-dock
     * @return {@code true} if the panel was previously undocked
     */
    public boolean redock(DockablePanel panel) {
        Objects.requireNonNull(panel, "panel must not be null");
        return undockedWindows.remove(panel) != null;
    }

    /**
     * Checks whether {@code panel} is currently undocked.
     *
     * @param panel panel identifier
     * @return {@code true} if undocked
     */
    public boolean isUndocked(DockablePanel panel) {
        UndockedPanelWindow win = undockedWindows.get(panel);
        return win != null && win.open();
    }

    /**
     * Returns an immutable list of all currently open undocked panel windows.
     *
     * @return immutable list of {@link UndockedPanelWindow}
     */
    public List<UndockedPanelWindow> getUndockedWindows() {
        return List.copyOf(undockedWindows.values());
    }

    /** Closes and clears all undocked Studio windows. */
    public void closeAll() {
        undockedWindows.clear();
    }
}
