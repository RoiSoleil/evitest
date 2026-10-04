package org.eclipse.evitest.core.frameworks;

import java.util.List;

import org.eclipse.evitest.core.TestCommandLine;

/**
 * Jasmine: its command line with the helper evitest-jasmine.cjs, which adds the reporter of EVitest with
 * {@code jasmine.getEnv().addReporter()} next to the console reporter of Jasmine (https://jasmine.github.io/setup/nodejs.html).
 */
final class JasmineSupport extends FrameworkSupport {

  /** The test files of Jasmine in a folder, as its default configuration finds them. */
  static final String FILES = "**/*[sS]pec.?(m|c)js";

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> command = nodeCommand(commandLine, entry(commandLine));
    for (String filter : commandLine.getFilters()) {
      command.add(isFolder(commandLine, filter) ? stripSlash(filter) + "/" + FILES : filter);
    }
    if (commandLine.getReporters() != null) {
      command.add("--helper=" + reporter(commandLine, "evitest-jasmine.cjs"));
    }
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--filter=" + pattern);
    }
    command.addAll(commandLine.getArguments());
    return command;
  }
}
