package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.frameworks.FrameworkSupport;

/**
 * Builds the command line running the tests with a framework and the reporter of EVitest, and the variables of its
 * environment.
 * <p>
 * The reporters are in {@link #reporters(File) a folder}: {@code evitest-reporter.mjs} for Vitest,
 * {@code evitest-jest.cjs}, {@code evitest-mocha.cjs}... Bun and Deno have no reporter: they write a JUnit report,
 * read when they end. The command line of each framework is built by its {@link FrameworkSupport}.
 */
public final class TestCommandLine {

  private final TestFramework framework;
  private final Installation installation;
  private String node = "node";
  private String nodeVersion;
  private File reporters;
  private File root;
  private File junitReport;
  private final List<String> filters = new ArrayList<>();
  private final List<TestSelector> selectors = new ArrayList<>();
  private String namePattern;
  private boolean updateSnapshots;
  private int inspectorPort;
  private final List<String> arguments = new ArrayList<>();

  public TestCommandLine(Installation installation) {
    this.installation = installation;
    this.framework = installation.framework();
  }

  public TestFramework framework() {
    return framework;
  }

  /** The Node.js executable, for the frameworks running on Node.js. */
  public TestCommandLine node(String executable) {
    this.node = executable;
    return this;
  }

  /** The version of Node.js ({@code 22.18.0}), null if it is not known. */
  public TestCommandLine nodeVersion(String version) {
    this.nodeVersion = version;
    return this;
  }

  /** The folder of the reporters of EVitest. */
  public TestCommandLine reporters(File folder) {
    this.reporters = folder;
    return this;
  }

  /** The folder where the tests run. */
  public TestCommandLine root(File folder) {
    this.root = folder;
    return this;
  }

  /** The JUnit report written by Bun and Deno. */
  public TestCommandLine junitReport(File file) {
    this.junitReport = file;
    return this;
  }

  /** The files and the folders to run, relative to the root ({@code src/math.test.ts}), all the tests if none. */
  public TestCommandLine filters(Collection<String> relativePaths) {
    for (String path : relativePaths) {
      String filter = path.replace('\\', '/');
      if (!filter.isEmpty() && !filter.equals(".") && !filters.contains(filter)) {
        filters.add(filter);
      }
    }
    return this;
  }

  /** The tests and the suites to run, all the tests of the files if none. */
  public TestCommandLine selectors(Collection<TestSelector> testSelectors) {
    selectors.addAll(testSelectors);
    return this;
  }

  /** A regular expression of the user on the full names of the tests, used when there is no selector. */
  public TestCommandLine namePattern(String pattern) {
    this.namePattern = pattern;
    return this;
  }

  public TestCommandLine updateSnapshots(boolean update) {
    this.updateSnapshots = update;
    return this;
  }

  /** Opens the inspector of Node.js on this port and waits for a debugger, 0 for none. */
  public TestCommandLine inspector(int port) {
    this.inspectorPort = port;
    return this;
  }

  /** Additional arguments of the framework. */
  public TestCommandLine arguments(Collection<String> additionalArguments) {
    arguments.addAll(additionalArguments);
    return this;
  }

  /**
   * The regular expression selecting the tests by their full names (the names of their suites and their name,
   * separated by spaces), as the framework filters them: the one of the selectors, else the pattern of the user, null
   * if all the tests run.
   */
  public String testNamePattern() {
    if (selectors.isEmpty()) {
      return namePattern == null || namePattern.isBlank() ? null : namePattern;
    }
    return FrameworkSupport.of(framework).testNamePattern(this);
  }

  /** The variables of the environment of the reporters: the selected tests, the files of Mocha. */
  public Map<String, String> environment() {
    Map<String, String> environment = new LinkedHashMap<>();
    String pattern = testNamePattern();
    if (pattern != null) {
      environment.put("EVITEST_PATTERN", pattern);
    }
    environment.putAll(FrameworkSupport.of(framework).environment(this));
    return environment;
  }

  public List<String> build() {
    if (framework.isPackage() && installation.entry() == null) {
      throw new IllegalStateException("The installation of " + framework.label() + " is not set");
    }
    return FrameworkSupport.of(framework).command(this);
  }

  public Installation getInstallation() {
    return installation;
  }

  public String getNode() {
    return node;
  }

  public String getNodeVersion() {
    return nodeVersion;
  }

  public File getReporters() {
    return reporters;
  }

  public File getRoot() {
    return root;
  }

  public File getJunitReport() {
    return junitReport;
  }

  public List<String> getFilters() {
    return Collections.unmodifiableList(filters);
  }

  public List<TestSelector> getSelectors() {
    return Collections.unmodifiableList(selectors);
  }

  public String getNamePattern() {
    return namePattern;
  }

  public boolean isUpdateSnapshots() {
    return updateSnapshots;
  }

  public int getInspectorPort() {
    return inspectorPort;
  }

  public List<String> getArguments() {
    return Collections.unmodifiableList(arguments);
  }
}
