package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import dev.distantstock.config.StockConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LinkHttp {
    static boolean auth(HttpExchange ex) {
        String token = StockConfig.TOKEN.get();
        if (token == null || token.isEmpty()) {
            return true;
        }
        String got = ex.getRequestHeaders().getFirst("X-DistantStock-Token");
        if (token.equals(got)) {
            return true;
        }
        reply(ex, 403, "{\"ok\":false,\"err\":\"token\"}");
        return false;
    }

    static void reply(HttpExchange ex, int code, String body) {
        try {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(code, raw.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(raw);
            }
        } catch (Exception ignored) {
        }
    }

    static String readBody(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static InetSocketAddress parseBind(String bind) {
        String host = "0.0.0.0";
        int port = 18772;
        if (bind != null && !bind.isEmpty()) {
            int i = bind.lastIndexOf(':');
            if (i < 0) {
                port = Integer.parseInt(bind.trim());
            } else {
                String h = bind.substring(0, i).trim();
                if (!h.isEmpty()) {
                    host = h;
                }
                port = Integer.parseInt(bind.substring(i + 1).trim());
            }
        }
        return new InetSocketAddress(host, port);
    }

    static UUID queryUuid(String q, String key) {
        String raw = query(q, key);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static String query(String q, String key) {
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equals(part.substring(0, eq))) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String jsonEsc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String field(String json, String key) {
        if (json == null) {
            return "";
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length()) {
            return "";
        }
        if (json.charAt(p) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int j = p + 1; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '\\' && j + 1 < json.length()) {
                    sb.append(json.charAt(++j));
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }
        int end = p;
        while (end < json.length() && ",}] \t\n\r".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(p, end);
    }

    private LinkHttp() {
    }
}
