package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.Objects;

/**
 * Architectural 3D stage rendering pipeline stub implementing {@link IRenderPipeline}.
 *
 * <p>Prepares perspective projection ({@code FOV = 75°}, {@code Z_NEAR = 100}, {@code Z_FAR = 100000})
 * and camera view matrices positioned at {@code Z_camera = (logicalWidth / 2) / tan(fov / 2)} so
 * future 3D stage extensions plug directly into the existing pipeline contract without breaking
 * runtime startup or tests. Emits controlled warnings via {@link EngineLogger} when initialized and
 * on first scene-graph render without throwing or crashing.</p>
 */
public final class RenderPipeline3DStub implements IRenderPipeline {

    /** Blueprint default perspective vertical field of view in degrees. */
    public static final float DEFAULT_FOV_DEGREES = 75.0f;

    /** Blueprint near clipping plane distance. */
    public static final float Z_NEAR = 100.0f;

    /** Blueprint far clipping plane distance. */
    public static final float Z_FAR = 100000.0f;

    private static final String LOG_CATEGORY = "RenderPipeline3DStub";

    private final EngineLogger logger;
    private final boolean glEnabled;
    private final float fovDegrees;
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();

    private int logicalWidth = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private float cameraDistanceZ = 0.0f;
    private boolean initialized = false;
    private boolean initWarningLogged = false;
    private boolean renderWarningLogged = false;
    private boolean glUnavailable = false;

    /**
     * Creates a {@code RenderPipeline3DStub} with an internal default {@link EngineLogger}
     * and safe OpenGL capability auto-detection.
     */
    public RenderPipeline3DStub() {
        this(new EngineLogger(), true);
    }

    /**
     * Creates a {@code RenderPipeline3DStub} logging to {@code logger} with safe OpenGL
     * capability auto-detection.
     *
     * @param logger structured logger (must not be {@code null})
     */
    public RenderPipeline3DStub(EngineLogger logger) {
        this(logger, true);
    }

