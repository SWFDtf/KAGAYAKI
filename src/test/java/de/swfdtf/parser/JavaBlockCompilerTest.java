package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.parser.VJavaAst.IfBranch;
import de.swfdtf.parser.VJavaAst.IfStatement;
import de.swfdtf.parser.VJavaAst.JavaBlockNode;
import de.swfdtf.parser.VJavaAst.JavaBlockStatement;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.MenuChoice;
import de.swfdtf.parser.VJavaAst.MenuStatement;
import de.swfdtf.parser.VJavaAst.ProjectAst;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JavaBlockCompiler} and {@link VJavaCompileException}.
 */
class JavaBlockCompilerTest {

    @Test
    void compilesCustomClassesSubclassesAndHelperMethodsInMemory() throws Exception {
        JavaBlockNode definitions = new JavaBlockNode(
                "blk_defs",
                "User classes",
                """
                // Comment with { braces } should be ignored
                /* Multi-line comment
                   with } and class Fake {} */
                import java.time.LocalDate;

                class InventarItem {
                    public final String name;
                    public final int wert;

                    public InventarItem(String name, int wert) {
                        this.name = name;
                        this.wert = wert;
                    }
                }

                public class SpezialGegenstand extends InventarItem {
                    public final String effekt;

                    public SpezialGegenstand(String name, int wert, String effekt) {
                        super(name, wert);
                        this.effekt = effekt;
                    }
                }

                static int bonusRechner(int basis, boolean aktiv) {
                    return aktiv ? basis + 25 : basis;
                }
                """,
                "main.vjava",
                10
        );

        JavaBlockNode execution = new JavaBlockNode(
                "blk_exec",
                "Use custom classes",
                """
                String braceLiteral = "{not a class}";
                SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                state.set("spieler_name", "Kaito");
                state.set("aktives_item", amulett);
                state.set("mut_punkte", bonusRechner(10, true));
                state.set("datum", LocalDate.of(2026, 11, 23).toString());
                state.set("brace_literal", braceLiteral);
                persistent.unlockAchievement("kompass_erhalten");
                engine.logInfo("Geladen: " + amulett.name);
                """,
                "main.vjava",
                50
        );

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        JavaBlockCompiler.CompiledProjectBlocks compiled = compiler.compile(List.of(definitions, execution));

        assertTrue(compiled.hasUserTypes());
        assertEquals(2, compiled.size());
        assertNotNull(compiled.getClassLoader());

        Class<?> baseClass = compiled.loadUserType("InventarItem");
        Class<?> subClass = compiled.loadUserType("SpezialGegenstand");
        assertTrue(baseClass.isAssignableFrom(subClass));

        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        SceneController scene = new SceneController(new SceneGraph());

        compiled.requireExecutable("blk_defs").execute(state, persistent, engine, scene);
        compiled.requireExecutable("blk_exec").execute(state, persistent, engine, scene);

        assertEquals("Kaito", state.getString("spieler_name", ""));
        assertEquals(35, state.getInt("mut_punkte", 0));
        assertEquals("2026-11-23", state.getString("datum", ""));
        assertEquals("{not a class}", state.getString("brace_literal", ""));
        assertTrue(persistent.isAchievementUnlocked("kompass_erhalten"));

        Object item = state.get("aktives_item");
        assertNotNull(item);
        assertTrue(subClass.isInstance(item));
        assertEquals("Sternenkompass", baseClass.getField("name").get(item));
        assertEquals("Nachtsicht", subClass.getField("effekt").get(item));
    }

    @Test
    void collectsNestedBlocksFromProjectAstAcrossLabelsIfsAndMenus() throws Exception {
        JavaBlockNode top = new JavaBlockNode("top_1", "top", "static int factor = 3;", "a.vjava", 2);
        JavaBlockNode inIf = new JavaBlockNode("if_1", "if", "state.set(\"from_if\", 7 * factor);", "a.vjava", 15);
        JavaBlockNode inMenu = new JavaBlockNode("menu_1", "menu", "state.set(\"from_menu\", true);", "a.vjava", 22);

        IfStatement ifStmt = new IfStatement(
                List.of(new IfBranch("true", List.of(new JavaBlockStatement(inIf, "a.vjava", 15)))),
                "a.vjava",
                14
        );
        MenuStatement menuStmt = new MenuStatement(
                null,
                null,
                null,
                List.of(new MenuChoice(
                        "Ok", null, false,
                        List.of(new JavaBlockStatement(inMenu, "a.vjava", 22)),
                        null, "a.vjava", 21)),
                "a.vjava",
                20
        );
        LabelNode startLabel = new LabelNode("start", List.of(ifStmt, menuStmt), "a.vjava", 12);

        ProjectAst ast = new ProjectAst(
                Path.of("."),
                ProjectDescriptor.createDefault("Test"),
                List.of(Path.of("a.vjava")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("start", startLabel),
                Map.of(),
                List.of(),
                List.of(top),
                List.of(),
                List.of(),
                List.of()
        );

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        JavaBlockCompiler.CompiledProjectBlocks compiled = compiler.compile(ast);

        assertEquals(3, compiled.size());
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        SceneController scene = new SceneController(new SceneGraph());

        compiled.requireExecutable("if_1").execute(state, persistent, engine, scene);
        compiled.requireExecutable("menu_1").execute(state, persistent, engine, scene);

        assertEquals(21, state.getInt("from_if", 0));
        assertTrue(state.getBoolean("from_menu", false));
    }

    @Test
    void reportsStructuredDiagnosticsOnInvalidSnippetSyntax() {
        JavaBlockNode brokenSnippet = new JavaBlockNode(
                "broken_snippet",
                "Broken code",
                """
                int ok = 1;
                String bad = 12345;
                """,
                "chapters/scene1.vjava",
                42
        );

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        VJavaCompileException ex = assertThrows(
                VJavaCompileException.class,
                () -> compiler.compile(List.of(brokenSnippet))
        );

        assertEquals("broken_snippet", ex.getBlockId());
        assertEquals("chapters/scene1.vjava", ex.getSourceFile());
        assertTrue(ex.getLineNumber() >= 42);
        assertFalse(ex.getDiagnosticMessages().isEmpty());
        assertFalse(ex.getDiagnostics().isEmpty());
        assertTrue(ex.getMessage().contains("chapters/scene1.vjava"));
    }

    @Test
    void reportsStructuredDiagnosticsOnInvalidUserTypeDeclaration() {
        JavaBlockNode brokenClass = new JavaBlockNode(
                "broken_type",
                "Broken class",
                """
                public static class Kaputt {
                    public int wert = "keine_zahl";
                }
                """,
                "defs/items.vjava",
                7
        );

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        VJavaCompileException ex = assertThrows(
                VJavaCompileException.class,
                () -> compiler.compile(List.of(brokenClass))
        );

        assertEquals("broken_type", ex.getBlockId());
        assertEquals("defs/items.vjava", ex.getSourceFile());
        assertTrue(ex.getLineNumber() >= 7);
        assertFalse(ex.getDiagnosticMessages().isEmpty());
    }
}
