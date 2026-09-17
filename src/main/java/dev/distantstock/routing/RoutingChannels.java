package dev.distantstock.routing;

import java.util.List;

/** Application-owned Transerver channel names. */
public final class RoutingChannels {
    public static final String NETWORK_ANNOUNCE = "distantstock:v1.network.announce";
    public static final String STOCK_QUERY = "distantstock:v1.stock.query";
    public static final String STOCK_RESULT = "distantstock:v1.stock.result";
    public static final String ORDER_REQUEST = "distantstock:v1.order.request";
    public static final String ORDER_RESULT = "distantstock:v1.order.result";
    public static final String PACKAGE_DISPATCH = "distantstock:v1.package.dispatch";
    public static final String PACKAGE_STRIP = "distantstock:v1.package.strip";

    private static final List<String> ALL = List.of(
            NETWORK_ANNOUNCE, STOCK_QUERY, STOCK_RESULT,
            ORDER_REQUEST, ORDER_RESULT, PACKAGE_DISPATCH, PACKAGE_STRIP);

    public static List<String> all() {
        return ALL;
    }

    private RoutingChannels() {
    }
}
