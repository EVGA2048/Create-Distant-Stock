package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.distantstock.stock.StockCache;

import java.util.List;
import java.util.UUID;

final class StockHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LinkHttp.reply(ex, 405, "{\"ok\":false}");
                return;
            }
            if (!LinkHttp.auth(ex)) {
                return;
            }
            UUID freq = LinkHttp.queryUuid(ex.getRequestURI().getRawQuery(), "freq");
            List<StockCache.Entry> list = freq == null ? List.of() : StockCache.get(freq);
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                StockCache.Entry e = list.get(i);
                sb.append("{\"id\":\"").append(LinkHttp.jsonEsc(e.itemId))
                        .append("\",\"n\":").append(e.count).append('}');
            }
            sb.append("]}");
            LinkHttp.reply(ex, 200, sb.toString());
        } catch (Exception e) {
            LinkHttp.reply(ex, 500, "{\"ok\":false}");
        }
    }
}
