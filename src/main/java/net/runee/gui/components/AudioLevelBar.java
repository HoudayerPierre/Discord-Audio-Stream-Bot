package net.runee.gui.components;

import net.runee.misc.Utils;

import javax.swing.*;
import java.awt.*;

public class AudioLevelBar extends JComponent {
    private static final int SEGMENT_COUNT = 100;

    private double level;
    private boolean forceMinimumVisibleSegment;

    public AudioLevelBar() {
        setOpaque(false);
        setPreferredSize(new Dimension(180, 18));
        setMinimumSize(new Dimension(120, 18));
    }

    public void setLevel(double level) {
        double bounded = Math.max(0d, Math.min(1d, level));
        if (Math.abs(this.level - bounded) >= 0.01d) {
            this.level = bounded;
            repaint();
        }
    }

    public void setForceMinimumVisibleSegment(boolean forceMinimumVisibleSegment) {
        this.forceMinimumVisibleSegment = forceMinimumVisibleSegment;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int gap = 2;
            int segmentWidth = Math.max(2, (width - gap * (SEGMENT_COUNT - 1)) / SEGMENT_COUNT);
            int usedWidth = segmentWidth * SEGMENT_COUNT + gap * (SEGMENT_COUNT - 1);
            int startX = Math.max(0, (width - usedWidth) / 2);
            int litSegments = (int) Math.round(level * SEGMENT_COUNT);
            if (forceMinimumVisibleSegment && level >= 0.01d) {
                litSegments = Math.max(1, litSegments);
            }

            g2.setColor(new Color(0x14, 0x16, 0x1b));
            g2.fillRoundRect(0, 0, width, height, 8, 8);

            for (int i = 0; i < SEGMENT_COUNT; i++) {
                double ratio = (i + 1d) / SEGMENT_COUNT;
                Color activeColor = ratio <= 0.65d
                        ? Utils.colorGreen
                        : ratio <= 0.85d ? Utils.colorYellow : Utils.colorRed;
                Color color = i < litSegments ? activeColor : new Color(0x2d, 0x31, 0x38);
                int x = startX + i * (segmentWidth + gap);
                g2.setColor(color);
                g2.fillRoundRect(x, 2, segmentWidth, height - 4, 4, 4);
            }

            g2.setColor(new Color(0x45, 0x4b, 0x54));
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8);
        } finally {
            g2.dispose();
        }
    }
}
