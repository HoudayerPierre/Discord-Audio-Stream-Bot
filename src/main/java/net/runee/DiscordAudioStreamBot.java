package net.runee;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.factory.IAudioSendFactory;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.GatewayPingEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.runee.commands.bot.*;
import net.runee.commands.settings.AutoJoinAudioCommand;
import net.runee.commands.settings.BindCommand;
import net.runee.commands.settings.FollowAudioCommand;
import net.runee.commands.user.*;
import net.runee.audio.InputDeviceDescriptor;
import net.runee.audio.InputDeviceService;
import net.runee.audio.SendPathMode;
import net.runee.errors.BassException;
import net.runee.errors.CommandException;
import net.runee.gui.MainFrame;
import net.runee.misc.Utils;
import net.runee.misc.discord.Command;
import net.runee.model.Config;
import net.runee.model.GuildConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.awt.*;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class DiscordAudioStreamBot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAudioStreamBot.class);
    public static final String NAME = "Discord Audio Stream Bot";
    public static final String GITHUB_URL = "https://github.com/BinkanSalaryman/Discord-Audio-Stream-Bot";

    private static DiscordAudioStreamBot instance;
    public static final File configPath = new File("config.json");
    private static Config config;
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setLenient()
            .create();

    public static DiscordAudioStreamBot getInstance() {
        if (instance == null) {
            instance = new DiscordAudioStreamBot();
        }
        return instance;
    }

    public static boolean hasInstance() {
        return instance != null;
    }

    public static Config getConfig() {
        if (config == null) {
            // load config
            try {
                if (configPath.exists()) {
                    config = gson.fromJson(Utils.readAllText(configPath), Config.class);
                } else {
                    config = new Config();
                    saveConfig();
                }
            } catch (IOException ex) {
                logger.warn("Failed to load or create new config file", ex);
            }
        }
        return config;
    }

    public static void setConfig(Config config) {
        DiscordAudioStreamBot.config = config;
    }

    public static void saveConfig() throws IOException {
        Utils.writeAllText(configPath, gson.toJson(config));
    }

    // data
    private JDA jda;
    private SpeakHandler standaloneInputMonitor;
    private InputDeviceDescriptor standaloneInputDescriptor;
    private ListenHandler standaloneLoopbackOutput;
    private String standaloneLoopbackPlaybackDevice;
    private String standaloneLoopbackFailedPlaybackDevice;
    private String standaloneRoutingLogState;

    // convenience
    private Map<String, Command> commands;

    private DiscordAudioStreamBot() {

    }

    public void login() throws LoginException {
        logger.info("Logging in...");
        jda = JDABuilder.create(config.botToken,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MESSAGES,
                        //GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.DIRECT_MESSAGES
                        //GatewayIntent.DIRECT_MESSAGE_REACTIONS
                )
                .setAudioModuleConfig(createAudioModuleConfig())
                .addEventListeners(this)
                .setEnableShutdownHook(false)
                .build()
        ;
        jda.setRequiredScopes("applications.commands"); // necessary for invite url which enables /command interactivity within discord client

        jda.updateCommands()
                .addCommands(getCommands().values()
                        .stream()
                        .map(Command::getData)
                        .collect(Collectors.toList())
                )
                .queue();
    }

    public void logoff() {
        logger.info("Logging off...");
        if (jda != null) {
            jda.shutdown();
        }
    }

    private AudioModuleConfig createAudioModuleConfig() {
        AudioModuleConfig config = new AudioModuleConfig()
                .withDaveSessionFactory(new JDaveSessionFactory());

        if (isNativeAudioSendDisabled()) {
            logger.warn("Native JDA audio send factory disabled by DASB_DISABLE_NATIVE_SEND");
            return config;
        }

        IAudioSendFactory audioSendFactory = createNativeAudioSendFactory();
        if (audioSendFactory != null) {
            logger.info("Using native JDA audio send factory: {}", audioSendFactory.getClass().getName());
            config.withAudioSendFactory(audioSendFactory);
        } else {
            logger.warn("Native JDA audio send factory was not found; falling back to default send factory");
        }

        return config;
    }

    private boolean isNativeAudioSendDisabled() {
        String value = System.getenv("DASB_DISABLE_NATIVE_SEND");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private SendPathMode getSendPathMode() {
        String value = System.getenv("DASB_MINIMAL_TONE_SEND");
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return SendPathMode.MINIMAL_TONE;
        }
        return SendPathMode.NORMAL;
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

    private IAudioSendFactory createNativeAudioSendFactory() {
        String[] candidateClassNames = {
                "com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory",
                "club.minnced.udpqueue.NativeAudioSendFactory"
        };

        for (String className : candidateClassNames) {
            try {
                Class<?> clazz = Class.forName(className);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof IAudioSendFactory) {
                    return (IAudioSendFactory) instance;
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

    public JDA getJDA() {
        return jda;
    }

    public String getInviteUrl() {
        return jda.getInviteUrl(Permission.EMPTY_PERMISSIONS);
    }

    public Map<String, Command> getCommands() {
        if (commands == null) {
            List<Command> commands = Arrays.asList(
                    // bot
                    new AboutCommand(),
                    new ExitCommand(),
                    new InviteCommand(),
                    new LeaveAudioAllCommand(),
                    new StopCommand(),
                    // bot user
                    new ActivityCommand(),
                    new JoinAudioCommand(),
                    new LeaveGuildCommand(),
                    new LeaveAudioCommand(),
                    new StatusCommand(),
                    new StageCommand(),
                    // settings
                    new AutoJoinAudioCommand(),
                    new BindCommand(),
                    new FollowAudioCommand()
            );

            this.commands = new HashMap<>();
            for (Command cmd : commands) {
                this.commands.put(cmd.getData().getName(), cmd);
            }
        }
        return commands;
    }

    @Override
    public void onReady(@Nonnull ReadyEvent e) {
        autoJoin();
    }

    private void autoJoin() {
        for (GuildConfig guildConfig : Utils.nullListToEmpty(getConfig().guildConfigs)) {
            for (int step = 0; true; step++) {
                switch (step) {
                    case 0:
                        if (guildConfig.followedUserId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn("Failed to retrieve guild with id '" + guildConfig.guildId + "' to follow voice");
                                continue;
                            }
                            Member target = guild.getMemberById(guildConfig.followedUserId);
                            if (target == null) {
                                logger.warn("User with id '" + guildConfig.followedUserId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            AudioChannel target_channel = target.getVoiceState().getChannel();
                            if (target_channel != null) {
                                joinAudio(target_channel);
                                return;
                            }
                        }
                        continue;
                    case 1:
                        if (guildConfig.autoJoinAudioChannelId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn("Failed to retrieve guild with id '" + guildConfig.guildId + "' to auto-join voice");
                                continue;
                            }
                            AudioChannel channel;
                            channel = guild.getVoiceChannelById(guildConfig.autoJoinAudioChannelId);
                            if(channel == null) {
                                channel = guild.getStageChannelById(guildConfig.autoJoinAudioChannelId);
                            }
                            if (channel == null) {
                                logger.warn("Voice channel with id '" + guildConfig.autoJoinAudioChannelId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            joinAudio(channel);
                            return;
                        }
                        continue;
                    default:
                        return;
                }
            }
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if(!isFollowedVoiceTarget(event.getMember())) {
            return;
        }

        if(event.getChannelJoined() != null && event.getChannelLeft() != null) {
            // audio channel moved
            if(!Objects.equals(event.getChannelJoined().getGuild(), event.getChannelLeft().getGuild())) {
                // moved to another guild's audio channel, disconnect audio manager of left guild
                leaveAudio(event.getChannelLeft().getGuild());
            }
            joinAudio(event.getChannelJoined());
        } else if(event.getChannelJoined() != null) {
            // audio channel joined
            joinAudio(event.getChannelJoined());
        } else if(event.getChannelLeft() != null) {
            // audio channel left
            leaveAudio(event.getChannelLeft().getGuild());
        }
    }

    private boolean isFollowedVoiceTarget(Member member) {
        GuildConfig guildConfig = getConfig().getGuildConfig(member.getGuild());
        return guildConfig.followedUserId != null && Objects.equals(member.getId(), guildConfig.followedUserId);
    }

    @Override
    public void onShutdown(@Nonnull ShutdownEvent e) {
        EventQueue.invokeLater(() -> MainFrame.getInstance().tabHome.onGatewayPing(null));
    }

    @Override
    public void onGatewayPing(@NotNull GatewayPingEvent e) {
        EventQueue.invokeLater(() -> MainFrame.getInstance().tabHome.onGatewayPing(e.getNewPing()));
    }

    @Override
    public void onStatusChange(@Nonnull StatusChangeEvent e) {
        switch (e.getNewValue()) {
            case CONNECTED:
                logger.info("Logged in");
                break;
            case SHUTDOWN:
                logger.info("Logged off");
                break;
            case FAILED_TO_LOGIN:
                logger.info("Failed to login");
                break;
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent e) {
        Command cmd = getCommands().get(e.getName());
        if (cmd != null) {
            try {
                cmd.run(e);
            } catch (CommandException ex) {
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription(ex.getReplyMessage())
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            } catch (Exception ex) {
                logger.error("Failed to execute command " + e.getName(), ex);
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription("Failed to execute command; details are in the log.")
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            }
        } else {
            e.replyEmbeds(new EmbedBuilder()
                    .setDescription("Unrecognized command: `" + e.getName() + "`!")
                    .setColor(Utils.colorRed)
                    .build()
            ).setEphemeral(true).queue();
        }
    }

    public void sendDirect(User user, MessageCreateData message) {
        user.openPrivateChannel().queue(chan -> {
            chan.sendMessage(message).queue();
        });
    }

    public void sendDirect(User user, String message) {
        sendDirect(user, MessageCreateData.fromContent(message));
    }

    public void sendDirect(User user, MessageEmbed embed) {
        sendDirect(user, MessageCreateData.fromEmbeds(embed));
    }

    public void joinAudio(AudioChannel channel) {
        AudioManager audioManager = channel.getGuild().getAudioManager();
        updateSpeakState(audioManager, null, null);
        updateListenState(audioManager, null, null);
        audioManager.setConnectionListener(new ConnectionListener() {
            @Override
            public void onPing(long ping) {
                EventQueue.invokeLater(() -> MainFrame.getInstance().tabHome.onAudioPing(channel.getGuild(), ping));
            }

            @Override
            public void onStatusChange(@Nonnull ConnectionStatus status) {
                try {
                    switch (status) {
                        case CONNECTED: {
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                ((SpeakHandler) sendingHandler).setPlaying(true);
                            } else if (sendingHandler instanceof ToneAudioSendHandler) {
                                ((ToneAudioSendHandler) sendingHandler).setPlaying(true);
                            }
                            break;
                        }
                        default: {
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                ((SpeakHandler) sendingHandler).setPlaying(false);
                            } else if (sendingHandler instanceof ToneAudioSendHandler) {
                                ((ToneAudioSendHandler) sendingHandler).setPlaying(false);
                            }
                            EventQueue.invokeLater(() -> MainFrame.getInstance().tabHome.onAudioPing(channel.getGuild(), null));
                            break;
                        }
                    }
                } catch (BassException ex) {
                    logger.error("Failed to pause/unpause speak handler for guild " + audioManager.getGuild().getName(), ex);
                }
            }
        });
        audioManager.openAudioConnection(channel);
    }

    public void leaveAudio(Guild guild) {
        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            updateSpeakState(audioManager, false, null);
            updateListenState(audioManager, false, null);
            audioManager.closeAudioConnection();
        }
    }

    public void leaveVoiceAll() {
        for (AudioManager audioManager : jda.getAudioManagers()) {
            leaveAudio(audioManager.getGuild());
        }
    }

    public void updateSpeakState(AudioManager audioManager, Boolean speakEnabled, InputDeviceDescriptor recordingDevice) {
        speakEnabled = speakEnabled != null ? speakEnabled : config.getSpeakEnabled();
        recordingDevice = recordingDevice != null ? recordingDevice : InputDeviceService.resolveConfiguredInputDevice(config);
        SendPathMode sendPathMode = getSendPathMode();

        // audio send handler
        AudioSendHandler sendingHandler = audioManager.getSendingHandler();
        if (speakEnabled) {
            if (sendPathMode == SendPathMode.NORMAL && recordingDevice == null) {
                logger.warn("No recording device is configured or discoverable for guild {}", audioManager.getGuild().getName());
                sendingHandler = null;
                speakEnabled = false;
            }
            if (sendingHandler == null) {
                sendingHandler = createSendingHandler(sendPathMode, audioManager.isConnected());
            } else if (!isCompatibleSendingHandler(sendingHandler, sendPathMode)) {
                if (sendingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) sendingHandler);
                }
                sendingHandler = createSendingHandler(sendPathMode, audioManager.isConnected());
            }
            if (speakEnabled && sendPathMode == SendPathMode.NORMAL && sendingHandler instanceof SpeakHandler) {
                try {
                    ((SpeakHandler) sendingHandler).openRecordingDevice(recordingDevice, audioManager.isConnected());
                } catch (Exception ex) {
                    logger.error("Failed to open recording device '{}' via {}",
                            recordingDevice.getDisplayName(),
                            recordingDevice.getBackend().getDisplayName(),
                            ex);
                    sendingHandler = null;
                    speakEnabled = false;
                }
            }
        } else {
            if (sendingHandler != null) {
                if (sendingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) sendingHandler);
                }
                sendingHandler = null;
            }
        }
        audioManager.setSendingHandler(sendingHandler);
        refreshConnectedSendLoopbackRouting();
        audioManager.setSelfMuted(!speakEnabled);
        if (speakEnabled) {
            EnumSet<SpeakingMode> speakingModes = getSpeakingModes();
            audioManager.setSpeakingMode(speakingModes);
            logger.info("Configured send path for guild {} as {}", audioManager.getGuild().getName(), sendPathMode);
            logger.info("Configured speaking mode for guild {} to {}", audioManager.getGuild().getName(), speakingModes);
        } else {
            audioManager.setSpeakingMode(EnumSet.of(SpeakingMode.VOICE));
        }
    }

    private AudioSendHandler createSendingHandler(SendPathMode sendPathMode, boolean connected) {
        if (sendPathMode == SendPathMode.MINIMAL_TONE) {
            logger.warn("Using minimal tone-only send path (DASB_MINIMAL_TONE_SEND)");
            return new ToneAudioSendHandler(connected);
        }
        return new SpeakHandler();
    }

    private boolean isCompatibleSendingHandler(AudioSendHandler sendingHandler, SendPathMode sendPathMode) {
        if (sendPathMode == SendPathMode.MINIMAL_TONE) {
            return sendingHandler instanceof ToneAudioSendHandler;
        }
        return sendingHandler instanceof SpeakHandler;
    }

    public void updateListenState(AudioManager audioManager, Boolean listenEnabled, String playbackDevice) {
        listenEnabled = listenEnabled != null ? listenEnabled : config.getListenEnabled();
        playbackDevice = playbackDevice != null ? playbackDevice : config.playbackDevice;

        // audio receive handler
        AudioReceiveHandler receivingHandler = audioManager.getReceivingHandler();
        if (listenEnabled) {
            if (receivingHandler == null) {
                receivingHandler = new ListenHandler();
            }
            if (receivingHandler instanceof ListenHandler) {
                try {
                    ((ListenHandler) receivingHandler).openPlaybackDevice(playbackDevice);
                } catch (Exception ex) {
                    logger.error("Failed to open playback device '" + playbackDevice + "'", ex);
                    receivingHandler = null;
                    listenEnabled = false;
                }
            }
        } else {
            if (receivingHandler != null) {
                if (receivingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) receivingHandler);
                }
                receivingHandler = null;
            }
        }
        audioManager.setReceivingHandler(receivingHandler);
        audioManager.setSelfDeafened(!listenEnabled);
    }

    public void setSpeakEnabled(boolean speakEnabled) {
        if (!speakEnabled) {
            closeStandaloneInputMonitor();
            closeStandaloneLoopbackOutput();
        } else if (config.getListenEnabled()) {
            refreshStandaloneLoopbackOutput(config.playbackDevice);
        }
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, speakEnabled, null);
        }
        refreshConnectedSendLoopbackRouting();
    }

    public void setListenEnabled(boolean listenEnabled) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, listenEnabled, null);
        }
        if (listenEnabled && config.getSpeakEnabled()) {
            refreshStandaloneLoopbackOutput(config.playbackDevice);
        } else {
            closeStandaloneLoopbackOutput();
        }
        refreshConnectedSendLoopbackRouting();
    }

    public void setRecordingDevice(InputDeviceDescriptor recordingDevice) {
        if (getConnectedAudioManagers().isEmpty()) {
            refreshStandaloneLocalAudio(recordingDevice, config.playbackDevice);
        } else {
            closeStandaloneInputMonitor();
            if (config.getListenEnabled()) {
                refreshStandaloneLoopbackOutput(config.playbackDevice);
            } else {
                closeStandaloneLoopbackOutput();
            }
        }
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, null, recordingDevice);
        }
        refreshConnectedSendLoopbackRouting();
    }

    public void setPlaybackDevice(String playbackDevice) {
        if (config.getListenEnabled()) {
            refreshStandaloneLoopbackOutput(playbackDevice);
        } else {
            closeStandaloneLoopbackOutput();
        }
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, null, playbackDevice);
        }
        refreshConnectedSendLoopbackRouting();
    }

    public void playSendTestTone() {
        boolean queued = false;
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
            if (sendingHandler instanceof SpeakHandler) {
                ((SpeakHandler) sendingHandler).playTestTone();
                queued = true;
            } else if (sendingHandler instanceof ToneAudioSendHandler) {
                ((ToneAudioSendHandler) sendingHandler).playTestTone();
                queued = true;
            }
        }
        if (!queued && standaloneInputMonitor != null) {
            standaloneInputMonitor.playTestTone();
            queued = true;
        }
        if (!queued) {
            logger.warn("Test tone requested, but no active sending handler is attached to a connected guild");
        }
    }

    public void playOutputTestTone() {
        boolean queued = false;
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            AudioReceiveHandler receivingHandler = audioManager.getReceivingHandler();
            if (receivingHandler instanceof ListenHandler) {
                ((ListenHandler) receivingHandler).playTestTone();
                queued = true;
            }
        }
        if (!queued) {
            logger.warn("Local playback test requested without an active listening handler; using standalone local playback test");
            ListenHandler.playStandaloneTestTone(config.playbackDevice);
        }
    }

    private List<AudioManager> getConnectedAudioManagers() {
        if (jda != null) {
            List<AudioManager> result = new ArrayList<>();
            for (AudioManager audioManager : jda.getAudioManagers()) {
                if (audioManager.isConnected()) {
                    result.add(audioManager);
                }
            }
            return result;
        } else {
            return Collections.emptyList();
        }
    }

    public double getInputVisualLevel(InputDeviceDescriptor recordingDevice) {
        if (!config.getSpeakEnabled()) {
            closeStandaloneInputMonitor();
            closeStandaloneLoopbackOutput();
            return 0d;
        }
        if (getConnectedAudioManagers().isEmpty()) {
            refreshStandaloneLocalAudio(recordingDevice, config.playbackDevice);
        } else {
            if (config.getListenEnabled()) {
                refreshStandaloneLoopbackOutput(config.playbackDevice);
            } else {
                closeStandaloneLoopbackOutput();
            }
            refreshConnectedSendLoopbackRouting();
        }
        return SpeakHandler.getVisualLevel(recordingDevice);
    }

    public double getAppOutputVisualLevel(InputDeviceDescriptor recordingDevice, String playbackDevice) {
        double outputLevel = 0d;
        if (config.getSpeakEnabled()) {
            if (getConnectedAudioManagers().isEmpty()) {
                refreshStandaloneLocalAudio(recordingDevice, playbackDevice);
            } else {
                if (config.getListenEnabled()) {
                    refreshStandaloneLoopbackOutput(playbackDevice);
                } else {
                    closeStandaloneLoopbackOutput();
                }
                refreshConnectedSendLoopbackRouting();
            }
            outputLevel = Math.max(outputLevel, SpeakHandler.getVisualLevel(recordingDevice));
        }
        if (!config.getListenEnabled()) {
            closeStandaloneLoopbackOutput();
            return outputLevel;
        }
        if (!getConnectedAudioManagers().isEmpty()) {
            return Math.max(outputLevel, ListenHandler.getVisualLevel(playbackDevice));
        }
        refreshStandaloneLocalAudio(recordingDevice, playbackDevice);
        return Math.max(outputLevel, ListenHandler.getVisualLevel(playbackDevice));
    }

    private void refreshStandaloneLocalAudio(InputDeviceDescriptor recordingDevice, String playbackDevice) {
        if (!config.getSpeakEnabled()) {
            closeStandaloneInputMonitor();
            closeStandaloneLoopbackOutput();
            return;
        }
        if (recordingDevice == null) {
            recordingDevice = InputDeviceService.resolveConfiguredInputDevice(config);
        }
        if (recordingDevice == null) {
            closeStandaloneInputMonitor();
            closeStandaloneLoopbackOutput();
            return;
        }
        if (config.getListenEnabled() && playbackDevice != null) {
            refreshStandaloneLoopbackOutput(playbackDevice);
        } else if (config.getListenEnabled() && playbackDevice == null) {
            refreshStandaloneLoopbackOutput(null);
        } else {
            closeStandaloneLoopbackOutput();
        }

        String routingState = recordingDevice.getDisplayName()
                + "|" + recordingDevice.getBackend().getDisplayName()
                + "|" + (playbackDevice != null ? playbackDevice : "(Default playback device)");
        if (!Objects.equals(standaloneRoutingLogState, routingState)) {
            standaloneRoutingLogState = routingState;
            logger.info("Standalone local audio routing: input={} [{}], output={}",
                    recordingDevice.getDisplayName(),
                    recordingDevice.getBackend().getDisplayName(),
                    playbackDevice != null ? playbackDevice : "(Default playback device)");
        }

        if (standaloneInputMonitor != null && recordingDevice.equals(standaloneInputDescriptor)) {
            standaloneInputMonitor.setPcmListener(standaloneLoopbackOutput != null
                    ? standaloneLoopbackOutput::enqueueForPlayback
                    : null);
            return;
        }
        closeStandaloneInputMonitor();
        try {
            standaloneInputMonitor = new SpeakHandler();
            standaloneInputMonitor.openRecordingDevice(recordingDevice, true);
            standaloneInputMonitor.setPcmListener(standaloneLoopbackOutput != null
                    ? standaloneLoopbackOutput::enqueueForPlayback
                    : null);
            standaloneInputDescriptor = recordingDevice;
            logger.info("Started standalone input monitor for '{}'", recordingDevice.getDisplayName());
        } catch (Exception ex) {
            logger.warn("Failed to start standalone input monitor for '{}'", recordingDevice.getDisplayName(), ex);
            closeStandaloneInputMonitor();
            closeStandaloneLoopbackOutput();
        }
    }

    private void refreshStandaloneLoopbackOutput(String playbackDevice) {
        if (!config.getListenEnabled()) {
            closeStandaloneLoopbackOutput();
            return;
        }
        if (standaloneLoopbackOutput == null
                && Objects.equals(standaloneLoopbackFailedPlaybackDevice, playbackDevice)) {
            return;
        }
        if (standaloneLoopbackOutput != null && Objects.equals(standaloneLoopbackPlaybackDevice, playbackDevice)) {
            refreshConnectedSendLoopbackRouting();
            return;
        }
        standaloneLoopbackFailedPlaybackDevice = null;
        closeStandaloneLoopbackOutput();
        try {
            standaloneLoopbackOutput = new ListenHandler();
            standaloneLoopbackOutput.openPlaybackDevice(playbackDevice);
            standaloneLoopbackPlaybackDevice = playbackDevice;
            refreshConnectedSendLoopbackRouting();
            logger.info("Started standalone loopback output for '{}'",
                    playbackDevice != null ? playbackDevice : "(Default playback device)");
        } catch (Exception ex) {
            standaloneLoopbackFailedPlaybackDevice = playbackDevice;
            logger.warn("Failed to start standalone loopback output for '{}'",
                    playbackDevice != null ? playbackDevice : "(Default playback device)",
                    ex);
            closeStandaloneLoopbackOutput();
        }
    }

    private void closeStandaloneInputMonitor() {
        if (standaloneInputMonitor != null) {
            Utils.closeQuiet(standaloneInputMonitor);
            standaloneInputMonitor = null;
            standaloneInputDescriptor = null;
        }
        if (standaloneLoopbackOutput == null) {
            standaloneRoutingLogState = null;
        }
    }

    private void closeStandaloneLoopbackOutput() {
        if (standaloneLoopbackOutput != null) {
            Utils.closeQuiet(standaloneLoopbackOutput);
            standaloneLoopbackOutput = null;
            standaloneLoopbackPlaybackDevice = null;
        }
        if (standaloneInputMonitor == null) {
            standaloneRoutingLogState = null;
        }
        refreshConnectedSendLoopbackRouting();
    }

    private void refreshConnectedSendLoopbackRouting() {
        SpeakHandler preferredHandler = null;
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
            if (sendingHandler instanceof SpeakHandler) {
                SpeakHandler speakHandler = (SpeakHandler) sendingHandler;
                if (preferredHandler == null && config.getSpeakEnabled() && standaloneLoopbackOutput != null) {
                    preferredHandler = speakHandler;
                    speakHandler.setPcmListener(standaloneLoopbackOutput::enqueueForPlayback);
                } else {
                    speakHandler.setPcmListener(null);
                }
            }
        }
    }
}
