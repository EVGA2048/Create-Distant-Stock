package dev.distantstock.item;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The sealed parcel used by the remote logistics line.
 *
 * It deliberately keeps Create's PackageItem behaviour so addresses,
 * order data and the nine-slot contents component remain compatible with
 * Create's funnels, package pumps and package entities.
 */
public final class RemotePackageItem extends PackageItem {
    private static final PackageStyles.PackageStyle STYLE =
            new PackageStyles.PackageStyle("cardboard", 12, 12, 23f, false);

    public RemotePackageItem(Item.Properties properties) {
        super(properties.stacksTo(1), STYLE);
        // This item is selected explicitly by the remote packager. It should
        // not become one of Create's random cardboard styles.
        PackageStyles.ALL_BOXES.remove(this);
        PackageStyles.STANDARD_BOXES.remove(this);
    }

    @Override
    public String getDescriptionId() {
        return "item.distantstock.remote_package";
    }

    /**
     * 一件包裹身上可以有两个地址，摸一下要看得出**两个都在**。
     *
     * <p>第一行是 Create 自己那行（原版颜色）：包裹**现在**穿的门牌，也就是这台服务器分拣它时认的
     * 那个。第二行是我们加的（淡蓝色）：它到了对面要换成的门牌 —— 两台服务器给同一个门口起的名字
     * 经常不一样，而这一行是玩家在下单之后唯一还能核对第二地址的地方。
     *
     * <p>第二行原来挂在"这单会跨服"这个条件后面，于是不跨服、或者还没被写上路线的包裹（比如被
     * 网络里一台普通打包机抢先打出来的）只显示一个地址，看着像第二个地址丢了。现在它只问一件事：
     * 这件包裹身上有没有第二个地址。有就画 —— 那本来就是它身上的东西。
     *
     * <p>过了海这行会自己消失：那一刻地址被换掉、标签被清掉，剩下的一行就是它在新服务器上要穿的。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tip, flag);
        tip.addAll(extraLines(stack));
    }

    /**
     * 这件包裹要多说的那几行，从 Create 的 tooltip 里分出来单独一个方法。
     *
     * <p>分开是为了能直接被测：Create 自己那部分要在客户端拼（它引用的是渲染期的东西），而这些行
     * 只读栈上的数据，服务端的 game test 里就能断言 ——「两个地址都写得出来」这条规则从此有人看着。
     */
    public static List<Component> extraLines(ItemStack stack) {
        List<Component> lines = new java.util.ArrayList<>();
        // 第二个地址，先画：它是"这件包裹身上写着什么"的一部分，和它会不会跨服无关。
        String home = RemoteRouteData.homeAddress(stack);
        if (!home.isEmpty()) {
            lines.add(Component.translatable("item.distantstock.remote_package.home_address", home)
                    .withStyle(ChatFormatting.AQUA));
        }
        // 这一行只在"它还要去别的地方"的时候画：落了地就不该再挂着一条已经走完的路。
        if (RemoteRouteData.crossServer(stack)) {
            RemoteRouteData.read(stack).ifPresent(route -> {
                // The label was written when the route was, on the server, where the directories that
                // know what a group is called live. A parcel routed by an older build carries none and
                // falls back on the ids — which is what this line printed before it could say a name.
                String label = RemoteRouteData.label(stack);
                lines.add(Component.translatable("item.distantstock.remote_package.crossing",
                        label.isEmpty()
                                ? shortId(route.destinationNodeId()) + " · " + shortId(route.receivingDockGroupId())
                                : label).withStyle(ChatFormatting.LIGHT_PURPLE));
            });
        }
        return lines;
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
