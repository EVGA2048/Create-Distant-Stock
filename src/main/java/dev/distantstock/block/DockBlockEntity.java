package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.PackageCodec;
import dev.distantstock.link.ReturnRoute;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;

public final class DockBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    public static final int SLOTS = 9;
    final ItemStackHandler inv = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            updateVisual();
        }
    };

    private UUID freq;
    private String address = "";
    private boolean linkUp;
    private int backlogOrders;
    private int inFlight;

    public DockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DOCK.get(), pos, state);
    }

    public UUID freq() {
        return freq;
    }

    public String address() {
        return address;
    }

    public boolean isExport() {
        return freq != null;
    }

    public boolean isImport() {
        return freq == null;
    }

    public boolean isFull() {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int usedSlots() {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public void setExport(UUID freq) {
        this.freq = freq;
        sync();
    }

    public void setImport(String address) {
        this.freq = null;
        this.address = address == null ? "" : address;
        sync();
    }

    public void rejected() {
        sync();
    }

    public boolean insert(ItemStack pkg) {
        ItemStack left = pkg.copy();
        for (int i = 0; i < inv.getSlots() && !left.isEmpty(); i++) {
            left = inv.insertItem(i, left, false);
        }
        if (left.getCount() < pkg.getCount()) {
            sync();
        }
        return left.isEmpty();
    }

    public Component modeMessage() {
        if (isExport()) {
            return Component.translatable("goggle.distantstock.mode.export")
                    .append(Component.literal(" " + RequesterData.shortFreq(freq)).withStyle(ChatFormatting.AQUA));
        }
        String addr = address.isBlank() ? "*" : address;
        return Component.translatable("goggle.distantstock.mode.import")
                .append(Component.literal(" " + addr).withStyle(ChatFormatting.WHITE));
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity be) {
        if (level.getGameTime() % 10 != 0) {
            return;
        }
        be.linkUp = LinkSnapshot.peerUp || !dev.distantstock.config.StockConfig.hasPeer();
        be.backlogOrders = LinkSnapshot.orderDepth;
        be.inFlight = LinkSnapshot.inFlight;
        if (be.isExport()) {
            be.pullAdjacent(level, pos);
            be.ship(level);
        }
        be.updateVisual();
        be.setChanged();
    }

    private void pullAdjacent(Level level, BlockPos pos) {
        if (isFull()) {
            return;
        }
        for (Direction d : Direction.values()) {
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos.relative(d), d.getOpposite());
            if (handler == null) {
                continue;
            }
            for (int i = 0; i < handler.getSlots(); i++) {
                if (!PackageItem.isPackage(handler.getStackInSlot(i))) {
                    continue;
                }
                ItemStack take = handler.extractItem(i, 1, false);
                if (take.isEmpty()) {
                    continue;
                }
                if (!insert(take)) {
                    handler.insertItem(i, take, false);
                    continue;
                }
                if (isFull()) {
                    return;
                }
            }
        }
    }

    private void ship(Level level) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!PackageItem.isPackage(stack)) {
                continue;
            }
            String nbt = PackageCodec.encode(stack, level.registryAccess());
            if (nbt.isEmpty()) {
                continue;
            }
            String dest = PackageItem.getAddress(stack);
            if (LinkQueues.offerOutboundPackage(new LinkQueues.Parcel(nbt, dest, ReturnRoute.peek(dest)))) {
                inv.extractItem(i, 1, false);
                dev.distantstock.link.LinkClient.wake();
            }
            break;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.dock");
        if (isExport()) {
            GoggleText.line(tip, "goggle.distantstock.mode.export");
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
            GoggleText.line(tip, "goggle.distantstock.backlog", backlogOrders, inFlight);
        } else {
            GoggleText.line(tip, "goggle.distantstock.mode.import");
            GoggleText.line(tip, "goggle.distantstock.address", address.isBlank() ? "*" : address);
            if (isFull()) {
                GoggleText.line(tip, "goggle.distantstock.slots.full");
            } else {
                GoggleText.line(tip, "goggle.distantstock.slots", usedSlots(), SLOTS);
            }
        }
        if (linkUp) {
            GoggleText.value(tip, "goggle.distantstock.link.up", ChatFormatting.GREEN);
        } else {
            GoggleText.value(tip, "goggle.distantstock.link.down", ChatFormatting.RED);
        }
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedDocks.add(this);
    }

    @Override
    public void setRemoved() {
        LoadedDocks.remove(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.saveAdditional(tag, regs);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        tag.putString("Address", address);
        tag.put("Inv", inv.serializeNBT(regs));
        tag.putBoolean("LinkUp", linkUp);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.loadAdditional(tag, regs);
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        address = tag.getString("Address");
        if (tag.contains("Inv")) {
            inv.deserializeNBT(regs, tag.getCompound("Inv"));
        }
        linkUp = tag.getBoolean("LinkUp");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        return saveWithoutMetadata(regs);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void updateVisual() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        BlockState next = state;
        if (state.hasProperty(DockBlock.LOADED)) {
            next = next.setValue(DockBlock.LOADED, usedSlots() > 0);
        }
        if (state.hasProperty(DockBlock.LIT)) {
            next = next.setValue(DockBlock.LIT, linkUp);
        }
        if (next != state) {
            level.setBlock(worldPosition, next, 3);
        }
    }

    private void sync() {
        setChanged();
        updateVisual();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
