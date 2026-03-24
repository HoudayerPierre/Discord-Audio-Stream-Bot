package net.runee.debug;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.factory.IAudioSendFactory;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.runee.DiscordAudioStreamBot;
import net.runee.ToneAudioSendHandler;
import net.runee.misc.Utils;
import net.runee.model.Config;
import net.runee.model.GuildConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class MinimalVoiceProbe extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(MinimalVoiceProbe.class);
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setLenient()
            .create();

    public static void main(String[] args) throws Exception {
        Config config = loadConfig();
        String token = firstNonBlank(System.getenv("DASB_BOT_TOKEN"), config.botToken);
        if (isBlank(token)) {
            throw new IllegalStateException("No bot token available for minimal voice probe");
        }

        MinimalVoiceProbe probe = new MinimalVoiceProbe(config);
        probe.login(token);
    }

    private final Config config;

    public MinimalVoiceProbe(Config config) {
        this.config = config;
    }

    private void login(String token) throws LoginException {
        logger.info("Starting minimal voice probe");
        logger.info("Probe config: nativeSendDisabled={}, directOpus={}, continuousTone={}, speakingModes={}",
                isNativeAudioSendDisabled(),
                isDirectOpusEnabled(),
                isContinuousToneEnabled(),
                getSpeakingModes());

        JDABuilder.create(token,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_VOICE_STATES)
                .setAudioModuleConfig(createAudioModuleConfig())
                .addEventListeners(this)
                .setEnableShutdownHook(true)
                .build();
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        try {
            Guild guild = resolveGuild(event.getJDA());
            AudioChannel channel = resolveChannel(guild);
            if (channel == null) {
                throw new IllegalStateException("No target audio channel configured for minimal voice probe");
            }

            logger.info("Minimal voice probe joining guild '{}' channel '{}'", guild.getName(), channel.getName());
            AudioManager audioManager = guild.getAudioManager();
            ToneAudioSendHandler sendingHandler = new ToneAudioSendHandler(false);
            sendingHandler.setContinuousTone(isContinuousToneEnabled());
            audioManager.setSendingHandler(sendingHandler);
            audioManager.setSelfMuted(false);
            audioManager.setSelfDeafened(true);
            audioManager.setSpeakingMode(getSpeakingModes());
            audioManager.setConnectionListener(new ConnectionListener() {
                @Override
                public void onStatusChange(@NotNull ConnectionStatus status) {
                    logger.info("Minimal voice probe audio status: {}", status);
                    switch (status) {
                        case CONNECTED -> {
                            sendingHandler.setPlaying(true);
                            if (!isContinuousToneEnabled()) {
                                sendingHandler.queueTone(getProbeDurationMillis());
                            }
                        }
                        default -> sendingHandler.setPlaying(false);
                    }
                }
            });
            audioManager.openAudioConnection(channel);
        } catch (Exception ex) {
            logger.error("Minimal voice probe failed during startup", ex);
            event.getJDA().shutdown();
        }
    }

    private AudioModuleConfig createAudioModuleConfig() {
        AudioModuleConfig moduleConfig = new AudioModuleConfig()
                .withDaveSessionFactory(new JDaveSessionFactory());

        if (isNativeAudioSendDisabled()) {
            logger.warn("Minimal voice probe is running without native JDA audio send factory");
            return moduleConfig;
        }

        IAudioSendFactory factory = createNativeAudioSendFactory();
        if (factory != null) {
            logger.info("Minimal voice probe using native JDA audio send factory: {}", factory.getClass().getName());
            moduleConfig.withAudioSendFactory(factory);
        } else {
            logger.warn("Minimal voice probe could not resolve a native JDA audio send factory");
        }
        return moduleConfig;
    }

    private IAudioSendFactory createNativeAudioSendFactory() {
        String[] candidateClassNames = {
                "com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory",
                "club.minnced.udpqueue.NativeAudioSendFactory"
        };

        for (String className : candidateClassNames) {
            try {
                Class<?> clazz = Class.forName(className);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof IAudioSendFactory audioSendFactory) {
                    return audioSendFactory;
                }
                logger.warn("Resolved audio send factory class {} but it does not implement IAudioSendFactory", className);
            } catch (ClassNotFoundException ignored) {
                // Try the next known package name.
            } catch (ReflectiveOperationException ex) {
                logger.warn("Failed to instantiate audio send factory {}", className, ex);
            }
        }

        return null;
    }

    private Guild resolveGuild(JDA jda) {
        String guildId = System.getenv("DASB_PROBE_GUILD_ID");
        if (!isBlank(guildId)) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                return guild;
            }
            throw new IllegalStateException("Configured DASB_PROBE_GUILD_ID was not found: " + guildId);
        }

        List<GuildConfig> guildConfigs = config.guildConfigs;
        if (guildConfigs != null) {
            for (GuildConfig guildConfig : guildConfigs) {
                if (guildConfig == null || isBlank(guildConfig.guildId)) {
                    continue;
                }
                Guild guild = jda.getGuildById(guildConfig.guildId);
                if (guild != null) {
                    return guild;
                }
            }
        }

        throw new IllegalStateException("No guild could be resolved for minimal voice probe");
    }

    private AudioChannel resolveChannel(Guild guild) {
        String channelId = System.getenv("DASB_PROBE_CHANNEL_ID");
        if (!isBlank(channelId)) {
            AudioChannel channel = guild.getVoiceChannelById(channelId);
            if (channel == null) {
                channel = guild.getStageChannelById(channelId);
            }
            return channel;
        }

        GuildConfig guildConfig = config.getGuildConfig(guild);
        if (!isBlank(guildConfig.autoJoinAudioChannelId)) {
            AudioChannel channel = guild.getVoiceChannelById(guildConfig.autoJoinAudioChannelId);
            if (channel == null) {
                channel = guild.getStageChannelById(guildConfig.autoJoinAudioChannelId);
            }
            return channel;
        }

        return null;
    }

    private EnumSet<SpeakingMode> getSpeakingModes() {
        String configured = System.getenv("DASB_SPEAKING_MODE");
        EnumSet<SpeakingMode> modes = EnumSet.noneOf(SpeakingMode.class);
        if (configured == null || configured.isBlank()) {
            modes.add(SpeakingMode.VOICE);
            return modes;
        }

        for (String rawPart : configured.split(",")) {
            String part = rawPart.trim().toUpperCase(Locale.ROOT);
            if (part.isEmpty()) {
                continue;
            }
            switch (part) {
                case "VOICE" -> modes.add(SpeakingMode.VOICE);
                case "SOUNDSHARE", "SOUND_SHARE" -> modes.add(SpeakingMode.SOUNDSHARE);
                case "PRIORITY" -> modes.add(SpeakingMode.PRIORITY);
                default -> logger.warn("Ignoring unsupported DASB_SPEAKING_MODE value '{}'", rawPart);
            }
        }

        if (modes.isEmpty()) {
            modes.add(SpeakingMode.VOICE);
        }
        return modes;
    }

    private boolean isContinuousToneEnabled() {
        return parseBooleanEnv("DASB_MINIMAL_TONE_LOOP");
    }

    private boolean isDirectOpusEnabled() {
        return parseBooleanEnv("DASB_MINIMAL_TONE_OPUS");
    }

    private boolean isNativeAudioSendDisabled() {
        return parseBooleanEnv("DASB_DISABLE_NATIVE_SEND");
    }

    private int getProbeDurationMillis() {
        String configured = System.getenv("DASB_PROBE_DURATION_MS");
        if (isBlank(configured)) {
            return 10_000;
        }
        try {
            return Math.max(20, Integer.parseInt(configured));
        } catch (NumberFormatException ex) {
            logger.warn("Invalid DASB_PROBE_DURATION_MS '{}', defaulting to 10000", configured);
            return 10_000;
        }
    }

    private boolean parseBooleanEnv(String key) {
        String value = System.getenv(key);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static Config loadConfig() throws IOException {
        File configPath = DiscordAudioStreamBot.configPath;
        if (!configPath.exists()) {
            return new Config();
        }
        return gson.fromJson(Utils.readAllText(configPath), Config.class);
    }

    private static String firstNonBlank(String primary, String fallback) {
        return !isBlank(primary) ? primary : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
