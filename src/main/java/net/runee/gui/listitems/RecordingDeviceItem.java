package net.runee.gui.listitems;

import net.runee.audio.InputDeviceDescriptor;

public class RecordingDeviceItem {
    private final InputDeviceDescriptor descriptor;

    public RecordingDeviceItem(InputDeviceDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public String getName() {
        return descriptor != null ? descriptor.getDisplayName() : null;
    }

    public InputDeviceDescriptor getDescriptor() {
        return descriptor;
    }
}
