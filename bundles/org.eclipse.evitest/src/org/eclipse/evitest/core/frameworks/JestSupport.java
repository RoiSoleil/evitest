package org.eclipse.evitest.core.frameworks;

import java.util.List;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestSelector;

/**
 * Jest: its command line with the reporter evitest-jest.cjs next to the default one (https://jestjs.io/docs/cli).
 */
final class JestSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> command = nodeCommand(commandLine, entry(commandLine));
    // The paths of Jest are regular expressions.
    for (String filter : commandLine.getFilters()) {
      command.add(TestSelector.escape(filter));
    }
    command.add("--reporters=default");
    if (commandLine.getReporters() != null) {
      command.add("--reporters=" + reporter(commandLine, "evitest-jest.cjs"));
    }
    command.add("--testLocationInResults");
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--testNamePattern=" + pattern);
    }
    if (commandLine.isUpdateSnapshots()) {
      command.add("--updateSnapshot");
    }
    if (commandLine.getInspectorPort() > 0) {
      // One process: the debugger attaches to it.
      command.add("--runInBand");
    }
    command.addAll(commandLine.getArguments());
    return command;
  }
}
