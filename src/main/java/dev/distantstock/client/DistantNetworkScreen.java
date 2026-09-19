package dev.distantstock.client;

import dev.distantstock.net.DistantNetworkActionC2S;
import dev.distantstock.net.DistantNetworkStateS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small Create-style management page for the Distant Stock network of the selected warehouse. */
public final class DistantNetworkScreen extends Screen {
    private static final int W = 220;
    private static final int H = 142;
    private static final int FRAME = 0xFF1B282C;
    private static final int PAPER = 0xFF24343A;
    private static final int INK = 0xFFEAF6F8;
    private static final int HINT = 0xFF8FB2BC;
    private static final int ACCENT = 0xFF9BE0C4;

    private final RequesterScreen parent;
    private final boolean requestStateOnInit;
    private DistantNetworkStateS2C state =
            new DistantNetworkStateS2C(false, null, "", false, "");

    private EditBox nameInput;
    private EditBox codeInput;
    private Button createButton;
    private Button joinButton;
    private Button resetButton;
    private Button leaveButton;
    private Button copyButton;

    public DistantNetworkScreen(RequesterScreen parent) {
        this(parent, true);
    }

    /** Client-smoke constructor: render the page without requiring a live play connection. */
    public DistantNetworkScreen(RequesterScreen parent, boolean requestStateOnInit) {
        super(Component.translatable("gui.distantstock.distant_network.title"));
        this.parent = parent;
        this.requestStateOnInit = requestStateOnInit;
    }

    public void apply(DistantNetworkStateS2C next) {
        state = next == null ? new DistantNetworkStateS2C(false, null, "", false, "") : next;
        updateWidgets();
    }

    private int x() {
        return (width - W) / 2;
    }

    private int y() {
        return (height - H) / 2;
    }

    @Override
    protected void init() {
        int x = x();
        int y = y();

        nameInput = new EditBox(font, x + 10, y + 70, 128, 12,
                Component.translatable("gui.distantstock.distant_network.name"));
        nameInput.setMaxLength(dev.distantstock.routing.DistantNetworkDirectory.MAX_NAME_LENGTH);
        nameInput.setBordered(false);
        nameInput.setTextColor(INK);
        addRenderableWidget(nameInput);

        codeInput = new EditBox(font, x + 10, y + 97, 104, 12,
                Component.translatable("gui.distantstock.distant_network.code"));
        codeInput.setMaxLength(9);
        codeInput.setBordered(false);
        codeInput.setTextColor(INK);
        addRenderableWidget(codeInput);

        createButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.create"),
                        b -> send(DistantNetworkActionC2S.CREATE, nameInput.getValue().trim()))
                .bounds(x + 145, y + 66, 62, 18).build());
        joinButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.join"),
                        b -> send(DistantNetworkActionC2S.JOIN, codeInput.getValue().trim()))
                .bounds(x + 121, y + 93, 86, 18).build());
        copyButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.distantstock.distant_network.copy_code"), b -> {
                    if (minecraft != null && !state.joinCode().isBlank()) {
                        minecraft.keyboardHandler.setClipboard(state.joinCode());
                    }
                })
                .bounds(x + 10, y + 92, 82, 18).build());
        resetButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.reset_code"),
                        b -> send(DistantNetworkActionC2S.RESET_CODE, ""))
                .bounds(x + 98, y + 92, 86, 18).build());
        leaveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.leave"),
                        b -> send(DistantNetworkActionC2S.LEAVE, ""))
                .bounds(x + 10, y + 116, 72, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.back"), b -> onClose())
                .bounds(x + W - 54, y + H - 22, 44, 16).build());

        updateWidgets();
        if (requestStateOnInit) {
            send(DistantNetworkActionC2S.REFRESH, "");
        }
    }

    private void updateWidgets() {
        if (nameInput == null) return;
        boolean joined = state.joined();
        boolean canJoinHere = state.localWarehouse() && !joined;
        nameInput.visible = canJoinHere;
        codeInput.visible = canJoinHere;
        createButton.visible = canJoinHere;
        joinButton.visible = canJoinHere;
        copyButton.visible = joined && state.owner() && !state.joinCode().isBlank();
        resetButton.visible = joined && state.owner() && state.localWarehouse();
        leaveButton.visible = joined && state.localWarehouse();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = x();
        int y = y();
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, FRAME);
        g.fill(x, y, x + W, y + H, PAPER);
        g.drawString(font, Component.translatable("gui.distantstock.distant_network.title"),
                x + 10, y + 8, INK, false);
        g.fill(x + 8, y + 20, x + W - 8, y + 21, 0xFF3E5A61);

        if (state.joined()) {
            g.drawString(font, state.networkName(), x + 10, y + 31, ACCENT, false);
            g.drawString(font, "ID  " + state.networkId().toString().substring(0, 8),
                    x + 10, y + 44, HINT, false);
            if (state.owner() && !state.joinCode().isBlank()) {
                g.drawString(font, Component.translatable(
                                "gui.distantstock.distant_network.code_value", state.joinCode()),
                        x + 10, y + 59, INK, false);
            } else {
                g.drawString(font, Component.translatable(
                                "gui.distantstock.distant_network.code_owner_only"),
                        x + 10, y + 59, HINT, false);
            }
            if (!state.localWarehouse()) {
                g.drawString(font, Component.translatable(
                                "gui.distantstock.distant_network.remote_readonly"),
                        x + 10, y + 78, HINT, false);
            }
        } else if (state.localWarehouse()) {
            g.drawString(font, Component.translatable(
                            "gui.distantstock.distant_network.unjoined"),
                    x + 10, y + 31, HINT, false);
            g.drawString(font, Component.translatable(
                            "gui.distantstock.distant_network.join_help"),
                    x + 10, y + 45, HINT, false);
            field(g, nameInput, Component.translatable(
                    "gui.distantstock.distant_network.name_hint").getString(), mouseX, mouseY);
            field(g, codeInput, "1F2A-5B7G", mouseX, mouseY);
        } else {
            g.drawString(font, Component.translatable(
                            "gui.distantstock.distant_network.no_local"),
                    x + 10, y + 31, HINT, false);
            g.drawString(font, Component.translatable(
                            "gui.distantstock.distant_network.no_local_help"),
                    x + 10, y + 45, HINT, false);
        }
    }

    private void field(GuiGraphics g, EditBox box, String hint, int mouseX, int mouseY) {
        int bx = box.getX();
        int by = box.getY();
        g.fill(bx - 3, by - 2, bx + box.getWidth() + 3, by + box.getHeight() + 2,
                box.isFocused() ? 0x665A7A82 : 0x332E444B);
        if (box.getValue().isBlank() && !box.isFocused()) {
            g.drawString(font, hint, bx, by + 1, HINT, false);
        }
    }

    private void send(int action, String value) {
        PacketDistributor.sendToServer(new DistantNetworkActionC2S(action, value == null ? "" : value));
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
