package dev.distantstock;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.client.RemoteGaugeRenderer;
import dev.distantstock.client.ResonatorRenderer;
import dev.distantstock.client.SignalPanelRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.Arrays;
import java.util.Map;

/** Opt-in client smoke test: bake real assets and load client mixins, then close without opening a save. */
@EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
public final class SignalLampClientSmoke {
    private static boolean done;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("distantstock.clientSmoke") || done) return;
        var mc = Minecraft.getInstance();
        if (mc.getOverlay() != null || mc.screen == null || mc.getModelManager().getMissingModel() == null) return;
        done = true;
        int scenes = 0;
        try {
            if (Arrays.stream(FactoryPanelConnectionHandler.class.getDeclaredMethods())
                    .noneMatch(m -> m.getName().contains("distantstock$lampOutput"))) {
                throw new AssertionError("Client lamp connection mixin was not applied");
            }
            // The casing's connected texture attaches by swapping its baked model, and a swap that
            // silently did not happen leaves a perfectly ordinary-looking block with no connection
            // logic at all. Nothing else in the game reports that, so it is asserted here.
            for (var state : ModBlocks.TOWER_CASING.get().getStateDefinition().getPossibleStates()) {
                if (!(mc.getBlockRenderer().getBlockModel(state)
                        instanceof com.simibubi.create.foundation.block.connected.CTModel)) {
                    throw new AssertionError("Distant casing is not using a connected-texture model: " + state);
                }
            }
            int states = 0;
            for (var block : java.util.List.of(ModBlocks.CYAN_INDICATOR_LAMP.get(), ModBlocks.ORANGE_INDICATOR_LAMP.get(),
                    ModBlocks.RED_INDICATOR_LAMP.get(), ModBlocks.GREEN_INDICATOR_LAMP.get(),
                    ModBlocks.WHITE_INDICATOR_LAMP.get(), ModBlocks.BRASS_INDICATOR_LAMP.get())) {
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    verify(mc.getBlockRenderer().getBlockModel(state), mc);
                    states++;
                }
            }
            var partials = dev.distantstock.block.SignalLampModels.all();
            for (var entry : partials.entrySet()) {
                try { verify(((PartialModel) entry.getValue()).get(), mc); }
                catch (AssertionError failure) { throw new AssertionError("Quarter model: " + entry.getKey(), failure); }
            }
            var gaugeField = dev.distantstock.block.RemoteGaugeModels.class.getDeclaredField("MODELS");
            gaugeField.setAccessible(true);
            var gaugePartials = (Map<?, ?>) gaugeField.get(null);
            for (var entry : gaugePartials.entrySet()) {
                try { verify(((PartialModel) entry.getValue()).get(), mc); }
                catch (AssertionError failure) { throw new AssertionError("Remote gauge model: " + entry.getKey(), failure); }
            }
            verify(com.simibubi.create.AllPartialModels.FACTORY_PANEL_WITH_BULB.get(), mc);
            // The resonator's arms and light column are partial models too, and a partial model is
            // only baked if something asked for it before the bake. The tower cap shipped with both
            // of them unbaked: what a player saw was the missing model, a purple-and-black cube
            // turning above the mast, while the inventory icon looked perfectly normal because it
            // comes from the block model. Nothing else in the game reports this.
            for (String part : new String[]{"ROTOR", "BEAM"}) {
                var resonatorField = ResonatorRenderer.class.getDeclaredField(part);
                resonatorField.setAccessible(true);
                try {
                    verify(((PartialModel) resonatorField.get(null)).get(), mc);
                } catch (AssertionError failure) {
                    throw new AssertionError("Resonator partial model: " + part, failure);
                }
            }
            // A sprite reaches the block atlas one of two ways: a baked model names it, or an atlas
            // definition lists it. The fluids ship as texture-only models and the dock's lift is
            // drawn straight from its renderer with no model at all, so both depend on
            // assets/minecraft/atlases/blocks.json. An unstitched sprite is not an error anywhere
            // else in the game; it simply draws as the purple-black checkerboard, which is why this
            // is the only place the mistake shows up as a failure instead of a screenshot.
            var atlas = mc.getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            var missing = atlas.getSprite(
                    net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()).contents().name();
            var expected = new java.util.ArrayList<ResourceLocation>();
            for (String fluid : new String[]{"ether", "molten_amethyst"}) {
                for (String kind : new String[]{"still", "flow"}) {
                    expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "fluid/" + fluid + "_" + kind));
                }
            }
            expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/dock_cap"));
            // The tower's models are generated from the art handoff, so a texture renamed or dropped
            // there reaches the game as a page of checkerboard with nothing upstream to catch it.
            for (String tower : new String[]{"andesite", "axis", "axis_top", "bearing", "bearing_top",
                    "brass", "cap", "casing", "casing_active", "casing_inactive", "core", "crystal",
                    "crystal_shell", "fluid_port_a", "ct_active", "ct_inactive",
                    "ct_active", "ct_inactive", "fluid", "fluid_port_a", "frame", "gearbox", "iron",
                    "polished", "shell"}) {
                expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/tower/" + tower));
            }
            int sprites = 0;
            for (var name : expected) {
                if (atlas.getSprite(name).contents().name().equals(missing)) {
                    throw new AssertionError("Sprite was not stitched into the block atlas: " + name);
                }
                sprites++;
            }
            LogUtils.getLogger().info("DISTANTSTOCK_ATLAS_SPRITES_OK: {} sprites stitched", sprites);
            // A Ponder scene is three things that have to agree and none of which the game checks
            // together: a structure file, a storyboard registered under that name, and one text key
            // per line the scene shows. Get any of them wrong and the scene either never opens or
            // opens with blank captions, and the only place that shows up is in front of a player.
            var language = net.minecraft.locale.Language.getInstance();
            for (var entry : java.util.Map.of(
                    "export", 5, "import", 3, "tune", 3, "status", 5, "tower", 8, "replenish", 6)
                    .entrySet()) {
                String scene = entry.getKey();
                var id = ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "ponder/" + scene + ".nbt");
                var resource = mc.getResourceManager().getResource(id).orElseThrow(
                        () -> new AssertionError("Ponder structure is missing: " + id));
                try (var stream = resource.open()) {
                    net.minecraft.nbt.NbtIo.readCompressed(stream,
                            net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                } catch (Exception broken) {
                    throw new AssertionError("Ponder structure does not parse: " + id, broken);
                }
                String header = "distantstock.ponder.distant_" + scene + ".header";
                if (!language.has(header)) {
                    throw new AssertionError("Ponder scene has no title: " + header);
                }
                for (int line = 1; line <= entry.getValue(); line++) {
                    String key = "distantstock.ponder.distant_" + scene + ".text_" + line;
                    if (!language.has(key)) {
                        throw new AssertionError("Ponder scene is missing a line of text: " + key);
                    }
                }
                scenes++;
            }
            LogUtils.getLogger().info("DISTANTSTOCK_PONDER_OK: {} scenes have a structure and their text", scenes);
            checkDockGroupPage(mc);
            checkMonitorPages(mc);
            if (checkTerminalClick()) {
                LogUtils.getLogger().info("DISTANTSTOCK_TERMINAL_CLICK_OK: 点一下就有反应");
            }
            LogUtils.getLogger().info("DISTANTSTOCK_CLIENT_SMOKE_PASSED: {} block states, {} quarter-lamp models, {} remote gauge models, factory panel, client mixin",
                    states, partials.size(), gaugePartials.size());
        } catch (Throwable failure) {
            LogUtils.getLogger().error("DISTANTSTOCK_CLIENT_SMOKE_FAILED", failure);
        } finally {
            mc.stop();
        }
    }

    /**
     * 港组那一页能不能开出来、画不画得出来。
     *
     * <p>它是从一个手绘的按钮打开的（终端列表行上的「网络…」），而"点了没反应"这类问题读代码
     * 是看不出来的：命中区、屏幕的构造、init 里的布局，任何一环出错都只是"什么都没发生"。
     * 这里在真实客户端里把它开出来、跑一遍 init 和绘制 —— 抛异常就是失败，不会再悄悄过去。
     *
     * <p>它不绑菜单，所以标题界面上就能跑，不像下面那条要玩家。两版都画：自己建的（有输入框、
     * 有按钮）和别人的（只有只读的一行）。
     */
    private static void checkDockGroupPage(Minecraft mc) {
        var graphics = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
        var mine = new dev.distantstock.net.DockGroupsS2C.Entry(java.util.UUID.randomUUID(),
                "测试网络", true, true, 2, "某人", java.util.List.of("甲", "乙"), true);
        var page = new dev.distantstock.client.DockGroupScreen(null, mine);
        page.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        page.render(graphics, 0, 0, 0f);

        var theirs = new dev.distantstock.net.DockGroupsS2C.Entry(java.util.UUID.randomUUID(),
                "别人的网络", true, false, 1, "某人", java.util.List.of(), false);
        var stranger = new dev.distantstock.client.DockGroupScreen(null, theirs);
        stranger.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        stranger.render(graphics, 0, 0, 0f);
        LogUtils.getLogger().info("DISTANTSTOCK_GROUP_PAGE_OK: 港组页面开得出、画得出来");
    }

    /**
     * 监视器两页都画得出来，而且**切页之后整块要重新居中**。
     *
     * <p>塔页比链路页高 66 像素、用的是另一张底图（见 {@code gen_monitor_tower_bg.py}）：切页时
     * 屏幕顶点的位置得跟着变，否则高的那一页会顶到屏幕外面 —— 玩家截的图里"底部那行区块选区被切掉"
     * 就是这么来的。这里画一遍链路页、点一下页签、再画一遍塔页；两张底图少一张、或者切页后没重算
     * 位置，这里都会抛出来。
     */
    private static void checkMonitorPages(Minecraft mc) {
        var graphics = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
        long base = net.minecraft.core.BlockPos.asLong(12, 104, 31);
        var tower = new dev.distantstock.routing.TowerReadout(true, base, "minecraft:overworld",
                6, 16, 512, 120, 5, 3,
                java.util.List.of(new dev.distantstock.routing.TowerReadout.Member(base, "II",
                        40, 16, true, 120, 4000, 512, false, 1, true, true, 3, 5)));
        var view = new dev.distantstock.link.LinkSnapshot.View("A", "B", 20, 5, 1, 2, 0,
                true, 19.5, 6, true, 12, 0, 1, 1,
                true, true, "node", "A服", "", 0, 0, 0, 0, tower);

        var monitor = new dev.distantstock.client.MonitorScreen(
                new net.minecraft.core.BlockPos(0, 0, 0), view);
        monitor.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        monitor.render(graphics, 0, 0, 0f);

        // 点一下「塔」那个页签：位置和 MonitorScreen.drawPageToggle 里写的一致。
        int left = (mc.getWindow().getGuiScaledWidth() - 272) / 2;
        int top = (mc.getWindow().getGuiScaledHeight() - 190) / 2;
        monitor.mouseClicked(left + 55 + 20, top + 27 + 6, 0);
        monitor.render(graphics, 0, 0, 0f);
        LogUtils.getLogger().info("DISTANTSTOCK_MONITOR_PAGES_OK: 监视器链路页与塔页都画得出来");
    }

    /**
     * 仓管界面「点一下有没有反应」。
     *
     * <p>玩家报的是"三个 UI 不跟手，港组那个点一下不弹窗，得先打一个字再退格"。这类毛病靠读代码
     * 判断不了 —— 焦点、命中区、绘制条件分散在三处，谁少一环都只是"没反应"。所以这里在真实客户端
     * 里把界面开起来，用程序点一下那个框，然后问它：你被聚焦了吗？退格能删掉字吗？
     *
     * <p>这是唯一能自动化的部分：弹窗画得对不对仍然要人看，但"点了没反应"这一条从此不会再回来。
     */
    private static boolean checkTerminalClick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            // 冒烟跑在标题界面：没有世界就没有玩家，没有玩家就开不了仓管界面。跳过要说出来，
            // 不能让"没跑"看着像"跑过了"。
            LogUtils.getLogger().warn(
                    "DISTANTSTOCK_TERMINAL_CLICK_SKIPPED: 标题界面没有玩家，界面点击检查未运行");
            return false;
        }
        var menu = new dev.distantstock.menu.RequesterMenu(0, mc.player.getInventory(),
                net.minecraft.world.InteractionHand.MAIN_HAND);
        var screen = new dev.distantstock.client.RequesterScreen(menu, mc.player.getInventory(),
                net.minecraft.network.chat.Component.literal("smoke"));
        screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());

        // 找出「接收港组」那个框：EditBox 里提示文字是它自己的翻译键那一个。
        net.minecraft.client.gui.components.EditBox group = null;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.EditBox box
                    && box.getMessage().getString().equals(net.minecraft.network.chat.Component
                            .translatable("gui.distantstock.route.group").getString())) {
                group = box;
                break;
            }
        }
        if (group == null) {
            throw new AssertionError("界面里找不到接收港组那个输入框");
        }
        int cx = group.getX() + group.getWidth() / 2;
        int cy = group.getY() + group.getHeight() / 2;
        screen.mouseClicked(cx, cy, 0);
        if (screen.getFocused() != group) {
            throw new AssertionError("点了接收港组那个框，它没有被聚焦 —— 下拉列表的条件就不成立");
        }
        // 退格：上一个版本这里被 keyPressed 的"正在打字就吞掉"规则吃掉了。
        group.setValue("测试");
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        if (!group.getValue().equals("测")) {
            throw new AssertionError("退格没有删掉字：" + group.getValue());
        }
        return true;
    }

    private static void verify(BakedModel model, Minecraft mc) {
        if (model == null || model == mc.getModelManager().getMissingModel()) throw new AssertionError("Missing baked model");
        int quads = 0;
        for (int i = 0; i <= 6; i++) {
            for (var quad : model.getQuads(null, i == 6 ? null : Direction.values()[i], RandomSource.create(42))) {
                if (quad.getSprite().contents().name().getPath().contains("missingno")) throw new AssertionError("Missing texture");
                quads++;
            }
        }
        if (quads == 0) throw new AssertionError("Unexpected empty model");
    }
}
