package net.midiandmore.newserv.hostserv;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class JsonConfig {
    private final Map<String, String> values;

    private JsonConfig(Map<String, String> values) {
        this.values = values;
    }

    static JsonConfig load(Path path) throws Exception {
        Map<String, String> values = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonArray entries = Json.createReader(reader).readArray();
            entries.getValuesAs(JsonObjectHolder::new).forEach(entry -> values.put(entry.name(), entry.value()));
        }
        return new JsonConfig(values);
    }

    String get(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    int intValue(String name, int fallback) {
        try {
            return Integer.parseInt(get(name, Integer.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record JsonObjectHolder(String name, String value) {
        private JsonObjectHolder(jakarta.json.JsonValue value) {
            this(((jakarta.json.JsonObject) value).getString("name"), ((jakarta.json.JsonObject) value).getString("value"));
        }
    }
}
