package org.eclipse.evitest.ui;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.evitest.core.TestSelector;

/**
 * The last results of the tests, by file, shown above the tests in the editors.
 */
public final class TestResults {

  public enum State {
    RUNNING, PASSED, FAILED, SKIPPED
  }

  /** Notified when results of a file changed. */
  public interface Listener {
    void resultsChanged(String file);
  }

  private static final Map<String, Map<List<String>, State>> RESULTS = new ConcurrentHashMap<>();
  /** The tests running now: their previous result stays until they end (a test skipped by a filter keeps it). */
  private static final Map<String, Set<List<String>>> RUNNING = new ConcurrentHashMap<>();
  private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

  private TestResults() {
  }

  public static void addListener(Listener listener) {
    LISTENERS.add(listener);
  }

  public static void removeListener(Listener listener) {
    LISTENERS.remove(listener);
  }

  /** Sets the result of a test which ended. */
  public static void set(String file, List<String> names, State state) {
    List<String> key = List.copyOf(names);
    RESULTS.computeIfAbsent(key(file), k -> new ConcurrentHashMap<>()).put(key, state);
    setRunning(file, key, false);
  }

  /** Marks a test running, or not running anymore without a new result. */
  public static void setRunning(String file, List<String> names, boolean running) {
    if (running) {
      RUNNING.computeIfAbsent(key(file), k -> ConcurrentHashMap.newKeySet()).add(List.copyOf(names));
    } else {
      Set<List<String>> tests = RUNNING.get(key(file));
      if (tests != null) {
        tests.remove(names);
      }
    }
  }

  /** Tells the listeners that the results of the file changed. */
  public static void fireChanged(String file) {
    for (Listener listener : LISTENERS) {
      listener.resultsChanged(key(file));
    }
  }

  /**
   * The state of the tests selected by the selector in the file: running if one runs, else failed if one failed,
   * passed if one passed, skipped if all were skipped. Null if none ran.
   */
  public static State get(String file, TestSelector selector) {
    String key = key(file);
    if (RUNNING.getOrDefault(key, Set.of()).stream().anyMatch(selector::matches)) {
      return State.RUNNING;
    }
    boolean passed = false;
    boolean skipped = false;
    for (Map.Entry<List<String>, State> entry : RESULTS.getOrDefault(key, Map.of()).entrySet()) {
      if (selector.matches(entry.getKey())) {
        State state = entry.getValue();
        if (state == State.FAILED) {
          return State.FAILED;
        }
        passed |= state == State.PASSED;
        skipped |= state == State.SKIPPED;
      }
    }
    if (passed) {
      return State.PASSED;
    }
    return skipped ? State.SKIPPED : null;
  }

  public static String key(String file) {
    String path = new File(file).getAbsoluteFile().toPath().normalize().toString().replace('\\', '/');
    return File.separatorChar == '\\' ? path.toLowerCase() : path;
  }

  static void clear() {
    RESULTS.clear();
    RUNNING.clear();
  }
}
