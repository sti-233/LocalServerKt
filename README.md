# LocalServerKt

LocalServerKt 是使用 Ktor 对 [LocalServer](https://github.com/dfc2333/LocalServer) 的部分重写，额外扩展了聊天、控制、音乐、视频搜索、AI 对话与浏览器等模块。服务器运行在 `0.0.0.0:80`。

## 端点

### Chat

- `/chat`
  - 重定向至聊天页面（`auth` 验证）
- `/whoami`
  - 返回当前请求 IP 对应的用户名（`auth` 验证）
- `/history?targetuser={?}`
  - 获取聊天记录；参数缺省时获取群聊记录，否则获取与指定用户的私聊记录；私聊对象不在用户列表时返回 400
- `/message`
  - WebSocket 接口，支持群聊与私聊（消息 `sendTo` 字段）；私聊对象不在用户列表时回 `err` 事件；消息 2 分钟内可撤回；图片消息以 `ImageB64:` 前缀 + data URL 发送（限 10MB），页面支持双击放大查看（滚轮/捏合缩放、拖拽平移）
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

- `/searchByType?keyword={}`
  - B 站视频搜索（WBI 签名请求）

### Ai

- `/ai`
  - 重定向至 AI 对话页面（`auth` 验证）
- `/ai/chat?text={}`
  - SSE 接口，代理上游模型 SSE 流；会话历史按 IP 保存在内存中
- `/ai/history`
  - 获取当前 IP 的会话历史
- `/ai/clear`
  - 清除当前 IP 的会话历史（POST）

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

## Thanks

- [dfc2333/LocalServer](https://github.com/dfc2333/LocalServer)
- [Guang233/CloudX](https://github.com/Guang233/CloudX)
