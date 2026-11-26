package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineEventBus;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VJavaScriptFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests covering Chapter 1 parser, runtime, sandbox, coverage, macro, linter, formatter,
 * JSON AST, TextMate grammar, REPL, and DSL reference P2 goals ({@code #0023}, {@code #0031},
 * {@code #0034}, {@code #0035}, {@code #0043}, {@code #0045}–{@code #0047}, {@code #0050},
 * {@code #0059}–{@code #0068}, {@code #0077}–{@code #0078}, {@code #0084}–{@code #0085}).
 */
class WaveP2Chapter1DslParserTest {

    public static final class InventoryProbe {
        public boolean hasItem(String item) {
            return "key".equals(item);
        }
    }

    @Test
    void parameterizedMacroAndRepeatWhileGuardsWorkCleanly() {
        String source = """
                macro bump(target, amount):
                    $target += amount

                label start:
                    $score = 0
                    repeat 3:
                        bump(score, 5)
                    "Done [score]"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("macro_test.vjava"), source);
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("macro_test.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("start", ast.labels().get(0)),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        ScriptRuntime runtime = new ScriptRuntime(project);
        runtime.start("start");
        assertEquals(15, runtime.getStateStore().getInt("score", -1));
        assertEquals("Done 15", runtime.getActiveDialogue().interpolatedText());

        String infSource = """
                label start:
                    while true:
                        $x = 1
                """;
        VJavaAst.ScriptFileAst infAst = VJavaParser.parse(Path.of("inf.vjava"), infSource);
        VJavaAst.ProjectAst infProject = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("inf.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("start", infAst.labels().get(0)),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ScriptRuntime infRuntime = new ScriptRuntime(infProject).setMaxLoopIterations(25);
        assertThrows(ScriptExecutionException.class, () -> infRuntime.start("start"));
    }

    @Test
    void configIncludeNamespaceAndDeepLineNumbersArePreserved(@TempDir Path dir) throws Exception {
        String mainScript = """
                include "chapter1.vjava"
                config:
                    text_speed = 65
                    default_gravity = 980

                label start:
                    $ok = true
                    repeat 1:
                        if $ok:
                            menu:
                                "Go":
                                    if $ok:
                                        jump chapter1.intro
                """;
        String subScript = """
                label chapter1.intro:
                    "In chapter 1"
                    jump .next
                label chapter1.next:
                    "Nested relative target"
                """;
        Files.writeString(dir.resolve("main.vjava"), mainScript, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("chapter1.vjava"), subScript, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(dir);
        assertEquals("65", project.configValues().get("text_speed"));
        assertEquals("980", project.configValues().get("default_gravity"));
        assertTrue(project.includes().contains("chapter1.vjava"));

        VJavaAst.MenuStatement menuStmt = (VJavaAst.MenuStatement) ((VJavaAst.IfStatement)
                ((VJavaAst.RepeatStatement) project.labels().get("start").statements().get(1))
                        .body().get(0)).branches().get(0).body().get(0);
        VJavaAst.IfStatement innerIf = (VJavaAst.IfStatement) menuStmt.choices().get(0).body().get(0);
        assertEquals(13, innerIf.branches().get(0).body().get(0).lineNumber());

        ScriptRuntime runtime = new ScriptRuntime(project);
        runtime.start("start");
        runtime.choose(0);
        assertEquals("In chapter 1", runtime.getActiveDialogue().interpolatedText());
        runtime.advance();
        assertEquals("Nested relative target", runtime.getActiveDialogue().interpolatedText());

        VJavaLinter.LintReport report = VJavaLinter.lint(project);
        assertTrue(report.isClean(), "Expected clean namespace lint, got: " + report.issues());
    }

    @Test
    void timersEventsCoroutinesRandomSetBlockLocalAndMenuTimeoutWork() {
        String source = """
                label bg_choreo:
                    pause 0.2
                    $bg_done = true
                label on_hit_event:
                    $event_fired = true
                    return
                label timed_dest:
                    "Timer fired!"
                label timeout_dest:
                    "Menu timed out!"
                label start:
                    $tmp = 99
                    local $tmp = 7
                    set:
                        $a = 10
                        $b = 20
                    on_event "magic_ping" call on_hit_event
                    spawn_cutscene bg_choreo
                    after 0.1 jump timed_dest
                    "Inside loop [tmp]"
                label sub_test:
                    local $sub_only = 123
                    local $tmp = 500
                    return
                label menu_test:
                    menu:
                        timeout 1.0 -> jump timeout_dest
                        "Choice" -> jump timed_dest
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("flow.vjava"), source);
        Map<String, VJavaAst.LabelNode> labels = new java.util.LinkedHashMap<>();
        ast.labels().forEach(l -> labels.put(l.name(), l));
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("flow.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                labels, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        EngineEventBus bus = new EngineEventBus(new EngineLogger());
        ScriptRuntime runtime = new ScriptRuntime(project).bindEventBus(bus);
        runtime.start("start");
        assertEquals(10, runtime.getStateStore().getInt("a", -1));
        assertEquals(20, runtime.getStateStore().getInt("b", -1));
        assertEquals(7, runtime.getStateStore().getInt("tmp", -1));

        bus.publish(new ScriptRuntime.ScriptNamedEvent("magic_ping", Map.of()));
        assertTrue(runtime.getStateStore().getBoolean("event_fired", false));

        runtime.update(0.15);
        assertEquals("Timer fired!", runtime.getActiveDialogue().interpolatedText());
        runtime.update(0.15);
        assertTrue(runtime.getStateStore().getBoolean("bg_done", false));

        runtime.jumpToLabel("menu_test");
        runtime.update(0.4);
        assertEquals(0.6, runtime.getActiveMenu().remainingSeconds(), 0.01);
        assertEquals(0.6, runtime.getStateStore().getDouble("_menu_timeout_remaining", -1.0), 0.01);
        runtime.update(0.7);
        assertEquals("Menu timed out!", runtime.getActiveDialogue().interpolatedText());
    }

    @Test
    void localVarsRestoreOnCallReturnAndRandomFollowsWeights() {
        String source = """
                label worker:
                    local $x = 999
                    local $fresh = "temp"
                    return
                label start:
                    $x = 10
                    call worker
                    "Back [x]"
                label roll:
                    $a_hits = 0
                    $b_hits = 0
                    repeat 1000:
                        random:
                            weight 75:
                                $a_hits += 1
                            weight 25:
                                $b_hits += 1
                    "Rolled"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("local_rand.vjava"), source);
        Map<String, VJavaAst.LabelNode> labels = new java.util.LinkedHashMap<>();
        ast.labels().forEach(l -> labels.put(l.name(), l));
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("local_rand.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                labels, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        ScriptRuntime runtime = new ScriptRuntime(project).setRandomSeed(42L).setMaxLoopIterations(2000);
        runtime.start("start");
        assertEquals(10, runtime.getStateStore().getInt("x", -1));
        assertFalse(runtime.getStateStore().has("fresh"));

        runtime.jumpToLabel("roll");
        int aHits = runtime.getStateStore().getInt("a_hits", 0);
        assertTrue(aHits >= 700 && aHits <= 800, "Expected ~75% hits, got " + aHits);
    }

    @Test
    void javaSandboxListMapLiteralsAndObjectCallbacksWorkSafely() {
        String unsafeScript = """
                @->{
                java.io.File f = new java.io.File("secret.txt");
                }<<
                label start:
                    "Hi"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("unsafe.vjava"), unsafeScript);
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("unsafe.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("start", ast.labels().get(0)),
                Map.of(), List.of(), ast.topLevelJavaBlocks(), List.of(), List.of(), List.of());

        JavaBlockCompiler sandboxed = new JavaBlockCompiler().setSandboxEnabled(true);
        assertThrows(VJavaCompileException.class, () -> sandboxed.compile(project));

        SimpleStateStore store = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        store.set("inventory", new InventoryProbe());
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("inventory.hasItem(\"key\")", store, persistent));

        Object listVal = ConditionEvaluator.evaluate("[\"a\", \"b\", \"c\"]", store, persistent);
        Object mapVal = ConditionEvaluator.evaluate("{\"key\": 42, \"flag\": true}", store, persistent);
        assertTrue(listVal instanceof List<?>);
        assertEquals("b", ((List<?>) listVal).get(1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) listVal).clear());
        assertTrue(mapVal instanceof Map<?, ?>);
        assertEquals(42, ((Map<?, ?>) mapVal).get("key"));
    }

    @Test
    void linterDetectsUnknownTransformsAndUnusedSymbolsWhileFormatterIsIdempotent() {
        String source = """
                define hero = Character("Hero")
                define ghost = Character("Ghost")
                default unused_var = 42
                image hero happy = "assets/images/hero.png"
                label start:
                    show hero happy at centr
                    hero "Hello"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("lint.vjava"), source);
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("lint.vjava")),
                Map.of("hero", ast.characters().get(0), "ghost", ast.characters().get(1)),
                Map.of("hero happy", ast.images().get(0)),
                Map.of(), Map.of(),
                Map.of("start", ast.labels().get(0)),
                Map.of(), List.of(), List.of(), ast.defaultVariables(),
                List.of("assets/images/hero.png"), List.of());

        VJavaLinter.LintReport report = VJavaLinter.lint(project);
        assertTrue(report.issues().stream().anyMatch(
                i -> "UNKNOWN_TRANSFORM".equals(i.code()) && i.message().contains("center")));
        assertTrue(report.issues().stream().anyMatch(
                i -> i.severity() == VJavaLinter.Severity.INFO && "UNUSED_CHARACTER".equals(i.code())));
        assertTrue(report.issues().stream().anyMatch(
                i -> i.severity() == VJavaLinter.Severity.INFO && "UNUSED_VARIABLE".equals(i.code())));

        VJavaScriptFormatter formatter = new VJavaScriptFormatter();
        String messy = "label start:\n\tmove   player   to   400   500   duration   0.5\n"
                + "\twasd_init   player   speed   280\n";
        String first = formatter.format(messy);
        assertTrue(first.contains("    move player to 400 500 duration 0.5"));
        assertTrue(first.contains("    wasd_init player speed 280"));
        assertEquals(first, formatter.format(first), "Formatter must be strictly idempotent");
    }

    @Test
    void astJsonTextMateReplCoverageAndDslReferenceCoverAllRequirements() {
        String source = """
                label start:
                    $hp = 10
                    if $hp > 5:
                        "Strong"
                    else:
                        "Weak"
                    "End"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("cov.vjava"), source);
        VJavaAst.ProjectAst project = new VJavaAst.ProjectAst(
                Path.of("."), null, List.of(Path.of("cov.vjava")),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("start", ast.labels().get(0)),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String json = AstJsonSerializer.toJson(project);
        assertTrue(json.contains("\"labels\"") && json.contains("\"start\""));

        String grammar = TextMateGrammarGenerator.generateTmLanguageJson();
        assertTrue(grammar.contains("\"scopeName\": \"source.vjava\""));
        assertTrue(grammar.contains("wasd_init") && grammar.contains("physics_init"));

        ScriptCoverageTracker tracker = new ScriptCoverageTracker();
        SceneController sc = new SceneController(new SceneGraph(), null, new EngineLogger(), null, false);
        ScriptRuntime runtime = new ScriptRuntime(
                project, new EngineAPI(EngineConfig.defaults()),
                new SimpleStateStore(), new SimplePersistentAccess(), sc)
                .setCoverageTracker(tracker);
        runtime.start("start");
        runtime.advance();

        ScriptCoverageTracker.CoverageReport covReport = tracker.buildReport(project);
        assertEquals(80.0, covReport.statementPercentage(), 0.01);
        assertEquals(50.0, covReport.branchPercentage(), 0.01);

        sc.spawnSprite("lena", "assets/images/lena.png", 100.0f, 100.0f);
        VJavaReplEvaluator.ReplResult replRes = VJavaReplEvaluator.evaluateLine("move lena to 500 500", runtime);
        assertTrue(replRes.success());
        assertEquals(500.0f, sc.getActiveSprite("lena").getTransform().getXpos(), 0.01f);
        assertEquals(500.0f, sc.getActiveSprite("lena").getTransform().getYpos(), 0.01f);

        int verifiedCount = VJavaDslReferenceGenerator.verifyAllExamplesParse();
        assertTrue(verifiedCount >= 60);
        String md = VJavaDslReferenceGenerator.generateMarkdown();
        assertTrue(md.contains("`PatrolStatement`") && md.contains("wasd_init"));
    }
}
