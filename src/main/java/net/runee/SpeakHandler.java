package net.runee;

import com.sun.jna.ptr.PointerByReference;
import jouvieje.bass.Bass;
import jouvieje.bass.defines.BASS_ACTIVE;
import jouvieje.bass.defines.BASS_ERROR;
import jouvieje.bass.defines.BASS_RECORD;
import jouvieje.bass.defines.BASS_STREAM;
import jouvieje.bass.structures.HRECORD;
import jouvieje.bass.utils.Pointer;
import net.dv8tion.jda.api.audio.AudioNatives;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.runee.audio.InputDeviceDescriptor;
import net.runee.audio.InputDeviceService;
import net.runee.audio.RecordingBackend;
import net.runee.errors.BassException;
import net.runee.misc.MemoryQueue;
import net.runee.misc.Utils;
import net.runee.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tomp2p.opuswrapper.Opus;

import static jouvieje.bass.defines.BASS_ACTIVE.*;
import static jouvieje.bass.defines.BASS_ERROR.BASS_ERROR_HANDLE;
import static net.dv8tion.jda.api.audio.AudioSendHandler.INPUT_FORMAT;

public class SpeakHandler implements AudioSendHandler, Closeable {
    public interface PcmListener {
        void onPcm(byte[] data, int length);
    }

    private static final Logger logger = LoggerFactory.getLogger(SpeakHandler.class);
    public static final int FRAME_MILLIS = 20;
    public static final int MAX_LAG = 200;
    public static final int TEST_TONE_DURATION_MILLIS = 3000;
    private static final int TARGET_QUEUE_FRAMES = 3;
    private static final double PULSE_GAIN = 8.0d;
    private static final long LEVEL_HOLD_NANOS = 750_000_000L;
    private static final double LEVEL_DECAY_PER_SECOND = 1.35d;
    private static final int OPUS_APPLICATION_AUDIO = 2049;
    private static final boolean DEBUG_TONE_ENABLED = "1".equals(System.getenv("DASB_DEBUG_TONE"))
            || "true".equalsIgnoreCase(System.getenv("DASB_DEBUG_TONE"));
    private static final double DEBUG_TONE_FREQUENCY = 440.0d;
    private static List<SpeakHandler> activeHandlers = new ArrayList<>();

    private final Object memoryQueueLock = new Object();
    private int recordingDevice;
    private InputDeviceDescriptor recordingDeviceDescriptor;
    private HRECORD recordingStream;
    private TargetDataLine javaSoundLine;
    private Thread javaSoundCaptureThread;
    private Process pulseProcess;
    private Thread pulseCaptureThread;
    private Thread pulseErrorThread;
    private boolean javaSoundBigEndian;
    private volatile boolean javaSoundPlaying;
    private long providedFrames;
    private long emptyPolls;
    private long statsWindowStartNanos;
    private long statsLastLogNanos;
    private long statsSampleCount;
    private double statsSquareSum;
    private int statsPeakAbs;
    private double debugTonePhase;
    private volatile double visualLevel;
    private volatile long visualLevelNanos;
    private PointerByReference opusEncoder;
    private ByteBuffer opusEncodeBuffer;
    private boolean directOpusEnabled;
    private boolean loggedDirectOpusEnabled;
    private boolean loggedPcmFallback;
    private volatile int testToneFramesRemaining;
    private MemoryQueue memoryQueue;
    private byte[] buffer;
    private PcmListener pcmListener;

    public SpeakHandler() {
        this.recordingDevice = -1;
        this.buffer = new byte[INPUT_FORMAT.getChannels() * (int) (INPUT_FORMAT.getSampleRate() * (FRAME_MILLIS / 1000f)) * (INPUT_FORMAT.getSampleSizeInBits() / 8)];
        this.statsWindowStartNanos = System.nanoTime();
        this.statsLastLogNanos = this.statsWindowStartNanos;
    }

