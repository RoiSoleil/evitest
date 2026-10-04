package org.eclipse.evitest.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test or a suite (a {@code describe}) to run, by the names of its suites and its own name.
 * <p>
 * A name read in the source of a test built by {@code .each} or {@code .for} is a template ({@code "adds %i"}): its
 * placeholders match anything.
 *
 * @param suite true for a suite, whose tests all run
 * @param names the names of the suites from the outermost one, then the name of the test or of the suite
 * @param template true if the names may contain placeholders of {@code .each}
 */
public record TestSelector(boolean suite, List<String> names, boolean template) {

  /**
   * Separator of the names in the full name of a test, as the {@code --testNamePattern} option of Vitest matches it: a
   * space up to Vitest 4, {@code " > "} since Vitest 5.
   */
  private static final String SEPARATOR = "(?: | > )";

  /**
   * Placeholders of the names of {@code .each}: printf like ones and {@code $variable}, and the expressions of the
   * template literals, read as {@code ${}} by {@link JsTestScanner}.
   */
  private static final Pattern PLACEHOLDER = Pattern.compile("%[sdifjoc#%]|\\$\\{\\}|\\$[\\w.\\[\\]]+");

  public TestSelector {
    names = List.copyOf(names);
  }

  public static TestSelector test(List<String> names) {
    return new TestSelector(false, names, false);
  }

  public static TestSelector suite(List<String> names) {
    return new TestSelector(true, names, false);
  }

  /** The name shown to the user: the names separated by {@code " > "}. */
  public String label() {
    return String.join(" > ", names);
  }

  /** Writes this selector as JSON (an attribute of the launch configurations). */
  public String toJson() {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("kind", suite ? "suite" : "test");
    object.put("names", names);
    if (template) {
      object.put("template", Boolean.TRUE);
    }
    return Json.write(object);
  }

  /** Reads a selector written by {@link #toJson()}, null if the text is not one. */
  public static TestSelector fromJson(String json) {
    Map<String, Object> object = Json.parseObject(json);
    List<String> names = Json.getStrings(object, "names");
    if (names.isEmpty()) {
      return null;
    }
    return new TestSelector("suite".equals(Json.getString(object, "kind")), names, Json.getBoolean(object, "template"));
  }

  /** The regular expression matching the full names of the tests selected by this selector. */
  public String toRegex() {
    return toRegex(false);
  }

  /**
   * The regular expression matching the full names of the tests selected by this selector.
   *
   * @param filePrefix true if the full names start with the name of the file (Vitest 1)
   */
  public String toRegex(boolean filePrefix) {
    StringBuilder regex = new StringBuilder(filePrefix ? "(?:^| )" : "^");
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) {
        regex.append(SEPARATOR);
      }
      regex.append(nameRegex(names.get(i)));
    }
    regex.append(suite ? "(?:" + SEPARATOR + ".*)?$" : "$");
    return regex.toString();
  }

  /** The regular expression of the {@code --testNamePattern} option matching the tests of all the selectors. */
  public static String toRegex(Collection<TestSelector> selectors) {
    return toRegex(selectors, false);
  }

  /**
   * The regular expression of the {@code --testNamePattern} option matching the tests of all the selectors.
   *
   * @param filePrefix true if the full names start with the name of the file (Vitest 1)
   */
  public static String toRegex(Collection<TestSelector> selectors, boolean filePrefix) {
    if (selectors.size() == 1) {
      return selectors.iterator().next().toRegex(filePrefix);
    }
    List<String> regexes = new ArrayList<>();
    for (TestSelector selector : selectors) {
      regexes.add("(?:" + selector.toRegex(filePrefix) + ")");
    }
    return String.join("|", regexes);
  }

  /** True if this selector selects the test or the suite with these names. */
  public boolean matches(List<String> fullNames) {
    if (fullNames.size() < names.size() || (!suite && fullNames.size() != names.size())) {
      return false;
    }
    for (int i = 0; i < names.size(); i++) {
      if (!Pattern.matches(nameRegex(names.get(i)), fullNames.get(i))) {
        return false;
      }
    }
    return true;
  }

  private String nameRegex(String name) {
    if (!template) {
      return escape(name);
    }
    StringBuilder regex = new StringBuilder();
    Matcher matcher = PLACEHOLDER.matcher(name);
    int last = 0;
    while (matcher.find()) {
      regex.append(escape(name.substring(last, matcher.start())));
      regex.append(matcher.group().equals("%%") ? "%" : ".*?");
      last = matcher.end();
    }
    regex.append(escape(name.substring(last)));
    return regex.toString();
  }

  /**
   * Escapes the characters of a regular expression. {@link Pattern#quote(String)} is not used: its {@code \Q...\E} is
   * not understood by the regular expressions of JavaScript.
   */
  static String escape(String text) {
    StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ("\\^$.|?*+()[]{}/".indexOf(c) >= 0) {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }
}
