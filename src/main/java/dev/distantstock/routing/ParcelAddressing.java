package dev.distantstock.routing;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 地址里写的「给谁」。
 *
 * <p>地址匹配本身是 Create 的：`*` 通配符、两边都可以是模式（`工厂/*` 收 `工厂/铁锭`，反过来也
 * 一样），照抄它的 {@link PackageItem#matchAddress}，所以我们这边一个字符都不用另立规矩。
 *
 * <p>这一层只加一件事：**`@名字` 指名给某个玩家**。写法来自无人机物流的运输蜂停泊港 —— 地址写
 * 玩家名，停泊港就把它送到那个人手上。在这里它的意思是两件具体的事：
 *
 * <ul>
 *   <li>落地的包裹如果是 `@名字`，**只有那个人能把它从港里拿走**（别人的空手右键会被拒绝并说明）。
 *   <li>包裹落地时如果那个人在线，会收到一句"你的包裹到了"。
 * </ul>
 *
 * <p>名字对不上档（离线、改名、从来没见过）时按名字本身不区分大小写地比 —— 一件指名给人的包裹
 * 不该因为服务器查不到档就谁也拿不走，那是把货锁死，比给错人更糟。
 */
public final class ParcelAddressing {
    /** 指名给玩家的前缀。 */
    public static final String PLAYER = "@";

    /** 这包裹指名给谁：名字，或者空串（没指名）。 */
    public static String addressee(ItemStack parcel) {
        if (parcel == null || parcel.isEmpty()) {
            return "";
        }
        return addressee(PackageItem.getAddress(parcel));
    }

    /** 同一个规则，直接看地址字符串。 */
    public static String addressee(String address) {
        if (address == null) {
            return "";
        }
        String trimmed = address.trim();
        if (trimmed.length() <= PLAYER.length() || !trimmed.startsWith(PLAYER)) {
            return "";
        }
        return trimmed.substring(PLAYER.length()).trim();
    }

    /**
     * 这个玩家能不能拿走这件包裹。
     *
     * <p>没指名的谁都能拿；指名了就只有那个人。名字先按玩家自己报的名字比（不区分大小写），再按
     * 服务器档案解析出来的账号比 —— 两条都试，因为改名和离线各会挡住一条。
     */
    public static boolean mayTake(MinecraftServer server, ItemStack parcel, Player player) {
        String name = addressee(parcel);
        if (name.isEmpty() || player == null) {
            return true;
        }
        if (player.getName().getString().equalsIgnoreCase(name)) {
            return true;
        }
        return PlayerNames.lookup(server, name)
                .map(profile -> profile.getId().equals(player.getUUID()))
                .orElse(false);
    }

    private ParcelAddressing() {
    }
}
