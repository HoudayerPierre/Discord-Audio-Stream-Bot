package net.runee.audio;

public enum RecordingBackend {
    BASS("BASS"),
    JAVASOUND("Java Sound"),
    PULSE("PulseAudio");

    private final String displayName;

    RecordingBackend(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static RecordingBackend fromConfigValue(String value) {
        if (value == null) {
            return null;
        }
        for (RecordingBackend backend : values()) {
            if (backend.name().equalsIgnoreCase(value)) {
                return backend;
            }
        }
        return null;
    }
}
