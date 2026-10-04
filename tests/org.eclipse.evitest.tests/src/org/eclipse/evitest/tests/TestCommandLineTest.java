package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The command lines of the frameworks, and the environment of their reporters. */
class TestCommandLineTest {

  private static final File REPORTERS = new File("/r");
  private static final List<TestSelector> ONE_TEST = List.of(TestSelector.test(List.of("math", "adds")));

  private Path root;

  @BeforeEach
  void createRoot() throws IOException {
    root = Files.createTempDirectory("evitest-command");
    Files.createDirectories(root.resolve("src/deep"));
    Files.createFile(root.resolve("src/a.test.ts"));
  }

  private static Installation installation(TestFramework framework, String version) {
    File entry = switch (framework) {
      case NODE -> null;
      case BUN -> new File("/bin/bun");
      case DENO -> new File("/bin/deno");
      default -> new File("/p/node_modules/" + framework.packageName() + "/" + framework.entry());
    };
    return new Installation(framework, entry, version);
  }

  private TestCommandLine commandLine(TestFramework framework) {
    return new TestCommandLine(installation(framework, "5.0.0")).node("/bin/node").nodeVersion("22.18.0")
        .reporters(REPORTERS).root(root.toFile());
  }

  private static String reporter(String name) {
    return new File(REPORTERS, name).getAbsolutePath().replace('\\', '/');
  }

  private static String entry(TestFramework framework) {
    return installation(framework, null).entry().getAbsolutePath();
  }

  @Test
  void vitest() {
    assumeFalse(NodeLocator.isWindows());
    assertEquals(List.of("/bin/node", entry(TestFramework.VITEST), "run", "src/a.test.ts", "--reporter=default",
        "--reporter=" + reporter("evitest-reporter.mjs"), "--includeTaskLocation", "--testNamePattern="
            + "^math(?: | > )adds$", "--update", "--bail=1"),
        commandLine(TestFramework.VITEST).filters(List.of("src/a.test.ts")).selectors(ONE_TEST).updateSnapshots(true)
            .arguments(List.of("--bail=1")).build());
  }

  @Test
  void jest() {
    assumeFalse(NodeLocator.isWindows());
    TestCommandLine commandLine = commandLine(TestFramework.JEST).filters(List.of("src/a.test.ts", "src/deep"))
        .selectors(ONE_TEST).updateSnapshots(true).arguments(List.of("--ci"));
    assertEquals(List.of("/bin/node", entry(TestFramework.JEST), "src\\/a\\.test\\.ts", "src\\/deep", "--reporters=default",
        "--reporters=" + reporter("evitest-jest.cjs"), "--testLocationInResults",
        "--testNamePattern=^math(?: | > )adds$", "--updateSnapshot", "--ci"), commandLine.build());
    assertEquals(Map.of("EVITEST_PATTERN", "^math(?: | > )adds$"), commandLine.environment());
    // Debugged in one process.
    List<String> debug = commandLine(TestFramework.JEST).inspector(9229).build();
    assertEquals(List.of("/bin/node", "--inspect-brk=127.0.0.1:9229", entry(TestFramework.JEST)), debug.subList(0, 3));
    assertTrue(debug.contains("--runInBand"));
  }

  @Test
  void mocha() {
    assumeFalse(NodeLocator.isWindows());
    TestCommandLine commandLine = commandLine(TestFramework.MOCHA).filters(List.of("src/a.test.ts", "src/deep"))
        .selectors(ONE_TEST);
    assertEquals(List.of("/bin/node", reporter("evitest-mocha-run.cjs"), entry(TestFramework.MOCHA), "--reporter",
        reporter("evitest-mocha.cjs"), "--recursive", "--grep", "^math(?: | > )adds$"), commandLine.build());
    // The files replace the spec of the configuration: the launcher of EVitest gives them to Mocha.
    Map<String, String> environment = commandLine.environment();
    assertEquals(List.of("src/a.test.ts", "src/deep"), Json.parse(environment.get("EVITEST_SPEC")));
    assertEquals("^math(?: | > )adds$", environment.get("EVITEST_PATTERN"));
    // All the tests: no spec, no --recursive.
    assertEquals(List.of("/bin/node", reporter("evitest-mocha-run.cjs"), entry(TestFramework.MOCHA), "--reporter",
        reporter("evitest-mocha.cjs")), commandLine(TestFramework.MOCHA).build());
    assertTrue(commandLine(TestFramework.MOCHA).environment().isEmpty());
    assertEquals(List.of("/bin/node", "--inspect-brk=127.0.0.1:9230", reporter("evitest-mocha-run.cjs")),
        commandLine(TestFramework.MOCHA).inspector(9230).build().subList(0, 3));
  }

