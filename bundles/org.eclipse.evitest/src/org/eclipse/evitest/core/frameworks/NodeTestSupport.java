package org.eclipse.evitest.core.frameworks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestCommandLine;

/**
 * The test runner of Node.js: {@code node --test} with the reporter evitest-node.cjs next to the spec reporter
 * (https://nodejs.org/api/test.html).
 */
final class NodeTestSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    int[] version = NodeLocator.majorMinor(commandLine.getNodeVersion());
    List<String> command = nodeCommand(commandLine, "--test");
    if (commandLine.getInspectorPort() > 0) {
      // The test files run in the process of the debugger, not in a process each.
      command.add(version[0] >= 23 ? "--test-isolation=none" : "--experimental-test-isolation=none");
    }
    command.add("--test-reporter=spec");
    command.add("--test-reporter-destination=stdout");
    if (commandLine.getReporters() != null) {
      // Loaded by import(): a URL on Windows, where C:/ would be the scheme of a URL.
      File reporter = new File(commandLine.getReporters(), "evitest-node.cjs");
      command.add("--test-reporter=" + (NodeLocator.isWindows() ? reporter.toPath().toUri().toString()
          : reporter(commandLine, "evitest-node.cjs")));
      command.add("--test-reporter-destination=stdout");
    }
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--test-name-pattern=" + pattern);
    }
    if (commandLine.isUpdateSnapshots()) {
      command.add("--test-update-snapshots");
    }
    command.addAll(commandLine.getArguments());
    for (String filter : commandLine.getFilters()) {
      if (isFolder(commandLine, filter)) {
        command.addAll(globs(stripSlash(filter), version));
      } else {
        command.add(filter);
      }
    }
    return command;
  }

  /** The test files of a folder, as Node.js finds them by default. */
  private static List<String> globs(String folder, int[] version) {
    // TypeScript runs without a flag since Node.js 22.18 and 23.6.
    boolean typeScript = version[0] > 23 || (version[0] == 23 && version[1] >= 6)
        || (version[0] == 22 && version[1] >= 18);
    String extensions = typeScript ? "{cjs,mjs,js,cts,mts,ts}" : "{cjs,mjs,js}";
    List<String> globs = new ArrayList<>();
    for (String name : new String[] { "**/*.test.", "**/*-test.", "**/*_test.", "**/test-*.", "**/test.",
        "**/test/**/*." }) {
      globs.add(folder + "/" + name + extensions);
    }
    return globs;
  }
}
