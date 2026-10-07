package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * Receive-only PA endpoint for a Create logistics network.
 *
 * <p>The speaker owns no message template and no sound selector. Both text and sound profile are
 * supplied by the network broadcaster that emitted the message. This keeps the network semantic
 * asymmetric and predictable: broadcasters send; speakers receive.</p>
 */
public final class NetworkSpeakerBlockEntity extends SmartBlockEntity implements NetworkBroadcastBus.Receiver {
    public static final int RADIUS = AnnouncerBlockEntity.DEFAULT_RADIUS;

    private LogisticallyLinkedBehaviour network;

    public NetworkSpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NETWORK_SPEAKER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        network = new LogisticallyLinkedBehaviour(this, false);
        behaviours.add(network);
    }

    public UUID networkId() {
        return network == null ? null : network.freqId;
    }

    @Override
    public ServerLevel broadcastLevel() {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    @Override
    public BlockPos broadcastPosition() {
        return worldPosition;
    }

    @Override
    public int broadcastRadius() {
        return RADIUS;
    }

    @Override
    public void deliverNetworkBroadcast(ServerPlayer player, String text, int soundProfile) {
        if (player == null || text == null || text.isBlank()) return;
        player.sendSystemMessage(Component.literal(text));
        player.connection.send(new ClientboundSoundPacket(soundFor(soundProfile), SoundSource.BLOCKS,
                worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5,
                1.15f, 1.0f, player.getRandom().nextLong()));
    }

    static Holder<SoundEvent> soundFor(int soundProfile) {
        return switch (Math.clamp(soundProfile, 0, 2)) {
            case 1 -> ModSounds.ANNOUNCER_IPPHONE;
            case 2 -> ModSounds.ANNOUNCER_RELAY;
            default -> ModSounds.ANNOUNCER_HMI;
        };
    }
}
