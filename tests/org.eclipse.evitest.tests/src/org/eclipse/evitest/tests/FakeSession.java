package org.eclipse.evitest.tests;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.unittest.model.ITestCaseElement;
import org.eclipse.unittest.model.ITestElement;
import org.eclipse.unittest.model.ITestRunSession;
import org.eclipse.unittest.model.ITestSuiteElement;

/**
 * A session of the Unit Test view recording what it is told, as lines: {@code started}, {@code suite path (count)},
 * {@code test path}, {@code start path}, {@code failed path FAILURE}, {@code ended path}, {@code ignored path},
 * {@code completed} or {@code aborted}.
 */
class FakeSession implements ITestRunSession {

  final List<String> log = new ArrayList<>();
  final Map<String, Element> elements = new LinkedHashMap<>();

  class Element implements ITestCaseElement, ITestSuiteElement {
    final String id;
    final String name;
    final String displayName;
    final String data;
    final Element parent;
    final List<Element> children = new ArrayList<>();
    FailureTrace failureTrace;

    Element(String id, String name, String displayName, String data, Element parent) {
      this.id = id;
      this.name = name;
      this.displayName = displayName;
      this.data = data;
      this.parent = parent;
    }

    String path() {
      return parent == null ? name : parent.path() + " > " + name;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getData() {
      return data;
    }

    @Override
    public ITestRunSession getTestRunSession() {
      return FakeSession.this;
    }

    @Override
    public Duration getDuration() {
      return null;
    }

    @Override
    public FailureTrace getFailureTrace() {
      return failureTrace;
    }

    @Override
    public ITestSuiteElement getParent() {
      return parent == null ? FakeSession.this : parent;
    }

    @Override
    public String getTestName() {
      return name;
    }

    @Override
    public String getDisplayName() {
      return displayName;
    }

    @Override
    public List<? extends ITestElement> getChildren() {
      return children;
    }

    @Override
    public boolean isIgnored() {
      return false;
    }

    @Override
    public boolean isDynamicTest() {
      return false;
    }
  }

  Element element(String path) {
    for (Element element : elements.values()) {
      if (element.path().equals(path)) {
        return element;
      }
    }
    return null;
  }

  private static String path(ITestElement element) {
    return ((Element) element).path();
  }

  @Override
  public ITestCaseElement newTestCase(String testId, String testName, ITestSuiteElement parent, String displayName,
      String data) {
    Element element = new Element(testId, testName, displayName, data, parent instanceof Element e ? e : null);
    add(element);
    log.add("test " + element.path());
    return element;
  }

  @Override
  public ITestSuiteElement newTestSuite(String testId, String testName, Integer testCount, ITestSuiteElement parent,
      String displayName, String data) {
    Element element = new Element(testId, testName, displayName, data, parent instanceof Element e ? e : null);
    add(element);
    log.add("suite " + element.path() + " (" + testCount + ")");
    return element;
  }

  private void add(Element element) {
    elements.put(element.id, element);
    if (element.parent != null) {
      element.parent.children.add(element);
    }
  }

  @Override
  public void notifyTestSessionStarted(Integer count) {
    log.add("started");
  }

  @Override
  public void notifyTestSessionCompleted(Duration duration) {
    log.add("completed");
  }

  @Override
  public void notifyTestSessionAborted(Duration duration, Exception cause) {
    log.add("aborted" + (cause == null ? "" : ": " + cause.getMessage()));
  }

  @Override
  public void notifyTestStarted(ITestElement test) {
    log.add("start " + path(test));
  }

  @Override
  public void notifyTestEnded(ITestElement test, boolean isIgnored) {
    log.add((isIgnored ? "ignored " : "ended ") + path(test));
  }

  @Override
  public void notifyTestFailed(ITestElement test, Result status, boolean isAssumptionFailed, FailureTrace failureTrace) {
    ((Element) test).failureTrace = failureTrace;
    log.add("failed " + path(test) + " " + status.name());
  }

  @Override
  public ILaunch getLaunch() {
    return null;
  }

  @Override
  public ITestElement getTestElement(String id) {
    return elements.get(id);
  }

  @Override
  public List<? extends ITestElement> getChildren() {
    return elements.values().stream().filter(element -> element.parent == null).toList();
  }

  @Override
  public String getId() {
    return "session";
  }

  @Override
  public String getData() {
    return null;
  }

  @Override
  public ITestRunSession getTestRunSession() {
    return this;
  }

  @Override
  public Duration getDuration() {
    return null;
  }

  @Override
  public FailureTrace getFailureTrace() {
    return null;
  }

  @Override
  public ITestSuiteElement getParent() {
    return null;
  }

  @Override
  public String getTestName() {
    return "session";
  }

  @Override
  public String getDisplayName() {
    return "session";
  }
}
