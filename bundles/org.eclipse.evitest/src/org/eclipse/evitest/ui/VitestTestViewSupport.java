package org.eclipse.evitest.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.text.StringMatcher;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.evitest.launch.VitestLaunchSettings;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.unittest.launcher.ITestRunnerClient;
import org.eclipse.unittest.model.ITestCaseElement;
import org.eclipse.unittest.model.ITestElement;
import org.eclipse.unittest.model.ITestRunSession;
import org.eclipse.unittest.model.ITestSuiteElement;
import org.eclipse.unittest.ui.ITestViewSupport;

/**
 * The support of Vitest in the Unit Test view: the results, the navigation to the tests and to the stack frames, the
 * rerun of the tests.
 */
public class VitestTestViewSupport implements ITestViewSupport {

  /** The frames of Vitest and of Node.js, hidden by the stack trace filter of the view. */
  private static final List<String> FILTERS = List.of("*/node_modules/vitest/*", "*/node_modules/@vitest/*",
      "*/node_modules/tinypool/*", "*/node_modules/tinyspy/*", "*/node_modules/.pnpm/vitest@*",
      "*/node_modules/.pnpm/@vitest+*", "*(node:*", "*at node:*");

  @Override
  public ITestRunnerClient newTestRunnerClient(ITestRunSession session) {
    return new VitestTestRunnerClient(session);
  }

  @Override
  public Collection<StringMatcher> getTraceExclusionFilterPatterns() {
    List<StringMatcher> matchers = new ArrayList<>();
    for (String filter : FILTERS) {
      matchers.add(new StringMatcher(filter, true, false));
    }
    return matchers;
  }

  @Override
  public IAction getOpenTestAction(Shell shell, ITestCaseElement testCase) {
    return OpenTestAction.create(testCase);
  }

  @Override
  public IAction getOpenTestAction(Shell shell, ITestSuiteElement testSuite) {
    return OpenTestAction.create(testSuite);
  }

  @Override
  public IAction createOpenEditorAction(Shell shell, ITestElement failure, String traceLine) {
    StackFrameLocation location = StackFrameLocation.parse(traceLine, rootOf(failure));
    return location == null ? null : new OpenStackFrameAction(location);
  }

  @Override
  public Runnable createShowStackTraceInConsoleViewActionDelegate(ITestElement failedTest) {
    return () -> StackTraceConsole.show(failedTest);
  }

  @Override
  public ILaunchConfiguration getRerunLaunchConfiguration(List<ITestElement> testElements) {
    if (testElements.isEmpty()) {
      return null;
    }
    ILaunch launch = testElements.get(0).getTestRunSession().getLaunch();
    ILaunchConfiguration configuration = launch == null ? null : launch.getLaunchConfiguration();
    if (configuration == null) {
      return null;
    }
    Set<String> paths = new LinkedHashSet<>();
    List<String> selectors = new ArrayList<>();
    boolean wholeFiles = false;
    for (ITestElement element : testElements) {
      if (element instanceof ITestRunSession) {
        return configuration;
      }
      TestElementData data = TestElementData.of(element);
      if (data == null) {
        // The unhandled errors: the whole run.
        return configuration;
      }
      paths.add(toConfigurationPath(data.file()));
      TestSelector selector = data.toSelector();
      if (selector == null) {
        wholeFiles = true;
      } else {
        selectors.add(selector.toJson());
      }
    }
    try {
      ILaunchConfigurationWorkingCopy copy = configuration.copy(configuration.getName());
      copy.setAttribute(VitestLaunchConstants.ATTR_PATHS, new ArrayList<>(paths));
      // The selectors apply to all the files: a whole file with tests of other files runs the whole files.
      copy.setAttribute(VitestLaunchConstants.ATTR_SELECTORS, wholeFiles ? null : selectors);
      copy.setAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, (String) null);
      copy.setAttribute(VitestLaunchConstants.ATTR_ROOT, rootOfConfiguration(launch, configuration));
      return copy;
    } catch (CoreException e) {
      Activator.log(e.getStatus());
      return null;
    }
  }

  /** The root of the rerun: the one of the run, when it was found from its tests (the rerun runs other tests). */
  private static String rootOfConfiguration(ILaunch launch, ILaunchConfiguration configuration) throws CoreException {
    String root = configuration.getAttribute(VitestLaunchConstants.ATTR_ROOT, "");
    if (root.isBlank()) {
      String launchRoot = launch.getAttribute(VitestLaunchConstants.LAUNCH_ROOT);
      return launchRoot == null ? "" : launchRoot;
    }
    return root;
  }

  /** The path of a test file in a launch configuration: its workspace path if it is in the workspace. */
  static String toConfigurationPath(String file) {
    IFile resource = Resources.findFile(file);
    return resource != null ? VitestLaunchSettings.toPath(resource) : file;
  }

  /** The folder where Vitest ran the tests of the element, null if it is not known. */
  static String rootOf(ITestElement element) {
    ILaunch launch = element == null ? null : element.getTestRunSession().getLaunch();
    return launch == null ? null : launch.getAttribute(VitestLaunchConstants.LAUNCH_ROOT);
  }

  @Override
  public String getDisplayName() {
    return "Vitest";
  }
}
