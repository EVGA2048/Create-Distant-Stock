package dev.distantstock.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

final class StatusHandler implements HttpHandler {
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
            LinkSnapshot.View v = LinkSnapshot.view();
            String json = "{\"ok\":true"
                    + ",\"self\":\"" + LinkHttp.jsonEsc(v.selfId()) + "\""
                    + ",\"tps\":" + round(v.localTps())
                    + ",\"mspt\":" + round(v.localMspt())
                    + ",\"orders\":" + v.orderDepth()
                    + ",\"packages\":" + v.packageDepth()
                    + ",\"inFlight\":" + v.inFlight()
                    + "}";
            LinkHttp.reply(ex, 200, json);
        } catch (Exception e) {
            LinkHttp.reply(ex, 500, "{\"ok\":false}");
        }
    }

    private static String round(double n) {
        return String.format(java.util.Locale.ROOT, "%.2f", n);
    }
}
