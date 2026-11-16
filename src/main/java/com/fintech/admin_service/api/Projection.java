package com.fintech.admin_service.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allow-list of fields. Whatever a source sends, only the named, non-personal fields leave this service: a field added to a
 * downstream response later (a name, an e-mail, an amount) is dropped here rather than shown to staff.
 */
final class Projection {

    private Projection() {
    }

    static Map<String, Object> pick(Map<String, Object> source, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (source.containsKey(key)) {
                out.put(key, source.get(key));
            }
        }
        return out;
    }

    static List<Map<String, Object>> pickAll(List<Map<String, Object>> rows, String... keys) {
        return rows == null ? List.of() : rows.stream().map(r -> pick(r, keys)).toList();
    }
}