    /**
     * Creates a {@code RenderPipeline3DStub} with explicit control over whether OpenGL framebuffer
     * clearing is attempted when an OpenGL context is active.
     *
     * @param logger    structured logger (must not be {@code null})
     * @param glEnabled {@code true} to clear the active OpenGL framebuffer during {@link #beginFrame}
     *                  when {@code GLCapabilities} are present, {@code false} for purely headless mode
     */
    public RenderPipeline3DStub(EngineLogger logger, boolean glEnabled) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.glEnabled = glEnabled;
        this.fovDegrees = DEFAULT_FOV_DEGREES;
    }

    /**
     * Computes the canonical Z camera distance at which a plane of width {@code logicalWidth}
     * subtends the horizontal span for {@code fovDegrees}:
     * {@code (logicalWidth / 2.0f) / tan(toRadians(fovDegrees) / 2.0f)}.
     *
     * @param logicalWidth logical stage width in pixels ({@code > 0})
     * @param fovDegrees   field of view angle in degrees in {@code (0, 180)}
     * @return camera Z distance
     * @throws IllegalArgumentException if arguments are out of valid bounds
     */
    public static float computeCameraDistanceZ(int logicalWidth, float fovDegrees) {
        if (logicalWidth <= 0) {
            throw new IllegalArgumentException("logicalWidth must be positive, got " + logicalWidth);
        }
        if (!Float.isFinite(fovDegrees) || fovDegrees <= 0.0f || fovDegrees >= 180.0f) {
            throw new IllegalArgumentException("fovDegrees must be finite and in (0, 180), got " + fovDegrees);
        }
        double halfWidth = logicalWidth / 2.0;
        double halfFovRad = Math.toRadians(fovDegrees) / 2.0;
        return (float) (halfWidth / Math.tan(halfFovRad));
    }

    @Override
    public void init(int logicalWidth, int logicalHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException(
                    "Logical dimensions must be positive, got " + logicalWidth + "x" + logicalHeight
            );
        }
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.cameraDistanceZ = computeCameraDistanceZ(logicalWidth, fovDegrees);

        float aspect = (float) logicalWidth / (float) logicalHeight;
        this.projectionMatrix.setPerspective((float) Math.toRadians(fovDegrees), aspect, Z_NEAR, Z_FAR);

        float centerX = logicalWidth * 0.5f;
        float centerY = logicalHeight * 0.5f;
        this.viewMatrix.setLookAt(
                centerX, centerY, cameraDistanceZ,
                centerX, centerY, 0.0f,
                0.0f, 1.0f, 0.0f
        );

        this.initialized = true;
        this.renderWarningLogged = false;
        this.initWarningLogged = true;
        logger.warn(
                LOG_CATEGORY,
                "RenderMode.MODE_3D is an inactive architectural stub (FOV=" + fovDegrees
                        + "°, Z_near=" + Z_NEAR + ", Z_far=" + Z_FAR
                        + ", Z_camera=" + cameraDistanceZ + "). Switch to MODE_2D for active rendering."
        );
    }

    @Override
    public void beginFrame(int windowWidth, int windowHeight) {
        if (canExecuteGl()) {
            if (windowWidth > 0 && windowHeight > 0) {
                GL11.glViewport(0, 0, windowWidth, windowHeight);
            }
            GL11.glClearColor(
                    RenderPipeline2D.CLEAR_R,
                    RenderPipeline2D.CLEAR_G,
                    RenderPipeline2D.CLEAR_B,
                    RenderPipeline2D.CLEAR_A
            );
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

    @Override
    public void renderSceneGraph(SceneGraph graph) {
        if (!renderWarningLogged) {
            renderWarningLogged = true;
            logger.warn(
                    LOG_CATEGORY,
                    "renderSceneGraph invoked while RenderMode.MODE_3D architectural stub is inactive; "
                            + "scene geometry is not drawn in 3D stub mode."
            );
        }
    }

    @Override
    public void endFrame() {
        // No offscreen FBO blit required for the inactive architectural stub.
    }

    @Override
    public RenderMode getMode() {
        return RenderMode.MODE_3D;
    }

    @Override
    public void dispose() {
        initialized = false;
    }

    /**
     * Returns a defensive copy of the computed 3D perspective projection matrix.
     *
     * @return perspective projection {@link Matrix4f}
     */
    public Matrix4f getProjectionMatrix() {
        return new Matrix4f(projectionMatrix);
    }

    /**
     * Returns a defensive copy of the computed 3D stage camera view matrix.
     *
     * @return camera view {@link Matrix4f}
     */
    public Matrix4f getViewMatrix() {
        return new Matrix4f(viewMatrix);
    }

    /**
     * Returns the computed camera Z distance.
     *
     * @return computed camera Z distance for the configured logical width and FOV
     */
    public float getCameraDistanceZ() {
        return cameraDistanceZ;
    }

    /**
     * Returns the perspective field of view in degrees.
     *
     * @return perspective field of view in degrees
     */
    public float getFovDegrees() {
        return fovDegrees;
    }

    /**
     * Returns the configured logical stage width.
     *
     * @return configured logical stage width
     */
    public int getLogicalWidth() {
        return logicalWidth;
    }

    /**
     * Returns the configured logical stage height.
     *
     * @return configured logical stage height
     */
    public int getLogicalHeight() {
        return logicalHeight;
    }

    /**
     * Returns whether this 3D stub pipeline is initialized.
     *
     * @return {@code true} after {@link #init(int, int)} until {@link #dispose()}
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns whether the controlled stub warning has been logged.
     *
     * @return {@code true} once the controlled stub warning has been logged
     */
    public boolean isWarningLogged() {
        return initWarningLogged || renderWarningLogged;
    }

    private boolean canExecuteGl() {
        if (!glEnabled || glUnavailable) {
            return false;
        }
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException ignored) {
            return false;
        } catch (LinkageError ignored) {
            glUnavailable = true;
            return false;
        }
    }
}
