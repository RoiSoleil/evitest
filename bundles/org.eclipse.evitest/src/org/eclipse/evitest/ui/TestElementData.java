package org.eclipse.evitest.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.evitest.core.Json;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.unittest.model.ITestElement;

/**
 * What EVitest keeps about an element of the Unit Test view, in its data: its file, its location, its names.
 *
 * @param kind {@link #MODULE} (a test file), {@link #SUITE} or {@link #TEST}
 * @param file the absolute path of the test file
 * @param line the line of the declaration (1 based), null if it is not known
 * @param column the column of the declaration (1 based), null if it is not known
 * @param names the names of the suites and of the element (empty for a test file)
 * @param project the name of the Vitest project, null if there is none
 */
public record TestElementData(String kind, String file, Integer line, Integer column, List<String> names,
    String project) {

  public static final String MODULE = "module";
  public static final String SUITE = "suite";
  public static final String TEST = "test";

  public TestElementData {
    names = names == null ? List.of() : List.copyOf(names);
  }

  public boolean isModule() {
    return MODULE.equals(kind);
  }

  /** The selector running this test or suite, null for a test file. */
  public TestSelector toSelector() {
    if (isModule() || names.isEmpty()) {
      return null;
    }
    return new TestSelector(SUITE.equals(kind), names, false);
  }

  public String toJson() {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("kind", kind);
    object.put("file", file);
    object.put("line", line);
    object.put("column", column);
    if (!names.isEmpty()) {
      object.put("names", names);
    }
    object.put("project", project);
    return Json.write(object);
  }

  public static TestElementData parse(String json) {
    Map<String, Object> object = Json.parseObject(json);
    if (Json.getString(object, "file") == null) {
      return null;
    }
    return new TestElementData(Json.getString(object, "kind"), Json.getString(object, "file"),
        Json.getInteger(object, "line"), Json.getInteger(object, "column"), Json.getStrings(object, "names"),
        Json.getString(object, "project"));
  }

  /** The data of an element of the view, null if it is not one of EVitest (the session, the unhandled errors). */
  public static TestElementData of(ITestElement element) {
    return element == null ? null : parse(element.getData());
  }
}
