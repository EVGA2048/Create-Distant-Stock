package dev.distantstock.client;

import dev.distantstock.net.AdminConfigS2C;
import dev.distantstock.net.SaveAdminC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/** 管理员链路设置。独立工业面板，密码遮挡。 */
public final class AdminScreen extends Screen {
    private static final int W = 250;
    private static final int H = 196;

    private final AdminConfigS2C start;
    private boolean host;
    private int left;
    private int top;
    private EditBox selfId;
    private EditBox bind;
    private EditBox peers;
    private EditBox token;

    public AdminScreen(AdminConfigS2C start) {
        super(Component.translatable("gui.distantstock.admin"));
        this.start = start;
        this.host = !"client".equalsIgnoreCase(start.role());
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int fx = left + 86;
        int fw = 148;
        selfId = box(fx, top + 56, fw, 14, start.selfId(), 32);
        bind = box(fx, top + 78, fw, 14, start.bind(), 64);
        peers = box(fx, top + 100, fw, 14, start.peers(), 512);
        token = box(fx, top + 122, fw, 14, start.token(), 128);
        token.setFormatter((value, pos) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));

        addRenderableWidget(Button.builder(Component.translatable("gui.distantstock.admin.host"), b -> host = true)
                .bounds(left + 12, top + 28, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.distantstock.admin.client"), b -> host = false)
                .bounds(left + 88, top + 28, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.distantstock.admin.save"), b -> save())
                .bounds(left + 80, top + H - 28, 90, 18).build());
    }

    private EditBox box(int x, int y, int w, int h, String value, int max) {
        EditBox box = new EditBox(font, x, y, w, h, Component.empty());
        box.setMaxLength(max);
        box.setBordered(false);
        box.setTextColor(DistantPanel.INK);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        DistantPanel.window(g, left, top, W, H);
        DistantPanel.title(g, font, Component.translatable("gui.distantstock.admin"), left, top, W);
        DistantPanel.lamp(g, left + W - 16, top + 5, start.peerUp());
        String link = start.linkLabel();
        g.drawString(font, link, left + W - font.width(link) - 22, top + 6,
                start.peerUp() ? DistantPanel.TITLE : DistantPanel.DOWN, false);

        g.drawString(font, Component.translatable("gui.distantstock.admin.self"), left + 12, top + 58, DistantPanel.INK, false);
        g.drawString(font, Component.translatable("gui.distantstock.admin.bind"), left + 12, top + 80, DistantPanel.INK, false);
        g.drawString(font, Component.translatable(host ? "gui.distantstock.admin.peers" : "gui.distantstock.admin.warehouse"),
                left + 12, top + 102, DistantPanel.INK, false);
        g.drawString(font, Component.translatable("gui.distantstock.admin.token"), left + 12, top + 124, DistantPanel.INK, false);
        DistantPanel.field(g, left + 84, top + 54, 152, 16);
        DistantPanel.field(g, left + 84, top + 76, 152, 16);
        DistantPanel.field(g, left + 84, top + 98, 152, 16);
        DistantPanel.field(g, left + 84, top + 120, 152, 16);
        g.drawString(font, Component.translatable(host ? "gui.distantstock.admin.hint.host" : "gui.distantstock.admin.hint.client"),
                left + 12, top + 144, DistantPanel.MUTED, false);
        super.render(g, mouseX, mouseY, partial);
    }

    private void save() {
        PacketDistributor.sendToServer(new SaveAdminC2S(
                host ? "host" : "client",
                selfId.getValue().trim(),
                bind.getValue().trim(),
                token.getValue(),
                peers.getValue().trim()
        ));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
