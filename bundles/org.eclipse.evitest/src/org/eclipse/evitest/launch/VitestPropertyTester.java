package org.eclipse.evitest.launch;

import java.io.File;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.VitestLocator;

/**
 * The properties of the resources for the enablement of the launch shortcut and of the code minings:
 * <ul>
 * <li>{@code org.eclipse.evitest.isTestFile}: a test file (its name matches the preferences) of a project using
 * Vitest;</li>
 * <li>{@code org.eclipse.evitest.canLaunch}: a test file, or a folder or a project using Vitest.</li>
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
      case "canLaunch" -> resource instanceof IFile ? isTestFile(resource) : usesVitest(resource.getLocation().toFile());
      default -> false;
    };
  }

  public static boolean isTestFile(IResource resource) {
    return resource instanceof IFile && Preferences.testFilePatterns().matches(resource.getName())
        && usesVitest(resource.getLocation().toFile());
  }

  /** True if Vitest is installed for the file or the folder. */
  private static boolean usesVitest(File location) {
    File root = VitestLocator.findRoot(location);
    return root != null && VitestLocator.findInstallation(root) != null;
  }
}
