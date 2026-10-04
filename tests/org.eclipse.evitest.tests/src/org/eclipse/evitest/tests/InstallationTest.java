package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The installations of the frameworks: their packages in node_modules, the executables of Bun and Deno. */
class InstallationTest {

  /** An environment without the PATH of the machine: only the executables of the tests are found. */
  private static final Map<String, String> NO_PATH = Map.of("PATH", "");

  private Path root;

  @BeforeEach
  void createRoot() throws IOException {
    root = Files.createTempDirectory("evitest-installation").toRealPath();
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

  /** An executable script printing a version. */
  private File executable(String path, String version) throws IOException {
    File file = write(path, "#!/bin/sh\necho '" + version + "'\n");
    assertTrue(file.setExecutable(true));
    return file;
  }

  @ParameterizedTest
  @EnumSource(value = TestFramework.class, names = { "VITEST", "JEST", "MOCHA", "JASMINE", "PLAYWRIGHT" })
  void findsThePackageInNodeModules(TestFramework framework) throws IOException {
    File app = Files.createDirectories(root.resolve("packages/app")).toFile();
    assertNull(Installation.find(framework, app, NO_PATH, null));
    // Hoisted in the node_modules of a parent folder.
    String folder = "node_modules/" + framework.packageName();
    File entry = write(folder + "/" + framework.entry(), "");
    write(folder + "/package.json", "{ \"name\": \"" + framework.packageName() + "\", \"version\": \"4.2.1\" }");
    Installation installation = Installation.find(framework, app, NO_PATH, null);
    assertNotNull(installation);
    assertEquals(framework, installation.framework());
    assertEquals(entry, installation.entry());
    assertEquals("4.2.1", installation.version());
    assertEquals(4, installation.major());
    // The nearest one wins.
    File nearest = write("packages/app/" + folder + "/" + framework.entry(), "");
    assertEquals(nearest, Installation.find(framework, app, NO_PATH, null).entry());
    assertNull(Installation.find(framework, app, NO_PATH, null).version());
  }

  @Test
  void theCommandOfPackageJsonWins() throws IOException {
    // A new version moves its command line: package.json says where.
    File moved = write("node_modules/jest/build/cli.js", "");
    write("node_modules/jest/bin/jest.js", "");
    write("node_modules/jest/package.json", "{ \"version\": \"31.0.0\", \"bin\": \"./build/cli.js\" }");
    assertEquals(moved, Installation.find(TestFramework.JEST, root.toFile(), NO_PATH, null).entry());
    File playwright = write("node_modules/@playwright/test/lib/cli.mjs", "");
    write("node_modules/@playwright/test/package.json",
        "{ \"version\": \"2.0.0\", \"bin\": { \"other\": \"other.js\", \"playwright\": \"lib/cli.mjs\" } }");
    assertEquals(playwright, Installation.find(TestFramework.PLAYWRIGHT, root.toFile(), NO_PATH, null).entry());
    // A command which does not exist: the entry known by EVitest.
    File mocha = write("node_modules/mocha/bin/mocha.js", "");
    write("node_modules/mocha/package.json", "{ \"bin\": { \"mocha\": \"missing.js\" } }");
    assertEquals(mocha, Installation.find(TestFramework.MOCHA, root.toFile(), NO_PATH, null).entry());
    // A broken package.json.
    File jasmine = write("node_modules/jasmine/bin/jasmine.js", "");
    write("node_modules/jasmine/package.json", "{ broken");
    assertEquals(jasmine, Installation.find(TestFramework.JASMINE, root.toFile(), NO_PATH, null).entry());
    assertNull(Installation.find(TestFramework.JASMINE, root.toFile(), NO_PATH, null).version());
  }

  @Test
  void aPackageWithoutItsEntryIsNotInstalled() throws IOException {
    write("node_modules/jest/package.json", "{ \"version\": \"30.0.0\" }");
    assertNull(Installation.find(TestFramework.JEST, root.toFile(), NO_PATH, null));
  }

  @Test
  void nodeTestNeedsNoPackage() {
    Installation installation = Installation.find(TestFramework.NODE, root.toFile(), NO_PATH, null);
    assertNotNull(installation);
    assertNull(installation.entry());
    assertEquals(0, installation.major());
  }

  @Test
  void theVitestInstallationOfTheCommandLine() throws IOException {
    File entry = write("node_modules/vitest/vitest.mjs", "");
    write("node_modules/vitest/package.json", "{ \"version\": \"3.2.4\" }");
    Installation installation = Installation.find(TestFramework.VITEST, root.toFile(), NO_PATH, null);
    assertEquals(entry, installation.toVitest().entry());
    assertEquals(3, installation.toVitest().major());
  }

  @ParameterizedTest
  @EnumSource(value = TestFramework.class, names = { "BUN", "DENO" })
  void findsTheExecutableOfTheRuntime(TestFramework framework) throws IOException {
    assumeFalse(NodeLocator.isWindows(), "Shell scripts");
    String name = framework.id();
    // Set by the user.
    File chosen = executable("chosen/" + name, name + " 9.8.7");
    Installation installation = Installation.find(framework, root.toFile(), NO_PATH, chosen.getAbsolutePath());
    assertEquals(chosen, installation.entry());
    assertEquals("9.8.7", installation.version());
    assertNull(Installation.find(framework, root.toFile(), NO_PATH, root.resolve("missing").toString()));
    // Installed by npm.
    File npm = executable(framework == TestFramework.BUN ? "node_modules/bun/bin/bun" : "node_modules/deno/deno",
        name + " 1.2.3 (stable)");
    assertEquals(npm, Installation.find(framework, root.resolve("src").toFile(), NO_PATH, "").entry());
    assertEquals("1.2.3", Installation.find(framework, root.resolve("src").toFile(), NO_PATH, null).version());
    npm.delete();
    // The npm package of Bun installs bin/bun.exe, also on Linux and macOS.
    File exe = executable(framework == TestFramework.BUN ? "node_modules/bun/bin/bun.exe" : "node_modules/deno/deno.exe",
        "3.0.0");
    assertEquals(exe, Installation.find(framework, root.resolve("src").toFile(), NO_PATH, null).entry());
    exe.delete();
    // In BUN_INSTALL or DENO_INSTALL.
    File installed = executable("install/bin/" + name, "v2.0.0");
    String variable = framework == TestFramework.BUN ? "BUN_INSTALL" : "DENO_INSTALL";
    Installation fromVariable = Installation.find(framework, root.toFile(),
        Map.of("PATH", "", variable, root.resolve("install").toString()), null);
    assertEquals(installed, fromVariable.entry());
    // On the PATH.
    File onPath = executable("path/" + name, "1.0.0");
    installed.delete();
    assertEquals(onPath, Installation.find(framework, root.toFile(),
        Map.of("PATH", root.resolve("path").toString()), null).entry());
  }

  @ParameterizedTest
  @EnumSource(value = TestFramework.class, names = { "BUN", "DENO" })
  void isInstalledRunsNothing(TestFramework framework) throws IOException {
    assumeFalse(NodeLocator.isWindows(), "Shell scripts");
    // The menus test it in the UI thread: the executable is found, never run.
    File ran = root.resolve("ran").toFile();
    File script = write("bin/" + framework.id(), "#!/bin/sh\ntouch '" + ran + "'\necho 7.7.7\n");
    assertTrue(script.setExecutable(true));
    Map<String, String> path = Map.of("PATH", root.resolve("bin").toString());
    assertTrue(Installation.isInstalled(framework, root.toFile(), path, null));
    assertTrue(Installation.isInstalled(framework, root.toFile(), NO_PATH, script.getAbsolutePath()));
    assertFalse(ran.exists());
    assertFalse(Installation.isInstalled(framework, root.toFile(), path, root.resolve("missing").toString()));
    // find gives the version: it runs the executable.
    assertEquals("7.7.7", Installation.find(framework, root.toFile(), path, null).version());
    assertTrue(ran.exists());
  }

  @Test
  void isInstalledFindsThePackagesAndNode() throws IOException {
    assertTrue(Installation.isInstalled(TestFramework.NODE, root.toFile(), NO_PATH, null));
    assertFalse(Installation.isInstalled(TestFramework.JEST, root.toFile(), NO_PATH, null));
    write("node_modules/jest/bin/jest.js", "");
    assertTrue(Installation.isInstalled(TestFramework.JEST, root.toFile(), NO_PATH, null));
  }

  @Test
  void versionsOfTheExecutables() throws IOException {
    assumeFalse(NodeLocator.isWindows(), "Shell scripts");
    assertEquals("22.18.0", NodeLocator.version(executable("node", "v22.18.0")));
    assertEquals("2.9.6", NodeLocator.version(executable("deno", "deno 2.9.6 (stable, release)\nv8 15.0.245.2")));
    assertNull(NodeLocator.version(executable("silent", "")));
    assertNull(NodeLocator.version(root.resolve("missing").toFile()));
    assertNull(NodeLocator.version(null));
    assertEquals(22, NodeLocator.majorMinor("v22.18.0")[0]);
    assertEquals(18, NodeLocator.majorMinor("22.18.0")[1]);
    assertEquals(0, NodeLocator.majorMinor(null)[0]);
    assertEquals(0, NodeLocator.majorMinor("unknown")[1]);
  }
}
