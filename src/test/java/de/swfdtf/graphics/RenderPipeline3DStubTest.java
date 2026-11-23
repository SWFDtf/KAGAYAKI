package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPipeline3DStubTest {

    private static final float EPS = 1e-3f;

    @Test
    void computesCameraDistanceZFor1920At75Degrees() {
        float expected = (float) (960.0 / Math.tan(Math.toRadians(75.0) / 2.0));
        float actual = RenderPipeline3DStub.computeCameraDistanceZ(1920, RenderPipeline3DStub.DEFAULT_FOV_DEGREES);
        assertEquals(expected, actual, EPS);
        assertEquals(1251.0963f, actual, EPS);

        assertThrows(IllegalArgumentException.class, () -> RenderPipeline3DStub.computeCameraDistanceZ(0, 75.0f));
        assertThrows(IllegalArgumentException.class, () -> RenderPipeline3DStub.computeCameraDistanceZ(1920, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> RenderPipeline3DStub.computeCameraDistanceZ(1920, 180.0f));
    }

    @Test
    void buildsPerspectiveProjectionAndCenteredViewMatrices() {
        EngineLogger logger = new EngineLogger();
        logger.setConsoleOutputEnabled(false);

        RenderPipeline3DStub stub = new RenderPipeline3DStub(logger, false);
        assertFalse(stub.isInitialized());
        assertFalse(stub.isWarningLogged());
        assertEquals(RenderMode.MODE_3D, stub.getMode());

        stub.init(1920, 1080);
        assertTrue(stub.isInitialized());
        assertTrue(stub.isWarningLogged());

        float expectedZ = RenderPipeline3DStub.computeCameraDistanceZ(1920, 75.0f);
        assertEquals(expectedZ, stub.getCameraDistanceZ(), EPS);

        Matrix4f expectedProj = new Matrix4f().setPerspective(
                (float) Math.toRadians(75.0f),
                1920.0f / 1080.0f,
                RenderPipeline3DStub.Z_NEAR,
                RenderPipeline3DStub.Z_FAR
        );
        Matrix4f actualProj = stub.getProjectionMatrix();
        assertEquals(expectedProj, actualProj);
        assertNotSame(actualProj, stub.getProjectionMatrix());

        // Center of stage (960, 540, 0) in camera view space should sit at (0, 0, -cameraDistanceZ)
        Matrix4f view = stub.getViewMatrix();
        Vector3f viewPos = view.transformPosition(new Vector3f(960.0f, 540.0f, 0.0f));
        assertEquals(0.0f, viewPos.x, EPS);
        assertEquals(0.0f, viewPos.y, EPS);
        assertEquals(-expectedZ, viewPos.z, EPS);

        // Projecting stage center through projection * view lands at NDC center (0, 0)
        Vector3f ndcCenter = actualProj.mul(view, new Matrix4f())
                .transformProject(new Vector3f(960.0f, 540.0f, 0.0f));
        assertEquals(0.0f, ndcCenter.x, EPS);
        assertEquals(0.0f, ndcCenter.y, EPS);
        assertTrue(ndcCenter.z > -1.0f && ndcCenter.z < 1.0f);
    }

    @Test
    void logsControlledWarningsAndRunsFullLifecycleHeadlesslyWithoutCrash() {
        EngineLogger logger = new EngineLogger();
        logger.setConsoleOutputEnabled(false);

        RenderPipeline3DStub stub = new RenderPipeline3DStub(logger);
        SceneGraph graph = new SceneGraph();
        graph.addNode(SceneGraph.LAYER_MASTER, new SceneNode("hero").setSize(400, 600));

        stub.init(1920, 1080);
        assertTrue(stub.isWarningLogged());
        assertEquals(1, logger.size());

        stub.beginFrame(1280, 720);
        stub.renderSceneGraph(graph);
        stub.endFrame();

        // First renderSceneGraph emits one additional controlled warning; subsequent frames do not spam
        assertEquals(2, logger.size());
        stub.beginFrame(1280, 720);
        stub.renderSceneGraph(graph);
        stub.endFrame();
        assertEquals(2, logger.size());

        List<EngineLogger.LogEntry> entries = logger.getRecentEntries(10);
        assertEquals(EngineLogger.Level.WARN, entries.get(0).level());
        assertEquals(EngineLogger.Level.WARN, entries.get(1).level());
        assertTrue(entries.get(0).message().contains("MODE_3D"));
        assertTrue(entries.get(1).message().contains("MODE_3D"));

        stub.dispose();
        assertFalse(stub.isInitialized());
    }
}
