package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.DistantStock;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Makes a parcel in hand always reach the dock, sneaking or not.
 *
 * Vanilla decides whether to offer a click to the block before the block ever hears about it:
 *
 * <pre>
 * boolean flag1 = player.isSecondaryUseActive() &amp;&amp; holdingSomething
 *         &amp;&amp; !(mainHand.doesSneakBypassUse(..) &amp;&amp; offhand.doesSneakBypassUse(..));
 * if (event.getUseBlock().isTrue() || (event.getUseBlock().isDefault() &amp;&amp; !flag1)) {
 *     blockstate.useItemOn(..);
 * </pre>
 *
 * Both hands have to bypass the sneak for {@code flag1} to clear, and an empty off hand never
 * does, so a player who sneaks while holding a parcel skips {@link DockBlock#useItemOn} outright.
 * That is a silent nothing: no insert, no refusal, not even the diagnostic line, which is exactly
 * how it was reported. The event itself still fires that early, so answering it with an explicit
 * "yes, use the block" is enough, and it keeps a single code path rather than a second copy of the
 * insert logic here.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class DockInteractionEvents {
    private static final Logger LOG = LogManager.getLogger();

    @SubscribeEvent
    public static void offerParcelToDock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof DockBlock)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!PackageItem.isPackage(held)) {
            return;
        }
        if (event.getEntity().isSecondaryUseActive()) {
            // Worth a line in the log: this is the case that used to do nothing at all, and a
            // report of "the dock ignored me" is otherwise impossible to tell from a missed click.
            LOG.info("Dock at {} taking a parcel from a sneaking player; overriding the "
                    + "sneak-use gate", event.getPos());
        }
        event.setUseBlock(TriState.TRUE);
    }

    private DockInteractionEvents() {
    }
}
