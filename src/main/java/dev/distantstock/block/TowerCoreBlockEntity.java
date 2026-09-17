package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.fluid.ModFluids;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerBilling;
import dev.distantstock.routing.TowerChunkLoader;
import dev.distantstock.routing.TowerSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.List;

/**
 * The tower's brain: how tall the mast is, what that buys, and what it costs to turn.
 *
 * <p>The tier is not stored, it is read. Every twenty ticks this walks the mast above it and asks
 * {@link TowerTier} what that height is worth. Storing it instead would mean a hundred ways for the
 * number to be wrong — a coupler broken while the block entity is unloaded, a world edited by hand,
 * a crash between the mast changing and the write — and the walk is at most eighteen block lookups
 * four times a second.
 *
 * <p>What is stored is only what was observed last, so a restart does not briefly bill the network
 * for a tier the tower does not have.
 */
public final class TowerCoreBlockEntity extends KineticBlockEntity implements IHaveGoggleInformation {
    /** Four times a second, the same beat the docks use. */
    private static final int RESCAN_TICKS = 20;

    /**
     * How much ether the base holds: four buckets, sixteen parcels.
     *
     * <p>Flat across the tiers on purpose — the tower's size buys reach, devices and stress draw,
     * and the tank is the part that has to be piped to. Sixteen parcels is enough that an operator
     * is not standing over the pipe while a line runs, and small enough that a tower nobody is
     * feeding stops instead of quietly hoarding a chest's worth of ether. A larger tank would only
     * move the number where the mechanic becomes invisible.
     */
    public static final int ETHER_CAPACITY = 4000;

    private int couplers;
    private TowerTier tier;
    /**
     * 服务端是不是在计费，随方块实体同步过来。
     *
     * <p>看下面的 {@code write}：这是一个"谁在算账谁说了算"的读数，而不是客户端自己那份配置。
     */
    private boolean charging;

    private final FluidTank tank = new FluidTank(ETHER_CAPACITY,
            stack -> stack.getFluid() == ModFluids.ETHER.get());

