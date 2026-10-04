# LocalServerKt 端点一览

> 基础地址：`http://<serverIp>/`。除特别标注外均为 GET；「鉴权」列：`auth` = form 域（白名单 IP 放行），`control` = basic 域（白名单 IP 放行），`—` = 无。

## Chat（聊天）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/chat` | auth | 跳转 `chat.html` 聊天页 |
| `/whoami` | auth | 当前客户端 IP 对应的用户名 |
| `/history` | — | 群聊记录；`?targetuser=<user>` 私聊记录，`&date=yyyy-MM-dd` 指定日期 |
| `/conversations` | — | `{today, names, last}`：侧边栏会话列表与各会话最后一条消息 |
| `/message` | — | WebSocket：聊天/私聊（`sendTo` 参数），图片 `ImageB64:` 前缀，2 分钟内可撤回 |
| `/clients` | — | 当前在线 WS 客户端列表 |
| `/wsSever` | — | 纯文本服务信息（typo 路由，沿用） |
| `/migrateImages` | — | 聊天记录图片消息迁移工具 |

## Control（控制）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/canControl` | — | 当前 IP 是否在 control 白名单，返回 `true`/`false`（前端据此隐藏控制端按钮） |
| `/log` | — | 最近日志文本 |
| `/userList` | — | 用户列表 JSON |
| `/start`、`/exit` | control | 切换 `Control.state`（只影响 auth 放行逻辑，不停服） |
| `/addUser?ip=&username=` | control | 添加白名单用户 |
| `/removeUser?ip=` | control | 移除白名单用户 |
| `/resetName?ip=&username=` | control | 改名 |
| `/vnc` | control | 重定向到 `http://<serverIp>:5901` |
| `/defaultFileDir` | control | 运行中 jar/classes 目录绝对路径 |
| `/help` | control | 本页 |

## Music（网易云）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/music` | auth | 跳转 `music.html` |
| `/searchMusic?keyword=&offset=&limit=` | — | 搜索歌曲（默认 limit=10） |
| `/getLyrics?id=` | — | 歌词（lrc/tlyric/romalrc/逐字 yrc） |
| `/getMusicUrl?id=&level=` | — | 播放/下载地址（level 如 lossless） |

## Video（B 站）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/video` | auth | 跳转推荐流页 `video/index.html` |
| `/recommend?fresh=&pageSize=` | — | 推荐流，`fresh` 递增翻页；风控时降级热门榜 |
| `/searchByType?keyword=&type=video\|user&page=` | — | 搜索（`/searchUser` 等价专用入口） |
| `/videoInfo?bvid=或aid=` | — | 视频详情（分P、UP主、统计） |
| `/videoStreamInfo?bvid=&cid=&qn=&tryLook=` | — | DASH 取流信息；`tryLook=1` 免登录 1080P |
| `/videoStream?url=&range=` | — | 视频流代理（仅 B 站 CDN 域名，透传 Range） |
| `/videoComments?aid=&page=&sort=` | — | 评论（3 热度/2 时间/1 混合，游标翻页） |
| `/userInfo?mid=` | — | UP 主资料（App 优先，Web 降级） |
| `/userSpace?mid=&cursor=&order=&pageSize=` | — | 空间投稿（App 游标翻页优先） |
| `/userDynamic?mid=&cursor=` | — | 空间动态（软限流自动重试） |

## Ai（AI 对话）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/ai` | auth | 跳转 `ai.html` |
| `/ai/chat` | — | SSE 流式对话（OpenAI 兼容上游转发） |
| `/ai/history` | — | 按 IP 的会话历史 |
| `/ai/clear`（POST） | — | 清空当前会话 |

## File（文件浏览，均 control）
| 端点 | 用法 |
|---|---|
| `/file` | 跳转 `file.html` |
| `/listFile?path=` | 目录条目（缺省列本地磁盘） |
| `/fileContent?path=` | 附件方式流式下载文件 |

## Terminal
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/terminal` | control | 跳转 `terminal.html`；WS 帧 `{"cmd":"..."}` → `{"out":"...===EXIT <code>"}` |

## Network（网络代理）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/`、`/client-lzysso/h5-sso` | auth | 跳转 `browser.html`（SSO 落点） |
| `/download?url=&inline=` | — | 下载代理；`inline=1` 时将 CSS/HTML 相对 URL 改写为绝对 URL |
| `/resources/...` | — | 静态资源（各页面 HTML/CSS/JS） |
