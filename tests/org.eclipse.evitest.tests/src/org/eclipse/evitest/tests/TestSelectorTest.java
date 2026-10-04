package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.evitest.core.TestSelector;
import org.junit.jupiter.api.Test;

class TestSelectorTest {

  /** The full names as Vitest 4 (spaces) and Vitest 5 (" > ") match them. */
  private static void assertSelects(TestSelector selector, String... names) {
    String regex = selector.toRegex();
    assertTrue(Pattern.compile(regex).matcher(String.join(" ", names)).find(), regex);
    assertTrue(Pattern.compile(regex).matcher(String.join(" > ", names)).find(), regex);
  }

  private static void assertDoesNotSelect(TestSelector selector, String... names) {
    String regex = selector.toRegex();
    assertFalse(Pattern.compile(regex).matcher(String.join(" ", names)).find(), regex);
    assertFalse(Pattern.compile(regex).matcher(String.join(" > ", names)).find(), regex);
  }

  @Test
  void test() {
    TestSelector selector = TestSelector.test(List.of("math", "adds"));
    assertSelects(selector, "math", "adds");
    assertDoesNotSelect(selector, "math", "adds", "more");
    assertDoesNotSelect(selector, "other", "math", "adds");
    assertDoesNotSelect(selector, "math", "add");
  }

  @Test
  void suite() {
    TestSelector selector = TestSelector.suite(List.of("math"));
    assertSelects(selector, "math", "adds");
    assertSelects(selector, "math", "nested", "adds");
    assertSelects(selector, "math");
    assertDoesNotSelect(selector, "mathematics", "adds");
  }

  @Test
  void specialCharacters() {
    TestSelector selector = TestSelector.test(List.of("handles (a + b) * c?", "[x] $1 ^ | {} / \\"));
    assertSelects(selector, "handles (a + b) * c?", "[x] $1 ^ | {} / \\");
    assertDoesNotSelect(selector, "handles a + b * c", "[x] $1 ^ | {} / \\");
  }

  @Test
  void template() {
    TestSelector selector = new TestSelector(false, List.of("adds %i and $b", "100%% sure"), true);
    assertSelects(selector, "adds 1 and 2", "100% sure");
    assertTrue(selector.matches(List.of("adds 1 and 2", "100% sure")));
    assertFalse(selector.matches(List.of("subtracts 1 and 2", "100% sure")));
  }

  @Test
  void severalSelectors() {
    String regex = TestSelector.toRegex(List.of(TestSelector.test(List.of("a", "b")), TestSelector.suite(List.of("c"))));
    Pattern pattern = Pattern.compile(regex);
    assertTrue(pattern.matcher("a b").find());
    assertTrue(pattern.matcher("c > d > e").find());
    assertFalse(pattern.matcher("a b c").find());
    assertFalse(pattern.matcher("d").find());
  }

  @Test
  void vitest1() {
    // The full names of Vitest 1 start with the name of the file.
    TestSelector selector = TestSelector.test(List.of("math", "adds"));
    Pattern pattern = Pattern.compile(TestSelector.toRegex(List.of(selector), true));
    assertTrue(pattern.matcher("src/math.test.ts math adds").find());
    assertTrue(pattern.matcher("math adds").find());
    assertFalse(pattern.matcher("src/math.test.ts math adds more").find());
    assertFalse(pattern.matcher("src/math.test.ts xmath adds").find());
  }

  @Test
  void matches() {
    TestSelector suite = TestSelector.suite(List.of("a"));
    assertTrue(suite.matches(List.of("a")));
    assertTrue(suite.matches(List.of("a", "b")));
    assertFalse(suite.matches(List.of("b", "a")));
    TestSelector test = TestSelector.test(List.of("a", "b"));
    assertTrue(test.matches(List.of("a", "b")));
    assertFalse(test.matches(List.of("a", "b", "c")));
    assertFalse(test.matches(List.of("a")));
  }

  @Test
  void json() {
    TestSelector selector = new TestSelector(true, List.of("a \"quoted\" name", "é"), true);
    assertEquals(selector, TestSelector.fromJson(selector.toJson()));
    assertEquals(TestSelector.test(List.of("x")), TestSelector.fromJson(TestSelector.test(List.of("x")).toJson()));
    assertNull(TestSelector.fromJson("not json"));
    assertNull(TestSelector.fromJson("{}"));
  }

  @Test
  void label() {
    assertEquals("a > b", TestSelector.test(List.of("a", "b")).label());
  }
}
