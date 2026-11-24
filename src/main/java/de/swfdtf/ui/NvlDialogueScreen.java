package de.swfdtf.ui;

import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Full-screen NVL (Novel) cumulative dialogue presentation screen ({@code Z = 200}).
 *
 * <p>Covers the stage with a translucent dark overlay ({@link ThemePalette#PANEL_GLASS}) and
 * accumulates multi-paragraph speaker and narration entries from {@link ScriptRuntime#getNvlLines()}
 * inside a scrollable {@link Viewport} until an {@code nvl clear} statement empties the buffer.</p>
 */
public class NvlDialogueScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "nvl_dialogue";

    private final ScriptSession session;
    private final Fixed rootContainer;
    private final Frame backdrop;
    private final Viewport viewport;
    private final VBox paragraphBox;

    private List<String> lastDisplayedLines = List.of();
    private final List<TextWidget> paragraphWidgets = new ArrayList<>();
    private StyledTextLayout.TypewriterController latestFallbackTypewriter;
    private ScriptRuntime.DialoguePrompt lastPrompt;

    /**
     * Creates an {@code NvlDialogueScreen} bound to {@code session}.
     *
     * @param session active script session
     */
    public NvlDialogueScreen(ScriptSession session) {
        super(SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        this.session = Objects.requireNonNull(session, "session must not be null");

        this.rootContainer = new Fixed();

        this.backdrop = new Frame() {
            @Override
            protected boolean onMouseDown(float lx, float ly, int button) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    handlePrimaryAdvance();
                    return true;
                }
                return false;
            }
        };
        this.backdrop.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setPadding(240.0f, 80.0f);

        this.viewport = new Viewport();
        this.viewport.setPadding(20.0f);

        this.paragraphBox = new VBox();
        this.paragraphBox.setSpacing(22.0f);
        this.paragraphBox.setFillWidth(true);

        this.viewport.addChild(this.paragraphBox);
        this.backdrop.addChild(this.viewport);
        this.rootContainer.addChildAt(this.backdrop, 0.0f, 0.0f, 1920.0f, 1080.0f);

        setRoot(this.rootContainer);

        addKeyBinding(GLFW.GLFW_KEY_SPACE, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_ENTER, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_KP_ENTER, this::handlePrimaryAdvance);
    }

    @Override
    public void onShow() {
        syncFromRuntime(true);
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        syncFromRuntime(false);

        boolean hasWidgetTw = !paragraphWidgets.isEmpty()
                && paragraphWidgets.get(paragraphWidgets.size() - 1).getTypewriter() != null;
        super.update(dt);
        if (!hasWidgetTw && latestFallbackTypewriter != null && dt >= 0.0) {
            latestFallbackTypewriter.advance(dt);
        }
    }

    /**
     * Finishes the newest paragraph's typewriter reveal, or advances the script if complete.
     */
    public void handlePrimaryAdvance() {
        StyledTextLayout.TypewriterController tw = resolveLatestTypewriter();
        if (tw != null && !tw.isFinished()) {
            tw.clickOrAdvance();
            return;
        }
        session.advance();
        syncFromRuntime(false);
    }

    /**
     * Synchronizes accumulated paragraphs with {@link ScriptRuntime#getNvlLines()}.
     */
    public void syncFromRuntime(boolean force) {
        ScriptRuntime runtime = session.getRuntime();
        List<String> runtimeNvlLines = runtime.getNvlLines();
        ScriptRuntime.DialoguePrompt activeDialogue = runtime.getActiveDialogue();

        List<String> targetLines;
        if (!runtimeNvlLines.isEmpty()) {
            targetLines = runtimeNvlLines;
        } else if (activeDialogue != null && !runtime.isNvlMode()) {
            String speaker = activeDialogue.speakerName();
            String single = (speaker != null && !speaker.isBlank())
                    ? speaker + ": " + activeDialogue.interpolatedText()
                    : activeDialogue.interpolatedText();
            targetLines = List.of(single);
        } else {
            targetLines = List.of();
        }

        boolean promptChanged = !Objects.equals(activeDialogue, lastPrompt);
        if (!force && targetLines.equals(lastDisplayedLines) && !promptChanged) {
            return;
        }

        lastPrompt = activeDialogue;
        lastDisplayedLines = List.copyOf(targetLines);
        rebuildParagraphs(targetLines);

        if (promptChanged && activeDialogue != null && session.getPreferencesState().isSelfVoicing()) {
            TextToSpeechService tts = UiMediaSupport.findTtsService(session);
            if (tts != null) {
                tts.setEnabled(true);
                tts.speak(activeDialogue.interpolatedText());
            }
        }
    }

    private void rebuildParagraphs(List<String> lines) {
        paragraphBox.clearChildren();
        paragraphWidgets.clear();
        latestFallbackTypewriter = null;

        if (lines.isEmpty()) {
            viewport.setScrollY(0.0f);
            return;
        }

        float cps = session.getPreferencesState().getTextSpeedCps();
        for (int i = 0; i < lines.size(); i++) {
            boolean isLatest = (i == lines.size() - 1);
            String line = lines.get(i);
            TextWidget entry = new TextWidget(line);
            entry.setFontSize(28.0f);
            entry.setColorHex(isLatest ? ThemePalette.TEXT_PRIMARY : ThemePalette.TEXT_MUTED);
            entry.setDropShadow(true);
            if (isLatest) {
                entry.setTypewriterCps(cps);
                latestFallbackTypewriter = new StyledTextLayout.TypewriterController(
                        TextTagParser.parse(line), cps
                );
            } else {
                entry.setTypewriterCps(0.0f);
            }
            paragraphWidgets.add(entry);
            paragraphBox.addChild(entry);
        }
        viewport.setScrollY(viewport.getMaxScrollY());
    }

    private StyledTextLayout.TypewriterController resolveLatestTypewriter() {
        if (paragraphWidgets.isEmpty()) {
            return null;
        }
        TextWidget latest = paragraphWidgets.get(paragraphWidgets.size() - 1);
        return latest.getTypewriter() != null ? latest.getTypewriter() : latestFallbackTypewriter;
    }

    /** @return immutable snapshot of currently displayed NVL paragraph strings */
    public List<String> getEntries() {
        return lastDisplayedLines;
    }

    /** @return number of accumulated NVL paragraphs currently shown */
    public int getEntryCount() {
        return paragraphWidgets.size();
    }

    /** @return {@code true} if the latest NVL paragraph has finished typing */
    public boolean isLatestTypewriterFinished() {
        StyledTextLayout.TypewriterController tw = resolveLatestTypewriter();
        return tw == null || tw.isFinished();
    }

    public Viewport getViewport() {
        return viewport;
    }

    public VBox getParagraphContainer() {
        return paragraphBox;
    }

    public List<TextWidget> getParagraphWidgets() {
        return Collections.unmodifiableList(paragraphWidgets);
    }
}
