package org.eclipse.evitest.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Node.js executable.
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
    String executable = isWindows() ? "node.exe" : "node";
    for (File folder : searchPath(environment)) {
      File node = new File(folder, executable);
      if (node.isFile() && node.canExecute()) {
        return node;
      }
    }
    return null;
  }

  private static List<File> searchPath(Map<String, String> environment) {
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
