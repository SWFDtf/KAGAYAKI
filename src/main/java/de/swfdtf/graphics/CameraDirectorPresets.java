package de.swfdtf.graphics;

import de.swfdtf.math.Easing;
import de.swfdtf.math.Transform2D;

import java.util.Objects;

/**
 * Cinematic 2D camera controller providing curated {@link CameraPreset} shots and smooth
 * frame-rate-independent interpolation into {@link Transform2D} stage transforms.
 */
public final class CameraDirectorPresets {

    /**
     * Predefined cinematic camera framing presets.
     */
    public enum CameraPreset {
        /** Neutral full-frame establishing shot centered at {@code zoom = 1.0f}. */
        ESTABLISHING,
        /** Tighter close-up shot with subtle upward framing bias. */
        CLOSE_UP,
        /** Dutch-angle dramatic tilt with moderate zoom-in. */
        DRAMATIC_TILT,
        /** Horizontal pan offset shot across the stage. */
        PAN_LEFT_TO_RIGHT,
        /** Subtle rhythmic zoom-in pulse. */
        ZOOM_PULSE
    }

    /**
     * Immutable camera framing state in logical coordinates.
     *
     * @param x               horizontal camera offset in logical pixels (must be finite)
     * @param y               vertical camera offset in logical pixels (must be finite)
     * @param zoom            camera zoom multiplier (must be finite and {@code > 0.0f})
     * @param rotationDegrees clockwise camera tilt in degrees (must be finite)
     */
    public record CameraState(float x, float y, float zoom, float rotationDegrees) {

        /** Neutral default camera framing: centered, unzoomed, zero rotation. */
        public static final CameraState DEFAULT = new CameraState(0.0f, 0.0f, 1.0f, 0.0f);

        /**
         * Validates finiteness and positive zoom constraints.
         */
        public CameraState {
            if (!Float.isFinite(x)) {
                throw new IllegalArgumentException("x must be finite, was: " + x);
            }
            if (!Float.isFinite(y)) {
                throw new IllegalArgumentException("y must be finite, was: " + y);
            }
            if (!Float.isFinite(zoom) || zoom <= 0.0f) {
                throw new IllegalArgumentException("zoom must be finite and > 0.0, was: " + zoom);
            }
            if (!Float.isFinite(rotationDegrees)) {
                throw new IllegalArgumentException("rotationDegrees must be finite, was: " + rotationDegrees);
            }
        }
    }

    private static final CameraState PRESET_ESTABLISHING = CameraState.DEFAULT;
    private static final CameraState PRESET_CLOSE_UP = new CameraState(0.0f, -45.0f, 1.35f, 0.0f);
    private static final CameraState PRESET_DRAMATIC_TILT = new CameraState(0.0f, 0.0f, 1.15f, -4.5f);
    private static final CameraState PRESET_PAN_LEFT_TO_RIGHT = new CameraState(160.0f, 0.0f, 1.15f, 0.0f);
    private static final CameraState PRESET_ZOOM_PULSE = new CameraState(0.0f, 0.0f, 1.12f, 0.0f);

    private CameraState currentState = CameraState.DEFAULT;
    private CameraState startState = CameraState.DEFAULT;
    private CameraState targetState = CameraState.DEFAULT;
    private double durationSeconds = 0.0;
    private double elapsedSeconds = 0.0;
    private boolean transitioning = false;

    /**
     * Creates a {@code CameraDirectorPresets} controller initialized to {@link CameraState#DEFAULT}.
     */
    public CameraDirectorPresets() {
    }

    /**
     * Returns the canonical target {@link CameraState} for a given {@link CameraPreset}.
     *
     * @param preset camera preset (must not be {@code null})
     * @return immutable preset {@link CameraState}
     * @throws NullPointerException if {@code preset} is {@code null}
     */
    public static CameraState getPresetState(CameraPreset preset) {
        Objects.requireNonNull(preset, "preset must not be null");
        return switch (preset) {
            case ESTABLISHING -> PRESET_ESTABLISHING;
            case CLOSE_UP -> PRESET_CLOSE_UP;
            case DRAMATIC_TILT -> PRESET_DRAMATIC_TILT;
            case PAN_LEFT_TO_RIGHT -> PRESET_PAN_LEFT_TO_RIGHT;
            case ZOOM_PULSE -> PRESET_ZOOM_PULSE;
        };
    }

    /**
     * Smoothly transitions the active camera toward the target state of {@code preset}.
     *
     * @param preset          target camera preset (must not be {@code null})
     * @param durationSeconds transition duration in seconds ({@code >= 0.0})
     * @throws NullPointerException     if {@code preset} is {@code null}
     * @throws IllegalArgumentException if {@code durationSeconds < 0.0} or non-finite
     */
    public void applyPreset(CameraPreset preset, double durationSeconds) {
        Objects.requireNonNull(preset, "preset must not be null");
        transitionTo(getPresetState(preset), durationSeconds);
    }

    /**
     * Smoothly transitions the active camera from its current state toward {@code target}.
     *
     * @param target          destination camera state (must not be {@code null})
     * @param durationSeconds transition duration in seconds ({@code >= 0.0})
     * @throws NullPointerException     if {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code durationSeconds < 0.0} or non-finite
     */
    public void transitionTo(CameraState target, double durationSeconds) {
        Objects.requireNonNull(target, "target must not be null");
        validateNonNegativeDouble(durationSeconds, "durationSeconds");

        if (durationSeconds == 0.0) {
            this.currentState = target;
            this.startState = target;
            this.targetState = target;
            this.durationSeconds = 0.0;
            this.elapsedSeconds = 0.0;
            this.transitioning = false;
            return;
        }

        this.startState = this.currentState;
        this.targetState = target;
        this.durationSeconds = durationSeconds;
        this.elapsedSeconds = 0.0;
        this.transitioning = true;
    }

