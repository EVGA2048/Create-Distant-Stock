package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The distant casing: the tower's 3x3 skirt and the decorative panel everywhere else.
 *
 * <p>Two textures, picked by a connected-texture behaviour on the client from the eight casings
 * around each face. A redstone signal turns the panel into a window, and the window spreads along
 * connected casings the way a signal spreads along wire — which is the part that lives here,
 * because it has to be in the block state: the texture is chosen while the chunk is baked, and
 * that code only ever sees a state, never a world.
 *
 * <p>{@link #POWERED} therefore means "this casing is connected, within range, to one that is
 * being driven directly". It is derived, never set by redstone itself — a casing can be lit with
 * nothing attached to it.
 */
public final class TowerCasingBlock extends Block
        implements com.simibubi.create.foundation.block.IBE<TowerCasingBlockEntity>,
        com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation {
    public static final MapCodec<TowerCasingBlock> CODEC = simpleCodec(TowerCasingBlock::new);
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    /**
     * Which face of this casing is a fluid port, if any.
     *
     * <p>A complete tower's base is walled in: the core's tank answers pipes on every face but the
     * bottom, and a finished skirt covers all four sides of it, so the tank is sealed inside a
     * machine that is built exactly as it is meant to be. The port is how ether gets in, and it is
     * one face rather than the whole block — a pipe arrives somewhere, and a casing with ports on
     * every side would show a socket to four faces that have nothing plugged into them.
     *
     * <p>One port per block, not one per face: two pipes into one casing is two pipes into a wall,
     * and the skirt has eight casings.
     */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Port> PORT =
            net.minecraft.world.level.block.state.properties.EnumProperty.create("port", Port.class);

    /** Which face carries the port. Up and down are left out: one is the coupler, one the ground. */
    public enum Port implements net.minecraft.util.StringRepresentable {
        NONE(null),
        NORTH(Direction.NORTH),
        EAST(Direction.EAST),
        SOUTH(Direction.SOUTH),
        WEST(Direction.WEST);

        private final Direction face;

        Port(Direction face) {
            this.face = face;
        }

        /** The face this value opens, or null for a casing that has no port. */
        public Direction face() {
            return face;
        }

        public static Port of(Direction face) {
            for (Port port : values()) {
                if (port.face == face) {
                    return port;
                }
            }
            return NONE;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * Ceiling on how many casings one search may look at.
     *
     * A radius on its own does not bound the work: a wall is mostly surface, so the number of
     * casings within N steps grows with N squared. This is the number that actually keeps a large
     * build from hitching when someone flips a lever, and hitting it simply reports unpowered.
     */
    private static final int SEARCH_LIMIT = 4096;

    public TowerCasingBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false).setValue(PORT, Port.NONE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Two casings never draw the face between them.
     *
     * <p>A window is not a hole. The inside of a tower's skirt is a room, and a player looking
     * through one pane was looking at the back of the pane beside it — the frame lines of a
     * neighbour's inner face, drawn because this block cannot occlude anything (see {@code ModBlocks}:
     * full occlusion culls the wall behind the window and the tower becomes a hole onto the far side
     * of the world). Hiding casing-against-casing faces keeps that room a room, and leaves the
     * tower's outline, the core and everything outside it alone.
     *
     * <p>Casings only: a casing's face is still drawn against air, against the core, and against
     * anything else a player builds beside it.
     */
    @Override
    public boolean hidesNeighborFace(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                     BlockState state, BlockState neighborState, Direction side) {
        return neighborState.is(this);
    }

    /**
     * Needed for the above to be consulted at all: NeoForge skips external face hiding for blocks
     * whose model sits in the solid layer, and an unlit casing is one of those. Both states answer
     * the same way on purpose — otherwise a wall would show its inner faces again the moment the
     * redstone went out.
     */
    @Override
    public boolean supportsExternalFaceHiding(BlockState state) {
        return true;
    }

    @Override
    public Class<TowerCasingBlockEntity> getBlockEntityClass() {
        return TowerCasingBlockEntity.class;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntityType<? extends TowerCasingBlockEntity>
            getBlockEntityType() {
        return ModBlockEntities.TOWER_CASING.get();
    }

    /**
     * The wrench opens and closes a fluid port.
     *
     * <p>Plain right-click, not sneak: sneak-wrench takes the block away, and a build gesture that
     * also removes things is one players learn to avoid. Nothing else about the casing is
     * configurable, so the wrench has this click to itself.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!DockBlock.isWrench(stack) || player.isShiftKeyDown()) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (!level.isClientSide) {
            // The clicked face, not the block: a pipe arrives at one side of one casing. Clicking
            // the face that is already open closes it; clicking another moves the port there, which
            // is one gesture instead of "close it first, then open it where you meant".
            Port wanted = hit.getDirection().getAxis().isHorizontal()
                    && state.getValue(PORT) != Port.of(hit.getDirection())
                    ? Port.of(hit.getDirection()) : Port.NONE;
            boolean open = wanted != Port.NONE;
            level.setBlock(pos, state.setValue(PORT, wanted), 3);
            level.playSound(null, pos, open ? net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_OPEN
                    : net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_CLOSE,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.2f);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    open ? "message.distantstock.casing.port.open"
                            : "message.distantstock.casing.port.closed"), true);
            if (open && coreFor(level, pos) == null) {
                // The other silent trap: a port only reaches the core of the tower this casing is
                // part of, and a casing standing on its own opens a socket onto nothing. The click
                // works and the block looks right, so the player finds out when the pipes will not
                // fill — say it here instead.
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.casing.port.no_tower"), false);
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * A casing in a tower's skirt reports the tower, not itself.
     *
     * <p>Nine blocks of skirt is eight blocks of wall between a player and the one block that knows
     * anything: the base answers questions about tier, reach and ether, and every square around it
     * is a casing that would otherwise say nothing at all. Create resolves this before it collects
     * the tooltip, so pointing at the base costs no block entity and no mixin — the overlay simply
     * asks the square the player is looking at where its information lives.
     *
     * <p>Deliberately not gated on the skirt being complete. A ring closed on three sides is a base
     * somebody is still building, and that is exactly when its numbers are worth reading; the only
     * thing required is that there is a base to read, which is what {@link #coreFor} looks for.
     */
    @Override
    public net.minecraft.core.BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        TowerCoreBlockEntity core = coreFor(level, pos);
        return core == null ? pos : core.getBlockPos();
    }

    /**
     * The tower this casing belongs to, or null when it is not part of one.
     *
     * <p>The core stands in the middle of the skirt at the same height, so the search is the eight
     * squares around it. Deliberately not a scan upwards: a casing is decorative nearly everywhere
     * it is placed, and the common case has to be the cheap one.
     */
    public static TowerCoreBlockEntity coreFor(Level level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos candidate = pos.offset(dx, 0, dz);
                if (level.getBlockState(candidate).is(ModBlocks.TOWER_CORE.get())
                        && level.getBlockEntity(candidate) instanceof TowerCoreBlockEntity core) {
                    return core;
                }
            }
        }
        return null;
    }

    /** The tank behind an open port, or null for a casing that is closed or in no tower. */
    public static net.neoforged.neoforge.fluids.capability.IFluidHandler portTank(
            Level level, BlockPos pos, BlockState state, Direction side) {
        if (level == null || side == null || state.getValue(PORT).face() != side) {
            return null;
        }
        TowerCoreBlockEntity core = coreFor(level, pos);
        // The core's own underside is where the shaft enters, and a port on that face would be a
        // pipe arriving at a driveshaft. Every other side of a port is fair game.
        return core == null || side == Direction.DOWN ? null : core.tank();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, PORT);
    }

    /**
     * Settle on the next tick rather than now.
     *
     * A redstone change reaches all six neighbours before any of them reacts, so deciding inline
     * would let a casing read a neighbour that has not been updated yet. One tick also collapses a
     * burst of changes — a lever, a piston, a whole bank of them — into a single pass per casing.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos neighbourPos, boolean isMoving) {
        if (!level.isClientSide && !level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean lit = drivenWithinRange(level, pos);
        if (lit == state.getValue(POWERED)) {
            return;
        }
        // setBlock, not just a state swap: the neighbours have to be told, or the change stops here
        // and the window never spreads past the casings the lever itself touches.
        level.setBlock(pos, state.setValue(POWERED, lit), Block.UPDATE_ALL);
    }

    /**
     * Whether any casing connected to this one is being driven directly by redstone, within range.
     *
     * <p>Note what is searched for: a casing with an actual signal, not a casing that is merely
     * {@link #POWERED}. The window is a light, and this is the bulb — a lit casing does not light
     * its neighbours any more than a lit lamp does, so the effect cannot walk away from the source
     * one casing at a time and outrun the range check.
     */
    private static boolean drivenWithinRange(Level level, BlockPos origin) {
        int range = StockConfig.casingRedstoneRange();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> frontier = new ArrayList<>();
        seen.add(origin);
        frontier.add(origin);

        for (int step = 0; step <= range && !frontier.isEmpty(); step++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos pos : frontier) {
                if (level.hasNeighborSignal(pos)) {
                    return true;
                }
                for (Direction direction : DIRECTIONS) {
                    BlockPos neighbour = pos.relative(direction);
                    if (seen.size() >= SEARCH_LIMIT) {
                        return false;
                    }
                    if (seen.add(neighbour)
                            && level.getBlockState(neighbour).getBlock() instanceof TowerCasingBlock) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }
}
