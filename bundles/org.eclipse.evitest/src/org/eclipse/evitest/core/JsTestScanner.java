package org.eclipse.evitest.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Finds the tests ({@code it}, {@code test}, {@code bench}) and the suites ({@code describe}, {@code suite}) declared in
 * the source of a JavaScript or TypeScript test file, without running it.
 * <p>
 * It is a tokenizer, not a parser: it knows the strings, the template literals, the comments and the regular expressions
 * so that their content is not taken for code, and it recognizes the calls such as {@code describe('name', ...)},
 * {@code it.skip('name', ...)}, {@code test.each([...])('name %i', ...)} or {@code describe(MyClass, ...)}. A string is
 * ended at the end of its line, so that a quote in the text of JSX does not hide the rest of the file.
 */
public final class JsTestScanner {

  /** A test or a suite found in the source. */
  public static final class TestBlock {
    private final boolean suite;
    private final String name;
    private final boolean template;
    private final int offset;
    private final int nameOffset;
    private int end;
    private final TestBlock parent;
    private final List<TestBlock> children = new ArrayList<>();

    TestBlock(boolean suite, String name, boolean template, int offset, int nameOffset, TestBlock parent) {
      this.suite = suite;
      this.name = name;
      this.template = template;
      this.offset = offset;
      this.nameOffset = nameOffset;
      this.parent = parent;
    }

    public boolean isSuite() {
      return suite;
    }

    public String getName() {
      return name;
    }

    /** True if the name is a template of {@code .each} or {@code .for}, or contains an expression. */
    public boolean isTemplate() {
      return template || (parent != null && parent.isTemplate());
    }

    /** Offset of the first character of the call ({@code describe}, {@code it}...). */
    public int getOffset() {
      return offset;
    }

    /** Offset of the name (its opening quote). */
    public int getNameOffset() {
      return nameOffset;
    }

    /** Offset after the closing parenthesis of the call, or the end of the source if it is not closed. */
    public int getEnd() {
      return end;
    }

    public TestBlock getParent() {
      return parent;
    }

    public List<TestBlock> getChildren() {
      return Collections.unmodifiableList(children);
    }

    /** The names of the enclosing suites, from the outermost one, then the name of this block. */
    public List<String> getNames() {
      List<String> names = new ArrayList<>();
      for (TestBlock block = this; block != null; block = block.parent) {
        names.add(0, block.name);
      }
      return names;
    }

    public TestSelector toSelector() {
      return new TestSelector(suite, getNames(), isTemplate());
    }

    @Override
    public String toString() {
      return (suite ? "suite " : "test ") + getNames();
    }
  }

  private static final Set<String> SUITE_FUNCTIONS = Set.of("describe", "suite");
  private static final Set<String> TEST_FUNCTIONS = Set.of("it", "test", "bench");
  /** Modifiers taking arguments before the call: {@code test.each(table)('name', ...)}. */
  private static final Set<String> CALLED_MODIFIERS = Set.of("each", "for", "runIf", "skipIf", "extend", "scoped");
  /** Modifiers building templates of names. */
  private static final Set<String> TEMPLATE_MODIFIERS = Set.of("each", "for");
  /** Keywords after which a slash starts a regular expression. */
  private static final Set<String> REGEX_KEYWORDS = Set.of("return", "typeof", "instanceof", "in", "of", "new",
      "delete", "void", "throw", "case", "do", "else", "yield", "await");

  private enum Kind {
    IDENTIFIER, STRING, TEMPLATE, PUNCTUATOR, NUMBER, REGEX
  }

  private record Token(Kind kind, String text, int start, int end, String value, boolean complex) {
    boolean is(String punctuator) {
      return kind == Kind.PUNCTUATOR && text.equals(punctuator);
    }
  }

  private final String source;
  private final List<Token> tokens = new ArrayList<>();

  private JsTestScanner(String source) {
    this.source = source;
  }

  /** The tests and the suites declared at the top level of the source, with their children. */
  public static List<TestBlock> scan(String source) {
    JsTestScanner scanner = new JsTestScanner(source);
    scanner.tokenize();
    return scanner.findBlocks();
  }

  /** All the tests and the suites of the source, in the order of the source. */
  public static List<TestBlock> flatten(List<TestBlock> blocks) {
    List<TestBlock> all = new ArrayList<>();
    for (TestBlock block : blocks) {
      all.add(block);
      all.addAll(flatten(block.children));
    }
    return all;
  }

  /** The innermost test or suite containing the offset, or null. */
  public static TestBlock find(List<TestBlock> blocks, int offset) {
    for (TestBlock block : blocks) {
      if (offset >= block.offset && offset <= block.end) {
        TestBlock child = find(block.children, offset);
        return child != null ? child : block;
      }
    }
    return null;
  }

