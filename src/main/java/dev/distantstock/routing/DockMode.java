package dev.distantstock.routing;

/** Defines which side of a remote dock inventory may participate in routing. */
public enum DockMode {
    SEND("goggle.distantstock.mode.export"),
    RECEIVE("goggle.distantstock.mode.import"),
    BIDIRECTIONAL("goggle.distantstock.mode.bidirectional");

    private final String translationKey;

    DockMode(String translationKey) {
        this.translationKey = translationKey;
    }

    /**
     * What this mode is called, carried on the mode itself.
     *
     * <p>The value settings panel builds its list from these, rather than from a hand-written list
     * beside the enum. A list written twice is a list that drifts: a fourth mode added to the enum
     * and forgotten in the panel would be a mode no player could choose, and nothing would say so.
     */
    public String translationKey() {
        return translationKey;
    }
}
