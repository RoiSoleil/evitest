package org.eclipse.evitest.core.frameworks;

import java.util.List;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.core.VitestCommandLine;

/** Vitest: {@code vitest run} with the reporter evitest-reporter.mjs (https://vitest.dev/guide/cli). */
final class VitestSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    return new VitestCommandLine().node(commandLine.getNode()).vitest(commandLine.getInstallation().toVitest())
        .reporter(commandLine.getReporters() == null ? null : reporter(commandLine, "evitest-reporter.mjs"))
        .filters(commandLine.getFilters()).selectors(commandLine.getSelectors())
        .namePattern(commandLine.getNamePattern()).updateSnapshots(commandLine.isUpdateSnapshots())
        .inspector(commandLine.getInspectorPort()).arguments(commandLine.getArguments()).build();
  }

  @Override
  public String testNamePattern(TestCommandLine commandLine) {
    // The full names of Vitest 1 start with the name of the file.
    return TestSelector.toRegex(commandLine.getSelectors(), commandLine.getInstallation().major() == 1);
  }
}
