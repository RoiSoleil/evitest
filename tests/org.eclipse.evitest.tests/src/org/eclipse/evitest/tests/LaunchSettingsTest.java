package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.launch.VitestLaunchConstants;
import org.eclipse.evitest.launch.VitestLaunchSettings;
import org.eclipse.evitest.launch.VitestPropertyTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The launch configurations resolved with the projects of the workspace: the framework, the folder of the tests, the
 * installation, the arguments, the errors; and the properties enabling Run As.
 */
class LaunchSettingsTest {

  @BeforeEach
  @AfterEach
  void clean() throws CoreException {
    TestWorkspace.deleteLaunches();
    TestWorkspace.deleteProjects();
    for (String key : new String[] { Preferences.ARGUMENTS, Preferences.BUN_PATH, Preferences.DENO_PATH,
        Preferences.TEST_FILES }) {
      Preferences.store().setToDefault(key);
    }
  }

  private static ILaunchConfigurationWorkingCopy configuration(String project, String... paths) throws CoreException {
    ILaunchConfigurationWorkingCopy configuration = DebugPlugin.getDefault().getLaunchManager()
        .getLaunchConfigurationType(VitestLaunchConstants.LAUNCH_CONFIGURATION_TYPE).newInstance(null, "settings");
    configuration.setAttribute(VitestLaunchConstants.ATTR_PROJECT, project);
    if (paths.length > 0) {
      configuration.setAttribute(VitestLaunchConstants.ATTR_PATHS, List.of(paths));
    }
    return configuration;
  }

