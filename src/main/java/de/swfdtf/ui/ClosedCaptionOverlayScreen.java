package de.swfdtf.ui;

import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.graphics.SceneLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Non-modal bottom-anchored overlay ({@code Z = Z_TOAST - 10}) displaying up to
 * {@value #MAX_VISIBLE_CAPTIONS} active closed captions from {@link ClosedCaptionQueue}.
 */
public class ClosedCaptionOverlayScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "closed_captions";

    /** Maximum number of caption lines displayed simultaneously. */
    public static final int MAX_VISIBLE_CAPTIONS = 3;

    private final ClosedCaptionQueue queue;
    private final Frame captionPanel;
    private final List<TextWidget> captionWidgets = new ArrayList<>(MAX_VISIBLE_CAPTIONS);

    /**
     * Creates a {@code ClosedCaptionOverlayScreen} backed by {@code queue}.
     *
     * @param queue closed-caption queue supplying active captions (must not be {@code null})
     */
    public ClosedCaptionOverlayScreen(ClosedCaptionQueue queue) {
        super(SCREEN_NAME, SceneLayer.Z_TOAST - 10, false);
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        setVisible(false);

        Fixed root = new Fixed();
        this.captionPanel = new Frame();
        this.captionPanel.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.0f)
                .setCornerRadius(8.0f)
                .setPadding(18.0f, 12.0f);

        VBox linesBox = new VBox();
        linesBox.setSpacing(6.0f);
        linesBox.setFillWidth(true);

        for (int i = 0; i < MAX_VISIBLE_CAPTIONS; i++) {
            TextWidget line = UiMediaSupport.createTitleText("", 22.0f);
            line.setVisible(false);
            line.setPreferredSize(1140.0f, 32.0f);
            captionWidgets.add(line);
            linesBox.addChild(line);
        }

        this.captionPanel.addChild(linesBox);
        root.addChildAt(this.captionPanel, 360.0f, 840.0f, 1200.0f, 160.0f);
        setRoot(root);
    }

    @Override
    public void update(double dt) {
        syncFromQueue();
        if (isVisible()) {
            super.update(dt);
        }
    }

    /**
     * Synchronizes displayed caption widgets with {@link ClosedCaptionQueue#getVisibleCaptions()},
     * retaining at most {@value #MAX_VISIBLE_CAPTIONS} newest captions and hiding when empty.
     */
    public void syncFromQueue() {
        List<ClosedCaptionQueue.CaptionEntry> visible = queue.getVisibleCaptions();
        if (visible.isEmpty()) {
            for (TextWidget tw : captionWidgets) {
                tw.setText("");
                tw.setVisible(false);
            }
            setVisible(false);
            return;
        }

        int count = Math.min(MAX_VISIBLE_CAPTIONS, visible.size());
        int start = visible.size() - count;
        for (int i = 0; i < MAX_VISIBLE_CAPTIONS; i++) {
            TextWidget tw = captionWidgets.get(i);
            if (i < count) {
                ClosedCaptionQueue.CaptionEntry entry = visible.get(start + i);
                String formatted = (entry.source() != null && !entry.source().isBlank())
                        ? entry.source() + ": " + entry.text()
                        : entry.text();
                tw.setText(formatted);
                tw.setVisible(true);
            } else {
                tw.setText("");
                tw.setVisible(false);
            }
        }
        setVisible(true);
    }

    /**
     * Returns the immutable list of reusable caption row widgets.
     *
     * @return caption row widgets
     */
    public List<TextWidget> getCaptionWidgets() {
        return List.copyOf(captionWidgets);
    }
}
