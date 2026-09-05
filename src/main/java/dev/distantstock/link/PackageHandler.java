package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

final class PackageHandler implements HttpHandler {
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
            String nbt = LinkHttp.field(body, "nbt");
            if (nbt.isEmpty()) {
                LinkHttp.reply(ex, 400, "{\"ok\":false,\"err\":\"nbt\"}");
                return;
            }
            String address = LinkHttp.field(body, "address");
            if (!LinkQueues.offerInboundPackage(new LinkQueues.Parcel(nbt, address))) {
                LinkHttp.reply(ex, 503, "{\"ok\":false,\"err\":\"full\"}");
                return;
            }
            LinkHttp.reply(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            LinkHttp.reply(ex, 500, "{\"ok\":false}");
        }
    }
}
