package org.eclipse.evitest.ui;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.evitest.core.Json;
import org.eclipse.unittest.model.ITestCaseElement;
import org.eclipse.unittest.model.ITestElement;
import org.eclipse.unittest.model.ITestElement.FailureTrace;
import org.eclipse.unittest.model.ITestElement.Result;
import org.eclipse.unittest.model.ITestRunSession;
import org.eclipse.unittest.model.ITestSuiteElement;

/**
 * Turns the events of the reporter (lines of JSON) into the tests of a session of the Unit Test view.
 */
public class VitestEventHandler {

  /** The identifier of the test showing the errors which happened outside the tests. */
  static final String UNHANDLED_ERRORS_ID = "evitest-unhandled-errors";

  private final ITestRunSession session;
  private final Map<String, ITestElement> elements = new HashMap<>();
  private final Map<String, TestElementData> data = new HashMap<>();
  private final Map<String, String> modes = new HashMap<>();
  private final Set<String> started = new HashSet<>();
  private final Set<String> ended = new HashSet<>();
  private boolean sessionStarted;
  private boolean sessionEnded;
  private String vitestVersion;

  public VitestEventHandler(ITestRunSession session) {
    this.session = session;
  }

  public boolean isSessionEnded() {
    return sessionEnded;
  }

  public String getVitestVersion() {
    return vitestVersion;
  }

  /** Handles a line sent by the reporter. Lines which are not events are ignored. */
  public void handle(String line) {
    if (line.isBlank()) {
      return;
    }
    Map<String, Object> event = Json.parseObject(line);
    String type = Json.getString(event, "type");
    if (type == null) {
      return;
    }
    switch (type) {
      case "hello" -> vitestVersion = Json.getString(event, "vitest");
      case "runStart" -> startSession();
      case "module" -> addModule(event);
      case "testStart" -> testStarted(Json.getString(event, "id"));
      case "testEnd" -> testEnded(event);
      case "suiteError" -> suiteFailed(event);
      case "runEnd" -> runEnded(event);
      default -> {
        // A newer reporter: unknown events are ignored.
      }
    }
  }

  /** Ends the session when the reporter is gone without ending it: Vitest crashed, or was terminated. */
  public void abort(String reason) {
    if (sessionEnded) {
      return;
    }
    sessionEnded = true;
    clearRunning();
    session.notifyTestSessionAborted(null, reason == null ? null : new IllegalStateException(reason));
  }

  private void startSession() {
    if (!sessionStarted) {
      sessionStarted = true;
      // The number of tests is not known: Vitest collects each file when it runs it.
      session.notifyTestSessionStarted(null);
    }
  }

  private void addModule(Map<String, Object> event) {
    startSession();
    String id = Json.getString(event, "id");
    if (id == null || elements.containsKey(id)) {
      return;
    }
    String file = Json.getString(event, "file");
    String name = Json.getString(event, "name");
    String project = Json.getString(event, "project");
    List<Map<String, Object>> children = Json.getObjects(event, "children");
    TestElementData moduleData = new TestElementData(TestElementData.MODULE, file, null, null, List.of(), project);
    String displayName = project == null ? name : name + " [" + project + "]";
    ITestSuiteElement module = session.newTestSuite(id, name, Integer.valueOf(countTests(children)), null, displayName,
        moduleData.toJson());
    elements.put(id, module);
    data.put(id, moduleData);
    for (Map<String, Object> child : children) {
      addNode(child, module, file, project);
    }
  }

  private void addNode(Map<String, Object> node, ITestSuiteElement parent, String file, String project) {
    String id = Json.getString(node, "id");
    if (id == null || elements.containsKey(id)) {
      return;
    }
    String name = Json.getString(node, "name");
    boolean suite = TestElementData.SUITE.equals(Json.getString(node, "kind"));
    TestElementData nodeData = new TestElementData(suite ? TestElementData.SUITE : TestElementData.TEST, file,
        Json.getInteger(node, "line"), Json.getInteger(node, "column"), Json.getStrings(node, "names"), project);
    data.put(id, nodeData);
    String mode = Json.getString(node, "mode");
    if (mode != null) {
      modes.put(id, mode);
    }
    if (suite) {
      List<Map<String, Object>> children = Json.getObjects(node, "children");
      ITestSuiteElement element = session.newTestSuite(id, name, Integer.valueOf(countTests(children)), parent, name,
          nodeData.toJson());
      elements.put(id, element);
      for (Map<String, Object> child : children) {
        addNode(child, element, file, project);
      }
    } else {
      elements.put(id, session.newTestCase(id, name, parent, name, nodeData.toJson()));
    }
  }

  private static int countTests(List<Map<String, Object>> nodes) {
    int count = 0;
    for (Map<String, Object> node : nodes) {
      count += TestElementData.SUITE.equals(Json.getString(node, "kind")) ? countTests(Json.getObjects(node, "children"))
          : 1;
    }
    return count;
  }

