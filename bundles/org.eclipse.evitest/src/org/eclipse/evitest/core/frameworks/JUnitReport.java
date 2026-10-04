package org.eclipse.evitest.core.frameworks;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestFramework;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Turns the JUnit report of a framework without a reporter API (Bun, Deno), and the output of its run, into the events
 * of the reporters of EVitest (lines of JSON): the tests are shown in the Unit Test view when they end.
 * <p>
 * The JUnit report is the documented output of these frameworks: the tests, their suites, their results and their
 * locations are read in it. A subclass per framework reads what is specific to it.
 */
public abstract class JUnitReport {

  private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]");
  private static final Pattern ERROR_LINE = Pattern.compile(
      "^(?:Uncaught )?(\\w*(?:Error|Exception)\\w*)(?: \\[\\w+\\])?: ?(.*)$");
  private static final Pattern LOWER_CASE_ERROR_LINE = Pattern.compile("^error: ?(.*)$");
  /** An escape sequence of the console written as a reference: {@code &#27;[31m}. */
  private static final Pattern ESCAPED_ANSI = Pattern.compile("&#(?:27|x0*1[bB]);\\[[0-9;?]*[ -/]*[@-~]");
  /** The characters not allowed in XML 1.0 (all the control characters but the tab and the line breaks). */
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
  private static final Pattern CONTROL_REFERENCES = Pattern.compile(
      "&#(?:0*(?:[0-8]|1[124-9]|2[0-9]|3[01])|x0*(?:[0-8bBcCeEfF]|1[0-9a-fA-F]));");

  private final TestFramework framework;
  protected final File root;
  private final Pattern selected;
  private final List<String> events = new ArrayList<>();
  private final Set<String> sentNodes = new HashSet<>();

  protected JUnitReport(TestFramework framework, File root, String pattern) {
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
    JUnitReport report = switch (framework) {
      case BUN -> new BunReport(root, pattern);
      case DENO -> new DenoReport(root, pattern);
      default -> throw new IllegalArgumentException(framework.label() + " has a reporter, not a JUnit report");
    };
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
    if (document == null) {
      if (!report.readWithoutReport(console)) {
        String text = console.strip();
        runErrors.add(error(text.isEmpty() ? framework.label() + " ended without a report of the tests." : text,
            null));
      }
    } else {
      Element testsuites = document.getDocumentElement();
      duration = seconds(testsuites.getAttribute("time"));
      report.read(testsuites, console);
    }
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("type", "runEnd");
    end.put("duration", duration);
    end.put("errors", runErrors.isEmpty() ? null : runErrors);
    report.send(end);
    return report.events;
  }

  /** Reads the report (its testsuites element) and the output of the run (without colors). */
  protected abstract void read(Element testsuites, String console);

  /**
   * Reads the output of a run without a report (the tests could not start), true if it gave the errors of the run;
   * false to show the whole output as the error of the run.
   */
  protected boolean readWithoutReport(String console) {
    return false;
  }

  /** True if the test is selected by the pattern of the run (its full name is the names separated by spaces). */
  protected boolean isSelected(String fullName) {
    return selected == null || selected.matcher(fullName).find();
  }

  /**
   * Parses a report. The reports of some versions (Deno 2.0) have the colors of the console, whose escape characters
   * are not allowed in XML 1.0: they are removed, with the other control characters, raw or as references.
   */
  static Document parse(String xml) {
    String sanitized = ESCAPED_ANSI.matcher(stripAnsi(xml)).replaceAll("");
    sanitized = CONTROL_CHARACTERS.matcher(sanitized).replaceAll("");
    sanitized = CONTROL_REFERENCES.matcher(sanitized).replaceAll("");
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      return builder.parse(new InputSource(new StringReader(sanitized)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------------------------------------------

  protected String module(String file) {
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

  protected String node(String parentId, String file, String kind, List<String> names, Integer line, Integer column,
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

  protected void testEnd(String id, String state, Long duration, List<Map<String, Object>> errors) {
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

  protected void suiteError(String id, List<Map<String, Object>> errors, Long duration) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "suiteError");
    event.put("id", id);
    event.put("errors", errors);
    event.put("duration", duration);
    send(event);
  }

  protected void send(Map<String, ?> event) {
    events.add(Json.write(event));
  }

  /**
   * An error read in a text: its first line naming an error ({@code TypeError: boom}, {@code error: message}) is its
   * message, the text from this line is its trace. The {@code Expected:} and {@code Received:} lines are the values
   * compared.
   */
  protected static Map<String, Object> error(String text, String type) {
    // The reports of some versions have the colors of the console.
    String[] lines = stripAnsi(text).split("\\R");
    String name = type == null || type.isBlank() ? "Error" : type;
    String message = lines.length == 0 ? "" : lines[0].strip();
    int start = 0;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      Matcher matcher = ERROR_LINE.matcher(line);
      Matcher lowerCase = LOWER_CASE_ERROR_LINE.matcher(line);
      if (matcher.matches()) {
        name = matcher.group(1);
        message = matcher.group(2);
        start = i;
        break;
      }
      if (lowerCase.matches()) {
        message = lowerCase.group(1);
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

  protected static List<Element> children(Element parent, String tag) {
    List<Element> elements = new ArrayList<>();
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && (tag == null || element.getTagName().equals(tag))) {
        elements.add(element);
      }
    }
    return elements;
  }

  protected static Element first(Element parent, String tag) {
    List<Element> elements = children(parent, tag);
    return elements.isEmpty() ? null : elements.get(0);
  }

  protected static List<String> append(List<String> names, String name) {
    List<String> appended = new ArrayList<>(names);
    appended.add(name);
    return appended;
  }

  protected static Integer integer(String text) {
    try {
      return text == null || text.isBlank() ? null : Integer.valueOf(text.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Milliseconds of a time in seconds ({@code "0.0123"}), null if it is not one. */
  protected static Long seconds(String text) {
    try {
      return text == null || text.isBlank() ? null : Long.valueOf(Math.round(Double.parseDouble(text) * 1000));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  protected static String stripAnsi(String text) {
    return ANSI.matcher(text).replaceAll("");
  }
}
