package org.eclipse.evitest.core.frameworks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestSelector;

/**
 * Deno: {@code deno test} writing a JUnit report, read by {@link DenoReport} when the tests end: Deno has no API for
 * the reporters (https://docs.deno.com/runtime/reference/cli/test/).
 */
final class DenoSupport extends FrameworkSupport {

  @Override
  public List<String> command(TestCommandLine commandLine) {
    List<String> arguments = commandLine.getArguments();
    List<String> command = new ArrayList<>(List.of(entry(commandLine), "test"));
    if (!arguments.contains("--no-allow-all") && arguments.stream().noneMatch(argument -> argument.startsWith("--allow-")
        || argument.startsWith("-A") || argument.startsWith("--deny-") || argument.startsWith("-P")
        || argument.startsWith("--permission-set"))) {
      // As the Deno extension of VS Code: the tests may read files, use the network...
      command.add("--allow-all");
    }
    command.add("--reporter=dot");
    if (commandLine.getJunitReport() != null) {
      command.add("--junit-path=" + commandLine.getJunitReport().getAbsolutePath());
    }
    String pattern = commandLine.testNamePattern();
    if (pattern != null) {
      command.add("--filter=/" + pattern + "/");
    }
    for (String argument : arguments) {
      if (!argument.equals("--no-allow-all")) {
        command.add(argument);
      }
    }
    command.addAll(commandLine.getFilters());
    if (commandLine.isUpdateSnapshots()) {
      // The snapshots of the standard library of Deno: an argument of the tests.
      command.add("--");
      command.add("--update");
    }
    return command;
  }

  /** Deno filters the tests declared by {@code Deno.test}, not their steps: the first names only. */
  @Override
  public String testNamePattern(TestCommandLine commandLine) {
    Set<String> regexes = new LinkedHashSet<>();
    for (TestSelector selector : commandLine.getSelectors()) {
      regexes.add(new TestSelector(false, List.of(selector.names().get(0)), selector.template()).toRegex());
    }
    return regexes.size() == 1 ? regexes.iterator().next() : "(?:" + String.join(")|(?:", regexes) + ")";
  }
}
