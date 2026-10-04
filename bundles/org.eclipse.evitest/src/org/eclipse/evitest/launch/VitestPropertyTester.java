package org.eclipse.evitest.launch;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.FrameworkDetector;
import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.TestFramework;

/**
 * The properties of the resources for the enablement of the launch shortcut and of the code minings:
 * <ul>
 * <li>{@code org.eclipse.evitest.isTestFile}: a test file (its name matches the preferences) whose test framework is
 * installed;</li>
 * <li>{@code org.eclipse.evitest.canLaunch}: a test file, or a folder or a project whose test framework is
 * installed.</li>
 * </ul>
 * The properties are tested in the UI thread, many times for each menu (each item of the selection, Run As, Debug
 * As...): they only look at files, never run a process, and their results are kept a few seconds.
 */
public class VitestPropertyTester extends PropertyTester {

  /** How long a result is kept: the files are read again for the next menus, after an npm install for example. */
  private static final long CACHE_MILLIS = 3000;
  private static final int CACHE_SIZE = 256;

  private record Result(boolean hasTests, long time) {
  }

  private static final Map<File, Result> CACHE = new ConcurrentHashMap<>();

  @Override
  public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
    IResource resource = Adapters.adapt(receiver, IResource.class);
    if (resource == null || resource.getLocation() == null) {
      return false;
    }
    return switch (property) {
      case "isTestFile" -> isTestFile(resource);
      case "canLaunch" -> resource instanceof IFile ? isTestFile(resource) : hasTests(resource.getLocation().toFile());
      default -> false;
    };
  }

  public static boolean isTestFile(IResource resource) {
    return resource instanceof IFile && Preferences.testFilePatterns().matches(resource.getName())
        && hasTests(resource.getLocation().toFile());
  }

  /** True if a test framework is installed for the file or the folder. */
  private static boolean hasTests(File location) {
    long now = System.currentTimeMillis();
    Result cached = CACHE.get(location);
    if (cached != null && now - cached.time() < CACHE_MILLIS) {
      return cached.hasTests();
    }
    boolean hasTests = findTests(location);
    if (CACHE.size() >= CACHE_SIZE) {
      CACHE.clear();
    }
    CACHE.put(location, new Result(hasTests, now));
    return hasTests;
  }

  private static boolean findTests(File location) {
    FrameworkDetector.Detection detection = FrameworkDetector.detect(location);
    if (detection == null) {
      return false;
    }
    TestFramework framework = detection.framework();
    if (framework == TestFramework.NODE) {
      // Node.js is checked by the launch.
      return true;
    }
    String executable = switch (framework) {
      case BUN -> Preferences.getString(Preferences.BUN_PATH);
      case DENO -> Preferences.getString(Preferences.DENO_PATH);
      default -> "";
    };
    // Not Installation.find: it runs Bun and Deno for their version.
    return Installation.isInstalled(framework, detection.root(), environment(), executable);
  }

  private static Map<String, String> environment() {
    DebugPlugin debug = DebugPlugin.getDefault();
    return debug == null ? System.getenv() : debug.getLaunchManager().getNativeEnvironmentCasePreserved();
  }
}
