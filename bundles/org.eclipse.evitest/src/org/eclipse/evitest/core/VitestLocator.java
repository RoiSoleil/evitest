package org.eclipse.evitest.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the root of a Vitest project (the folder where Vitest runs) and the installation of Vitest used there.
 */
public final class VitestLocator {

  private static final List<String> CONFIG_PREFIXES = List.of("vitest.config.", "vite.config.", "vitest.workspace.",
      "vitest.projects.");
  private static final List<String> CONFIG_EXTENSIONS = List.of("ts", "mts", "cts", "js", "mjs", "cjs", "json");
  private static final Pattern DEPENDENCY = Pattern.compile("\"vitest\"\\s*:");

  /** An installation of Vitest. */
  public record Installation(File entry, String version) {

    /** The major version, 0 if it is not known. */
    public int major() {
      Matcher matcher = Pattern.compile("^(\\d+)").matcher(version == null ? "" : version);
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
  }

  private VitestLocator() {
  }

  /**
   * The root of the Vitest project of a file or a folder: the nearest folder with a configuration file of Vitest or
   * Vite, or else the nearest folder with a package.json depending on Vitest, or else the nearest folder with a
   * package.json. Null if there is none.
   */
  public static File findRoot(File start) {
    File folder = start.isDirectory() ? start : start.getParentFile();
    for (File current = folder; current != null; current = current.getParentFile()) {
      if (hasConfig(current)) {
        return current;
      }
    }
    File firstPackage = null;
    for (File current = folder; current != null; current = current.getParentFile()) {
      File packageJson = new File(current, "package.json");
      if (packageJson.isFile()) {
        if (dependsOnVitest(packageJson)) {
          return current;
        }
        if (firstPackage == null) {
          firstPackage = current;
        }
      }
    }
    return firstPackage;
  }

  public static boolean hasConfig(File folder) {
    String[] names = folder.list();
    if (names == null) {
      return false;
    }
    for (String name : names) {
      for (String prefix : CONFIG_PREFIXES) {
        if (name.startsWith(prefix) && CONFIG_EXTENSIONS.contains(name.substring(prefix.length()))) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean dependsOnVitest(File packageJson) {
    try {
      String content = Files.readString(packageJson.toPath(), StandardCharsets.UTF_8);
      return DEPENDENCY.matcher(content).find();
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * The installation of Vitest used in the root: node_modules/vitest in the root or in one of its parents (the
   * dependencies hoisted in a monorepo). Null if there is none.
   */
  public static Installation findInstallation(File root) {
    for (File current = root; current != null; current = current.getParentFile()) {
      File vitest = new File(current, "node_modules/vitest");
      Installation installation = installation(vitest);
      if (installation != null) {
        return installation;
      }
    }
    return null;
  }

  /**
   * The installation of Vitest in a folder (the vitest package) or of an entry file (vitest.mjs), null if it is not
   * one.
   */
  public static Installation installation(File file) {
    File entry;
    File folder;
    if (file.isFile()) {
      entry = file;
      folder = file.getParentFile();
    } else {
      folder = file;
      entry = new File(file, "vitest.mjs");
    }
    if (!entry.isFile()) {
      return null;
    }
    String version = null;
    File packageJson = new File(folder, "package.json");
    if (packageJson.isFile()) {
      try {
        Map<String, Object> object = Json.parseObject(Files.readString(packageJson.toPath(), StandardCharsets.UTF_8));
        version = Json.getString(object, "version");
      } catch (IOException e) {
        // Unknown version.
      }
    }
    return new Installation(entry, version);
  }
}
