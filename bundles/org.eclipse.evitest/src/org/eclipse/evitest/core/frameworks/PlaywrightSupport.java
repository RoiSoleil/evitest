package org.eclipse.evitest.core.frameworks;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestSelector;

/**
 * Playwright Test: {@code playwright test} with the reporter evitest-playwright.cjs next to the list reporter
 * (https://playwright.dev/docs/test-cli).
 */
final class PlaywrightSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> command = new ArrayList<>(List.of(commandLine.getNode(), entry(commandLine), "test"));
    // The paths of Playwright are regular expressions.
    for (String filter : commandLine.getFilters()) {
      command.add(TestSelector.escape(filter));
    }
    command.add(commandLine.getReporters() == null ? "--reporter=list"
        : "--reporter=list," + reporter(commandLine, "evitest-playwright.cjs"));
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--grep=" + pattern);
    }
    if (commandLine.isUpdateSnapshots()) {
      command.add("--update-snapshots");
    }
    command.addAll(commandLine.getArguments());
    return command;
  }

  /** The title of the tests of Playwright starts with the project and the file: not anchored at the start. */
  @Override
  public String testNamePattern(TestCommandLine commandLine) {
    return TestSelector.toRegex(commandLine.getSelectors(), true);
  }
}
