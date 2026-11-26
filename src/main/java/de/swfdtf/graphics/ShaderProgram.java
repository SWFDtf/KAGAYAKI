package de.swfdtf.graphics;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OpenGL 3.3 Core GLSL shader program compiler, linker, and uniform binder with location caching.
 */
public final class ShaderProgram {

    private int programId = 0;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    /**
     * Creates an uncompiled {@code ShaderProgram}. Call {@link #compile(String, String)} before binding.
     */
    public ShaderProgram() {
    }

    /**
     * Creates and immediately compiles a {@code ShaderProgram} from GLSL vertex and fragment sources.
     *
     * @param vertexSrc   GLSL vertex shader source
     * @param fragmentSrc GLSL fragment shader source
     */
    public ShaderProgram(String vertexSrc, String fragmentSrc) {
        compile(vertexSrc, fragmentSrc);
    }

    /**
     * Compiles and links the vertex and fragment shader stages into an OpenGL program.
     * Any previously compiled program held by this instance is disposed first.
     *
     * @param vertexSrc   GLSL vertex shader source (non-null, non-blank)
     * @param fragmentSrc GLSL fragment shader source (non-null, non-blank)
     * @throws IllegalStateException if shader compilation or program linking fails
     */
    public void compile(String vertexSrc, String fragmentSrc) {
        if (vertexSrc == null || vertexSrc.isBlank()) {
            throw new IllegalArgumentException("vertexSrc must not be null or blank");
        }
        if (fragmentSrc == null || fragmentSrc.isBlank()) {
            throw new IllegalArgumentException("fragmentSrc must not be null or blank");
        }

        if (programId != 0) {
            dispose();
        }

        int vertexShader = 0;
        int fragmentShader = 0;
        int newProgram = 0;

        try {
            vertexShader = compileStage(GL20.GL_VERTEX_SHADER, vertexSrc, "vertex");
            fragmentShader = compileStage(GL20.GL_FRAGMENT_SHADER, fragmentSrc, "fragment");

            newProgram = GL20.glCreateProgram();
            if (newProgram == 0) {
                throw new IllegalStateException("glCreateProgram returned 0");
            }

            GL20.glAttachShader(newProgram, vertexShader);
            GL20.glAttachShader(newProgram, fragmentShader);
            GL20.glLinkProgram(newProgram);

            if (GL20.glGetProgrami(newProgram, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
                String log = GL20.glGetProgramInfoLog(newProgram);
                throw new IllegalStateException("Shader program link failed: " + log);
            }

            GL20.glDetachShader(newProgram, vertexShader);
            GL20.glDetachShader(newProgram, fragmentShader);

            this.programId = newProgram;
            this.uniformLocations.clear();
            newProgram = 0;
        } finally {
            if (newProgram != 0) {
                GL20.glDeleteProgram(newProgram);
            }
            if (vertexShader != 0) {
                GL20.glDeleteShader(vertexShader);
            }
            if (fragmentShader != 0) {
                GL20.glDeleteShader(fragmentShader);
            }
        }
    }

    private static int compileStage(int stageType, String source, String stageLabel) {
        int shader = GL20.glCreateShader(stageType);
        if (shader == 0) {
            throw new IllegalStateException("glCreateShader failed for " + stageLabel + " stage");
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("GLSL " + stageLabel + " compilation failed: " + log);
        }
        return shader;
    }

    /**
     * Binds this shader program as the active OpenGL program.
     *
     * @throws IllegalStateException if the shader program has not been compiled or was disposed
     */
    public void bind() {
        requireCompiled();
        GL20.glUseProgram(programId);
    }

    /**
     * Unbinds any active OpenGL shader program ({@code glUseProgram(0)}).
     */
    public void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Uploads a 16-element column-major matrix to a {@code mat4} uniform.
     * The shader program must currently be bound via {@link #bind()}.
     *
     * @param name               uniform variable name
     * @param columnMajorMatrix  16-float column-major matrix array
     */
    public void setUniformMat4(String name, float[] columnMajorMatrix) {
        Objects.requireNonNull(columnMajorMatrix, "columnMajorMatrix must not be null");
        if (columnMajorMatrix.length != 16) {
            throw new IllegalArgumentException("Expected 16 floats for mat4, got " + columnMajorMatrix.length);
        }
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL20.glUniformMatrix4fv(location, false, columnMajorMatrix);
        }
    }

    /**
     * Uploads a JOML {@link Matrix4f} to a {@code mat4} uniform.
     * The shader program must currently be bound via {@link #bind()}.
     *
     * @param name   uniform variable name
     * @param matrix JOML 4x4 matrix (must not be {@code null})
     */
    public void setUniformMat4(String name, Matrix4f matrix) {
        Objects.requireNonNull(matrix, "matrix must not be null");
        int location = getUniformLocation(name);
        if (location >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buffer = stack.mallocFloat(16);
                matrix.get(buffer);
                GL20.glUniformMatrix4fv(location, false, buffer);
            }
        }
    }

    /**
     * Sets a {@code vec4} uniform. The shader program must currently be bound via {@link #bind()}.
     *
     * @param name uniform variable name
     * @param x    X component
     * @param y    Y component
     * @param z    Z component
     * @param w    W component
     */
    public void setUniformVec4(String name, float x, float y, float z, float w) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL20.glUniform4f(location, x, y, z, w);
        }
    }

    /**
     * Sets a {@code vec2} uniform. The shader program must currently be bound via {@link #bind()}.
     *
     * @param name uniform variable name
     * @param x    X component
     * @param y    Y component
     */
    public void setUniformVec2(String name, float x, float y) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL20.glUniform2f(location, x, y);
        }
    }

    /**
     * Sets a {@code float} uniform. The shader program must currently be bound via {@link #bind()}.
     *
     * @param name  uniform variable name
     * @param value scalar float value
     */
    public void setUniformFloat(String name, float value) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL20.glUniform1f(location, value);
        }
    }

    /**
     * Sets an {@code int} or {@code sampler2D} texture-unit uniform.
     * The shader program must currently be bound via {@link #bind()}.
     *
     * @param name  uniform variable name
     * @param value integer value
     */
    public void setUniformInt(String name, int value) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL20.glUniform1i(location, value);
        }
    }

    /**
     * Looks up and caches the OpenGL uniform location for {@code name}.
     *
     * @param name uniform variable name (non-null)
     * @return uniform location index, or {@code -1} if unused/absent in the linked program
     */
    public int getUniformLocation(String name) {
        Objects.requireNonNull(name, "name must not be null");
        requireCompiled();
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = GL20.glGetUniformLocation(programId, name);
        uniformLocations.put(name, location);
        return location;
    }

    /**
     * Returns the native OpenGL program handle.
     *
     * @return native OpenGL program handle, or {@code 0} if not compiled or disposed
     */
    public int getProgramId() {
        return programId;
    }

    /**
     * Returns whether a compiled and linked OpenGL program is held.
     *
     * @return {@code true} if a compiled and linked OpenGL program is currently held
     */
    public boolean isCompiled() {
        return programId != 0;
    }

    /**
     * Deletes the native OpenGL program and clears cached uniform locations.
     * Safe to invoke multiple times.
     */
    public void dispose() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        uniformLocations.clear();
    }

    private void requireCompiled() {
        if (programId == 0) {
            throw new IllegalStateException("ShaderProgram is not compiled or has been disposed");
        }
    }
}
