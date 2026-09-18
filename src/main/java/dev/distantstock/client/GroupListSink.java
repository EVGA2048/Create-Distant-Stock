package dev.distantstock.client;

import dev.distantstock.net.DockGroupsS2C;
import dev.distantstock.net.RemoteGroupsS2C;

/**
 * 能把接收港组清单收下的界面。
 *
 * <p>清单是服务器推的（{@link DockGroupsS2C} 和 {@link RemoteGroupsS2C}），推给的是**玩家**，不是某个
 * 界面 —— 所以由界面的类型来决定这份东西归谁。以前只有终端收（它就是为它写的），现在远仓红石请求器
 * 和远仓仪表也要用它选组，于是抽成一个小接口：包的处理里按这个认，谁实现了谁就收得到。
 *
 * <p>两个方法而不是一个：本服的清单和对面公告过来的清单是两条包、两个时机（一个随要随给，一个跟着
 * 公告走），合并成一个类型反而要在两边各拆一次。
 */
public interface GroupListSink {
    void acceptGroups(DockGroupsS2C groups);

    void acceptRemotes(RemoteGroupsS2C remotes);
}
