package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.stock.StockCache;
import dev.distantstock.routing.TowerActivation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create's redstone requester, pointed at a warehouse on another server.
 *
 * <p>Everything about the machine is Create's: the nine ghost slots, the screen, the address field,
 * the rising edge that fires it. What changes is where the order goes — a bound requester sends it
 * across servers instead of asking the local packagers, and the goods arrive at a dock group on this
 * side.
 *
 * <p><b>Unbound is a plain redstone requester, and says so.</b> The block is a variant of one, and a
 * player who places it and never binds it has a redstone requester with a different coat of paint;
 * taking that away would make an unconfigured machine look broken. What it must not do is stay
 * quiet about it — see {@link #bindingLines()} for why the silence was worse than the fault it was
 * avoiding.
 *
 * <p><b>Nothing is ordered twice for one pulse.</b> The pulse is the whole trigger, and the machine
 * answers one edge with one order — there is no outstanding count to keep here as there is on a
 * gauge, because there is no target to hold at. What the far side does with it is its own business.
 */
public final class RemoteRedstoneRequesterBlockEntity extends RedstoneRequesterBlockEntity
        implements IHaveGoggleInformation {
    private RemoteBinding binding;
    /** 组名的**客户端副本**。服务端从不读它，读的时候现算（见 {@link #knownGroupName()}）。 */
    private String syncedGroupName;

    public RemoteRedstoneRequesterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REMOTE_REDSTONE_REQUESTER.get(), pos, state);
    }

    @Nullable
    public RemoteBinding binding() {
        return binding;
    }

    /** Points this requester at a warehouse. Null unbinds it, leaving Create's own behaviour. */
    public void bind(@Nullable RemoteBinding next) {
        this.binding = next;
        if (next != null) {
            // The far stock is only refreshed for networks something is watching, and the partial
            // check below is the only reason this machine reads it.
            StockCache.watch(next.network());
            // 玩家亲手把它指到这张网络上：之前「对面说不认识它」的退避作废，下一次就去问。
            StockCache.clearRefusal(next.network());
        }
        setChanged();
        sendData();
    }

    /**
     * One pulse, one order.
     *
     * <p>Bound, the order goes across servers; unbound, Create's own path runs untouched. The
     * partial check is against the warehouse's cached summary rather than this world's, because the
     * question "can they supply this" is only answerable by their side — and when nothing has
     * answered yet, the order goes anyway. Refusing to order until a cache this machine does not
     * control happens to be warm would be a machine that ignores its pulse for no reason the player
     * could see, and an order the far side cannot fill comes back through the return path rather
     * than vanishing.
     */
    @Override
    public void triggerRequest() {
        RemoteBinding bound = binding;
        if (bound == null) {
            super.triggerRequest();
            return;
        }
        if (encodedRequest == null || encodedRequest.isEmpty()) {
            return;
        }
        if (level == null || !TowerActivation.active(level, worldPosition)) {
            // Carried by a tower or it does not send. The unbound path above is Create's own machine
            // and is left alone; this one spends a warehouse's stock across a server boundary, and
            // that is the tower's to carry.
            playEffect(false);
            lastRequestSucceeded = false;
            return;
        }
        List<LinkQueues.Line> lines = linesFor(bound);
        if (lines.isEmpty()) {
            playEffect(false);
            lastRequestSucceeded = false;
            return;
        }
        // 地址用机器自己屏幕上那个框，而不是绑定时从终端抄来的那份。
        //
        // 这就是"这台机器没作用"的那半个 bug：Create 的请求器把地址存在 encodedTargetAdress 里，
        // 玩家照着原版习惯在屏幕里把它填好，而对面收到的包裹上写的是另一个地址 —— 绑定那一刻从
        // 终端抄下来的快照，之后再没更新过。一个看得见、能改、而且显然在问"送到哪"的框填了等于
        // 没填。现在它是唯一说了算的：绑定剩下的只是"从哪台服务器的哪张网络发货"。
        boolean ok = RemoteGaugeOrders.orderAll(level == null ? null : level.getServer(),
                bound.network(), encodedTargetAdress, bound.receivingGroup(), bound.homeAddress(), lines);
        lastRequestSucceeded = ok;
        playEffect(ok);
    }

    /** The configured items as order lines, cut down to what the warehouse is known to hold. */
    private List<LinkQueues.Line> linesFor(RemoteBinding bound) {
        Map<String, Integer> available = cachedStock(bound);
        List<LinkQueues.Line> lines = new ArrayList<>();
        for (BigItemStack entry : encodedRequest.stacks()) {
            if (entry == null || entry.stack == null || entry.stack.isEmpty() || entry.count <= 0) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString();
            int count = entry.count;
            Integer known = available.get(id);
            if (known != null) {
                if (known <= 0) {
                    if (!allowPartialRequests) {
                        // Not a partial order but a missing line: the far side cannot supply any of
                        // it, and sending the rest would answer a pulse with something the player
                        // did not ask for.
                        return List.of();
                    }
                    continue;
                }
                count = Math.min(count, known);
            }
            lines.add(new LinkQueues.Line(id, count));
        }
        return lines;
    }

    /** The warehouse's last reported stock, by item id, or empty when nothing has reported yet. */
    private Map<String, Integer> cachedStock(RemoteBinding bound) {
        List<StockCache.Entry> summary = StockCache.get(bound.network());
        if (summary == null || summary.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (StockCache.Entry entry : summary) {
            if (entry != null && entry.itemId != null) {
                counts.merge(entry.itemId, entry.count, Integer::sum);
            }
        }
        return counts;
    }

    @Override
    public boolean addToGoggleTooltip(List<net.minecraft.network.chat.Component> tooltip, boolean sneaking) {
        tooltip.addAll(bindingLines());
        return !tooltip.isEmpty();
    }

    /**
     * 绑在哪儿，或者没绑。
     *
     * <p>没绑定的时候**要说出来**。这里原来什么都不画，理由是一台没配置的机器不该看起来像坏的 ——
     * 那条理由在实机上被推翻了：没绑定时这台机器确实按普通红石请求器工作（脉冲打出去走本机网络），
     * 于是玩家看到的就是"远仓红石请求器没作用"，而屏幕上没有一个字告诉他为什么。它与旁边那台原版
     * 请求器**长得一样、行为也一样**，唯一的区别是名字 —— 那不叫"工作正常"，那叫"看不出来"。
     */
    public List<net.minecraft.network.chat.Component> bindingLines() {
        List<net.minecraft.network.chat.Component> tip = new ArrayList<>();
        GoggleText.title(tip, "block.distantstock.remote_redstone_requester");
        if (binding == null) {
            GoggleText.line(tip, "goggle.distantstock.remote_requester.unbound");
            GoggleText.line(tip, "goggle.distantstock.remote_requester.bind_hint");
            return tip;
        }
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.source", binding.network().shortLabel());
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.group",
                binding.receivingGroup() == null ? "—" : RequesterData.shortFreq(binding.receivingGroup()));
        // 送货地址在这台机器上不是绑定的一部分（它用的是屏幕里那个框），所以这里把它读出来给
        // 玩家看：屏幕里填了什么、对面包裹上会写什么，是同一件事的两个地方。
        GoggleText.line(tip, "goggle.distantstock.address",
                encodedTargetAdress == null || encodedTargetAdress.isBlank() ? "—" : encodedTargetAdress);
        return tip;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (binding != null) {
            tag.put("RemoteBinding", binding.save());
        }
        if (clientPacket) {
            tag.putString("GroupName", liveGroupName());
        }
    }

    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        // The drop carries its binding: a machine picked up and put down again should still know
        // where its goods come from, the same way it keeps the nine items and the address.
        if (binding != null) {
            tag.put("RemoteBinding", binding.save());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        binding = RemoteBinding.read(tag.getCompound("RemoteBinding"));
        if (clientPacket || level == null || level.isClientSide) {
            // 只读同步过来的那一份。组名是要在屏幕上画出来的，而客户端没有目录可查 —— 一个 uuid
            // 前八位在一行写着「接收港组」的框里等于什么都没说。
            syncedGroupName = tag.contains("GroupName") ? tag.getString("GroupName") : null;
        }
    }

    /**
     * 屏幕要显示的那个组名：客户端读同步过来的那一份，服务端现算。
     *
     * <p>和港护目镜那一行是同一个坑（那边当时显示的是 uuid 前八位）：组名只有服务端查得到。
     */
    public String knownGroupName() {
        if (level != null && !level.isClientSide) {
            return liveGroupName();
        }
        return syncedGroupName == null || syncedGroupName.isEmpty()
                ? (binding == null || binding.receivingGroup() == null ? ""
                        : RequesterData.shortFreq(binding.receivingGroup()))
                : syncedGroupName;
    }

    private String liveGroupName() {
        if (binding == null || binding.receivingGroup() == null
                || level == null || level.getServer() == null) {
            return "";
        }
        return dev.distantstock.routing.DockGroupDirectory.get(level.getServer())
                .find(binding.receivingGroup())
                .map(dev.distantstock.routing.DockGroup::name)
                .orElseGet(() -> RequesterData.shortFreq(binding.receivingGroup()));
    }

    /** 屏幕改完目标以后写回来：网络和送货地址不动，只换接收港组和本端地址。 */
    public void retarget(@Nullable java.util.UUID group, String homeAddress) {
        if (binding == null) {
            return;
        }
        bind(new RemoteBinding(binding.network(), group, binding.address(), homeAddress));
    }

    /**
     * 这台机器的界面是**我们自己的**菜单类型，所以标题也归我们。
     *
     * <p>标题是服务端在 openMenu 时发过来的这一句。沿用 Create 的类型就只能是「红石请求器」——
     * 一台远仓请求器和旁边那台原版长得一模一样，玩家分不出该往哪台里填。
     */
    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.distantstock.remote_redstone_requester");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
            int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new dev.distantstock.menu.RemoteRedstoneRequesterMenu(
                dev.distantstock.menu.ModMenus.REMOTE_REQUESTER.get(), id, inventory, this);
    }
}
