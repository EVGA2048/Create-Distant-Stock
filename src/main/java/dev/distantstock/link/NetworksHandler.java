package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.distantstock.stock.NetworkDirectory;

final class NetworksHandler implements HttpHandler {
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
            StringBuilder body = new StringBuilder("{\"ok\":true,\"networks\":[");
            var entries = NetworkDirectory.local();
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    body.append(',');
                }
                NetworkDirectory.Entry entry = entries.get(i);
                body.append("{\"freq\":\"").append(entry.freq())
                        .append("\",\"server\":\"").append(LinkHttp.jsonEsc(entry.server()))
                        .append("\",\"links\":").append(entry.links()).append('}');
            }
            LinkHttp.reply(ex, 200, body.append("]}").toString());
        } catch (Exception e) {
            LinkHttp.reply(ex, 500, "{\"ok\":false}");
        }
    }
}
