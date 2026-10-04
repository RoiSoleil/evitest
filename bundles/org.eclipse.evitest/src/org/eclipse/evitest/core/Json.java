package org.eclipse.evitest.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer: the events of the reporter and the data of the test elements.
 * <p>
 * Objects are read as {@link Map}s keeping the order of their members, arrays as {@link List}s, numbers as
 * {@link Double}s.
 */
public final class Json {

  private final String text;
  private int position;

  private Json(String text) {
    this.text = text;
  }

  /**
   * Reads a JSON value.
   *
   * @throws IllegalArgumentException if the text is not valid JSON
   */
  public static Object parse(String text) {
    Json json = new Json(text);
    json.skipWhitespace();
    Object value = json.readValue();
    json.skipWhitespace();
    if (json.position != text.length()) {
      throw json.error("Unexpected text after the value");
    }
    return value;
  }

  /** Reads a JSON object, or returns an empty map if the text is not an object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    if (text == null || text.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      Object value = parse(text);
      return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    } catch (IllegalArgumentException e) {
      return Collections.emptyMap();
    }
  }

  public static String getString(Map<String, Object> object, String key) {
    return object.get(key) instanceof String string ? string : null;
  }

  public static Integer getInteger(Map<String, Object> object, String key) {
    return object.get(key) instanceof Number number ? Integer.valueOf(number.intValue()) : null;
  }

  public static Long getLong(Map<String, Object> object, String key) {
    return object.get(key) instanceof Number number ? Long.valueOf(Math.round(number.doubleValue())) : null;
  }

  public static boolean getBoolean(Map<String, Object> object, String key) {
    return Boolean.TRUE.equals(object.get(key));
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> getObjects(Map<String, Object> object, String key) {
    if (!(object.get(key) instanceof List<?> list)) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object element : list) {
      if (element instanceof Map) {
        objects.add((Map<String, Object>) element);
      }
    }
    return objects;
  }

  public static List<String> getStrings(Map<String, Object> object, String key) {
    if (!(object.get(key) instanceof List<?> list)) {
      return Collections.emptyList();
    }
    List<String> strings = new ArrayList<>();
    for (Object element : list) {
      strings.add(element == null ? "" : element.toString());
    }
    return strings;
  }

  /** Writes a value: {@link Map}, {@link Iterable}, {@link String}, {@link Number}, {@link Boolean} or null. */
  public static String write(Object value) {
    StringBuilder builder = new StringBuilder();
    write(builder, value);
    return builder.toString();
  }

  private static void write(StringBuilder builder, Object value) {
    if (value == null) {
      builder.append("null");
    } else if (value instanceof String string) {
      writeString(builder, string);
    } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      builder.append(value);
    } else if (value instanceof Number number) {
      double d = number.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
        builder.append((long) d);
      } else {
        builder.append(d);
      }
    } else if (value instanceof Map<?, ?> map) {
      builder.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getValue() == null) {
          continue;
        }
        if (!first) {
          builder.append(',');
        }
        first = false;
        writeString(builder, String.valueOf(entry.getKey()));
        builder.append(':');
        write(builder, entry.getValue());
      }
      builder.append('}');
    } else if (value instanceof Iterable<?> iterable) {
      builder.append('[');
      boolean first = true;
      for (Object element : iterable) {
        if (!first) {
          builder.append(',');
        }
        first = false;
        write(builder, element);
      }
      builder.append(']');
    } else {
      writeString(builder, value.toString());
    }
  }

  private static void writeString(StringBuilder builder, String string) {
    builder.append('"');
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      switch (c) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        default -> {
          if (c < 0x20) {
            builder.append(String.format("\\u%04x", Integer.valueOf(c)));
          } else {
            builder.append(c);
          }
        }
      }
    }
    builder.append('"');
  }

  private Object readValue() {
    if (position >= text.length()) {
      throw error("Unexpected end of the text");
    }
    char c = text.charAt(position);
    switch (c) {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return readString();
      case 't':
        expect("true");
        return Boolean.TRUE;
      case 'f':
        expect("false");
        return Boolean.FALSE;
      case 'n':
        expect("null");
        return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) {
          return readNumber();
        }
        throw error("Unexpected character '" + c + "'");
    }
  }

  private Map<String, Object> readObject() {
    Map<String, Object> object = new LinkedHashMap<>();
    position++;
    skipWhitespace();
    if (peek() == '}') {
      position++;
      return object;
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw error("Expected the name of a member");
      }
      String key = readString();
      skipWhitespace();
      if (peek() != ':') {
        throw error("Expected ':'");
      }
      position++;
      skipWhitespace();
      object.put(key, readValue());
      skipWhitespace();
      char c = peek();
      position++;
      if (c == '}') {
        return object;
      }
      if (c != ',') {
        throw error("Expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray() {
    List<Object> array = new ArrayList<>();
    position++;
    skipWhitespace();
    if (peek() == ']') {
      position++;
      return array;
    }
    while (true) {
      skipWhitespace();
      array.add(readValue());
      skipWhitespace();
      char c = peek();
      position++;
      if (c == ']') {
        return array;
      }
      if (c != ',') {
        throw error("Expected ',' or ']'");
      }
    }
  }

  private String readString() {
    StringBuilder builder = new StringBuilder();
    position++;
    while (true) {
      if (position >= text.length()) {
        throw error("Unterminated string");
      }
      char c = text.charAt(position++);
      if (c == '"') {
        return builder.toString();
      }
      if (c != '\\') {
        builder.append(c);
        continue;
      }
      if (position >= text.length()) {
        throw error("Unterminated string");
      }
      char escaped = text.charAt(position++);
      switch (escaped) {
        case '"', '\\', '/' -> builder.append(escaped);
        case 'b' -> builder.append('\b');
        case 'f' -> builder.append('\f');
        case 'n' -> builder.append('\n');
        case 'r' -> builder.append('\r');
        case 't' -> builder.append('\t');
        case 'u' -> {
          if (position + 4 > text.length()) {
            throw error("Invalid unicode escape");
          }
          try {
            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
          } catch (NumberFormatException e) {
            throw error("Invalid unicode escape");
          }
          position += 4;
        }
        default -> throw error("Invalid escape '\\" + escaped + "'");
      }
    }
  }

  private Double readNumber() {
    int start = position;
    while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
      position++;
    }
    try {
      return Double.valueOf(text.substring(start, position));
    } catch (NumberFormatException e) {
      throw error("Invalid number");
    }
  }

  private void expect(String word) {
    if (!text.startsWith(word, position)) {
      throw error("Expected '" + word + "'");
    }
    position += word.length();
  }

  private char peek() {
    if (position >= text.length()) {
      throw error("Unexpected end of the text");
    }
    return text.charAt(position);
  }

  private void skipWhitespace() {
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at " + position);
  }
}
