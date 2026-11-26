package de.swfdtf.ui;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * Built-in Dark-Slate About screen ({@code Z = 400}, modal) displaying project title, version,
 * author, {@link EngineAPI#ENGINE_VERSION}, {@link EngineAPI#RENDER_BACKEND_NAME}, active
 * {@link RenderMode}, and open-source library license attributions.
 */
public class AboutScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "about";

    private final ScriptSession session;
    private final ScreenManager screenManager;

    private final TextWidget projectTitleValue;
    private final TextWidget projectVersionValue;
    private final TextWidget authorValue;
    private final TextWidget engineVersionValue;
    private final TextWidget backendValue;
    private final TextWidget renderModeValue;
    private final TextWidget licenseSummary;

    /**
     * Creates an {@code AboutScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager (nullable)
     */
    public AboutScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(240.0f, 110.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox content = new VBox();
        content.setSpacing(16.0f);
        content.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("About", 36.0f));
        header.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        this.projectTitleValue = UiMediaSupport.createTitleText("", 28.0f);
        this.projectVersionValue = UiMediaSupport.createTitleText("", 22.0f);
        this.authorValue = UiMediaSupport.createTitleText("", 22.0f);
        this.engineVersionValue = UiMediaSupport.createTitleText(EngineAPI.ENGINE_VERSION, 22.0f);
        this.backendValue = UiMediaSupport.createTitleText(EngineAPI.RENDER_BACKEND_NAME, 22.0f);
        this.renderModeValue = UiMediaSupport.createTitleText("", 22.0f);

        Frame infoCard = new Frame();
        UiMediaSupport.styleElevatedCard(infoCard);
        VBox rows = new VBox();
        rows.setSpacing(10.0f);
        rows.addChild(projectTitleValue);
        rows.addChild(projectVersionValue);
        rows.addChild(authorValue);
        rows.addChild(engineVersionValue);
        rows.addChild(backendValue);
        rows.addChild(renderModeValue);
        infoCard.addChild(rows);

        Frame licenseCard = new Frame();
        UiMediaSupport.styleElevatedCard(licenseCard);
        this.licenseSummary = UiMediaSupport.createMutedText(
                "Open-Source Attribution: Built with Java 21, LWJGL 3 (BSD-3-Clause), OpenGL 3.3 Core, "
                        + "OpenAL Soft (LGPL-2.0+), STB (Public Domain / MIT), JOML (MIT), and Gson (Apache-2.0).",
                19.0f
        );
        licenseCard.addChild(licenseSummary);

        content.addChild(header);
        content.addChild(infoCard);
        content.addChild(licenseCard);
        panel.addChild(content);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshInfo();
    }

    @Override
    public void onShow() {
        refreshInfo();
    }

    /**
     * Refreshes displayed metadata from {@link ProjectDescriptor}, {@link EngineAPI}, and {@link RenderMode}.
     */
    public void refreshInfo() {
        EngineAPI api = session.getEngineApi();
        ProjectDescriptor desc = api.getProjectDescriptor();

        String title = desc != null ? desc.getTitle() : "KAGAYAKI Project";
        String version = desc != null ? desc.getVersion() : "1.0.0";
        String author = (desc != null && desc.getAuthor() != null && !desc.getAuthor().isBlank())
                ? desc.getAuthor()
                : "Unspecified";

        RenderMode activeMode = resolveActiveRenderMode(api);
        projectTitleValue.setText("Title: " + title);
        projectVersionValue.setText("Version: " + version);
        authorValue.setText("Author: " + author);
        engineVersionValue.setText("Engine: " + EngineAPI.ENGINE_VERSION);
        backendValue.setText("Renderer: " + EngineAPI.RENDER_BACKEND_NAME);
        renderModeValue.setText("Active RenderMode: " + activeMode.name());
    }

    private RenderMode resolveActiveRenderMode(EngineAPI api) {
        String prefMode = session.getPreferencesState().getRenderMode();
        String cfgMode = api.getConfig() != null ? api.getConfig().renderModeName() : prefMode;
        String effective = prefMode != null && !prefMode.isBlank() ? prefMode : cfgMode;
        try {
            return RenderMode.fromString(effective);
        } catch (IllegalArgumentException ignored) {
            return RenderMode.MODE_2D;
        }
    }

    private void closeScreen() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    /**
     * Returns the project title widget.
     *
     * @return title widget
     */
    public TextWidget getProjectTitleValue() {
        return projectTitleValue;
    }

    /**
     * Returns the project version widget.
     *
     * @return version widget
     */
    public TextWidget getProjectVersionValue() {
        return projectVersionValue;
    }

    /**
     * Returns the author widget.
     *
     * @return author widget
     */
    public TextWidget getAuthorValue() {
        return authorValue;
    }

    /**
     * Returns the engine version widget.
     *
     * @return engine version widget
     */
    public TextWidget getEngineVersionValue() {
        return engineVersionValue;
    }

    /**
     * Returns the render backend widget.
     *
     * @return backend widget
     */
    public TextWidget getBackendValue() {
        return backendValue;
    }

    /**
     * Returns the active render mode widget.
     *
     * @return render mode widget
     */
    public TextWidget getRenderModeValue() {
        return renderModeValue;
    }

    /**
     * Returns the license summary widget.
     *
     * @return license summary widget
     */
    public TextWidget getLicenseSummary() {
        return licenseSummary;
    }
}
