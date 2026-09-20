package dev.distantstock.menu;

import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterFind;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.stock.StockCache;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.distantstock.routing.RemoteNetworkId;

public final class RequesterMenu extends AbstractContainerMenu {
    public final InteractionHand hand;
    public final BlockPos gaugePos;
    public List<StockCache.Entry> stock = new ArrayList<>();
    public List<NetworkDirectory.Entry> networks = new ArrayList<>();
    public UUID selectedFreq;
    public boolean demo;
    /** Server-authored configuration snapshot carried by the menu-opening payload. Client only. */
    boolean hasOpenedState;
    RemoteNetworkId openedNetworkId;
    UUID openedDistantNetworkId;
    String openedAddress = "";
    String openedHomeAddress = "";
    UUID openedReceivingGroup;
    String openedReceivingGroupName = "";

    public RequesterMenu(int id, Inventory inv, InteractionHand hand) {
        super(ModMenus.REQUESTER.get(), id);
        this.hand = hand;
        this.gaugePos = null;
        if (!inv.player.level().isClientSide) {
            refresh(inv.player);
            // The screen cannot draw a list it has never been given, and the field it replaces used
            // to be free text precisely because there was nothing to list.
            sendGroupList(inv.player, device(inv.player));
        }
    }

    /** Persist only the terminal's Distant Stock network context; no Create warehouse is implied. */
    public void persistDistantNetworkContext(Player player, UUID scope) {
        if (player == null || isGauge()) return;
        ItemStack stack = device(player);
        if (stack.isEmpty()) return;
        RequesterData.setDistantNetwork(stack, scope);
        player.getInventory().setChanged();
    }

    public void clearDistantNetworkContext(Player player) {
        if (player == null || isGauge()) return;
        ItemStack stack = device(player);
        if (stack.isEmpty()) return;
        RequesterData.setDistantNetwork(stack, null);
        RequesterData.clearWarehouseBinding(stack);
        RequesterData.setReceivingGroup(stack, null, null);
        selectedFreq = null;
        player.getInventory().setChanged();
    }

    /** Replace an obsolete local RemoteNetworkId stored on the device with the live directory id. */
    public void persistLocalNetworkIdentity(Player player, RemoteNetworkId member, UUID scope) {
        if (player == null || member == null) return;
        UUID stored = scope == null
                ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID : scope;
        GaugeBlockEntity gauge = gauge(player);
        if (gauge != null) {
            gauge.setNetwork(member, stored);
            return;
        }
        ItemStack stack = device(player);
        if (!stack.isEmpty()) {
            RequesterData.setNetwork(stack, member, stored);
            player.getInventory().setChanged();
        }
    }

    /**
     * Persist a changed Distant Stock membership onto the currently selected warehouse binding.
     * Live announcements are still preferred when present; this copy is what survives a quiet peer.
     */
    public void persistDistantNetworkScope(Player player, RemoteNetworkId member, UUID scope) {
        if (player == null || member == null || !member.equals(networkId(player))) {
            return;
        }
        UUID stored = scope == null
                ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID : scope;
        GaugeBlockEntity gauge = gauge(player);
        if (gauge != null) {
            if (!stored.equals(gauge.distantNetworkId())) {
                gauge.setNetwork(member, stored);
            }
            return;
        }
        ItemStack stack = device(player);
        if (!stack.isEmpty()) {
            if (!RequesterData.distantNetwork(stack).filter(stored::equals).isPresent()) {
                RequesterData.setNetwork(stack, member, stored);
                player.getInventory().setChanged();
            }
        }
    }

    public RequesterMenu(int id, Inventory inv, BlockPos gaugePos) {
        super(ModMenus.REQUESTER.get(), id);
        this.hand = InteractionHand.MAIN_HAND;
        this.gaugePos = gaugePos;
        if (!inv.player.level().isClientSide) {
            refresh(inv.player);
            // The desk has a group list of its own to offer: without this the dropdown stays empty
            // and the field shows the display name of a system the server may not even hold.
            sendGroupList(inv.player, device(inv.player));
        }
    }

