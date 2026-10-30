package io.azalio.workflow.wrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the wrapper contract, so the core needs no third-party library on a Java 8 server.
 *
 * <p>{@link #write(Object)} handles {@code Map}, {@code List}/arrays, strings, numbers, booleans and
 * {@code null}. {@link #parseFlatObject(String)} reads a request file: one JSON object whose values are
 * strings, numbers, booleans or {@code null}; nested objects are flattened with {@code .} and arrays are
 * joined with {@code ,} - requests are flat {@code KEY=VALUE} maps like an NEI arglist.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote((String) v, sb);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 2);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                write(e.getValue(), sb, indent + 2);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Iterable) {
            List<Object> items = new ArrayList<Object>();
            for (Object o : (Iterable<?>) v) {
                items.add(o);
            }
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < items.size(); i++) {
                pad(sb, indent + 2);
                write(items.get(i), sb, indent + 2);
                sb.append(i + 1 < items.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            quote(String.valueOf(v), sb);
        }
    }

    /** One line, no indentation - for the Kestra {@code ::{...}::} output marker. */
    public static String writeCompact(Object value) {
        return write(value).replaceAll("\n\\s*", "");
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
    }

    static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    /**
     * Parses any JSON value: objects become ordered {@code Map<String, Object>}, arrays {@code List<Object>},
     * scalars {@code String} (numbers and booleans as their text), {@code null} stays null.
     */
    public static Object parse(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd()) {
            throw p.error("unexpected text after the JSON value");
        }
        return v;
    }

    /** Parses one JSON object into a flat, ordered {@code KEY -> value} map. */
    public static Map<String, String> parseFlatObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("The request must be a JSON object");
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        flatten("", v, out);
        return out;
    }

    private static void flatten(String prefix, Object v, Map<String, String> out) {
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String k = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(k, e.getValue(), out);
            }
        } else if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) v) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(o == null ? "" : o);
            }
            out.put(prefix, sb.toString());
        } else {
            out.put(prefix, v == null ? "" : String.valueOf(v));
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("Invalid JSON at position " + i + ": " + what);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            skipWs();
            if (atEnd()) {
                throw error("value expected");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return "true";
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return "false";
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(i) != '"') {
                    throw error("object key expected");
                }
                String k = string();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') {
                    throw error("':' expected");
                }
                i++;
                m.put(k, value());
                skipWs();
                if (atEnd()) {
                    throw error("'}' expected");
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("',' or '}' expected");
                }
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                skipWs();
                if (atEnd()) {
                    throw error("']' expected");
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("',' or ']' expected");
                }
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw error("bad \\u escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw error("bad escape \\" + e);
                }
            }
        }

        String number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("value expected");
            }
            return s.substring(start, i);
        }
    }
}
