package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.item.SignalLampPanelItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public final class SignalPanelBlockEntity extends FactoryPanelBlockEntity implements IHaveGoggleInformation {
    private int lampSignal;
    private boolean panelDataReady;

    public SignalPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIGNAL_PANEL.get(), pos, state);
        setLazyTickRate(2);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        panels = new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
        redraw = true;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour panel = new SignalLampAwarePanelBehaviour(this, slot);
            panels.put(slot, panel);
            behaviours.add(panel);
        }
        behaviours.add(advancements = new AdvancementBehaviour(this, AllAdvancements.FACTORY_GAUGE));
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null) {
            return;
        }
        int next = level.getBestNeighborSignal(worldPosition);
        if (next == lampSignal) {
            return;
        }
        lampSignal = next;
        if (!level.isClientSide) {
            sendData();
        }
    }

    public int lampSignal(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            return 0;
        }
        boolean connectedGaugeIsOn = level != null && behaviour.targetedBy.values().stream()
                .map(connection -> FactoryPanelBehaviour.at(level, connection))
                .anyMatch(source -> source != null && (source.satisfied || source.redstonePowered));
        return connectedGaugeIsOn ? 15 : 0;
    }

    public ItemStack lampStack(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            return ItemStack.EMPTY;
        }
        ItemStack filter = behaviour.getFilter();
        return SignalLampPanelItem.from(filter) == null ? ItemStack.EMPTY : filter;
    }

    public boolean isLamp(FactoryPanelBlock.PanelSlot slot) {
        return !lampStack(slot).isEmpty();
    }

    /**
     * Client-side placement creates the block entity before the server sends
     * the installed lamp stack. Until that packet arrives, Create's default
     * active panel must not be rendered as a factory gauge.
     */
    public boolean panelDataReady() {
        return level != null && (!level.isClientSide || panelDataReady);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        boolean found = false;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            ItemStack lamp = lampStack(slot);
            SignalLampPanelItem item = SignalLampPanelItem.from(lamp);
            if (item == null) {
                continue;
            }
            if (!found) {
                tip.add(Component.empty());
                tip.add(Component.translatable(onlyLamps() ? "goggle.distantstock.signal_lamp.title"
                        : "goggle.distantstock.signal_panel").withStyle(ChatFormatting.WHITE));
                found = true;
            }
            Component name = lamp.get(DataComponents.CUSTOM_NAME);
            tip.add(Component.literal("  ").append(name == null ? lamp.getHoverName() : name)
                    .withStyle(ChatFormatting.GRAY));
            int strength = lampSignal(slot);
            tip.add(Component.translatable("goggle.distantstock.signal_lamp.detail",
                            Component.translatable("goggle.distantstock.signal_lamp.material."
                                    + item.material().name().toLowerCase()),
                            Component.translatable("goggle.distantstock.signal_lamp.color."
                                    + item.color().name().toLowerCase()), strength)
                    .withStyle(strength > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        }
        return found;
    }

    /** True when every occupied slot holds a lamp; an empty panel is not a lamp panel. */
    private boolean onlyLamps() {
        boolean any = false;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            any = true;
            if (SignalLampPanelItem.from(behaviour.getFilter()) == null) {
                return false;
            }
        }
        return any;
    }

    @Override
    public void destroy() {
        if (level == null || level.isClientSide) {
            super.destroy();
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            ItemStack filter = behaviour.getFilter();
            ItemStack drop = SignalLampPanelItem.from(filter) != null
                    ? filter.copyWithCount(1)
                    : new ItemStack(BuiltInRegistries.BLOCK.get(
                            ResourceLocation.fromNamespaceAndPath("create", "factory_gauge")));
            drops.add(drop);
            behaviour.disable();
        }

        // FactoryPanelBlockEntity normally drops extra factory gauges. All slots are
        // disabled here so its drop pass stays empty; each real slot is returned above.
        super.destroy();
        for (ItemStack drop : drops) {
            Block.popResource(level, worldPosition, drop);
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("LampSignal", lampSignal);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        lampSignal = tag.getInt("LampSignal");
        panelDataReady = true;
    }

    private static final class SignalLampAwarePanelBehaviour extends FactoryPanelBehaviour {
        private final SignalPanelBlockEntity owner;

        private SignalLampAwarePanelBehaviour(SignalPanelBlockEntity be, FactoryPanelBlock.PanelSlot slot) {
            super(be, slot);
            owner = be;
        }

        private boolean isLampSlot() {
            return SignalLampPanelItem.from(getFilter()) != null;
        }

        private boolean isLampInputBlocked() {
            return !owner.panelDataReady() || isLampSlot();
        }

        /**
         * A lamp is not a factory gauge: holding right-click on it must not open Create's count setting,
         * and the block's own interaction (name tag, wrench) has to stay reachable.
         */
        @Override
        public boolean acceptsValueSettings() {
            return owner.panelDataReady() && !isLampSlot();
        }

        /** Create's renderer draws "hold to set the amount" over the slot; a lamp has no amount. */
        @Override
        public net.minecraft.network.chat.MutableComponent getAmountTip() {
            return isLampInputBlocked() ? net.minecraft.network.chat.Component.empty() : super.getAmountTip();
        }

        @Override
        public boolean bypassesInput(net.minecraft.world.item.ItemStack stack) {
            return isLampInputBlocked() || super.bypassesInput(stack);
        }

        @Override
        public void tick() {
            if (!isLampInputBlocked()) {
                super.tick();
            }
        }

        @Override
        public void lazyTick() {
            if (!isLampInputBlocked()) {
                super.lazyTick();
            }
        }
    }
}
