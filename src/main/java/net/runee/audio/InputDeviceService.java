package net.runee.audio;

import jouvieje.bass.Bass;
import jouvieje.bass.structures.BASS_DEVICEINFO;
import net.runee.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class InputDeviceService {
    private static final Logger logger = LoggerFactory.getLogger(InputDeviceService.class);

    private InputDeviceService() {
    }

    public static List<InputDeviceDescriptor> listInputDevices() {
        List<InputDeviceDescriptor> devices = new ArrayList<>();
        devices.addAll(listBassInputDevices());
        if (isLinux()) {
            devices.addAll(listJavaSoundInputDevices());
            devices.addAll(listPulseInputDevices());
        }
        devices.sort(Comparator
                .comparingInt(InputDeviceService::deviceRank)
                .thenComparing(InputDeviceDescriptor::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(device -> device.getBackend().name())
                .thenComparing(InputDeviceDescriptor::getId));
        return devices;
    }

    public static InputDeviceDescriptor resolveConfiguredInputDevice(Config cfg) {
        if (cfg == null) {
            return null;
        }

        List<InputDeviceDescriptor> devices = listInputDevices();
        RecordingBackend configuredBackend = RecordingBackend.fromConfigValue(cfg.recordingBackend);
        if (configuredBackend != null && cfg.recordingDeviceId != null) {
            for (InputDeviceDescriptor device : devices) {
                if (device.getBackend() == configuredBackend && Objects.equals(device.getId(), cfg.recordingDeviceId)) {
                    return device;
                }
            }
        }

        String legacyName = cfg.recordingDeviceName != null ? cfg.recordingDeviceName : cfg.recordingDevice;
        if (legacyName != null) {
            for (InputDeviceDescriptor device : devices) {
                if (Objects.equals(device.getDisplayName(), legacyName)) {
                    return device;
                }
            }
        }

        return null;
    }

    public static void applySelection(Config cfg, InputDeviceDescriptor descriptor) {
        if (cfg == null) {
            return;
        }
        if (descriptor == null) {
            cfg.recordingBackend = null;
            cfg.recordingDeviceId = null;
            cfg.recordingDeviceName = null;
            cfg.recordingDevice = null;
            return;
        }
        cfg.recordingBackend = descriptor.getBackend().name();
        cfg.recordingDeviceId = descriptor.getId();
        cfg.recordingDeviceName = descriptor.getDisplayName();
        cfg.recordingDevice = descriptor.getDisplayName();
    }

    public static int getBassRecordingDeviceHandle(String deviceName) {
        if (deviceName == null) {
            return 0;
        }
        BASS_DEVICEINFO info = BASS_DEVICEINFO.allocate();
        try {
            for (int device = 0; Bass.BASS_RecordGetDeviceInfo(device, info); device++) {
                if (deviceName.equals(info.getName())) {
                    return device;
                }
            }
        } finally {
            info.release();
        }
        throw new IllegalStateException("BASS recording device '" + deviceName + "' not found");
    }

    public static Mixer.Info findJavaSoundMixerInfo(String id) {
        if (id == null) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (id.equals(buildJavaSoundId(info))) {
                return info;
            }
        }
        return null;
    }

    public static void logLinuxAudioDiscovery(Config cfg) {
        if (!isLinux()) {
            return;
        }

        logger.info("Linux audio discovery: persisted backend={}, id={}, name={}, legacyName={}",
                cfg != null ? cfg.recordingBackend : null,
                cfg != null ? cfg.recordingDeviceId : null,
                cfg != null ? cfg.recordingDeviceName : null,
                cfg != null ? cfg.recordingDevice : null);

        BASS_DEVICEINFO info = BASS_DEVICEINFO.allocate();
        try {
            for (int device = 0; Bass.BASS_RecordGetDeviceInfo(device, info); device++) {
                logger.info("BASS input device [{}]: {}", device, info.getName());
            }
            for (int device = 0; Bass.BASS_GetDeviceInfo(device, info); device++) {
                logger.info("BASS playback device [{}]: {}", device, info.getName());
            }
        } finally {
            info.release();
        }

        for (InputDeviceDescriptor device : listJavaSoundInputDevices()) {
            logger.info("Java Sound input device [{}]: {}", device.getId(), device.getDisplayName());
        }
        for (InputDeviceDescriptor device : listPulseInputDevices()) {
            logger.info("Pulse input device [{}]: {}", device.getId(), device.getDisplayName());
        }

        InputDeviceDescriptor selected = resolveConfiguredInputDevice(cfg);
        if (selected != null) {
            logger.info("Configured input device resolved to {} [{}] via {}",
                    selected.getDisplayName(),
                    selected.getId(),
                    selected.getBackend().getDisplayName());
        } else {
            logger.warn("Configured input device could not be resolved from current discovery results");
        }
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static List<InputDeviceDescriptor> listBassInputDevices() {
        List<InputDeviceDescriptor> devices = new ArrayList<>();
        BASS_DEVICEINFO info = BASS_DEVICEINFO.allocate();
        try {
            for (int device = 0; Bass.BASS_RecordGetDeviceInfo(device, info); device++) {
                devices.add(new InputDeviceDescriptor(RecordingBackend.BASS, info.getName(), info.getName()));
            }
        } finally {
            info.release();
        }
        return devices;
    }

    private static List<InputDeviceDescriptor> listJavaSoundInputDevices() {
        List<InputDeviceDescriptor> devices = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                if (!supportsTargetDataLine(mixer)) {
                    continue;
                }
                devices.add(new InputDeviceDescriptor(
                        RecordingBackend.JAVASOUND,
                        buildJavaSoundId(info),
                        buildJavaSoundDisplayName(info)
                ));
            } catch (Exception ex) {
                logger.debug("Skipping Java Sound mixer {}", info.getName(), ex);
            }
        }
        return devices;
    }

    private static List<InputDeviceDescriptor> listPulseInputDevices() {
        List<InputDeviceDescriptor> devices = new ArrayList<>();
        if (!isCommandAvailable("pactl")) {
            return devices;
        }

        Process process = null;
        try {
            process = new ProcessBuilder("pactl", "list", "short", "sources")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    InputDeviceDescriptor device = parsePulseSourceLine(line);
                    if (device != null) {
                        devices.add(device);
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("pactl list short sources exited with code {}", exitCode);
            }
        } catch (IOException ex) {
            logger.warn("Failed to enumerate PulseAudio/PipeWire sources via pactl", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while enumerating PulseAudio/PipeWire sources");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return devices;
    }

    private static boolean supportsTargetDataLine(Mixer mixer) {
        for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
            if (TargetDataLine.class.isAssignableFrom(lineInfo.getLineClass())) {
                return true;
            }
            if (lineInfo instanceof javax.sound.sampled.DataLine.Info) {
                return true;
            }
        }
        return false;
    }

    private static String buildJavaSoundId(Mixer.Info info) {
        return info.getName() + "|" + info.getVendor() + "|" + info.getVersion() + "|" + info.getDescription();
    }

    private static String buildJavaSoundDisplayName(Mixer.Info info) {
        String description = info.getDescription();
        if (description == null || description.isBlank() || Objects.equals(description, info.getName())) {
            return info.getName();
        }
        return info.getName() + " - " + description;
    }

    private static int deviceRank(InputDeviceDescriptor descriptor) {
        String name = descriptor.getDisplayName().toLowerCase(Locale.ROOT);
        int keywordRank = 1;
        if (name.contains("monitor") || name.contains("pipewire") || name.contains("pulse")
                || name.contains("loopback") || name.contains("cable")) {
            keywordRank = 0;
        }
        int backendRank = descriptor.getBackend() == RecordingBackend.BASS ? 0 : 1;
        return keywordRank * 10 + backendRank;
    }

    private static InputDeviceDescriptor parsePulseSourceLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        String[] parts = line.split("\\t");
        if (parts.length < 2) {
            return null;
        }

        String sourceName = parts[1].trim();
        if (sourceName.isEmpty()) {
            return null;
        }

        String displayName = sourceName;
        if (parts.length >= 5) {
            displayName += " - " + parts[4].trim();
        }
        if (parts.length >= 6 && !parts[5].isBlank()) {
            displayName += " (" + parts[5].trim() + ")";
        }

        return new InputDeviceDescriptor(RecordingBackend.PULSE, sourceName, displayName);
    }

    private static boolean isCommandAvailable(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", "command -v " + command).start();
            return process.waitFor() == 0;
        } catch (IOException ex) {
            logger.debug("Command check failed for {}", command, ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
