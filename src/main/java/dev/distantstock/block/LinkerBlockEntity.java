package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.PackageCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;

public final class LinkerBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    public static final int SLOTS = 9;
    final ItemStackHandler inv = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private UUID freq;
    private String address = "";
    private long lastReceiveMs;
    private int rejects;
    private boolean linkUp;
    private int backlogOrders;
    private int inFlight;
    private int lastRtt;
    private LinkQueues.PackResult lastPack;
    private long lastFailMs;

    public LinkerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LINKER.get(), pos, state);
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
        rejects++;
        sync();
    }

    public boolean insert(ItemStack pkg) {
        ItemStack left = pkg.copy();
        for (int i = 0; i < inv.getSlots() && !left.isEmpty(); i++) {
            left = inv.insertItem(i, left, false);
        }
        if (left.getCount() < pkg.getCount()) {
            lastReceiveMs = System.currentTimeMillis();
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, LinkerBlockEntity be) {
        if (level.getGameTime() % 10 != 0) {
            return;
        }
        be.linkUp = LinkSnapshot.peerUp || !dev.distantstock.config.StockConfig.hasPeer();
        be.backlogOrders = LinkSnapshot.orderDepth;
        be.inFlight = LinkSnapshot.inFlight;
        be.lastRtt = (int) LinkSnapshot.peerRttMs;
        be.lastPack = LinkQueues.lastPack(be.freq);
        if (!be.linkUp) {
            be.lastFailMs = System.currentTimeMillis();
        }
        if (be.isExport()) {
            be.ship(level);
        }
        be.setChanged();
        level.sendBlockUpdated(pos, state, state, 3);
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
            if (LinkQueues.offerOutboundPackage(new LinkQueues.Parcel(nbt, dest))) {
                inv.extractItem(i, 1, false);
                dev.distantstock.link.LinkClient.wake();
            }
            break;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.linker");
        if (isExport()) {
            GoggleText.line(tip, "goggle.distantstock.mode.export");
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
            linkLine(tip);
            GoggleText.line(tip, "goggle.distantstock.backlog", backlogOrders, inFlight);
            if (sneaking) {
                if (lastPack == LinkQueues.PackResult.SUCCESS) {
                    GoggleText.value(tip, "goggle.distantstock.last.pack.ok", ChatFormatting.GREEN);
                } else if (lastPack == LinkQueues.PackResult.NO_STOCK) {
                    GoggleText.value(tip, "goggle.distantstock.last.pack.stock", ChatFormatting.GOLD);
                } else {
                    GoggleText.value(tip, "goggle.distantstock.last.pack.unloaded", ChatFormatting.YELLOW);
                }
                if (linkUp && lastRtt >= 0) {
                    GoggleText.line(tip, "goggle.distantstock.rtt", lastRtt);
                } else {
                    GoggleText.value(tip, "goggle.distantstock.last.fail", ChatFormatting.RED);
                }
            }
        } else {
            GoggleText.line(tip, "goggle.distantstock.mode.import");
            GoggleText.line(tip, "goggle.distantstock.address", address.isBlank() ? "*" : address);
            if (isFull()) {
                GoggleText.value(tip, "goggle.distantstock.slots.full", ChatFormatting.GOLD);
            } else {
                GoggleText.line(tip, "goggle.distantstock.slots", usedSlots(), SLOTS);
            }
            linkLine(tip);
            if (sneaking) {
                GoggleText.line(tip, "goggle.distantstock.last.recv", ago(lastReceiveMs));
                GoggleText.line(tip, "goggle.distantstock.reject", rejects);
            }
        }
        return true;
    }

    private void linkLine(List<Component> tip) {
        if (linkUp) {
            GoggleText.value(tip, "goggle.distantstock.link.up", ChatFormatting.GREEN);
        } else {
            GoggleText.value(tip, "goggle.distantstock.link.down", ChatFormatting.RED);
        }
    }

    private static String ago(long ms) {
        if (ms <= 0) {
            return "—";
        }
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) {
            return s + "s";
        }
        return (s / 60) + "m";
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedLinkers.add(this);
    }

    @Override
    public void setRemoved() {
        LoadedLinkers.remove(this);
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
        tag.putLong("LastRecv", lastReceiveMs);
        tag.putInt("Rejects", rejects);
        tag.putBoolean("LinkUp", linkUp);
        tag.putInt("Backlog", backlogOrders);
        tag.putInt("InFlight", inFlight);
        tag.putInt("Rtt", lastRtt);
        if (lastPack != null) {
            tag.putString("LastPack", lastPack.name());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.loadAdditional(tag, regs);
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        address = tag.getString("Address");
        if (tag.contains("Inv")) {
            inv.deserializeNBT(regs, tag.getCompound("Inv"));
        }
        lastReceiveMs = tag.getLong("LastRecv");
        rejects = tag.getInt("Rejects");
        linkUp = tag.getBoolean("LinkUp");
        backlogOrders = tag.getInt("Backlog");
        inFlight = tag.getInt("InFlight");
        lastRtt = tag.getInt("Rtt");
        if (tag.contains("LastPack")) {
            try {
                lastPack = LinkQueues.PackResult.valueOf(tag.getString("LastPack"));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        return saveWithoutMetadata(regs);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
