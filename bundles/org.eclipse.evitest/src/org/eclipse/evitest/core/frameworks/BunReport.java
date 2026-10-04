package org.eclipse.evitest.core.frameworks;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.evitest.core.TestFramework;
import org.w3c.dom.Element;

/**
 * The results of Bun: its JUnit report ({@code --reporter=junit}) gives the files, the suites ({@code describe}) as
 * nested test suites, the tests, their results and their lines.
 * <p>
 * The report has no message for the failed tests, nor the files which cannot be loaded: they are read in the output of
 * Bun, as a complement. The output is not an API: if its format changes, the results stay right and the errors say to
 * see the console.
 */
final class BunReport extends JUnitReport {

  /** The result of a test: {@code (fail) name [1ms]}, or {@code ✗ name [1ms]} with colors. */
  private static final Pattern RESULT = Pattern.compile(
      "^(?:\\((pass|fail|skip|todo)\\)|([✓✗»✎]))\\s*(.*?)(?: \\[[\\d.]+m?s\\])?$");
  private static final Pattern FILE = Pattern.compile("^(\\S.*\\.[cm]?[jt]sx?):$");
  private static final String UNHANDLED = "# Unhandled error between tests";
  /** The name of the test standing for a failed hook (beforeAll...). */
  private static final String HOOK = "(unnamed)";
  private static final String SEE_THE_CONSOLE = "The test failed: see the console for its error.";

  BunReport(File root, String pattern) {
    super(TestFramework.BUN, root, pattern);
  }

  @Override
  protected void read(Element testsuites, String console) {
    Output output = Output.parse(console);
    Set<String> files = new LinkedHashSet<>();
    for (Element suite : children(testsuites, "testsuite")) {
      String file = suite.getAttribute("file").isEmpty() ? suite.getAttribute("name") : suite.getAttribute("file");
      files.add(file);
      String moduleId = module(file);
      readChildren(suite, file, moduleId, List.of(), output);
      List<String> unhandled = output.unhandled.get(file);
      if (unhandled != null && !unhandled.isEmpty()) {
        suiteError(moduleId, errors(unhandled), null);
      }
    }
    // The files which could not be loaded are only in the output.
    for (Map.Entry<String, List<String>> entry : output.unhandled.entrySet()) {
      if (!files.contains(entry.getKey())) {
        suiteError(module(entry.getKey()), errors(entry.getValue()), null);
      }
    }
  }

  /** Bun writes no report when no test file could be loaded: the errors of the files are in the output. */
  @Override
  protected boolean readWithoutReport(String console) {
    Output output = Output.parse(console);
    for (Map.Entry<String, List<String>> entry : output.unhandled.entrySet()) {
      suiteError(module(entry.getKey()), errors(entry.getValue()), null);
    }
    return !output.unhandled.isEmpty();
  }

  private void readChildren(Element parent, String file, String parentId, List<String> names, Output output) {
    for (Element child : children(parent, null)) {
      if (child.getTagName().equals("testsuite")) {
        List<String> suiteNames = append(names, child.getAttribute("name"));
        String id = node(parentId, file, "suite", suiteNames, integer(child.getAttribute("line")), null, null);
        readChildren(child, file, id, suiteNames, output);
      } else if (child.getTagName().equals("testcase")) {
        if (names.isEmpty() && !child.getAttribute("classname").isBlank()) {
          // Bun 1.2 has no nested suites: the names of the suites are in the class name.
          List<String> suiteNames = classnameSuites(child.getAttribute("classname"));
          String suiteId = parentId;
          for (int i = 1; i <= suiteNames.size(); i++) {
            suiteId = node(suiteId, file, "suite", suiteNames.subList(0, i), null, null, null);
          }
          readTestCase(child, file, suiteId, suiteNames, output);
        } else {
          readTestCase(child, file, parentId, names, output);
        }
      }
    }
  }

