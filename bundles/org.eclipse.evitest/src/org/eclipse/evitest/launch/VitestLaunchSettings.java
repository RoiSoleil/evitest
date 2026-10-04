package org.eclipse.evitest.launch;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.FrameworkDetector;
import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;

/**
 * The values of a launch configuration of tests, resolved: files, framework, root, installation of the framework,
 * Node.js...
 */
public final class VitestLaunchSettings {

  private final File root;
  private final Installation installation;
  private final File node;
  private final List<String> filters;
  private final List<TestSelector> selectors;
  private final String namePattern;
  private final boolean updateSnapshots;
  private final List<String> arguments;
  private final Map<String, String> environment;

  private VitestLaunchSettings(File root, Installation installation, File node, List<String> filters,
      List<TestSelector> selectors, String namePattern, boolean updateSnapshots, List<String> arguments,
      Map<String, String> environment) {
    this.root = root;
    this.installation = installation;
    this.node = node;
    this.filters = filters;
    this.selectors = selectors;
    this.namePattern = namePattern;
    this.updateSnapshots = updateSnapshots;
    this.arguments = arguments;
    this.environment = environment;
  }

  public File getRoot() {
    return root;
  }

  public Installation getInstallation() {
    return installation;
  }

  public TestFramework getFramework() {
    return installation.framework();
  }

  /** Node.js, null for the frameworks which do not run on it (Bun, Deno). */
  public File getNode() {
    return node;
  }

  /** The files and folders to run, relative to the root. */
  public List<String> getFilters() {
    return filters;
  }

  public List<TestSelector> getSelectors() {
    return selectors;
  }

  public String getNamePattern() {
    return namePattern;
  }

  public boolean isUpdateSnapshots() {
    return updateSnapshots;
  }

  public List<String> getArguments() {
    return arguments;
  }

  /** The environment of the tests: the one of Eclipse with the variables of the configuration. */
  public Map<String, String> getEnvironment() {
    return environment;
  }

  public static VitestLaunchSettings resolve(ILaunchConfiguration configuration) throws CoreException {
    List<File> locations = new ArrayList<>();
    for (String path : configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, Collections.emptyList())) {
      File location = toFile(path);
      if (location == null || !location.exists()) {
        throw new CoreException(Activator.error("The test file or folder " + path + " does not exist.", null));
      }
      locations.add(location);
    }
    IProject project = getProject(configuration);

    TestFramework chosen = TestFramework.fromId(configuration.getAttribute(VitestLaunchConstants.ATTR_FRAMEWORK, ""));
    File root;
    TestFramework framework;
    String rootAttribute = substitute(configuration.getAttribute(VitestLaunchConstants.ATTR_ROOT, "")).trim();
    if (!rootAttribute.isEmpty()) {
      root = new File(rootAttribute);
      if (!root.isDirectory()) {
        throw new CoreException(Activator.error("The folder of the tests " + root + " does not exist.", null));
      }
      framework = chosen;
      if (framework == null) {
        FrameworkDetector.Detection detection = FrameworkDetector.detect(!locations.isEmpty() ? locations.get(0) : root);
        framework = detection == null ? null : detection.framework();
      }
      if (framework == null) {
        throw new CoreException(Activator.error(noFramework(root), null));
      }
    } else {
      File start = !locations.isEmpty() ? locations.get(0)
          : project != null && project.getLocation() != null ? project.getLocation().toFile() : null;
      if (start == null) {
        throw new CoreException(Activator.error("Choose the project or the tests to run.", null));
      }
      if (chosen != null) {
        framework = chosen;
        root = FrameworkDetector.findRoot(chosen, start);
      } else {
        FrameworkDetector.Detection detection = FrameworkDetector.detect(start);
        if (detection == null) {
          throw new CoreException(Activator.error(noFramework(start), null));
        }
        framework = detection.framework();
        root = detection.root();
      }
    }

    Map<String, String> environment = environment(configuration);

    Installation installation = Installation.find(framework, root, environment, executablePreference(framework));
    if (installation == null) {
      throw new CoreException(Activator.error(notInstalled(framework, root), null));
    }

    File node = null;
    if (framework.usesNode()) {
      String nodeAttribute = substitute(configuration.getAttribute(VitestLaunchConstants.ATTR_NODE, "")).trim();
      if (nodeAttribute.isEmpty()) {
        nodeAttribute = substitute(Preferences.getString(Preferences.NODE_PATH)).trim();
      }
      if (!nodeAttribute.isEmpty()) {
        node = new File(nodeAttribute);
        if (!node.isFile()) {
          throw new CoreException(Activator.error("Node.js " + node + " does not exist.", null));
        }
      } else {
        node = NodeLocator.find(environment);
        if (node == null) {
          throw new CoreException(Activator.error(
              "Node.js was not found: set its location in Window > Preferences > EVitest.", null));
        }
      }
      if (framework == TestFramework.NODE) {
        installation = new Installation(framework, null, NodeLocator.version(node));
      }
    }

