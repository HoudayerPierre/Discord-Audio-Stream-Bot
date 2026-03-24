package net.runee.audio;

import net.runee.ListenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaybackDeviceService {
    private static final Logger logger = LoggerFactory.getLogger(PlaybackDeviceService.class);

    private PlaybackDeviceService() {
    }

    public static List<PlaybackDeviceDescriptor> listPlaybackDevices() {
        List<PlaybackDeviceDescriptor> devices = new ArrayList<>();
        if (ListenHandler.isLinux()) {
            devices.addAll(listPulseCaptureStreamTargets());
            devices.addAll(listJavaSoundPlaybackDevices());
            devices.addAll(listPulsePlaybackDevices());
        } else {
            devices.addAll(listBassPlaybackDevices());
        }
        devices.sort(Comparator.comparing(PlaybackDeviceDescriptor::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return devices;
    }

    private static List<PlaybackDeviceDescriptor> listPulseCaptureStreamTargets() {
        List<PlaybackDeviceDescriptor> devices = new ArrayList<>();
        if (!isCommandAvailable("pactl")) {
            return devices;
        }

        Process process = null;
        try {
            process = new ProcessBuilder("pactl", "list", "source-outputs")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                Map<String, String> properties = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Source Output #")) {
                        addPulseCaptureStreamTarget(devices, properties);
                        properties.clear();
                        continue;
                    }
                    int equalsIndex = trimmed.indexOf('=');
                    if (equalsIndex <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, equalsIndex).trim();
                    String value = trimmed.substring(equalsIndex + 1).trim();
                    properties.put(key, stripQuotes(value));
                }
                addPulseCaptureStreamTarget(devices, properties);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("pactl list source-outputs exited with code {}", exitCode);
            }
        } catch (IOException ex) {
            logger.warn("Failed to enumerate PulseAudio/PipeWire source-outputs via pactl", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while enumerating PulseAudio/PipeWire source-outputs");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return devices;
    }

    private static List<PlaybackDeviceDescriptor> listJavaSoundPlaybackDevices() {
        List<PlaybackDeviceDescriptor> devices = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!supportsSourceDataLine(mixer)) {
                    continue;
                }
                devices.add(new PlaybackDeviceDescriptor(
                        ListenHandler.buildJavaSoundId(mixerInfo),
                        ListenHandler.buildJavaSoundDisplayName(mixerInfo),
                        "Java Sound"
                ));
            } catch (Exception ex) {
                logger.debug("Skipping Java Sound playback mixer {}", mixerInfo.getName(), ex);
            }
        }
        return devices;
    }

    private static List<PlaybackDeviceDescriptor> listPulsePlaybackDevices() {
        List<PlaybackDeviceDescriptor> devices = new ArrayList<>();
        if (!isCommandAvailable("pactl")) {
            return devices;
        }

        Process process = null;
        try {
            process = new ProcessBuilder("pactl", "list", "short", "sinks")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    PlaybackDeviceDescriptor device = parsePulseSinkLine(line);
                    if (device != null) {
                        devices.add(device);
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("pactl list short sinks exited with code {}", exitCode);
            }
        } catch (IOException ex) {
            logger.warn("Failed to enumerate PulseAudio/PipeWire sinks via pactl", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while enumerating PulseAudio/PipeWire sinks");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return devices;
    }

    private static List<PlaybackDeviceDescriptor> listBassPlaybackDevices() {
        List<PlaybackDeviceDescriptor> devices = new ArrayList<>();
        jouvieje.bass.structures.BASS_DEVICEINFO info = jouvieje.bass.structures.BASS_DEVICEINFO.allocate();
        try {
            for (int device = 0; jouvieje.bass.Bass.BASS_GetDeviceInfo(device, info); device++) {
                devices.add(new PlaybackDeviceDescriptor(info.getName(), info.getName(), "BASS"));
            }
        } finally {
            info.release();
        }
        return devices;
    }

    private static PlaybackDeviceDescriptor parsePulseSinkLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        String[] parts = line.split("\\t");
        if (parts.length < 2) {
            return null;
        }

        String sinkName = parts[1].trim();
        if (sinkName.isEmpty()) {
            return null;
        }

        String displayName = sinkName;
        if (parts.length >= 5 && !parts[4].isBlank()) {
            displayName += " (" + parts[4].trim() + ")";
        }

        return new PlaybackDeviceDescriptor(
                ListenHandler.buildPulsePlaybackId(sinkName),
                displayName,
                "PulseAudio"
        );
    }

    private static void addPulseCaptureStreamTarget(List<PlaybackDeviceDescriptor> devices, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return;
        }

        if (!"true".equals(properties.get("stream.capture.sink"))) {
            return;
        }

        String targetObject = properties.get("target.object");
        String applicationName = properties.get("application.name");
        String nodeName = properties.get("node.name");
        String mediaName = properties.get("media.name");
        if (targetObject == null || targetObject.isBlank() || applicationName == null || applicationName.isBlank()) {
            return;
        }

        StringBuilder displayName = new StringBuilder(applicationName);
        if (nodeName != null && !nodeName.isBlank()) {
            displayName.append('/').append(nodeName);
        }
        if (mediaName != null && !mediaName.isBlank()) {
            displayName.append(" [").append(mediaName).append(']');
        }

        String configId = ListenHandler.buildPulseStreamTargetPlaybackId(targetObject, displayName.toString());
        devices.add(new PlaybackDeviceDescriptor(configId, displayName.toString(), "PipeWire stream"));
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static boolean supportsSourceDataLine(Mixer mixer) {
        for (Line.Info lineInfo : mixer.getSourceLineInfo()) {
            if (lineInfo instanceof javax.sound.sampled.DataLine.Info) {
                return true;
            }
        }
        return false;
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
