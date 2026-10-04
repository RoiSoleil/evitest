package org.eclipse.evitest.ui;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A location in a line of a stack trace: {@code at fn (/project/src/a.test.ts:12:5)},
 * {@code at file:///C:/project/a.ts:3:1}, {@code ❯ src/a.test.ts:12:5}...
 *
 * @param file the absolute path of the file
 * @param line the line, 1 based
 * @param column the column, 1 based, 0 if it is not known
 */
public record StackFrameLocation(String file, int line, int column) {

  /** The position at the end of the line: :line or :line:column, then a closing parenthesis or bracket. */
  private static final Pattern POSITION = Pattern.compile(":(\\d+)(?::(\\d+))?\\)?\\s*\\]?\\s*$");

  /**
   * The location in a line of a stack trace, null if there is none or if its file does not exist.
   * <p>
   * The path may contain spaces: the paths starting after each space or parenthesis are tried, from the longest.
   *
   * @param root the folder of the relative paths, null if it is not known
   */
  public static StackFrameLocation parse(String traceLine, String root) {
    if (traceLine == null) {
      return null;
    }
    String text = traceLine.strip();
    Matcher matcher = POSITION.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    String before = text.substring(0, matcher.start());
    int line = Integer.parseInt(matcher.group(1));
    int column = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
    for (int start = 0; start < before.length(); start++) {
      if (start > 0 && !isBoundary(before.charAt(start - 1))) {
        continue;
      }
      File file = toFile(before.substring(start), root);
      if (file != null) {
        return new StackFrameLocation(file.getAbsolutePath(), line, column);
      }
    }
    return null;
  }

  private static boolean isBoundary(char c) {
    return Character.isWhitespace(c) || c == '(' || c == '[';
  }

  private static File toFile(String candidate, String root) {
    String path = candidate.strip();
    if (path.isEmpty() || path.startsWith("node:") || path.startsWith("<") || path.startsWith("(")
        || path.startsWith("[")) {
      return null;
    }
    File file;
    if (path.startsWith("file:")) {
      try {
        file = new File(URI.create(path.replace(" ", "%20")));
      } catch (IllegalArgumentException e) {
        file = new File(URLDecoder.decode(path.substring("file://".length()), StandardCharsets.UTF_8));
      }
    } else {
      file = new File(path);
      if (!file.isAbsolute()) {
        if (root == null) {
          return null;
        }
        file = new File(root, path);
      }
    }
    return file.isFile() ? file : null;
  }
}
