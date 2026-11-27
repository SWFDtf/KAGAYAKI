package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.Camera3D;
import de.swfdtf.graphics.Material3D;
import de.swfdtf.graphics.Mesh3D;
import de.swfdtf.graphics.Scene3DWorld;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaDslReferenceGenerator;
import de.swfdtf.parser.VJavaParser;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-4 3D DSL statements, VJavaLinter 3D checks,
 * {@code HYBRID_3D_EXPLORATION} project template, and {@link Studio3DInspectorService}.
 */
class WaveP2Chapter4DslAndTools3DTest {

    @Test
    @DisplayName("VJavaParser parses scene3d, spawn3d, camera3d, wasd3d_init, fog3d, material3d")
    void parserParsesAll3dStatements() {
        String source = """
                label start:
                    scene3d "assets/models/hall.obj"
                    spawn3d pillar "assets/models/pillar.obj" at 2.0 0.0 -4.0
                    camera3d look_at 0.0 1.4 -4.0 duration 0.5
                    wasd3d_init speed 4.5 fov 72.0
                    fog3d "#1b2842" density 0.025 falloff 0.15
                    material3d pillar tint "#c8a878" roughness 0.45 metallic 0.10
                    return
                """;
        VJavaAst.ScriptFileAst fileAst = VJavaParser.parse(Path.of("main.vjava"), source);
        VJavaAst.LabelNode label = fileAst.labels().get(0);
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Scene3DStatement));
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Spawn3DStatement));
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Camera3DStatement));
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Wasd3DInitStatement));
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Fog3DStatement));
        assertTrue(label.statements().stream().anyMatch(s -> s instanceof VJavaAst.Material3DStatement));
    }

    @Test
    @DisplayName("ScriptRuntime steps through 3D DSL statements into dialogue cleanly")
    void scriptRuntimeExecutes3dStatements(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    scene3d "hall"
                    spawn3d statue "cube" at 0 0 -5
                    camera3d move 0 2 5 duration 0.4
                    wasd3d_init speed 5.0 fov 75.0
                    fog3d "#203040" density 0.02 falloff 0.1
                    material3d statue tint "#ffffff" roughness 0.5 metallic 0.2
                    "3D hall initialized."
                    return
                """, StandardCharsets.UTF_8);
        VJavaAst.ProjectAst project = ProjectScanner.scan(tempDir);
        ScriptRuntime runtime = new ScriptRuntime(project);
        runtime.start("start");
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("3D hall initialized.", runtime.getActiveDialogue().interpolatedText());
    }

    @Test
    @DisplayName("VJavaDslReferenceGenerator documents all 6 new 3D DSL commands")
    void dslReferenceIncludes3dCommands() {
        String md = VJavaDslReferenceGenerator.generateMarkdown();
        assertTrue(md.contains("scene3d"));
        assertTrue(md.contains("spawn3d"));
        assertTrue(md.contains("camera3d"));
        assertTrue(md.contains("wasd3d_init"));
        assertTrue(md.contains("fog3d"));
        assertTrue(md.contains("material3d"));
    }

    @Test
    @DisplayName("VJavaLinter emits MISSING_ASSET when scene3d or spawn3d mesh file does not exist")
    void linterDetectsMissing3dMeshAsset(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    scene3d "assets/models/missing_hall.obj"
                    spawn3d prop "assets/models/missing_prop.glb" at 0 0 0
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(tempDir);
        VJavaLinter.LintReport report = VJavaLinter.lint(project);
        assertEquals(2, report.issues().stream().filter(i -> "MISSING_ASSET".equals(i.code())).count());
    }

    @Test
    @DisplayName("VJavaLinter permits built-in 3D primitive keywords without disk mesh file")
    void linterPermitsBuiltin3dPrimitives(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    scene3d "hall"
                    spawn3d box "cube" at 0 0 -3
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(tempDir);
        VJavaLinter.LintReport report = VJavaLinter.lint(project);
        assertFalse(report.issues().stream().anyMatch(i -> "MISSING_ASSET".equals(i.code())));
    }

    @Test
    @DisplayName("VJavaLinter warns HIGH_POLY_MESH when OBJ mesh exceeds 100,000 vertices")
    void linterWarnsHighPolyMesh(@TempDir Path tempDir) throws IOException {
        Path modelsDir = tempDir.resolve("assets/models");
        Files.createDirectories(modelsDir);
        StringBuilder hugeObj = new StringBuilder();
        for (int i = 0; i < 100_005; i++) {
            hugeObj.append("v 0.0 0.0 0.0\n");
        }
        Files.writeString(modelsDir.resolve("dense.obj"), hugeObj.toString(), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    spawn3d hi "assets/models/dense.obj" at 0 0 0
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(tempDir);
        VJavaLinter.LintReport report = VJavaLinter.lint(project);
        assertTrue(report.issues().stream().anyMatch(i ->
                "HIGH_POLY_MESH".equals(i.code()) && i.severity() == VJavaLinter.Severity.WARNING));
    }

    @Test
    @DisplayName("ProjectTemplateGenerator HYBRID_3D_EXPLORATION scaffolds clean 3D project and OBJs")
    void hybrid3dTemplateGeneratesCleanProject(@TempDir Path tempDir) throws IOException {
        Path root = ProjectTemplateGenerator.generate(
                tempDir.resolve("hybrid3d_proj"),
                "Astral Hall 3D",
                "Studio",
                "2D",
                ProjectTemplateGenerator.TemplateType.HYBRID_3D_EXPLORATION
        );

        ProjectDescriptor descriptor = ProjectDescriptor.load(root);
        assertEquals("3D", descriptor.getRenderMode());
        assertTrue(Files.isRegularFile(root.resolve("assets/models/hall.obj")));
        assertTrue(Files.isRegularFile(root.resolve("assets/models/hall.mtl")));
        assertTrue(Files.isRegularFile(root.resolve("assets/models/pillar.obj")));

        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);
        VJavaLinter.LintReport report = VJavaLinter.lint(ast);
        assertEquals(0, report.errorCount(), "Unexpected errors: " + report.issues());
        assertEquals(0, report.warningCount(), "Unexpected warnings: " + report.issues());
    }

    @Test
    @DisplayName("UserTemplateRepository lists built-in preview card for HYBRID_3D_EXPLORATION")
    void templateRepositoryIncludesHybrid3dCard(@TempDir Path tempDir) throws IOException {
        UserTemplateRepository repo = new UserTemplateRepository(tempDir.resolve("user_templates"));
        assertTrue(repo.listAllPreviewCards().stream().anyMatch(c ->
                "HYBRID_3D_EXPLORATION".equals(c.templateId()) && !c.userDefined()));
    }

    @Test
    @DisplayName("Studio3DInspectorService inspects and edits live Camera3D position and FOV")
    void studioInspectorLiveCameraEdit() {
        Camera3D camera = new Camera3D();
        Studio3DInspectorService inspector = new Studio3DInspectorService();
        Studio3DInspectorService.CameraSnapshot edited = inspector.applyCameraEdit(camera, 2.0f, 3.0f, 8.0f, 75.0f);
        assertEquals(2.0f, edited.x(), 0.01f);
        assertEquals(75.0f, edited.fovDegrees(), 0.01f);
        assertTrue(edited.formatSummary().contains("fov=75.0"));
    }

    @Test
    @DisplayName("Studio3DInspectorService drags props along X/Y/Z axes with grid snap and snippet")
    void studioInspectorGizmoDragWithSnap() {
        Scene3DWorld world = new Scene3DWorld();
        world.putNode(new Scene3DWorld.Node3D("pillar", Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("pillar_mat", "#ffffff")).setPosition(1.0f, 0.0f, -2.0f));

        Studio3DInspectorService inspector = new Studio3DInspectorService();
        Studio3DInspectorService.PropDragResult dragX = inspector.dragNodeAlongAxis(
                world, "pillar", Studio3DInspectorService.GizmoAxis.X, 1.37f, 0.5f);
        Studio3DInspectorService.PropDragResult dragY = inspector.dragNodeAlongAxis(
                world, "pillar", Studio3DInspectorService.GizmoAxis.Y, 1.2f, 0.5f);
        assertEquals(2.5f, dragX.x(), 0.01f);
        assertEquals(1.0f, dragY.y(), 0.01f);
        assertTrue(dragY.vjavaSnippet().startsWith("spawn3d pillar "));
        assertThrows(IllegalArgumentException.class, () ->
                inspector.dragNodeAlongAxis(world, "missing", Studio3DInspectorService.GizmoAxis.Z, 1f, 0f));
    }

    @Test
    @DisplayName("Studio3DInspectorService updates node material uniforms and exports formatted JSON")
    void studioInspectorMaterialEditAndJsonExport() {
        Scene3DWorld world = new Scene3DWorld();
        world.putNode(new Scene3DWorld.Node3D("pillar", Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("pillar_mat", "#ffffff")));

        Studio3DInspectorService inspector = new Studio3DInspectorService();
        Material3D updated = inspector.updateNodeMaterial(
                world, "pillar", Material3D.ShaderStyle.TOON, "#388bfd", 0.2f, 0.6f, 0.15f);
        assertEquals(Material3D.ShaderStyle.TOON, updated.getStyle());
        String json = inspector.exportMaterialsJson(world);
        assertNotNull(json);
        assertTrue(json.contains("\"style\": \"TOON\""));
    }

    @Test
    @DisplayName("Studio3DInspectorService generates NavPath debug lines only when Studio debug is active")
    void studioInspectorNavPathDebugStudioOnly() {
        Studio3DInspectorService inspector = new Studio3DInspectorService();
        List<Vector3f> path = List.of(new Vector3f(0, 0, 0), new Vector3f(0, 0, -5), new Vector3f(4, 0, -5));
        assertFalse(inspector.buildNavPathLines(path, 0.5f).isEmpty());

        inspector.setStudioDebugEnabled(false);
        assertFalse(inspector.isStudioDebugEnabled());
        assertTrue(inspector.buildNavPathLines(path, 0.5f).isEmpty());
    }
}
