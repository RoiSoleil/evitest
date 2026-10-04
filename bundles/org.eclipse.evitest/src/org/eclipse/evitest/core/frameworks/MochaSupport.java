package org.eclipse.evitest.core.frameworks;

import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.TestCommandLine;

/**
 * Mocha: its command line, started by evitest-mocha-run.cjs, with the reporter evitest-mocha.cjs
 * (https://mochajs.org/#command-line-usage).
 * <p>
 * Mocha adds the files of its command line to the spec of its configuration: the launcher gives it the files of
 * EVITEST_SPEC instead of the spec.
 */
final class MochaSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> command = commandLine.getReporters() == null ? nodeCommand(commandLine, entry(commandLine))
        : nodeCommand(commandLine, reporter(commandLine, "evitest-mocha-run.cjs"), entry(commandLine));
    if (commandLine.getReporters() != null) {
      command.add("--reporter");
      command.add(reporter(commandLine, "evitest-mocha.cjs"));
    } else {
      // Without the launcher of EVitest, the files are on the command line.
      command.addAll(commandLine.getFilters());
    }
    if (commandLine.getFilters().stream().anyMatch(filter -> isFolder(commandLine, filter))) {
      // The test files of the subfolders of the folders too.
      command.add("--recursive");
    }
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--grep");
      command.add(pattern);
    }
    command.addAll(commandLine.getArguments());
    return command;
  }

  @Override
  public Map<String, String> environment(TestCommandLine commandLine) {
    return commandLine.getFilters().isEmpty() ? Map.of()
        : Map.of("EVITEST_SPEC", Json.write(commandLine.getFilters()));
  }
}
