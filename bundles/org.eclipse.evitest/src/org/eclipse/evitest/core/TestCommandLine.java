package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the command line running the tests with a framework and the reporter of EVitest, and the variables of its
 * environment.
 * <p>
 * The reporters are in {@link #reporters(File) a folder}: {@code evitest-reporter.mjs} for Vitest,
 * {@code evitest-jest.cjs}, {@code evitest-mocha.cjs}... Bun and Deno have no reporter: they write a JUnit report,
 * read when they end.
 */
public final class TestCommandLine {

  /** The test files of Jasmine in a folder, as the default configuration of Jasmine finds them. */
  static final String JASMINE_FILES = "**/*[sS]pec.?(m|c)js";

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
   * separated by spaces), null if all the tests run.
   * <ul>
   * <li>Playwright matches it with the project and the file before the names: it is not anchored at the start;</li>
   * <li>Deno filters the tests declared by {@code Deno.test}, not their steps: it matches the first names only.</li>
   * </ul>
   */
  public String testNamePattern() {
    if (selectors.isEmpty()) {
      return namePattern == null || namePattern.isBlank() ? null : namePattern;
    }
    return switch (framework) {
      // The full names of Vitest 1 start with the name of the file.
      case VITEST -> TestSelector.toRegex(selectors, installation.major() == 1);
      case PLAYWRIGHT -> TestSelector.toRegex(selectors, true);
      case DENO -> {
        Set<String> regexes = new LinkedHashSet<>();
        for (TestSelector selector : selectors) {
          regexes.add(new TestSelector(false, List.of(selector.names().get(0)), selector.template()).toRegex());
        }
        yield regexes.size() == 1 ? regexes.iterator().next() : "(?:" + String.join(")|(?:", regexes) + ")";
      }
      default -> TestSelector.toRegex(selectors, false);
    };
  }

  /** The variables of the environment of the reporters: the selected tests, the files of Mocha. */
  public Map<String, String> environment() {
    Map<String, String> environment = new LinkedHashMap<>();
    String pattern = testNamePattern();
    if (pattern != null) {
      environment.put("EVITEST_PATTERN", pattern);
    }
    if (framework == TestFramework.MOCHA && !filters.isEmpty()) {
      environment.put("EVITEST_SPEC", Json.write(filters));
    }
    return environment;
  }

  public List<String> build() {
    if (framework.isPackage() && installation.entry() == null) {
      throw new IllegalStateException("The installation of " + framework.label() + " is not set");
    }
    return switch (framework) {
      case VITEST -> vitest();
      case JEST -> jest();
      case MOCHA -> mocha();
      case JASMINE -> jasmine();
      case PLAYWRIGHT -> playwright();
      case NODE -> nodeTest();
      case BUN -> bun();
      case DENO -> deno();
    };
  }

  private List<String> vitest() {
    return new VitestCommandLine().node(node).vitest(installation.toVitest())
        .reporter(reporters == null ? null : reporter("evitest-reporter.mjs")).filters(filters).selectors(selectors)
        .namePattern(namePattern).updateSnapshots(updateSnapshots).inspector(inspectorPort).arguments(arguments)
        .build();
  }

  /** node [--inspect-brk] entry: the frameworks run by Node.js, in the process of the debugger. */
  private List<String> nodeCommand(String... entries) {
    List<String> command = new ArrayList<>();
    command.add(node);
    if (inspectorPort > 0) {
      command.add("--inspect-brk=127.0.0.1:" + inspectorPort);
    }
    for (String entry : entries) {
      command.add(entry);
    }
    return command;
  }

  private List<String> jest() {
    List<String> command = nodeCommand(installation.entry().getAbsolutePath());
    // The paths of Jest are regular expressions.
    for (String filter : filters) {
      command.add(TestSelector.escape(filter));
    }
    command.add("--reporters=default");
    if (reporters != null) {
      command.add("--reporters=" + reporter("evitest-jest.cjs"));
    }
    command.add("--testLocationInResults");
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--testNamePattern=" + pattern);
    }
    if (updateSnapshots) {
      command.add("--updateSnapshot");
    }
    if (inspectorPort > 0) {
      // One process: the debugger attaches to it.
      command.add("--runInBand");
    }
    command.addAll(arguments);
    return command;
  }

  private List<String> mocha() {
    List<String> command = reporters == null ? nodeCommand(installation.entry().getAbsolutePath())
        : nodeCommand(reporter("evitest-mocha-run.cjs"), installation.entry().getAbsolutePath());
    if (reporters != null) {
      command.add("--reporter");
      command.add(reporter("evitest-mocha.cjs"));
    } else {
      // Without the launcher of EVitest, the files are on the command line.
      command.addAll(filters);
    }
    if (filters.stream().anyMatch(this::isFolder)) {
      // The test files of the subfolders of the folders too.
      command.add("--recursive");
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--grep");
      command.add(pattern);
    }
    command.addAll(arguments);
    return command;
  }

  private List<String> jasmine() {
    List<String> command = nodeCommand(installation.entry().getAbsolutePath());
    for (String filter : filters) {
      command.add(isFolder(filter) ? stripSlash(filter) + "/" + JASMINE_FILES : filter);
    }
    if (reporters != null) {
      command.add("--reporter=" + reporter("evitest-jasmine.cjs"));
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--filter=" + pattern);
    }
    command.addAll(arguments);
    return command;
  }

  private List<String> playwright() {
    List<String> command = new ArrayList<>(List.of(node, installation.entry().getAbsolutePath(), "test"));
    // The paths of Playwright are regular expressions.
    for (String filter : filters) {
      command.add(TestSelector.escape(filter));
    }
    command.add(reporters == null ? "--reporter=list" : "--reporter=list," + reporter("evitest-playwright.cjs"));
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--grep=" + pattern);
    }
    if (updateSnapshots) {
      command.add("--update-snapshots");
    }
    command.addAll(arguments);
    return command;
  }

  private List<String> nodeTest() {
    List<String> command = nodeCommand("--test");
    if (inspectorPort > 0) {
      // The test files run in the process of the debugger, not in a process each.
      int[] version = NodeLocator.majorMinor(nodeVersion);
      command.add(version[0] >= 23 ? "--test-isolation=none" : "--experimental-test-isolation=none");
    }
    command.add("--test-reporter=spec");
    command.add("--test-reporter-destination=stdout");
    if (reporters != null) {
      // Loaded by import(): a URL on Windows, where C:/ would be the scheme of a URL.
      File nodeReporter = new File(reporters, "evitest-node.cjs");
      command.add("--test-reporter="
          + (NodeLocator.isWindows() ? nodeReporter.toPath().toUri().toString() : reporter("evitest-node.cjs")));
      command.add("--test-reporter-destination=stdout");
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--test-name-pattern=" + pattern);
    }
    if (updateSnapshots) {
      command.add("--test-update-snapshots");
    }
    command.addAll(arguments);
    for (String filter : filters) {
      if (isFolder(filter)) {
        command.addAll(nodeTestGlobs(stripSlash(filter)));
      } else {
        command.add(filter);
      }
    }
    return command;
  }

  /** The test files of a folder, as Node.js finds them by default. */
  private List<String> nodeTestGlobs(String folder) {
    int[] version = NodeLocator.majorMinor(nodeVersion);
    // TypeScript runs without a flag since Node.js 22.18 and 23.6.
    boolean typeScript = version[0] > 23 || (version[0] == 23 && version[1] >= 6) || (version[0] == 22 && version[1] >= 18);
    String extensions = typeScript ? "{cjs,mjs,js,cts,mts,ts}" : "{cjs,mjs,js}";
    List<String> globs = new ArrayList<>();
    for (String name : new String[] { "**/*.test.", "**/*-test.", "**/*_test.", "**/test-*.", "**/test.",
        "**/test/**/*." }) {
      globs.add(folder + "/" + name + extensions);
    }
    return globs;
  }

  private List<String> bun() {
    List<String> command = new ArrayList<>(List.of(installation.entry().getAbsolutePath(), "test"));
    for (String filter : filters) {
      // A path, not a filter on the names of the files.
      command.add(filter.startsWith("/") || filter.matches("^[A-Za-z]:.*") || filter.startsWith("./") ? filter
          : "./" + filter);
    }
    if (junitReport != null) {
      command.add("--reporter=junit");
      command.add("--reporter-outfile=" + junitReport.getAbsolutePath());
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--test-name-pattern=" + pattern);
    }
    if (updateSnapshots) {
      command.add("--update-snapshots");
    }
    command.addAll(arguments);
    return command;
  }

  private List<String> deno() {
    List<String> command = new ArrayList<>(List.of(installation.entry().getAbsolutePath(), "test"));
    if (!arguments.contains("--no-allow-all") && arguments.stream().noneMatch(argument -> argument.startsWith("--allow-")
        || argument.startsWith("-A") || argument.startsWith("--deny-") || argument.startsWith("-P")
        || argument.startsWith("--permission-set"))) {
      // As the Deno extension of VS Code: the tests may read files, use the network...
      command.add("--allow-all");
    }
    command.add("--reporter=dot");
    if (junitReport != null) {
      command.add("--junit-path=" + junitReport.getAbsolutePath());
    }
    String pattern = testNamePattern();
    if (pattern != null) {
      command.add("--filter=/" + pattern + "/");
    }
    List<String> scriptArguments = new ArrayList<>();
    for (String argument : arguments) {
      if (!argument.equals("--no-allow-all")) {
        command.add(argument);
      }
    }
    command.addAll(filters);
    if (updateSnapshots) {
      // The snapshots of the standard library of Deno: an argument of the tests.
      scriptArguments.add("--update");
    }
    if (!scriptArguments.isEmpty()) {
      command.add("--");
      command.addAll(scriptArguments);
    }
    return command;
  }

  private String reporter(String name) {
    return new File(reporters, name).getAbsolutePath().replace('\\', '/');
  }

  private boolean isFolder(String filter) {
    if (filter.endsWith("/")) {
      return true;
    }
    File file = new File(filter);
    if (!file.isAbsolute() && root != null) {
      file = new File(root, filter);
    }
    return file.isDirectory();
  }

  private static String stripSlash(String filter) {
    return filter.endsWith("/") ? filter.substring(0, filter.length() - 1) : filter;
  }
}