    List<String> filters = new ArrayList<>();
    Path rootPath = root.toPath().toAbsolutePath().normalize();
    for (File location : locations) {
      Path path = location.toPath().toAbsolutePath().normalize();
      if (path.equals(rootPath)) {
        // All the tests of the root.
        filters.clear();
        break;
      }
      // A file outside the root (a shared folder): its absolute path.
      filters.add(path.startsWith(rootPath) ? rootPath.relativize(path).toString().replace('\\', '/')
          : path.toString().replace('\\', '/'));
    }

    List<TestSelector> selectors = new ArrayList<>();
    for (String json : configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, Collections.emptyList())) {
      TestSelector selector = TestSelector.fromJson(json);
      if (selector != null) {
        selectors.add(selector);
      }
    }

    List<String> arguments = new ArrayList<>();
    if (framework == TestFramework.VITEST) {
      // The additional arguments of the preferences are the ones of Vitest.
      arguments.addAll(Arrays.asList(DebugPlugin.parseArguments(substitute(Preferences.getString(Preferences.ARGUMENTS)))));
    }
    arguments.addAll(Arrays.asList(
        DebugPlugin.parseArguments(substitute(configuration.getAttribute(VitestLaunchConstants.ATTR_ARGUMENTS, "")))));

    return new VitestLaunchSettings(root, installation, node, filters, selectors,
        configuration.getAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, ""),
        configuration.getAttribute(VitestLaunchConstants.ATTR_UPDATE_SNAPSHOTS, false), arguments, environment);
  }

  private static String noFramework(File location) {
    return "No test framework found for " + location + ": install Vitest, Jest, Mocha, Jasmine or Playwright Test"
        + " (npm install), or choose the framework of the launch configuration (Run > Run Configurations...).";
  }

  private static String notInstalled(TestFramework framework, File root) {
    if (framework.isPackage()) {
      return framework.label() + " is not installed in " + root + " (node_modules/" + framework.packageName()
          + " is missing): run npm install, pnpm install or yarn there.";
    }
    return framework.label() + " was not found: install it, or set its location in Window > Preferences > EVitest.";
  }

  /** The executable of Bun or Deno set in the preferences, empty to search it. */
  private static String executablePreference(TestFramework framework) throws CoreException {
    return switch (framework) {
      case BUN -> substitute(Preferences.getString(Preferences.BUN_PATH)).trim();
      case DENO -> substitute(Preferences.getString(Preferences.DENO_PATH)).trim();
      default -> "";
    };
  }

  public static IProject getProject(ILaunchConfiguration configuration) throws CoreException {
    String name = configuration.getAttribute(VitestLaunchConstants.ATTR_PROJECT, "");
    if (name.isEmpty() || !ResourcesPlugin.getWorkspace().validateName(name, IResource.PROJECT).isOK()) {
      return null;
    }
    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
    return project.exists() ? project : null;
  }

  /**
   * The location of a path of the launch configurations: a workspace path (also of a file not refreshed yet in its
   * project), or else a file system path.
   */
  public static File toFile(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
    IPath workspacePath = IPath.fromPortableString(path);
    IResource resource = workspaceRoot.findMember(workspacePath);
    if (resource != null && resource.getLocation() != null) {
      return resource.getLocation().toFile();
    }
    if (workspacePath.segmentCount() > 1) {
      // A file of a project which is not refreshed yet (created outside of Eclipse): the location of the project.
      IProject project = workspaceRoot.getProject(workspacePath.segment(0));
      if (project.isOpen() && project.getLocation() != null) {
        File file = project.getLocation().append(workspacePath.removeFirstSegments(1)).toFile();
        if (file.exists()) {
          return file;
        }
      }
    }
    return new File(path);
  }

  /** The path of a resource in the launch configurations. */
  public static String toPath(IResource resource) {
    return resource.getFullPath().toPortableString();
  }

  private static Map<String, String> environment(ILaunchConfiguration configuration) throws CoreException {
    ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
    Map<String, String> environment = new LinkedHashMap<>();
    String[] variables = manager.getEnvironment(configuration);
    if (variables == null) {
      environment.putAll(manager.getNativeEnvironmentCasePreserved());
    } else {
      for (String variable : variables) {
        int equals = variable.indexOf('=');
        if (equals > 0) {
          environment.put(variable.substring(0, equals), variable.substring(equals + 1));
        }
      }
    }
    return environment;
  }

  private static String substitute(String text) throws CoreException {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(text);
  }
}
