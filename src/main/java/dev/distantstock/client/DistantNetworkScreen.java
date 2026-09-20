package dev.distantstock.client;

import dev.distantstock.net.DistantNetworkActionC2S;
import dev.distantstock.net.DistantNetworkStateS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small Create-style management page for the Distant Stock network carried by this terminal. */
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
    private EditBox warehouseNameInput;
    private Button createButton;
    private Button joinButton;
    private Button renameWarehouseButton;
    private Button resetButton;
    private Button leaveButton;
    private Button copyButton;
    /** Drafts survive init()/resize/state refreshes; widgets are only views of these values. */
    private String nameDraft = "";
    private String codeDraft = "";
    private String warehouseNameDraft = "";

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
        if (warehouseNameInput != null && !warehouseNameInput.isFocused()) {
            String current = selectedLocalWarehouseName();
            if (!current.isBlank()) {
                warehouseNameDraft = current;
                warehouseNameInput.setValue(current);
            }
        }
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
        nameInput.setValue(nameDraft);
        nameInput.setResponder(value -> {
            nameDraft = value == null ? "" : value;
            updateWidgets();
        });
        addRenderableWidget(nameInput);

        codeInput = new EditBox(font, x + 10, y + 97, 104, 12,
                Component.translatable("gui.distantstock.distant_network.code"));
        codeInput.setMaxLength(9);
        codeInput.setBordered(false);
        codeInput.setTextColor(INK);
        codeInput.setValue(codeDraft);
        codeInput.setResponder(value -> {
            codeDraft = value == null ? "" : value;
            updateWidgets();
        });
        addRenderableWidget(codeInput);

        String currentWarehouseName = selectedLocalWarehouseName();
        if (warehouseNameDraft.isBlank() && !currentWarehouseName.isBlank()) {
            warehouseNameDraft = currentWarehouseName;
        }
        warehouseNameInput = new EditBox(font, x + 10, y + 72, 128, 12,
                Component.translatable("gui.distantstock.distant_network.warehouse_name"));
        warehouseNameInput.setMaxLength(dev.distantstock.routing.DistantNetworkDirectory.MAX_NAME_LENGTH);
        warehouseNameInput.setBordered(false);
        warehouseNameInput.setTextColor(INK);
        warehouseNameInput.setValue(warehouseNameDraft);
        warehouseNameInput.setResponder(value -> {
            warehouseNameDraft = value == null ? "" : value;
            updateWidgets();
        });
        addRenderableWidget(warehouseNameInput);

        createButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.create"),
                        b -> send(DistantNetworkActionC2S.CREATE, nameDraft.trim()))
                .bounds(x + 145, y + 66, 62, 18).build());
        joinButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.join"),
                        b -> {
                            String normalized = normalizedCodeDraft();
                            if (normalized != null) send(DistantNetworkActionC2S.JOIN, normalized);
                        })
                .bounds(x + 121, y + 93, 86, 18).build());
        renameWarehouseButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.distantstock.distant_network.rename_warehouse"),
                        b -> send(DistantNetworkActionC2S.RENAME_WAREHOUSE,
                                warehouseNameDraft.trim()))
                .bounds(x + 145, y + 68, 62, 18).build());
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

    private dev.distantstock.stock.NetworkDirectory.Entry selectedLocalWarehouse() {
        if (parent == null || minecraft == null || minecraft.player == null || !state.joined()) {
            return null;
        }
        java.util.UUID freq = parent.getMenu().freq(minecraft.player);
        if (freq == null) return null;
        return parent.getMenu().networks.stream()
                .filter(entry -> entry.local() && freq.equals(entry.freq()))
                .filter(entry -> state.networkId().equals(entry.distantNetworkId()))
                .findFirst().orElse(null);
    }

    private String selectedLocalWarehouseName() {
        var entry = selectedLocalWarehouse();
        return entry == null ? "" : entry.warehouseName();
    }

    private void updateWidgets() {
        if (nameInput == null) return;
        boolean joined = state.joined();
        boolean portable = parent == null || !parent.getMenu().isGauge();
        boolean canJoinHere = portable && !joined;
        boolean canRenameWarehouse = joined && selectedLocalWarehouse() != null;
        nameInput.visible = canJoinHere;
        codeInput.visible = canJoinHere;
        warehouseNameInput.visible = canRenameWarehouse;
        createButton.visible = canJoinHere;
        joinButton.visible = canJoinHere;
        renameWarehouseButton.visible = canRenameWarehouse;
        createButton.active = canJoinHere && !nameDraft.trim().isEmpty();
        joinButton.active = canJoinHere && normalizedCodeDraft() != null;
        renameWarehouseButton.active = canRenameWarehouse && !warehouseNameDraft.trim().isEmpty();
        copyButton.visible = joined && state.owner() && !state.joinCode().isBlank();
        resetButton.visible = joined && state.owner();
        leaveButton.visible = joined && portable;
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
            if (selectedLocalWarehouse() != null) {
                field(g, warehouseNameInput, Component.translatable(
                        "gui.distantstock.distant_network.warehouse_name_hint").getString(), mouseX, mouseY);
            }
        } else if (parent == null || !parent.getMenu().isGauge()) {
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
                            "message.distantstock.network.portable_required"),
                    x + 10, y + 31, HINT, false);
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

    private String normalizedCodeDraft() {
        try {
            return dev.distantstock.routing.DistantNetworkDirectory.normalizeCode(codeDraft);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null && parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
