package net.runee.gui.components;

import com.jgoodies.forms.builder.FormBuilder;
import net.dv8tion.jda.api.JDA;
import net.runee.DiscordAudioStreamBot;
import net.runee.ListenHandler;
import net.runee.SpeakHandler;
import net.runee.audio.InputDeviceDescriptor;
import net.runee.audio.InputDeviceService;
import net.runee.audio.PlaybackDeviceDescriptor;
import net.runee.audio.PlaybackDeviceService;
import net.runee.gui.renderer.PlaybackDeviceListCellRenderer;
import net.runee.gui.renderer.RecordingDeviceListCellRenderer;
import net.runee.gui.listitems.PlaybackDeviceItem;
import net.runee.misc.Utils;
import net.runee.misc.gui.SpecBuilder;
import net.runee.gui.listitems.RecordingDeviceItem;
import net.runee.model.Config;

import javax.swing.*;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class SettingsPanel extends JPanel {
    // general
    private JPasswordField botToken;
    private JCheckBox hideToken;
    private JCheckBox autoLogin;

    // audio
    private JButton speakEnabled;
    private JButton listenEnabled;
    private JButton testTone;
    private JButton outputTestTone;
    private JList<RecordingDeviceItem> recordingDevices;
    private JList<PlaybackDeviceItem> playbackDevices;
    private JCheckBox speakThresholdEnabled;
    private JSlider speakThreshold;
    private JSlider inputVolume;
    private JSlider outputVolume;
    private AudioLevelBar inputLevelBar;
    private AudioLevelBar outputLevelBar;
    private Timer levelTimer;

    public SettingsPanel() {
        initComponents();
        layoutComponents();
        loadConfig();
    }

    private void initComponents() {
        final DiscordAudioStreamBot bot = DiscordAudioStreamBot.getInstance();

        // general
        botToken = new JPasswordField();
        Utils.addChangeListener(botToken, e -> {
            DiscordAudioStreamBot.getConfig().botToken = Utils.emptyStringToNull(new String(((JPasswordField) e.getSource()).getPassword()));
            updateAutoLoginEnabled();
            saveConfig();
        });
        hideToken = new JCheckBox("Hide");
        hideToken.setSelected(true);
        hideToken.addActionListener(e -> updateTokenVisibility());
        autoLogin = new JCheckBox();
        autoLogin.addActionListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.autoLogin = !cfg.isAutoLogin();
            saveConfig();
        });

        // audio
        speakEnabled = new JButton();
        speakEnabled.addActionListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.speakEnabled = !cfg.getSpeakEnabled();
            bot.setSpeakEnabled(cfg.getSpeakEnabled());
            updateSpeakEnabled();
            saveConfig();
        });
        listenEnabled = new JButton();
        listenEnabled.addActionListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.listenEnabled = !cfg.getListenEnabled();
            bot.setListenEnabled(cfg.getListenEnabled());
            updateListenEnabled();
            saveConfig();
        });
        testTone = new JButton("Test tone");
        testTone.addActionListener(e -> bot.playSendTestTone());
        outputTestTone = new JButton("Output test");
        outputTestTone.addActionListener(e -> bot.playOutputTestTone());
        recordingDevices = new JList<>();
        recordingDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recordingDevices.setCellRenderer(new RecordingDeviceListCellRenderer());
        recordingDevices.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && recordingDevices.getSelectedIndex() >= 0) {
                RecordingDeviceItem value = recordingDevices.getSelectedValue();
                InputDeviceDescriptor recordingDevice = value != null ? value.getDescriptor() : null;
                bot.setRecordingDevice(recordingDevice);
                InputDeviceService.applySelection(DiscordAudioStreamBot.getConfig(), recordingDevice);
                saveConfig();
            }
        });
        playbackDevices = new JList<>();
        playbackDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playbackDevices.setCellRenderer(new PlaybackDeviceListCellRenderer());
        playbackDevices.addListSelectionListener(e -> {
            if (playbackDevices.getSelectedIndex() >= 0) {
                PlaybackDeviceItem value = playbackDevices.getSelectedValue();
                String playbackDevice = value != null ? value.getId() : null;
                bot.setPlaybackDevice(playbackDevice);
                DiscordAudioStreamBot.getConfig().playbackDevice = playbackDevice;
                saveConfig();
            }
        });
        speakThresholdEnabled = new JCheckBox();
        speakThresholdEnabled.addActionListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.speakThresholdEnabled = !cfg.getSpeakThresholdEnabled();
            updateSpeakThresholdEnabled();
            saveConfig();
        });
        speakThreshold = new JSlider();
        speakThreshold.setMinimum(1);
        speakThreshold.setMaximum(99);
        speakThreshold.addChangeListener(e -> {
            if (!speakThreshold.getValueIsAdjusting()) {
                final Config cfg = DiscordAudioStreamBot.getConfig();
                cfg.speakThreshold = speakThreshold.getValue() * (1d/100d);
            }
        });
        inputVolume = new JSlider();
        inputVolume.setMinimum(0);
        inputVolume.setMaximum(150);
        inputVolume.addChangeListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.inputVolume = inputVolume.getValue() / 100d;
            if (!inputVolume.getValueIsAdjusting()) {
                saveConfig();
            }
        });
        outputVolume = new JSlider();
        outputVolume.setMinimum(0);
        outputVolume.setMaximum(150);
        outputVolume.addChangeListener(e -> {
            final Config cfg = DiscordAudioStreamBot.getConfig();
            cfg.outputVolume = outputVolume.getValue() / 100d;
            if (!outputVolume.getValueIsAdjusting()) {
                saveConfig();
            }
        });
        inputLevelBar = new AudioLevelBar();
        outputLevelBar = new AudioLevelBar();
        outputLevelBar.setForceMinimumVisibleSegment(true);
        levelTimer = new Timer(100, e -> refreshAudioLevels());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    levelTimer.start();
                } else {
                    levelTimer.stop();
                }
            }
        });
    }

    private void loadConfig() {
        final Config cfg = DiscordAudioStreamBot.getConfig();

        // general
        botToken.setText(Utils.nullToEmptyString(cfg.botToken));
        updateTokenVisibility();
        autoLogin.setSelected(cfg.isAutoLogin());
        updateAutoLoginEnabled();

        // voice
        speakEnabled.setSelected(cfg.getSpeakEnabled());
        updateSpeakEnabled();
        listenEnabled.setSelected(cfg.getListenEnabled());
        updateListenEnabled();
        {
            DefaultListModel<RecordingDeviceItem> model = new DefaultListModel<>();
            List<InputDeviceDescriptor> devices = InputDeviceService.listInputDevices();
            for (InputDeviceDescriptor device : devices) {
                model.addElement(new RecordingDeviceItem(device));
            }
            recordingDevices.setModel(model);
            InputDeviceDescriptor selected = InputDeviceService.resolveConfiguredInputDevice(cfg);
            for (int i = 0; i < model.getSize(); i++) {
                RecordingDeviceItem recordingDevice = model.get(i);
                InputDeviceDescriptor descriptor = recordingDevice != null ? recordingDevice.getDescriptor() : null;
                if (Objects.equals(descriptor, selected)) {
                    recordingDevices.setSelectedIndex(i);
                    break;
                }
            }
        }
        {
            DefaultListModel<PlaybackDeviceItem> model = new DefaultListModel<>();
            model.addElement(new PlaybackDeviceItem(null, "(Default playback device)"));
            for (PlaybackDeviceDescriptor device : PlaybackDeviceService.listPlaybackDevices()) {
                model.addElement(new PlaybackDeviceItem(device.getId(), device.getListLabel()));
            }
            playbackDevices.setModel(model);
            for (int i = 0; i < model.getSize(); i++) {
                PlaybackDeviceItem playbackDeviceItem = model.get(i);
                String playbackDeviceId = playbackDeviceItem != null ? playbackDeviceItem.getId() : null;
                String playbackDeviceName = playbackDeviceItem != null ? playbackDeviceItem.getName() : null;
                if (Objects.equals(playbackDeviceId, cfg.playbackDevice)
                        || Objects.equals(playbackDeviceName, cfg.playbackDevice)) {
                    playbackDevices.setSelectedIndex(i);
                    break;
                }
            }
        }
        speakThresholdEnabled.setSelected(cfg.getSpeakThresholdEnabled());
        speakThreshold.setValue((int) (cfg.getSpeakThreshold() * 100));
        inputVolume.setValue((int) Math.round(cfg.getInputVolume() * 100));
        outputVolume.setValue((int) Math.round(cfg.getOutputVolume() * 100));
        updateSpeakThresholdEnabled();
    }

    private void saveConfig() {
        try {
            DiscordAudioStreamBot.saveConfig();
        } catch (IOException ex) {
            Utils.guiError(this, "Failed to save config", ex);
        }
    }

    private void layoutComponents() {
        int row = 1;
        FormBuilder
                .create()
                .columns(SpecBuilder
                        .create()
                        .add("r:p")
                        .add("f:max(p;100px)")
                        .gap("f:3dlu:g")
                        .add("r:p")
                        .add("f:max(p;100px)")
                        .build()
                )
                .rows(SpecBuilder
                        .create()
                        .add("c:p") // general
                        .add("c:p")
                        .add("c:p")
                        .gapUnrelated().add("c:p")
                        .add("c:p")
                        .add("t:p")
                        .add("c:p")
                        .add("c:p")
                        .add("c:p")
                        .add("c:p", 4)
                        .build()
                )
                .columnGroups(new int[]{1, 5}, new int[]{2, 6})
                .panel(this)
                .border(BorderFactory.createEmptyBorder(5, 5, 5, 5))
                .addSeparator("General").xyw(1, row, 7)
                .add("Bot token").xy(1, row += 2)
                /**/.add(botToken).xy(3, row)
                /**/.add("Auto login").xy(5, row)
                /**/.add(autoLogin).xy(7, row)
                .add("").xy(1, row += 2)
                /**/.add(hideToken).xy(3, row)
                .addSeparator("Audio").xyw(1, row += 2, 7)
                .add("Mute/Unmute").xy(1, row += 2)
                /**/.add(speakEnabled).xy(3, row)
                /**/.add("Deafen/Undeafen").xy(5, row)
                /**/.add(listenEnabled).xy(7, row)
                .add("Send test").xy(1, row += 2)
                /**/.add(testTone).xy(3, row)
                /**/.add("Output test").xy(5, row)
                /**/.add(outputTestTone).xy(7, row)
                .add("Input device").xy(1, row += 2)
                /**/.add(recordingDevices).xy(3, row)
                /**/.add("Output device").xy(5, row)
                /**/.add(playbackDevices).xy(7, row)
                .add("Input level").xy(1, row += 2)
                /**/.add(inputLevelBar).xy(3, row)
                /**/.add("Output level").xy(5, row)
                /**/.add(outputLevelBar).xy(7, row)
                .add("Input volume").xy(1, row += 2)
                /**/.add(inputVolume).xy(3, row)
                .add("Output volume").xy(5, row)
                /**/.add(outputVolume).xy(7, row)
                .add("Voice activity").xy(1, row += 2)
                /**/.add(speakThresholdEnabled).xy(3, row)
                .add("Speak threshold").xy(1, row += 2)
                /**/.add(speakThreshold).xy(3, row)
                .build();
    }

    public void updateLoginStatus(JDA.Status status) {
        switch (status) {
            case SHUTDOWN:
            case FAILED_TO_LOGIN:
                botToken.setEnabled(true);
                break;
            default:
                botToken.setEnabled(false);
                break;
        }
    }

    private void updateAutoLoginEnabled() {
        boolean enabled = DiscordAudioStreamBot.getConfig().botToken != null;
        autoLogin.setEnabled(enabled);
    }

    private void updateTokenVisibility() {
        if (botToken.getPassword().length == 0) {
            botToken.setEchoChar((char) 0);
            return;
        }
        botToken.setEchoChar(hideToken.isSelected() ? '*' : (char) 0);
    }

    private void updateSpeakEnabled() {
        boolean enabled = DiscordAudioStreamBot.getConfig().getSpeakEnabled();
        ImageIcon icon = Utils.getIcon("icomoon/32px/031-mic.png", 24, true);
        if (!enabled) {
            icon = new ImageIcon(Utils.overlayImage((BufferedImage) icon.getImage(), Utils.getIcon("runee/32px/strike-through.png", 24, true).getImage()));
        }
        speakEnabled.setIcon(icon);
        recordingDevices.setEnabled(enabled);
        inputLevelBar.setEnabled(enabled);
        testTone.setEnabled(enabled);
        inputVolume.setEnabled(enabled);
        updateOutputVolumeEnabled();
    }

    private void updateListenEnabled() {
        boolean enabled = DiscordAudioStreamBot.getConfig().getListenEnabled();
        ImageIcon icon = Utils.getIcon("icomoon/32px/017-headphones.png", 24, true);
        if (!enabled) {
            icon = new ImageIcon(Utils.overlayImage((BufferedImage) icon.getImage(), Utils.getIcon("runee/32px/strike-through.png", 24, true).getImage()));
        }
        listenEnabled.setIcon(icon);
        playbackDevices.setEnabled(enabled);
        outputLevelBar.setEnabled(DiscordAudioStreamBot.getConfig().getSpeakEnabled() || enabled);
        outputTestTone.setEnabled(enabled);
        updateOutputVolumeEnabled();
    }

    private void updateSpeakThresholdEnabled() {
        boolean enabled = DiscordAudioStreamBot.getConfig().getSpeakThresholdEnabled();
        speakThreshold.setEnabled(enabled);
    }

    private void updateOutputVolumeEnabled() {
        outputVolume.setEnabled(DiscordAudioStreamBot.getConfig().getSpeakEnabled()
                || DiscordAudioStreamBot.getConfig().getListenEnabled());
    }

    private void refreshAudioLevels() {
        RecordingDeviceItem recordingDeviceItem = recordingDevices.getSelectedValue();
        InputDeviceDescriptor recordingDevice = recordingDeviceItem != null ? recordingDeviceItem.getDescriptor() : null;
        PlaybackDeviceItem playbackDeviceItem = playbackDevices.getSelectedValue();
        String playbackDevice = playbackDeviceItem != null ? playbackDeviceItem.getId() : null;

        inputLevelBar.setLevel(DiscordAudioStreamBot.getConfig().getSpeakEnabled()
                ? DiscordAudioStreamBot.getInstance().getInputVisualLevel(recordingDevice)
                : 0d);
        outputLevelBar.setLevel((DiscordAudioStreamBot.getConfig().getSpeakEnabled()
                || DiscordAudioStreamBot.getConfig().getListenEnabled())
                ? DiscordAudioStreamBot.getInstance().getAppOutputVisualLevel(recordingDevice, playbackDevice)
                : 0d);
    }
}
