package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptDebugger;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.TextInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Wave 5 F09 Studio IDE, syntax highlighter, script debugger, template author,
 * and multiline editor inputs.
 */
class Wave5StudioIdeAndDebuggerTest {

    @Test
    void syntaxHighlighterHandlesTokensEdgeCasesAndFastThroughput() {
        VJavaSyntaxHighlighter highlighter = new VJavaSyntaxHighlighter();
        String sample = "label start:\n    $ hp = 10\n    :: js @->{ String s = \"}<<\"; }<< // end\n";
        List<VJavaSyntaxHighlighter.TokenRun> runs = highlighter.tokenize(sample);
        assertFalse(runs.isEmpty());
        assertTrue(runs.stream().anyMatch(r -> r.type() == VJavaSyntaxHighlighter.TokenType.KEYWORD));
        assertTrue(runs.stream().anyMatch(r -> r.type() == VJavaSyntaxHighlighter.TokenType.VARIABLE));
        assertTrue(runs.stream().anyMatch(r -> r.type() == VJavaSyntaxHighlighter.TokenType.JAVA_BLOCK));
        assertTrue(runs.stream().anyMatch(r -> r.type() == VJavaSyntaxHighlighter.TokenType.COMMENT));

        String bulk = sample.repeat(350);
        highlighter.tokenize(bulk);
        long t0 = System.nanoTime();
        List<VJavaSyntaxHighlighter.TokenRun> bulkRuns = highlighter.tokenize(bulk);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertFalse(bulkRuns.isEmpty());
        assertTrue(elapsedMs < 10L, "Bulk 1,050-line tokenize should be fast (< 2 ms typical), took " + elapsedMs);
    }

    @Test
    void scriptDebuggerBreakpointsAndStepModes(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    call helper
                    "Done"
                    return
                label helper:
                    "Inside helper"
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(tempDir);
        EngineAPI api = new EngineAPI(EngineConfig.builder().projectDir(tempDir).headless(true).build());
        ScriptRuntime runtime = new ScriptRuntime(ast, api);
        runtime.compile();

        ScriptDebugger debugger = new ScriptDebugger();
        assertThrows(IllegalArgumentException.class, () -> debugger.addBreakpoint("  ", 0));
        assertThrows(IllegalArgumentException.class, () -> debugger.addBreakpoint("start", -1));

        debugger.addBreakpoint("helper", 0);
        assertTrue(debugger.hasBreakpoint("helper", 0));

        debugger.stepInto(runtime);
        assertTrue(debugger.isPaused());
        assertEquals("helper", runtime.getCurrentLabel());
        assertFalse(debugger.captureCallStackSummary(runtime).isEmpty());

        debugger.continueExecution(runtime);
        assertEquals("start", runtime.getCurrentLabel());
    }

    @Test
    void multilineTextInputAndCustomAuthorProjectTemplate(@TempDir Path tempDir) throws Exception {
        TextInput editor = new TextInput("", null).setMultiline(true);
        editor.setText("label start:\n    \"Line 1\"\n    return\n");
        assertTrue(editor.getText().contains("\n"));

        Path out = ProjectTemplateGenerator.generate(
                tempDir.resolve("author_proj"),
                "Author Test",
                "Alice Studio",
                "3D",
                ProjectTemplateGenerator.TemplateType.MINIMAL
        );
        ProjectDescriptor loaded = ProjectDescriptor.load(out);
        assertEquals("Author Test", loaded.getTitle());
        assertEquals("Alice Studio", loaded.getAuthor());
    }
}
