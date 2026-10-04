package org.eclipse.evitest.ui;

import org.eclipse.jface.action.Action;

/** Opens the file of a line of a stack trace at its line. */
public class OpenStackFrameAction extends Action {

  private final StackFrameLocation location;

  public OpenStackFrameAction(StackFrameLocation location) {
    super("&Go to File");
    this.location = location;
  }

  @Override
  public void run() {
    Resources.open(location.file(), location.line(), location.column(), 0);
  }
}
