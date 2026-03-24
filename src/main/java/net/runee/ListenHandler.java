package net.runee;

import jouvieje.bass.Bass;
import jouvieje.bass.structures.HSTREAM;
import jouvieje.bass.utils.Pointer;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.runee.errors.BassException;
import net.runee.misc.MemoryQueue;
import net.runee.misc.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.dv8tion.jda.api.audio.AudioSendHandler.INPUT_FORMAT;

public class ListenHandler implements AudioReceiveHandler, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(ListenHandler.class);

    public static final int MAX_LAG = 200;
    public static final int PLAYBACK_FLAGS = 0; //BASS_DEVICE.BASS_DEVICE_3D;
    public static final int TEST_TONE_DURATION_MILLIS = 3000;
    private static final long LEVEL_HOLD_NANOS = 750_000_000L;
    private static final double LEVEL_DECAY_PER_SECOND = 1.35d;
    private static final long STATS_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final double TEST_TONE_FREQUENCY = 440.0d;
    private static final int TEST_TONE_AMPLITUDE = 12000;
    private static final String PULSE_PLAYBACK_PREFIX = "pulse:";
    private static final String PULSE_STREAM_TARGET_PREFIX = "pulse-stream:";
    private static final List<ListenHandler> activeHandlers = new ArrayList<>();
    private static volatile String standaloneVisualPlaybackDeviceId;
    private static volatile double standaloneVisualLevel;
    private static volatile long standaloneVisualLevelNanos;

    private final Object memoryQueueLock = new Object();
    private int playbackDevice;
    private String playbackDeviceId;
    private String playbackDeviceName;
    private HSTREAM playbackStream;
    private SourceDataLine javaSoundLine;
    private Thread javaSoundPlaybackThread;
    private boolean javaSoundBigEndian;
    private Process pulsePlaybackProcess;
    private Thread pulsePlaybackThread;
    private MemoryQueue memoryQueue;
    private boolean closed;
    private volatile double visualLevel;
    private volatile long visualLevelNanos;
    private long receivedFrames;
    private long receivedBytes;
    private long playedBytes;
    private int statsPeakAbs;
    private long statsWindowStartNanos = System.nanoTime();
    private long statsLastLogNanos = statsWindowStartNanos;

    public ListenHandler() {
        this.playbackDevice = -1;
    }

    public void openPlaybackDevice(String playbackDeviceId) throws BassException {
        Utils.closeQuiet(this);

        this.playbackDevice = -1;
        this.playbackDeviceId = playbackDeviceId;
        this.playbackDeviceName = playbackDeviceId != null ? playbackDeviceId : "(Default playback device)";
        memoryQueue = new MemoryQueue();

        if (isPulsePlaybackDeviceId(playbackDeviceId)) {
            openPulsePlaybackDevice(playbackDeviceId);
            closed = false;
            activeHandlers.add(this);
            return;
        }

        if (isLinux() && tryOpenJavaSoundPlaybackDevice(playbackDeviceId)) {
            logger.info("Using Java Sound playback on Linux for '{}'", playbackDeviceName);
            closed = false;
            activeHandlers.add(this);
            return;
        }

        if (playbackDeviceId != null && findJavaSoundOutputMixerInfo(playbackDeviceId) != null) {
            throw new RuntimeException("Java Sound playback device '" + playbackDeviceId + "' could not be opened with a supported format");
        }

        this.playbackDevice = Utils.getPlaybackDeviceHandle(playbackDeviceId);
        this.playbackDeviceName = playbackDeviceId != null ? playbackDeviceId : "Default";

        HSTREAM playbackStream = null;
        for (ListenHandler handler : activeHandlers) {
            if (!handler.closed && handler.playbackStream != null && handler.playbackDevice == playbackDevice) {
                playbackStream = handler.playbackStream;
                break;
            }
        }

        if (playbackStream != null) {
            this.playbackStream = playbackStream;
        } else {
            try {
                if (!Bass.BASS_Init(playbackDevice, (int) OUTPUT_FORMAT.getSampleRate(), PLAYBACK_FLAGS, null, null)) {
                    Utils.checkBassError();
                }
                this.playbackStream = Bass.BASS_StreamCreate((int) OUTPUT_FORMAT.getSampleRate(), OUTPUT_FORMAT.getChannels(), PLAYBACK_FLAGS, ListenHandler::STREAMPROC, null);
                Utils.checkBassError();
                Bass.BASS_ChannelPlay(this.playbackStream.asInt(), false);
            } catch (BassException ex) {
                if (!tryOpenJavaSoundPlaybackDevice(playbackDeviceId)) {
                    Utils.closeQuiet(this);
                    throw ex;
                }
                logger.warn("Falling back to Java Sound playback for '{}'", playbackDeviceName, ex);
            }
        }

        closed = false;
        activeHandlers.add(this);
    }

    public static boolean isLinux() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    public static String buildPulsePlaybackId(String sinkName) {
        return sinkName != null ? PULSE_PLAYBACK_PREFIX + sinkName : null;
    }

    public static String buildPulseStreamTargetPlaybackId(String sinkName, String streamDisplayName) {
        if (sinkName == null) {
            return null;
        }
        String encodedDisplay = streamDisplayName != null ? streamDisplayName.replace("|", "/") : "";
        return PULSE_STREAM_TARGET_PREFIX + sinkName + "|" + encodedDisplay;
    }

    public static boolean isPulsePlaybackDeviceId(String playbackDeviceId) {
        return playbackDeviceId != null
                && (playbackDeviceId.startsWith(PULSE_PLAYBACK_PREFIX)
                || playbackDeviceId.startsWith(PULSE_STREAM_TARGET_PREFIX));
    }

    @Override
    public boolean canReceiveCombined() {
        return !closed;
    }

    public static double getVisualLevel(String playbackDeviceId) {
        long now = System.nanoTime();
        double level = getDecayedStandaloneVisualLevel(playbackDeviceId, now);
        for (ListenHandler handler : new ArrayList<>(activeHandlers)) {
            if (playbackDeviceId == null || playbackDeviceId.equals(handler.playbackDeviceId)) {
                level = Math.max(level, handler.getDecayedVisualLevel(now));
            }
        }
        return level;
    }

    public void playTestTone() {
        int channels = OUTPUT_FORMAT.getChannels();
        int bytesPerSample = OUTPUT_FORMAT.getSampleSizeInBits() / 8;
        int frameCount = (int) ((OUTPUT_FORMAT.getSampleRate() * TEST_TONE_DURATION_MILLIS) / 1000);
        byte[] data = new byte[frameCount * channels * bytesPerSample];
        double phase = 0d;
        double phaseIncrement = (2.0d * Math.PI * TEST_TONE_FREQUENCY) / OUTPUT_FORMAT.getSampleRate();
        int peakAbs = 0;
        int index = 0;

        for (int i = 0; i < frameCount; i++) {
            short sample = (short) (Math.sin(phase) * TEST_TONE_AMPLITUDE);
            phase += phaseIncrement;
            if (phase >= 2.0d * Math.PI) {
                phase -= 2.0d * Math.PI;
            }
            peakAbs = Math.max(peakAbs, Math.abs((int) sample));
            for (int ch = 0; ch < channels; ch++) {
                data[index++] = (byte) ((sample >> 8) & 0xff);
                data[index++] = (byte) (sample & 0xff);
            }
        }

        synchronized (memoryQueueLock) {
            if (memoryQueue != null) {
                memoryQueue.enqueue(data, 0, data.length);
            }
        }
        publishVisualLevel(applyPlaybackVolume(peakAbs));
        logger.info("Queued {} ms local playback test tone for '{}'", TEST_TONE_DURATION_MILLIS, playbackDeviceName);
    }

    public void enqueueForPlayback(byte[] data, int length) {
        if (data == null || length <= 0) {
            return;
        }
        int boundedLength = Math.min(length, data.length);
        synchronized (memoryQueueLock) {
            if (memoryQueue != null) {
                memoryQueue.enqueue(data, 0, boundedLength);
            }
        }
    }

    public static void playStandaloneTestTone(String playbackDeviceId) {
        Thread thread = new Thread(() -> {
            try (ListenHandler handler = new ListenHandler()) {
                handler.openPlaybackDevice(playbackDeviceId);
                handler.playTestTone();
                publishStandaloneVisualLevel(playbackDeviceId, applyPlaybackVolume(TEST_TONE_AMPLITUDE));
                Thread.sleep(TEST_TONE_DURATION_MILLIS + 250L);
                logger.info("Played {} ms standalone local playback test tone for '{}'",
                        TEST_TONE_DURATION_MILLIS,
                        handler.playbackDeviceName);
            } catch (Exception ex) {
                logger.warn("Standalone playback test failed for '{}'", playbackDeviceId, ex);
            }
        });
        thread.setName("DASB Standalone Playback Test");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void handleCombinedAudio(@Nonnull CombinedAudio combinedAudio) {
        byte[] data = combinedAudio.getAudioData(1);
        int peakAbs = computePeakAbs(data, data.length);
        publishVisualLevel(peakAbs);
        recordReceivedAudioStats(data.length, peakAbs);
        synchronized (memoryQueueLock) {
            memoryQueue.enqueue(data, 0, data.length);
        }
        maybeLogAudioStats();
    }

    private static int STREAMPROC(HSTREAM handle, ByteBuffer buffer, int length, Pointer user) {
        List<ListenHandler> handlers = getActiveHandlers(handle);

        int bytesPerSample = OUTPUT_FORMAT.getSampleSizeInBits() / 8;

        int maxSampleCount = 0; // biggest sample count in handlers
        for (ListenHandler handler : handlers) {
            maxSampleCount = Math.max(maxSampleCount, handler.memoryQueue.size() / bytesPerSample);
        }
        int numSamplesRead = length / bytesPerSample; // amount of samples to read

        byte[] sampleBuffer = new byte[bytesPerSample];
        int peakAbs = 0;
        for (int s = 0; s < numSamplesRead; s++) {
            int mixedSample = 0;
            for (ListenHandler handler : handlers) {
                int sample;
                synchronized (handler.memoryQueueLock) {
                    int sampleCount = handler.memoryQueue.size() / bytesPerSample;
                    if (s == maxSampleCount - sampleCount) {
                        handler.memoryQueue.dequeue(sampleBuffer, 0, sampleBuffer.length);
                        sample = (short)((sampleBuffer[0] & 0xff) << 8) | ((short) (sampleBuffer[1] & 0xff));
                    } else {
                        sample = 0;
                    }
                }
                mixedSample += sample;
            }
            if (!handlers.isEmpty()) {
                mixedSample /= handlers.size();
            }
            mixedSample = applyPlaybackVolume(mixedSample);
            peakAbs = Math.max(peakAbs, Math.abs(mixedSample));
            buffer.putShort((short) mixedSample);
        }

        for (ListenHandler handler : handlers) {
            handler.publishVisualLevel(peakAbs);
        }

        for (ListenHandler handler : handlers) {
            float lag = handler.memoryQueue.size() / (INPUT_FORMAT.getChannels() * INPUT_FORMAT.getSampleRate() * (1 / 1000f) * (INPUT_FORMAT.getSampleSizeInBits() >> 3));
            if (lag >= MAX_LAG) {
                synchronized (handler.memoryQueueLock) {
                    handler.memoryQueue.clear();
                }
                logger.warn("ListenHandler is " + (int)lag + " ms behind! Clearing queue...");
            }
        }

        return numSamplesRead * bytesPerSample;
    }

    private static List<ListenHandler> getActiveHandlers(HSTREAM handle) {
        List<ListenHandler> matchingHandlers = new ArrayList<>();
        for (ListenHandler handler : new ArrayList<>(activeHandlers)) {
            if (handle.equals(handler.playbackStream)) {
                matchingHandlers.add(handler);
            }
        }
        return matchingHandlers;
    }

    @Override
    public void close() throws IOException {
        activeHandlers.remove(this);
        closed = true;
        if (playbackStream != null) {
            Bass.BASS_ChannelStop(playbackStream.asInt());
        }
        if (javaSoundLine != null) {
            javaSoundLine.stop();
            javaSoundLine.flush();
            javaSoundLine.close();
        }
        if (pulsePlaybackProcess != null) {
            pulsePlaybackProcess.destroy();
        }
        if (playbackDevice >= 0) {
            if (playbackStream != null) {
                Bass.BASS_SetDevice(playbackDevice);
                Bass.BASS_Free();
            }
        }
        memoryQueue = null;
        //buffer = null;
        playbackStream = null;
        javaSoundLine = null;
        javaSoundPlaybackThread = null;
        pulsePlaybackProcess = null;
        pulsePlaybackThread = null;
        playbackDeviceId = null;
        playbackDeviceName = null;
        playbackDevice = -1;
        if (Bass.BASS_ErrorGetCode() != 0) {
            Utils.checkBassError();
        }
    }

    private boolean tryOpenJavaSoundPlaybackDevice(String playbackDeviceId) {
        try {
            Mixer mixer = findJavaSoundOutputMixer(playbackDeviceId);
            javax.sound.sampled.AudioFormat preferredFormat = OUTPUT_FORMAT;
            DataLine.Info preferredInfo = new DataLine.Info(SourceDataLine.class, preferredFormat);
            javax.sound.sampled.AudioFormat fallbackFormat = new javax.sound.sampled.AudioFormat(
                    preferredFormat.getSampleRate(),
                    preferredFormat.getSampleSizeInBits(),
                    preferredFormat.getChannels(),
                    true,
                    false
            );
            DataLine.Info fallbackInfo = new DataLine.Info(SourceDataLine.class, fallbackFormat);

            if (mixer != null) {
                if (mixer.isLineSupported(preferredInfo)) {
                    javaSoundLine = (SourceDataLine) mixer.getLine(preferredInfo);
                    javaSoundLine.open(preferredFormat);
                    javaSoundBigEndian = preferredFormat.isBigEndian();
                } else if (mixer.isLineSupported(fallbackInfo)) {
                    javaSoundLine = (SourceDataLine) mixer.getLine(fallbackInfo);
                    javaSoundLine.open(fallbackFormat);
                    javaSoundBigEndian = fallbackFormat.isBigEndian();
                } else {
                    return false;
                }
                playbackDeviceName = buildJavaSoundDisplayName(mixer.getMixerInfo());
            } else {
                try {
                    javaSoundLine = (SourceDataLine) AudioSystem.getLine(preferredInfo);
                    javaSoundLine.open(preferredFormat);
                    javaSoundBigEndian = preferredFormat.isBigEndian();
                } catch (LineUnavailableException unsupportedPreferred) {
                    javaSoundLine = (SourceDataLine) AudioSystem.getLine(fallbackInfo);
                    javaSoundLine.open(fallbackFormat);
                    javaSoundBigEndian = fallbackFormat.isBigEndian();
                }
                playbackDeviceName = "(Default playback device)";
            }
            javaSoundLine.start();
            startJavaSoundPlaybackThread();
            return true;
        } catch (LineUnavailableException ex) {
            logger.warn("Java Sound playback fallback failed for '{}'", playbackDeviceName, ex);
            return false;
        }
    }

    private void openPulsePlaybackDevice(String playbackDeviceId) {
        if (!isLinux()) {
            throw new RuntimeException("Pulse playback devices are only supported on Linux");
        }
        if (!isCommandAvailable("pacat")) {
            throw new RuntimeException("pacat is not available for Pulse playback");
        }

        String sinkName = resolvePulseSinkName(playbackDeviceId);
        if (sinkName.isBlank()) {
            throw new RuntimeException("Invalid Pulse playback device id: " + playbackDeviceId);
        }

        try {
            pulsePlaybackProcess = new ProcessBuilder(
                    "pacat",
                    "--playback",
                    "--raw",
                    "--rate=" + (int) OUTPUT_FORMAT.getSampleRate(),
                    "--channels=" + OUTPUT_FORMAT.getChannels(),
                    "--format=s16be",
                    "--device=" + sinkName
            ).redirectErrorStream(true).start();
            playbackDeviceName = sinkName;
            startPulsePlaybackThread();
            logger.info("Using Pulse playback on Linux for '{}'", playbackDeviceName);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to open Pulse playback device '" + sinkName + "'", ex);
        }
    }

    private String resolvePulseSinkName(String playbackDeviceId) {
        if (playbackDeviceId == null) {
            return "";
        }
        if (playbackDeviceId.startsWith(PULSE_PLAYBACK_PREFIX)) {
            return playbackDeviceId.substring(PULSE_PLAYBACK_PREFIX.length());
        }
        if (playbackDeviceId.startsWith(PULSE_STREAM_TARGET_PREFIX)) {
            String encoded = playbackDeviceId.substring(PULSE_STREAM_TARGET_PREFIX.length());
            int separator = encoded.indexOf('|');
            return separator >= 0 ? encoded.substring(0, separator) : encoded;
        }
        return playbackDeviceId;
    }

    public static Mixer findJavaSoundOutputMixer(String playbackDeviceId) {
        if (playbackDeviceId == null) {
            return null;
        }
        Mixer.Info resolvedMixerInfo = findJavaSoundOutputMixerInfo(playbackDeviceId);
        if (resolvedMixerInfo != null) {
            return AudioSystem.getMixer(resolvedMixerInfo);
        }
        String normalizedName = playbackDeviceId.toLowerCase(Locale.ROOT);
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, OUTPUT_FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (!mixer.isLineSupported(lineInfo)) {
                continue;
            }
            String candidate = (mixerInfo.getName() + " " + mixerInfo.getDescription()).toLowerCase(Locale.ROOT);
            if (candidate.contains(normalizedName) || normalizedName.contains(mixerInfo.getName().toLowerCase(Locale.ROOT))) {
                return mixer;
            }
        }
        return null;
    }

    public static Mixer.Info findJavaSoundOutputMixerInfo(String id) {
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

    public static String buildJavaSoundId(Mixer.Info info) {
        return info.getName() + "|" + info.getVendor() + "|" + info.getVersion() + "|" + info.getDescription();
    }

    public static String buildJavaSoundDisplayName(Mixer.Info info) {
        String description = info.getDescription();
        if (description == null || description.isBlank() || description.equals(info.getName())) {
            return info.getName();
        }
        return info.getName() + " - " + description;
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

    private void startJavaSoundPlaybackThread() {
        javaSoundPlaybackThread = new Thread(() -> {
            byte[] writeBuffer = new byte[3840];
            while (javaSoundLine != null && javaSoundLine.isOpen()) {
                int bytesRead;
                synchronized (memoryQueueLock) {
                    bytesRead = memoryQueue != null ? memoryQueue.dequeue(writeBuffer, 0, writeBuffer.length) : 0;
                }
                if (bytesRead <= 0) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                applyPlaybackVolume(writeBuffer, bytesRead);
                publishVisualLevel(computePeakAbs(writeBuffer, bytesRead));
                if (!javaSoundBigEndian) {
                    swapEndianInPlace(writeBuffer, bytesRead);
                }
                javaSoundLine.write(writeBuffer, 0, bytesRead);
                recordPlayedAudioStats(bytesRead);
                maybeLogAudioStats();
            }
        });
        javaSoundPlaybackThread.setName("DASB Java Sound Playback");
        javaSoundPlaybackThread.setDaemon(true);
        javaSoundPlaybackThread.start();
    }

    private void startPulsePlaybackThread() {
        pulsePlaybackThread = new Thread(() -> {
            byte[] writeBuffer = new byte[3840];
            try (OutputStream outputStream = pulsePlaybackProcess.getOutputStream()) {
                while (pulsePlaybackProcess != null && pulsePlaybackProcess.isAlive()) {
                    int bytesRead;
                    synchronized (memoryQueueLock) {
                        bytesRead = memoryQueue != null ? memoryQueue.dequeue(writeBuffer, 0, writeBuffer.length) : 0;
                    }
                    if (bytesRead <= 0) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                    applyPlaybackVolume(writeBuffer, bytesRead);
                    publishVisualLevel(computePeakAbs(writeBuffer, bytesRead));
                    outputStream.write(writeBuffer, 0, bytesRead);
                    outputStream.flush();
                    recordPlayedAudioStats(bytesRead);
                    maybeLogAudioStats();
                }
            } catch (IOException ex) {
                if (!closed) {
                    logger.warn("Pulse playback thread stopped unexpectedly for '{}'", playbackDeviceName, ex);
                }
            }
        });
        pulsePlaybackThread.setName("DASB Pulse Playback");
        pulsePlaybackThread.setDaemon(true);
        pulsePlaybackThread.start();
    }

    private int computePeakAbs(byte[] data, int length) {
        int peakAbs = 0;
        for (int offset = 0; offset + 1 < length; offset += 2) {
            short sample = (short) (((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff));
            peakAbs = Math.max(peakAbs, Math.abs((int) sample));
        }
        return peakAbs;
    }

    private void swapEndianInPlace(byte[] data, int length) {
        for (int offset = 0; offset + 1 < length; offset += 2) {
            byte tmp = data[offset];
            data[offset] = data[offset + 1];
            data[offset + 1] = tmp;
        }
    }

    private static void applyPlaybackVolume(byte[] data, int length) {
        if (data == null || length <= 0) {
            return;
        }
        double volume = DiscordAudioStreamBot.getConfig().getOutputVolume();
        if (Math.abs(volume - 1.0d) < 0.0001d) {
            return;
        }
        for (int offset = 0; offset + 1 < length; offset += 2) {
            short sample = (short) (((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff));
            int scaled = (int) Math.round(sample * volume);
            scaled = Math.max(Short.MIN_VALUE, Math.min(scaled, Short.MAX_VALUE));
            data[offset] = (byte) ((scaled >> 8) & 0xff);
            data[offset + 1] = (byte) (scaled & 0xff);
        }
    }

    private static int applyPlaybackVolume(int sample) {
        double volume = DiscordAudioStreamBot.getConfig().getOutputVolume();
        int scaled = (int) Math.round(sample * volume);
        return Math.max(Short.MIN_VALUE, Math.min(scaled, Short.MAX_VALUE));
    }

    private static byte[] createTestTonePcm() {
        int channels = OUTPUT_FORMAT.getChannels();
        int bytesPerSample = OUTPUT_FORMAT.getSampleSizeInBits() / 8;
        int frameCount = (int) ((OUTPUT_FORMAT.getSampleRate() * TEST_TONE_DURATION_MILLIS) / 1000);
        byte[] data = new byte[frameCount * channels * bytesPerSample];
        double phase = 0d;
        double phaseIncrement = (2.0d * Math.PI * TEST_TONE_FREQUENCY) / OUTPUT_FORMAT.getSampleRate();
        int index = 0;

        for (int i = 0; i < frameCount; i++) {
            short sample = (short) (Math.sin(phase) * TEST_TONE_AMPLITUDE);
            phase += phaseIncrement;
            if (phase >= 2.0d * Math.PI) {
                phase -= 2.0d * Math.PI;
            }
            for (int ch = 0; ch < channels; ch++) {
                data[index++] = (byte) ((sample >> 8) & 0xff);
                data[index++] = (byte) (sample & 0xff);
            }
        }
        return data;
    }

    private void publishVisualLevel(int peakAbs) {
        visualLevel = Math.max(0d, Math.min(1d, peakAbs / (double) Short.MAX_VALUE));
        visualLevelNanos = System.nanoTime();
    }

    private static void publishStandaloneVisualLevel(String playbackDeviceId, int peakAbs) {
        standaloneVisualPlaybackDeviceId = playbackDeviceId;
        standaloneVisualLevel = Math.max(0d, Math.min(1d, peakAbs / (double) Short.MAX_VALUE));
        standaloneVisualLevelNanos = System.nanoTime();
    }

    private void recordReceivedAudioStats(int bytes, int peakAbs) {
        receivedFrames++;
        receivedBytes += bytes;
        statsPeakAbs = Math.max(statsPeakAbs, peakAbs);
    }

    private void recordPlayedAudioStats(int bytes) {
        playedBytes += bytes;
    }

    private double getDecayedVisualLevel(long now) {
        long age = now - visualLevelNanos;
        if (age <= LEVEL_HOLD_NANOS) {
            return visualLevel;
        }
        double decay = ((age - LEVEL_HOLD_NANOS) / 1_000_000_000d) * LEVEL_DECAY_PER_SECOND;
        return Math.max(0d, visualLevel - decay);
    }

    private static double getDecayedStandaloneVisualLevel(String playbackDeviceId, long now) {
        if (!(playbackDeviceId == null || playbackDeviceId.equals(standaloneVisualPlaybackDeviceId))) {
            return 0d;
        }
        long age = now - standaloneVisualLevelNanos;
        if (age <= LEVEL_HOLD_NANOS) {
            return standaloneVisualLevel;
        }
        double decay = ((age - LEVEL_HOLD_NANOS) / 1_000_000_000d) * LEVEL_DECAY_PER_SECOND;
        return Math.max(0d, standaloneVisualLevel - decay);
    }

    private void maybeLogAudioStats() {
        long now = System.nanoTime();
        if (now - statsLastLogNanos < STATS_LOG_INTERVAL_NANOS) {
            return;
        }

        int queuedBytes;
        synchronized (memoryQueueLock) {
            queuedBytes = memoryQueue != null ? memoryQueue.size() : 0;
        }

        double windowSeconds = Math.max(0.001d, (now - statsWindowStartNanos) / 1_000_000_000d);
        double framesPerSecond = receivedFrames / windowSeconds;
        double queuedMillis = queuedBytes / (double) getFrameSizeBytes() * 20.0d;

        logger.debug("Listen stats: device={}, recvFps={}, recvBytes={}, playedBytes={}, peak={}, queuedMs={}",
                playbackDeviceName,
                String.format(Locale.ROOT, "%.1f", framesPerSecond),
                receivedBytes,
                playedBytes,
                statsPeakAbs,
                String.format(Locale.ROOT, "%.1f", queuedMillis));

        statsWindowStartNanos = now;
        statsLastLogNanos = now;
        receivedFrames = 0;
        receivedBytes = 0;
        playedBytes = 0;
        statsPeakAbs = 0;
    }

    private int getFrameSizeBytes() {
        return OUTPUT_FORMAT.getChannels()
                * (int) (OUTPUT_FORMAT.getSampleRate() * 0.02d)
                * (OUTPUT_FORMAT.getSampleSizeInBits() / 8);
    }
}
