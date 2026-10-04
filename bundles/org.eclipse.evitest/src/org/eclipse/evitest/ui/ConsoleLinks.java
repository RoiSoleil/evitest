package org.eclipse.evitest.ui;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.ui.console.IHyperlink;
import org.eclipse.ui.console.IPatternMatchListenerDelegate;
import org.eclipse.ui.console.PatternMatchEvent;
import org.eclipse.ui.console.TextConsole;

/**
 * Makes the locations in the consoles of Vitest links: {@code ❯ src/math.test.ts:12:5}, the frames of the stack traces.
 */
public class ConsoleLinks implements IPatternMatchListenerDelegate {

  private TextConsole console;

  @Override
  public void connect(TextConsole textConsole) {
    this.console = textConsole;
  }

  @Override
  public void disconnect() {
    console = null;
  }

  @Override
  public void matchFound(PatternMatchEvent event) {
    TextConsole current = console;
    if (current == null) {
      return;
    }
    try {
      String text = current.getDocument().get(event.getOffset(), event.getLength());
      StackFrameLocation location = StackFrameLocation.parse(text, root(current));
      if (location == null) {
        return;
      }
      // The link covers the path and the position, without the parenthesis.
      int start = text.indexOf(location.file()) >= 0 ? text.indexOf(location.file()) : firstNonBlank(text);
      int end = text.length();
      while (end > start && (text.charAt(end - 1) == ')' || Character.isWhitespace(text.charAt(end - 1)))) {
        end--;
      }
      current.addHyperlink(new Link(location), event.getOffset() + start, end - start);
    } catch (BadLocationException e) {
      Activator.log(e);
    }
  }

  private static int firstNonBlank(String text) {
    int start = 0;
    while (start < text.length() && (Character.isWhitespace(text.charAt(start)) || text.charAt(start) == '(')) {
      start++;
    }
    return start;
  }

  /** The folder of the relative paths: the folder where Vitest ran. */
  private static String root(TextConsole console) {
    if (console instanceof org.eclipse.debug.ui.console.IConsole processConsole) {
      IProcess process = processConsole.getProcess();
      ILaunch launch = process == null ? null : process.getLaunch();
      return launch == null ? null : launch.getAttribute(VitestLaunchConstants.LAUNCH_ROOT);
    }
    return null;
  }

  private record Link(StackFrameLocation location) implements IHyperlink {

    @Override
    public void linkActivated() {
      Resources.open(location.file(), location.line(), location.column(), 0);
    }

    @Override
    public void linkEntered() {
      // Nothing.
    }

    @Override
    public void linkExited() {
      // Nothing.
    }
  }
}