  @Test
  void jasmine() {
    assumeFalse(NodeLocator.isWindows());
    assertEquals(List.of("/bin/node", entry(TestFramework.JASMINE), "src/a.test.ts", "src/deep/**/*[sS]pec.?(m|c)js",
        "--helper=" + reporter("evitest-jasmine.cjs"), "--filter=^math(?: | > )adds$", "--random=false"),
        commandLine(TestFramework.JASMINE).filters(List.of("src/a.test.ts", "src/deep/")).selectors(ONE_TEST)
            .arguments(List.of("--random=false")).build());
  }

  @Test
  void playwright() {
    assumeFalse(NodeLocator.isWindows());
    TestCommandLine commandLine = commandLine(TestFramework.PLAYWRIGHT).filters(List.of("tests/a.spec.ts"))
        .selectors(ONE_TEST).updateSnapshots(true).arguments(List.of("--project=chromium"));
    // The title of the tests starts with the project and the file: the pattern is not anchored at the start.
    assertEquals(List.of("/bin/node", entry(TestFramework.PLAYWRIGHT), "test", "tests\\/a\\.spec\\.ts",
        "--reporter=list," + reporter("evitest-playwright.cjs"), "--grep=(?:^| )math(?: | > )adds$",
        "--update-snapshots", "--project=chromium"), commandLine.build());
    assertTrue(Pattern.compile(commandLine.testNamePattern()).matcher(" tests/a.spec.ts math adds").find());
    assertFalse(Pattern.compile(commandLine.testNamePattern()).matcher(" tests/a.spec.ts math adds more").find());
  }

  @Test
  void nodeTest() {
    assumeFalse(NodeLocator.isWindows());
    assertEquals(List.of("/bin/node", "--test", "--test-reporter=spec", "--test-reporter-destination=stdout",
        "--test-reporter=" + reporter("evitest-node.cjs"), "--test-reporter-destination=stdout",
        "--test-name-pattern=^math(?: | > )adds$", "--test-update-snapshots", "--test-concurrency=1", "src/a.test.ts"),
        commandLine(TestFramework.NODE).filters(List.of("src/a.test.ts")).selectors(ONE_TEST).updateSnapshots(true)
            .arguments(List.of("--test-concurrency=1")).build());
  }

  @Test
  void nodeTestFindsTheTestFilesOfAFolderAsNode() {
    List<String> globs = commandLine(TestFramework.NODE).filters(List.of("src/deep")).build();
    assertTrue(globs.contains("src/deep/**/*.test.{cjs,mjs,js,cts,mts,ts}"), globs.toString());
    assertTrue(globs.contains("src/deep/**/test/**/*.{cjs,mjs,js,cts,mts,ts}"), globs.toString());
    // TypeScript without a flag since Node.js 22.18.
    List<String> old = new TestCommandLine(installation(TestFramework.NODE, null)).nodeVersion("22.10.0")
        .root(root.toFile()).filters(List.of("src/deep")).build();
    assertTrue(old.contains("src/deep/**/*_test.{cjs,mjs,js}"), old.toString());
    assertFalse(old.stream().anyMatch(argument -> argument.contains("ts}")), old.toString());
  }

  @Test
  void nodeTestIsDebuggedInTheProcessOfTheDebugger() {
    List<String> node22 = commandLine(TestFramework.NODE).inspector(9229).build();
    assertEquals(List.of("/bin/node", "--inspect-brk=127.0.0.1:9229", "--test", "--experimental-test-isolation=none"),
        node22.subList(0, 4));
    List<String> node24 = new TestCommandLine(installation(TestFramework.NODE, null)).nodeVersion("24.1.0")
        .inspector(9229).build();
    assertTrue(node24.contains("--test-isolation=none"), node24.toString());
  }

