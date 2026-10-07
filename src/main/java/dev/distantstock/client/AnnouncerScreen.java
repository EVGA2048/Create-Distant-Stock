package dev.distantstock.client;

import dev.distantstock.block.AnnouncerBlockEntity;
import dev.distantstock.net.OpenAnnouncerS2C;
import dev.distantstock.net.SaveAnnouncerC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact editor for a local PA terminal. Display Link parameters are read-only diagnostics here. */
public final class AnnouncerScreen extends Screen {
    private static final int W = 320;
    private static final int H = 270;
    private static final int FRAME = 0xFF151A1C;
    private static final int PANEL = 0xFF20292C;
    private static final int PANEL2 = 0xFF2A3437;
    private static final int INK = 0xFFE9E3D4;
    private static final int MUTED = 0xFFA9A28F;
    private static final int BRASS = 0xFFD2A84B;

    private OpenAnnouncerS2C snapshot;
    private EditBox template;
    private EditBox prefix;
    private EditBox radius;
    private int soundProfile;
    private Button soundButton;

    public AnnouncerScreen(OpenAnnouncerS2C snapshot) {
        super(Component.translatable(snapshot.networked()
                ? "gui.distantstock.network_broadcaster.title"
                : "gui.distantstock.announcer.title"));
        this.snapshot = snapshot;
        this.soundProfile = snapshot.soundProfile();
    }

    public boolean isSource(net.minecraft.core.BlockPos pos) {
        return snapshot.source().equals(pos);
    }

    public void update(OpenAnnouncerS2C next) {
        if (next == null || !snapshot.source().equals(next.source())) return;
        snapshot = next;
        if (template != null && !template.isFocused()) template.setValue(next.template());
        if (prefix != null && !prefix.isFocused()) prefix.setValue(next.prefix());
        if (radius != null && !radius.isFocused()) radius.setValue(Integer.toString(next.radius()));
        soundProfile = next.soundProfile();
        refreshSoundButton();
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        int x = left();
        int y = top();
        template = new EditBox(font, x + 16, y + 48, W - 32, 18,
                Component.translatable("gui.distantstock.announcer.template"));
        template.setMaxLength(AnnouncerBlockEntity.MAX_TEMPLATE);
        template.setValue(snapshot.template());
        addRenderableWidget(template);

        prefix = new EditBox(font, x + 16, y + 86, 150, 18,
                Component.translatable("gui.distantstock.announcer.prefix"));
        prefix.setMaxLength(AnnouncerBlockEntity.MAX_PREFIX);
        prefix.setValue(snapshot.prefix());
        addRenderableWidget(prefix);

        if (!snapshot.networked()) {
            radius = new EditBox(font, x + 16, y + 124, 58, 18,
                    Component.translatable("gui.distantstock.announcer.radius"));
            radius.setMaxLength(3);
            radius.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
            radius.setValue(Integer.toString(snapshot.radius()));
            addRenderableWidget(radius);
        }

        soundProfile = snapshot.soundProfile();
        soundButton = Button.builder(soundLabel(), button -> {
                    soundProfile = (soundProfile + 1) % 3;
                    refreshSoundButton();
                })
                .bounds(x + 16, y + 159, 170, 20).build();
        addRenderableWidget(soundButton);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(x + W - 92, y + H - 28, 76, 20).build());
    }

    private Component soundLabel() {
        return Component.translatable("gui.distantstock.announcer.sound." + soundProfile);
    }

    private void refreshSoundButton() {
        if (soundButton != null) soundButton.setMessage(soundLabel());
    }

    private void saveAndClose() {
        int parsed = snapshot.radius();
        try {
            if (radius != null) parsed = Integer.parseInt(radius.getValue());
        } catch (NumberFormatException ignored) {
        }
        parsed = Math.clamp(parsed, AnnouncerBlockEntity.MIN_RADIUS, AnnouncerBlockEntity.MAX_RADIUS);
        PacketDistributor.sendToServer(new SaveAnnouncerC2S(snapshot.source(), template.getValue(), prefix.getValue(), parsed, soundProfile));
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, FRAME);
        g.fill(x, y, x + W, y + H, PANEL);
        g.fill(x + 10, y + 10, x + W - 10, y + 34, PANEL2);
        g.fill(x + 10, y + 10, x + 13, y + H - 10, BRASS);

        g.drawString(font, title, x + 20, y + 18, INK, false);
        g.drawString(font, Component.translatable("gui.distantstock.announcer.template"),
                x + 16, y + 38, MUTED, false);
        g.drawString(font, Component.translatable("gui.distantstock.announcer.prefix"),
                x + 16, y + 76, MUTED, false);
        g.drawString(font, Component.translatable("gui.distantstock.announcer.prefix_hint"),
                x + 172, y + 91, MUTED, false);
        if (!snapshot.networked()) {
            g.drawString(font, Component.translatable("gui.distantstock.announcer.radius"),
                    x + 16, y + 114, MUTED, false);
            g.drawString(font, Component.translatable("gui.distantstock.announcer.radius_hint",
                            AnnouncerBlockEntity.MIN_RADIUS, AnnouncerBlockEntity.MAX_RADIUS),
                    x + 80, y + 129, MUTED, false);
        }

        g.drawString(font, Component.translatable("gui.distantstock.announcer.sound"),
                x + 16, y + 147, MUTED, false);
        g.drawString(font, Component.translatable("gui.distantstock.announcer.parameters"),
                x + 16, y + 189, BRASS, false);
        for (int i = 0; i < AnnouncerBlockEntity.PARAMETER_COUNT; i++) {
            String value = i < snapshot.parameters().size() ? snapshot.parameters().get(i) : "";
            if (value.isBlank()) value = Component.translatable("gui.distantstock.announcer.empty").getString();
            String row = "{" + (i + 1) + "}  " + value;
            if (font.width(row) > W - 40) {
                while (!row.isEmpty() && font.width(row + "…") > W - 40) row = row.substring(0, row.length() - 1);
                row += "…";
            }
            g.drawString(font, row, x + 20, y + 204 + i * 13, i < snapshot.parameters().size()
                    && !snapshot.parameters().get(i).isBlank() ? INK : MUTED, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
