package org.eclipse.evitest.tests.swtbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.evitest.tests.TestWorkspace;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotMenu;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTree;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Run As > Vitest Test, in the Project Explorer and in the editors, runs the real Vitest of the fixture and shows the
 * results in the Unit Test view. Skipped without Node.js and the dependencies of the fixture ({@code npm ci} in {@code fixture}).
 */
class RunAsVitestTestTest extends SwtBotTest {

  private static final String RESULT_VIEW = "org.eclipse.unittest.ui.ResultView";

  private IProject project;

  @BeforeEach
  void importTheFixture() throws CoreException {
    project = TestWorkspace.createFixtureProject("fixture");
    // Run As is in the context menus with the launch actions, which the perspective of the tests may not show.
    UIThreadRunnable.syncExec(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
        .showActionSet("org.eclipse.debug.ui.launchActionSet"));
  }

  private static SWTBotTreeItem select(String... path) {
    SWTBotView explorer = showView(IPageLayout.ID_PROJECT_EXPLORER);
    SWTBotTreeItem item = explorer.bot().tree().getTreeItem(path[0]);
    for (int i = 1; i < path.length; i++) {
      item = item.expand().getNode(path[i]);
    }
    return item.select();
  }

  /** Clicks Run As > Vitest Test in the context menu of the item. */
  private static void runAsVitestTest(SWTBotTreeItem item) {
    runAsVitestTest(item.contextMenu("Run As"));
  }

  /** Clicks Vitest Test in the menu Run As (its items are numbered). */
  private static void runAsVitestTest(SWTBotMenu runAs) {
    String label = runAs.menuItems().stream().filter(text -> text.endsWith("Vitest Test")).findFirst()
        .orElseThrow(() -> new AssertionError("No Vitest Test in " + runAs.menuItems()));
    runAs.menu(label).click();
  }

  /** Waits for the end of the run: Vitest ended and the view received all the tests. */
  private static SWTBotView waitForTheEndOfTheRun() {
    bot.waitUntil(new DefaultCondition() {
      @Override
      public boolean test() {
        ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
        return launches.length > 0 && launches[0].isTerminated();
      }

      @Override
      public String getFailureMessage() {
        return "Vitest did not end";
      }
    }, 120_000);
    SWTBotView results = bot.viewById(RESULT_VIEW);
    results.show();
    bot.waitUntil(new DefaultCondition() {
      @Override
      public boolean test() {
        String[] runs = results.bot().textWithLabel("Runs: ").getText().trim().split("[/ ]");
        return runs.length > 1 && !runs[1].equals("0") && runs[0].equals(runs[1]);
      }

      @Override
      public String getFailureMessage() {
        return "The Unit Test view did not receive the end of the run";
      }
    });
    return results;
  }

  /** The labels of the tree, indented by level, without the durations. */
  private static List<String> labels(SWTBotTree tree) {
    List<String> labels = new ArrayList<>();
    for (SWTBotTreeItem item : tree.getAllItems()) {
      collect(item, "", labels);
    }
    return labels;
  }

  private static void collect(SWTBotTreeItem item, String indentation, List<String> labels) {
    labels.add(indentation + item.getText().replaceAll(" [^\\p{Alnum}\\s]*[\\d.,]+ ?s$", ""));
    item.expand();
    for (SWTBotTreeItem child : item.getItems()) {
      collect(child, indentation + "  ", labels);
    }
  }

  /** The tests of the fixture, as the Unit Test view shows them. */
  private static final List<String> TESTS = List.of(
      "src/math.test.ts [Runner: Vitest]",
      "  math",
      "    adds",
      "      one and one",
      "      two and two",
      "    skipped",
      "    later",
      "    compares objects",
      "  doubles 2",
      "  doubles 3",
      "  throws");

  /** The counters of the view: runs, errors, failures. */
  private static List<String> counters(SWTBotView results) {
    return List.of(results.bot().textWithLabel("Runs: ").getText(), results.bot().textWithLabel("Errors: ").getText(),
        results.bot().textWithLabel("Failures: ").getText());
  }

  @Test
  void runsATestFile() throws CoreException {
    runAsVitestTest(select("fixture", "src", "math.test.ts"));
    SWTBotView results = waitForTheEndOfTheRun();
    assertEquals(TESTS, labels(results.bot().tree()));
    // "compares objects" fails, "throws" is an error.
    assertEquals(List.of("8/8 (2 skipped)", "1", "1"), counters(results));
    // The launch configuration of the file, reused by the next runs.
    ILaunchConfiguration configuration = TestWorkspace.findConfiguration("math.test.ts");
    assertNotNull(configuration);
    assertEquals(List.of("/fixture/src/math.test.ts"),
        configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, List.of()));
  }

  @Test
  void runsTheTestAtTheCursorOfTheEditor() throws CoreException {
    IFile file = project.getFile("src/math.test.ts");
    UIThreadRunnable.syncExec(() -> {
      try {
        ITextEditor editor = (ITextEditor) IDE.openEditor(
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file, "org.eclipse.ui.DefaultTextEditor");
        String text = editor.getDocumentProvider().getDocument(editor.getEditorInput()).get();
        editor.selectAndReveal(text.indexOf("two and two"), 0);
      } catch (PartInitException e) {
        throw new IllegalStateException(e);
      }
    });
    runAsVitestTest(bot.menu("Run").menu("Run As"));
    SWTBotView results = waitForTheEndOfTheRun();
    // Only "two and two" runs: Vitest skips the other tests.
    assertEquals(TESTS, labels(results.bot().tree()));
    assertEquals(List.of("8/8 (7 skipped)", "0", "0"), counters(results));
    ILaunchConfiguration configuration = TestWorkspace.findConfiguration("math.test.ts - math _ adds _ two and two");
    assertNotNull(configuration);
    assertEquals(List.of(TestSelector.test(List.of("math", "adds", "two and two")).toJson()),
        configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, List.of()));
  }
}
