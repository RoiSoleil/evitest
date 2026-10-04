package org.eclipse.evitest.core.frameworks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;

/**
 * What EVitest knows of the command line of a test framework: how to run its tests with the reporter of EVitest, how to
 * select the tests to run.
 * <p>
 * One subclass per framework: the changes of a framework are made in its class. The command lines only use the
 * documented options of the frameworks.
 */
public abstract class FrameworkSupport {

  private static final Map<TestFramework, FrameworkSupport> SUPPORTS = Map.of( //
      TestFramework.VITEST, new VitestSupport(), //
      TestFramework.JEST, new JestSupport(), //
      TestFramework.MOCHA, new MochaSupport(), //
      TestFramework.JASMINE, new JasmineSupport(), //
      TestFramework.PLAYWRIGHT, new PlaywrightSupport(), //
      TestFramework.NODE, new NodeTestSupport(), //
      TestFramework.BUN, new BunSupport(), //
      TestFramework.DENO, new DenoSupport());

  /** The support of a framework. */
  public static FrameworkSupport of(TestFramework framework) {
    return SUPPORTS.get(framework);
  }

  /** The command line running the tests described by the builder. */
  public abstract List<String> command(TestCommandLine commandLine);

  /**
   * The regular expression of the selectors of the builder (there is at least one), matching the full names of the
   * tests (the names of their suites and their name, separated by spaces) as the framework filters them.
   */
  public String testNamePattern(TestCommandLine commandLine) {
    return TestSelector.toRegex(commandLine.getSelectors(), false);
  }

  /** The variables of the environment of the reporter, besides the pattern of the selected tests. */
  public Map<String, String> environment(TestCommandLine commandLine) {
    return Map.of();
  }

  /** {@code node [--inspect-brk=127.0.0.1:port] entries...}: a framework run by Node.js, in the process debugged. */
  protected static List<String> nodeCommand(TestCommandLine commandLine, String... entries) {
    List<String> command = new ArrayList<>();
    command.add(commandLine.getNode());
    if (commandLine.getInspectorPort() > 0) {
      command.add("--inspect-brk=127.0.0.1:" + commandLine.getInspectorPort());
    }
    for (String entry : entries) {
      command.add(entry);
    }
    return command;
  }

  /** The path of the entry point of the installation. */
  protected static String entry(TestCommandLine commandLine) {
    return commandLine.getInstallation().entry().getAbsolutePath();
  }

  /** The path of a reporter of EVitest, with slashes. */
  protected static String reporter(TestCommandLine commandLine, String name) {
    return new File(commandLine.getReporters(), name).getAbsolutePath().replace('\\', '/');
  }

  /** True if the filter is a folder (a path relative to the root, or absolute). */
  protected static boolean isFolder(TestCommandLine commandLine, String filter) {
    if (filter.endsWith("/")) {
      return true;
    }
    File file = new File(filter);
    if (!file.isAbsolute() && commandLine.getRoot() != null) {
      file = new File(commandLine.getRoot(), filter);
    }
    return file.isDirectory();
  }

  protected static String stripSlash(String filter) {
    return filter.endsWith("/") ? filter.substring(0, filter.length() - 1) : filter;
  }
}
