package org.eclipse.evitest.launch;

import org.eclipse.evitest.Activator;

/** The identifiers and the attributes of the launch configurations of tests. */
public final class VitestLaunchConstants {

  public static final String LAUNCH_CONFIGURATION_TYPE = Activator.PLUGIN_ID + ".launchConfigurationType";

  /** The identifier of the support of the Unit Test view (the unittestViewSupport extension). */
  public static final String VIEW_SUPPORT = Activator.PLUGIN_ID + ".viewSupport";

  /**
   * The test framework ({@link org.eclipse.evitest.core.TestFramework#id()}: vitest, jest, mocha...), empty to detect
   * it from the tests.
   */
  public static final String ATTR_FRAMEWORK = Activator.PLUGIN_ID + ".framework";
  /** The project of the tests. */
  public static final String ATTR_PROJECT = Activator.PLUGIN_ID + ".project";
  /** The folder where the tests run (variables allowed), empty to find it from the tests. */
  public static final String ATTR_ROOT = Activator.PLUGIN_ID + ".root";
  /** The files and folders to run (list of workspace paths, or file system paths), all the tests of the root if none. */
  public static final String ATTR_PATHS = Activator.PLUGIN_ID + ".paths";
  /** The tests and suites to run (list of {@link org.eclipse.evitest.core.TestSelector} in JSON). */
  public static final String ATTR_SELECTORS = Activator.PLUGIN_ID + ".selectors";
  /** A regular expression of the user on the full names of the tests (used when there is no selector). */
  public static final String ATTR_NAME_PATTERN = Activator.PLUGIN_ID + ".namePattern";
  /** The Node.js executable, empty for the one of the preferences. */
  public static final String ATTR_NODE = Activator.PLUGIN_ID + ".node";
  /** Additional arguments of Vitest (variables allowed). */
  public static final String ATTR_ARGUMENTS = Activator.PLUGIN_ID + ".arguments";
  /** Updates the snapshots ({@code --update}). */
  public static final String ATTR_UPDATE_SNAPSHOTS = Activator.PLUGIN_ID + ".updateSnapshots";

  /** Attribute of the launches: the port where Eclipse receives the events of the reporter. */
  public static final String LAUNCH_PORT = Activator.PLUGIN_ID + ".port";
  /** Attribute of the launches: the folder where the tests run. */
  public static final String LAUNCH_ROOT = Activator.PLUGIN_ID + ".rootFolder";
  /** Attribute of the launches: the test framework. */
  public static final String LAUNCH_FRAMEWORK = Activator.PLUGIN_ID + ".frameworkId";

  /** The type of the processes running the tests (all the frameworks: the links of their consoles are the same). */
  public static final String PROCESS_TYPE = "vitest";

  private VitestLaunchConstants() {
  }
}
