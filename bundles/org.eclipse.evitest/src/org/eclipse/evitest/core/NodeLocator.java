package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Node.js executable, and the other executables (Bun, Deno).
 * <p>
 * The PATH of Eclipse is searched first, then the usual places of Node.js: Eclipse started from the desktop (macOS in
 * particular) does not have the PATH of the shell where nvm, Volta or Homebrew add Node.js.
 */
public final class NodeLocator {

  private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

  private NodeLocator() {
  }

  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
  }

  /** The Node.js executable found in the environment, or null. */
  public static File find(Map<String, String> environment) {
    return findExecutable("node", environment, List.of(), List.of());
  }

  /**
   * An executable found in the first folders, then in the PATH of the environment, then in the last folders and in the
   * usual places of Node.js, or null.
   *
   * @param name the name of the executable, without the .exe of Windows
   */
  public static File findExecutable(String name, Map<String, String> environment, List<File> firstFolders,
      List<File> lastFolders) {
    String executable = isWindows() ? name + ".exe" : name;
    List<File> folders = new ArrayList<>(firstFolders);
    folders.addAll(path(environment));
    folders.addAll(lastFolders);
    folders.addAll(searchPath(environment));
    for (File folder : folders) {
      File file = new File(folder, executable);
      if (file.isFile() && file.canExecute()) {
        return file;
      }
    }
    return null;
  }

  private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();

  /**
   * The version printed by {@code executable --version} ({@code v22.1.0}, {@code 1.3.14}, {@code deno 2.9.6 ...}):
   * its first numbers, null if they are not known. The versions are cached by executable and modification time.
   */
  public static String version(File executable) {
    if (executable == null || !executable.isFile()) {
      return null;
    }
    String key = executable.getAbsolutePath() + "@" + executable.lastModified();
    String cached = VERSIONS.get(key);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    String version = null;
    try {
      Process process = new ProcessBuilder(executable.getAbsolutePath(), "--version").redirectErrorStream(true).start();
      process.getOutputStream().close();
      String output;
      try (var input = process.getInputStream()) {
        output = new String(input.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
      }
      if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        Matcher matcher = VERSION.matcher(output);
        if (matcher.find()) {
          version = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
        }
      } else {
        process.destroyForcibly();
      }
    } catch (java.io.IOException e) {
      // Unknown version.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    VERSIONS.put(key, version == null ? "" : version);
    return version;
  }

  /** The major and minor numbers of a version ({@code 22.18.0}), {0, 0} if it is not one. */
  public static int[] majorMinor(String version) {
    Matcher matcher = VERSION.matcher(version == null ? "" : version);
    if (!matcher.find()) {
      return new int[] { 0, 0 };
    }
    return new int[] { Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)) };
  }

  /** The folders of the PATH of the environment. */
  private static List<File> path(Map<String, String> environment) {
    List<File> folders = new ArrayList<>();
    String path = null;
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      // "Path" on Windows.
      if (entry.getKey().equalsIgnoreCase("PATH")) {
        path = entry.getValue();
      }
    }
    if (path != null) {
      for (String folder : path.split(File.pathSeparator)) {
        if (!folder.isBlank()) {
          folders.add(new File(folder.trim()));
        }
      }
    }
    return folders;
  }

  /** The PATH, then the usual places of Node.js. */
  private static List<File> searchPath(Map<String, String> environment) {
    List<File> folders = path(environment);
    String home = System.getProperty("user.home");
    if (isWindows()) {
      addIfSet(folders, environment, "ProgramFiles", "nodejs");
      addIfSet(folders, environment, "ProgramFiles(x86)", "nodejs");
      addIfSet(folders, environment, "NVM_SYMLINK", "");
      addIfSet(folders, environment, "VOLTA_HOME", "bin");
      addIfSet(folders, environment, "LOCALAPPDATA", "Volta\\bin");
      folders.add(new File("C:\\Program Files\\nodejs"));
    } else {
      addIfSet(folders, environment, "VOLTA_HOME", "bin");
      folders.add(new File(home, ".volta/bin"));
      folders.add(new File("/opt/homebrew/bin"));
      folders.add(new File("/usr/local/bin"));
      folders.add(new File("/usr/bin"));
      // nvm: the most recent version.
      String nvmDir = environment.get("NVM_DIR");
      File nvmVersions = new File(nvmDir != null ? new File(nvmDir) : new File(home, ".nvm"), "versions/node");
      File[] versions = nvmVersions.listFiles(File::isDirectory);
      if (versions != null) {
        Arrays.stream(versions).sorted(Comparator.comparing(File::getName, NodeLocator::compareVersions).reversed())
            .forEach(version -> folders.add(new File(version, "bin")));
      }
      folders.add(new File(home, ".local/share/fnm/aliases/default/bin"));
      folders.add(new File(home, ".asdf/shims"));
      folders.add(new File(home, ".local/share/mise/shims"));
    }
    return folders;
  }

  private static void addIfSet(List<File> folders, Map<String, String> environment, String variable, String child) {
    String value = environment.get(variable);
    if (value != null && !value.isBlank()) {
      folders.add(child.isEmpty() ? new File(value) : new File(value, child));
    }
  }

  static int compareVersions(String a, String b) {
    Matcher matcherA = VERSION.matcher(a);
    Matcher matcherB = VERSION.matcher(b);
    if (!matcherA.find() || !matcherB.find()) {
      return a.compareTo(b);
    }
    for (int group = 1; group <= 3; group++) {
      int comparison = Integer.compare(Integer.parseInt(matcherA.group(group)), Integer.parseInt(matcherB.group(group)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }
}
