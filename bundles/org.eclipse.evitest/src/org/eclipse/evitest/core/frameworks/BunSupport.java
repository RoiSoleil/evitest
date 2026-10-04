package org.eclipse.evitest.core.frameworks;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestSelector;

/**
 * Bun: {@code bun test} writing a JUnit report, read by {@link BunReport} when the tests end: Bun has no API for the
 * reporters (https://bun.sh/docs/cli/test).
 */
final class BunSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> command = new ArrayList<>(List.of(entry(commandLine), "test"));
    for (String filter : commandLine.getFilters()) {
      // A path, not a filter on the names of the files.
      command.add(filter.startsWith("/") || filter.matches("^[A-Za-z]:.*") || filter.startsWith("./") ? filter
          : "./" + filter);
    }
    if (commandLine.getJunitReport() != null) {
      command.add("--reporter=junit");
      command.add("--reporter-outfile=" + commandLine.getJunitReport().getAbsolutePath());
    }
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--test-name-pattern=" + pattern);
    }
    if (commandLine.isUpdateSnapshots()) {
      command.add("--update-snapshots");
    }
    command.addAll(commandLine.getArguments());
    return command;
  }

  /** The full names of the tests of Bun 1.2 do not start with the first name: not anchored at the start. */
  @Override
  public String testNamePattern(TestCommandLine commandLine) {
    return TestSelector.toRegex(commandLine.getSelectors(), true);
  }
}
