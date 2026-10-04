package org.eclipse.evitest.launch;

import java.io.File;

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
 */
public class VitestPropertyTester extends PropertyTester {

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
    return Installation.find(framework, detection.root(), environment(), executable) != null;
  }

  private static java.util.Map<String, String> environment() {
    DebugPlugin debug = DebugPlugin.getDefault();
    return debug == null ? System.getenv() : debug.getLaunchManager().getNativeEnvironmentCasePreserved();
  }
}
