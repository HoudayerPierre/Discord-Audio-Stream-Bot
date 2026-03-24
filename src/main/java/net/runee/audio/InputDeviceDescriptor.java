package net.runee.audio;

import java.util.Objects;

public class InputDeviceDescriptor {
    private final RecordingBackend backend;
    private final String id;
    private final String displayName;

    public InputDeviceDescriptor(RecordingBackend backend, String id, String displayName) {
        this.backend = Objects.requireNonNull(backend);
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
    }

    public RecordingBackend getBackend() {
        return backend;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getListLabel() {
        if (backend == RecordingBackend.BASS) {
            return displayName;
        }
        return displayName + " [" + backend.getDisplayName() + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InputDeviceDescriptor)) {
            return false;
        }
        InputDeviceDescriptor that = (InputDeviceDescriptor) o;
        return backend == that.backend && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(backend, id);
    }
}
