package org.eclipse.evitest.tests.swtbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.evitest.tests.Fixtures;
import org.eclipse.evitest.tests.TestWorkspace;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotMenu;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Run As > JavaScript Test, in the Project Explorer and in the editors, runs the real framework of each fixture and
 * shows the results in the Unit Test view. Skipped for the frameworks which are not installed (see {@link Fixtures}).
 */
class RunAsJavaScriptTestTest extends SwtBotTest {

  private static final String RESULT_VIEW = "org.eclipse.unittest.ui.ResultView";

  @BeforeEach
  void showTheLaunchActions() {
    // Run As is in the context menus with the launch actions, which the perspective of the tests may not show.
    UIThreadRunnable.syncExec(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
        .showActionSet("org.eclipse.debug.ui.launchActionSet"));
  }

  private static IProject importFixture(TestFramework framework) throws CoreException {
    return TestWorkspace.createFixtureProject("fixture-" + framework.id(), framework);
  }

  private static SWTBotTreeItem select(String project, String file) {
    SWTBotView explorer = showView(IPageLayout.ID_PROJECT_EXPLORER);
    // "fixture-jest (in jest)": the project is not named after its folder.
    SWTBotTreeItem item = Arrays.stream(explorer.bot().tree().getAllItems())
        .filter(candidate -> candidate.getText().startsWith(project + " ")).findFirst()
        .orElseThrow(() -> new AssertionError("No project " + project + " in the Project Explorer"));
    for (String segment : file.split("/")) {
      item = item.expand().getNode(segment);
    }
    return item.select();
  }

  /** Clicks JavaScript Test in a menu Run As (its items are numbered). */
  private static void runAsJavaScriptTest(SWTBotMenu runAs) {
    String label = runAs.menuItems().stream().filter(text -> text.endsWith("JavaScript Test")).findFirst()
        .orElseThrow(() -> new AssertionError("No JavaScript Test in " + runAs.menuItems()));
    runAs.menu(label).click();
  }

  /** Waits for the end of the run: the tests ended and the view received all of them. */
  private static SWTBotView waitForTheEndOfTheRun() {
    bot.waitUntil(new DefaultCondition() {
      @Override
      public boolean test() {
        ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
        return launches.length > 0 && launches[0].isTerminated();
      }

      @Override
      public String getFailureMessage() {
        return "The tests did not end";
      }
    }, 180_000);
    SWTBotView results = bot.viewById(RESULT_VIEW);
    results.show();
    bot.waitUntil(new DefaultCondition() {
      @Override
      public boolean test() {
        String[] runs = runs(results);
        return runs.length > 1 && !runs[1].equals("0") && runs[0].equals(runs[1]);
      }

      @Override
      public String getFailureMessage() {
        return "The Unit Test view did not receive the end of the run: " + String.join("/", runs(results));
      }
    });
    return results;
  }

  private static String[] runs(SWTBotView results) {
    return results.bot().textWithLabel("Runs: ").getText().trim().split("[/ ]");
  }

  /** The counters of the view: runs, errors, failures. */
  private static List<String> counters(SWTBotView results) {
    return List.of(results.bot().textWithLabel("Runs: ").getText(), results.bot().textWithLabel("Errors: ").getText(),
        results.bot().textWithLabel("Failures: ").getText());
  }

  /** The tests of the tree under its test file, as paths of names, and the labels of the test files. */
  private static Set<String> paths(SWTBotView results, List<String> files) {
    Set<String> paths = new TreeSet<>();
    for (SWTBotTreeItem item : results.bot().tree().getAllItems()) {
      files.add(item.getText());
      item.expand();
      for (SWTBotTreeItem child : item.getItems()) {
        collect(child, "", paths);
      }
    }
    return paths;
  }

  private static void collect(SWTBotTreeItem item, String parent, Set<String> paths) {
    String name = item.getText().replaceAll(" [^\\p{Alnum}\\s]*[\\d.,]+ ?s$", "");
    String path = parent.isEmpty() ? name : parent + " > " + name;
    paths.add(path);
    item.expand();
    for (SWTBotTreeItem child : item.getItems()) {
      collect(child, path, paths);
    }
  }

  /** The tests and the suites of the fixture of math, as the Unit Test view shows them. */
  private static Set<String> mathTests(TestFramework framework) {
    if (framework == TestFramework.DENO) {
      return new TreeSet<>(List.of("math", "math > adds", "math > adds > one and one", "math > adds > two and two",
          "math > skipped", "math > compares objects", "doubles 2", "doubles 3", "later", "throws"));
    }
    return new TreeSet<>(List.of("math", "math > adds", "math > adds > one and one", "math > adds > two and two",
        "math > skipped", "math > later", "math > compares objects", "doubles 2", "doubles 3", "throws"));
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsATestFile(TestFramework framework) throws CoreException {
    IProject project = importFixture(framework);
    String file = Fixtures.mathFile(framework);
    runAsJavaScriptTest(select(project.getName(), file).contextMenu("Run As"));
    SWTBotView results = waitForTheEndOfTheRun();
    List<String> files = new ArrayList<>();
    assertEquals(mathTests(framework), paths(results, files));
    assertEquals(1, files.size(), files.toString());
    assertTrue(files.get(0).startsWith(file), files.toString());
    // Deno: the comparison of the fixture throws an Error, not an assertion.
    assertEquals(framework == TestFramework.DENO ? List.of("8/8 (2 skipped)", "2", "0")
        : List.of("8/8 (2 skipped)", "1", "1"), counters(results));
    // The launch configuration of the file, reused by the next runs.
    String name = file.substring(file.lastIndexOf('/') + 1);
    ILaunchConfiguration configuration = TestWorkspace.findConfiguration(name);
    assertNotNull(configuration);
    assertEquals(List.of("/" + project.getName() + "/" + file),
        configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, List.of()));
    ILaunch launch = DebugPlugin.getDefault().getLaunchManager().getLaunches()[0];
    assertEquals(framework.id(), launch.getAttribute(VitestLaunchConstants.LAUNCH_FRAMEWORK));
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runsTheTestAtTheCursorOfTheEditor(TestFramework framework) throws CoreException {
    IProject project = importFixture(framework);
    IFile file = project.getFile(Fixtures.mathFile(framework));
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
    runAsJavaScriptTest(bot.menu("Run").menu("Run As"));
    SWTBotView results = waitForTheEndOfTheRun();
    List<String> files = new ArrayList<>();
    Set<String> paths = paths(results, files);
    assertTrue(paths.contains("math > adds > two and two"), paths.toString());
    List<String> counters = counters(results);
    if (framework != TestFramework.DENO) {
      // Only "two and two" passed: the other tests are skipped, or not run at all.
      assertEquals(List.of("0", "0"), counters.subList(1, 3), counters.toString());
      String[] runs = counters.get(0).split("[/ ]");
      String skipped = counters.get(0).replaceAll(".*\\((\\d+) skipped\\).*", "$1");
      int passed = Integer.parseInt(runs[0]) - (skipped.equals(counters.get(0)) ? 0 : Integer.parseInt(skipped));
      assertEquals(1, passed, counters.toString());
    }
    ILaunchConfiguration configuration = TestWorkspace.findConfiguration(
        file.getName() + " - math _ adds _ two and two");
    assertNotNull(configuration);
    assertEquals(List.of(TestSelector.test(List.of("math", "adds", "two and two")).toJson()),
        configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, List.of()));
  }
}
