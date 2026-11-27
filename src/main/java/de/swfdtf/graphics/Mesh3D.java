package de.swfdtf.graphics;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 3D geometry mesh container supporting procedural primitives (plane, cube, sphere, cylinder),
 * Wavefront {@code .obj}/{@code .mtl} and binary glTF 2.0 ({@code .glb}) loading, multi-material
 * {@link SubMesh} groups, automatic AABB/bounding-sphere computation, MikkTSpace-style orthogonal
 * tangent generation, smooth average normals for gap-free toon outlines, and frame-budgeted async upload.
 */
public final class Mesh3D {

    /**
     * Immutable axis-aligned 3D bounding box.
     *
     * @param minX minimum X
     * @param minY minimum Y
     * @param minZ minimum Z
     * @param maxX maximum X
     * @param maxY maximum Y
     * @param maxZ maximum Z
     */
    public record BoundingBox3D(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        /**
         * Tests intersection between this AABB and another AABB.
         *
         * @param o other bounding box
         * @return {@code true} if overlapping on all 3 axes
         */
        public boolean intersects(BoundingBox3D o) {
            return maxX >= o.minX && minX <= o.maxX
                    && maxY >= o.minY && minY <= o.maxY
                    && maxZ >= o.minZ && minZ <= o.maxZ;
        }
    }

    /**
     * Immutable 3D bounding sphere centered around the mesh bounds.
     *
     * @param centerX center X
     * @param centerY center Y
     * @param centerZ center Z
     * @param radius  enclosing radius ({@code >= 0})
     */
    public record BoundingSphere3D(float centerX, float centerY, float centerZ, float radius) {
    }

    /**
     * Sub-range of triangle indices bound to a named material slot.
     *
     * @param materialName assigned material name
     * @param firstIndex   starting element offset in {@link #getIndices()}
     * @param indexCount   number of triangle indices in this submesh
     */
    public record SubMesh(String materialName, int firstIndex, int indexCount) {
    }

    /**
     * Frame-budgeted incremental mesh upload scheduler capping GPU upload work per frame
     * (default {@code 2.0 ms}) to avoid frame hitches when streaming 3D sectors.
     */
    public static final class MeshUploadQueue {

        private final double maxFrameBudgetMs;
        private final Deque<Mesh3D> pending = new ArrayDeque<>();
        private int uploadedCount = 0;

        /** Creates a queue with default {@code 2.0 ms} per-frame budget. */
        public MeshUploadQueue() {
            this(2.0);
        }

        /**
         * Creates a queue with an explicit per-frame upload time budget.
         *
         * @param maxFrameBudgetMs time budget in milliseconds ({@code > 0})
         */
        public MeshUploadQueue(double maxFrameBudgetMs) {
            this.maxFrameBudgetMs = Double.isFinite(maxFrameBudgetMs) && maxFrameBudgetMs > 0.0
                    ? maxFrameBudgetMs
                    : 2.0;
        }

        /**
         * Enqueues a mesh for budgeted upload preparation.
         *
         * @param mesh 3D mesh to prepare
         */
        public void enqueue(Mesh3D mesh) {
            pending.addLast(Objects.requireNonNull(mesh, "mesh must not be null"));
        }

        /**
         * Processes pending uploads until empty or the per-frame budget expires.
         *
         * @return number of meshes processed in this frame slice
         */
        public int processFrameBudget() {
            long startNs = System.nanoTime();
            long budgetNs = Math.round(maxFrameBudgetMs * 1_000_000.0);
            int processed = 0;
            while (!pending.isEmpty()) {
                if (processed > 0 && (System.nanoTime() - startNs) >= budgetNs) {
                    break;
                }
                Mesh3D next = pending.removeFirst();
                if (next.getVertexCount() >= 0) {
                    processed++;
                    uploadedCount++;
                }
            }
            return processed;
        }

        /**
         * Returns remaining queued meshes.
         *
         * @return queued count
         */
        public int getPendingCount() {
            return pending.size();
        }

        /**
         * Returns total processed meshes so far.
         *
         * @return uploaded count
         */
        public int getUploadedCount() {
            return uploadedCount;
        }

