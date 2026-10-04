package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.evitest.core.FrameworkDetector;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.ui.StackFrameLocation;
import org.eclipse.evitest.ui.TestElementData;
import org.eclipse.unittest.model.ITestElement.FailureTrace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Runs the real frameworks on their fixture (the same tests written for each of them) as the launch does, and checks
 * what the Unit Test view receives: the tests, their results, their errors, their locations, the selection of tests.
 * Skipped for the frameworks which are not installed.
 */
class FrameworksEndToEndTest {

  private static String math(TestFramework framework) {
    return Fixtures.mathFile(framework);
  }

  private static List<String> mathAndHooks(TestFramework framework) {
    List<String> files = new ArrayList<>(List.of(math(framework)));
    if (Fixtures.hooksFile(framework) != null) {
      files.add(Fixtures.hooksFile(framework));
    }
    return files;
  }

  /** The tests of .each, by their name in the source: a template of Vitest, Jest and Bun, a template literal else. */
  private static TestSelector doubles(TestFramework framework) {
    String name = switch (framework) {
      case VITEST, JEST, BUN -> "doubles %i";
      default -> "doubles ${}";
    };
    return new TestSelector(false, List.of(name), true);
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void detectsTheFrameworkOfTheFixture(TestFramework framework) {
    File fixture = Fixtures.fixture(framework);
    FrameworkDetector.Detection file = FrameworkDetector.detect(new File(fixture, math(framework)));
    assertNotNull(file);
    assertEquals(framework, file.framework());
    assertEquals(fixture, file.root());
    // node:test: the test script of package.json runs it.
    FrameworkDetector.Detection folder = FrameworkDetector.detect(fixture);
    assertNotNull(folder);
    assertEquals(framework, folder.framework());
    assertEquals(fixture, folder.root());
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsTheTests(TestFramework framework) throws Exception {
    Fixtures.Run run = Fixtures.run(framework, mathAndHooks(framework), List.of());
    List<String> log = run.session().log;
    String file = math(framework);
    assertEquals("started", log.get(0), log.toString());
    assertEquals("completed", log.get(log.size() - 1), run.output() + "\n" + log);
    assertTrue(log.contains("ended " + file + " > math > adds > one and one"), log.toString());
    assertTrue(log.contains("ended " + file + " > math > adds > two and two"), log.toString());
    assertTrue(log.contains("ignored " + file + " > math > skipped"), log.toString());
    String later = framework == TestFramework.DENO ? file + " > later" : file + " > math > later";
    assertTrue(log.contains("ignored " + later), log.toString());
    assertTrue(log.contains("ended " + file + " > doubles 2"), log.toString());
    assertTrue(log.contains("ended " + file + " > doubles 3"), log.toString());
    assertTrue(log.contains("failed " + file + " > throws ERROR"), log.toString());
    // Deno: the fixture compares with a function of its own, which throws an Error.
    String comparison = framework == TestFramework.DENO ? "ERROR" : "FAILURE";
    assertTrue(log.contains("failed " + file + " > math > compares objects " + comparison), log.toString());
    assertFalse(log.stream().anyMatch(line -> line.contains("Unhandled errors")), log.toString());

    FakeSession.Element compares = run.session().element(file + " > math > compares objects");
    FailureTrace trace = compares.getFailureTrace();
    assertNotNull(trace);
    if (framework == TestFramework.VITEST || framework == TestFramework.JEST || framework == TestFramework.MOCHA
        || framework == TestFramework.NODE) {
      assertTrue(trace.isComparisonFailure(), trace.getTrace());
      assertTrue(trace.getExpected().contains("\"b\": 3"), trace.getExpected());
      assertTrue(trace.getActual().contains("\"b\": 2"), trace.getActual());
    }
    // The frame of the assertion opens the test file at its line.
    String frame = trace.getTrace().lines().filter(line -> line.contains(new File(file).getName() + ":"))
        .findFirst().orElseThrow(() -> new AssertionError(trace.getTrace()));
    StackFrameLocation location = StackFrameLocation.parse(frame, Fixtures.fixture(framework).getAbsolutePath());
    assertNotNull(location, frame);
    assertEquals(new File(Fixtures.fixture(framework), file).getCanonicalPath(),
        new File(location.file()).getCanonicalPath());
    assertEquals(assertionLine(framework), location.line(), frame);

    // The location of the declaration, when the framework gives it.
    TestElementData data = TestElementData.parse(run.session().element(file + " > math > adds > one and one").getData());
    assertEquals(List.of("math", "adds", "one and one"), data.names());
    assertEquals(declarationLine(framework), data.line());
    assertEquals(new File(Fixtures.fixture(framework), file).getCanonicalPath(), new File(data.file()).getCanonicalPath());

    String hooks = Fixtures.hooksFile(framework);
    if (hooks != null) {
      // The error of the failed beforeAll is on its suite, or on its test.
      String neverRuns = hooks + " > with a broken hook > never runs";
      assertTrue(log.contains("failed " + hooks + " > with a broken hook ERROR")
          || log.contains("failed " + neverRuns + " ERROR"), log.toString());
      // Not run (skipped), or failed with the error of the hook (Jest, Playwright); Bun does not report it.
      assertTrue(log.contains("ignored " + neverRuns) || log.contains("failed " + neverRuns + " ERROR")
          || (framework == TestFramework.BUN && log.stream().noneMatch(line -> line.endsWith(neverRuns))),
          log.toString());
    }
  }

  /** The line of the expect of "compares objects" in the fixture of the framework (its first frame in the file). */
  private static int assertionLine(TestFramework framework) {
    return switch (framework) {
      case VITEST, MOCHA, BUN, PLAYWRIGHT -> 18;
      case JEST, JASMINE -> 16;
      case NODE -> 19;
      // The comparison function of the fixture.
      case DENO -> 3;
    };
  }

  /** The line of the declaration of "one and one", null if the framework does not give it. */
  private static Integer declarationLine(TestFramework framework) {
    return switch (framework) {
      case VITEST, BUN, PLAYWRIGHT -> Integer.valueOf(5);
      case NODE -> Integer.valueOf(6);
      case DENO -> Integer.valueOf(9);
      // Jest gives it with the result, after the test is added; Mocha and Jasmine do not give it.
      case JEST, MOCHA, JASMINE -> null;
    };
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsOneTest(TestFramework framework) throws Exception {
    Fixtures.Run run = Fixtures.run(framework, List.of(math(framework)),
        List.of(TestSelector.test(List.of("math", "adds", "one and one"))));
    List<String> log = run.session().log;
    String file = math(framework);
    assertTrue(log.contains("ended " + file + " > math > adds > one and one"), run.output() + "\n" + log);
    assertFalse(log.contains("ended " + file + " > doubles 2"), log.toString());
    assertFalse(log.stream().anyMatch(line -> line.startsWith("failed ") && line.contains("throws")), log.toString());
    if (framework != TestFramework.DENO) {
      // Deno selects the tests, not their steps: the whole test "math" runs.
      assertFalse(log.contains("ended " + file + " > math > adds > two and two"), log.toString());
    }
    // The other files do not run.
    assertTrue(log.stream().noneMatch(line -> line.contains("hooks") || line.contains("broken")), log.toString());
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsOneSuiteAndTemplates(TestFramework framework) throws Exception {
    Fixtures.Run run = Fixtures.run(framework, List.of(math(framework)),
        List.of(TestSelector.suite(List.of("math", "adds")), doubles(framework)));
    List<String> log = run.session().log;
    String file = math(framework);
    // Deno runs the steps of a selected test: the step "compares objects" of "math" runs.
    List<String> ended = log.stream().filter(line -> line.startsWith("ended "))
        .filter(line -> framework != TestFramework.DENO || !line.endsWith("compares objects")).sorted().toList();
    assertEquals(List.of("ended " + file + " > doubles 2", "ended " + file + " > doubles 3",
        "ended " + file + " > math > adds > one and one", "ended " + file + " > math > adds > two and two"), ended,
        run.output() + "\n" + log);
    if (framework != TestFramework.DENO) {
      assertFalse(log.stream().anyMatch(line -> line.startsWith("failed ")), log.toString());
    }
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void reportsATestFileWhichCannotBeLoaded(TestFramework framework) throws Exception {
    Fixtures.Run run = Fixtures.run(framework, List.of(Fixtures.brokenFile(framework)), List.of());
    List<String> log = run.session().log;
    String broken = Fixtures.brokenFile(framework);
    switch (framework) {
      case VITEST, JEST, NODE, BUN -> {
        // The error is on the file.
        assertTrue(log.contains("failed " + broken + " ERROR"), run.output() + "\n" + log);
        assertTrue(run.session().element(broken).getFailureTrace().getTrace().contains("Error"),
            run.session().element(broken).getFailureTrace().getTrace());
      }
      default -> {
        // The framework ends before running the tests: the error is the one of the run.
        assertTrue(log.contains("failed Unhandled errors ERROR"), run.output() + "\n" + log);
        String trace = run.session().element("Unhandled errors").getFailureTrace().getTrace();
        assertTrue(trace.contains("broken") || trace.contains("SyntaxError") || trace.contains("Unexpected"), trace);
      }
    }
    assertEquals("completed", log.get(log.size() - 1), log.toString());
    assertNull(run.session().elements.values().stream().filter(element -> element.name.equals("is not closed"))
        .findFirst().orElse(null), log.toString());
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void tellsTheFrameworkAndItsVersion(TestFramework framework) throws Exception {
    Fixtures.Run run = Fixtures.run(framework, List.of(math(framework)),
        List.of(TestSelector.test(List.of("math", "adds", "one and one"))));
    assertEquals(framework.label(), run.handler().getFramework());
    assertNotNull(run.handler().getFrameworkVersion(), framework.label());
    assertTrue(run.handler().getFrameworkVersion().matches("\\d+\\.\\d+.*"), run.handler().getFrameworkVersion());
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsWithTheColorsOfTheConsole(TestFramework framework) throws Exception {
    // The console of Eclipse shows the colors: the results and the errors are the same.
    Fixtures.Run run = Fixtures.run(framework, List.of(math(framework)), List.of(), true);
    List<String> log = run.session().log;
    String file = math(framework);
    assertTrue(log.contains("ended " + file + " > math > adds > one and one"), run.output() + "\n" + log);
    assertTrue(log.contains("failed " + file + " > throws ERROR"), run.output() + "\n" + log);
    String trace = run.session().element(file + " > throws").getFailureTrace().getTrace();
    assertTrue(trace.startsWith("TypeError: boom"), trace);
    assertFalse(trace.contains("\u001b["), trace);
  }
}