    public TowerCoreBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.TOWER_CORE.get(), pos, state);
    }

    public TowerCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /** The base's tank, for the capability that lets pipes fill it. */
    public IFluidHandler tank() {
        return tank;
    }

    public int ether() {
        return tank.getFluidAmount();
    }

    /**
     * Takes ether out for one parcel. Returns what actually left, which is less than asked for when
     * the tower is nearly empty.
     *
     * <p>Not clamped to the request: the caller has to see the shortfall to know the parcel must
     * stay where it is, and a tower that pays part of a parcel would be paying for a transfer it
     * cannot complete.
     */
    public int drawEther(int amount) {
        if (amount <= 0) {
            return 0;
        }
        FluidStack drained = tank.drain(amount, IFluidHandler.FluidAction.EXECUTE);
        if (!drained.isEmpty()) {
            setChanged();
        }
        return drained.getAmount();
    }

    /** Puts ether back, for a charge whose parcel then turned out not to leave. */
    public void storeEther(int amount) {
        if (amount <= 0) {
            return;
        }
        int stored = tank.fill(new FluidStack(ModFluids.ETHER.get(), amount), IFluidHandler.FluidAction.EXECUTE);
        if (stored > 0) {
            setChanged();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % RESCAN_TICKS != 0) {
            return;
        }
        rescan();
        billStandby();
    }

    /**
     * The tower's running cost, once a second, while it carries something.
     *
     * <p>Off unless {@code tower.standbyCost} is set, and it charges nothing for a tower that is not
     * a tower or carries nothing: a bare base standing in a field is not a machine that is running,
     * and a mast with no devices attached has nothing to keep alive.
     *
     * <p>An empty tank does not stop the tower. This is what running costs, not what it takes to
     * run: the charge with teeth is the per-parcel one, which refuses to send goods the tower cannot
     * pay for. A standby charge that switched the machine off would make an unattended tower go dark
     * with no one watching, which is a worse failure than a bill the operator can see.
     */
    private void billStandby() {
        int cost = dev.distantstock.config.StockConfig.towerStandbyCost();
        if (cost <= 0 || !isRunning()) {
            return;
        }
        TowerSystem.TowerId id = id();
        TowerActivation.Usage usage = TowerActivation.usage(id);
        if (usage == null || usage.carried() <= 0) {
            return;
        }
        drawEther(cost);
    }

    /** Re-read the mast above and react if the answer moved. */
    private void rescan() {
        TowerStructure.Mast found = TowerStructure.mast(level, worldPosition).orElse(null);
        int height = found == null ? 0 : found.couplers();
        TowerTier reached = found == null ? null : found.tier();
        if (height == couplers && reached == tier) {
            return;
        }
        couplers = height;
        tier = reached;
        // The stress this block draws just changed, and the network only recomputes when told to.
        // Without this the tower reports its old draw until something else disturbs the network.
        networkDirty = true;
        // What the tower carries just changed too, and the snapshot that answers "is this device
        // on" is only rebuilt on its own beat. Waiting for that beat would mean up to a second of
        // docks believing they are carried by a mast that is no longer there.
        TowerActivation.markDirty();
        setChanged();
        notifyUpdate();
    }

    /**
     * What this tower draws, which is nothing at all until it is a tower.
     *
     * <p>Overridden rather than registered in {@code BlockStressValues}: that table is keyed by
     * block, and this draw depends on how tall the mast standing on the block is. A fixed entry
     * would have to pick one number for a bare base and a seven-storey tower both.
     */
    @Override
    public float calculateStressApplied() {
        float applied = tier == null ? 0.0f : tier.stress();
        lastStressApplied = applied;
        return applied;
    }

    /**
     * Whether the tower is built and turning.
     *
     * <p>{@code getSpeed()} is already zero when the network is overstressed or the game is frozen,
     * so this is the one question the rest of the mod should ask instead of looking at rotation
     * itself.
     */
    public boolean isRunning() {
        return tier != null && isSpeedRequirementFulfilled();
    }

    /** Couplers counted on the last rescan, for the readout and for the cap notice. */
    public int couplers() {
        return couplers;
    }

    /**
     * Whether the network this tower draws from is over its capacity.
     *
     * <p>Kept apart from {@link #isRunning()} on purpose: an overstressed tower and a tower with no
     * shaft under it both report a speed of zero, and a readout that showed them the same way would
     * send an operator looking for a missing shaft when the answer is "add another motor".
     */
    public boolean overstressed() {
        return isOverStressed();
    }

    /**
     * What this tower's network can supply, in Create's stress units; zero when it has no network.
     *
     * <p>Asked of the network rather than of this block because capacity is not the tower's — it
     * belongs to whatever is turning the shaft, and the useful comparison is the whole network's
     * supply against the whole network's draw. Read only on the readout's beat: the sum walks the
     * network's members.
     */
    public float networkCapacity() {
        if (!hasNetwork()) {
            return 0;
        }
        var network = getOrCreateNetwork();
        return network == null ? 0 : network.calculateCapacity();
    }

    public TowerTier tier() {
        return tier;
    }

    /** This tower's place in the systems, for the readout and for the ownership of tickets. */
    public TowerSystem.TowerId id() {
        return level == null ? null : TowerSystem.TowerId.of(level.dimension(), worldPosition);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.tower_core");
        if (tier == null) {
            GoggleText.line(tip, "goggle.distantstock.tower.unbuilt", couplers, TowerTier.I.couplers());
            return true;
        }
        GoggleText.line(tip, "goggle.distantstock.tower.tier", tier.name(), couplers);
        if (TowerTier.capped(couplers)) {
            GoggleText.line(tip, "goggle.distantstock.tower.capped");
        }
        GoggleText.line(tip, "goggle.distantstock.tower.radius", tier.radius());
        GoggleText.line(tip, "goggle.distantstock.tower.devices", tier.devices());
        // Said before the carrying count, because a tower that is not turning carries nothing and
        // "0 / 0 台设备" on its own reads as "your docks were not counted" rather than "this tower
        // is not working". Reported from play exactly that way, twice.
        if (getSpeed() == 0) {
            GoggleText.line(tip, "goggle.distantstock.tower.not_turning");
        } else if (!isRunning()) {
            GoggleText.line(tip, "goggle.distantstock.tower.too_slow", getSpeed());
        }
        TowerSystem.TowerId id = id();
        TowerActivation.Usage usage = id == null ? null : TowerActivation.usage(id);
        if (usage != null) {
            GoggleText.line(tip, "goggle.distantstock.tower.carrying", usage.carried(), usage.limit());
        } else if (isRunning()) {
            // Standing and turning, but in no system: the sum is the tower's own budget, since it
            // has no neighbour to share a system with.
            GoggleText.line(tip, "goggle.distantstock.tower.carrying", 0, tier.devices());
        } else {
            GoggleText.line(tip, "goggle.distantstock.tower.carrying.idle", tier.devices());
        }
        GoggleText.line(tip, "goggle.distantstock.tower.chunks", tier.chunkSide(), tier.chunkSide());
        // 储罐那样的写法：和 Create 的流体容器读数同一套 —— 表头是它自己的键，下面一行
        // 「流体名 数量 / 容量 mB」。以前这里写自创的「以太 4000 / 4000 mB（不计费）」，名字是
        // 简称、格式也自成一格；玩家在储罐上看惯的是前者，那就该是前者。
        //
        // 余量永远显示。上一版在计费关掉时把整行换成「以太：未启用」，于是关掉计费的存档永远
        // 看不到罐里还剩多少 —— 而玩家问的正是这个。计费是另一件事，另起一行低声说。
        FluidStack stored = tank.getFluid();
        GoggleText.line(tip, "create.gui.goggles.fluid_container");
        GoggleText.line(tip, "goggle.distantstock.tower.ether",
                stored.isEmpty() ? ModFluids.ETHER.get().getFluidType().getDescription()
                        : stored.getHoverName(),
                tank.getFluidAmount(), ETHER_CAPACITY);
        if (!charging) {
            GoggleText.line(tip, "goggle.distantstock.tower.ether.off");
        }
        // 罐满时不再单独喊一句「已满」：储罐的读数是 4000 / 4000 mB，谁都看得懂，而那一行金色
        // 的标语是这里唯一一处和储罐不像的地方。
        if (!isRunning()) {
            GoggleText.value(tip, "goggle.distantstock.tower.stalled", ChatFormatting.RED);
            // Why is the first question asked of a tower that will not turn, and the answer is
            // almost always that nothing is turning it: the base takes rotation on its underside
            // and nowhere else, so a shaft brought in from the side reaches nothing. Saying which
            // face it wants is cheaper than a player taking the skirt apart to find out.
            if (getSpeed() == 0) {
                GoggleText.line(tip, "goggle.distantstock.tower.no_shaft");
            }
        }
        addStressImpactStats(tip, getSpeed());
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedTowers.add(this);
        // A mast that grew while the chunk was unloaded is only visible now, and the snapshot has
        // been deciding without it in the meantime.
        TowerActivation.markDirty();
    }

    /**
     * The tower is still standing in the world — only its chunk went away.
     *
     * <p>{@code remove()} is deliberately not called from here. Create's own lifecycle already
     * separates the two cases, and this half must keep the tower's chunk tickets: they exist
     * precisely so the tower's own chunk comes back without a player walking past it.
     */
    @Override
    public void onChunkUnloaded() {
        LoadedTowers.remove(this);
        TowerActivation.markDirty();
        super.onChunkUnloaded();
    }

    /**
     * The block is gone for good: this is the one place the tower's chunks are released.
     *
     * <p>Create calls {@code remove()} from {@code setRemoved()} only when the chunk did not unload,
     * so a tower that merely left memory does not release anything.
     */
    @Override
    public void remove() {
        LoadedTowers.remove(this);
        if (level instanceof ServerLevel serverLevel) {
            TowerChunkLoader.forget(serverLevel, worldPosition);
            // The settings go with the tower. A base broken and placed again is a new machine with
            // new decisions to make, and keeping a radius somebody chose for a tower that no longer
            // exists would charge the new one for the old one's choices. A tower that is merely
            // unloaded never comes through here, so its settings survive a chunk going away.
            if (serverLevel.getServer() != null) {
                dev.distantstock.routing.TowerDirectory.get(serverLevel.getServer())
                        .clear(TowerSystem.TowerId.of(serverLevel.dimension(), worldPosition));
            }
        }
        TowerActivation.markDirty();
        super.remove();
    }

    @Override
    public void destroy() {
        LoadedTowers.remove(this);
        TowerActivation.markDirty();
        super.destroy();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Couplers", couplers);
        if (tier != null) {
            tag.putString("Tier", tier.name());
        }
        // 计费是**服务端**的事，读数却画在客户端。
        //
        // tower.chargeParcels 是 common 配置，两台机器各读各的文件，而 NeoForge 的配置文件只补新键、
        // 从不改已有的值 —— 于是老客户端那份一直留着当年的 false，护目镜一口咬定"不计费"，服务端却
        // 照扣不误，玩家看到的是一句话在撒谎。所以这个读数跟着方块实体同步：谁在算账，谁说了算。
        if (clientPacket) {
            tag.putBoolean("Charging", TowerBilling.enabled());
        }
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            charging = tag.getBoolean("Charging");
        }
        couplers = tag.getInt("Couplers");
        tier = null;
        if (tag.contains("Tier")) {
            try {
                tier = TowerTier.valueOf(tag.getString("Tier"));
            } catch (IllegalArgumentException unknown) {
                // A tier this build does not have: leave it null and let the next rescan settle it.
            }
        }
        if (tag.contains("Tank")) {
            tank.readFromNBT(registries, tag.getCompound("Tank"));
        }
    }
}
