package org.eclipse.evitest.tests.swtbot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.evitest.tests.TestWorkspace;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.waits.Conditions;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Run > Run Configurations...: the Vitest launch configurations and their main tab.
 */
class VitestLaunchConfigurationTest extends SwtBotTest {

  private static final String NAME = "swtbot-math";

  private IProject project;
  private IFile test;

  @BeforeEach
  void createProject() throws CoreException {
    project = TestWorkspace.createProject("demo");
    TestWorkspace.createFile(project, "package.json", "{ \"devDependencies\": { \"vitest\": \"5.0.3\" } }\n");
    test = TestWorkspace.createFile(project, "src/math.test.ts",
        "import { describe, it } from 'vitest'\n\ndescribe('math', () => {\n  it('adds', () => {})\n})\n");
  }

  private static SWTBotShell openRunConfigurations() {
    UIThreadRunnable.asyncExec(() -> DebugUITools.openLaunchConfigurationDialogOnGroup(
        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), new StructuredSelection(),
        IDebugUIConstants.ID_RUN_LAUNCH_GROUP));
    SWTBotShell shell = bot.shell("Run Configurations");
    shell.activate();
    return shell;
  }

  /** The error message of the tab shown by the dialog, null if the configuration is valid. */
  private static String errorMessage(SWTBotShell shell) {
    return UIThreadRunnable.syncExec(() -> {
      ILaunchConfigurationDialog dialog = (ILaunchConfigurationDialog) shell.widget.getData();
      return dialog.getActiveTab().getErrorMessage();
    });
  }

  private static void close(SWTBotShell shell) {
    shell.bot().button("Close").click();
    bot.waitUntil(Conditions.shellCloses(shell));
  }

  @Test
  void createsAConfigurationInTheDialog() throws CoreException {
    SWTBotShell shell = openRunConfigurations();
    SWTBot dialog = shell.bot();
    dialog.tree().getTreeItem("Vitest").select();
    dialog.toolbarButtonWithTooltip("New launch configuration").click();
    dialog.textWithLabel("Name:").setText(NAME);
    dialog.cTabItem("Vitest").activate();

    // Nothing to run yet.
    dialog.textWithLabel("Project:").setText("");
    assertEquals("Choose the project, the tests or the folder of Vitest.", errorMessage(shell));
    assertFalse(dialog.button("Run").isEnabled());
    dialog.textWithLabel("Project:").setText("missing");
    assertEquals("The project missing does not exist.", errorMessage(shell));
    dialog.textWithLabel("Project:").setText("demo");
    assertNull(errorMessage(shell));
    dialog.textWithLabel("Files and folders:").setText("/demo/src/missing.test.ts");
    assertEquals("The file or folder /demo/src/missing.test.ts does not exist.", errorMessage(shell));
    dialog.textWithLabel("Files and folders:").setText("/demo/src/math.test.ts");
    assertNull(errorMessage(shell));
    assertTrue(dialog.button("Run").isEnabled());

    dialog.textWithLabel("Tests:").setText("math > adds");
    dialog.checkBox("Update the snapshots (--update)").select();
    dialog.textWithLabel("Arguments:").setText("--bail=1");
    dialog.button("Apply").click();
    close(shell);

    ILaunchConfiguration configuration = TestWorkspace.findConfiguration(NAME);
    assertNotNull(configuration);
    assertEquals("demo", configuration.getAttribute(VitestLaunchConstants.ATTR_PROJECT, ""));
    assertEquals(List.of("/demo/src/math.test.ts"), configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, List.of()));
    assertEquals(List.of(TestSelector.suite(List.of("math", "adds")).toJson()),
        configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, List.of()));
    assertTrue(configuration.getAttribute(VitestLaunchConstants.ATTR_UPDATE_SNAPSHOTS, false));
    assertEquals("--bail=1", configuration.getAttribute(VitestLaunchConstants.ATTR_ARGUMENTS, ""));
    assertNull(configuration.getAttribute(VitestLaunchConstants.ATTR_ROOT, (String) null));
    assertArrayEquals(new IResource[] { test }, configuration.getMappedResources());
  }

  @Test
  void showsAnExistingConfiguration() throws CoreException {
    ILaunchConfigurationWorkingCopy configuration = DebugPlugin.getDefault().getLaunchManager()
        .getLaunchConfigurationType(VitestLaunchConstants.LAUNCH_CONFIGURATION_TYPE).newInstance(null, NAME);
    configuration.setAttribute(VitestLaunchConstants.ATTR_PROJECT, "demo");
    configuration.setAttribute(VitestLaunchConstants.ATTR_PATHS, List.of("/demo/src/math.test.ts"));
    configuration.setAttribute(VitestLaunchConstants.ATTR_SELECTORS,
        List.of(TestSelector.test(List.of("math", "adds")).toJson()));
    configuration.setAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, "ad+s");
    configuration.setAttribute(VitestLaunchConstants.ATTR_ROOT, "${project_loc:demo}");
    configuration.doSave();

    SWTBotShell shell = openRunConfigurations();
    SWTBot dialog = shell.bot();
    SWTBotTreeItem item = dialog.tree().getTreeItem("Vitest").expand().getNode(NAME);
    item.select();
    dialog.cTabItem("Vitest").activate();
    assertEquals("demo", dialog.textWithLabel("Project:").getText());
    assertEquals("/demo/src/math.test.ts", dialog.textWithLabel("Files and folders:").getText());
    assertEquals("math > adds", dialog.textWithLabel("Tests:").getText());
    assertEquals("ad+s", dialog.textWithLabel("Test name pattern:").getText());
    assertEquals("${project_loc:demo}", dialog.textWithLabel("Folder of Vitest:").getText());
    assertFalse(dialog.checkBox("Update the snapshots (--update)").isChecked());
    assertNull(errorMessage(shell));

    // The tests which are not edited keep their selector (a test, not a suite).
    dialog.textWithLabel("Test name pattern:").setText("");
    dialog.button("Apply").click();
    close(shell);
    ILaunchConfiguration saved = TestWorkspace.findConfiguration(NAME);
    assertEquals(List.of(TestSelector.test(List.of("math", "adds")).toJson()),
        saved.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, List.of()));
    assertEquals("", saved.getAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, ""));
  }
}
