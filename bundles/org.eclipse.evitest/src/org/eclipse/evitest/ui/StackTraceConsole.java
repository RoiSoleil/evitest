package org.eclipse.evitest.ui;

import java.io.IOException;

import org.eclipse.evitest.Activator;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.unittest.model.ITestElement;
import org.eclipse.unittest.model.ITestElement.FailureTrace;

/** The console showing the stack trace of a failed test, where its frames are links. */
final class StackTraceConsole {

  private static final String NAME = "Vitest Stack Trace";
  /** The type of the console, for the links of its frames (plugin.xml). */
  static final String TYPE = "org.eclipse.evitest.ui.StackTraceConsole";

  private StackTraceConsole() {
  }

  static void show(ITestElement test) {
    FailureTrace failure = test == null ? null : test.getFailureTrace();
    if (failure == null || failure.getTrace() == null) {
      return;
    }
    MessageConsole console = console();
    console.clearConsole();
    console.activate();
    try (MessageConsoleStream stream = console.newMessageStream()) {
      stream.println(test.getDisplayName() != null ? test.getDisplayName() : test.getTestName());
      stream.println(failure.getTrace());
    } catch (IOException e) {
      Activator.log(e);
    }
    ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
  }

  private static MessageConsole console() {
    IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
    for (IConsole console : manager.getConsoles()) {
      if (console instanceof MessageConsole messageConsole && NAME.equals(console.getName())) {
        return messageConsole;
      }
    }
    MessageConsole console = new MessageConsole(NAME, TYPE, Activator.getImageDescriptor(Activator.IMG_VITEST), true);
    manager.addConsoles(new IConsole[] { console });
    return console;
  }
}
