package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
    public void receiveNetworkBroadcast(String text, int soundProfile) {
        if (!(level instanceof ServerLevel serverLevel) || text == null || text.isBlank()) return;

        double cx = worldPosition.getX() + .5;
        double cy = worldPosition.getY() + .5;
        double cz = worldPosition.getZ() + .5;
        double maxDistance = (double) RADIUS * RADIUS;
        int listeners = 0;

        for (var player : serverLevel.players()) {
            if (player.distanceToSqr(cx, cy, cz) > maxDistance) continue;
            player.sendSystemMessage(Component.literal(text));
            listeners++;
        }

        // A PA endpoint only emits when somebody is actually in its service area, matching the
        // local brass announcer and avoiding pointless server sound packets in empty chunks.
        if (listeners > 0) {
            serverLevel.playSound(null, worldPosition, soundFor(soundProfile),
                    SoundSource.BLOCKS, 1.15f, 1.0f);
        }
    }

    static SoundEvent soundFor(int soundProfile) {
        return switch (Math.clamp(soundProfile, 0, 2)) {
            case 1 -> ModSounds.ANNOUNCER_IPPHONE.get();
            case 2 -> ModSounds.ANNOUNCER_RELAY.get();
            default -> ModSounds.ANNOUNCER_HMI.get();
        };
    }
}
