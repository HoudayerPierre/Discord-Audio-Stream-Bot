package net.runee;

import com.sun.jna.ptr.PointerByReference;
import net.dv8tion.jda.api.audio.AudioNatives;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tomp2p.opuswrapper.Opus;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static net.dv8tion.jda.api.audio.AudioSendHandler.INPUT_FORMAT;

public class ToneAudioSendHandler implements AudioSendHandler, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(ToneAudioSendHandler.class);
    private static final int FRAME_MILLIS = 20;
    private static final int TEST_TONE_DURATION_MILLIS = 3000;
    private static final double TONE_FREQUENCY = 440.0d;
    private static final int TONE_AMPLITUDE = 12000;
    private static final int OPUS_APPLICATION_AUDIO = 2049;

    private final byte[] frameBuffer;
    private final boolean directOpus;
    private volatile boolean playing;
    private volatile boolean continuousTone;
    private volatile int testToneFramesRemaining;
    private double tonePhase;
    private long providedFrames;
    private long statsWindowStartNanos;
    private long statsLastLogNanos;
    private PointerByReference opusEncoder;
    private ByteBuffer opusEncodeBuffer;
    private boolean loggedDirectOpusEnabled;

    public ToneAudioSendHandler(boolean playing) {
        this.playing = playing;
        this.directOpus = "1".equals(System.getenv("DASB_MINIMAL_TONE_OPUS"))
                || "true".equalsIgnoreCase(System.getenv("DASB_MINIMAL_TONE_OPUS"));
        this.frameBuffer = new byte[INPUT_FORMAT.getChannels()
                * (int) (INPUT_FORMAT.getSampleRate() * (FRAME_MILLIS / 1000f))
                * (INPUT_FORMAT.getSampleSizeInBits() / 8)];
        this.statsWindowStartNanos = System.nanoTime();
        this.statsLastLogNanos = this.statsWindowStartNanos;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public void setContinuousTone(boolean continuousTone) {
        this.continuousTone = continuousTone;
        if (continuousTone) {
            logger.info("Minimal send-path continuous tone enabled (directOpus={})", directOpus);
        }
    }

    public void playTestTone() {
        queueTone(TEST_TONE_DURATION_MILLIS);
    }

    public void queueTone(int durationMillis) {
        testToneFramesRemaining = Math.max(1, durationMillis / FRAME_MILLIS);
        logger.info("Queued {} ms minimal send-path test tone (directOpus={})", durationMillis, directOpus);
    }

    @Override
    public boolean canProvide() {
        return playing && (continuousTone || testToneFramesRemaining > 0);
    }

    @Nullable
    @Override
    public ByteBuffer provide20MsAudio() {
        if (!playing || (!continuousTone && testToneFramesRemaining <= 0)) {
            return null;
        }

        fillToneFrame(frameBuffer);
        if (!continuousTone && testToneFramesRemaining > 0) {
            testToneFramesRemaining--;
        }
        providedFrames++;
        maybeLogStats();

        if (directOpus) {
            if (!ensureDirectOpusEncoder()) {
                return null;
            }
            ByteBuffer encoded = encodeOpus(frameBuffer, frameBuffer.length);
            if (encoded == null) {
                return null;
            }
            if (!loggedDirectOpusEnabled) {
                loggedDirectOpusEnabled = true;
                logger.info("ToneAudioSendHandler is providing direct Opus frames to JDA");
            }
            return encoded;
        }

        return ByteBuffer.wrap(frameBuffer);
    }

    @Override
    public boolean isOpus() {
        return directOpus;
    }

    private void fillToneFrame(byte[] out) {
        int channels = INPUT_FORMAT.getChannels();
        int samples = out.length / 2 / channels;
        double sampleRate = INPUT_FORMAT.getSampleRate();
        int index = 0;
        for (int i = 0; i < samples; i++) {
            short sample = (short) (Math.sin(tonePhase) * TONE_AMPLITUDE);
            tonePhase += (2.0d * Math.PI * TONE_FREQUENCY) / sampleRate;
            if (tonePhase >= 2.0d * Math.PI) {
                tonePhase -= 2.0d * Math.PI;
            }
            for (int ch = 0; ch < channels; ch++) {
                out[index++] = (byte) ((sample >> 8) & 0xff);
                out[index++] = (byte) (sample & 0xff);
            }
        }
    }

    private void maybeLogStats() {
        long now = System.nanoTime();
        if (now - statsLastLogNanos < 1_000_000_000L) {
            return;
        }

        double windowSeconds = Math.max(0.001d, (now - statsWindowStartNanos) / 1_000_000_000d);
        double framesPerSecond = providedFrames / windowSeconds;
        logger.info("Minimal tone stats: directOpus={}, fps={}, remainingFrames={}",
                directOpus,
                String.format("%.1f", framesPerSecond),
                continuousTone ? "continuous" : Integer.toString(testToneFramesRemaining));

        statsWindowStartNanos = now;
        statsLastLogNanos = now;
        providedFrames = 0;
    }

    private boolean ensureDirectOpusEncoder() {
        if (opusEncoder != null) {
            return true;
        }
        if (!AudioNatives.ensureOpus()) {
            logger.warn("AudioNatives.ensureOpus() returned false for minimal direct Opus path");
            return false;
        }

        IntBuffer error = IntBuffer.allocate(1);
        opusEncoder = Opus.INSTANCE.opus_encoder_create(
                (int) INPUT_FORMAT.getSampleRate(),
                INPUT_FORMAT.getChannels(),
                OPUS_APPLICATION_AUDIO,
                error);
        if (error.get(0) != 0 || opusEncoder == null) {
            logger.error("Received error status from opus_encoder_create(...) in ToneAudioSendHandler: {}", error.get(0));
            opusEncoder = null;
            return false;
        }
        if (opusEncodeBuffer == null) {
            opusEncodeBuffer = ByteBuffer.allocateDirect(4096);
        }
        logger.info("ToneAudioSendHandler direct Opus encoder initialized");
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
            logger.error("Received error code from opus_encode(...) in ToneAudioSendHandler: {}", encoded);
            return null;
        }

        opusEncodeBuffer.position(0);
        opusEncodeBuffer.limit(encoded);
        ByteBuffer packet = ByteBuffer.allocateDirect(encoded);
        packet.put(opusEncodeBuffer);
        packet.flip();
        return packet;
    }

    @Override
    public void close() throws IOException {
        if (opusEncoder != null) {
            Opus.INSTANCE.opus_encoder_destroy(opusEncoder);
            opusEncoder = null;
        }
        opusEncodeBuffer = null;
    }
}
