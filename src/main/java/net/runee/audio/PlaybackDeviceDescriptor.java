package net.runee.audio;

import java.util.Objects;

public class PlaybackDeviceDescriptor {
    private final String id;
    private final String displayName;
    private final String backendDisplayName;

    public PlaybackDeviceDescriptor(String id, String displayName, String backendDisplayName) {
        this.id = id;
        this.displayName = Objects.requireNonNull(displayName);
        this.backendDisplayName = Objects.requireNonNull(backendDisplayName);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getListLabel() {
        return displayName + " [" + backendDisplayName + "]";
    }
}
