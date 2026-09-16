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
    private static final int ROW_H = 18;
    private static final int BUTTON_W = 44;
    /** Space between Create's window and our first field. */
    private static final int GAP = 6;
    /** Two rows and the space between them: what the panel is lifted by, and what our fields take. */
    private static final int FOOTER_H = ROW_H * 2 + 2;
    /** How blue the window is washed. Low enough to read through, high enough to notice. */
    private static final int TINT = 0x3040A0FF;
    /** The plate the two fields sit on, outside Create's window. */
    private static final int FOOTER_PLATE = 0xB0151D2A;

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
        // The panel is lifted by half the footer's height so that the two fields below it land where
        // Create's window used to end. This has to happen *before* super.init(): catnip computes
        // guiLeft/guiTop there, and only the offset may be set in advance.
        //
        // The first version of this screen placed its fields from the *screen's* size instead,
        // believing catnip's window rectangle was out of reach. It is not — guiLeft, guiTop,
        // windowWidth and windowHeight are protected fields of AbstractSimiScreen, and
        // FactoryPanelScreen reads them itself — so the boxes landed on top of Create's own address
        // box and its confirm button, which is what the player saw as scrambled labels.
        setWindowOffset(0, -(FOOTER_H + GAP) / 2);
        super.init();

        int left = guiLeft;
        int top = guiTop + windowHeight + GAP;
        int wide = windowWidth - BUTTON_W - 4;

        destination = new EditBox(font, left, top, wide, ROW_H,
                Component.translatable("gui.distantstock.remote_gauge.destination"));
        destination.setHint(Component.translatable("gui.distantstock.remote_gauge.destination.hint"));
        destination.setMaxLength(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        // Left empty rather than prefilled: the destination is a group *id* wherever it is stored,
        // and this side has no name table to turn one back into a name. An empty box with a hint
        // that says "leave it alone to keep what is there" is honest; a wrong name is not.
        destination.setValue("");
        addRenderableWidget(destination);

        address = new EditBox(font, left, top + ROW_H + 2, wide, ROW_H,
                Component.translatable("gui.distantstock.remote_gauge.address"));
        address.setHint(Component.translatable("gui.distantstock.remote_gauge.address.hint"));
        address.setMaxLength(64);
        address.setValue(currentAddress(behaviour));
        addRenderableWidget(address);

        addRenderableWidget(Button.builder(Component.translatable("gui.distantstock.confirm"),
                        button -> send())
                .bounds(left + windowWidth - BUTTON_W, top + ROW_H + 2, BUTTON_W, ROW_H)
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
        // A wash over Create's window, drawn after it. Everything in it stays readable; the point is
        // only that this panel is visibly not a plain factory gauge. The wash stops at the window's
        // edge now that the screen is taller than the window: tinting the whole screen would dim
        // the two fields that are the reason this screen exists.
        graphics.fill(guiLeft, guiTop, guiLeft + windowWidth, guiTop + windowHeight, TINT);
        // And a plate under them, which sit outside the window and would otherwise float over
        // whatever the player has built.
        int top = guiTop + windowHeight + GAP;
        graphics.fill(guiLeft - 4, top - 4, guiLeft + windowWidth + 4, top + FOOTER_H + 4,
                FOOTER_PLATE);
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
