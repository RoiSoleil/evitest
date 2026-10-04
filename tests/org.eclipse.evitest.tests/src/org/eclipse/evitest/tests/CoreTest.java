package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.TestFilePatterns;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.core.VitestCommandLine;
import org.eclipse.evitest.core.VitestLocator;
import org.eclipse.evitest.ui.StackFrameLocation;
import org.junit.jupiter.api.Test;

/** The JSON, the test file patterns, the location of Vitest, its command line, the locations of the stack frames. */
class CoreTest {

  @Test
  void jsonRoundTrip() {
    String json = "{\"a\":1,\"b\":[true,false,null,\"x\\n\\\"y\\\" \\u00e9\"],\"c\":{\"d\":-1.5e2}}";
    Object value = Json.parse(json);
    @SuppressWarnings("unchecked")
    Map<String, Object> object = (Map<String, Object>) value;
    assertEquals(Integer.valueOf(1), Json.getInteger(object, "a"));
    assertEquals("x\n\"y\" \u00e9", ((List<?>) object.get("b")).get(3));
    assertEquals(-150.0, ((Number) ((Map<?, ?>) object.get("c")).get("d")).doubleValue());
    assertEquals("{\"a\":1,\"b\":[true,false,null,\"x\\n\\\"y\\\" \u00e9\"],\"c\":{\"d\":-150}}", Json.write(value));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
    assertTrue(Json.parseObject("[1]").isEmpty());
    assertTrue(Json.parseObject("broken").isEmpty());
  }

  @Test
  void testFilePatterns() {
    TestFilePatterns defaults = new TestFilePatterns(TestFilePatterns.DEFAULT);
    assertTrue(defaults.matches("math.test.ts"));
    assertTrue(defaults.matches("Button.spec.tsx"));
    assertTrue(defaults.matches("api.test.mjs"));
    assertFalse(defaults.matches("math.ts"));
    assertFalse(defaults.matches("test.ts"));
    assertFalse(defaults.matches("math.test.ts.snap"));
    // Jasmine, Deno, node:test.
    assertTrue(defaults.matches("mathSpec.js"));
    assertTrue(defaults.matches("math_test.ts"));
    assertTrue(defaults.matches("math-test.mjs"));
    assertFalse(defaults.matches("inspect.js"));
    assertFalse(defaults.matches("latest.ts"));
    TestFilePatterns custom = new TestFilePatterns("*.unit.ts, check_*.js");
    assertTrue(custom.matches("a.unit.ts"));
    assertTrue(custom.matches("check_a.js"));
    assertFalse(custom.matches("a.test.ts"));
  }

  @Test
  void commandLine() {
    VitestLocator.Installation vitest5 = new VitestLocator.Installation(new File("/p/node_modules/vitest/vitest.mjs"),
        "5.0.3");
    List<String> command = new VitestCommandLine().node("/usr/bin/node").vitest(vitest5).reporter("/r/reporter.mjs")
        .filters(List.of("src/a.test.ts", "src\\b.test.ts", "src/a.test.ts", "."))
        .selectors(List.of(TestSelector.test(List.of("a", "b")))).namePattern("ignored").updateSnapshots(true)
        .inspector(9229).arguments(List.of("--bail=1")).build();
    assertEquals(List.of("/usr/bin/node", new File("/p/node_modules/vitest/vitest.mjs").getAbsolutePath(), "run",
        "src/a.test.ts", "src/b.test.ts", "--reporter=default", "--reporter=/r/reporter.mjs", "--includeTaskLocation",
        "--testNamePattern=^a(?: | > )b$", "--update", "--inspectBrk=127.0.0.1:9229", "--no-file-parallelism",
        "--bail=1"), command);

    VitestLocator.Installation vitest2 = new VitestLocator.Installation(new File("vitest.mjs"), "2.1.9");
    List<String> old = new VitestCommandLine().vitest(vitest2).namePattern("^adds").build();
    assertFalse(old.contains("--includeTaskLocation"));
    assertTrue(old.contains("--testNamePattern=^adds"));
    assertFalse(new VitestCommandLine().vitest(vitest2).namePattern(" ").build().stream()
        .anyMatch(argument -> argument.startsWith("--testNamePattern")));
    assertEquals(0, new VitestLocator.Installation(new File("x"), null).major());
  }

  @Test
  void locator() throws IOException {
    Path root = Files.createTempDirectory("evitest");
    try {
      Path app = Files.createDirectories(root.resolve("packages/app/src/deep"));
      Path lib = Files.createDirectories(root.resolve("packages/lib/src"));
      Files.writeString(root.resolve("package.json"), "{\"devDependencies\":{\"vitest\":\"^5.0.0\"}}");
      Files.writeString(root.resolve("packages/app/vite.config.ts"), "export default {}");
      Files.writeString(root.resolve("packages/lib/package.json"), "{\"name\":\"lib\"}");
      Path test = Files.writeString(app.resolve("a.test.ts"), "");

      assertEquals(root.resolve("packages/app").toFile(), VitestLocator.findRoot(test.toFile()));
      assertEquals(root.resolve("packages/app").toFile(), VitestLocator.findRoot(app.toFile()));
      // No configuration: the package depending on Vitest, not the nearest package.json.
      assertEquals(root.toFile(), VitestLocator.findRoot(lib.toFile()));
      assertNull(VitestLocator.findInstallation(root.toFile()));

      // Hoisted in the root of the monorepo.
      Path vitest = Files.createDirectories(root.resolve("node_modules/vitest"));
      Files.writeString(vitest.resolve("vitest.mjs"), "");
      Files.writeString(vitest.resolve("package.json"), "{\"name\":\"vitest\",\"version\":\"4.1.2\"}");
      VitestLocator.Installation installation = VitestLocator.findInstallation(root.resolve("packages/app").toFile());
      assertNotNull(installation);
      assertEquals("4.1.2", installation.version());
      assertEquals(4, installation.major());
      assertEquals(vitest.resolve("vitest.mjs").toFile(), installation.entry());
    } finally {
      delete(root.toFile());
    }
  }

  @Test
  void stackFrameLocations() throws IOException {
    Path root = Files.createTempDirectory("evitest");
    try {
      Path file = Files.writeString(Files.createDirectories(root.resolve("src")).resolve("a b.test.ts"), "",
          StandardCharsets.UTF_8);
      String path = file.toString();
      assertEquals(new StackFrameLocation(path, 12, 5), StackFrameLocation.parse("    at " + path + ":12:5", null));
      assertEquals(new StackFrameLocation(path, 3, 1),
          StackFrameLocation.parse("    at Object.<anonymous> (" + path + ":3:1)", null));
      assertEquals(new StackFrameLocation(path, 7, 2),
          StackFrameLocation.parse("    at fn (" + file.toUri() + ":7:2)", null));
      assertEquals(new StackFrameLocation(path, 4, 9), StackFrameLocation.parse(" ❯ src/a b.test.ts:4:9", root.toString()));
      assertEquals(new StackFrameLocation(path, 2, 17),
          StackFrameLocation.parse("   ╭─[ src/a b.test.ts:2:17 ]", root.toString()));
      assertEquals(new StackFrameLocation(path, 8, 0), StackFrameLocation.parse(path + ":8", null));
      assertNull(StackFrameLocation.parse("    at node:internal/process/task_queues:95:5", null));
      assertNull(StackFrameLocation.parse("    at /does/not/exist.ts:1:1", null));
      assertNull(StackFrameLocation.parse("AssertionError: expected 1 to be 2", null));
    } finally {
      delete(root.toFile());
    }
  }

  static void delete(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    file.delete();
  }
}
