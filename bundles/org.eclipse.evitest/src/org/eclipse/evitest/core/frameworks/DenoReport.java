package org.eclipse.evitest.core.frameworks;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.evitest.core.TestFramework;
import org.w3c.dom.Element;

/**
 * The results of Deno: its JUnit report ({@code --junit-path}) has a test suite per file, and a test case per test
 * ({@code Deno.test}) and per step ({@code t.step}), named after its test: {@code test > step}. A test with steps is a
 * suite.
 */
final class DenoReport extends JUnitReport {

  /** The failure of a test because of its steps, which are already failed. */
  private static final Pattern STEPS_FAILED = Pattern.compile("^\\d+ test steps? failed\\.?$");
  private static final String SEPARATOR = " > ";

  DenoReport(File root, String pattern) {
    super(TestFramework.DENO, root, pattern);
  }

  @Override
  protected void read(Element testsuites, String console) {
    for (Element suite : children(testsuites, "testsuite")) {
      String file = suite.getAttribute("name");
      String moduleId = module(file);
      List<Element> testcases = children(suite, "testcase");
      Map<String, Element> byName = new LinkedHashMap<>();
      Set<String> suites = new HashSet<>();
      for (Element testcase : testcases) {
        String name = testcase.getAttribute("name");
        byName.putIfAbsent(name, testcase);
        List<String> names = splitNames(name);
        for (int i = 1; i < names.size(); i++) {
          suites.add(String.join(SEPARATOR, names.subList(0, i)));
        }
      }
      Map<String, String> ids = new HashMap<>();
      for (Element testcase : testcases) {
        readNode(testcase.getAttribute("name"), testcase, file, moduleId, byName, suites, ids);
      }
    }
  }

  private String readNode(String name, Element testcase, String file, String moduleId, Map<String, Element> byName,
      Set<String> suites, Map<String, String> ids) {
    String existing = ids.get(name);
    if (existing != null && (testcase == null || suites.contains(name))) {
      return existing;
    }
    List<String> names = splitNames(name);
    String parentId = moduleId;
    if (names.size() > 1) {
      String parentName = String.join(SEPARATOR, names.subList(0, names.size() - 1));
      parentId = readNode(parentName, byName.get(parentName), file, moduleId, byName, suites, ids);
    }
    Integer line = testcase == null ? null : integer(testcase.getAttribute("line"));
    Integer column = testcase == null ? null : integer(testcase.getAttribute("col"));
    Element failure = testcase == null ? null : first(testcase, "failure");
    Element skipped = testcase == null ? null : first(testcase, "skipped");
    Long duration = testcase == null ? null : seconds(testcase.getAttribute("time"));
    if (suites.contains(name)) {
      String id = node(parentId, file, "suite", names, line, column, null);
      ids.put(name, id);
      if (failure != null && !STEPS_FAILED.matcher(failure.getAttribute("message").strip()).matches()) {
        suiteError(id, List.of(error(failure)), duration);
      }
      return id;
    }
    // Deno filters the tests by their first name: their steps run with them.
    String mode = skipped != null && isSelected(names.get(0)) ? "skip" : null;
    String id = node(parentId, file, "test", names, line, column, mode);
    ids.put(name, id);
    if (failure != null) {
      testEnd(id, "failed", duration, List.of(error(failure)));
    } else {
      testEnd(id, skipped != null ? "skipped" : "passed", duration, null);
    }
    return id;
  }

  private static Map<String, Object> error(Element failure) {
    String text = failure.getTextContent() == null ? "" : failure.getTextContent().strip();
    String message = failure.getAttribute("message").replaceFirst("^Uncaught ", "");
    return error(text.isEmpty() ? message : text, null);
  }

  private static List<String> splitNames(String name) {
    return List.of(name.split(SEPARATOR, -1));
  }
}
