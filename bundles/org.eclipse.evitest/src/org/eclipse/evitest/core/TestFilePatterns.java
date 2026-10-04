package org.eclipse.evitest.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The names of the test files, as globs separated by commas: {@code *.{test,spec}.{js,ts}, *.unit.ts}.
 * <p>
 * A glob knows {@code *}, {@code ?} and the alternatives {@code {a,b}}; it matches the name of the file, not its path.
 */
public final class TestFilePatterns {

  /** The test files of Vitest by default. */
  public static final String DEFAULT = "*.{test,spec}.{js,jsx,ts,tsx,mjs,cjs,mts,cts}";

  private final List<Pattern> patterns;

  public TestFilePatterns(String globs) {
    patterns = new ArrayList<>();
    for (String glob : split(globs == null ? "" : globs)) {
      if (!glob.isBlank()) {
        patterns.add(Pattern.compile(toRegex(glob.trim())));
      }
    }
  }

  public boolean matches(String fileName) {
    for (Pattern pattern : patterns) {
      if (pattern.matcher(fileName).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Splits the globs on the commas which are not in braces. */
  private static List<String> split(String globs) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < globs.length(); i++) {
      char c = globs.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (c == ',' && depth == 0) {
        parts.add(globs.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(globs.substring(start));
    return parts;
  }

  static String toRegex(String glob) {
    StringBuilder regex = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '*' -> regex.append("[^/]*");
        case '?' -> regex.append("[^/]");
        case '{' -> {
          depth++;
          regex.append("(?:");
        }
        case '}' -> {
          if (depth > 0) {
            depth--;
            regex.append(')');
          } else {
            regex.append("\\}");
          }
        }
        case ',' -> regex.append(depth > 0 ? "|" : ",");
        default -> {
          if ("\\.[]()^$+|".indexOf(c) >= 0) {
            regex.append('\\');
          }
          regex.append(c);
        }
      }
    }
    while (depth-- > 0) {
      regex.append(')');
    }
    return regex.toString();
  }
}
