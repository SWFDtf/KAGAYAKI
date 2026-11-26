package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.ChapterRegistry;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * Chapter Selection modal screen ({@code Z = 460}) listing registered {@link ChapterRegistry} chapters,
 * disabling locked chapters and jumping cleanly to unlocked chapter entry labels.
 */
public class ChapterSelectScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "chapter_select";

    private final ChapterRegistry chapterRegistry;
    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final VBox chapterList;

    /**
     * Creates a {@code ChapterSelectScreen}.
     *
     * @param chapterRegistry chapter registry
     * @param session         active script session
     * @param screenManager   UI screen manager
     */
    public ChapterSelectScreen(
            ChapterRegistry chapterRegistry,
            ScriptSession session,
            ScreenManager screenManager
    ) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL + 60, true);
        this.chapterRegistry = Objects.requireNonNull(chapterRegistry, "chapterRegistry must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(240.0f, 110.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox layout = new VBox();
        layout.setSpacing(18.0f);
        layout.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Kapitelauswahl", 34.0f));
        header.addChild(UiMediaSupport.createActionButton("Zurück", 140.0f, 40.0f, this::closeScreen));

        Viewport viewport = new Viewport();
        viewport.setPreferredSize(1320.0f, 660.0f);
        this.chapterList = new VBox();
        this.chapterList.setSpacing(12.0f);
        this.chapterList.setFillWidth(true);
        viewport.addChild(this.chapterList);

        layout.addChild(header);
        layout.addChild(viewport);
        panel.addChild(layout);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
    }

    @Override
    public void onShow() {
        refreshChapters();
    }

    /**
     * Rebuilds interactive chapter buttons from {@link ChapterRegistry}.
     */
    public void refreshChapters() {
        chapterList.clearChildren();
        for (ChapterRegistry.ChapterDef chapter : chapterRegistry.listAll()) {
            boolean unlocked = chapterRegistry.isUnlocked(chapter.id());
            String label = unlocked ? chapter.title() : chapter.title() + " (Gesperrt)";
            TextButton btn = UiMediaSupport.createActionButton(
                    label,
                    600.0f,
                    50.0f,
                    () -> launchChapter(chapter)
            );
            btn.setEnabled(unlocked);
            if (!unlocked) {
                btn.setAlpha(0.45f);
            }
            chapterList.addChild(btn);
        }
        if (screenManager != null) {
            layout(screenManager.getLogicalWidth(), screenManager.getLogicalHeight());
        }
    }

    private void launchChapter(ChapterRegistry.ChapterDef chapter) {
        if (!chapterRegistry.isUnlocked(chapter.id())) {
            return;
        }
        closeScreen();
        if (screenManager != null) {
            screenManager.hideScreen(MainMenuScreen.SCREEN_NAME);
        }
        if (chapter.startLabel() != null && !chapter.startLabel().isBlank()) {
            session.getRuntime().jumpToLabel(chapter.startLabel());
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
}