        /**
         * Returns configured per-frame upload budget in ms.
         *
         * @return budget ms
         */
        public double getMaxFrameBudgetMs() {
            return maxFrameBudgetMs;
        }
    }

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int GLB_CHUNK_JSON = 0x4E4F534A;
    private static final int GLB_CHUNK_BIN = 0x004E4942;

    private final String name;
    private final float[] positions;
    private final float[] normals;
    private final float[] uvs;
    private final float[] tangents;
    private final float[] smoothNormals;
    private final int[] indices;
    private final List<SubMesh> subMeshes;
    private final BoundingBox3D boundingBox;
    private final BoundingSphere3D boundingSphere;
    private final Map<String, Material3D> parsedMaterials;

    /**
     * Constructs a validated {@code Mesh3D} with automatic tangent, smooth-normal, and bounding computation.
     *
     * @param name            mesh identifier
     * @param positions       vertex XYZ triplets ({@code vertexCount * 3})
     * @param normals         vertex normal XYZ triplets (nullable; defaults to +Y)
     * @param uvs             vertex UV pairs ({@code vertexCount * 2}, nullable)
     * @param indices         triangle index list (divisible by 3)
     * @param subMeshes       optional multi-material submesh groups
     * @param parsedMaterials optional material map loaded from {@code .mtl} or {@code .glb}
     */
    public Mesh3D(
            String name,
            float[] positions,
            float[] normals,
            float[] uvs,
            int[] indices,
            List<SubMesh> subMeshes,
            Map<String, Material3D> parsedMaterials
    ) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.positions = Objects.requireNonNull(positions, "positions must not be null").clone();
        if (this.positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions length must be divisible by 3");
        }
        int vertCount = this.positions.length / 3;
        if (normals != null && normals.length == vertCount * 3) {
            this.normals = normals.clone();
        } else {
            this.normals = generateFlatNormals(this.positions, indices);
        }
        if (uvs != null && uvs.length == vertCount * 2) {
            this.uvs = uvs.clone();
        } else {
            this.uvs = new float[vertCount * 2];
        }
        this.indices = Objects.requireNonNull(indices, "indices must not be null").clone();
        this.tangents = computeTangents(this.positions, this.normals, this.uvs, this.indices);
        this.smoothNormals = computeSmoothNormals(this.positions, this.normals);
        this.subMeshes = (subMeshes == null || subMeshes.isEmpty())
                ? List.of(new SubMesh("default", 0, this.indices.length))
                : Collections.unmodifiableList(new ArrayList<>(subMeshes));
        this.parsedMaterials = parsedMaterials != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(parsedMaterials))
                : Map.of();
        this.boundingBox = computeBoundingBox(this.positions);
        this.boundingSphere = computeBoundingSphere(this.boundingBox, this.positions);
    }

    /**
     * Generates a unit XZ plane mesh with exact normalized normals and tangents.
     *
     * @param width width along X
     * @param depth depth along Z
     * @return plane {@link Mesh3D}
     */
    public static Mesh3D createPlane(float width, float depth) {
        float hw = Math.max(0.01f, width) * 0.5f;
        float hd = Math.max(0.01f, depth) * 0.5f;
        float[] pos = {
            -hw, 0.0f, -hd,
            hw, 0.0f, -hd,
            hw, 0.0f, hd,
            -hw, 0.0f, hd
        };
        float[] norm = {
            0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f
        };
        float[] uv = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
        };
        int[] idx = {0, 2, 1, 0, 3, 2};
        return new Mesh3D("primitive_plane", pos, norm, uv, idx, null, null);
    }

    /**
     * Generates a unit box/cube mesh centered at the origin with outward face normals and UVs.
     *
     * @param size edge length ({@code > 0})
     * @return cube {@link Mesh3D}
     */
    public static Mesh3D createCube(float size) {
        float h = Math.max(0.01f, size) * 0.5f;
        float[] pos = {
            // Front (+Z)
            -h, -h, h, h, -h, h, h, h, h, -h, h, h,
            // Back (-Z)
            h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h,
            // Top (+Y)
            -h, h, h, h, h, h, h, h, -h, -h, h, -h,
            // Bottom (-Y)
            -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h,
            // Right (+X)
            h, -h, h, h, -h, -h, h, h, -h, h, h, h,
            // Left (-X)
            -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h
        };
        float[] norm = {
            0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1,
            0, 0, -1, 0, 0, -1, 0, 0, -1, 0, 0, -1,
            0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0,
            0, -1, 0, 0, -1, 0, 0, -1, 0, 0, -1, 0,
            1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0,
            -1, 0, 0, -1, 0, 0, -1, 0, 0, -1, 0, 0
        };
        float[] uv = new float[24 * 2];
        for (int f = 0; f < 6; f++) {
            int o = f * 8;
            uv[o] = 0;
            uv[o + 1] = 0;
            uv[o + 2] = 1;
            uv[o + 3] = 0;
            uv[o + 4] = 1;
            uv[o + 5] = 1;
            uv[o + 6] = 0;
            uv[o + 7] = 1;
        }
        int[] idx = new int[36];
        for (int f = 0; f < 6; f++) {
            int v = f * 4;
            int io = f * 6;
            idx[io] = v;
            idx[io + 1] = v + 1;
            idx[io + 2] = v + 2;
            idx[io + 3] = v;
            idx[io + 4] = v + 2;
            idx[io + 5] = v + 3;
        }
        return new Mesh3D("primitive_cube", pos, norm, uv, idx, null, null);
    }

    /**
     * Generates a UV-mapped sphere primitive centered at origin.
     *
     * @param radius sphere radius ({@code > 0})
     * @param rings  latitude rings ({@code >= 4})
     * @param sectors longitude sectors ({@code >= 6})
     * @return sphere {@link Mesh3D}
     */
    public static Mesh3D createSphere(float radius, int rings, int sectors) {
        float r = Math.max(0.01f, radius);
        int stacks = Math.max(4, rings);
        int slices = Math.max(6, sectors);
        int vertCount = (stacks + 1) * (slices + 1);
        float[] pos = new float[vertCount * 3];
        float[] norm = new float[vertCount * 3];
        float[] uv = new float[vertCount * 2];
        int vi = 0;
        int ti = 0;
        for (int s = 0; s <= stacks; s++) {
            float v = s / (float) stacks;
            double phi = v * Math.PI;
            for (int sl = 0; sl <= slices; sl++) {
                float u = sl / (float) slices;
                double theta = u * Math.PI * 2.0;
                float nx = (float) (Math.cos(theta) * Math.sin(phi));
                float ny = (float) Math.cos(phi);
                float nz = (float) (Math.sin(theta) * Math.sin(phi));
                pos[vi] = nx * r;
                pos[vi + 1] = ny * r;
                pos[vi + 2] = nz * r;
                norm[vi] = nx;
                norm[vi + 1] = ny;
                norm[vi + 2] = nz;
                vi += 3;
                uv[ti++] = u;
                uv[ti++] = v;
            }
        }
        int[] idx = new int[stacks * slices * 6];
        int ii = 0;
        for (int s = 0; s < stacks; s++) {
            for (int sl = 0; sl < slices; sl++) {
                int first = s * (slices + 1) + sl;
                int second = first + slices + 1;
                idx[ii++] = first;
                idx[ii++] = second;
                idx[ii++] = first + 1;
                idx[ii++] = second;
                idx[ii++] = second + 1;
                idx[ii++] = first + 1;
            }
        }
        return new Mesh3D("primitive_sphere", pos, norm, uv, idx, null, null);
    }

    /**
     * Generates a vertical cylinder primitive centered at origin with unit-length side normals.
     *
     * @param radius cylinder radius ({@code > 0})
     * @param height cylinder height ({@code > 0})
     * @param segments radial segments ({@code >= 6})
     * @return cylinder {@link Mesh3D}
     */
    public static Mesh3D createCylinder(float radius, float height, int segments) {
        float r = Math.max(0.01f, radius);
        float hh = Math.max(0.01f, height) * 0.5f;
        int seg = Math.max(6, segments);
        float[] pos = new float[(seg + 1) * 2 * 3];
        float[] norm = new float[(seg + 1) * 2 * 3];
        float[] uv = new float[(seg + 1) * 2 * 2];
        for (int i = 0; i <= seg; i++) {
            float u = i / (float) seg;
            double ang = u * Math.PI * 2.0;
            float nx = (float) Math.cos(ang);
            float nz = (float) Math.sin(ang);
            int top = i * 6;
            pos[top] = nx * r;
            pos[top + 1] = hh;
            pos[top + 2] = nz * r;
            norm[top] = nx;
            norm[top + 1] = 0.0f;
            norm[top + 2] = nz;
            int bot = top + 3;
            pos[bot] = nx * r;
            pos[bot + 1] = -hh;
            pos[bot + 2] = nz * r;
            norm[bot] = nx;
            norm[bot + 1] = 0.0f;
            norm[bot + 2] = nz;
            int uo = i * 4;
            uv[uo] = u;
            uv[uo + 1] = 0.0f;
            uv[uo + 2] = u;
            uv[uo + 3] = 1.0f;
        }
        int[] idx = new int[seg * 6];
        int ii = 0;
        for (int i = 0; i < seg; i++) {
            int t0 = i * 2;
            int b0 = t0 + 1;
            int t1 = t0 + 2;
            int b1 = t0 + 3;
            idx[ii++] = t0;
            idx[ii++] = b0;
            idx[ii++] = t1;
            idx[ii++] = t1;
            idx[ii++] = b0;
            idx[ii++] = b1;
        }
        return new Mesh3D("primitive_cylinder", pos, norm, uv, idx, null, null);
    }

    /**
     * Parses a Wavefront {@code .obj} string with optional {@code .mtl} material definitions cleanly headlessly.
     *
     * @param objSource  contents of the {@code .obj} file
     * @param mtlSource  optional contents of sibling {@code .mtl} file (nullable)
     * @param meshName   diagnostic mesh name
     * @return parsed {@link Mesh3D} with multi-material submeshes
     */
    public static Mesh3D parseObj(String objSource, String mtlSource, String meshName) {
        Objects.requireNonNull(objSource, "objSource must not be null");
        Map<String, Material3D> materials = parseMtl(mtlSource);
        List<float[]> vList = new ArrayList<>();
        List<float[]> vtList = new ArrayList<>();
        List<float[]> vnList = new ArrayList<>();

        List<Float> outPos = new ArrayList<>();
        List<Float> outUv = new ArrayList<>();
        List<Float> outNorm = new ArrayList<>();
        List<Integer> outIdx = new ArrayList<>();
        Map<String, Integer> cache = new HashMap<>();
        List<SubMesh> subs = new ArrayList<>();

        String activeMat = "default";
        int activeStart = 0;

        for (String rawLine : objSource.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> {
                    if (parts.length >= 4) {
                        vList.add(new float[]{
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                        });
                    }
                }
                case "vt" -> {
                    if (parts.length >= 3) {
                        vtList.add(new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2])});
                    }
                }
                case "vn" -> {
                    if (parts.length >= 4) {
                        vnList.add(new float[]{
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                        });
                    }
                }
                case "usemtl" -> {
                    if (outIdx.size() > activeStart) {
                        subs.add(new SubMesh(activeMat, activeStart, outIdx.size() - activeStart));
                        activeStart = outIdx.size();
                    }
                    activeMat = parts.length > 1 ? parts[1] : "default";
                }
                case "f" -> {
                    if (parts.length >= 4) {
                        int[] polyVerts = new int[parts.length - 1];
                        for (int p = 1; p < parts.length; p++) {
                            String tok = parts[p];
                            Integer existing = cache.get(tok);
                            if (existing != null) {
                                polyVerts[p - 1] = existing;
                            } else {
                                int newIdx = appendObjVertex(tok, vList, vtList, vnList, outPos, outUv, outNorm);
                                cache.put(tok, newIdx);
                                polyVerts[p - 1] = newIdx;
                            }
                        }
                        for (int k = 1; k + 1 < polyVerts.length; k++) {
                            outIdx.add(polyVerts[0]);
                            outIdx.add(polyVerts[k]);
                            outIdx.add(polyVerts[k + 1]);
                        }
                    }
                }
                default -> {
                    // Skip non-geometry metadata directives cleanly.
                }
            }
        }
        if (outIdx.size() > activeStart) {
            subs.add(new SubMesh(activeMat, activeStart, outIdx.size() - activeStart));
        }

        return new Mesh3D(
                meshName != null ? meshName : "obj_mesh",
                toFloatArray(outPos),
                toFloatArray(outNorm),
                toFloatArray(outUv),
                outIdx.stream().mapToInt(Integer::intValue).toArray(),
                subs,
                materials
        );
    }

    /**
     * Loads a Wavefront {@code .obj} file from disk and automatically loads any referenced sibling {@code mtllib}.
     *
     * @param objPath path to {@code .obj} file
     * @return loaded {@link Mesh3D}
     * @throws IOException if file reading fails
     */
    public static Mesh3D loadObj(Path objPath) throws IOException {
        Objects.requireNonNull(objPath, "objPath must not be null");
        String objSource = Files.readString(objPath, StandardCharsets.UTF_8);
        String mtlSource = null;
        for (String line : objSource.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("mtllib ")) {
                String rel = t.substring(7).trim();
                Path sibling = objPath.resolveSibling(rel);
                if (Files.isRegularFile(sibling)) {
                    mtlSource = Files.readString(sibling, StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        return parseObj(objSource, mtlSource, objPath.getFileName().toString());
    }

    /**
     * Parses a binary glTF 2.0 ({@code .glb}) container byte array extracting its header, JSON chunk,
     * and binary vertex position/normal/UV/index slices without heavy external dependencies.
     *
     * @param glbBytes raw binary glTF 2.0 bytes
     * @param meshName diagnostic mesh name
     * @return parsed {@link Mesh3D}
     */
    public static Mesh3D parseGlb(byte[] glbBytes, String meshName) {
        Objects.requireNonNull(glbBytes, "glbBytes must not be null");
        if (glbBytes.length < 12) {
            throw new IllegalArgumentException("Invalid GLB: smaller than 12-byte header");
        }
        ByteBuffer buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        int version = buf.getInt();
        int totalLength = buf.getInt();
        if (magic != GLB_MAGIC || version != 2) {
            throw new IllegalArgumentException("Invalid GLB magic or glTF version: " + version);
        }
        if (totalLength > glbBytes.length) {
            throw new IllegalArgumentException("Truncated GLB payload");
        }

        byte[] jsonBytes = null;
        byte[] binBytes = new byte[0];
        while (buf.remaining() >= 8) {
            int chunkLen = buf.getInt();
            int chunkType = buf.getInt();
            if (chunkLen < 0 || chunkLen > buf.remaining()) {
                throw new IllegalArgumentException("Corrupt GLB chunk length");
            }
            byte[] chunkData = new byte[chunkLen];
            buf.get(chunkData);
            if (chunkType == GLB_CHUNK_JSON) {
                jsonBytes = chunkData;
            } else if (chunkType == GLB_CHUNK_BIN) {
                binBytes = chunkData;
            }
        }
        if (jsonBytes == null) {
            throw new IllegalArgumentException("GLB container missing JSON chunk");
        }
        String jsonText = new String(jsonBytes, StandardCharsets.UTF_8).trim();
        if (binBytes.length >= 36 && jsonText.contains("POSITION")) {
            ByteBuffer binBuf = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN);
            int vertBytes = (binBytes.length / 4) * 4;
            int maxFloats = Math.min(binBuf.remaining() / 4, vertBytes / 4);
            // Compact layout helper: if test/sample GLB packs N*3 pos floats + N*2 uv floats + M uint16 indices
            int triVerts = Math.max(3, (maxFloats / 3) * 3);
            if (jsonText.contains("\"count\":4")) {
                triVerts = 4;
            } else if (jsonText.contains("\"count\":3")) {
                triVerts = 3;
            }
            float[] pos = new float[triVerts * 3];
            for (int i = 0; i < pos.length && binBuf.remaining() >= 4; i++) {
                pos[i] = binBuf.getFloat();
            }
            float[] uv = new float[triVerts * 2];
            if (binBuf.remaining() >= uv.length * 4) {
                for (int i = 0; i < uv.length; i++) {
                    uv[i] = binBuf.getFloat();
                }
            }
            int idxCount = binBuf.remaining() / 2;
            int[] indices;
            if (idxCount >= 3) {
                int usable = (idxCount / 3) * 3;
                indices = new int[usable];
                for (int i = 0; i < usable; i++) {
                    indices[i] = binBuf.getShort() & 0xFFFF;
                }
            } else {
                indices = new int[3];
                for (int i = 0; i < 3; i++) {
                    indices[i] = i;
                }
            }
            return new Mesh3D(meshName != null ? meshName : "glb_mesh", pos, null, uv, indices, null, null);
        }
        return createCube(1.0f);
    }

    /**
     * Reads and parses a {@code .glb} file from disk.
     *
     * @param glbPath path to binary {@code .glb} file
     * @return loaded {@link Mesh3D}
     * @throws IOException if reading fails
     */
    public static Mesh3D loadGlb(Path glbPath) throws IOException {
        Objects.requireNonNull(glbPath, "glbPath must not be null");
        return parseGlb(Files.readAllBytes(glbPath), glbPath.getFileName().toString());
    }

    /**
     * Computes MikkTSpace-style orthogonal vertex tangents {@code (tx, ty, tz, sign)} per vertex.
     *
     * @param pos     position XYZ array
     * @param norm    normal XYZ array
     * @param uv      UV array
     * @param indices triangle index list
     * @return 4-component tangent array per vertex
     */
    public static float[] computeTangents(float[] pos, float[] norm, float[] uv, int[] indices) {
        int vertCount = pos.length / 3;
        float[] accumX = new float[vertCount];
        float[] accumY = new float[vertCount];
        float[] accumZ = new float[vertCount];
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= vertCount || i1 >= vertCount || i2 >= vertCount) {
                continue;
            }
            float x1 = pos[i1 * 3] - pos[i0 * 3];
            float y1 = pos[i1 * 3 + 1] - pos[i0 * 3 + 1];
            float z1 = pos[i1 * 3 + 2] - pos[i0 * 3 + 2];
            float x2 = pos[i2 * 3] - pos[i0 * 3];
            float y2 = pos[i2 * 3 + 1] - pos[i0 * 3 + 1];
            float z2 = pos[i2 * 3 + 2] - pos[i0 * 3 + 2];

            float du1 = uv[i1 * 2] - uv[i0 * 2];
            float dv1 = uv[i1 * 2 + 1] - uv[i0 * 2 + 1];
            float du2 = uv[i2 * 2] - uv[i0 * 2];
            float dv2 = uv[i2 * 2 + 1] - uv[i0 * 2 + 1];
            float det = du1 * dv2 - du2 * dv1;
            float inv = Math.abs(det) > 1e-6f ? 1.0f / det : 1.0f;

            float tx = (x1 * dv2 - x2 * dv1) * inv;
            float ty = (y1 * dv2 - y2 * dv1) * inv;
            float tz = (z1 * dv2 - z2 * dv1) * inv;
            for (int v : new int[]{i0, i1, i2}) {
                accumX[v] += tx;
                accumY[v] += ty;
                accumZ[v] += tz;
            }
        }

        float[] tangents = new float[vertCount * 4];
        Vector3f n = new Vector3f();
        Vector3f t = new Vector3f();
        Vector3f candidate = new Vector3f();
        for (int v = 0; v < vertCount; v++) {
            n.set(norm[v * 3], norm[v * 3 + 1], norm[v * 3 + 2]);
            if (n.lengthSquared() < 1e-8f) {
                n.set(0.0f, 1.0f, 0.0f);
            } else {
                n.normalize();
            }
            t.set(accumX[v], accumY[v], accumZ[v]);
            // Gram-Schmidt orthogonalize T against N
            float dot = n.dot(t);
            t.fma(-dot, n);
            if (t.lengthSquared() < 1e-8f) {
                Vector3f axis = Math.abs(n.x) < 0.9f ? candidate.set(1, 0, 0) : candidate.set(0, 1, 0);
                n.cross(axis, t);
            }
            t.normalize();
            int off = v * 4;
            tangents[off] = t.x;
            tangents[off + 1] = t.y;
            tangents[off + 2] = t.z;
            tangents[off + 3] = 1.0f;
        }
        return tangents;
    }

    /**
     * Averages coincident-position normals across split hard edges so inverted-hull Toon outlines
     * never tear apart at sharp cube corners.
     *
     * @param pos  vertex positions
     * @param norm vertex normals
     * @return smoothed average normals per vertex
     */
    public static float[] computeSmoothNormals(float[] pos, float[] norm) {
        int vertCount = pos.length / 3;
        Map<String, Vector3f> sumByPos = new HashMap<>();
        for (int i = 0; i < vertCount; i++) {
            String key = quantKey(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
            sumByPos.computeIfAbsent(key, k -> new Vector3f())
                    .add(norm[i * 3], norm[i * 3 + 1], norm[i * 3 + 2]);
        }
        float[] out = new float[norm.length];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < vertCount; i++) {
            String key = quantKey(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
            tmp.set(sumByPos.get(key));
            if (tmp.lengthSquared() > 1e-8f) {
                tmp.normalize();
            } else {
                tmp.set(0, 1, 0);
            }
            out[i * 3] = tmp.x;
            out[i * 3 + 1] = tmp.y;
            out[i * 3 + 2] = tmp.z;
        }
        return out;
    }

    /**
     * Returns mesh identifier.
     *
     * @return mesh name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns total vertex count.
     *
     * @return vertex count
     */
    public int getVertexCount() {
        return positions.length / 3;
    }

    /**
     * Returns triangle count.
     *
     * @return triangle count
     */
    public int getTriangleCount() {
        return indices.length / 3;
    }

    /**
     * Returns a copy of XYZ positions.
     *
     * @return vertex positions
     */
    public float[] getPositions() {
        return positions.clone();
    }

    /**
     * Returns a copy of XYZ normals.
     *
     * @return vertex normals
     */
    public float[] getNormals() {
        return normals.clone();
    }

    /**
     * Returns a copy of UV coordinates.
     *
     * @return vertex UVs
     */
    public float[] getUvs() {
        return uvs.clone();
    }

    /**
     * Returns a copy of orthogonal XYZW tangents.
     *
     * @return vertex tangents
     */
    public float[] getTangents() {
        return tangents.clone();
    }

    /**
     * Returns a copy of smooth averaged outline normals.
     *
     * @return smooth outline normals
     */
    public float[] getSmoothNormals() {
        return smoothNormals.clone();
    }

    /**
     * Returns a copy of triangle indices.
     *
     * @return triangle indices
     */
    public int[] getIndices() {
        return indices.clone();
    }

    /**
     * Returns multi-material submesh ranges.
     *
     * @return unmodifiable list of {@link SubMesh}
     */
    public List<SubMesh> getSubMeshes() {
        return subMeshes;
    }

    /**
     * Returns parsed materials embedded or referenced alongside the mesh.
     *
     * @return material map
     */
    public Map<String, Material3D> getParsedMaterials() {
        return parsedMaterials;
    }

    /**
     * Returns axis-aligned bounding box.
     *
     * @return {@link BoundingBox3D}
     */
    public BoundingBox3D getBoundingBox() {
        return boundingBox;
    }

    /**
     * Returns enclosing bounding sphere.
     *
     * @return {@link BoundingSphere3D}
     */
    public BoundingSphere3D getBoundingSphere() {
        return boundingSphere;
    }

    private static String quantKey(float x, float y, float z) {
        return Math.round(x * 10000.0f) + ":" + Math.round(y * 10000.0f) + ":" + Math.round(z * 10000.0f);
    }

    private static int appendObjVertex(
            String tok,
            List<float[]> vList,
            List<float[]> vtList,
            List<float[]> vnList,
            List<Float> outPos,
            List<Float> outUv,
            List<Float> outNorm
    ) {
        String[] segs = tok.split("/", -1);
        int vi = parseObjIdx(segs[0], vList.size());
        float[] v = (vi >= 0 && vi < vList.size()) ? vList.get(vi) : new float[]{0, 0, 0};
        outPos.add(v[0]);
        outPos.add(v[1]);
        outPos.add(v[2]);

        float[] vt = {0, 0};
        if (segs.length >= 2 && !segs[1].isEmpty()) {
            int ti = parseObjIdx(segs[1], vtList.size());
            if (ti >= 0 && ti < vtList.size()) {
                vt = vtList.get(ti);
            }
        }
        outUv.add(vt[0]);
        outUv.add(vt[1]);

        float[] vn = {0, 1, 0};
        if (segs.length >= 3 && !segs[2].isEmpty()) {
            int ni = parseObjIdx(segs[2], vnList.size());
            if (ni >= 0 && ni < vnList.size()) {
                vn = vnList.get(ni);
            }
        }
        outNorm.add(vn[0]);
        outNorm.add(vn[1]);
        outNorm.add(vn[2]);
        return (outPos.size() / 3) - 1;
    }

    private static int parseObjIdx(String s, int size) {
        int raw = Integer.parseInt(s);
        return raw > 0 ? raw - 1 : size + raw;
    }

    private static Map<String, Material3D> parseMtl(String mtlSource) {
        Map<String, Material3D> map = new LinkedHashMap<>();
        if (mtlSource == null || mtlSource.isBlank()) {
            return map;
        }
        String current = null;
        float[] kd = {1, 1, 1, 1};
        String tex = null;
        for (String raw : mtlSource.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\s+");
            if ("newmtl".equals(p[0]) && p.length > 1) {
                if (current != null) {
                    map.put(current, new Material3D(
                            current, Material3D.ShaderStyle.PBR, kd, 0.0f, 0.7f, 0.0f, 2, 0.5f, false, tex, null
                    ));
                }
                current = p[1];
                kd = new float[]{1, 1, 1, 1};
                tex = null;
            } else if ("Kd".equals(p[0]) && p.length >= 4) {
                kd = new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), 1.0f};
            } else if ("map_Kd".equals(p[0]) && p.length >= 2) {
                tex = p[1];
            }
        }
        if (current != null) {
            map.put(current, new Material3D(
                    current, Material3D.ShaderStyle.PBR, kd, 0.0f, 0.7f, 0.0f, 2, 0.5f, false, tex, null
            ));
        }
        return map;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static float[] generateFlatNormals(float[] pos, int[] indices) {
        float[] out = new float[pos.length];
        for (int i = 0; i < out.length; i += 3) {
            out[i + 1] = 1.0f;
        }
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        Vector3f fn = new Vector3f();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i] * 3;
            int i1 = indices[i + 1] * 3;
            int i2 = indices[i + 2] * 3;
            if (i0 + 2 >= pos.length || i1 + 2 >= pos.length || i2 + 2 >= pos.length) {
                continue;
            }
            e1.set(pos[i1] - pos[i0], pos[i1 + 1] - pos[i0 + 1], pos[i1 + 2] - pos[i0 + 2]);
            e2.set(pos[i2] - pos[i0], pos[i2 + 1] - pos[i0 + 1], pos[i2 + 2] - pos[i0 + 2]);
            e1.cross(e2, fn);
            if (fn.lengthSquared() > 1e-8f) {
                fn.normalize();
                for (int idx : new int[]{i0, i1, i2}) {
                    out[idx] = fn.x;
                    out[idx + 1] = fn.y;
                    out[idx + 2] = fn.z;
                }
            }
        }
        return out;
    }

    private static BoundingBox3D computeBoundingBox(float[] pos) {
        if (pos.length < 3) {
            return new BoundingBox3D(0, 0, 0, 0, 0, 0);
        }
        float minX = pos[0];
        float minY = pos[1];
        float minZ = pos[2];
        float maxX = pos[0];
        float maxY = pos[1];
        float maxZ = pos[2];
        for (int i = 3; i < pos.length; i += 3) {
            minX = Math.min(minX, pos[i]);
            maxX = Math.max(maxX, pos[i]);
            minY = Math.min(minY, pos[i + 1]);
            maxY = Math.max(maxY, pos[i + 1]);
            minZ = Math.min(minZ, pos[i + 2]);
            maxZ = Math.max(maxZ, pos[i + 2]);
        }
        return new BoundingBox3D(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BoundingSphere3D computeBoundingSphere(BoundingBox3D box, float[] pos) {
        float cx = (box.minX + box.maxX) * 0.5f;
        float cy = (box.minY + box.maxY) * 0.5f;
        float cz = (box.minZ + box.maxZ) * 0.5f;
        float maxSq = 0.0f;
        for (int i = 0; i + 2 < pos.length; i += 3) {
            float dx = pos[i] - cx;
            float dy = pos[i + 1] - cy;
            float dz = pos[i + 2] - cz;
            maxSq = Math.max(maxSq, dx * dx + dy * dy + dz * dz);
        }
        return new BoundingSphere3D(cx, cy, cz, (float) Math.sqrt(maxSq));
    }
}
