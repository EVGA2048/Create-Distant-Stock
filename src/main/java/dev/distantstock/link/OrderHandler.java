package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OrderHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                LinkHttp.reply(ex, 405, "{\"ok\":false}");
                return;
            }
            if (!LinkHttp.auth(ex)) {
                return;
            }
            String body = LinkHttp.readBody(ex);
            UUID freq;
            try {
                freq = UUID.fromString(LinkHttp.field(body, "freq"));
            } catch (Exception e) {
                LinkHttp.reply(ex, 400, "{\"ok\":false,\"err\":\"freq\"}");
                return;
            }
            String address = LinkHttp.field(body, "address");
            List<LinkQueues.Line> items = parseItems(body);
            if (items.isEmpty()) {
                LinkHttp.reply(ex, 400, "{\"ok\":false,\"err\":\"items\"}");
                return;
            }
            if (!LinkQueues.offerInboundOrder(new LinkQueues.Order(freq, address, items))) {
                LinkHttp.reply(ex, 503, "{\"ok\":false,\"err\":\"full\"}");
                return;
            }
            LinkHttp.reply(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            LinkHttp.reply(ex, 500, "{\"ok\":false}");
        }
    }

    static List<LinkQueues.Line> parseItems(String json) {
        List<LinkQueues.Line> out = new ArrayList<>();
        int arr = json.indexOf("\"items\"");
        if (arr < 0) {
            return out;
        }
        int lb = json.indexOf('[', arr);
        int rb = json.indexOf(']', lb);
        if (lb < 0 || rb < 0) {
            return out;
        }
        String inner = json.substring(lb + 1, rb);
        int from = 0;
        while (from < inner.length()) {
            int o = inner.indexOf('{', from);
            if (o < 0) {
                break;
            }
            int c = inner.indexOf('}', o);
            if (c < 0) {
                break;
            }
            String obj = inner.substring(o, c + 1);
            String id = LinkHttp.field(obj, "id");
            int n = 0;
            try {
                n = Integer.parseInt(LinkHttp.field(obj, "n"));
            } catch (Exception ignored) {
            }
            if (!id.isEmpty() && n > 0) {
                out.add(new LinkQueues.Line(id, n));
            }
            from = c + 1;
        }
        return out;
    }
}
