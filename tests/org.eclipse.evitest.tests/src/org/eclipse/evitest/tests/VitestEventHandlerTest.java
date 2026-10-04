package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.ui.TestElementData;
import org.eclipse.evitest.ui.TestResults;
import org.eclipse.evitest.ui.VitestEventHandler;
import org.eclipse.unittest.model.ITestElement.FailureTrace;
import org.junit.jupiter.api.Test;

class VitestEventHandlerTest {

  private static final String MODULE = """
      {"type":"module","id":"m1","file":"/p/math.test.ts","name":"math.test.ts","children":[
        {"id":"m1_0","kind":"suite","name":"math","names":["math"],"mode":"run","line":2,"column":1,"children":[
          {"id":"m1_0_0","kind":"test","name":"adds","names":["math","adds"],"mode":"run","line":3,"column":3},
          {"id":"m1_0_1","kind":"test","name":"fails","names":["math","fails"],"mode":"run","line":4,"column":3},
          {"id":"m1_0_2","kind":"test","name":"later","names":["math","later"],"mode":"todo","line":5,"column":3}]},
        {"id":"m1_1","kind":"test","name":"throws","names":["throws"],"mode":"run","line":7,"column":1}]}
      """.replace("\n", "");

  private static FakeSession run(String... lines) {
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    for (String line : lines) {
      handler.handle(line);
    }
    return session;
  }

  @Test
  void vitest3AndNewer() {
    FakeSession session = run( //
        "{\"type\":\"hello\",\"protocol\":1,\"vitest\":\"5.0.3\",\"root\":\"/p\"}", //
        "{\"type\":\"runStart\",\"files\":1}", //
        "{\"type\":\"moduleQueued\",\"id\":\"m1\"}", //
        MODULE, //
        "{\"type\":\"testStart\",\"id\":\"m1_0_0\"}", //
        "{\"type\":\"testEnd\",\"id\":\"m1_0_0\",\"state\":\"passed\",\"duration\":2}", //
        "{\"type\":\"testStart\",\"id\":\"m1_0_1\"}", //
        "{\"type\":\"testEnd\",\"id\":\"m1_0_1\",\"state\":\"failed\",\"errors\":[{\"name\":\"AssertionError\","
            + "\"message\":\"expected 1 to be 2\",\"trace\":\"AssertionError: expected 1 to be 2\\n    at /p/math.test.ts:4:20\","
            + "\"expected\":\"2\",\"actual\":\"1\",\"assertion\":true}]}", //
        "{\"type\":\"testEnd\",\"id\":\"m1_0_2\",\"state\":\"skipped\"}", //
        "{\"type\":\"testStart\",\"id\":\"m1_1\"}", //
        "{\"type\":\"testEnd\",\"id\":\"m1_1\",\"state\":\"failed\",\"errors\":[{\"name\":\"TypeError\",\"message\":\"boom\","
            + "\"trace\":\"TypeError: boom\",\"assertion\":false}]}", //
        "{\"type\":\"runEnd\",\"reason\":\"failed\",\"duration\":540}");
    assertEquals(List.of( //
        "started", //
        "suite math.test.ts (4)", //
        "suite math.test.ts > math (3)", //
        "test math.test.ts > math > adds", //
        "test math.test.ts > math > fails", //
        "test math.test.ts > math > later", //
        "test math.test.ts > throws", //
        "start math.test.ts > math > adds", //
        "ended math.test.ts > math > adds", //
        "start math.test.ts > math > fails", //
        "failed math.test.ts > math > fails FAILURE", //
        "ended math.test.ts > math > fails", //
        "start math.test.ts > math > later", //
        "ignored math.test.ts > math > later", //
        "start math.test.ts > throws", //
        "failed math.test.ts > throws ERROR", //
        "ended math.test.ts > throws", //
        "completed"), session.log);

    FailureTrace trace = session.element("math.test.ts > math > fails").getFailureTrace();
    assertEquals("AssertionError: expected 1 to be 2\n    at /p/math.test.ts:4:20", trace.getTrace());
    assertEquals("2", trace.getExpected());
    assertEquals("1", trace.getActual());
    assertTrue(trace.isComparisonFailure());
    assertFalse(session.element("math.test.ts > throws").getFailureTrace().isComparisonFailure());

    TestElementData data = TestElementData.parse(session.element("math.test.ts > math > adds").getData());
    assertEquals(new TestElementData(TestElementData.TEST, "/p/math.test.ts", 3, 3, List.of("math", "adds"), null),
        data);
    TestElementData module = TestElementData.parse(session.element("math.test.ts").getData());
    assertTrue(module.isModule());
    assertNull(module.toSelector());
    assertEquals(TestSelector.suite(List.of("math")),
        TestElementData.parse(session.element("math.test.ts > math").getData()).toSelector());

    assertEquals(TestResults.State.PASSED, TestResults.get("/p/math.test.ts", TestSelector.test(List.of("math", "adds"))));
    assertEquals(TestResults.State.FAILED, TestResults.get("/p/math.test.ts", TestSelector.suite(List.of("math"))));
    assertEquals(TestResults.State.SKIPPED,
        TestResults.get("/p/math.test.ts", TestSelector.test(List.of("math", "later"))));
  }

