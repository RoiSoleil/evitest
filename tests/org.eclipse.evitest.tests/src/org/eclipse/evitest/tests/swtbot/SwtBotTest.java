package org.eclipse.evitest.tests.swtbot;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.evitest.tests.TestWorkspace;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.exceptions.WidgetNotFoundException;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.junit5.SWTBotJunit5Extension;
import org.eclipse.swtbot.swt.finder.utils.SWTBotPreferences;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The SWTBot tests drive the workbench of the tests as a user, outside of the UI thread. A failed test saves a
 * screenshot in the screenshots folder.
 */
@ExtendWith(SWTBotJunit5Extension.class)
abstract class SwtBotTest {

  protected static SWTWorkbenchBot bot;

  @BeforeAll
  static void createBot() {
    SWTBotPreferences.KEYBOARD_LAYOUT = "EN_US";
    SWTBotPreferences.TIMEOUT = 20_000;
    bot = new SWTWorkbenchBot();
    try {
      bot.viewByTitle("Welcome").close();
    } catch (WidgetNotFoundException e) {
      // No welcome page.
    }
  }

  @BeforeEach
  @AfterEach
  void cleanWorkspace() throws CoreException {
    closeDialogs();
    TestWorkspace.deleteLaunches();
    TestWorkspace.deleteProjects();
  }

  /** Closes the dialogs left open by a failed test. */
  private static void closeDialogs() {
    UIThreadRunnable.syncExec(() -> {
      for (Shell shell : Display.getCurrent().getShells()) {
        if (shell.getData() instanceof Dialog dialog) {
          dialog.close();
        }
      }
    });
  }

  /** Opens and activates a view. */
  protected static SWTBotView showView(String id) {
    UIThreadRunnable.syncExec(() -> {
      try {
        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(id);
      } catch (PartInitException e) {
        throw new IllegalStateException(e);
      }
    });
    SWTBotView view = bot.viewById(id);
    view.show();
    return view;
  }
}
