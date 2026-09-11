package com.pointblue.dirxml.dev.packages;

import java.util.List;
import java.util.Map;

/**
 * The catalog's JSON reader/writer — a thin, package-private delegate to the
 * public {@link com.pointblue.dirxml.dev.json.Json} (promoted out of this
 * package so forms/PRD code and the CLI can use it too). {@link #write} keeps
 * the catalog's original pretty (2-space) shape.
 */
final class Json {

    private Json() {
    }

    static String write(Object root) {
        return com.pointblue.dirxml.dev.json.Json.pretty(root);
    }

    static Object parse(String s) {
        return com.pointblue.dirxml.dev.json.Json.parse(s);
    }

    static Map<String, Object> asMap(Object o) {
        return com.pointblue.dirxml.dev.json.Json.asMap(o);
    }

    static List<Object> asList(Object o) {
        return com.pointblue.dirxml.dev.json.Json.asList(o);
    }

    static String asString(Object o) {
        return com.pointblue.dirxml.dev.json.Json.asString(o);
    }

    static boolean asBool(Object o) {
        return com.pointblue.dirxml.dev.json.Json.asBool(o);
    }

    static int asInt(Object o, int dflt) {
        return com.pointblue.dirxml.dev.json.Json.asInt(o, dflt);
    }
}
