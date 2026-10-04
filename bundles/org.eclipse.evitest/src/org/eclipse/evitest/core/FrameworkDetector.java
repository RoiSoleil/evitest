package org.eclipse.evitest.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds the test framework of a test file, a folder or a project, and the folder where it runs.
 * <p>
 * In this order:
 * <ol>
 * <li>a test file importing its framework ({@code vitest}, {@code @jest/globals}, {@code node:test}, {@code bun:test},
 * {@code @playwright/test}, {@code Deno.test}) runs with it, in the nearest folder configuring it, else in the nearest
 * folder with a package.json;</li>
 * <li>else the nearest folder with the configuration file of a framework ({@code vitest.config.ts},
 * {@code jest.config.js}, {@code .mocharc.yml}, {@code spec/support/jasmine.json}, {@code playwright.config.ts},
 * {@code deno.json}, {@code bunfig.toml});</li>
 * <li>else the nearest package.json depending on a framework, or configuring it ({@code "jest"}, {@code "mocha"}), or
 * whose test script runs it ({@code "test": "node --test"}, {@code "bun test"}, {@code "deno test"});</li>
 * <li>else the nearest folder with the lock file of Bun.</li>
 * </ol>
 */
public final class FrameworkDetector {

  private static final Pattern TEST_SCRIPT_NODE = Pattern.compile("(?:^|[\\s;&|(])node\\b[^;&|]*\\s--test\\b");
  private static final Pattern TEST_SCRIPT_BUN = Pattern.compile("(?:^|[\\s;&|(])bun\\s+test\\b");
  private static final Pattern TEST_SCRIPT_DENO = Pattern.compile("(?:^|[\\s;&|(])deno\\s+test\\b");

  /** The beginning of the test files read for their imports. */
  private static final int SOURCE_LIMIT = 64 * 1024;

  /**
   * The framework of tests and the folder where it runs them.
   *
   * @param framework the test framework
   * @param root the folder where it runs: the one of its configuration or of the package.json
   */
  public record Detection(TestFramework framework, File root) {
  }

  private FrameworkDetector() {
  }

  /** The framework of a test file or of a folder, null if none is found. */
  public static Detection detect(File location) {
    File folder = location.isDirectory() ? location : location.getParentFile();
    if (folder == null) {
      return null;
    }
    if (location.isFile()) {
      TestFramework imported = importedFramework(readStart(location));
      if (imported != null) {
        return new Detection(imported, findRoot(imported, location));
      }
    }
    for (File current = folder; current != null; current = current.getParentFile()) {
      for (TestFramework framework : TestFramework.values()) {
        if (framework.isConfiguredIn(current)) {
          return new Detection(framework, current);
        }
      }
    }
    for (File current = folder; current != null; current = current.getParentFile()) {
      TestFramework framework = packageJsonFramework(new File(current, "package.json"));
      if (framework != null) {
        return new Detection(framework, current);
      }
    }
    for (File current = folder; current != null; current = current.getParentFile()) {
      if (new File(current, "bun.lock").isFile() || new File(current, "bun.lockb").isFile()) {
        return new Detection(TestFramework.BUN, current);
      }
    }
    return null;
  }

  /** The framework imported by the source of a test file, null if it imports none. */
  public static TestFramework importedFramework(String source) {
    if (source == null) {
      return null;
    }
    for (TestFramework framework : TestFramework.values()) {
      if (framework.isImportedBy(source)) {
        return framework;
      }
    }
    return null;
  }

  /**
   * The folder where a framework runs the tests of a file or a folder: the nearest folder configuring the framework,
   * else the nearest package.json depending on it, else the nearest package.json, else the folder of the tests.
   */
  public static File findRoot(TestFramework framework, File location) {
    File folder = location.isDirectory() ? location : location.getParentFile();
    if (folder == null) {
      return null;
    }
    for (File current = folder; current != null; current = current.getParentFile()) {
      if (framework.isConfiguredIn(current)) {
        return current;
      }
    }
    File firstPackage = null;
    for (File current = folder; current != null; current = current.getParentFile()) {
      File packageJson = new File(current, "package.json");
      if (packageJson.isFile()) {
        if (dependsOn(packageJson, framework)) {
          return current;
        }
        if (firstPackage == null) {
          firstPackage = current;
        }
      }
    }
    return firstPackage != null ? firstPackage : folder;
  }

  /** The framework a package.json depends on, configures or runs in its test script, null if there is none. */
  public static TestFramework packageJsonFramework(File packageJson) {
    if (!packageJson.isFile()) {
      return null;
    }
    Map<String, Object> object = readJson(packageJson);
    for (TestFramework framework : TestFramework.values()) {
      if (dependsOn(object, framework)) {
        return framework;
      }
    }
    return testScriptFramework(object);
  }

  /** The runtime run by the test script of package.json: node --test, bun test, deno test; null if there is none. */
  private static TestFramework testScriptFramework(Map<String, Object> packageJson) {
    if (!(packageJson.get("scripts") instanceof Map<?, ?> scripts) || !(scripts.get("test") instanceof String script)) {
      return null;
    }
    if (TEST_SCRIPT_NODE.matcher(script).find()) {
      return TestFramework.NODE;
    }
    if (TEST_SCRIPT_BUN.matcher(script).find()) {
      return TestFramework.BUN;
    }
    if (TEST_SCRIPT_DENO.matcher(script).find()) {
      return TestFramework.DENO;
    }
    return null;
  }

  /** True if the package.json depends on the framework, or configures it. */
  public static boolean dependsOn(File packageJson, TestFramework framework) {
    return packageJson.isFile() && dependsOn(readJson(packageJson), framework);
  }

  private static boolean dependsOn(Map<String, Object> packageJson, TestFramework framework) {
    if (framework.packageJsonKey() != null && packageJson.containsKey(framework.packageJsonKey())) {
      return true;
    }
    if (framework.packageName() == null) {
      return false;
    }
    for (String key : new String[] { "devDependencies", "dependencies", "peerDependencies", "optionalDependencies" }) {
      if (packageJson.get(key) instanceof Map<?, ?> dependencies && dependencies.containsKey(framework.packageName())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> readJson(File file) {
    try {
      return Json.parseObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      return Map.of();
    }
  }

  /** The beginning of a file, null if it cannot be read. */
  static String readStart(File file) {
    try (InputStream input = Files.newInputStream(file.toPath())) {
      return new String(input.readNBytes(SOURCE_LIMIT), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }
}
