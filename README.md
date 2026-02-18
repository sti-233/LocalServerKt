# LocalServerKt

LocalServerKt 是使用 Ktor 对 [LocalServer](https://github.com/dfc2333/LocalServer) 的部分重写。

## 重写部分

### Chat

- `/login`
  - 重定向至登录页面
- `/chat`
  - 重定向至聊天页面
- `/setName?username={}`
  - 用户名为空时设置用户名
- `/history?targetuser={?}`
  - 获取聊天记录 用户名为空时获取群聊记录
- `/message`
  - websocket接口
- `/clients`
  - 查看所有在线websocket客户端IP

### Control

- `/start?p={}`
  - 启动服务
- `/stop?p={}`
  - 停止服务

## 拓展部分

### Music

- `/music`
  - 重定向至音乐页面
- `/searchMusic?keyword={}&offset={?}&limit={?}`
  - 搜索音乐
- `/getLyrics?id={}`
  - 获取歌词
- `/getMusicUrl?id={}&level={}`
  - 获取音乐链接

## 验证

### 实现

分为 `auth` 与 `control`

`auth` 验证

- 请求链接参数 `?p={?}` 是否存在且正确
- 服务状态是否为 `true`
- 请求方IP是否存在与 `userList.json` 中

失败时自动重定向至 `https://mx.j2inter.corn`

`control` 仅验证请求链接参数 `?p={?}` 是否存在且正确

### 接入页面

- `auth`
  - `/login`
  - `/chat`
  - `/music`
- `control`
  - `/start?p={}`
  - `/stop?p={}`

## Thanks

- [dfc2333/LocalServer](https://github.com/dfc2333/LocalServer)
- [Guang233/CloudX](https://github.com/Guang233/CloudX)