    public void openRecordingDevice(InputDeviceDescriptor recordingDeviceDescriptor, boolean setPlaying) throws BassException, IOException {
        Utils.closeQuiet(this);

        this.recordingDeviceDescriptor = recordingDeviceDescriptor;
        memoryQueue = new MemoryQueue();
        javaSoundPlaying = setPlaying;

        if (recordingDeviceDescriptor.getBackend() == RecordingBackend.BASS) {
            openBassRecordingDevice(recordingDeviceDescriptor, setPlaying);
        } else if (recordingDeviceDescriptor.getBackend() == RecordingBackend.JAVASOUND) {
            openJavaSoundRecordingDevice(recordingDeviceDescriptor);
        } else if (recordingDeviceDescriptor.getBackend() == RecordingBackend.PULSE) {
            openPulseRecordingDevice(recordingDeviceDescriptor);
        } else {
            throw new IOException("Unsupported recording backend: " + recordingDeviceDescriptor.getBackend());
        }

        activeHandlers.add(this);
        logger.info("Opened recording device '{}' via {}",
                recordingDeviceDescriptor.getDisplayName(),
                recordingDeviceDescriptor.getBackend().getDisplayName());
    }

    private void openBassRecordingDevice(InputDeviceDescriptor recordingDeviceDescriptor, boolean setPlaying) throws BassException {
        recordingDevice = InputDeviceService.getBassRecordingDeviceHandle(recordingDeviceDescriptor.getId());

        HRECORD sharedRecordingStream = null;
        for (SpeakHandler handler : activeHandlers) {
            if (handler.recordingDeviceDescriptor != null
                    && handler.recordingDeviceDescriptor.equals(recordingDeviceDescriptor)
                    && handler.recordingStream != null) {
                sharedRecordingStream = handler.recordingStream;
                break;
            }
        }

        if (sharedRecordingStream != null) {
            this.recordingStream = sharedRecordingStream;
        } else {
            try {
                if (!Bass.BASS_RecordInit(recordingDevice)) {
                    Utils.checkBassError();
                }
                int flags = BASS_STREAM.BASS_STREAM_AUTOFREE;
                if(!setPlaying) {
                    flags |= BASS_RECORD.BASS_RECORD_PAUSE;
                }
                this.recordingStream = Bass.BASS_RecordStart((int) INPUT_FORMAT.getSampleRate(), INPUT_FORMAT.getChannels(), flags, SpeakHandler::RECORDPROC, null);
                Utils.checkBassError();
            } catch (BassException ex) {
                Utils.closeQuiet(this);
                throw ex;
            }
        }
    }

    private void openJavaSoundRecordingDevice(InputDeviceDescriptor recordingDeviceDescriptor) throws IOException {
        Mixer.Info mixerInfo = InputDeviceService.findJavaSoundMixerInfo(recordingDeviceDescriptor.getId());
        if (mixerInfo == null) {
            throw new IOException("Java Sound mixer '" + recordingDeviceDescriptor.getId() + "' not found");
        }

        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        try {
            AudioFormat preferredFormat = createJavaSoundFormat(true);
            DataLine.Info preferredInfo = new DataLine.Info(TargetDataLine.class, preferredFormat);
            AudioFormat selectedFormat = preferredFormat;
            DataLine.Info selectedInfo = preferredInfo;
            javaSoundBigEndian = true;
            if (!mixer.isLineSupported(preferredInfo)) {
                selectedFormat = createJavaSoundFormat(false);
                selectedInfo = new DataLine.Info(TargetDataLine.class, selectedFormat);
                javaSoundBigEndian = false;
            }

            javaSoundLine = (TargetDataLine) mixer.getLine(selectedInfo);
            javaSoundLine.open(selectedFormat);
            javaSoundLine.start();
            startJavaSoundCaptureThread();
        } catch (LineUnavailableException ex) {
            throw new IOException("Failed to open Java Sound input '" + recordingDeviceDescriptor.getDisplayName() + "'", ex);
        }
    }

