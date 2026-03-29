package net.runee.gui.components;

import com.jgoodies.forms.builder.FormBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.*;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateIconEvent;
import net.dv8tion.jda.api.events.guild.update.GuildUpdateNameEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.runee.DiscordAudioStreamBot;
import net.runee.gui.renderer.GuildListCellRenderer;
import net.runee.model.GuildConfig;
import net.runee.misc.Utils;
import net.runee.misc.gui.SpecBuilder;

import javax.annotation.Nonnull;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeSelectionModel;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MaintenancePanel extends JPanel implements EventListener {
    // components
    private JList<Guild> guilds;
    private JTree voiceChannelsTree;
    private JScrollPane guildsScrollPane;
    private JScrollPane voiceChannelsScrollPane;
    private JButton resync;
    private JButton addGuild;
    private JButton removeGuild;
    private JButton connectToChannel;
    private JButton disconnectFromChannel;
    private JButton unsetFromAutoJoin;
    private JButton setToAutoJoin;
    // convenience
    private DefaultListModel<Guild> guildsModel;
    private DefaultTreeModel voiceChannelsTreeModel;
    private String selectedVoiceChannelTreeGuildId;
    private String selectedVoiceChannelTreeChannelId;
    private final Map<String, String> pendingConnectChannelIds = new HashMap<>();
    private final Map<String, Long> pendingConnectStartedAt = new HashMap<>();
    private Timer voiceTreeRefreshTimer;
    private int voiceTreeRefreshTicksRemaining;
    private static final long MIN_PENDING_DISPLAY_MILLIS = 700L;

    public MaintenancePanel() {
        initModels();
        initComponents();
        layoutComponents();
    }

    private void initModels() {
        guildsModel = new DefaultListModel<>();
        voiceChannelsTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode("No guild selected"));
        voiceTreeRefreshTimer = new Timer(250, e -> onVoiceTreeRefreshTimer());
        voiceTreeRefreshTimer.setRepeats(true);
    }

    private void initComponents() {
        guilds = new JList<>();
        guilds.setModel(guildsModel);
        guilds.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        guilds.setVisibleRowCount(8);
        guilds.setCellRenderer(GuildListCellRenderer.getInstance());
        guilds.addListSelectionListener(e -> updateGuildControls());
        guildsScrollPane = new JScrollPane(guilds);
        guildsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        guildsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        guildsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        voiceChannelsTree = new JTree(voiceChannelsTreeModel);
        voiceChannelsTree.setVisibleRowCount(12);
        voiceChannelsTree.setLargeModel(true);
        voiceChannelsTree.setRootVisible(true);
        voiceChannelsTree.setShowsRootHandles(true);
        voiceChannelsTree.setEditable(false);
        voiceChannelsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        voiceChannelsTree.setCellRenderer(new VoiceChannelTreeCellRenderer());
        voiceChannelsTree.addTreeSelectionListener(e -> {
            rememberVoiceChannelTreeSelection();
            updateActionButtons();
        });
        voiceChannelsScrollPane = new JScrollPane(voiceChannelsTree);
        voiceChannelsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        voiceChannelsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        voiceChannelsScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        resync = new JButton("Re/Sync.");
        resync.addActionListener(e -> forceResyncView());
        addGuild = new JButton("Invite bot...");
        addGuild.addActionListener(e -> {
            if(!Utils.browseUrl(DiscordAudioStreamBot.getInstance().getInviteUrl())) {
                JOptionPane.showMessageDialog(this, "Unable to open invite url in browser.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        removeGuild = new JButton("Leave guild");
        removeGuild.addActionListener(e -> {
            Guild guild = guilds.getSelectedValue();
            guild.leave().queue();
        });
        connectToChannel = new JButton("Connect to channel");
        connectToChannel.addActionListener(e -> connectSelectedChannel());
        disconnectFromChannel = new JButton("Disconnect from channel");
        disconnectFromChannel.addActionListener(e -> disconnectSelectedGuildChannel());
        unsetFromAutoJoin = new JButton("Unset from autojoin");
        unsetFromAutoJoin.addActionListener(e -> clearAutoJoinChannel());
        setToAutoJoin = new JButton("Set to autojoin");
        setToAutoJoin.addActionListener(e -> setSelectedChannelAsAutoJoin());
    }

    private void layoutComponents() {
        int row;
        FormBuilder
                .create()
                .columns("f:p:g")
                .rows(SpecBuilder
                        .create()
                        .add("c:p") // guilds
                        .add("f:p:g")
                        .add("c:p")
                        .add("c:p")
                        .add("f:p:g")
                        .add("c:p")
                        .build()
                )
                .panel(this)
                .border(BorderFactory.createEmptyBorder(5, 5, 5, 5))
                .addSeparator("Guilds").xyw(1, row = 1, 1)
                .add(guildsScrollPane).xy(1, row += 2)
                .add(Utils.buildFlowPanel(addGuild, removeGuild)).xy(1, row += 2)
                .addSeparator("Voice channels").xyw(1, row += 2, 1)
                .add(voiceChannelsScrollPane).xy(1, row += 2)
                .add(Utils.buildFlowPanel(resync, connectToChannel, disconnectFromChannel, unsetFromAutoJoin, setToAutoJoin)).xy(1, row += 2)
                .build();
    }

    private void updateGuilds() {
        final JDA jda = DiscordAudioStreamBot.getInstance().getJDA();
        Guild previouslySelectedGuild = guilds.getSelectedValue();
        guildsModel.clear();
        for (Guild guild : jda.getGuilds()) {
            guildsModel.addElement(guild);
        }
        restoreGuildSelection(previouslySelectedGuild);
        updateGuildControls();
    }

    public void updateLoginStatus(JDA.Status status) {
        switch (status) {
            case CONNECTED:
                final JDA jda = DiscordAudioStreamBot.getInstance().getJDA();
                updateGuilds();
                jda.addEventListener(this);
                break;
        }
    }

    private void restoreGuildSelection(Guild previousSelection) {
        if (previousSelection != null) {
            Guild matchingGuild = findGuildById(previousSelection.getId());
            if (matchingGuild != null) {
                guilds.setSelectedValue(matchingGuild, true);
                return;
            }
        }

        if (DiscordAudioStreamBot.getConfig().isAutoLogin()) {
            Guild preferredGuild = findPreferredAutoLoginGuild();
            if (preferredGuild != null) {
                guilds.setSelectedValue(preferredGuild, true);
                return;
            }
        }

        if (!guildsModel.isEmpty() && guilds.getSelectedIndex() < 0) {
            guilds.setSelectedIndex(0);
        }
    }

    private Guild findPreferredAutoLoginGuild() {
        for (int i = 0; i < guildsModel.size(); i++) {
            Guild guild = guildsModel.get(i);
            if (guild.getAudioManager().getConnectedChannel() != null) {
                return guild;
            }
        }

        for (int i = 0; i < guildsModel.size(); i++) {
            Guild guild = guildsModel.get(i);
            GuildConfig guildConfig = DiscordAudioStreamBot.getConfig().getGuildConfig(guild);
            if (guildConfig.autoJoinAudioChannelId != null) {
                return guild;
            }
        }

        return null;
    }

    private Guild findGuildById(String guildId) {
        for (int i = 0; i < guildsModel.size(); i++) {
            Guild guild = guildsModel.get(i);
            if (Objects.equals(guild.getId(), guildId)) {
                return guild;
            }
        }
        return null;
    }

    private void updateGuildControls() {
        removeGuild.setEnabled(guilds.getSelectedValue() != null);
        updateVoiceChannelTree();
        updateActionButtons();
    }

    private void forceResyncView() {
        startVoiceTreeRefreshPolling();
        updateGuilds();
        updateVoiceChannelTree();
    }

    private void updateVoiceChannelTree() {
        Guild guild = guilds.getSelectedValue();
        DefaultMutableTreeNode root;
        if (guild == null) {
            root = new DefaultMutableTreeNode("No guild selected");
        } else {
            root = new DefaultMutableTreeNode(guild.getName());
            addVoiceChannelNodes(root, guild);
        }
        voiceChannelsTreeModel.setRoot(root);
        expandAllRows();
        restoreVoiceChannelTreeSelection(root, guild);
        updateActionButtons();
    }

    private void updateActionButtons() {
        Guild guild = guilds.getSelectedValue();
        AudioChannel selectedChannel = getSelectedAudioChannel();
        AudioChannelUnion connectedChannel = guild != null ? guild.getAudioManager().getConnectedChannel() : null;
        GuildConfig guildConfig = guild != null ? DiscordAudioStreamBot.getConfig().getGuildConfig(guild) : null;

        connectToChannel.setEnabled(selectedChannel != null
                && guild != null
                && guild.getSelfMember().hasPermission(selectedChannel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)
                && (connectedChannel == null || !Objects.equals(selectedChannel.getId(), connectedChannel.getId())));
        disconnectFromChannel.setEnabled(guild != null && connectedChannel != null);
        unsetFromAutoJoin.setEnabled(guildConfig != null && guildConfig.autoJoinAudioChannelId != null);
        setToAutoJoin.setEnabled(selectedChannel != null
                && guildConfig != null
                && !Objects.equals(selectedChannel.getId(), guildConfig.autoJoinAudioChannelId));
    }

    private AudioChannel getSelectedAudioChannel() {
        Guild guild = guilds.getSelectedValue();
        if (guild == null) {
            return null;
        }

        TreePath selectionPath = voiceChannelsTree.getSelectionPath();
        if (selectionPath == null) {
            return null;
        }

        Object lastPathComponent = selectionPath.getLastPathComponent();
        if (!(lastPathComponent instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof VoiceChannelTreeNodeData data)) {
            return null;
        }

        AudioChannel audioChannel = guild.getVoiceChannelById(data.channelId);
        if (audioChannel != null) {
            return audioChannel;
        }

        return guild.getStageChannelById(data.channelId);
    }

    private void connectSelectedChannel() {
        Guild guild = guilds.getSelectedValue();
        AudioChannel selectedChannel = getSelectedAudioChannel();
        if (guild == null || selectedChannel == null) {
            return;
        }
        if (!guild.getSelfMember().hasPermission(selectedChannel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)) {
            JOptionPane.showMessageDialog(this, "The bot does not have permission to join that channel.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        pendingConnectChannelIds.put(guild.getId(), selectedChannel.getId());
        pendingConnectStartedAt.put(guild.getId(), System.currentTimeMillis());
        DiscordAudioStreamBot.getInstance().joinAudio(selectedChannel);
        startVoiceTreeRefreshPolling();
        updateVoiceChannelTree();
    }

    private void disconnectSelectedGuildChannel() {
        Guild guild = guilds.getSelectedValue();
        if (guild == null) {
            return;
        }

        DiscordAudioStreamBot.getInstance().leaveAudio(guild);
        pendingConnectChannelIds.remove(guild.getId());
        pendingConnectStartedAt.remove(guild.getId());
        startVoiceTreeRefreshPolling();
        updateVoiceChannelTree();
    }

    private void clearAutoJoinChannel() {
        Guild guild = guilds.getSelectedValue();
        if (guild == null) {
            return;
        }

        GuildConfig guildConfig = DiscordAudioStreamBot.getConfig().getGuildConfig(guild);
        guildConfig.autoJoinAudioChannelId = null;
        saveConfigAndRefreshTree();
    }

    private void setSelectedChannelAsAutoJoin() {
        Guild guild = guilds.getSelectedValue();
        AudioChannel selectedChannel = getSelectedAudioChannel();
        if (guild == null || selectedChannel == null) {
            return;
        }

        GuildConfig guildConfig = DiscordAudioStreamBot.getConfig().getGuildConfig(guild);
        guildConfig.autoJoinAudioChannelId = selectedChannel.getId();
        saveConfigAndRefreshTree();
    }

    private void saveConfigAndRefreshTree() {
        try {
            DiscordAudioStreamBot.saveConfig();
            updateVoiceChannelTree();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save config.json. See app.log for details.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addVoiceChannelNodes(DefaultMutableTreeNode root, Guild guild) {
        Member selfMember = guild.getSelfMember();
        GuildConfig guildConfig = DiscordAudioStreamBot.getConfig().getGuildConfig(guild);
        String autoJoinChannelId = DiscordAudioStreamBot.getConfig().isAutoLogin() ? guildConfig.autoJoinAudioChannelId : null;
        AudioChannelViewState channelViewState = resolveAudioChannelViewState(guild);
        String connectedChannelId = channelViewState.connectedChannelId;
        String pendingChannelId = channelViewState.pendingChannelId;

        DefaultMutableTreeNode voiceRoot = new DefaultMutableTreeNode("Voice channels");
        addStandardVoiceChannels(voiceRoot, guild.getVoiceChannels(), selfMember, connectedChannelId, pendingChannelId, autoJoinChannelId);
        root.add(voiceRoot);

        DefaultMutableTreeNode stageRoot = new DefaultMutableTreeNode("Stage channels");
        addStageChannels(stageRoot, guild.getStageChannels(), selfMember, connectedChannelId, pendingChannelId, autoJoinChannelId);
        root.add(stageRoot);
    }

    private AudioChannelViewState resolveAudioChannelViewState(Guild guild) {
        AudioChannelUnion connectedChannel = guild.getAudioManager().getConnectedChannel();
        String connectedChannelId = connectedChannel != null ? connectedChannel.getId() : null;
        long now = System.currentTimeMillis();
        String pendingChannelId = pendingConnectChannelIds.get(guild.getId());
        if (pendingChannelId == null) {
            return new AudioChannelViewState(connectedChannelId, null);
        }
        Long pendingStartedAt = pendingConnectStartedAt.get(guild.getId());
        boolean keepPendingVisible = pendingStartedAt != null && now - pendingStartedAt < MIN_PENDING_DISPLAY_MILLIS;

        ConnectionStatus connectionStatus = guild.getAudioManager().getConnectionStatus();
        if (connectionStatus != null && connectionStatus.name().startsWith("CONNECTING")) {
            if (Objects.equals(pendingChannelId, connectedChannelId)) {
                pendingConnectChannelIds.remove(guild.getId());
                pendingConnectStartedAt.remove(guild.getId());
                return new AudioChannelViewState(connectedChannelId, null);
            }
            return new AudioChannelViewState(null, pendingChannelId);
        }

        if (Objects.equals(pendingChannelId, connectedChannelId)) {
            if (keepPendingVisible) {
                return new AudioChannelViewState(null, pendingChannelId);
            }
            pendingConnectChannelIds.remove(guild.getId());
            pendingConnectStartedAt.remove(guild.getId());
            return new AudioChannelViewState(connectedChannelId, null);
        }

        if (connectedChannelId != null) {
            pendingConnectChannelIds.remove(guild.getId());
            pendingConnectStartedAt.remove(guild.getId());
            return new AudioChannelViewState(connectedChannelId, null);
        }

        pendingConnectChannelIds.remove(guild.getId());
        pendingConnectStartedAt.remove(guild.getId());
        return new AudioChannelViewState(null, null);
    }

    private void addStandardVoiceChannels(DefaultMutableTreeNode parent, List<VoiceChannel> channels, Member selfMember, String connectedChannelId, String pendingChannelId, String autoJoinChannelId) {
        if (channels.isEmpty()) {
            parent.add(new DefaultMutableTreeNode("None"));
            return;
        }

        for (VoiceChannel channel : channels) {
            parent.add(new DefaultMutableTreeNode(new VoiceChannelTreeNodeData(
                    channel.getId(),
                    channel.getName(),
                    selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT),
                    Objects.equals(channel.getId(), connectedChannelId),
                    Objects.equals(channel.getId(), pendingChannelId),
                    Objects.equals(channel.getId(), autoJoinChannelId)
            )));
        }
    }

    private void addStageChannels(DefaultMutableTreeNode parent, List<StageChannel> channels, Member selfMember, String connectedChannelId, String pendingChannelId, String autoJoinChannelId) {
        if (channels.isEmpty()) {
            parent.add(new DefaultMutableTreeNode("None"));
            return;
        }

        for (StageChannel channel : channels) {
            parent.add(new DefaultMutableTreeNode(new VoiceChannelTreeNodeData(
                    channel.getId(),
                    channel.getName(),
                    selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT),
                    Objects.equals(channel.getId(), connectedChannelId),
                    Objects.equals(channel.getId(), pendingChannelId),
                    Objects.equals(channel.getId(), autoJoinChannelId)
            )));
        }
    }

    private void expandAllRows() {
        for (int i = 0; i < voiceChannelsTree.getRowCount(); i++) {
            voiceChannelsTree.expandRow(i);
        }
    }

    private void restoreVoiceChannelTreeSelection(DefaultMutableTreeNode root, Guild guild) {
        if (guild == null) {
            voiceChannelsTree.clearSelection();
            return;
        }

        TreePath primaryPath = findPrimaryChannelPath(root);
        if (primaryPath != null) {
            voiceChannelsTree.setSelectionPath(primaryPath);
            return;
        }

        if (Objects.equals(selectedVoiceChannelTreeGuildId, guild.getId()) && selectedVoiceChannelTreeChannelId != null) {
            TreePath rememberedPath = findChannelPath(root, selectedVoiceChannelTreeChannelId);
            if (rememberedPath != null) {
                voiceChannelsTree.setSelectionPath(rememberedPath);
                return;
            }
        }

        GuildConfig guildConfig = DiscordAudioStreamBot.getConfig().getGuildConfig(guild);
        if (DiscordAudioStreamBot.getConfig().isAutoLogin() && guildConfig.autoJoinAudioChannelId != null) {
            TreePath autoJoinPath = findChannelPath(root, guildConfig.autoJoinAudioChannelId);
            if (autoJoinPath != null) {
                voiceChannelsTree.setSelectionPath(autoJoinPath);
                return;
            }
        }

        voiceChannelsTree.clearSelection();
    }

    private void rememberVoiceChannelTreeSelection() {
        Guild guild = guilds.getSelectedValue();
        if (guild == null) {
            selectedVoiceChannelTreeGuildId = null;
            selectedVoiceChannelTreeChannelId = null;
            return;
        }

        TreePath selectionPath = voiceChannelsTree.getSelectionPath();
        if (selectionPath == null) {
            selectedVoiceChannelTreeGuildId = guild.getId();
            selectedVoiceChannelTreeChannelId = null;
            return;
        }

        Object lastPathComponent = selectionPath.getLastPathComponent();
        if (lastPathComponent instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof VoiceChannelTreeNodeData data) {
            selectedVoiceChannelTreeGuildId = guild.getId();
            selectedVoiceChannelTreeChannelId = data.channelId;
            return;
        }

        selectedVoiceChannelTreeGuildId = guild.getId();
        selectedVoiceChannelTreeChannelId = null;
    }

    private TreePath findPrimaryChannelPath(DefaultMutableTreeNode root) {
        TreePath connectedPath = findNodePath(root, NodeMatchMode.CONNECTED);
        if (connectedPath != null) {
            return connectedPath;
        }
        TreePath pendingPath = findNodePath(root, NodeMatchMode.PENDING);
        if (pendingPath != null) {
            return pendingPath;
        }
        return findNodePath(root, NodeMatchMode.AUTO_JOIN);
    }

    private TreePath findChannelPath(DefaultMutableTreeNode root, String channelId) {
        DefaultMutableTreeNode node = findNodeByChannelId(root, channelId);
        return node != null ? new TreePath(node.getPath()) : null;
    }

    private TreePath findNodePath(DefaultMutableTreeNode root, NodeMatchMode matchMode) {
        DefaultMutableTreeNode node = findNode(root, matchMode);
        return node != null ? new TreePath(node.getPath()) : null;
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, NodeMatchMode matchMode) {
        Object value = node.getUserObject();
        if (value instanceof VoiceChannelTreeNodeData data) {
            if (matches(data, matchMode)) {
                return node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode match = findNode(child, matchMode);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    private boolean matches(VoiceChannelTreeNodeData data, NodeMatchMode matchMode) {
        return switch (matchMode) {
            case CONNECTED -> data.connected;
            case PENDING -> data.pending;
            case AUTO_JOIN -> data.autoJoin;
        };
    }

    private void startVoiceTreeRefreshPolling() {
        voiceTreeRefreshTicksRemaining = 24;
        if (!voiceTreeRefreshTimer.isRunning()) {
            voiceTreeRefreshTimer.start();
        }
    }

    private void onVoiceTreeRefreshTimer() {
        if (voiceTreeRefreshTicksRemaining <= 0) {
            voiceTreeRefreshTimer.stop();
            return;
        }

        voiceTreeRefreshTicksRemaining--;
        updateVoiceChannelTree();

        Guild guild = guilds.getSelectedValue();
        if (guild == null) {
            voiceTreeRefreshTimer.stop();
            return;
        }

        AudioChannelUnion connectedChannel = guild.getAudioManager().getConnectedChannel();
        if (connectedChannel != null) {
            pendingConnectChannelIds.remove(guild.getId());
            Long pendingStartedAt = pendingConnectStartedAt.get(guild.getId());
            if (pendingStartedAt == null || System.currentTimeMillis() - pendingStartedAt >= MIN_PENDING_DISPLAY_MILLIS) {
                pendingConnectStartedAt.remove(guild.getId());
                voiceTreeRefreshTimer.stop();
                updateVoiceChannelTree();
                return;
            }
        }

        if (!pendingConnectChannelIds.containsKey(guild.getId())
                && guild.getAudioManager().getConnectionStatus() == ConnectionStatus.NOT_CONNECTED) {
            pendingConnectStartedAt.remove(guild.getId());
            voiceTreeRefreshTimer.stop();
        }
    }

    private DefaultMutableTreeNode findNodeByChannelId(DefaultMutableTreeNode node, String channelId) {
        Object value = node.getUserObject();
        if (value instanceof VoiceChannelTreeNodeData data && Objects.equals(data.channelId, channelId)) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode match = findNodeByChannelId(child, channelId);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    @Override
    public void onEvent(@Nonnull GenericEvent e) {
        if(e instanceof GenericGuildEvent) {
            final Guild guild = ((GenericGuildEvent) e).getGuild();
            if (e instanceof GuildJoinEvent) {
                guildsModel.addElement(guild);
                updateGuildControls();
            }
            if(e instanceof GuildLeaveEvent) {
                guildsModel.removeElement(guild);
                int index = guilds.getSelectedIndex();
                if (index == guildsModel.size()) {
                    index--;
                }
                if (index >= 0) {
                    guilds.setSelectedIndex(index);
                }

                updateGuildControls();
                GuildListCellRenderer.getInstance().clearIconCache(guild);
            }
            if(e instanceof GuildUpdateIconEvent) {
                GuildListCellRenderer.getInstance().clearIconCache(guild);
            }
            if (e instanceof GuildUpdateNameEvent || e instanceof GuildJoinEvent || e instanceof GuildLeaveEvent) {
                updateVoiceChannelTree();
            }
        }

        Guild selectedGuild = guilds.getSelectedValue();
        if (selectedGuild == null) {
            return;
        }

        if (e instanceof ChannelCreateEvent channelCreateEvent
                && selectedGuild.equals(channelCreateEvent.getGuild())) {
            updateVoiceChannelTree();
        }
        if (e instanceof ChannelDeleteEvent channelDeleteEvent
                && selectedGuild.equals(channelDeleteEvent.getGuild())) {
            updateVoiceChannelTree();
        }
        if (e instanceof ChannelUpdateNameEvent channelUpdateNameEvent
                && selectedGuild.equals(channelUpdateNameEvent.getGuild())) {
            updateVoiceChannelTree();
        }
        if (e instanceof GuildVoiceUpdateEvent guildVoiceUpdateEvent
                && selectedGuild.equals(guildVoiceUpdateEvent.getGuild())
                && guildVoiceUpdateEvent.getMember().equals(selectedGuild.getSelfMember())) {
            startVoiceTreeRefreshPolling();
            updateVoiceChannelTree();
        }
    }

    private enum NodeMatchMode {
        CONNECTED,
        PENDING,
        AUTO_JOIN
    }

    private static final class VoiceChannelTreeNodeData {
        private final String channelId;
        private final String name;
        private final boolean accessible;
        private final boolean connected;
        private final boolean pending;
        private final boolean autoJoin;

        private VoiceChannelTreeNodeData(String channelId, String name, boolean accessible, boolean connected, boolean pending, boolean autoJoin) {
            this.channelId = channelId;
            this.name = name;
            this.accessible = accessible;
            this.connected = connected;
            this.pending = pending;
            this.autoJoin = autoJoin;
        }
    }

    private static final class AudioChannelViewState {
        private final String connectedChannelId;
        private final String pendingChannelId;

        private AudioChannelViewState(String connectedChannelId, String pendingChannelId) {
            this.connectedChannelId = connectedChannelId;
            this.pendingChannelId = pendingChannelId;
        }
    }

    private static final class VoiceChannelTreeCellRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel label;
        private final java.awt.Color defaultBackground;
        private final java.awt.Color selectionBackground;
        private final java.awt.Color defaultForeground;
        private final java.awt.Color selectionForeground;
        private final java.awt.Color connectedBackground;
        private final java.awt.Color pendingBackground;

        private VoiceChannelTreeCellRenderer() {
            super(new java.awt.BorderLayout());
            label = new JLabel();
            label.setOpaque(false);
            add(label, java.awt.BorderLayout.CENTER);
            setOpaque(true);

            defaultBackground = firstNonNull(UIManager.getColor("Tree.textBackground"), java.awt.Color.WHITE);
            selectionBackground = firstNonNull(UIManager.getColor("Tree.selectionBackground"), new java.awt.Color(184, 207, 229));
            defaultForeground = firstNonNull(UIManager.getColor("Tree.textForeground"), java.awt.Color.BLACK);
            selectionForeground = firstNonNull(UIManager.getColor("Tree.selectionForeground"), java.awt.Color.BLACK);
            connectedBackground = java.awt.Color.decode("#CFEFD1");
            pendingBackground = java.awt.Color.decode("#F7D7A8");

            setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        }

        @Override
        public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            String text = String.valueOf(value);
            java.awt.Color background = defaultBackground;
            java.awt.Color foreground = defaultForeground;
            java.awt.Font font = tree.getFont();

            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof VoiceChannelTreeNodeData data) {
                text = formatLabel(data);
                if (data.connected) {
                    background = connectedBackground;
                    font = font.deriveFont(java.awt.Font.BOLD);
                } else if (data.pending) {
                    background = pendingBackground;
                }
            }

            if (selected) {
                background = selectionBackground;
                foreground = selectionForeground;
            }

            label.setText(text);
            label.setForeground(foreground);
            label.setFont(font);
            setBackground(background);

            return this;
        }

        private static java.awt.Color firstNonNull(java.awt.Color primary, java.awt.Color fallback) {
            return primary != null ? primary : fallback;
        }

        private String formatLabel(VoiceChannelTreeNodeData data) {
            StringBuilder text = new StringBuilder("<html>");
            if (data.connected) {
                text.append("<b>");
            }
            text.append(escapeHtml(data.name));
            if (data.connected) {
                text.append("</b>");
                text.append(" ").append(tag("connected", "#2E8B57"));
            } else if (data.pending) {
                text.append(" ").append(tag("connecting", "#B8860B"));
            }
            if (data.autoJoin) {
                text.append(" ").append(tag("auto-join", "#1E6BD6"));
            }
            text.append(" ").append(tag(data.accessible ? "available" : "unavailable", data.accessible ? "#2E8B57" : "#B03A2E"));
            text.append("</html>");
            return text.toString();
        }

        private String tag(String text, String color) {
            return "<font color='" + color + "'>[" + escapeHtml(text) + "]</font>";
        }

        private String escapeHtml(String value) {
            return value
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
        }
    }
}
