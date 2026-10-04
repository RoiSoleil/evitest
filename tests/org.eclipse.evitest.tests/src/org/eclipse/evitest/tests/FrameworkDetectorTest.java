package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.evitest.core.FrameworkDetector;
import org.eclipse.evitest.core.FrameworkDetector.Detection;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.frameworks.FrameworkSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The test frameworks, and how they are found from the tests. */
class FrameworkDetectorTest {

  private Path root;

  @BeforeEach
  void createRoot() throws IOException {
    root = Files.createTempDirectory("evitest-detector").toRealPath();
  }

  @AfterEach
  void deleteRoot() throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  private File write(String path, String content) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file.toFile();
  }

  private File folder(String path) throws IOException {
    return Files.createDirectories(root.resolve(path)).toFile();
  }

  private static void assertDetection(TestFramework framework, File root, Detection detection) {
    assertNotNull(detection);
    assertEquals(framework, detection.framework());
    assertEquals(root, detection.root());
  }

  @Test
  void frameworksHaveDistinctIdentifiersAndLabels() {
    Set<String> ids = new HashSet<>();
    Set<String> labels = new HashSet<>();
    for (TestFramework framework : TestFramework.values()) {
      assertTrue(ids.add(framework.id()), framework.id());
      assertTrue(labels.add(framework.label()), framework.label());
      assertEquals(framework, TestFramework.fromId(framework.id()));
      assertEquals(framework.label(), framework.toString());
      assertEquals(framework.isPackage(), framework.packageName() != null);
      assertEquals(framework.isPackage(), framework.entry() != null);
      // Each framework has its command line, and the packages their command in package.json.
      assertNotNull(FrameworkSupport.of(framework), framework.label());
      assertEquals(framework.isPackage(), framework.command() != null);
    }
    assertNull(TestFramework.fromId("karma"));
    assertNull(TestFramework.fromId(""));
    assertNull(TestFramework.fromId(null));
  }

  @Test
  void capabilitiesOfTheFrameworks() {
    assertFalse(TestFramework.BUN.usesNode());
    assertFalse(TestFramework.DENO.usesNode());
    assertTrue(TestFramework.NODE.usesNode());
    assertFalse(TestFramework.NODE.isPackage());
    assertFalse(TestFramework.BUN.isLive());
    assertFalse(TestFramework.DENO.isLive());
    assertTrue(TestFramework.PLAYWRIGHT.isLive());
    for (TestFramework framework : new TestFramework[] { TestFramework.VITEST, TestFramework.JEST, TestFramework.MOCHA,
        TestFramework.JASMINE, TestFramework.NODE }) {
      assertTrue(framework.supportsDebug(), framework.label());
    }
    for (TestFramework framework : new TestFramework[] { TestFramework.PLAYWRIGHT, TestFramework.BUN,
        TestFramework.DENO }) {
      assertFalse(framework.supportsDebug(), framework.label());
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = { //
      "import { it } from 'vitest'|VITEST", //
      "import { describe, it } from \"vitest\"|VITEST", //
      "const { it } = require('vitest')|VITEST", //
      "import 'vitest'|VITEST", //
      "import { jest, it } from '@jest/globals'|JEST", //
      "import { test } from 'node:test'|NODE", //
      "const test = require('node:test')|NODE", //
      "import { test, expect } from 'bun:test'|BUN", //
      "import { test, expect } from '@playwright/test'|PLAYWRIGHT", //
      "Deno.test('x', () => {})|DENO", //
      "Deno.test.ignore('x', () => {})|DENO", //
      "import { describe, it } from 'jsr:@std/testing/bdd'|DENO", //
      "import { describe } from '@std/testing/bdd'|DENO" })
  void findsTheFrameworkImportedByATestFile(String source, TestFramework framework) {
    assertEquals(framework, FrameworkDetector.importedFramework(source));
    assertTrue(framework.isImportedBy(source));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = { //
      "describe('math', () => {})", //
      "import { expect } from 'chai'", //
      "import vitestConfig from './vitest-config'", //
      "import { test } from './node:test-helpers.js'", //
      "// it('x') with vitest", //
      "const Denotest = 1" })
  void ignoresTheSourcesWhichDoNotImportAFramework(String source) {
    assertNull(FrameworkDetector.importedFramework(source));
  }

  @Test
  void anImportedFrameworkWins() throws IOException {
    write("package.json", "{ \"devDependencies\": { \"vitest\": \"5.0.0\", \"@playwright/test\": \"1.0.0\" } }");
    write("vitest.config.ts", "export default {}");
    write("e2e/playwright.config.ts", "export default {}");
    File unit = write("src/math.test.ts", "import { it } from 'vitest'\nit('x', () => {})\n");
    File e2e = write("e2e/tests/home.spec.ts", "import { test } from '@playwright/test'\ntest('x', () => {})\n");
    File node = write("src/node.test.js", "import { test } from 'node:test'\n");
    assertDetection(TestFramework.VITEST, root.toFile(), FrameworkDetector.detect(unit));
    assertDetection(TestFramework.PLAYWRIGHT, root.resolve("e2e").toFile(), FrameworkDetector.detect(e2e));
    // node:test has no configuration: the folder of package.json.
    assertDetection(TestFramework.NODE, root.toFile(), FrameworkDetector.detect(node));
    // A folder has no imports: the configuration of the folder.
    assertDetection(TestFramework.PLAYWRIGHT, root.resolve("e2e").toFile(), FrameworkDetector.detect(root.resolve("e2e/tests").toFile()));
    assertDetection(TestFramework.VITEST, root.toFile(), FrameworkDetector.detect(root.resolve("src").toFile()));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = { //
      "vitest.config.mts|VITEST", //
      "vite.config.js|VITEST", //
      "vitest.workspace.json|VITEST", //
      "jest.config.cjs|JEST", //
      "jest.config.ts|JEST", //
      ".mocharc.yml|MOCHA", //
      ".mocharc.json|MOCHA", //
      "spec/support/jasmine.json|JASMINE", //
      "spec/support/jasmine.mjs|JASMINE", //
      "playwright.config.ts|PLAYWRIGHT", //
      "deno.json|DENO", //
      "deno.jsonc|DENO", //
      "bunfig.toml|BUN" })
  void findsTheConfigurationOfAFramework(String config, TestFramework framework) throws IOException {
    write("project/" + config, "");
    File test = write("project/src/deep/a.test.js", "it('x', () => {})\n");
    File project = root.resolve("project").toFile();
    assertTrue(framework.isConfiguredIn(project));
    assertDetection(framework, project, FrameworkDetector.detect(test));
    assertDetection(framework, project, FrameworkDetector.detect(project));
    assertEquals(project, FrameworkDetector.findRoot(framework, test));
  }

  @Test
  void theNearestConfigurationWins() throws IOException {
    write("jest.config.js", "");
    write("packages/app/vitest.config.ts", "");
    File app = write("packages/app/src/a.test.ts", "it('x', () => {})");
    File lib = write("packages/lib/src/a.test.ts", "it('x', () => {})");
    assertDetection(TestFramework.VITEST, root.resolve("packages/app").toFile(), FrameworkDetector.detect(app));
    assertDetection(TestFramework.JEST, root.toFile(), FrameworkDetector.detect(lib));
  }

  @Test
  void severalConfigurationsInAFolderFollowThePriorityOfTheFrameworks() throws IOException {
    write("playwright.config.ts", "");
    write("jest.config.js", "");
    write(".mocharc.yml", "");
    assertDetection(TestFramework.JEST, root.toFile(), FrameworkDetector.detect(root.toFile()));
    write("vite.config.ts", "");
    assertDetection(TestFramework.VITEST, root.toFile(), FrameworkDetector.detect(root.toFile()));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = { //
      "{ \"devDependencies\": { \"vitest\": \"^5\" } }|VITEST", //
      "{ \"devDependencies\": { \"jest\": \"^30\" } }|JEST", //
      "{ \"dependencies\": { \"mocha\": \"^12\" } }|MOCHA", //
      "{ \"devDependencies\": { \"jasmine\": \"^7\" } }|JASMINE", //
      "{ \"devDependencies\": { \"@playwright/test\": \"^1\" } }|PLAYWRIGHT", //
      "{ \"jest\": { \"testEnvironment\": \"node\" } }|JEST", //
      "{ \"mocha\": { \"spec\": \"test\" } }|MOCHA", //
      "{ \"peerDependencies\": { \"jest\": \"*\" } }|JEST", //
      "{ \"devDependencies\": { \"jest\": \"^30\", \"vitest\": \"^5\" } }|VITEST", //
      "{ \"scripts\": { \"test\": \"node --test\" } }|NODE", //
      "{ \"scripts\": { \"test\": \"node --experimental-strip-types --test test/\" } }|NODE", //
      "{ \"scripts\": { \"test\": \"tsc && node --test dist\" } }|NODE", //
      "{ \"scripts\": { \"test\": \"bun test\" } }|BUN", //
      "{ \"scripts\": { \"test\": \"deno test -A\" } }|DENO", //
      "{ \"scripts\": { \"test\": \"node --test\" }, \"devDependencies\": { \"jest\": \"^30\" } }|JEST" })
  void findsTheDependenciesOfPackageJson(String packageJson, TestFramework framework) throws IOException {
    write("lib/package.json", packageJson);
    File test = write("lib/test/a.test.js", "it('x', () => {})");
    assertEquals(framework, FrameworkDetector.packageJsonFramework(root.resolve("lib/package.json").toFile()));
    assertDetection(framework, root.resolve("lib").toFile(), FrameworkDetector.detect(test));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = { //
      "{ \"scripts\": { \"test\": \"echo no tests\" } }", //
      "{ \"scripts\": { \"test\": \"node scripts/test.js\" } }", //
      "{ \"scripts\": { \"build\": \"node --test\" } }", //
      "{ \"scripts\": { \"test\": \"debuntest\" } }", //
      "{ \"scripts\": [] }" })
  void otherTestScriptsAreNotFrameworks(String packageJson) throws IOException {
    write("package.json", packageJson);
    assertNull(FrameworkDetector.packageJsonFramework(root.resolve("package.json").toFile()));
  }

  @Test
  void findsBunFromItsLockFile() throws IOException {
    write("package.json", "{ \"name\": \"app\" }");
    write("bun.lock", "{}");
    File test = write("src/a.test.ts", "test('x', () => {})");
    assertDetection(TestFramework.BUN, root.toFile(), FrameworkDetector.detect(test));
    Files.delete(root.resolve("bun.lock"));
    write("bun.lockb", "");
    assertDetection(TestFramework.BUN, root.toFile(), FrameworkDetector.detect(test));
  }

  @Test
  void findsNothingWithoutAFramework() throws IOException {
    write("package.json", "{ \"name\": \"app\", \"devDependencies\": { \"typescript\": \"^6\" } }");
    File test = write("src/a.test.ts", "describe('x', () => {})");
    assertNull(FrameworkDetector.detect(test));
    assertNull(FrameworkDetector.detect(folder("empty")));
    assertNull(FrameworkDetector.packageJsonFramework(root.resolve("missing.json").toFile()));
    write("broken/package.json", "{ not json");
    assertNull(FrameworkDetector.packageJsonFramework(root.resolve("broken/package.json").toFile()));
  }

  @Test
  void theRootOfAChosenFramework() throws IOException {
    write("package.json", "{ \"devDependencies\": { \"mocha\": \"^12\" } }");
    write("packages/a/package.json", "{ \"name\": \"a\" }");
    File test = write("packages/a/test/a.test.js", "it('x', () => {})");
    // The package.json depending on the framework, else the nearest one, else the folder of the tests.
    assertEquals(root.toFile(), FrameworkDetector.findRoot(TestFramework.MOCHA, test));
    assertEquals(root.resolve("packages/a").toFile(), FrameworkDetector.findRoot(TestFramework.JASMINE, test));
    assertEquals(root.resolve("packages/a").toFile(), FrameworkDetector.findRoot(TestFramework.NODE, test));
    // Deno is not configured: the nearest package.json.
    File alone = write("alone/x_test.ts", "Deno.test('x', () => {})");
    assertEquals(root.toFile(), FrameworkDetector.findRoot(TestFramework.DENO, alone));
    write("alone/deno.json", "{}");
    assertEquals(root.resolve("alone").toFile(), FrameworkDetector.findRoot(TestFramework.DENO, alone));
  }

  @ParameterizedTest
  @EnumSource(TestFramework.class)
  void aChosenFrameworkRunsInItsConfiguration(TestFramework framework) throws IOException {
    if (framework.configFiles().isEmpty()) {
      return;
    }
    write("a/" + framework.configFiles().get(0), "");
    File test = write("a/b/c/x.test.js", "");
    assertEquals(root.resolve("a").toFile(), FrameworkDetector.findRoot(framework, test));
  }
}