    public static RequesterMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        RequesterMenu menu = buf.readBoolean()
                ? new RequesterMenu(id, inv, buf.readBlockPos())
                : new RequesterMenu(id, inv, buf.readEnum(InteractionHand.class));
        if (buf.readBoolean()) {
            menu.selectedFreq = buf.readUUID();
        }
        MenuSync.readCatalog(menu, buf);
        MenuSync.readState(menu, buf);
        return menu;
    }

    public boolean isGauge() {
        return gaugePos != null;
    }

    public GaugeBlockEntity gauge(Player player) {
        if (gaugePos == null || player.level() == null) {
            return null;
        }
        return player.level().getBlockEntity(gaugePos) instanceof GaugeBlockEntity be ? be : null;
    }

    public UUID freq(Player player) {
        if (selectedFreq != null) {
            return selectedFreq;
        }
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.freq();
        }
        return RequesterData.freq(device(player));
    }

    public String address(Player player) {
        if (hasOpenedState && player != null && player.level().isClientSide) {
            return openedAddress;
        }
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.address();
        }
        return RequesterData.address(device(player));
    }

    /**
     * The address the goods wear once they are back on this side, or "".
     *
     * <p>Blank is the ordinary setting and means the parcel keeps whatever address it was packed
     * with — which is right for everything that never crosses, and for a crossing where both servers
     * happen to call the door the same thing.
     */
    public String homeAddress(Player player) {
        if (hasOpenedState && player != null && player.level().isClientSide) {
            return openedHomeAddress;
        }
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.homeAddress();
        }
        return RequesterData.homeAddress(device(player));
    }

    public void writeHomeAddress(Player player, String homeAddress) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            be.setHomeAddress(homeAddress);
            return;
        }
        ItemStack stack = device(player);
        if (!stack.isEmpty()) {
            RequesterData.setHomeAddress(stack, homeAddress);
            player.getInventory().setChanged();
        }
    }

    public RemoteNetworkId networkId(Player player) {
        if (hasOpenedState && player != null && player.level().isClientSide) {
            return openedNetworkId;
        }
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.networkId();
        }
        return RequesterData.network(device(player)).orElse(null);
    }

    /** The Distant Stock network that contains the selected Create logistics network. */
    public UUID distantNetworkId(Player player) {
        if (hasOpenedState && player != null && player.level().isClientSide) {
            if (selectedFreq != null) {
                for (NetworkDirectory.Entry entry : networks) {
                    if (selectedFreq.equals(entry.freq())) {
                        return entry.distantNetworkId() == null
                                ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID
                                : entry.distantNetworkId();
                    }
                }
            }
            return openedDistantNetworkId == null
                    ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID
                    : openedDistantNetworkId;
        }
        GaugeBlockEntity gauge = gauge(player);
        if (gauge != null && gauge.hasDistantNetworkId()
                && dev.distantstock.routing.DistantNetworkDirectory.isFormalId(gauge.distantNetworkId())) {
            return gauge.distantNetworkId();
        }
        if (!isGauge()) {
            UUID carriedScope = RequesterData.distantNetwork(device(player))
                    .filter(dev.distantstock.routing.DistantNetworkDirectory::isFormalId)
                    .orElse(null);
            if (carriedScope != null) {
                return carriedScope;
            }
        }
        RemoteNetworkId resolved = MenuSync.resolve(networkId(player), freq(player));
        if (resolved == null || player == null || player.level().getServer() == null) {
            return dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        }

        UUID localNode = dev.distantstock.link.TranserverBridge.localNodeUuid();
        if (localNode != null && localNode.equals(resolved.nodeId())) {
            // Local SavedData is the only authority for membership. A device's stored scope is
            // merely a cache and must never let two old terminals disagree about one warehouse.
            // Canonicalise by frequency when a live local row exists, but do not require the
            // background scanner to have run yet.
            RemoteNetworkId canonical = NetworkDirectory.findByFreq(resolved.createFrequency())
                    .filter(NetworkDirectory.Entry::local)
                    .map(NetworkDirectory.Entry::networkId)
                    .orElse(resolved);
            return dev.distantstock.routing.DistantNetworkDirectory.get(player.level().getServer())
                    .formalNetworkOf(canonical)
                    .orElse(dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID);
        }
        NetworkDirectory.Entry live = NetworkDirectory.find(resolved).orElse(null);
        if (live != null && dev.distantstock.routing.DistantNetworkDirectory
                .isFormalId(live.distantNetworkId())) {
            return live.distantNetworkId();
        }
        return dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
    }

    public void writeAddress(Player player, String address) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            be.setAddress(address);
            return;
        }
        ItemStack stack = device(player);
        if (!stack.isEmpty()) {
            RequesterData.setAddress(stack, address);
            player.getInventory().setChanged();
        }
    }

    /**
     * Points the requester at a group, making or renaming one if that is what the name asks for.
     *
     * <p>Three outcomes from one field, because the screen has one field: an existing name selects,
     * an unused name creates, and a rename renames whatever the requester is already carrying. The
     * rename case is why the flag is here rather than being inferred — without it, renaming a group
     * to a name nobody has used would create a second group and leave the original behind.
     */
    public void writeDockGroup(Player player, String name, int action) {
        ItemStack stack = device(player);
        if (action == dev.distantstock.net.SetDockGroupC2S.REFRESH) {
            // 界面刚打开：只把列表再发一遍，一个字都不改。名字在这里没有意义，所以放在校验前面。
            sendGroupList(player, stack);
            return;
        }
        if (name == null || (!isGauge() && stack.isEmpty())) {
            return;
        }
        String trimmed = name.trim();
        // The client limits the field, a packet does not. Refusing here keeps a crafted name from
        // reaching DockGroup, which throws on a name that is long or blank — an exception raised
        // while handling a network packet, which is a far worse way to find out.
        if (trimmed.length() > dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH) {
            return;
        }
        if (trimmed.isEmpty()) {
            setCarriedGroup(player, stack, null);
            return;
        }
        if (player.level().getServer() == null) {
            return;
        }
        dev.distantstock.routing.DockGroupDirectory directory =
                dev.distantstock.routing.DockGroupDirectory.get(player.level().getServer());
        if (action == dev.distantstock.net.SetDockGroupC2S.RENAME) {
            // Rename the group this requester already carries. With nothing carried there is
            // nothing to rename, so the same gesture falls through to selecting or creating —
            // otherwise the button would appear to do nothing at all on a fresh requester.
            java.util.Optional<java.util.UUID> carried =
                    dev.distantstock.item.RequesterData.receivingGroup(stack);
            if (carried.isPresent()
                    && carried.get().equals(directory.DEFAULT_GROUP_ID)) {
                // The default system is the fallback for every lookup that misses, and its name is
                // in the language file. Renaming it would leave the one group every dock starts in
                // wearing a name nothing recognises — a player reported being able to do exactly
                // this from the box the name is shown in.
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("gui.distantstock.group.default_fixed"), true);
                sendGroupList(player, stack);
                return;
            }
            if (carried.isPresent()) {
                if (directory.find(carried.get()).isEmpty()) {
                    // A destination on another server. Its name belongs to whoever owns it over
                    // there, and renaming it here would only be a local label on somebody else's
                    // group — the one thing the list must not show. (The rename would also throw:
                    // the id is not in this directory.)
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "gui.distantstock.pair.remote_no_rename"), true);
                    sendGroupList(player, stack);
                    return;
                }
                if (!dev.distantstock.routing.ReceivingAddressResolver.mayClaimLocalName(
                        player.level().getServer(), carried.get(), trimmed)) {
                    // A receiving-address name is network-wide now. Another server having the name
                    // is just as much a collision as another local group having it.
                    return;
                }
                dev.distantstock.routing.DockGroup renamed =
                        directory.rename(carried.get(), trimmed);
                setCarriedGroup(player, stack, renamed);
                sendGroupList(player, stack);
                return;
            }
        }
        java.util.UUID who = player == null ? null : player.getUUID();
        UUID addressScope = distantNetworkId(player);
        if (!dev.distantstock.routing.DistantNetworkDirectory.isFormalId(addressScope)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.distantstock.network.required"), true);
            sendGroupList(player, stack);
            return;
        }
        if (action == dev.distantstock.net.SetDockGroupC2S.TOGGLE_OPEN) {
            // Legacy management flag. It no longer controls delivery, but old saves/screens may
            // still use it for collaborator membership until that UI is fully retired.
            dev.distantstock.routing.DockGroup target =
                    directory.findByName(addressScope, trimmed).orElse(null);
            if (target != null && target.ownedBy(who)) {
                directory.setOpen(target.id(), !target.open());
            }
            sendGroupList(player, stack);
            return;
        }
        if (action == dev.distantstock.net.SetDockGroupC2S.TOGGLE_VISIBILITY) {
            dev.distantstock.routing.DockGroup target =
                    directory.findByName(addressScope, trimmed).orElse(null);
            if (target != null && target.ownedBy(who)) {
                directory.setVisibility(target.id(),
                        target.visibility() == dev.distantstock.routing.DockGroup.Visibility.PUBLIC
                                ? dev.distantstock.routing.DockGroup.Visibility.UNLISTED
                                : dev.distantstock.routing.DockGroup.Visibility.PUBLIC);
            }
            sendGroupList(player, stack);
            return;
        }
        if (action == dev.distantstock.net.SetDockGroupC2S.DELETE) {
            // Only the owner may remove their own system. The two-click confirmation lives in the
            // screen and is a courtesy, not a lock: a modified client can send this straight away,
            // and the thing it can do is delete a group it already owns. What must not happen is
            // somebody else's group disappearing, and that is what this check is for.
            dev.distantstock.routing.DockGroup target =
                    directory.findByName(addressScope, trimmed).orElse(null);
            if (target != null && target.ownedBy(who) && directory.delete(target.id())) {
                // The docks that were in it go back to the group every dock starts in. A dock left
                // pointing at a system nobody can address any more would sit there looking busy
                // forever.
                for (dev.distantstock.block.DockBlockEntity dock
                        : dev.distantstock.block.LoadedDocks.allInGroup(target.id())) {
                    dock.setGroupId(directory.DEFAULT_GROUP_ID);
                }
                if (dev.distantstock.item.RequesterData.receivingGroup(stack).filter(target.id()::equals).isPresent()) {
                    setCarriedGroup(player, stack, directory.require(directory.DEFAULT_GROUP_ID));
                }
            }
            sendGroupList(player, stack);
            return;
        }
        var resolved = dev.distantstock.routing.ReceivingAddressResolver.resolve(
                player.level().getServer(), addressScope, trimmed);
        if (resolved.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.CONFLICT) {
            // Dedicated wording is intentionally left to the language PR. The important part here
            // is that a conflicting name never silently picks the local or remote copy.
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "gui.distantstock.group.unknown_name", trimmed), true);
            sendGroupList(player, stack);
            return;
        }
        if (resolved.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.REMOTE) {
            var remote = resolved.remote();
            // Keep only the globally unique receiving-address name on the terminal. Older builds
            // wrote "server·name"; the resolver still accepts that spelling when it reads one.
            setCarriedGroup(player, stack, remote.group(), remote.name());
            sendGroupList(player, stack);
            return;
        }
        dev.distantstock.routing.DockGroup existing =
                resolved.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.LOCAL
                        ? resolved.local() : null;
        // A group made here belongs to whoever made it, and starts closed. See
        // DockGroupDirectory.createFor for why the default is the quiet one.
        dev.distantstock.routing.DockGroup group = existing != null
                ? existing : directory.createForNetwork(trimmed, who, addressScope,
                dev.distantstock.routing.DockGroup.Visibility.PUBLIC);
        // findByName above and createFor here both run on the server thread, so nothing can slip
        // between them. The duplicate check inside createFor is what keeps that an argument rather
        // than a hope.
        setCarriedGroup(player, stack, group);
        // Push the list again: a system just made does not exist on the client until it is told,
        // and the one now carried has to stop being drawn as somebody else's.
        sendGroupList(player, stack);
    }

    /**
     * The group this requester is pointed at: the desk's own when opened from a desk, the held
     * item's when opened from the hand.
     *
     * <p>One place answers this so the screen, the list it is sent and the group an order is filed
     * under cannot disagree about where the goods come out.
     */
    public java.util.Optional<UUID> carriedGroup(Player player) {
        if (hasOpenedState && player != null && player.level().isClientSide) {
            return java.util.Optional.ofNullable(openedReceivingGroup);
        }
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return java.util.Optional.of(be.receivingGroup());
        }
        return RequesterData.receivingGroup(device(player));
    }

    /** Writes a group to whichever of the two owns it. See {@link #carriedGroup}. */
    private void setCarriedGroup(Player player, ItemStack stack, dev.distantstock.routing.DockGroup group) {
        setCarriedGroup(player, stack, group == null ? null : group.id(),
                group == null ? null : group.name());
    }

    /**
     * The same, by bare id and name, for a group this server has no {@code DockGroup} for.
     *
     * <p>A destination from another server is an id and a label and nothing else — there is no
     * record of it here to hand around, and inventing one would mean a local group that no local
     * dock can be in.
     */
    private void setCarriedGroup(Player player, ItemStack stack, UUID id, String name) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            be.setReceivingGroup(id == null
                    ? dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID : id);
            return;
        }
        RequesterData.setReceivingGroup(stack, id, name);
        if (player != null) {
            player.getInventory().setChanged();
        }
    }

    /** Name included in the server-authored open snapshot, before the async group list arrives. */
    public String openedReceivingGroupName() {
        return hasOpenedState ? openedReceivingGroupName : "";
    }

    public boolean hasOpenedState() {
        return hasOpenedState;
    }

    public java.util.Optional<UUID> openedReceivingGroup() {
        return hasOpenedState ? java.util.Optional.ofNullable(openedReceivingGroup)
                : java.util.Optional.empty();
    }

    public String openedAddress() {
        return hasOpenedState ? openedAddress : "";
    }

    public String openedHomeAddress() {
        return hasOpenedState ? openedHomeAddress : "";
    }

    public RemoteNetworkId openedNetworkId() {
        return hasOpenedState ? openedNetworkId : null;
    }

    public java.util.Optional<UUID> openedDistantNetworkId() {
        return hasOpenedState ? java.util.Optional.ofNullable(openedDistantNetworkId)
                : java.util.Optional.empty();
    }

    /** Pushes the current list to whoever has this screen open. */
    public static void sendGroupList(Player player, ItemStack stack) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || player.level().getServer() == null) {
            // There is nothing to push to a player who is not on a server — a game test's mock
            // player is the case that matters, and the write it is testing happens either way.
            return;
        }
        dev.distantstock.routing.DockGroupDirectory directory =
                dev.distantstock.routing.DockGroupDirectory.get(player.level().getServer());
        java.util.UUID carried = player.containerMenu instanceof RequesterMenu menu
                ? menu.carriedGroup(player).orElse(null)
                : dev.distantstock.item.RequesterData.receivingGroup(stack).orElse(null);
        java.util.UUID scope = player.containerMenu instanceof RequesterMenu menu
                ? menu.distantNetworkId(player)
                : scopeOf(player, stack);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                dev.distantstock.net.DockGroupsS2C.of(directory, player.getUUID(), carried, scope,
                        group -> dev.distantstock.block.LoadedDocks.allInGroup(group).size(),
                        owner -> dev.distantstock.routing.PlayerNames.display(
                                player.level().getServer(), owner)));
        // The other half of the same list. Sent together because the screen draws one list: a
        // destination from another server is chosen the same way and differs only in what the far
        // end does with it.
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                dev.distantstock.net.RemoteGroupsS2C.of(
                        dev.distantstock.routing.RemoteGroups.get(player.level().getServer()),
                        player.level().getServer(), player.getUUID(), scope));
    }

    private static UUID scopeOf(Player player, ItemStack stack) {
        if (player == null || player.level().getServer() == null || stack == null || stack.isEmpty()) {
            return dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        }
        UUID terminalScope = RequesterData.distantNetwork(stack)
                .filter(dev.distantstock.routing.DistantNetworkDirectory::isFormalId)
                .orElse(null);
        if (terminalScope != null) {
            return terminalScope;
        }
        RemoteNetworkId network = RequesterData.network(stack).orElse(null);
        if (network == null) {
            return dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        }
        return NetworkDirectory.find(network).map(NetworkDirectory.Entry::distantNetworkId)
                .or(() -> RequesterData.distantNetwork(stack))
                .orElseGet(() -> dev.distantstock.routing.DistantNetworkDirectory
                        .get(player.level().getServer()).scopeOf(network));
    }

    public ItemStack device(Player player) {
        if (isGauge()) {
            return new ItemStack(ModBlocks.GAUGE.get());
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof RequesterItem) {
            return stack;
        }
        return RequesterFind.find(player);
    }

    public boolean tuned(Player player) {
        return freq(player) != null;
    }

    public void refresh(Player player) {
        UUID freq = freq(player);
        // 只记得频率的设备也要能看见对面那张网络：从目录里把网络 id 补出来。少了这一步，
        // 这台终端只会按频率 watch，而跨服那条链路问的是网络 id —— 没人去问，库存永远是空的。
        RemoteNetworkId networkId = MenuSync.resolve(networkId(player), freq);
        MenuSync.warm(networkId, freq);
        stock = new ArrayList<>(networkId == null ? StockCache.get(freq) : StockCache.get(networkId));
        demo = StockConfig.DEMO_STOCK.get();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (isGauge()) {
            return gauge(player) != null && player.distanceToSqr(gaugePos.getX() + 0.5, gaugePos.getY() + 0.5, gaugePos.getZ() + 0.5) < 64;
        }
        return device(player).getItem() instanceof RequesterItem;
    }
}