    /**
     * Focuses and zooms the camera onto a logical world point {@code (focusX, focusY)} within a viewport
     * of size {@code (logicalWidth, logicalHeight)}.
     *
     * @param focusX          target logical X focal point
     * @param focusY          target logical Y focal point
     * @param zoom            target zoom multiplier ({@code > 0.0f})
     * @param durationSeconds transition duration in seconds ({@code >= 0.0})
     * @param logicalWidth    logical viewport width ({@code > 0.0f})
     * @param logicalHeight   logical viewport height ({@code > 0.0f})
     */
    public void zoomTo(
            float focusX,
            float focusY,
            float zoom,
            double durationSeconds,
            float logicalWidth,
            float logicalHeight
    ) {
        if (!Float.isFinite(focusX) || !Float.isFinite(focusY)) {
            throw new IllegalArgumentException("Focus point must be finite");
        }
        if (!Float.isFinite(logicalWidth) || logicalWidth <= 0.0f
                || !Float.isFinite(logicalHeight) || logicalHeight <= 0.0f) {
            throw new IllegalArgumentException("Logical viewport dimensions must be finite and positive");
        }
        float offsetX = (focusX - logicalWidth * 0.5f) * zoom;
        float offsetY = (focusY - logicalHeight * 0.5f) * zoom;
        transitionTo(new CameraState(offsetX, offsetY, zoom, 0.0f), durationSeconds);
    }

    /**
     * Focuses and zooms smoothly onto the center (or portrait focus point) of a {@link SceneNode}.
     *
     * @param node            target scene node (must not be {@code null})
     * @param zoom            target zoom multiplier ({@code > 0.0f})
     * @param durationSeconds transition duration in seconds ({@code >= 0.0})
     * @param logicalWidth    logical viewport width ({@code > 0.0f})
     * @param logicalHeight   logical viewport height ({@code > 0.0f})
     */
    public void zoomToNode(
            SceneNode node,
            float zoom,
            double durationSeconds,
            float logicalWidth,
            float logicalHeight
    ) {
        Objects.requireNonNull(node, "node must not be null");
        var bounds = node.getTransform().resolveBounds(
                node.getWidth(),
                node.getHeight(),
                logicalWidth,
                logicalHeight
        );
        float targetX = bounds.x() + bounds.width() * 0.5f;
        float targetY = bounds.y() + bounds.height() * 0.35f;
        zoomTo(targetX, targetY, zoom, durationSeconds, logicalWidth, logicalHeight);
    }

    /**
     * Advances any active camera transition by {@code deltaSeconds}.
     *
     * @param deltaSeconds elapsed frame time in seconds ({@code >= 0.0})
     * @throws IllegalArgumentException if {@code deltaSeconds < 0.0} or non-finite
     */
    public void update(double deltaSeconds) {
        validateNonNegativeDouble(deltaSeconds, "deltaSeconds");
        if (!transitioning || deltaSeconds == 0.0) {
            return;
        }

        elapsedSeconds = Math.min(durationSeconds, elapsedSeconds + deltaSeconds);
        float linearT = (float) (elapsedSeconds / durationSeconds);
        float easedT = Easing.smoothstep(linearT);

        currentState = new CameraState(
                lerp(startState.x(), targetState.x(), easedT),
                lerp(startState.y(), targetState.y(), easedT),
                lerp(startState.zoom(), targetState.zoom(), easedT),
                lerp(startState.rotationDegrees(), targetState.rotationDegrees(), easedT)
        );

        if (elapsedSeconds >= durationSeconds) {
            currentState = targetState;
            transitioning = false;
        }
    }

    /**
     * Returns the active interpolated {@link CameraState}.
     *
     * @return current camera state
     */
    public CameraState getCurrentState() {
        return currentState;
    }

    /**
     * Returns the target {@link CameraState} of the latest transition.
     *
     * @return target camera state
     */
    public CameraState getTargetState() {
        return targetState;
    }

    /**
     * Returns whether a camera transition is currently running.
     *
     * @return {@code true} if transitioning
     */
    public boolean isTransitioning() {
        return transitioning;
    }

    /**
     * Converts the active {@link CameraState} into a center-anchored {@link Transform2D}
     * for a stage of dimensions {@code (logicalWidth, logicalHeight)}.
     *
     * @param logicalWidth  logical viewport width in pixels ({@code > 0.0f})
     * @param logicalHeight logical viewport height in pixels ({@code > 0.0f})
     * @return configured {@link Transform2D}
     * @throws IllegalArgumentException if {@code logicalWidth <= 0.0f}, {@code logicalHeight <= 0.0f},
     *                                  or either dimension is non-finite
     */
    public Transform2D toTransform2D(float logicalWidth, float logicalHeight) {
        if (!Float.isFinite(logicalWidth) || logicalWidth <= 0.0f) {
            throw new IllegalArgumentException("logicalWidth must be finite and > 0.0, was: " + logicalWidth);
        }
        if (!Float.isFinite(logicalHeight) || logicalHeight <= 0.0f) {
            throw new IllegalArgumentException("logicalHeight must be finite and > 0.0, was: " + logicalHeight);
        }

        return new Transform2D()
                .setXanchor(0.5f)
                .setYanchor(0.5f)
                .setXpos(logicalWidth * 0.5f - currentState.x())
                .setYpos(logicalHeight * 0.5f - currentState.y())
                .setZoom(currentState.zoom())
                .setRotate(currentState.rotationDegrees());
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void validateNonNegativeDouble(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0.0, was: " + value);
        }
    }
}
