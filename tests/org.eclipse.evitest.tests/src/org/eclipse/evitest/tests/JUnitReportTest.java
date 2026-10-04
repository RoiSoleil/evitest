package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.frameworks.JUnitReport;
import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.ui.TestElementData;
import org.eclipse.evitest.ui.VitestEventHandler;
import org.eclipse.unittest.model.ITestElement.FailureTrace;
import org.junit.jupiter.api.Test;

/** The JUnit reports of Bun and Deno, and their output, as the events of a reporter. */
class JUnitReportTest {

  private static final File ROOT = new File("/p");

  /** The report of Bun 1.3 for the fixture: the errors of the failed tests are not in it. */
  private static final String BUN_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuites name="bun test" tests="9" assertions="5" failures="3" skipped="2" time="0.093793843">
        <testsuite name="src/hooks.test.ts" file="src/hooks.test.ts" tests="1" assertions="0" failures="1" skipped="0" time="0" hostname="vm">
          <testsuite name="with a broken hook" file="src/hooks.test.ts" line="3" tests="1" assertions="0" failures="1" skipped="0" time="0" hostname="vm">
            <testcase name="(unnamed)" classname="with a broken hook" time="0.000956" file="src/hooks.test.ts" assertions="0">
              <failure type="AssertionError" />
            </testcase>
          </testsuite>
        </testsuite>
        <testsuite name="src/math.test.ts" file="src/math.test.ts" tests="8" assertions="5" failures="2" skipped="2" time="0" hostname="vm">
          <testsuite name="math" file="src/math.test.ts" line="3" tests="5" assertions="3" failures="1" skipped="2" time="0.001" hostname="vm">
            <testsuite name="adds" file="src/math.test.ts" line="4" tests="2" assertions="2" failures="0" skipped="0" time="0" hostname="vm">
              <testcase name="one and one" classname="adds &amp;gt; math" time="0.000789" file="src/math.test.ts" line="5" assertions="1" />
              <testcase name="two and two" classname="adds &amp;gt; math" time="0.000036" file="src/math.test.ts" line="9" assertions="1" />
            </testsuite>
            <testcase name="skipped" classname="math" time="0" file="src/math.test.ts" line="14" assertions="0">
              <skipped />
            </testcase>
            <testcase name="later" classname="math" time="0" file="src/math.test.ts" line="15" assertions="0">
              <skipped message="TODO" />
            </testcase>
            <testcase name="compares objects" classname="math" time="0.001935" file="src/math.test.ts" line="17" assertions="1">
              <failure type="AssertionError" />
            </testcase>
          </testsuite>
          <testcase name="doubles 2" classname="" time="0.000065" file="src/math.test.ts" line="22" assertions="1" />
          <testcase name="throws" classname="" time="0.000063" file="src/math.test.ts" line="26" assertions="0">
            <failure type="AssertionError" />
          </testcase>
        </testsuite>
      </testsuites>
      """;

  /** The output of Bun for the same run, with colors. */
  private static final String BUN_OUTPUT = """
      bun test v1.3.14 (0d9b296a)