  private static String message(CoreException exception) {
    return exception.getStatus().getMessage();
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void resolvesTheFrameworkOfTheTests(TestFramework framework) throws CoreException {
    IProject project = TestWorkspace.createFixtureProject("settings-" + framework.id(), framework);
    String math = Fixtures.mathFile(framework);
    VitestLaunchSettings settings = VitestLaunchSettings
        .resolve(configuration(project.getName(), "/" + project.getName() + "/" + math));
    assertEquals(framework, settings.getFramework());
    assertEquals(Fixtures.fixture(framework), settings.getRoot());
    assertEquals(List.of(math), settings.getFilters());
    assertNotNull(settings.getInstallation());
    if (framework.usesNode()) {
      assertNotNull(settings.getNode());
      if (framework == TestFramework.NODE) {
        // node:test is the version of Node.js.
        assertEquals(NodeLocator.version(settings.getNode()), settings.getInstallation().version());
      }
    } else {
      assertNull(settings.getNode());
      assertTrue(settings.getInstallation().entry().isFile());
    }
    // The whole project: all the tests of the root.
    assertTrue(VitestLaunchSettings.resolve(configuration(project.getName(), "/" + project.getName())).getFilters()
        .isEmpty());
  }

  @Test
  void aChosenFrameworkWinsOverTheDetectedOne() throws CoreException {
    IProject project = TestWorkspace.createProject("chosen");
    TestWorkspace.createFile(project, "package.json", "{ \"devDependencies\": { \"vitest\": \"5.0.0\" } }");
    TestWorkspace.createFile(project, "test/a.test.js", "import { test } from 'node:test'\n");
    ILaunchConfigurationWorkingCopy configuration = configuration("chosen", "/chosen/test/a.test.js");
    assertEquals(TestFramework.NODE, VitestLaunchSettings.resolve(configuration).getFramework());
    configuration.setAttribute(VitestLaunchConstants.ATTR_FRAMEWORK, "vitest");
    CoreException missing = assertThrows(CoreException.class, () -> VitestLaunchSettings.resolve(configuration));
    assertTrue(message(missing).startsWith("Vitest is not installed in "), message(missing));
    assertTrue(message(missing).contains("node_modules/vitest is missing"), message(missing));
    // An unknown framework is detected.
    configuration.setAttribute(VitestLaunchConstants.ATTR_FRAMEWORK, "karma");
    assertEquals(TestFramework.NODE, VitestLaunchSettings.resolve(configuration).getFramework());
  }

  @Test
  void theFolderOfTheTestsMayBeChosen() throws Exception {
    File fixture = Fixtures.fixture(TestFramework.NODE);
    ILaunchConfigurationWorkingCopy configuration = configuration("");
    configuration.setAttribute(VitestLaunchConstants.ATTR_ROOT, fixture.getAbsolutePath());
    // The test script of package.json runs node --test.
    VitestLaunchSettings settings = VitestLaunchSettings.resolve(configuration);
    assertEquals(TestFramework.NODE, settings.getFramework());
    assertEquals(fixture, settings.getRoot());
    assertTrue(settings.getFilters().isEmpty());
    // A folder without a framework, unless it is chosen.
    File empty = java.nio.file.Files.createTempDirectory("evitest-empty").toFile();
    try {
      configuration.setAttribute(VitestLaunchConstants.ATTR_ROOT, empty.getAbsolutePath());
      CoreException none = assertThrows(CoreException.class, () -> VitestLaunchSettings.resolve(configuration));
      assertTrue(message(none).startsWith("No test framework found for "), message(none));
      configuration.setAttribute(VitestLaunchConstants.ATTR_FRAMEWORK, "node");
      assertEquals(empty, VitestLaunchSettings.resolve(configuration).getRoot());
    } finally {
      empty.delete();
    }
    configuration.setAttribute(VitestLaunchConstants.ATTR_ROOT, new File(fixture, "missing").getAbsolutePath());
    CoreException missing = assertThrows(CoreException.class, () -> VitestLaunchSettings.resolve(configuration));
    assertTrue(message(missing).startsWith("The folder of the tests "), message(missing));
  }

  @Test
  void aFileNotRefreshedYetIsFoundInItsProject() throws Exception {
    IProject project = TestWorkspace.createProject("unrefreshed");
    TestWorkspace.createFile(project, "package.json", "{ \"scripts\": { \"test\": \"node --test\" } }");
    // Written outside of Eclipse: the workspace does not know it.
    java.nio.file.Path test = project.getLocation().toFile().toPath().resolve("test/a.test.js");
    java.nio.file.Files.createDirectories(test.getParent());
    java.nio.file.Files.writeString(test, "import { test } from 'node:test'\n");
    assertNull(project.findMember("test/a.test.js"));
    assertEquals(test.toFile(), VitestLaunchSettings.toFile("/unrefreshed/test/a.test.js"));
    VitestLaunchSettings settings = VitestLaunchSettings.resolve(configuration("unrefreshed", "/unrefreshed/test/a.test.js"));
    assertEquals(TestFramework.NODE, settings.getFramework());
    assertEquals(List.of("test/a.test.js"), settings.getFilters());
    // A missing file stays missing.
    assertEquals(new File("/unrefreshed/test/missing.test.js"),
        VitestLaunchSettings.toFile("/unrefreshed/test/missing.test.js"));
  }

  @Test
  void errorsOfTheConfiguration() throws CoreException {
    CoreException nothing = assertThrows(CoreException.class, () -> VitestLaunchSettings.resolve(configuration("")));
    assertEquals("Choose the project or the tests to run.", message(nothing));
    CoreException missingFile = assertThrows(CoreException.class,
        () -> VitestLaunchSettings.resolve(configuration("", "/missing/a.test.ts")));
    assertEquals("The test file or folder /missing/a.test.ts does not exist.", message(missingFile));
    IProject project = TestWorkspace.createProject("plain");
    TestWorkspace.createFile(project, "a.test.ts", "describe('x', () => {})");
    CoreException noFramework = assertThrows(CoreException.class,
        () -> VitestLaunchSettings.resolve(configuration("plain", "/plain/a.test.ts")));
    assertTrue(message(noFramework).startsWith("No test framework found for "), message(noFramework));
    assertTrue(message(noFramework).contains("choose the framework of the launch configuration"), message(noFramework));
  }

  @Test
  void bunAndDenoAreFoundWithThePreferences() throws CoreException {
    IProject project = TestWorkspace.createProject("runtimes");
    TestWorkspace.createFile(project, "bunfig.toml", "");
    TestWorkspace.createFile(project, "a.test.ts", "import { test } from 'bun:test'\n");
    ILaunchConfigurationWorkingCopy configuration = configuration("runtimes", "/runtimes/a.test.ts");
    Preferences.store().setValue(Preferences.BUN_PATH, "/missing/bun");
    CoreException missing = assertThrows(CoreException.class, () -> VitestLaunchSettings.resolve(configuration));
    assertEquals("Bun was not found: install it, or set its location in Window > Preferences > EVitest.",
        message(missing));
    // The executable of the preferences.
    Installation installed = Installation.find(TestFramework.BUN, Fixtures.fixture(TestFramework.BUN), System.getenv(),
        null);
    Preferences.store().setValue(Preferences.BUN_PATH, installed.entry().getAbsolutePath());
    VitestLaunchSettings settings = VitestLaunchSettings.resolve(configuration);
    assertEquals(installed.entry(), settings.getInstallation().entry());
    assertEquals(project.getLocation().toFile(), settings.getRoot());
  }

  @Test
  void theArgumentsOfThePreferencesAreTheOnesOfVitest() throws CoreException {
    Preferences.store().setValue(Preferences.ARGUMENTS, "--bail=1");
    IProject vitest = TestWorkspace.createFixtureProject("arguments-vitest", TestFramework.VITEST);
    ILaunchConfigurationWorkingCopy configuration = configuration(vitest.getName(),
        "/" + vitest.getName() + "/" + Fixtures.mathFile(TestFramework.VITEST));
    configuration.setAttribute(VitestLaunchConstants.ATTR_ARGUMENTS, "--retry=2");
    assertEquals(List.of("--bail=1", "--retry=2"), VitestLaunchSettings.resolve(configuration).getArguments());
    configuration.setAttribute(VitestLaunchConstants.ATTR_FRAMEWORK, "node");
    assertEquals(List.of("--retry=2"), VitestLaunchSettings.resolve(configuration).getArguments());
  }

  @ParameterizedTest
  @EnumSource(value = TestFramework.class, names = { "PLAYWRIGHT", "BUN", "DENO" })
  void someFrameworksCannotBeDebugged(TestFramework framework) throws CoreException {
    IProject project = TestWorkspace.createFixtureProject("debug-" + framework.id(), framework);
    ILaunchConfigurationWorkingCopy configuration = configuration(project.getName(),
        "/" + project.getName() + "/" + Fixtures.mathFile(framework));
    CoreException refused = assertThrows(CoreException.class,
        () -> configuration.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor()));
    assertEquals("Debugging the " + framework.label() + " tests is not supported: run them, or debug them with the"
        + " tools of " + framework.label() + ".", message(refused));
  }

