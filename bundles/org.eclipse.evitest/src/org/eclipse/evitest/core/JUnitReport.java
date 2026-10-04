package org.eclipse.evitest.core;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Turns the JUnit report of Bun or Deno, and the output of the run, into the events of the reporters of EVitest (lines
 * of JSON): the frameworks without a reporter API are shown in the Unit Test view when they end.
 * <ul>
 * <li>Bun: the report has the suites ({@code describe}) as nested test suites, but not the errors of the failed tests,
 * nor the files which cannot be loaded: they are read in the output;</li>
 * <li>Deno: the steps ({@code t.step}) are test cases named {@code "test > step"}: a test with steps is a suite.</li>
 * </ul>
 */
public final class JUnitReport {

  private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]");
  private static final Pattern ERROR_LINE = Pattern.compile("^(?:Uncaught )?(\\w*(?:Error|Exception)\\w*)(?: \\[\\w+\\])?: ?(.*)$");
  private static final Pattern BUN_ERROR_LINE = Pattern.compile("^error: ?(.*)$");
  /** The result of a test: {@code (fail) name [1ms]}, or {@code ✗ name [1ms]} with colors. */
  private static final Pattern BUN_RESULT = Pattern.compile(
      "^(?:\\((pass|fail|skip|todo)\\)|([\u2713\u2717\u00bb\u270e]))\\s*(.*?)(?: \\[[\\d.]+m?s\\])?$");
  private static final Pattern BUN_FILE = Pattern.compile("^(\\S.*\\.[cm]?[jt]sx?):$");
  private static final Pattern DENO_STEPS_FAILED = Pattern.compile("^\\d+ test steps? failed\\.?$");
  private static final String BUN_HOOK = "(unnamed)";

  private final TestFramework framework;
  private final File root;
  private final Pattern selected;
  private final List<String> events = new ArrayList<>();
  private final Set<String> sentNodes = new HashSet<>();

  private JUnitReport(TestFramework framework, File root, String pattern) {
    this.framework = framework;
    this.root = root;
    Pattern compiled = null;
    if (pattern != null && !pattern.isBlank()) {
      try {
        compiled = Pattern.compile(pattern);
      } catch (RuntimeException e) {
        // Not a regular expression of Java: all the skipped tests are skipped by the user.
      }
    }
    this.selected = compiled;
  }

  /**
   * The events of a run.
   *
   * @param framework {@link TestFramework#BUN} or {@link TestFramework#DENO}
   * @param version the version of the framework, null if it is not known
   * @param root the folder of the run
   * @param xml the JUnit report, null if there is none (the run failed before the tests)
   * @param output the output of the run (its errors when there is no report, the errors of the tests of Bun)
   * @param pattern the regular expression selecting the tests of the run (see
   *          {@link TestCommandLine#testNamePattern()}), null if all the tests ran: the tests it excludes are reported
   *          as skipped, but they are not skipped by the user
   */
  public static List<String> toEvents(TestFramework framework, String version, File root, String xml, String output,
      String pattern) {
    JUnitReport report = new JUnitReport(framework, root, pattern);
    String console = output == null ? "" : stripAnsi(output);
    Map<String, Object> hello = new LinkedHashMap<>();
    hello.put("type", "hello");
    hello.put("protocol", Integer.valueOf(2));
    hello.put("framework", framework.label());
    hello.put("version", version);
    hello.put("root", root.getAbsolutePath().replace('\\', '/'));
    report.send(hello);
    report.send(Map.of("type", "runStart"));
    Document document = xml == null || xml.isBlank() ? null : parse(xml);
    Long duration = null;
    List<Map<String, Object>> runErrors = new ArrayList<>();
    BunOutput bunOutput = framework == TestFramework.BUN ? BunOutput.parse(console) : null;
    if (document == null && bunOutput != null && !bunOutput.unhandled.isEmpty()) {
      // Bun writes no report when no test file could be loaded: the errors of the files are in the output.
      for (Map.Entry<String, List<String>> entry : bunOutput.unhandled.entrySet()) {
        report.suiteError(report.module(entry.getKey()), unhandledErrors(entry.getValue()), null);
      }
    } else if (document == null) {
      String text = console.strip();
      runErrors.add(error(text.isEmpty() ? framework.label() + " ended without a report of the tests." : text, null));
    } else {
      Element testsuites = document.getDocumentElement();
      duration = seconds(testsuites.getAttribute("time"));
      if (framework == TestFramework.BUN) {
        report.bun(testsuites, bunOutput);
      } else {
        report.deno(testsuites);
      }
    }
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("type", "runEnd");
    end.put("duration", duration);
    end.put("errors", runErrors.isEmpty() ? null : runErrors);
    report.send(end);
    return report.events;
  }

  private static Document parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      return builder.parse(new InputSource(new StringReader(xml)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Bun
  // ---------------------------------------------------------------------------------------------------------------

  private void bun(Element testsuites, BunOutput output) {
    Set<String> files = new LinkedHashSet<>();
    for (Element suite : children(testsuites, "testsuite")) {
      String file = suite.getAttribute("file").isEmpty() ? suite.getAttribute("name") : suite.getAttribute("file");
      files.add(file);
      String moduleId = module(file);
      bunChildren(suite, file, moduleId, List.of(), output);
      List<String> unhandled = output.unhandled.get(file);
      if (unhandled != null && !unhandled.isEmpty()) {
        suiteError(moduleId, unhandledErrors(unhandled), null);
      }
    }
    // The files which could not be loaded are only in the output.
    for (Map.Entry<String, List<String>> entry : output.unhandled.entrySet()) {
      if (!files.contains(entry.getKey())) {
        suiteError(module(entry.getKey()), unhandledErrors(entry.getValue()), null);
      }
    }
  }

  private void bunChildren(Element parent, String file, String parentId, List<String> names, BunOutput output) {
    for (Element child : children(parent, null)) {
      if (child.getTagName().equals("testsuite")) {
        List<String> suiteNames = append(names, child.getAttribute("name"));
        String id = node(parentId, file, "suite", suiteNames, integer(child.getAttribute("line")), null, null);
        bunChildren(child, file, id, suiteNames, output);
      } else if (child.getTagName().equals("testcase")) {
        List<String> testNames = append(names, child.getAttribute("name"));
        String fullName = String.join(" > ", testNames);
        Element failure = first(child, "failure");
        Element skipped = first(child, "skipped");
        if (BUN_HOOK.equals(child.getAttribute("name"))) {
          // A failed hook (beforeAll...) is reported as a test without a name.
          if (failure != null) {
            suiteError(parentId, List.of(bunError(output.take(fullName), failure)), null);
          }
          continue;
        }
        String mode = skipped == null || !isSelected(testNames) ? null
            : "TODO".equalsIgnoreCase(skipped.getAttribute("message")) ? "todo" : "skip";
        String id = node(parentId, file, "test", testNames, integer(child.getAttribute("line")), null, mode);
        Long duration = seconds(child.getAttribute("time"));
        if (failure != null) {
          testEnd(id, "failed", duration, List.of(bunError(output.take(fullName), failure)));
        } else {
          testEnd(id, skipped != null ? "skipped" : "passed", duration, null);
        }
      }
    }
  }

  private static Map<String, Object> bunError(String block, Element failure) {
    if (block == null || block.isBlank()) {
      String message = failure.getAttribute("message");
      String text = failure.getTextContent() == null ? "" : failure.getTextContent().strip();
      return error(!text.isEmpty() ? text : !message.isEmpty() ? message : "The test failed.",
          failure.getAttribute("type"));
    }
    return error(block, null);
  }

  private static List<Map<String, Object>> unhandledErrors(List<String> blocks) {
    List<Map<String, Object>> errors = new ArrayList<>();
    for (String block : blocks) {
      errors.add(error(block, null));
    }
    return errors;
  }

  /**
   * The errors in the output of Bun: the lines before each {@code (fail) name} since the previous result, and the
   * {@code # Unhandled error between tests} blocks, by file (the {@code file.test.ts:} headers).
   */
  static final class BunOutput {
    final Map<String, Deque<String>> failures = new HashMap<>();
    final Map<String, List<String>> unhandled = new LinkedHashMap<>();

    static BunOutput parse(String console) {
      BunOutput output = new BunOutput();
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
        if (line.startsWith("# Unhandled error between tests")) {
          unhandledBlock = new ArrayList<>();
          dashes = 0;
          block.clear();
          continue;
        }
        Matcher fileMatcher = BUN_FILE.matcher(line);
        if (fileMatcher.matches()) {
          file = fileMatcher.group(1);
          block.clear();
          continue;
        }
        Matcher result = BUN_RESULT.matcher(line);
        if (result.matches()) {
          if ("fail".equals(result.group(1)) || "\u2717".equals(result.group(2))) {
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

  // ---------------------------------------------------------------------------------------------------------------
  // Deno
  // ---------------------------------------------------------------------------------------------------------------

  private void deno(Element testsuites) {
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
          suites.add(String.join(" > ", names.subList(0, i)));
        }
      }
      Map<String, String> ids = new HashMap<>();
      for (Element testcase : testcases) {
        denoNode(testcase.getAttribute("name"), testcase, file, moduleId, byName, suites, ids);
      }
    }
  }

  private String denoNode(String name, Element testcase, String file, String moduleId, Map<String, Element> byName,
      Set<String> suites, Map<String, String> ids) {
    String existing = ids.get(name);
    if (existing != null && (testcase == null || suites.contains(name))) {
      return existing;
    }
    List<String> names = splitNames(name);
    String parentId = moduleId;
    if (names.size() > 1) {
      String parentName = String.join(" > ", names.subList(0, names.size() - 1));
      parentId = denoNode(parentName, byName.get(parentName), file, moduleId, byName, suites, ids);
    }
    Integer line = testcase == null ? null : integer(testcase.getAttribute("line"));
    Integer column = testcase == null ? null : integer(testcase.getAttribute("col"));
    Element failure = testcase == null ? null : first(testcase, "failure");
    Element skipped = testcase == null ? null : first(testcase, "skipped");
    Long duration = testcase == null ? null : seconds(testcase.getAttribute("time"));
    if (suites.contains(name)) {
      String id = node(parentId, file, "suite", names, line, column, null);
      ids.put(name, id);
      if (failure != null && !DENO_STEPS_FAILED.matcher(failure.getAttribute("message").strip()).matches()) {
        suiteError(id, List.of(denoError(failure)), duration);
      }
      return id;
    }
    String id = node(parentId, file, "test", names, line, column, skipped != null && isSelected(names) ? "skip" : null);
    ids.put(name, id);
    if (failure != null) {
      testEnd(id, "failed", duration, List.of(denoError(failure)));
    } else {
      testEnd(id, skipped != null ? "skipped" : "passed", duration, null);
    }
    return id;
  }

  private static Map<String, Object> denoError(Element failure) {
    String text = failure.getTextContent() == null ? "" : failure.getTextContent().strip();
    String message = failure.getAttribute("message").replaceFirst("^Uncaught ", "");
    return error(text.isEmpty() ? message : text, null);
  }

  /**
   * True if the test is selected by the pattern of the run: Deno filters the tests by their first name (its steps run
   * with their test), Bun by their full name.
   */
  private boolean isSelected(List<String> names) {
    if (selected == null) {
      return true;
    }
    String name = framework == TestFramework.DENO ? names.get(0) : String.join(" ", names);
    return selected.matcher(name).find();
  }

  private static List<String> splitNames(String name) {
    return List.of(name.split(" > ", -1));
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------------------------------------------

  private String module(String file) {
    File location = new File(file);
    if (!location.isAbsolute()) {
      location = new File(root, file);
    }
    String path = location.toPath().normalize().toString().replace('\\', '/');
    String id = path;
    if (sentNodes.add(id)) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("type", "module");
      event.put("id", id);
      event.put("file", path);
      String name = root.toPath().toAbsolutePath().normalize().relativize(location.toPath().toAbsolutePath().normalize())
          .toString().replace('\\', '/');
      event.put("name", name);
      event.put("children", List.of());
      send(event);
    }
    return id;
  }

  private String node(String parentId, String file, String kind, List<String> names, Integer line, Integer column,
      String mode) {
    String moduleId = module(file);
    String base = moduleId + "\u0000" + (kind.equals("suite") ? "s" : "t") + "\u0000" + String.join("\u0000", names);
    String id = base;
    for (int index = 2; kind.equals("test") && sentNodes.contains(id); index++) {
      // Two tests with the same names.
      id = base + "\u0000" + index;
    }
    if (sentNodes.add(id)) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", id);
      node.put("kind", kind);
      node.put("name", names.get(names.size() - 1));
      node.put("names", names);
      node.put("mode", mode);
      node.put("line", line);
      node.put("column", column);
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("type", "node");
      event.put("parent", parentId);
      event.put("node", node);
      send(event);
    }
    return id;
  }

  private void testEnd(String id, String state, Long duration, List<Map<String, Object>> errors) {
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("type", "testStart");
    start.put("id", id);
    send(start);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "testEnd");
    event.put("id", id);
    event.put("state", state);
    event.put("duration", duration);
    event.put("errors", errors);
    send(event);
  }

  private void suiteError(String id, List<Map<String, Object>> errors, Long duration) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "suiteError");
    event.put("id", id);
    event.put("errors", errors);
    event.put("duration", duration);
    send(event);
  }

  private void send(Map<String, ?> event) {
    events.add(Json.write(event));
  }

  /**
   * An error read in a text: its first line naming an error ({@code TypeError: boom}, {@code error: message}) is its
   * message, the text from this line is its trace. The {@code Expected:} and {@code Received:} lines are the values
   * compared.
   */
  static Map<String, Object> error(String text, String type) {
    String[] lines = text.split("\\R");
    String name = type == null || type.isBlank() ? "Error" : type;
    String message = lines.length == 0 ? "" : lines[0].strip();
    int start = 0;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      Matcher matcher = ERROR_LINE.matcher(line);
      Matcher bun = BUN_ERROR_LINE.matcher(line);
      if (matcher.matches()) {
        name = matcher.group(1);
        message = matcher.group(2);
        start = i;
        break;
      }
      if (bun.matches()) {
        message = bun.group(1);
        Matcher inner = ERROR_LINE.matcher(message);
        if (inner.matches()) {
          // error: SyntaxError: ...
          name = inner.group(1);
          message = inner.group(2);
        }
        start = i;
        break;
      }
    }
    StringBuilder trace = new StringBuilder(name + ": " + message);
    String expected = null;
    String actual = null;
    for (int i = start + 1; i < lines.length; i++) {
      trace.append('\n').append(lines[i]);
      String line = lines[i].strip();
      if (line.startsWith("Expected: ") && expected == null) {
        expected = line.substring("Expected: ".length());
      } else if (line.startsWith("Received: ") && actual == null) {
        actual = line.substring("Received: ".length());
      }
    }
    if (start > 0) {
      // The code frame before the error.
      trace.append("\n\n");
      for (int i = 0; i < start; i++) {
        trace.append(lines[i]).append('\n');
      }
    }
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("name", name);
    error.put("message", message);
    error.put("trace", trace.toString().strip());
    error.put("expected", expected != null && actual != null ? expected : null);
    error.put("actual", expected != null && actual != null ? actual : null);
    error.put("assertion", Boolean.valueOf(name.startsWith("Assertion") || message.startsWith("expect(")
        || (expected != null && actual != null)));
    return error;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // XML
  // ---------------------------------------------------------------------------------------------------------------

  private static List<Element> children(Element parent, String tag) {
    List<Element> elements = new ArrayList<>();
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && (tag == null || element.getTagName().equals(tag))) {
        elements.add(element);
      }
    }
    return elements;
  }

  private static Element first(Element parent, String tag) {
    List<Element> elements = children(parent, tag);
    return elements.isEmpty() ? null : elements.get(0);
  }

  private static List<String> append(List<String> names, String name) {
    List<String> appended = new ArrayList<>(names);
    appended.add(name);
    return appended;
  }

  private static Integer integer(String text) {
    try {
      return text == null || text.isBlank() ? null : Integer.valueOf(text.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Milliseconds of a time in seconds ({@code "0.0123"}), null if it is not one. */
  private static Long seconds(String text) {
    try {
      return text == null || text.isBlank() ? null : Long.valueOf(Math.round(Double.parseDouble(text) * 1000));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String stripAnsi(String text) {
    return ANSI.matcher(text).replaceAll("");
  }
}
