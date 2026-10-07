package dev.distantstock.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ChainConveyorBlockEntity.class)
public interface ChainConveyorAccessor {
    @Accessor("loopingPackages")
    List<ChainConveyorPackage> distantstock$loopingPackages();

    @Accessor("travellingPackages")
    Map<BlockPos, List<ChainConveyorPackage>> distantstock$travellingPackages();
}
