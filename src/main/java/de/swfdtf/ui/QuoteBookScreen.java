package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.state.QuoteBook;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;

/**
 * Quote-Book modal screen ({@code Z = 460}) displaying player-bookmarked dialogue lines.
 */
public class QuoteBookScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "quote_book";

    private final QuoteBook quoteBook;
    private final ScreenManager screenManager;
    private final VBox quotesContainer;

    /**
     * Creates a {@code QuoteBookScreen}.
     *
     * @param quoteBook     persistent quote-book store
     * @param screenManager UI screen manager
     */
    public QuoteBookScreen(QuoteBook quoteBook, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL + 60, true);
        this.quoteBook = Objects.requireNonNull(quoteBook, "quoteBook must not be null");
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
        header.addChild(UiMediaSupport.createTitleText("Zitatebuch", 34.0f));
        header.addChild(UiMediaSupport.createActionButton("Zurück", 140.0f, 40.0f, this::closeScreen));

        Viewport viewport = new Viewport();
        viewport.setPreferredSize(1320.0f, 660.0f);
        this.quotesContainer = new VBox();
        this.quotesContainer.setSpacing(12.0f);
        this.quotesContainer.setFillWidth(true);
        viewport.addChild(this.quotesContainer);

        layout.addChild(header);
        layout.addChild(viewport);
        panel.addChild(layout);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
    }

    @Override
    public void onShow() {
        refreshQuotes();
    }

    /**
     * Rebuilds visible quote cards from {@link QuoteBook}.
     */
    public void refreshQuotes() {
        quotesContainer.clearChildren();
        List<QuoteBook.QuoteEntry> quotes = quoteBook.listQuotes();
        if (quotes.isEmpty()) {
            TextWidget empty = new TextWidget("Noch keine Zitate mit [B] gespeichert.");
            empty.setFontSize(20.0f).setColorHex(ThemePalette.TEXT_MUTED);
            quotesContainer.addChild(empty);
        } else {
            for (QuoteBook.QuoteEntry entry : quotes) {
                Frame card = new Frame();
                card.setBackgroundColorHex(ThemePalette.ELEVATED)
                        .setBorderColorHex(ThemePalette.BORDER)
                        .setBorderThickness(1.0f)
                        .setCornerRadius(6.0f)
                        .setPadding(14.0f);
                VBox cardCol = new VBox();
                cardCol.setSpacing(6.0f);
                String prefix = entry.speaker().isEmpty() ? "" : entry.speaker() + ": ";
                TextWidget quoteTxt = new TextWidget(prefix + entry.text());
                quoteTxt.setFontSize(22.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
                cardCol.addChild(quoteTxt);
                card.addChild(cardCol);
                quotesContainer.addChild(card);
            }
        }
        if (screenManager != null) {
            layout(screenManager.getLogicalWidth(), screenManager.getLogicalHeight());
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