  private void testStarted(String id) {
    ITestElement element = elements.get(id);
    if (element instanceof ITestCaseElement && started.add(id)) {
      session.notifyTestStarted(element);
      TestElementData testData = data.get(id);
      if (testData != null) {
        TestResults.setRunning(testData.file(), testData.names(), true);
        TestResults.fireChanged(testData.file());
      }
    }
  }

  private void testEnded(Map<String, Object> event) {
    String id = Json.getString(event, "id");
    ITestElement element = elements.get(id);
    if (element == null || !ended.add(id)) {
      return;
    }
    testStarted(id);
    String state = Json.getString(event, "state");
    TestElementData testData = data.get(id);
    if ("failed".equals(state)) {
      List<Map<String, Object>> errors = Json.getObjects(event, "errors");
      session.notifyTestFailed(element, kind(errors), false, failureTrace(errors));
      session.notifyTestEnded(element, false);
      record(testData, TestResults.State.FAILED);
    } else if ("skipped".equals(state)) {
      session.notifyTestEnded(element, true);
      String mode = modes.get(id);
      if ("skip".equals(mode) || "todo".equals(mode)) {
        record(testData, TestResults.State.SKIPPED);
      } else if (testData != null) {
        // Not run because of a filter or of a failure before it: its previous result stays.
        TestResults.setRunning(testData.file(), testData.names(), false);
        TestResults.fireChanged(testData.file());
      }
    } else {
      session.notifyTestEnded(element, false);
      record(testData, TestResults.State.PASSED);
    }
  }

  private void record(TestElementData testData, TestResults.State state) {
    if (testData != null) {
      TestResults.set(testData.file(), testData.names(), state);
      TestResults.fireChanged(testData.file());
    }
  }

  private void suiteFailed(Map<String, Object> event) {
    ITestElement element = elements.get(Json.getString(event, "id"));
    if (element == null) {
      return;
    }
    List<Map<String, Object>> errors = Json.getObjects(event, "errors");
    session.notifyTestFailed(element, Result.ERROR, false, failureTrace(errors));
  }

  private void runEnded(Map<String, Object> event) {
    startSession();
    List<Map<String, Object>> errors = Json.getObjects(event, "errors");
    if (!errors.isEmpty()) {
      // Errors outside the tests (a promise rejected after its test...): Vitest fails the run because of them.
      ITestCaseElement unhandled = session.newTestCase(UNHANDLED_ERRORS_ID, "Unhandled errors", null,
          "Unhandled errors (" + errors.size() + ")", null);
      session.notifyTestStarted(unhandled);
      session.notifyTestFailed(unhandled, Result.ERROR, false, failureTrace(errors));
      session.notifyTestEnded(unhandled, false);
    }
    sessionEnded = true;
    clearRunning();
    Long duration = Json.getLong(event, "duration");
    Duration elapsed = duration == null ? null : Duration.ofMillis(duration.longValue());
    if ("interrupted".equals(Json.getString(event, "reason"))) {
      session.notifyTestSessionAborted(elapsed, null);
    } else {
      session.notifyTestSessionCompleted(elapsed);
    }
  }

  /** The tests started but not ended (the run was interrupted) are not running anymore. */
  private void clearRunning() {
    for (String id : started) {
      TestElementData testData = data.get(id);
      if (!ended.contains(id) && testData != null) {
        TestResults.setRunning(testData.file(), testData.names(), false);
        TestResults.fireChanged(testData.file());
      }
    }
  }

  /** Assertions are failures, other errors are errors (as in JUnit). */
  private static Result kind(List<Map<String, Object>> errors) {
    for (Map<String, Object> error : errors) {
      if (Json.getBoolean(error, "assertion")) {
        return Result.FAILURE;
      }
    }
    return errors.isEmpty() ? Result.FAILURE : Result.ERROR;
  }

  /** The traces of all the errors, and the expected and actual values of the first comparison. */
  static FailureTrace failureTrace(List<Map<String, Object>> errors) {
    StringBuilder trace = new StringBuilder();
    String expected = null;
    String actual = null;
    for (Map<String, Object> error : errors) {
      if (!trace.isEmpty()) {
        trace.append("\n\n");
      }
      String text = Json.getString(error, "trace");
      if (text == null) {
        text = Json.getString(error, "message");
      }
      trace.append(text == null ? "Error" : text);
      if (expected == null && actual == null) {
        expected = Json.getString(error, "expected");
        actual = Json.getString(error, "actual");
      }
    }
    if (trace.isEmpty()) {
      trace.append("The test failed without an error.");
    }
    return new FailureTrace(trace.toString(), expected, actual);
  }
}