  @Test
  void theOtherFrameworksNeedWildWebDeveloperToBeDebugged() throws CoreException {
    IProject project = TestWorkspace.createFixtureProject("debug-jest", TestFramework.JEST);
    ILaunchConfigurationWorkingCopy configuration = configuration(project.getName(),
        "/" + project.getName() + "/" + Fixtures.mathFile(TestFramework.JEST));
    // Wild Web Developer is not in the tests.
    CoreException refused = assertThrows(CoreException.class,
        () -> configuration.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor()));
    assertTrue(message(refused).startsWith("Debugging the tests needs the Node.js debugger of Wild Web Developer"),
        message(refused));
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void runAsIsEnabledOnTheTestsOfAnInstalledFramework(TestFramework framework) throws CoreException {
    IProject project = TestWorkspace.createFixtureProject("tester-" + framework.id(), framework);
    VitestPropertyTester tester = new VitestPropertyTester();
    IFile math = project.getFile(Fixtures.mathFile(framework));
    assertTrue(math.exists());
    assertTrue(VitestPropertyTester.isTestFile(math));
    assertTrue(tester.test(math, "canLaunch", null, null));
    assertTrue(tester.test(math, "isTestFile", null, null));
    assertTrue(tester.test(project, "canLaunch", null, null));
    // Not a test file.
    IFile packageJson = project.getFile("package.json");
    if (packageJson.exists()) {
      assertFalse(VitestPropertyTester.isTestFile(packageJson));
    }
    assertFalse(tester.test(math, "unknown", null, null));
  }

  @Test
  void runAsIsDisabledWithoutAnInstalledFramework() throws CoreException {
    IProject project = TestWorkspace.createProject("not-installed");
    TestWorkspace.createFile(project, "package.json", "{ \"devDependencies\": { \"jest\": \"30.0.0\" } }");
    IFile test = TestWorkspace.createFile(project, "src/a.test.js", "test('x', () => {})");
    VitestPropertyTester tester = new VitestPropertyTester();
    assertFalse(tester.test(test, "canLaunch", null, null));
    assertFalse(tester.test(project, "canLaunch", null, null));
    assertFalse(tester.test(new Object(), "canLaunch", null, null));
    // The test files of the preferences.
    Preferences.store().setValue(Preferences.TEST_FILES, "*.check.js");
    IProject node = TestWorkspace.createProject("node-files");
    IFile check = TestWorkspace.createFile(node, "a.check.js", "import { test } from 'node:test'\n");
    IFile other = TestWorkspace.createFile(node, "a.test.js", "import { test } from 'node:test'\n");
    assertTrue(VitestPropertyTester.isTestFile(check));
    assertFalse(VitestPropertyTester.isTestFile(other));
  }

  @Test
  void bunNeedsItsExecutable() throws CoreException {
    assumeTrue(!NodeLocator.isWindows());
    IProject project = TestWorkspace.createProject("bun-tester");
    TestWorkspace.createFile(project, "bunfig.toml", "");
    IFile test = TestWorkspace.createFile(project, "a.test.ts", "import { test } from 'bun:test'\n");
    Preferences.store().setValue(Preferences.BUN_PATH, "/missing/bun");
    assertFalse(new VitestPropertyTester().test(test, "canLaunch", null, null));
  }
}
