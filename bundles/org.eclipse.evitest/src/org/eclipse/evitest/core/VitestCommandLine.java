package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the command line running Vitest with the reporter of EVitest.
 */
public final class VitestCommandLine {

  private String node = "node";
  private File entry;
  private String reporter;
  private int major;
  private final List<String> filters = new ArrayList<>();
  private final List<TestSelector> selectors = new ArrayList<>();
  private String namePattern;
  private boolean updateSnapshots;
  private int inspectorPort;
  private final List<String> arguments = new ArrayList<>();

  /** The Node.js executable. */
  public VitestCommandLine node(String executable) {
    this.node = executable;
    return this;
  }

  /** The installation of Vitest. */
  public VitestCommandLine vitest(VitestLocator.Installation installation) {
    this.entry = installation.entry();
    this.major = installation.major();
    return this;
  }

  /** The path of the reporter of EVitest. */
  public VitestCommandLine reporter(String path) {
    this.reporter = path;
    return this;
  }

  /** The files and the folders to run, relative to the root ({@code src/math.test.ts}), all the tests if none. */
  public VitestCommandLine filters(Collection<String> relativePaths) {
    for (String path : relativePaths) {
      String filter = path.replace('\\', '/');
      if (!filter.isEmpty() && !filter.equals(".") && !filters.contains(filter)) {
        filters.add(filter);
      }
    }
    return this;
  }

  /** The tests and the suites to run, all the tests of the files if none. */
  public VitestCommandLine selectors(Collection<TestSelector> testSelectors) {
    selectors.addAll(testSelectors);
    return this;
  }

  /** A regular expression of the user on the full names of the tests, used when there is no selector. */
  public VitestCommandLine namePattern(String pattern) {
    this.namePattern = pattern;
    return this;
  }

  public VitestCommandLine updateSnapshots(boolean update) {
    this.updateSnapshots = update;
    return this;
  }

  /** Opens the inspector of Node.js on this port and waits for a debugger before each test file, 0 for none. */
  public VitestCommandLine inspector(int port) {
    this.inspectorPort = port;
    return this;
  }

  /** Additional arguments of Vitest. */
  public VitestCommandLine arguments(Collection<String> additionalArguments) {
    arguments.addAll(additionalArguments);
    return this;
  }

  /** The regular expression given to the {@code --testNamePattern} option, null if there is none. */
  public String testNamePattern() {
    if (!selectors.isEmpty()) {
      // The full names of Vitest 1 start with the name of the file.
      return TestSelector.toRegex(selectors, major == 1);
    }
    return namePattern == null || namePattern.isBlank() ? null : namePattern;
  }

  public List<String> build() {
    if (entry == null) {
      throw new IllegalStateException("The installation of Vitest is not set");
    }
    List<String> command = new ArrayList<>();
    command.add(node);
    command.add(entry.getAbsolutePath());
    command.add("run");
    command.addAll(filters);
    command.add("--reporter=default");
    if (reporter != null) {
      command.add("--reporter=" + reporter.replace('\\', '/'));
    }
    if (major >= 3) {
      // Vitest 1 and 2 have no such option: the reporter sets it in the configuration.
      command.add("--includeTaskLocation");
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--testNamePattern=" + pattern);
    }
    if (updateSnapshots) {
      command.add("--update");
    }
    if (inspectorPort > 0) {
      // The test files run one after the other: the debugger attaches to each of them on the same port.
      command.add("--inspectBrk=127.0.0.1:" + inspectorPort);
      command.add("--no-file-parallelism");
    }
    command.addAll(arguments);
    return command;
  }
}