  @Test
  void bun() {
    assumeFalse(NodeLocator.isWindows());
    File junit = new File("/tmp/junit.xml");
    assertEquals(List.of("/bin/bun", "test", "./src/a.test.ts", "/abs/b.test.ts", "--reporter=junit",
        "--reporter-outfile=" + junit.getAbsolutePath(), "--test-name-pattern=(?:^| )math(?: | > )adds$",
        "--update-snapshots", "--bail"),
        commandLine(TestFramework.BUN).filters(List.of("src/a.test.ts", "/abs/b.test.ts")).junitReport(junit)
            .selectors(ONE_TEST).updateSnapshots(true).arguments(List.of("--bail")).build());
  }

  @Test
  void deno() {
    assumeFalse(NodeLocator.isWindows());
    File junit = new File("/tmp/junit.xml");
    // Deno filters the tests by their first name: the steps run with their test.
    List<TestSelector> selectors = List.of(TestSelector.test(List.of("math", "adds", "one")),
        TestSelector.suite(List.of("math", "subtracts")), new TestSelector(false, List.of("doubles ${}"), true));
    TestCommandLine commandLine = commandLine(TestFramework.DENO).filters(List.of("src/a_test.ts")).junitReport(junit)
        .selectors(selectors).updateSnapshots(true);
    assertEquals(List.of("/bin/deno", "test", "--allow-all", "--reporter=dot",
        "--junit-path=" + junit.getAbsolutePath(), "--filter=/(?:^math$)|(?:^doubles .*?$)/", "src/a_test.ts", "--",
        "--update"), commandLine.build());
    // The permissions of the user replace --allow-all.
    List<String> permissions = commandLine(TestFramework.DENO).arguments(List.of("--allow-read")).build();
    assertFalse(permissions.contains("--allow-all"), permissions.toString());
    assertTrue(permissions.contains("--allow-read"), permissions.toString());
    List<String> none = commandLine(TestFramework.DENO).arguments(List.of("--no-allow-all")).build();
    assertFalse(none.contains("--allow-all") || none.contains("--no-allow-all"), none.toString());
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void aNamePatternOfTheUserIsUsedWithoutSelectors(TestFramework framework) {
    TestCommandLine commandLine = commandLine(framework).namePattern("add.*");
    assertEquals("add.*", commandLine.testNamePattern());
    assertEquals("add.*", commandLine.environment().get("EVITEST_PATTERN"));
    assertNull(commandLine(framework).namePattern("  ").testNamePattern());
    // The selectors win.
    assertTrue(commandLine(framework).namePattern("add.*").selectors(ONE_TEST).testNamePattern().contains("math"));
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsWithTheReporterOfEVitest(TestFramework framework) {
    List<String> command = commandLine(framework).junitReport(new File("/tmp/junit.xml")).build();
    String joined = String.join(" ", command);
    if (framework.isLive()) {
      assertTrue(joined.contains(REPORTERS.getAbsolutePath().replace('\\', '/') + "/evitest-"), joined);
    } else {
      assertTrue(joined.contains("junit"), joined);
    }
  }

  @Test
  void vitest1MatchesTheNameOfTheFile() {
    TestCommandLine commandLine = new TestCommandLine(installation(TestFramework.VITEST, "1.6.0")).selectors(ONE_TEST);
    assertEquals("(?:^| )math(?: | > )adds$", commandLine.testNamePattern());
  }

  @Test
  void aPackageNeedsItsEntry() {
    assertThrows(IllegalStateException.class,
        () -> new TestCommandLine(new Installation(TestFramework.JEST, null, null)).build());
  }

  @Test
  void theFiltersAreNormalized() {
    List<String> command = commandLine(TestFramework.JASMINE).filters(List.of("spec\\a.js", ".", "", "spec\\a.js"))
        .build();
    assertEquals(1, command.stream().filter(argument -> argument.equals("spec/a.js")).count(), command.toString());
    assertFalse(command.contains("."), command.toString());
  }
}
