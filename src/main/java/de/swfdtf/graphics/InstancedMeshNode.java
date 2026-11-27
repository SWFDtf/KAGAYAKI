package de.swfdtf.graphics;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hardware-instanced 3D mesh node rendering hundreds or thousands of identical forest trees,
 * pillars, or candles in a single {@code glDrawElementsInstanced} batch ({@code #0282}).
 */
public final class InstancedMeshNode {

    /** Number of floats stored per column-major 4x4 instance model matrix. */
    public static final int FLOATS_PER_MATRIX = 16;

    /**
     * Performance and draw-call batch telemetry recorded on submission.
     *
     * @param instanceCount number of visible instances packed
     * @param drawCallCount total OpenGL instanced draw calls submitted ({@code 1} when non-empty)
     * @param prepareTimeMs CPU transform-packing time in milliseconds
     */
    public record InstancedBatchStats(int instanceCount, int drawCallCount, double prepareTimeMs) {
    }

    private final String id;
    private final Mesh3D mesh;
    private final Material3D material;
    private final List<Matrix4f> instanceTransforms = new ArrayList<>();
    private InstancedBatchStats lastStats = new InstancedBatchStats(0, 0, 0.0);

    /**
     * Creates an {@code InstancedMeshNode} sharing one {@link Mesh3D} and {@link Material3D}.
     *
     * @param id       unique batch node identifier
     * @param mesh     shared 3D mesh geometry
     * @param material shared surface material
     */
    public InstancedMeshNode(String id, Mesh3D mesh, Material3D material) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.mesh = Objects.requireNonNull(mesh, "mesh must not be null");
        this.material = material != null ? material : Material3D.defaultPbr(id + "_mat", "#ffffff");
    }

    /**
     * Adds an instance world transform matrix to the batch.
     *
     * @param transform 4x4 model matrix (copied defensively)
     * @return {@code this} for fluent chaining
     */
    public InstancedMeshNode addInstance(Matrix4f transform) {
        Objects.requireNonNull(transform, "transform must not be null");
        instanceTransforms.add(new Matrix4f(transform));
        return this;
    }

    /**
     * Convenience helper appending a translated, Y-rotated, uniformly scaled instance.
     *
     * @param x        world X
     * @param y        world Y
     * @param z        world Z
     * @param yawDeg   Y rotation in degrees
     * @param scale    uniform scale factor
     * @return {@code this} for fluent chaining
     */
    public InstancedMeshNode addInstance(float x, float y, float z, float yawDeg, float scale) {
        Matrix4f m = new Matrix4f()
                .translate(x, y, z)
                .rotateY((float) Math.toRadians(yawDeg))
                .scale(Math.max(0.01f, scale));
        instanceTransforms.add(m);
        return this;
    }

    /** Clears all registered instance transforms. */
    public void clearInstances() {
        instanceTransforms.clear();
    }

    /**
     * Packs all instance 4x4 model matrices into a contiguous column-major buffer suitable for
     * instanced VBO streaming (`glVertexAttribDivisor`) and updates {@link #getLastStats()}.
     *
     * @return flat {@code float[instanceCount * 16]} matrix buffer
     */
    public float[] packInstanceMatrices() {
        long startNs = System.nanoTime();
        int count = instanceTransforms.size();
        float[] packed = new float[count * FLOATS_PER_MATRIX];
        for (int i = 0; i < count; i++) {
            instanceTransforms.get(i).get(packed, i * FLOATS_PER_MATRIX);
        }
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        int drawCalls = count > 0 ? 1 : 0;
        this.lastStats = new InstancedBatchStats(count, drawCalls, elapsedMs);
        return packed;
    }

    /**
     * Prepares the packed matrix buffer and executes a single {@code GL31.glDrawElementsInstanced}
     * call when an OpenGL 3.3 context with an active VAO is bound ({@code #0282}).
     *
     * @param vaoBound {@code true} if caller bound the mesh VAO and instanced matrix VBO
     * @return {@link InstancedBatchStats} for this submission
     */
    public InstancedBatchStats submitInstancedDraw(boolean vaoBound) {
        packInstanceMatrices();
        int count = instanceTransforms.size();
        if (count > 0 && vaoBound && hasGlContext()) {
            GL31.glDrawElementsInstanced(
                    GL11.GL_TRIANGLES,
                    mesh.getIndices().length,
                    GL11.GL_UNSIGNED_INT,
                    0L,
                    count
            );
        }
        return lastStats;
    }

    /**
     * Returns node identifier.
     *
     * @return identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Returns shared {@link Mesh3D}.
     *
     * @return shared mesh
     */
    public Mesh3D getMesh() {
        return mesh;
    }

    /**
     * Returns shared {@link Material3D}.
     *
     * @return shared material
     */
    public Material3D getMaterial() {
        return material;
    }

    /**
     * Returns immutable copy of all instance transforms.
     *
     * @return list of 4x4 model matrices
     */
    public List<Matrix4f> getInstanceTransforms() {
        return instanceTransforms.stream().map(Matrix4f::new).toList();
    }

    /**
     * Returns instance count.
     *
     * @return number of instances
     */
    public int getInstanceCount() {
        return instanceTransforms.size();
    }

    /**
     * Returns batch packing &amp; draw-call metrics from the latest submission.
     *
     * @return {@link InstancedBatchStats}
     */
    public InstancedBatchStats getLastStats() {
        return lastStats;
    }

    private static boolean hasGlContext() {
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}
