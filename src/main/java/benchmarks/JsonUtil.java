package benchmarks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// JSON minimal, fait main (pas de dependance externe) : suffisant pour des objets plats
// {"cle": valeur, ...} avec valeurs String/Long/Boolean/null, ce qui couvre les messages
// REST de RestMaster/RestWorker (blocs et rapports de decouverte).
final class JsonUtil {

    private static final Pattern PAIR = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}]+)");

    private JsonUtil() {}

    static String object(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(keyValues[i]).append("\":").append(render(keyValues[i + 1]));
        }
        return sb.append("}").toString();
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value.toString(); // Long/Integer/Boolean
    }

    // Retourne les paires cle -> valeur brute (sans guillemets pour les strings).
    static Map<String, String> parse(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = PAIR.matcher(json);
        while (m.find()) {
            String value = m.group(2).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            map.put(m.group(1), value);
        }
        return map;
    }
}
