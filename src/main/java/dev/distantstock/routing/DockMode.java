package dev.distantstock.routing;

/** Defines which side of a remote dock inventory may participate in routing. */
public enum DockMode {
    SEND,
    RECEIVE,
    BIDIRECTIONAL;

    /**
     * The next mode round, for the wrench.
     *
     * <p>Receiving comes first because that is what a dock does before anyone configures it, so a
     * player who cycles past the one they wanted passes through the default on the way back to it.
     */
    public DockMode next() {
        return switch (this) {
            case RECEIVE -> SEND;
            case SEND -> BIDIRECTIONAL;
            case BIDIRECTIONAL -> RECEIVE;
        };
    }
}