  @Test
  void vitest2() {
    // No runStart and no testStart.
    FakeSession session = run( //
        "{\"type\":\"hello\",\"protocol\":1,\"vitest\":\"2.1.9\",\"root\":\"/p\"}", //
        MODULE, //
        "{\"type\":\"testEnd\",\"id\":\"m1_0_0\",\"state\":\"passed\",\"duration\":1}", //
        "{\"type\":\"runEnd\",\"duration\":10}");
    assertEquals(List.of("started", "suite math.test.ts (4)", "suite math.test.ts > math (3)",
        "test math.test.ts > math > adds", "test math.test.ts > math > fails", "test math.test.ts > math > later",
        "test math.test.ts > throws", "start math.test.ts > math > adds", "ended math.test.ts > math > adds",
        "completed"), session.log);
  }

  @Test
  void filteredTestsKeepTheirPreviousResult() {
    run(MODULE.replace("/p/", "/q/"), "{\"type\":\"testEnd\",\"id\":\"m1_1\",\"state\":\"passed\"}",
        "{\"type\":\"runEnd\"}");
    // Run again with a filter: "throws" is skipped by the filter, not by the code.
    run(MODULE.replace("/p/", "/q/"), "{\"type\":\"testEnd\",\"id\":\"m1_1\",\"state\":\"skipped\"}",
        "{\"type\":\"runEnd\"}");
    assertEquals(TestResults.State.PASSED, TestResults.get("/q/math.test.ts", TestSelector.test(List.of("throws"))));
  }

  @Test
  void errorsOfSuitesAndModules() {
    FakeSession session = run( //
        "{\"type\":\"runStart\"}", //
        "{\"type\":\"module\",\"id\":\"b\",\"file\":\"/p/broken.test.ts\",\"name\":\"broken.test.ts\","
            + "\"project\":\"unit\",\"children\":[]}", //
        "{\"type\":\"suiteError\",\"id\":\"b\",\"errors\":[{\"name\":\"Error\",\"message\":\"Transform failed\","
            + "\"trace\":\"Error: Transform failed\"}]}", //
        "{\"type\":\"suiteError\",\"id\":\"unknown\",\"errors\":[]}", //
        "{\"type\":\"runEnd\",\"reason\":\"failed\",\"errors\":[{\"name\":\"Error\",\"message\":\"late\","
            + "\"trace\":\"Error: late\"},{\"message\":\"second\"}]}");
    assertEquals(List.of("started", "suite broken.test.ts (null)", "failed broken.test.ts ERROR",
        "test Unhandled errors", "start Unhandled errors", "failed Unhandled errors ERROR", "ended Unhandled errors",
        "completed"), session.log);
    assertEquals("broken.test.ts [unit]", session.element("broken.test.ts").getDisplayName());
    assertEquals("Error: late\n\nsecond", session.element("Unhandled errors").getFailureTrace().getTrace());
  }

  @Test
  void interruptedAndAborted() {
    assertEquals(List.of("started", "aborted"),
        run("{\"type\":\"runStart\"}", "{\"type\":\"runEnd\",\"reason\":\"interrupted\"}").log);

    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    handler.handle("{\"type\":\"runStart\"}");
    handler.handle("not json");
    handler.handle("{\"type\":\"future\"}");
    handler.handle("");
    assertFalse(handler.isSessionEnded());
    handler.abort("Vitest crashed");
    handler.abort("twice");
    assertTrue(handler.isSessionEnded());
    assertEquals(List.of("started", "aborted: Vitest crashed"), session.log);
  }

