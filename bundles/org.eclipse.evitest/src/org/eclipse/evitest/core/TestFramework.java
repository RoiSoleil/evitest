package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The JavaScript test frameworks run by EVitest.
 * <p>
 * The order of the constants is the priority of the frameworks when several of them are configured in the same folder.
 */
public enum TestFramework {

  VITEST("vitest", "Vitest", "vitest", "vitest.mjs", //
      configs(List.of("vitest.config.", "vite.config.", "vitest.workspace.", "vitest.projects."),
          List.of("ts", "mts", "cts", "js", "mjs", "cjs", "json")),
      null, "(?:from|import|require\\s*\\()\\s*['\"]vitest['\"]"),
  JEST("jest", "Jest", "jest", "bin/jest.js", //
      configs(List.of("jest.config."), List.of("ts", "mts", "cts", "js", "mjs", "cjs", "json")), //
      "jest", "['\"]@jest/globals['\"]"),
  MOCHA("mocha", "Mocha", "mocha", "bin/mocha.js", //
      configs(List.of(".mocharc."), List.of("js", "cjs", "mjs", "yaml", "yml", "json", "jsonc")), //
      "mocha", null),
  JASMINE("jasmine", "Jasmine", "jasmine", "bin/jasmine.js", //
      configs(List.of("spec/support/jasmine."), List.of("json", "js", "mjs", "cjs")), //
      null, null),
  PLAYWRIGHT("playwright", "Playwright Test", "@playwright/test", "cli.js", //
      configs(List.of("playwright.config."), List.of("ts", "mts", "cts", "js", "mjs", "cjs")), //
      null, "['\"]@playwright/test['\"]"),
  DENO("deno", "Deno", null, null, List.of("deno.json", "deno.jsonc"), null,
      "\\bDeno\\.test\\b|['\"](?:jsr:)?@std/testing(?:/[\\w-]+)?['\"]"),
  BUN("bun", "Bun", null, null, List.of("bunfig.toml"), null, "['\"]bun:test['\"]"),
  NODE("node", "node:test", null, null, List.of(), null, "['\"]node:test['\"]");

  private final String id;
  private final String label;
  private final String packageName;
  private final String entry;
  private final List<String> configFiles;
  private final String packageJsonKey;
  private final Pattern importPattern;

  TestFramework(String id, String label, String packageName, String entry, List<String> configFiles,
      String packageJsonKey, String importRegex) {
    this.id = id;
    this.label = label;
    this.packageName = packageName;
    this.entry = entry;
    this.configFiles = configFiles;
    this.packageJsonKey = packageJsonKey;
    this.importPattern = importRegex == null ? null : Pattern.compile(importRegex);
  }

  private static List<String> configs(List<String> prefixes, List<String> extensions) {
    List<String> names = new ArrayList<>();
    for (String prefix : prefixes) {
      for (String extension : extensions) {
        names.add(prefix + extension);
      }
    }
    return List.copyOf(names);
  }

  /** The identifier of the framework in the launch configurations. */
  public String id() {
    return id;
  }

  /** The name shown to the user. */
  public String label() {
    return label;
  }

  /** The npm package installing the framework in node_modules, null for the runtimes (Node.js, Bun, Deno). */
  public String packageName() {
    return packageName;
  }

  /** The entry point of the command line of the framework in its package, null for the runtimes. */
  public String entry() {
    return entry;
  }

  /** The configuration files of the framework, relative to the folder where it runs. */
  public List<String> configFiles() {
    return configFiles;
  }

  /** The key of package.json configuring the framework, null if there is none. */
  public String packageJsonKey() {
    return packageJsonKey;
  }

  /** True if the source of a test file imports the framework (vitest, @jest/globals, node:test, bun:test...). */
  public boolean isImportedBy(String source) {
    return importPattern != null && source != null && importPattern.matcher(source).find();
  }

  /** True if the framework runs on Node.js (all but Bun and Deno). */
  public boolean usesNode() {
    return this != BUN && this != DENO;
  }

  /** True if the framework is installed in node_modules (all but the runtimes). */
  public boolean isPackage() {
    return packageName != null;
  }

  /** True if the tests can be debugged with the Node.js debugger of Wild Web Developer. */
  public boolean supportsDebug() {
    return this == VITEST || this == JEST || this == MOCHA || this == JASMINE || this == NODE;
  }

  /** True if the results arrive while the tests run; false for the frameworks read from a JUnit report at the end. */
  public boolean isLive() {
    return this != BUN && this != DENO;
  }

  /** True if one of the configuration files of the framework is in the folder. */
  public boolean isConfiguredIn(File folder) {
    for (String name : configFiles) {
      if (new File(folder, name).isFile()) {
        return true;
      }
    }
    return false;
  }

  /** The framework of an identifier, null if it is not one. */
  public static TestFramework fromId(String id) {
    for (TestFramework framework : values()) {
      if (framework.id.equals(id)) {
        return framework;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    return label;
  }
}