  /** A test of the suite with these names, or the failed hook of the suite. */
  private void readTestCase(Element testcase, String file, String parentId, List<String> names, Output output) {
    List<String> testNames = append(names, testcase.getAttribute("name"));
    String fullName = String.join(" > ", testNames);
    Element failure = first(testcase, "failure");
    Element skipped = first(testcase, "skipped");
    if (HOOK.equals(testcase.getAttribute("name"))) {
      if (failure != null) {
        suiteError(parentId, List.of(failureError(output.take(fullName), failure)), null);
      }
      return;
    }
    String mode = skipped == null || !isSelected(String.join(" ", testNames)) ? null
        : "TODO".equalsIgnoreCase(skipped.getAttribute("message")) ? "todo" : "skip";
    String id = node(parentId, file, "test", testNames, integer(testcase.getAttribute("line")), null, mode);
    Long duration = seconds(testcase.getAttribute("time"));
    if (failure != null) {
      testEnd(id, "failed", duration, List.of(failureError(output.take(fullName), failure)));
    } else {
      testEnd(id, skipped != null ? "skipped" : "passed", duration, null);
    }
  }

  /**
   * The names of the suites of a class name of Bun 1.2: from the innermost one ({@code "adds &gt; math"}, escaped
   * twice), the outermost one first.
   */
  static List<String> classnameSuites(String classname) {
    if (classname == null || classname.isBlank()) {
      return List.of();
    }
    String unescaped = classname.replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"").replace("&amp;", "&");
    List<String> suites = new ArrayList<>(List.of(unescaped.split(" > ", -1)));
    java.util.Collections.reverse(suites);
    return suites;
  }

  /** The error of a failed test: the one of the output, else the one of the report, else see the console. */
  private static Map<String, Object> failureError(String block, Element failure) {
    if (block != null && !block.isBlank()) {
      return error(block, (String) null);
    }
    String message = failure.getAttribute("message");
    String text = failure.getTextContent() == null ? "" : failure.getTextContent().strip();
    String type = failure.getAttribute("type");
    // Without a message, the type of the report (AssertionError for all the failures) says nothing.
    return error(!text.isEmpty() ? text : !message.isEmpty() ? message : SEE_THE_CONSOLE,
        text.isEmpty() && message.isEmpty() ? null : type);
  }

  private static List<Map<String, Object>> errors(List<String> blocks) {
    List<Map<String, Object>> errors = new ArrayList<>();
    for (String block : blocks) {
      errors.add(error(block, (String) null));
    }
    return errors;
  }

  /**
   * The errors in the output of Bun: the lines before each {@code (fail) name} since the previous result, and the
   * {@code # Unhandled error between tests} blocks, by file (the {@code file.test.ts:} headers).
   */
  static final class Output {
    final Map<String, Deque<String>> failures = new HashMap<>();
    final Map<String, List<String>> unhandled = new LinkedHashMap<>();

    static Output parse(String console) {
      Output output = new Output();
      String file = null;
      List<String> block = new ArrayList<>();
      List<String> unhandledBlock = null;
      int dashes = 0;
      for (String line : console.split("\\R", -1)) {
        if (unhandledBlock != null) {
          if (line.matches("^-{5,}$")) {
            dashes++;
            if (dashes == 2) {
              output.unhandled.computeIfAbsent(file == null ? "" : file, key -> new ArrayList<>())
                  .add(String.join("\n", unhandledBlock).strip());
              unhandledBlock = null;
            }
          } else {
            unhandledBlock.add(line);
          }
          continue;
        }
        if (line.startsWith(UNHANDLED)) {
          unhandledBlock = new ArrayList<>();
          dashes = 0;
          block.clear();
          continue;
        }
        Matcher fileMatcher = FILE.matcher(line);
        if (fileMatcher.matches()) {
          file = fileMatcher.group(1);
          block.clear();
          continue;
        }
        Matcher result = RESULT.matcher(line);
        if (result.matches()) {
          if ("fail".equals(result.group(1)) || "✗".equals(result.group(2))) {
            output.failures.computeIfAbsent(result.group(3), key -> new ArrayDeque<>())
                .add(String.join("\n", block).strip());
          }
          block.clear();
          continue;
        }
        block.add(line);
      }
      return output;
    }

    /** The error of the next failed test with this full name, null if there is none. */
    String take(String fullName) {
      Deque<String> blocks = failures.get(fullName);
      return blocks == null ? null : blocks.poll();
    }
  }
}