  @Test
  void testsAddedWhileTheyRun() {
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    for (String line : List.of( //
        "{\"type\":\"hello\",\"protocol\":2,\"framework\":\"Jest\",\"version\":\"30.5.2\",\"root\":\"/p\"}", //
        "{\"type\":\"runStart\"}", //
        "{\"type\":\"module\",\"id\":\"f\",\"file\":\"/p/a.test.js\",\"name\":\"a.test.js\",\"project\":\"unit\",\"children\":[]}", //
        "{\"type\":\"node\",\"parent\":\"f\",\"node\":{\"id\":\"s\",\"kind\":\"suite\",\"name\":\"math\",\"names\":[\"math\"],\"line\":1}}", //
        "{\"type\":\"node\",\"parent\":\"s\",\"node\":{\"id\":\"t1\",\"kind\":\"test\",\"name\":\"adds\",\"names\":[\"math\",\"adds\"],\"line\":2,\"column\":3}}", //
        "{\"type\":\"testStart\",\"id\":\"t1\"}", //
        "{\"type\":\"testEnd\",\"id\":\"t1\",\"state\":\"passed\"}", //
        "{\"type\":\"node\",\"parent\":\"s\",\"node\":{\"id\":\"t2\",\"kind\":\"test\",\"name\":\"later\",\"names\":[\"math\",\"later\"],\"mode\":\"todo\"}}", //
        "{\"type\":\"testEnd\",\"id\":\"t2\",\"state\":\"skipped\"}", //
        // Ignored: an unknown parent, a test as a parent, a node twice, a node without its node.
        "{\"type\":\"node\",\"parent\":\"unknown\",\"node\":{\"id\":\"x\",\"kind\":\"test\",\"name\":\"x\",\"names\":[\"x\"]}}", //
        "{\"type\":\"node\",\"parent\":\"t1\",\"node\":{\"id\":\"y\",\"kind\":\"test\",\"name\":\"y\",\"names\":[\"y\"]}}", //
        "{\"type\":\"node\",\"parent\":\"s\",\"node\":{\"id\":\"t1\",\"kind\":\"test\",\"name\":\"adds\",\"names\":[\"math\",\"adds\"]}}", //
        "{\"type\":\"node\",\"parent\":\"s\"}", //
        "{\"type\":\"runEnd\",\"duration\":3}")) {
      handler.handle(line);
    }
    assertEquals("Jest", handler.getFramework());
    assertEquals("30.5.2", handler.getFrameworkVersion());
    assertNull(handler.getVitestVersion());
    assertEquals(List.of( //
        "started", //
        "suite a.test.js (null)", //
        "suite a.test.js > math (null)", //
        "test a.test.js > math > adds", //
        "start a.test.js > math > adds", //
        "ended a.test.js > math > adds", //
        "test a.test.js > math > later", //
        "start a.test.js > math > later", //
        "ignored a.test.js > math > later", //
        "completed"), session.log);
    TestElementData adds = TestElementData.parse(session.element("a.test.js > math > adds").getData());
    assertEquals("/p/a.test.js", adds.file());
    assertEquals("unit", adds.project());
    assertEquals(List.of("math", "adds"), adds.names());
    assertEquals(Integer.valueOf(2), adds.line());
    assertEquals(Integer.valueOf(3), adds.column());
    assertEquals(TestResults.State.PASSED, TestResults.get("/p/a.test.js", TestSelector.test(List.of("math", "adds"))));
    assertEquals(TestResults.State.SKIPPED, TestResults.get("/p/a.test.js", TestSelector.test(List.of("math", "later"))));
  }

  @Test
  void theFrameworkOfTheReporterOfVitest() {
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    assertNull(handler.getFramework());
    handler.handle("{\"type\":\"hello\",\"protocol\":1,\"vitest\":\"5.0.3\",\"root\":\"/p\"}");
    assertEquals("Vitest", handler.getFramework());
    assertEquals("5.0.3", handler.getFrameworkVersion());
    assertEquals("5.0.3", handler.getVitestVersion());
  }
}
