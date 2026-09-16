package dev.distantstock.client;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import dev.distantstock.net.BindGaugePanelC2S;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create's factory gauge screen, with the cross-server half added underneath it.
 *
 * <p>Everything Create draws — the filter, the amount, the connections, the crafting arrangement —
 * is left exactly as it is, because none of it is ours to change and all of it is what a player
 * already knows. What is added is the two things that had no face anywhere: <b>where this panel
 * orders from</b> and <b>what address its goods carry</b>. Before this they existed only as a
 * gesture with a tuned terminal and a line on the goggles, which is why a remote gauge read as an
 * ordinary factory gauge that happened to do nothing.
 *
 * <p>The window is washed sky-blue on the way out. The panel underneath is Create's, deliberately —
 * a player who has configured a factory gauge already knows where everything is — and the tint is
 * the one signal that says this one is not quite that.
 *
 * <p>The warehouse is not editable here on purpose. It is a Create logistics network on some node,
 * and the only way to name one is to stand in front of it with a tuned terminal; a text field would
 * be asking the player to type a UUID. The screen says so when there is none.
 */
public final class RemoteGaugeScreen extends FactoryPanelScreen {
    private static final int FIELD_W = 120;
    private static final int ROW_H = 18;
    /** How blue the window is washed. Low enough to read through, high enough to notice. */
    private static final int TINT = 0x3040A0FF;

    private final FactoryPanelBehaviour behaviour;
    private EditBox destination;
    private EditBox address;

    private RemoteGaugeScreen(FactoryPanelBehaviour behaviour) {
        super(behaviour);
        this.behaviour = behaviour;
    }

    /** Opens this screen for a panel, on the client. Called from the behaviour's display hook. */
    public static void open(FactoryPanelBehaviour behaviour) {
        // A server has no Screen; this class is only ever loaded on the client, and only from the
        // behaviour's displayScreen, which Create itself guards the same way.
        if (Minecraft.getInstance().player == null) {
            return;
        }
        ScreenOpener.open(new RemoteGaugeScreen(behaviour));
    }

    @Override
    protected void init() {
        super.init();
        // Placed from the screen's own size rather than from Create's window rectangle: catnip's
        // window fields are not reachable from here, and a field that is visible and centred beats
        // one that is positioned exactly and does not compile. Where it lands relative to Create's
        // window is the one thing about this screen that needs a pair of eyes.
        int left = (width - FIELD_W) / 2;
        int top = height - 74;

        destination = new EditBox(font, left, top, FIELD_W, ROW_H,
                Component.translatable("gui.distantstock.remote_gauge.destination"));
        destination.setHint(Component.translatable("gui.distantstock.remote_gauge.destination.hint"));
        destination.setMaxLength(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        // Left empty rather than prefilled: the destination is a group *id* wherever it is stored,
        // and this side has no name table to turn one back into a name. An empty box with a hint
        // that says "leave it alone to keep what is there" is honest; a wrong name is not.
        destination.setValue("");
        addRenderableWidget(destination);

        address = new EditBox(font, left, top + ROW_H + 4, FIELD_W - 42, ROW_H,
                Component.translatable("gui.distantstock.remote_gauge.address"));
        address.setHint(Component.translatable("gui.distantstock.remote_gauge.address.hint"));
        address.setMaxLength(64);
        address.setValue(currentAddress(behaviour));
        addRenderableWidget(address);

        addRenderableWidget(Button.builder(Component.translatable("gui.distantstock.confirm"),
                        button -> send())
                .bounds(left + FIELD_W - 38, top + ROW_H + 4, 38, ROW_H)
                .build());
    }

    private void send() {
        FactoryPanelBlock.PanelSlot slot = behaviour.slot;
        PacketDistributor.sendToServer(new BindGaugePanelC2S(
                behaviour.blockEntity.getBlockPos(), slot.ordinal(),
                destination.getValue(), address.getValue()));
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderWindow(graphics, mouseX, mouseY, partialTicks);
        // A wash over the whole screen, drawn after Create's window. Everything stays readable; the
        // point is only that this panel is visibly not a plain factory gauge.
        graphics.fill(0, 0, width, height, TINT);
    }

    /**
     * The address this panel's goods carry today, or an empty string.
     *
     * <p>Only the address can be shown: it is a string the binding carries, and the destination is a
     * group id this side has no name table for.
     */
    private static String currentAddress(FactoryPanelBehaviour behaviour) {
        if (behaviour.blockEntity instanceof dev.distantstock.block.RemoteGaugeBlockEntity gauge) {
            var binding = gauge.binding(behaviour.slot);
            return binding == null ? "" : binding.address();
        }
        if (behaviour.blockEntity instanceof dev.distantstock.block.SignalPanelBlockEntity signal) {
            var binding = signal.binding(behaviour.slot);
            return binding == null ? "" : binding.address();
        }
        if (!(behaviour.blockEntity instanceof FactoryPanelBlockEntity board)) {
            return "";
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                ? dev.distantstock.panel.DeployerPanels.addressOf(board, behaviour.slot)
                : "";
    }
}
