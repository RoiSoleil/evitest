package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.evitest.core.JsTestScanner;
import org.eclipse.evitest.core.JsTestScanner.TestBlock;
import org.junit.jupiter.api.Test;

class JsTestScannerTest {

  private static List<String> all(String source) {
    return JsTestScanner.flatten(JsTestScanner.scan(source)).stream()
        .map(block -> (block.isSuite() ? "S:" : "T:") + String.join(" > ", block.getNames())
            + (block.isTemplate() ? " (template)" : ""))
        .toList();
  }

  @Test
  void nestedSuitesAndTests() {
    String source = """
        import { describe, it, expect, test } from 'vitest'

        describe('math', () => {
          describe("adds", () => {
            it('one and one', () => {
              expect(1 + 1).toBe(2)
            })
            test(`two and two`, async () => {})
          })
          it('subtracts', () => {})
        })

        test('top level', () => {})
        """;
    assertEquals(List.of("S:math", "S:math > adds", "T:math > adds > one and one", "T:math > adds > two and two",
        "T:math > subtracts", "T:top level"), all(source));
  }

  @Test
  void modifiers() {
    String source = """
        describe.skip('skipped', () => {
          it.only('only', () => {})
          it.todo('later')
          test.concurrent.fails('fails', () => {})
          it.skipIf(process.env.CI)('not on CI', () => {})
          it.runIf(isLinux())('on Linux', () => {})
        })
        """;
    assertEquals(List.of("S:skipped", "T:skipped > only", "T:skipped > later", "T:skipped > fails",
        "T:skipped > not on CI", "T:skipped > on Linux"), all(source));
  }

  @Test
  void each() {
    String source = """
        describe.each([[1, 2], [3, 4]])('pair %i %i', (a, b) => {
          it('sums', () => {})
        })
        test.each`
          a    | b
          ${1} | ${2}
        `('adds $a and $b', ({ a, b }) => {})
        test.for([1, 2])('number %d', (n) => {})
        """;
    assertEquals(List.of("S:pair %i %i (template)", "T:pair %i %i > sums (template)", "T:adds $a and $b (template)",
        "T:number %d (template)"), all(source));
  }

  @Test
  void templateLiteralWithExpression() {
    String source = """
        const name = 'x'
        it(`works with ${name}`, () => {})
        """;
    List<TestBlock> blocks = JsTestScanner.scan(source);
    assertEquals(1, blocks.size());
    assertEquals("works with ${}", blocks.get(0).getName());
    assertTrue(blocks.get(0).isTemplate());
    assertTrue(blocks.get(0).toSelector().matches(List.of("works with x")));
  }

  @Test
  void suiteNamedAfterAFunction() {
    String source = """
        class Calculator {}
        describe(Calculator, () => {
          it('exists', () => {})
        })
        """;
    assertEquals(List.of("S:Calculator", "T:Calculator > exists"), all(source));
  }

  @Test
  void ignoresCommentsStringsAndRegularExpressions() {
    String source = """
        // it('commented', () => {})
        /* describe('also commented', () => {}) */
        const text = "it('in a string')"
        const regex = /it\\('in a regex'\\)/g
        const ratio = a / b / c
        describe('real', () => {
          const template = `test('in a template')`
          it('test', () => { expect(text).toMatch(/\\)/) })
        })
        """;
    assertEquals(List.of("S:real", "T:real > test"), all(source));
  }

  @Test
  void ignoresPropertiesAndDeclarations() {
    String source = """
        import { it } from 'vitest'
        const config = { test: { include: [] } }
        config.test('not a test')
        function test(name) {}
        it('real', () => {})
        """;
    assertEquals(List.of("T:real"), all(source));
  }

  @Test
  void quoteInJsxTextDoesNotHideTheRestOfTheFile() {
    String source = """
        describe('component', () => {
          it('renders', () => {
            render(<p>Don't panic</p>)
          })
          it('still found', () => {})
        })
        """;
    assertEquals(List.of("S:component", "T:component > renders", "T:component > still found"), all(source));
  }

  @Test
  void escapesInNames() {
    String source = "it('it\\'s \\\"quoted\\\" \\u00e9', () => {})";
    assertEquals("it's \"quoted\" \u00e9", JsTestScanner.scan(source).get(0).getName());
  }

  @Test
  void findByOffset() {
    String source = """
        describe('outer', () => {
          it('first', () => {
            const x = 1
          })

          it('second', () => {})
        })
        """;
    List<TestBlock> blocks = JsTestScanner.scan(source);
    TestBlock first = JsTestScanner.find(blocks, source.indexOf("const x"));
    assertNotNull(first);
    assertEquals(List.of("outer", "first"), first.getNames());
    TestBlock outer = JsTestScanner.find(blocks, source.indexOf("\n\n") + 1);
    assertNotNull(outer);
    assertEquals(List.of("outer"), outer.getNames());
    assertTrue(outer.isSuite());
    assertEquals(source.indexOf("describe"), outer.getOffset());
    assertEquals(source.indexOf("'outer'"), outer.getNameOffset());
    assertNull(JsTestScanner.find(blocks, source.length()));
  }

  @Test
  void findByNames() {
    String source = """
        describe('outer', () => {
          it.each([1, 2])('number %i', () => {})
          it('plain', () => {})
        })
        """;
    List<TestBlock> blocks = JsTestScanner.scan(source);
    assertEquals(source.indexOf("it('plain'"), JsTestScanner.find(blocks, List.of("outer", "plain")).getOffset());
    assertEquals(source.indexOf("it.each"), JsTestScanner.find(blocks, List.of("outer", "number 2")).getOffset());
    assertNull(JsTestScanner.find(blocks, List.of("outer", "missing")));
  }

  @Test
  void unclosedCallsEndWithTheSource() {
    String source = "describe('broken', () => {\n  it('typing', () => {\n";
    List<TestBlock> blocks = JsTestScanner.scan(source);
    assertEquals(List.of("S:broken", "T:broken > typing"), all(source));
    assertEquals(source.length(), blocks.get(0).getEnd());
    assertFalse(blocks.get(0).getChildren().isEmpty());
  }
}