      src/hooks.test.ts:
      4 |   beforeAll(() => {
      5 |     throw new Error('hook failed')
                                           ^
      \u001b[31merror\u001b[0m: hook failed
            at <anonymous> (/p/src/hooks.test.ts:5:34)
      (fail) with a broken hook > (unnamed) [0.13ms]

      src/broken.test.ts:

      # Unhandled error between tests
      -------------------------------
      4 | it('is not closed', () => {
                                     ^
      error: Unexpected end of file
          at /p/src/broken.test.ts:4:28
      -------------------------------


      src/math.test.ts:
      17 |   it('compares objects', () => {
      18 |     expect({ a: 1, b: 2 }).toEqual({ a: 1, b: 3 })
                                      ^
      error: expect(received).toEqual(expected)

      - Expected  - 1
      + Received  + 1

            at <anonymous> (/p/src/math.test.ts:18:28)
      (fail) math > compares objects [0.14ms]
      26 | test('throws', () => {
      27 |   throw new TypeError('boom')
                                       ^
      TypeError: boom
            at <anonymous> (/p/src/math.test.ts:27:29)
      (fail) throws [0.04ms]

       4 pass
       1 fail
      """;

  /** The report of Deno 2.9 for the fixture: the steps are named after their test. */
  private static final String DENO_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuites name="deno test" tests="9" failures="3" errors="0" time="0.008">
          <testsuite name="./src/math_test.ts" tests="9" disabled="2" errors="0" failures="3">
              <testcase name="math" classname="./src/math_test.ts" time="0.003" line="7" col="6">
                  <failure message="1 test step failed">1 test step failed.</failure>
              </testcase>
              <testcase name="doubles 2" classname="./src/math_test.ts" time="0.000" line="26" col="8">
              </testcase>
              <testcase name="later" classname="./src/math_test.ts" time="0.000" line="31" col="11">
                  <skipped/>
              </testcase>
              <testcase name="throws" classname="./src/math_test.ts" time="0.001" line="33" col="6">
                  <failure message="Uncaught TypeError: boom">TypeError: boom
        throw new TypeError(&apos;boom&apos;)
              ^
          at fn (file:///p/src/math_test.ts:34:9)</failure>
              </testcase>
              <testcase name="math &gt; adds" classname="./src/math_test.ts" time="0.001" line="8" col="11">
              </testcase>
              <testcase name="math &gt; adds &gt; one and one" classname="./src/math_test.ts" time="0.000" line="9" col="13">
              </testcase>
              <testcase name="math &gt; skipped" classname="./src/math_test.ts" time="0.000" line="18" col="11">
                  <skipped/>
              </testcase>
              <testcase name="math &gt; compares objects" classname="./src/math_test.ts" time="0.001" line="20" col="11">
                  <failure message="Uncaught AssertionError: Values are not equal.">AssertionError: Values are not equal.

          [Diff] Actual / Expected

            {
              a: 1,
          -   b: 2,
          +   b: 3,
            }

          at assertEquals (file:///p/src/math_test.ts:3:11)
          at file:///p/src/math_test.ts:21:5</failure>
              </testcase>
          </testsuite>
      </testsuites>
      """;

  private static FakeSession handle(List<String> events) {
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    for (String event : events) {
      handler.handle(event);
    }
    assertTrue(handler.isSessionEnded());
    return session;
  }

  @Test
  void bun() {
    List<String> events = JUnitReport.toEvents(TestFramework.BUN, "1.3.14", ROOT, BUN_XML, BUN_OUTPUT, null);
    Map<String, Object> hello = Json.parseObject(events.get(0));
    assertEquals("Bun", hello.get("framework"));
    assertEquals("1.3.14", hello.get("version"));
    FakeSession session = handle(events);
    List<String> log = session.log;
    assertEquals("started", log.get(0));
    assertEquals("completed", log.get(log.size() - 1));
    assertTrue(log.contains("ended src/math.test.ts > math > adds > one and one"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > math > adds > two and two"), log.toString());
    assertTrue(log.contains("ignored src/math.test.ts > math > skipped"), log.toString());
    assertTrue(log.contains("ignored src/math.test.ts > math > later"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > doubles 2"), log.toString());
    assertTrue(log.contains("failed src/math.test.ts > math > compares objects FAILURE"), log.toString());
    assertTrue(log.contains("failed src/math.test.ts > throws ERROR"), log.toString());
    // The hook without a name fails its suite.
    assertTrue(log.contains("failed src/hooks.test.ts > with a broken hook ERROR"), log.toString());
    assertFalse(log.stream().anyMatch(line -> line.contains("(unnamed)")), log.toString());
    // The file which could not be loaded is only in the output.
    assertTrue(log.contains("failed src/broken.test.ts ERROR"), log.toString());

    FailureTrace compares = session.element("src/math.test.ts > math > compares objects").getFailureTrace();
    assertTrue(compares.getTrace().startsWith("Error: expect(received).toEqual(expected)"), compares.getTrace());
    assertTrue(compares.getTrace().contains("at <anonymous> (/p/src/math.test.ts:18:28)"), compares.getTrace());
    // The code frame after the error, without the colors.
    assertTrue(compares.getTrace().contains("18 |     expect({ a: 1, b: 2 })"), compares.getTrace());
    FailureTrace hook = session.element("src/hooks.test.ts > with a broken hook").getFailureTrace();
    assertTrue(hook.getTrace().startsWith("Error: hook failed"), hook.getTrace());
    assertFalse(hook.getTrace().contains("\u001b"), hook.getTrace());
    FailureTrace broken = session.element("src/broken.test.ts").getFailureTrace();
    assertTrue(broken.getTrace().startsWith("Error: Unexpected end of file"), broken.getTrace());

    TestElementData one = TestElementData.parse(session.element("src/math.test.ts > math > adds > one and one").getData());
    assertEquals("/p/src/math.test.ts", one.file().replace('\\', '/').replaceFirst("^[A-Za-z]:", ""));
    assertEquals(Integer.valueOf(5), one.line());
    assertEquals(List.of("math", "adds", "one and one"), one.names());
    TestElementData adds = TestElementData.parse(session.element("src/math.test.ts > math > adds").getData());
    assertEquals(TestElementData.SUITE, adds.kind());
    assertEquals(Integer.valueOf(4), adds.line());
  }

  @Test
  void theModesOfTheSkippedTestsOfBun() {
    List<Map<String, Object>> nodes = JUnitReport.toEvents(TestFramework.BUN, null, ROOT, BUN_XML, BUN_OUTPUT, null)
        .stream().map(Json::parseObject).filter(event -> "node".equals(event.get("type")))
        .map(event -> asMap(event.get("node"))).toList();
    assertEquals("skip", node(nodes, "skipped").get("mode"));
    assertEquals("todo", node(nodes, "later").get("mode"));
    assertNull(node(nodes, "one and one").get("mode"));
    // Excluded by the pattern of the run: not skipped by the user.
    List<Map<String, Object>> filtered = JUnitReport.toEvents(TestFramework.BUN, null, ROOT, BUN_XML, BUN_OUTPUT,
        "^throws$").stream().map(Json::parseObject).filter(event -> "node".equals(event.get("type")))
        .map(event -> asMap(event.get("node"))).toList();
    assertNull(node(filtered, "skipped").get("mode"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> node(List<Map<String, Object>> nodes, String name) {
    return nodes.stream().filter(node -> name.equals(node.get("name"))).findFirst()
        .orElseThrow(() -> new AssertionError(name + " in " + nodes));
  }

  @Test
  void bunOnGitHubActions() {
    // Bun writes workflow commands when it runs on GitHub Actions.
    String output = """
        ::group::src/broken.test.ts:

        # Unhandled error between tests
        -------------------------------
        error: Unexpected end of file
            at /p/src/broken.test.ts:4:28
        -------------------------------
        ::endgroup::
        ::group::src/math.test.ts:
        TypeError: boom
              at <anonymous> (/p/src/math.test.ts:27:29)
        ::error file=src/math.test.ts,line=27,col=29,title=throws::TypeError: boom
        (fail) throws [0.04ms]
        ::endgroup::
        """;
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, BUN_XML, output, null));
    assertTrue(session.log.contains("failed src/broken.test.ts ERROR"), session.log.toString());
    assertFalse(session.log.stream().anyMatch(line -> line.contains("::")), session.log.toString());
    String trace = session.element("src/math.test.ts > throws").getFailureTrace().getTrace();
    assertTrue(trace.startsWith("TypeError: boom"), trace);
    assertFalse(trace.contains("::error"), trace);
    // Without a report.
    FakeSession broken = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, null, output, null));
    assertTrue(broken.log.contains("failed src/broken.test.ts ERROR"), broken.log.toString());
  }

  @Test
  void bunWithoutAReport() {
    // Bun writes no report when no test file can be loaded.
    String output = """
        src/broken.test.ts:

        # Unhandled error between tests
        -------------------------------
        error: Unexpected end of file
            at /p/src/broken.test.ts:4:28
        -------------------------------
        """;
    List<String> log = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, null, output, null)).log;
    assertTrue(log.contains("failed src/broken.test.ts ERROR"), log.toString());
    assertFalse(log.stream().anyMatch(line -> line.contains("Unhandled errors")), log.toString());
  }

  @Test
  void deno() {
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.DENO, "2.9.6", ROOT, DENO_XML, "", null));
    List<String> log = session.log;
    assertTrue(log.contains("suite src/math_test.ts > math (null)"), log.toString());
    assertTrue(log.contains("suite src/math_test.ts > math > adds (null)"), log.toString());
    assertTrue(log.contains("ended src/math_test.ts > math > adds > one and one"), log.toString());
    assertTrue(log.contains("ignored src/math_test.ts > math > skipped"), log.toString());
    assertTrue(log.contains("ignored src/math_test.ts > later"), log.toString());
    assertTrue(log.contains("ended src/math_test.ts > doubles 2"), log.toString());
    assertTrue(log.contains("failed src/math_test.ts > math > compares objects FAILURE"), log.toString());
    assertTrue(log.contains("failed src/math_test.ts > throws ERROR"), log.toString());
    // A test fails because of its steps: they are already failed.
    assertFalse(log.contains("failed src/math_test.ts > math ERROR"), log.toString());
    assertEquals("completed", log.get(log.size() - 1));

    FailureTrace throwsTrace = session.element("src/math_test.ts > throws").getFailureTrace();
    assertTrue(throwsTrace.getTrace().startsWith("TypeError: boom"), throwsTrace.getTrace());
    assertTrue(throwsTrace.getTrace().contains("at fn (file:///p/src/math_test.ts:34:9)"), throwsTrace.getTrace());
    TestElementData one = TestElementData.parse(session.element("src/math_test.ts > math > adds > one and one").getData());
    assertEquals(Integer.valueOf(9), one.line());
    assertEquals(Integer.valueOf(13), one.column());
  }

  @Test
  void denoSkipsTheTestsExcludedByThePatternWithoutAMode() {
    List<Map<String, Object>> nodes = JUnitReport.toEvents(TestFramework.DENO, null, ROOT, DENO_XML, "", "^throws$")
        .stream().map(Json::parseObject).filter(event -> "node".equals(event.get("type")))
        .map(event -> asMap(event.get("node"))).toList();
    // "later" is not selected; "math > skipped" neither: the pattern is on the first names.
    assertNull(node(nodes, "later").get("mode"));
    assertNull(node(nodes, "skipped").get("mode"));
    List<Map<String, Object>> math = JUnitReport.toEvents(TestFramework.DENO, null, ROOT, DENO_XML, "", "^math$")
        .stream().map(Json::parseObject).filter(event -> "node".equals(event.get("type")))
        .map(event -> asMap(event.get("node"))).toList();
    assertEquals("skip", node(math, "skipped").get("mode"));
  }

  @Test
  void aSuiteOfDenoWhichFailsOnItsOwn() {
    String xml = """
        <testsuites><testsuite name="./a_test.ts">
          <testcase name="outer" line="1" col="1"><failure message="Uncaught Error: setup failed">Error: setup failed
            at file:///p/a_test.ts:2:9</failure></testcase>
          <testcase name="outer &gt; inner" line="3" col="3"></testcase>
        </testsuite></testsuites>
        """;
    List<String> log = handle(JUnitReport.toEvents(TestFramework.DENO, null, ROOT, xml, "", null)).log;
    assertTrue(log.contains("failed a_test.ts > outer ERROR"), log.toString());
    assertTrue(log.contains("ended a_test.ts > outer > inner"), log.toString());
  }

  @Test
  void withoutAReportTheOutputIsTheErrorOfTheRun() {
    String output = "\u001b[1m\u001b[31merror\u001b[0m: SyntaxError: Expected ',', got '<eof>'\n"
        + "    at file:///p/src/broken_test.ts:2:35\n";
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.DENO, null, ROOT, null, output, null));
    assertTrue(session.log.contains("failed Unhandled errors ERROR"), session.log.toString());
    String trace = session.element("Unhandled errors").getFailureTrace().getTrace();
    assertTrue(trace.startsWith("SyntaxError: Expected ',', got '<eof>'"), trace);
    assertTrue(trace.contains("broken_test.ts:2:35"), trace);
    // Nothing at all.
    FakeSession empty = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, "", "", null));
    assertTrue(empty.element("Unhandled errors").getFailureTrace().getTrace().contains("Bun ended without a report"));
  }

  @Test
  void aReportWhichIsNotXmlIsIgnored() {
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.DENO, null, ROOT, "<testsuites><broken",
        "error: Test failed", null));
    assertTrue(session.log.contains("failed Unhandled errors ERROR"), session.log.toString());
  }

  @Test
  void anXmlWithADoctypeIsRefused() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
        + "<testsuites><testsuite name=\"a_test.ts\"><testcase name=\"&e;\"/></testsuite></testsuites>";
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.DENO, null, ROOT, xml, "", null));
    assertTrue(session.log.contains("failed Unhandled errors ERROR"), session.log.toString());
    assertFalse(session.log.stream().anyMatch(line -> line.contains("root:")), session.log.toString());
  }

