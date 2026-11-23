package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

/**
 * Extensible custom displayable contract for procedural graphics, particle emitters, live gauges,
 * and custom shaders authored either in pure Java or inside vJava {@code @->{ ... }<<} blocks.
 *
 * <p>Instances can be attached to a {@link SpriteNode} via
 * {@link SpriteNode#setCustomDisplayable(CustomDisplayable)} and rendered seamlessly within the
 * Z-ordered {@link SceneGraph} with inherited bounds, opacity, and {@link ColorMatrix4f}.</p>
 */
public interface CustomDisplayable {

    /**
     * Advances any time-dependent simulation state of this displayable.
     *
     * @param dt elapsed simulation step in seconds
     */
    void update(double dt);

    /**
     * Renders this displayable into the active {@link RenderPipeline2D}.
     *
     * @param pipeline    active 2D rendering pipeline
     * @param bounds      resolved destination bounds in logical canvas coordinates
     * @param alpha       effective inherited opacity in {@code [0, 1]}
     * @param colorMatrix effective inherited 4x4 premultiplied-alpha color transformation matrix
     */
    void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix);

    /**
     * Returns the natural preferred width in logical pixels, or {@code 0} if unconstrained.
     *
     * @return preferred width in logical pixels
     */
    default float getPreferredWidth() {
        return 0.0f;
    }

    /**
     * Returns the natural preferred height in logical pixels, or {@code 0} if unconstrained.
     *
     * @return preferred height in logical pixels
     */
    default float getPreferredHeight() {
        return 0.0f;
    }
}
