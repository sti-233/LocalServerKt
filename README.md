# LocalServerKt

LocalServerKt 是使用 Ktor 对 [LocalServer](https://github.com/dfc2333/LocalServer) 的部分重写，额外扩展了聊天、控制、音乐、视频搜索、AI 对话、文件浏览与浏览器等模块。服务器运行在 `0.0.0.0:80`。

## 端点

### Chat

- `/chat`
  - 重定向至聊天页面（`auth` 验证）
- `/whoami`
  - 返回当前请求 IP 对应的用户名（`auth` 验证）
- `/history?targetuser={?}&date={?}`
  - 获取聊天记录；参数缺省时获取群聊记录，`targetuser` 非空时获取与指定用户的私聊记录（不在用户列表时返回 400）；`date=yyyy-MM-dd` 查看指定日期的群聊记录
- `/conversations`
  - 获取会话列表：返回 `{today, names, last}`。`names` 为 `message/` 下各 history 文件名（群聊为日期、私聊为"名A-名B"）；`last` 为各会话最后一条消息的 `{time, by}`；供聊天页侧边栏快速切换会话与未读小红点
- `/message`
  - WebSocket 接口，支持群聊与私聊（消息 `sendTo` 字段）；私聊对象不在用户列表时回 `err` 事件；消息 2 分钟内可撤回；图片消息以 `ImageB64:` 前缀 + data URL 发送（限 10MB），页面支持双击放大查看（滚轮/捏合缩放、拖拽平移）
  - 聊天页特性：可收起的会话侧边栏（按私聊对象/群聊日期分组切换，私聊对象框填 `yyyy-MM-dd` 可浏览该日群聊记录）；未读小红点（基于 `/conversations` 的 `last` 与本地 localStorage 已读时间戳）；断线后自动重连（指数退避 3s~30s），重连成功后补拉历史与会话列表
- `/clients`
  - 查看所有在线 WebSocket 客户端 IP
- `/wsSever`
  - 纯文本接口，查看在线客户端数量

### Control

- `/start?p={}`
  - 开启服务状态（`control` 验证；状态只影响 `auth` 验证，不真正停止服务器）
- `/exit?p={}`
  - 关闭服务状态（同上）
- `/userList`
  - 获取 `userList.json` 内容
- `/addUser?ip={}&username={?}`
  - 添加用户（`control` 验证）
- `/removeUser?ip={}`
  - 移除用户（`control` 验证）
- `/resetName?ip={}&username={?}`
  - 重置用户名（`control` 验证）
- `/vnc`
  - 重定向至 VNC 查看器（`control` 验证）
- `/log`
  - 获取 `logs.txt` 日志内容

### Music

- `/music`
  - 重定向至音乐页面（`auth` 验证）
- `/searchMusic?keyword={}&offset={?}&limit={?}`
  - 搜索音乐
- `/getLyrics?id={}`
  - 获取歌词
- `/getMusicUrl?id={}&level={}`
  - 获取音乐链接

### Video

- `/video`
  - 重定向至视频页面（推荐流 + 搜索入口）
- `/recommend?fresh={?}&pageSize={?}`
  - 首页推荐流，`fresh` 递增翻页；被风控时自动降级到热门榜
- `/searchByType?keyword={}&type={video|user}&page={?}`
  - 统一搜索入口（WBI 签名），`type=user` 搜用户/UP主
- `/searchUser?keyword={}&page={?}&userType={?}`
  - 用户搜索（等价于 `type=user`）
- `/videoInfo?bvid={}|aid={}`
  - 视频详情（分P、UP主、统计）
- `/videoStreamInfo?bvid={}&cid={}&qn={?}`
  - 取流信息（DASH 多清晰度）；默认按 1080P 协商，免登录 1080P（`try_look=1` + `fnval=4048`），1080P+ 需大会员登录；响应附 `availableQualities` 与 `bestQuality`，并预取首段流数据
- `/videoStream?url={}`
  - 取流代理（透传 Range，可拖动进度条；仅放行 B 站 CDN 域名）
- `/videoComments?aid={}&page={?}&sort={?}`
  - 视频评论（游标翻页，`sort` 3=热度 / 2=时间 / 1=混合）
- `/userInfo?mid={}`
  - 账号资料（App 端接口优先，失败降级 Web 端）
- `/userSpace?mid={}&cursor={?}&order={?}&pageSize={?}`
  - 空间投稿（App 端游标翻页优先，失败降级 Web 端页码翻页）
- `/userDynamic?mid={}&cursor={?}`
  - 空间动态（图文 / 视频投稿 / 转发；内置重试应对 B 站软限流）

### Ai

- `/ai`
  - 重定向至 AI 对话页面（`auth` 验证）
- `/ai/chat?text={}`
  - SSE 接口，代理上游模型 SSE 流；会话历史按 IP 保存在内存中
- `/ai/history`
  - 获取当前 IP 的会话历史
- `/ai/clear`
  - 清除当前 IP 的会话历史（POST）

### File

- `/file`
  - 重定向至文件浏览页面（`control` 验证）
- `/listFile?path={?}`
  - 列出目录内容（文件夹排前、按名称忽略大小写排序）；`path` 缺省或 `/` 时返回本地磁盘列表（`control` 验证）
- `/fileContent?path={}`
  - 以 `attachment` 响应头原样返回文件，由浏览器直接下载（`control` 验证）
- `/defaultFileDir`
  - 返回运行中 jar 所在的目录，供前端作为默认浏览目录（`control` 验证）

### 浏览器 / 下载

- `/`、`/client-lzysso/h5-sso`
  - 重定向至浏览器页面（`auth` 验证）
- `/download?url={}&inline={?}`
  - 下载代理；`inline=1` 时内联返回，并把 CSS/HTML 中的相对 URL 改写为绝对 URL

## 验证

### 实现

分为 `auth` 与 `control` 两个 realm。

当请求带有正确的 `?p={}` 参数，或客户端 IP 为 `192.168.20.1` 时，两个 realm 的验证均被跳过。

`auth` 验证

- 请求方 IP 是否存在于 `userList.json` 中
- 服务状态是否为 `true`

失败时自动重定向至 `https://h5.lezhiyun.com/multi_wjdc/?host=www.lezhiyun.com&token=`

`control` 仅验证 `?p={}` 参数是否正确（无用户列表 / 服务状态检查）

### 接入页面

- `auth`
  - `/`
  - `/chat`
  - `/music`
  - `/ai`
- `control`
  - `/start?p={}`
  - `/exit?p={}`
  - `/addUser`、`/removeUser`、`/resetName`、`/vnc`
  - `/file`

## Thanks

- [dfc2333/LocalServer](https://github.com/dfc2333/LocalServer)
- [Guang233/CloudX](https://github.com/Guang233/CloudX)