    private void openPulseRecordingDevice(InputDeviceDescriptor recordingDeviceDescriptor) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "parec",
                "--device=" + recordingDeviceDescriptor.getId(),
                "--format=s16le",
                "--channels=" + INPUT_FORMAT.getChannels(),
                "--rate=" + (int) INPUT_FORMAT.getSampleRate(),
                "--latency-msec=20",
                "--process-time-msec=20",
                "--raw"
        );
        processBuilder.environment().put("PULSE_PROP_application.name", "Discord Audio Stream Bot");
        processBuilder.environment().put("PULSE_PROP_media.name", "DASB Capture");
        processBuilder.environment().put("PULSE_PROP_media.role", "music");
        processBuilder.environment().put("PULSE_PROP_node.name", "discord-audio-stream-bot-capture");
        pulseProcess = processBuilder.start();
        startPulseCaptureThread();
        startPulseErrorThread();
        unmutePulseSourceOutputAsync(pulseProcess.pid());
    }

    public void setPlaying(boolean playing) throws BassException {
        if(recordingStream == null && javaSoundLine == null && pulseProcess == null) {
            throw new IllegalStateException("No open stream, call openRecordingDevice first");
        }
        if (recordingDeviceDescriptor != null && recordingDeviceDescriptor.getBackend() == RecordingBackend.JAVASOUND) {
            javaSoundPlaying = playing;
            if (!playing) {
                synchronized (memoryQueueLock) {
                    memoryQueue.clear();
                }
            }
            return;
        }
        if (recordingDeviceDescriptor != null && recordingDeviceDescriptor.getBackend() == RecordingBackend.PULSE) {
            javaSoundPlaying = playing;
            if (!playing) {
                synchronized (memoryQueueLock) {
                    memoryQueue.clear();
                }
            }
            return;
        }
        switch (Bass.BASS_ChannelIsActive(this.recordingStream.asInt())) {
            case BASS_ACTIVE_PLAYING:
            case BASS_ACTIVE_STALLED:
                if(!playing) {
                    Bass.BASS_ChannelPause(this.recordingStream.asInt());
                }
                break;
            case BASS_ACTIVE_PAUSED:
            case BASS_ACTIVE_STOPPED:
                if(playing) {
                    Bass.BASS_ChannelPlay(this.recordingStream.asInt(), false);
                }
                break;
            default:
                throw new IndexOutOfBoundsException();
        }
        try {
            Utils.checkBassError();
        } catch(BassException ex) {
            if(ex.getError() == BASS_ERROR_HANDLE) {
                // TODO invalid handle:
                // not sure why, but after moving around guilds/channels a few times (with follow-audio on), the stream handle randomly becomes invalid (probably a synchronization issue)
                // as a workaround, let's open a new stream
                logger.warn("Workaround: Restarting recording stream", ex);
                try {
                    openRecordingDevice(recordingDeviceDescriptor, playing);
                } catch (IOException ioEx) {
                    throw new RuntimeException("Failed to reopen recording device '" + recordingDeviceDescriptor.getDisplayName() + "'", ioEx);
                }
            }
        }
    }

    public float getLag() {
        if (memoryQueue == null) {
            return 0f;
        }
        return (memoryQueue.size() / (float) buffer.length) * FRAME_MILLIS;
    }

    public static double getVisualLevel(InputDeviceDescriptor descriptor) {
        if (descriptor == null) {
            return 0d;
        }
        long now = System.nanoTime();
        double level = 0d;
        for (SpeakHandler handler : new ArrayList<>(activeHandlers)) {
            if (descriptor.equals(handler.recordingDeviceDescriptor)) {
                level = Math.max(level, handler.getDecayedVisualLevel(now));
            }
        }
        return level;
    }

    public void playTestTone() {
        testToneFramesRemaining = Math.max(1, TEST_TONE_DURATION_MILLIS / FRAME_MILLIS);
        logger.info("Queued {} ms test tone for recording device '{}'",
                TEST_TONE_DURATION_MILLIS,
                recordingDeviceDescriptor != null ? recordingDeviceDescriptor.getDisplayName() : "(none)");
    }

    public void setPcmListener(PcmListener pcmListener) {
        this.pcmListener = pcmListener;
    }

    private static boolean RECORDPROC(HRECORD handle, ByteBuffer buffer, int length, Pointer user) {
        Config cfg = DiscordAudioStreamBot.getConfig();
        List<SpeakHandler> handlers = getActiveHandlers(handle);
        byte[] sampleBuffer = new byte[2];
        int numSamplesToWrite = length / sampleBuffer.length;
        int peakAbs = 0;
        for (int s = 0; s < numSamplesToWrite; s++) {
            short sample = buffer.getShort();
            peakAbs = Math.max(peakAbs, Math.abs((int) sample));
            writeProcessedSample(sampleBuffer, sample, cfg);
            for (SpeakHandler handler : handlers) {
                synchronized (handler.memoryQueueLock) {
                    handler.memoryQueue.enqueue(sampleBuffer, 0, sampleBuffer.length);
                }
            }
        }
        for (SpeakHandler handler : handlers) {
            handler.publishVisualLevel(peakAbs);
        }
        return true;
    }

    private static List<SpeakHandler> getActiveHandlers(HRECORD handle) {
        List<SpeakHandler> matchingHandlers = new ArrayList<>();
        for (SpeakHandler handler : new ArrayList<>(activeHandlers)) {
            if (handle.equals(handler.recordingStream)) {
                matchingHandlers.add(handler);
            }
        }
        return matchingHandlers;
    }

    private static void writeProcessedSample(byte[] sampleBuffer, short sample, Config cfg) {
        sample = applyInputVolume(sample, cfg);
        if(cfg.getSpeakThresholdEnabled() && Math.abs((double)sample) <= cfg.getSpeakThreshold() * Short.MAX_VALUE) {
            sample = 0;
        }
        sample = applyOutputVolume(sample);
        sampleBuffer[0] = (byte) ((sample >> 8) & 0xff);
        sampleBuffer[1] = (byte) (sample & 0xff);
    }

    private AudioFormat createJavaSoundFormat(boolean bigEndian) {
        return new AudioFormat((float) INPUT_FORMAT.getSampleRate(), INPUT_FORMAT.getSampleSizeInBits(), INPUT_FORMAT.getChannels(), true, bigEndian);
    }

    private void startJavaSoundCaptureThread() {
        javaSoundCaptureThread = new Thread(() -> {
            byte[] readBuffer = new byte[buffer.length * 4];
            Config cfg = DiscordAudioStreamBot.getConfig();
            while (javaSoundLine != null && javaSoundLine.isOpen()) {
                int bytesRead = javaSoundLine.read(readBuffer, 0, readBuffer.length);
                if (bytesRead <= 0 || !javaSoundPlaying || memoryQueue == null) {
                    continue;
                }
                enqueueJavaSoundData(readBuffer, bytesRead, cfg);
            }
        });
        javaSoundCaptureThread.setName("DASB Java Sound Capture");
        javaSoundCaptureThread.setDaemon(true);
        javaSoundCaptureThread.start();
    }

    private void enqueueJavaSoundData(byte[] data, int length, Config cfg) {
        byte[] processed = new byte[length - (length % 2)];
        byte[] sampleBuffer = new byte[2];
        int peakAbs = 0;
        int out = 0;
        for (int offset = 0; offset + 1 < length; offset += 2) {
            short sample;
            if (javaSoundBigEndian) {
                sample = (short) (((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff));
            } else {
                sample = (short) (((data[offset + 1] & 0xff) << 8) | (data[offset] & 0xff));
            }
            peakAbs = Math.max(peakAbs, Math.abs((int) sample));
            writeProcessedSample(sampleBuffer, sample, cfg);
            processed[out++] = sampleBuffer[0];
            processed[out++] = sampleBuffer[1];
        }
        synchronized (memoryQueueLock) {
            memoryQueue.enqueue(processed, 0, out);
            trimQueueLocked();
        }
        notifyPcmListener(processed, out);
        publishVisualLevel(peakAbs);
    }

    private void startPulseCaptureThread() {
        pulseCaptureThread = new Thread(() -> {
            byte[] readBuffer = new byte[buffer.length];
            Config cfg = DiscordAudioStreamBot.getConfig();
            try {
                while (pulseProcess != null) {
                    int bytesRead = pulseProcess.getInputStream().read(readBuffer);
                    if (bytesRead < 0) {
                        break;
                    }
                    if (bytesRead == 0 || !javaSoundPlaying || memoryQueue == null) {
                        continue;
                    }
                    enqueuePulseData(readBuffer, bytesRead, cfg);
                }
            } catch (IOException ex) {
                if (pulseProcess != null) {
                    logger.warn("Pulse capture process terminated unexpectedly", ex);
                }
            }
        });
        pulseCaptureThread.setName("DASB Pulse Capture");
        pulseCaptureThread.setDaemon(true);
        pulseCaptureThread.start();
    }

    private void enqueuePulseData(byte[] data, int length, Config cfg) {
        byte[] processed = new byte[length - (length % 2)];
        byte[] sampleBuffer = new byte[2];
        int peakAbs = 0;
        int out = 0;
        for (int offset = 0; offset + 1 < length; offset += 2) {
            short sample = (short) (((data[offset + 1] & 0xff) << 8) | (data[offset] & 0xff));
            sample = applyPulseGain(sample);
            peakAbs = Math.max(peakAbs, Math.abs((int) sample));
            writeProcessedSample(sampleBuffer, sample, cfg);
            processed[out++] = sampleBuffer[0];
            processed[out++] = sampleBuffer[1];
        }
        synchronized (memoryQueueLock) {
            memoryQueue.enqueue(processed, 0, out);
            trimQueueLocked();
        }
        notifyPcmListener(processed, out);
        publishVisualLevel(peakAbs);
    }

    private void notifyPcmListener(byte[] processed, int length) {
        if (pcmListener != null && length > 0) {
            pcmListener.onPcm(processed, length);
        }
    }

    private short applyPulseGain(short sample) {
        int amplified = (int) Math.round(sample * PULSE_GAIN);
        amplified = Math.max(Short.MIN_VALUE, Math.min(amplified, Short.MAX_VALUE));
        return (short) amplified;
    }

    private void startPulseErrorThread() {
        pulseErrorThread = new Thread(() -> {
            byte[] stderr = new byte[1024];
            try {
                while (pulseProcess != null) {
                    int bytesRead = pulseProcess.getErrorStream().read(stderr);
                    if (bytesRead < 0) {
                        break;
                    }
                    if (bytesRead == 0) {
                        continue;
                    }
                    logger.debug("parec stderr: {}", new String(stderr, 0, bytesRead));
                }
            } catch (IOException ex) {
                if (pulseProcess != null) {
                    logger.debug("Pulse stderr reader stopped", ex);
                }
            }
        });
        pulseErrorThread.setName("DASB Pulse Stderr");
        pulseErrorThread.setDaemon(true);
        pulseErrorThread.start();
    }

    private void unmutePulseSourceOutputAsync(long pid) {
        Thread pulseControlThread = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && pulseProcess != null; attempt++) {
                try {
                    Thread.sleep(200L);
                    String sourceOutputId = findPulseSourceOutputId(pid);
                    if (sourceOutputId == null) {
                        continue;
                    }
                    runPulseControl("pactl", "set-source-output-mute", sourceOutputId, "0");
                    runPulseControl("pactl", "set-source-output-volume", sourceOutputId, "100%");
                    logger.info("Ensured Pulse source-output {} is unmuted for capture pid {}", sourceOutputId, pid);
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException ex) {
                    logger.debug("Failed to adjust Pulse source-output for pid {}", pid, ex);
                    return;
                }
            }
        });
        pulseControlThread.setName("DASB Pulse Control");
        pulseControlThread.setDaemon(true);
        pulseControlThread.start();
    }

    private String findPulseSourceOutputId(long pid) throws IOException {
        Process process = new ProcessBuilder("pactl", "list", "source-outputs")
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentId = null;
            String pidString = "\"" + pid + "\"";
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Source Output #")) {
                    currentId = trimmed.substring("Source Output #".length()).trim();
                } else if (currentId != null && trimmed.startsWith("application.process.id = ") && trimmed.endsWith(pidString)) {
                    return currentId;
                }
            }
        }
        return null;
    }

    private void runPulseControl(String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.debug("Pulse control command exited with code {}: {}", exitCode, String.join(" ", command));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void trimQueueLocked() {
        if (memoryQueue == null) {
            return;
        }
        int maxQueueBytes = buffer.length * TARGET_QUEUE_FRAMES;
        if (memoryQueue.size() <= maxQueueBytes) {
            return;
        }

        int bytesToDrop = memoryQueue.size() - maxQueueBytes;
        byte[] discardBuffer = new byte[Math.min(bytesToDrop, buffer.length)];
        while (bytesToDrop > 0) {
            int chunk = Math.min(bytesToDrop, discardBuffer.length);
            int dropped = memoryQueue.dequeue(discardBuffer, 0, chunk);
            if (dropped <= 0) {
                break;
            }
            bytesToDrop -= dropped;
        }
    }

    @Override
    public boolean canProvide() {
        if (DEBUG_TONE_ENABLED || testToneFramesRemaining > 0) {
            return true;
        }
        if (memoryQueue == null) {
            return false;
        }
        float lag = getLag();
        if (lag >= MAX_LAG) {
            synchronized (memoryQueueLock) {
                memoryQueue.clear();
            }
            logger.warn("SpeakHandler is " + (int)lag + " ms behind! Clearing queue...");
        }
        boolean canProvide = memoryQueue.size() >= buffer.length;
        if (!canProvide) {
            emptyPolls++;
            maybeLogAudioStats();
        }
        return canProvide;
    }

    @Nullable
    @Override
    public ByteBuffer provide20MsAudio() {
        int numBytesRead;
        if (DEBUG_TONE_ENABLED || testToneFramesRemaining > 0) {
            fillDebugTone(buffer);
            if (testToneFramesRemaining > 0) {
                testToneFramesRemaining--;
            }
            recordProvidedAudioStats(buffer, buffer.length);
            providedFrames++;
            maybeLogAudioStats();
            numBytesRead = buffer.length;
        } else {
            synchronized (memoryQueueLock) {
                numBytesRead = memoryQueue.dequeue(buffer, 0, buffer.length);
            }
            if (numBytesRead > 0) {
                recordProvidedAudioStats(buffer, numBytesRead);
                providedFrames++;
                maybeLogAudioStats();
            }
        }

        if (numBytesRead <= 0) {
            return ByteBuffer.wrap(buffer, 0, 0);
        }

        if (ensureDirectOpusEncoder()) {
            ByteBuffer encoded = encodeOpus(buffer, numBytesRead);
            if (encoded != null) {
                if (!loggedDirectOpusEnabled) {
                    loggedDirectOpusEnabled = true;
                    logger.info("SpeakHandler is providing direct Opus frames to JDA");
                }
                return encoded;
            }
        }
        if (!loggedPcmFallback) {
            loggedPcmFallback = true;
            logger.warn("SpeakHandler is using JDA PCM encode path");
        }
        return ByteBuffer.wrap(buffer, 0, numBytesRead);
    }

    @Override
    public boolean isOpus() {
        return directOpusEnabled;
    }

    private void fillDebugTone(byte[] out) {
        int channels = INPUT_FORMAT.getChannels();
        int samples = out.length / 2 / channels;
        double sampleRate = INPUT_FORMAT.getSampleRate();
        int amplitude = 12000;
        int index = 0;
        for (int i = 0; i < samples; i++) {
            short sample = (short) (Math.sin(debugTonePhase) * amplitude);
            sample = applyOutputVolume(sample);
            debugTonePhase += (2.0d * Math.PI * DEBUG_TONE_FREQUENCY) / sampleRate;
            if (debugTonePhase >= 2.0d * Math.PI) {
                debugTonePhase -= 2.0d * Math.PI;
            }
            for (int ch = 0; ch < channels; ch++) {
                out[index++] = (byte) ((sample >> 8) & 0xff);
                out[index++] = (byte) (sample & 0xff);
            }
        }
    }

    private void recordProvidedAudioStats(byte[] data, int length) {
        int peakAbs = 0;
        for (int offset = 0; offset + 1 < length; offset += 2) {
            short sample = (short) (((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff));
            int abs = Math.abs(sample);
            statsPeakAbs = Math.max(statsPeakAbs, abs);
            peakAbs = Math.max(peakAbs, abs);
            statsSquareSum += (double) sample * sample;
            statsSampleCount++;
        }
        publishVisualLevel(peakAbs);
    }

    private void publishVisualLevel(int peakAbs) {
        visualLevel = Math.max(0d, Math.min(1d, peakAbs / (double) Short.MAX_VALUE));
        visualLevelNanos = System.nanoTime();
    }

    private double getDecayedVisualLevel(long now) {
        long age = now - visualLevelNanos;
        if (age <= LEVEL_HOLD_NANOS) {
            return visualLevel;
        }
        double decay = ((age - LEVEL_HOLD_NANOS) / 1_000_000_000d) * LEVEL_DECAY_PER_SECOND;
        return Math.max(0d, visualLevel - decay);
    }

    private void maybeLogAudioStats() {
        long now = System.nanoTime();
        if (now - statsLastLogNanos < 5_000_000_000L) {
            return;
        }

        double windowSeconds = Math.max(0.001d, (now - statsWindowStartNanos) / 1_000_000_000d);
        double framesPerSecond = providedFrames / windowSeconds;
        double rms = statsSampleCount > 0 ? Math.sqrt(statsSquareSum / statsSampleCount) : 0d;
        int queuedBytes = memoryQueue != null ? memoryQueue.size() : 0;

        logger.debug("Audio stats: backend={}, fps={}, rms={}, peak={}, queuedMs={}, emptyPolls={}",
                recordingDeviceDescriptor != null ? recordingDeviceDescriptor.getBackend().name() : "NONE",
                String.format("%.1f", framesPerSecond),
                String.format("%.1f", rms),
                statsPeakAbs,
                String.format("%.1f", queuedBytes / (double) buffer.length * FRAME_MILLIS),
                emptyPolls);

        statsWindowStartNanos = now;
        statsLastLogNanos = now;
        providedFrames = 0;
        emptyPolls = 0;
        statsSampleCount = 0;
        statsSquareSum = 0d;
        statsPeakAbs = 0;
    }

    private boolean ensureDirectOpusEncoder() {
        if (directOpusEnabled && opusEncoder != null) {
            return true;
        }
        if (!AudioNatives.ensureOpus()) {
            directOpusEnabled = false;
            logger.warn("AudioNatives.ensureOpus() returned false for direct Opus path");
            return false;
        }
        if (opusEncoder == null) {
            IntBuffer error = IntBuffer.allocate(1);
            opusEncoder = Opus.INSTANCE.opus_encoder_create(
                    (int) INPUT_FORMAT.getSampleRate(),
                    INPUT_FORMAT.getChannels(),
                    OPUS_APPLICATION_AUDIO,
                    error);
            if (error.get(0) != 0 || opusEncoder == null) {
                logger.error("Received error status from opus_encoder_create(...): {}", error.get(0));
                opusEncoder = null;
                directOpusEnabled = false;
                return false;
            }
            logger.info("SpeakHandler direct Opus encoder initialized");
        }
        if (opusEncodeBuffer == null) {
            opusEncodeBuffer = ByteBuffer.allocateDirect(4096);
        }
        directOpusEnabled = true;
        return true;
    }

    private ByteBuffer encodeOpus(byte[] pcm, int length) {
        ShortBuffer shortBuffer = ShortBuffer.allocate(length / 2);
        for (int offset = 0; offset + 1 < length; offset += 2) {
            int high = pcm[offset] & 0xff;
            int low = pcm[offset + 1] & 0xff;
            shortBuffer.put((short) ((high << 8) | low));
        }
        shortBuffer.flip();

        opusEncodeBuffer.clear();
        int encoded = Opus.INSTANCE.opus_encode(opusEncoder, shortBuffer, 960, opusEncodeBuffer, opusEncodeBuffer.capacity());
        if (encoded <= 0) {
            logger.error("Received error code from opus_encode(...): {}", encoded);
            directOpusEnabled = false;
            return null;
        }
        opusEncodeBuffer.position(0);
        opusEncodeBuffer.limit(encoded);
        ByteBuffer packet = ByteBuffer.allocateDirect(encoded);
        packet.put(opusEncodeBuffer);
        packet.flip();
        return packet;
    }

    private static short applyOutputVolume(short sample) {
        double volume = DiscordAudioStreamBot.getConfig().getOutputVolume();
        int scaled = (int) Math.round(sample * volume);
        scaled = Math.max(Short.MIN_VALUE, Math.min(scaled, Short.MAX_VALUE));
        return (short) scaled;
    }

    private static short applyInputVolume(short sample, Config cfg) {
        double volume = cfg != null ? cfg.getInputVolume() : DiscordAudioStreamBot.getConfig().getInputVolume();
        int scaled = (int) Math.round(sample * volume);
        scaled = Math.max(Short.MIN_VALUE, Math.min(scaled, Short.MAX_VALUE));
        return (short) scaled;
    }

    @Override
    public void close() throws IOException {
        activeHandlers.remove(this);
        boolean usedBass = recordingDeviceDescriptor != null && recordingDeviceDescriptor.getBackend() == RecordingBackend.BASS;
        if (recordingStream != null) {
            Bass.BASS_ChannelStop(recordingStream.asInt());
        }
        if (javaSoundLine != null) {
            javaSoundLine.stop();
            javaSoundLine.close();
        }
        if (pulseProcess != null) {
            pulseProcess.destroy();
        }
        if (usedBass && recordingDevice >= 0) {
            Bass.BASS_RecordSetDevice(recordingDevice);
            Bass.BASS_RecordFree();
        }
        memoryQueue = null;
        recordingStream = null;
        javaSoundLine = null;
        javaSoundCaptureThread = null;
        pulseProcess = null;
        pulseCaptureThread = null;
        pulseErrorThread = null;
        recordingDeviceDescriptor = null;
        if (opusEncoder != null) {
            Opus.INSTANCE.opus_encoder_destroy(opusEncoder);
            opusEncoder = null;
        }
        opusEncodeBuffer = null;
        directOpusEnabled = false;
        // TODO invalid handle:
        // not sure why, but after moving around guilds/channels a few times (with follow-audio on), the stream handle becomes invalid  (probably a synchronization issue)
        // as a workaround, keep the recording device last used
        //recordingDevice = -1;
        if (usedBass) {
            Utils.checkBassError();
        }
    }
}
