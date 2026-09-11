package dev.distantstock.routing;

import java.util.Optional;

/**
 * Where a parcel goes, in strict order. A parcel carrying its own route always wins; a Create order ID is
 * looked up next; only a plain parcel with neither falls back to the sending dock's configured default.
 * When all three are empty the parcel must stay in the dock and the player has to configure a route:
 * guessing a node or picking a random one is never allowed.
 */
public final class RouteResolution {
    public static Optional<RemoteRoute> resolve(Optional<RemoteRoute> packageRoute,
                                                Optional<RemoteRoute> orderRoute,
                                                Optional<RemoteRoute> dockDefault) {
        if (packageRoute.isPresent()) {
            return packageRoute;
        }
        if (orderRoute.isPresent()) {
            return orderRoute;
        }
        return dockDefault;
    }

    private RouteResolution() {
    }
}
