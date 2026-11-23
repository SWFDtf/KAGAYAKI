package de.swfdtf.graphics;

/**
 * Unified rendering pipeline contract abstracting 2D and 3D stage render backends.
 */
public interface IRenderPipeline {

    /**
     * Initializes GPU resources and projection state for the specified logical canvas dimensions.
     *
     * @param logicalWidth  logical canvas width (typically {@code 1920})
     * @param logicalHeight logical canvas height (typically {@code 1080})
     */
    void init(int logicalWidth, int logicalHeight);

    /**
     * Prepares the render target and OpenGL pipeline state for a new frame.
     *
     * @param windowWidth  physical drawable width of the target window framebuffer in pixels
     * @param windowHeight physical drawable height of the target window framebuffer in pixels
     */
    void beginFrame(int windowWidth, int windowHeight);

    /**
     * Renders all visible layers and nodes in {@code graph} sorted by Z-order.
     *
     * @param graph scene graph to render
     */
    void renderSceneGraph(SceneGraph graph);

    /**
     * Finalizes the frame (for example blitting the logical offscreen framebuffer onto the window
     * with aspect-preserving letterboxing/pillarboxing).
     */
    void endFrame();

    /**
     * Returns the {@link RenderMode} implemented by this pipeline.
     *
     * @return render backend mode
     */
    RenderMode getMode();

    /**
     * Releases all GPU/native resources owned by this pipeline.
     */
    void dispose();
}
