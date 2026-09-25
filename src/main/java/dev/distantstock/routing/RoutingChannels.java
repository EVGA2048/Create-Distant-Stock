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
    public static final String DISTANT_NETWORK_JOIN_REQUEST = "distantstock:v1.network.join.request";
    public static final String DISTANT_NETWORK_JOIN_ACCEPT = "distantstock:v1.network.join.accept";
    public static final String DISTANT_NETWORK_DELETE = "distantstock:v1.network.delete";
    public static final String RECEIVER_PROBE_REQUEST = "distantstock:v1.receiver.probe.request";
    public static final String RECEIVER_PROBE_RESULT = "distantstock:v1.receiver.probe.result";

    private static final List<String> ALL = List.of(
            NETWORK_ANNOUNCE, STOCK_QUERY, STOCK_RESULT,
            ORDER_REQUEST, ORDER_RESULT, PACKAGE_DISPATCH, PACKAGE_STRIP,
            DISTANT_NETWORK_JOIN_REQUEST, DISTANT_NETWORK_JOIN_ACCEPT, DISTANT_NETWORK_DELETE,
            RECEIVER_PROBE_REQUEST, RECEIVER_PROBE_RESULT);

    public static List<String> all() {
        return ALL;
    }

    private RoutingChannels() {
    }
}
