package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.EndingsManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Built-in Dark-Slate Endings Tree screen ({@code Z = 400}, modal) visualizing all registered
 * {@link EndingsManager.EndingDef} story branches, parent-child links, unlock statuses, and
 * overall completion percentage.
 */
public class EndingsTreeScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "endings_tree";

    /**
     * Flattened tree node representation for rendering and verification.
     */
    public record TreeNodeView(
            EndingsManager.EndingDef ending,
            int depth,
            boolean unlocked,
            TextWidget titleLabel,
            TextWidget statusLabel
    ) {
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final TextWidget completionLabel;
    private final Bar completionBar;
    private final Viewport viewport;
    private final VBox treeContainer;
    private final List<TreeNodeView> nodeViews = new ArrayList<>();

    /**
     * Creates an {@code EndingsTreeScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public EndingsTreeScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(160.0f, 80.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(16.0f);
        mainBox.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(18.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Story Endings Tree", 34.0f));
        this.completionLabel = UiMediaSupport.createMutedText("0.0% Complete", 22.0f);
        header.addChild(completionLabel);
        header.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        this.completionBar = new Bar(0.0f, 100.0f);
        this.completionBar.setPreferredSize(1500.0f, 18.0f);
        this.completionBar.setFillColorHex(ThemePalette.SUCCESS);

        this.viewport = new Viewport();
        this.viewport.setPreferredSize(1500.0f, 680.0f);
        this.treeContainer = new VBox();
        this.treeContainer.setSpacing(10.0f);
        this.treeContainer.setFillWidth(true);
        this.viewport.addChild(treeContainer);

        mainBox.addChild(header);
        mainBox.addChild(completionBar);
        mainBox.addChild(viewport);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshTree();
    }

    @Override
    public void onShow() {
        refreshTree();
    }

    /**
     * Rebuilds the parent-child ending hierarchy and completion metrics from {@link EndingsManager}.
     */
    public void refreshTree() {
        EndingsManager em = session.getEndingsManager();
        double pct = em.getCompletionPercentage();
        completionLabel.setText(String.format(java.util.Locale.ROOT,
                "%.1f%% Complete (%d / %d)", pct, em.getUnlockedCount(), em.getTotalCount()));
        completionBar.setRange(100.0f);
        completionBar.setValue((float) pct);

        treeContainer.clearChildren();
        nodeViews.clear();

        List<EndingsManager.EndingDef> all = em.getAllEndings();
        if (all.isEmpty()) {
            treeContainer.addChild(UiMediaSupport.createMutedText("No endings registered.", 22.0f));
            return;
        }

        Set<String> visited = new HashSet<>();
        for (EndingsManager.EndingDef def : all) {
            boolean isRoot = def.parentEndingId() == null || def.parentEndingId().isBlank()
                    || em.getEnding(def.parentEndingId()).isEmpty();
            if (isRoot) {
                appendBranch(em, def, 0, visited);
            }
        }
        for (EndingsManager.EndingDef def : all) {
            if (!visited.contains(def.id())) {
                appendBranch(em, def, 0, visited);
            }
        }
    }

    private void appendBranch(
            EndingsManager em,
            EndingsManager.EndingDef def,
            int depth,
            Set<String> visited
    ) {
        if (!visited.add(def.id())) {
            return;
        }
        boolean unlocked = em.isUnlocked(def.id());

        Frame card = new Frame();
        UiMediaSupport.styleElevatedCard(card);
        card.setPadding(16.0f + depth * 36.0f, 10.0f, 16.0f, 10.0f);

        VBox box = new VBox();
        box.setSpacing(4.0f);

        HBox row = new HBox();
        row.setSpacing(14.0f);
        row.setAlignCenterY(true);

        String prefix = depth == 0 ? "" : "└─ ";
        String branchTag = (def.branchLabel() != null && !def.branchLabel().isBlank())
                ? "[" + def.branchLabel() + "] "
                : "";
        String title = unlocked ? prefix + branchTag + def.title() : prefix + branchTag + "Locked Ending";

        TextWidget titleLbl = UiMediaSupport.createTitleText(title, 22.0f);
        TextWidget statusLbl = UiMediaSupport.createTitleText(unlocked ? "UNLOCKED" : "LOCKED", 18.0f);
        statusLbl.setColorHex(unlocked ? ThemePalette.SUCCESS : ThemePalette.TEXT_MUTED);

        row.addChild(titleLbl);
        row.addChild(statusLbl);

        String desc = unlocked && def.description() != null
                ? def.description()
                : "Discover this story branch to unlock.";
        TextWidget descLbl = UiMediaSupport.createMutedText(desc, 17.0f);

        box.addChild(row);
        box.addChild(descLbl);
        card.addChild(box);
        treeContainer.addChild(card);

        nodeViews.add(new TreeNodeView(def, depth, unlocked, titleLbl, statusLbl));

        for (EndingsManager.EndingDef child : em.getChildEndings(def.id())) {
            appendBranch(em, child, depth + 1, visited);
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

    public List<TreeNodeView> getNodeViews() {
        return Collections.unmodifiableList(nodeViews);
    }

    public Bar getCompletionBar() {
        return completionBar;
    }

    public TextWidget getCompletionLabel() {
        return completionLabel;
    }
}
