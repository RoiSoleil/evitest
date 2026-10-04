package org.eclipse.evitest.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An installation of a test framework.
 *
 * @param framework the framework
 * @param entry the file to run: the entry point of the command line of the package with Node.js
 *          ({@code node_modules/jest/bin/jest.js}), or the executable of Bun or Deno; null for node:test
 * @param version the version of the framework (of Node.js for node:test), null if it is not known
 */
public record Installation(TestFramework framework, File entry, String version) {

  private static final Pattern MAJOR = Pattern.compile("^(\\d+)");

  /** The major version, 0 if it is not known. */
  public int major() {
    Matcher matcher = MAJOR.matcher(version == null ? "" : version);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  /** The installation of Vitest, as the command line of Vitest takes it. */
  public VitestLocator.Installation toVitest() {
    return new VitestLocator.Installation(entry, version);
  }

  /**
   * The installation of a framework for the tests of a root.
   * <ul>
   * <li>the npm packages: node_modules/&lt;package&gt; in the root or in one of its parents (the dependencies hoisted
   * in a monorepo);</li>
   * <li>Bun and Deno: the executable set by the user, else the one installed by npm in node_modules, else the one of
   * the PATH, else the one of their installer ({@code ~/.bun/bin}, {@code ~/.deno/bin});</li>
   * <li>node:test: Node.js, found by the launch.</li>
   * </ul>
   *
   * @param executable the executable of Bun or Deno set by the user, null or empty to search it
   * @return the installation, null if it is not found
   */
  public static Installation find(TestFramework framework, File root, Map<String, String> environment,
      String executable) {
    if (framework == TestFramework.VITEST) {
      VitestLocator.Installation vitest = VitestLocator.findInstallation(root);
      return vitest == null ? null : new Installation(framework, vitest.entry(), vitest.version());
    }
    if (framework.isPackage()) {
      for (File current = root; current != null; current = current.getParentFile()) {
        File folder = new File(current, "node_modules/" + framework.packageName());
        File entry = new File(folder, framework.entry());
        if (entry.isFile()) {
          return new Installation(framework, entry, packageVersion(folder));
        }
      }
      return null;
    }
    if (framework == TestFramework.NODE) {
      return new Installation(framework, null, null);
    }
    File file = findExecutable(framework, root, environment, executable);
    return file == null ? null : new Installation(framework, file, NodeLocator.version(file));
  }

  /** The executable of Bun or Deno, null if it is not found. */
  static File findExecutable(TestFramework framework, File root, Map<String, String> environment, String executable) {
    if (executable != null && !executable.isBlank()) {
      File file = new File(executable.trim());
      return file.isFile() ? file : null;
    }
    String name = framework.id();
    List<File> npm = new ArrayList<>();
    for (File current = root; current != null; current = current.getParentFile()) {
      // The executables installed by the npm packages "bun" and "deno".
      npm.add(new File(current, framework == TestFramework.BUN ? "node_modules/bun/bin" : "node_modules/deno"));
    }
    // After the PATH: the places of the installers of Bun and Deno.
    List<File> installers = new ArrayList<>();
    String install = environment.get(framework == TestFramework.BUN ? "BUN_INSTALL" : "DENO_INSTALL");
    if (install != null && !install.isBlank()) {
      installers.add(new File(install, "bin"));
    }
    installers.add(new File(System.getProperty("user.home"), "." + name + "/bin"));
    return NodeLocator.findExecutable(name, environment, npm, installers);
  }

  private static String packageVersion(File folder) {
    File packageJson = new File(folder, "package.json");
    if (!packageJson.isFile()) {
      return null;
    }
    try {
      return Json.getString(Json.parseObject(Files.readString(packageJson.toPath(), StandardCharsets.UTF_8)), "version");
    } catch (IOException e) {
      return null;
    }
  }
}
