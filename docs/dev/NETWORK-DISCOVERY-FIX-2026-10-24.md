# 0.3.57：远仓网络发现与邀请码找回

- 创建者在创建网络的服务器执行 `/distantstock network code`，列出自己的邀请码；也可用 `/distantstock network code "网络名"`。无需持有已绑定仓库的终端。命令仍继承原来的管理员权限要求，且只向创建者本人返回邀请码。
- `/distantstock network` 同时显示本地已知网络和远端节点公告的网络。网络为空、仓库未加载也可被发现；公告不携带邀请码，不自动加入网络。远端发现缓存五分钟过期，空公告替换旧目录。
- 手持未加入网络的终端执行 `/distantstock network join-terminal XXXX-XXXX`，先加入远仓网络，再选择仓库。原 `network join` 保持把本地仓库加入网络的语义。
- ES2、VL4 都更新 DistantStock 并重启。新增能力 `network-catalog-v1`，仅向支持该能力的节点发送 v6 公告；旧节点继续收到 v5 仓库/港组公告。Router 和 Transerver 无须为本次变更升级。

现场验证：VL4 创建无仓库的网络，更换终端后使用 code 命令取回邀请码；ES2 等待节点握手与公告后使用 network 查看，再手持空终端用 join-terminal 加入。错误邀请码应仍被拒绝，非创建者不能读取邀请码。远端目录可见不代表加入握手已成功；若仍加入失败，需检查两服状态和加入结果消息。