  /** The test or the suite with these names, or null. */
  public static TestBlock find(List<TestBlock> blocks, List<String> names) {
    TestBlock exact = null;
    for (TestBlock block : flatten(blocks)) {
      List<String> blockNames = block.getNames();
      if (blockNames.equals(names)) {
        return block;
      }
      if (exact == null && block.isTemplate() && block.toSelector().matches(names) && blockNames.size() == names.size()) {
        exact = block;
      }
    }
    return exact;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Tokens
  // ---------------------------------------------------------------------------------------------------------------

  private void tokenize() {
    int i = 0;
    int length = source.length();
    while (i < length) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
        i = endOfLine(i);
      } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? length : end + 2;
      } else if (c == '\'' || c == '"') {
        i = readString(i, c);
      } else if (c == '`') {
        i = readTemplate(i);
      } else if (Character.isJavaIdentifierStart(c)) {
        int start = i;
        while (i < length && Character.isJavaIdentifierPart(source.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.IDENTIFIER, source.substring(start, i), start, i, null, false));
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < length && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.'
            || source.charAt(i) == '_')) {
          i++;
        }
        tokens.add(new Token(Kind.NUMBER, source.substring(start, i), start, i, null, false));
      } else if (c == '/' && regexAllowed()) {
        i = readRegex(i);
      } else {
        tokens.add(new Token(Kind.PUNCTUATOR, String.valueOf(c), i, i + 1, null, false));
        i++;
      }
    }
  }

  private int endOfLine(int from) {
    int end = source.indexOf('\n', from);
    return end < 0 ? source.length() : end;
  }

  private int readString(int start, char quote) {
    StringBuilder value = new StringBuilder();
    int i = start + 1;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == quote) {
        tokens.add(new Token(Kind.STRING, source.substring(start, i + 1), start, i + 1, value.toString(), false));
        return i + 1;
      }
      if (c == '\n') {
        // Not a string (JSX text, a broken line): the rest of the file is still read.
        tokens.add(new Token(Kind.PUNCTUATOR, String.valueOf(quote), start, start + 1, null, false));
        return start + 1;
      }
      if (c == '\\' && i + 1 < source.length()) {
        i = readEscape(i, value);
      } else {
        value.append(c);
        i++;
      }
    }
    tokens.add(new Token(Kind.PUNCTUATOR, String.valueOf(quote), start, start + 1, null, false));
    return start + 1;
  }

  /** Reads the escape sequence at i (a backslash) into value, returns the offset after it. */
  private int readEscape(int i, StringBuilder value) {
    char next = source.charAt(i + 1);
    switch (next) {
      case 'n' -> value.append('\n');
      case 't' -> value.append('\t');
      case 'r' -> value.append('\r');
      case 'b' -> value.append('\b');
      case 'f' -> value.append('\f');
      case 'v' -> value.append('\u000b');
      case '0' -> value.append('\0');
      case '\r' -> {
        // A line continuation.
        if (i + 2 < source.length() && source.charAt(i + 2) == '\n') {
          return i + 3;
        }
      }
      case '\n' -> {
        // A line continuation.
      }
      case 'u' -> {
        if (i + 2 < source.length() && source.charAt(i + 2) == '{') {
          int close = source.indexOf('}', i + 3);
          if (close > 0) {
            try {
              value.appendCodePoint(Integer.parseInt(source.substring(i + 3, close), 16));
              return close + 1;
            } catch (IllegalArgumentException e) {
              // Kept as written.
            }
          }
        } else if (i + 6 <= source.length()) {
          try {
            value.append((char) Integer.parseInt(source.substring(i + 2, i + 6), 16));
            return i + 6;
          } catch (NumberFormatException e) {
            // Kept as written.
          }
        }
        value.append(next);
      }
      case 'x' -> {
        if (i + 4 <= source.length()) {
          try {
            value.append((char) Integer.parseInt(source.substring(i + 2, i + 4), 16));
            return i + 4;
          } catch (NumberFormatException e) {
            // Kept as written.
          }
        }
        value.append(next);
      }
      default -> value.append(next);
    }
    return i + 2;
  }

  private int readTemplate(int start) {
    StringBuilder value = new StringBuilder();
    boolean complex = false;
    int i = start + 1;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '`') {
        tokens.add(new Token(Kind.TEMPLATE, source.substring(start, i + 1), start, i + 1, value.toString(), complex));
        return i + 1;
      }
      if (c == '\\' && i + 1 < source.length()) {
        i = readEscape(i, value);
      } else if (c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
        complex = true;
        // The expression matches a placeholder of the names.
        value.append("${}");
        i = skipTemplateExpression(i + 2);
      } else {
        value.append(c);
        i++;
      }
    }
    tokens.add(new Token(Kind.TEMPLATE, source.substring(start), start, source.length(), value.toString(), complex));
    return source.length();
  }

  /** Skips the expression of a template literal starting at i, returns the offset after its closing brace. */
  private int skipTemplateExpression(int i) {
    int depth = 1;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      } else if (c == '\'' || c == '"') {
        int end = i + 1;
        while (end < source.length() && source.charAt(end) != c && source.charAt(end) != '\n') {
          end += source.charAt(end) == '\\' ? 2 : 1;
        }
        i = end;
      } else if (c == '`') {
        int before = tokens.size();
        i = readTemplate(i) - 1;
        // The nested template is part of the expression, not a token.
        while (tokens.size() > before) {
          tokens.remove(tokens.size() - 1);
        }
      }
      i++;
    }
    return source.length();
  }

  private boolean regexAllowed() {
    if (tokens.isEmpty()) {
      return true;
    }
    Token previous = tokens.get(tokens.size() - 1);
    return switch (previous.kind) {
      case IDENTIFIER -> REGEX_KEYWORDS.contains(previous.text);
      case PUNCTUATOR -> !previous.is(")") && !previous.is("]") && !previous.is("}");
      default -> false;
    };
  }

  private int readRegex(int start) {
    int i = start + 1;
    boolean inClass = false;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '\n') {
        // Not a regular expression: a division.
        tokens.add(new Token(Kind.PUNCTUATOR, "/", start, start + 1, null, false));
        return start + 1;
      }
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '/' && !inClass) {
        i++;
        while (i < source.length() && Character.isLetter(source.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.REGEX, source.substring(start, i), start, i, null, false));
        return i;
      }
      i++;
    }
    tokens.add(new Token(Kind.PUNCTUATOR, "/", start, start + 1, null, false));
    return start + 1;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Calls
  // ---------------------------------------------------------------------------------------------------------------

  private List<TestBlock> findBlocks() {
    List<TestBlock> roots = new ArrayList<>();
    List<TestBlock> open = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      // Closes the blocks ended before this token.
      while (!open.isEmpty() && open.get(open.size() - 1).end <= token.start) {
        open.remove(open.size() - 1);
      }
      if (token.kind != Kind.IDENTIFIER) {
        continue;
      }
      boolean suite = SUITE_FUNCTIONS.contains(token.text);
      if (!suite && !TEST_FUNCTIONS.contains(token.text)) {
        continue;
      }
      if (index > 0 && (tokens.get(index - 1).is(".") || isDeclaration(index - 1))) {
        // A property (foo.test), or a declaration (function test()).
        continue;
      }
      TestBlock parent = open.isEmpty() ? null : open.get(open.size() - 1);
      TestBlock block = readCall(index, suite, parent);
      if (block != null) {
        if (parent == null) {
          roots.add(block);
        } else {
          parent.children.add(block);
        }
        open.add(block);
      }
    }
    return roots;
  }

  private boolean isDeclaration(int index) {
    Token token = tokens.get(index);
    return token.kind == Kind.IDENTIFIER
        && (token.text.equals("function") || token.text.equals("const") || token.text.equals("let")
            || token.text.equals("var") || token.text.equals("class") || token.text.equals("import"));
  }

  /** Reads the call starting with the function at index, null if it is not the call of a test or of a suite. */
  private TestBlock readCall(int index, boolean suite, TestBlock parent) {
    Token function = tokens.get(index);
    int i = index + 1;
    boolean template = false;
    // The modifiers: .only, .skip, .each(table), .skipIf(condition)...
    while (i + 1 < tokens.size() && tokens.get(i).is(".") && tokens.get(i + 1).kind == Kind.IDENTIFIER) {
      String modifier = tokens.get(i + 1).text;
      i += 2;
      if (CALLED_MODIFIERS.contains(modifier)) {
        template |= TEMPLATE_MODIFIERS.contains(modifier);
        if (i < tokens.size() && tokens.get(i).kind == Kind.TEMPLATE) {
          // test.each`table`
          i++;
        } else if (i < tokens.size() && tokens.get(i).is("(")) {
          i = skipBalanced(i);
          if (i < 0) {
            return null;
          }
        }
      }
    }
    if (i >= tokens.size() || !tokens.get(i).is("(")) {
      return null;
    }
    int open = i;
    if (open + 1 >= tokens.size()) {
      return null;
    }
    Token nameToken = tokens.get(open + 1);
    String name;
    boolean nameTemplate = false;
    switch (nameToken.kind) {
      case STRING -> name = nameToken.value;
      case TEMPLATE -> {
        name = nameToken.value;
        nameTemplate = nameToken.complex;
      }
      case IDENTIFIER -> {
        // describe(MyClass, ...): Vitest names the suite after the function or the class.
        Token next = open + 2 < tokens.size() ? tokens.get(open + 2) : null;
        if (next == null || !(next.is(",") || next.is(")"))) {
          return null;
        }
        name = nameToken.text;
      }
      default -> {
        return null;
      }
    }
    int close = skipBalanced(open);
    TestBlock block = new TestBlock(suite, name, template || nameTemplate, function.start, nameToken.start, parent);
    block.end = close < 0 ? source.length() : tokens.get(close - 1).end;
    if (parent != null && block.end > parent.end) {
      block.end = parent.end;
    }
    return block;
  }

  /** Returns the index after the token closing the bracket at index, -1 if it is not closed. */
  private int skipBalanced(int index) {
    int depth = 0;
    for (int i = index; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.is("(") || token.is("[") || token.is("{")) {
        depth++;
      } else if (token.is(")") || token.is("]") || token.is("}")) {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return -1;
  }
}
