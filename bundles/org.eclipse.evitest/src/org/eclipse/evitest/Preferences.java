package org.eclipse.evitest;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.evitest.core.TestFilePatterns;
import org.eclipse.jface.preference.IPreferenceStore;

/** The preferences of EVitest (Window > Preferences > EVitest), and their default values. */
public class Preferences extends AbstractPreferenceInitializer {

  /** The Node.js executable, empty to search it. */
  public static final String NODE_PATH = "nodePath";
  /** The names of the test files, globs separated by commas. */
  public static final String TEST_FILES = "testFiles";
  /** Additional arguments of Vitest for all the launches. */
  public static final String ARGUMENTS = "arguments";
  /** Colors in the console (FORCE_COLOR). */
  public static final String COLORS = "colors";
  /** "Run | Debug" above the tests in the editors. */
  public static final String CODE_MININGS = "codeMinings";

  @Override
  public void initializeDefaultPreferences() {
    IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    defaults.put(NODE_PATH, "");
    defaults.put(TEST_FILES, TestFilePatterns.DEFAULT);
    defaults.put(ARGUMENTS, "");
    defaults.putBoolean(COLORS, true);
    defaults.putBoolean(CODE_MININGS, true);
  }

  public static IPreferenceStore store() {
    return Activator.getDefault().getPreferenceStore();
  }

  public static String getString(String key) {
    return Platform.getPreferencesService().getString(Activator.PLUGIN_ID, key, "", null);
  }

  public static boolean getBoolean(String key) {
    return Platform.getPreferencesService().getBoolean(Activator.PLUGIN_ID, key, false, null);
  }

  public static TestFilePatterns testFilePatterns() {
    return new TestFilePatterns(getString(TEST_FILES));
  }
}