  @Test
  void twoTestsWithTheSameNamesAreTwoTests() {
    String xml = """
        <testsuites><testsuite name="a.test.ts" file="a.test.ts">
          <testcase name="same" file="a.test.ts" line="1"/>
          <testcase name="same" file="a.test.ts" line="2"><failure/></testcase>
        </testsuite></testsuites>
        """;
    List<String> log = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, xml, "", null)).log;
    assertEquals(2, log.stream().filter(line -> line.equals("test a.test.ts > same")).count(), log.toString());
    assertTrue(log.contains("ended a.test.ts > same"), log.toString());
    assertTrue(log.contains("failed a.test.ts > same ERROR"), log.toString());
  }

  @Test
  void bunWithColors() {
    // With FORCE_COLOR (the console of Eclipse shows the colors), Bun marks the failed tests with a cross.
    String colored = BUN_OUTPUT.replace("(fail) math > compares objects [0.14ms]",
        "\u001b[0m\u001b[31m\u2717\u001b[0m \u001b[0mmath\u001b[2m >\u001b[0m\u001b[1m compares objects\u001b[0m \u001b[2m[0.21ms]\u001b[0m")
        .replace("(fail) throws [0.04ms]", "\u001b[31m\u2717\u001b[0m\u001b[0m\u001b[1m throws\u001b[0m [0.05ms]");
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.BUN, null, ROOT, BUN_XML, colored, null));
    assertTrue(session.log.contains("failed src/math.test.ts > throws ERROR"), session.log.toString());
    assertTrue(session.log.contains("failed src/math.test.ts > math > compares objects FAILURE"), session.log.toString());
    assertTrue(session.element("src/math.test.ts > throws").getFailureTrace().getTrace().startsWith("TypeError: boom"));
  }

  /** The report of Bun 1.2: no nested suites, their names in the class names from the innermost one. */
  private static final String BUN_1_2_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuites name="bun test" tests="5" assertions="3" failures="1" skipped="1" time="0.014999367">
        <testsuite name="src/hooks.test.ts" tests="1" assertions="0" failures="1" skipped="0" time="0" hostname="vm">
          <testcase name="never runs" classname="with a broken hook" time="0" file="src/hooks.test.ts" assertions="0">
            <failure type="AssertionError" />
          </testcase>
        </testsuite>
        <testsuite name="src/math.test.ts" tests="4" assertions="3" failures="0" skipped="1" time="0" hostname="vm">
          <testcase name="one and one" classname="adds &amp;gt; math" time="0.000034324" file="src/math.test.ts" assertions="1" />
          <testcase name="skipped" classname="math" time="0" file="src/math.test.ts" assertions="0">
            <skipped />
          </testcase>
          <testcase name="doubles 2" classname="" time="0.000015813" file="src/math.test.ts" assertions="1" />
        </testsuite>
      </testsuites>
      """;

  @Test
  void bun12() {
    String output = """
        src/hooks.test.ts:
        error: hook failed
              at <anonymous> (/p/src/hooks.test.ts:5:34)
        (fail) with a broken hook > never runs [0.13ms]
        """;
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.BUN, "1.2.0", ROOT, BUN_1_2_XML, output, null));
    List<String> log = session.log;
    assertTrue(log.contains("suite src/math.test.ts > math (null)"), log.toString());
    assertTrue(log.contains("suite src/math.test.ts > math > adds (null)"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > math > adds > one and one"), log.toString());
    assertTrue(log.contains("ignored src/math.test.ts > math > skipped"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > doubles 2"), log.toString());
    assertTrue(log.contains("failed src/hooks.test.ts > with a broken hook > never runs ERROR"), log.toString());
    assertTrue(session.element("src/hooks.test.ts > with a broken hook > never runs").getFailureTrace().getTrace()
        .startsWith("Error: hook failed"));
  }

  @Test
  void theColorsOfAReportAreRemoved() {
    // Deno 2.0 writes the colors of the console in its report: not allowed in XML 1.0, raw or as references.
    String xml = """
        <testsuites><testsuite name="./a_test.ts">
          <testcase name="throws" line="1" col="1"><failure message="Uncaught TypeError: boom">\u001b[0m\u001b[1m\u001b[31mTypeError\u001b[0m: boom&#27;[0m\u0007
            at fn (file:///p/a_test.ts:2:9)&#x1b;[39m&#1;</failure></testcase>
        </testsuite></testsuites>
        """;
    FakeSession session = handle(JUnitReport.toEvents(TestFramework.DENO, "2.0.0", ROOT, xml, "", null));
    String trace = session.element("a_test.ts > throws").getFailureTrace().getTrace();
    assertTrue(trace.startsWith("TypeError: boom"), trace);
    assertFalse(trace.contains("\u001b"), trace);
  }
}

