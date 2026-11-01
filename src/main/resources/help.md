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
| `/img/{*}` | — | 聊天图片（`cache/picture/<sha256>.<ext>`，未命中回落旧 `cache/`） |
| `/pictures` | — | 图片列表：`?limit=`（默认 60，上限 200）→ `{dir,total,items:[{name,ref,size,mtime}]}`，按修改时间倒序；聊天页「缓存图片」面板用 |
| `/savePicture`（POST） | — | 保存图片：JSON `{"data":"data:image/...;base64,..."}` → 去重落盘 `cache/picture/`（全屏查看器的「保存」与「裁剪并保存」共用此端点） |
| `/migrateImages` | control | 聊天记录图片消息迁移（`ImageB64:` → `ImageRef:`） |
| `/migratePictures` | control | 图片目录迁移（旧 `cache/<sha256>.<ext>` → `cache/picture/`） |

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

## Timetable（课表）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/timetable` | auth | 跳转 `timetable.html`：只读课表，内容全部来自 `timetable.json` |
| `/timetable/now` | auth | 返回服务端"现在"：`{day, dayName, hour, minute, minutes, date, time, epochMs, week, satTrack}`，`day` 周序周一=0，`week` 教学周次，`satTrack` 周六该用 `A` 还是 `B`。页面据此高亮今天那列与当前那节，不依赖客户端时钟 |

页面**不保存个人课表**（不写 Cookie、不推 `/api/prefs`），改 `src/main/resources/timetable.json` 即更新所有人看到的内容。

两张表：
- 主表 = 周一~周五 + 周日。作息 `WEEK_BELL`：早自习 07:30-08:00；上午 5 节 08:00 起、每节 40 分钟课间 10 分钟，
  第二节后 09:30-09:50 大课间（后面再补 10 分钟课间，第三节正好 10:00），第五节 50 分钟到 12:30；
  午休 12:30-14:30；下午 4 节 14:30 起；17:40 放学后晚饭 17:40-18:30；
  晚自习 18:30-20:00 / 20:10-21:30 / 21:40-22:30。周日白天不上课、也没有第三节晚自习（格子标 `na` 灰底）。
- 周六单独一张表。作息 `SAT_BELL`：**没有大课间**，上午 5 节都是 40 分钟 + 10 分钟课间，08:00 开始 → 12:00 放学，
  午休 12:00-14:30，下午与平时相同，**没有晚自习**。周六上午有 A/B 两套课表，页内按钮切换：
  A 用 `cells` 的 `5-1`..`5-5`（数学 数学 物理 英语 英语），B 用 `satB` 的 `5-1`..`5-5`（语文 语文 自习 化学 生物）。
  **默认按教学周自动选**：第 1 周从 2026-08-31（周一）起算，单周 A、双周 B；
  周六表标题旁显示"第 N 周 · 单/双周"。手动点按钮后本次会话内以手动为准。

左侧竖列按时段纵向合并（`上午`/`下午`/`晚自习`），由 `segsOf()` 按 `part` 连续段算 `rowspan`。
格子键统一为 `"<周序>-<节次>"`（周序 0=周一 … 5=周六，节次 1..9）；晚自习不分学科、不占格子。
页面每 30 秒重算一次「今天那一列」与「当前这一节」的高亮。

**时间以服务端为准**：加载时先 `GET /timetable/now` 校时，记下 `epochMs` 与当时的本地单调时钟，
之后按"服务端时间 + 已流逝时长"推进；接口失败才退回本机 `new Date()`。
时分一律走 `getUTCDay()`/`getUTCHours()` 再手动加 8 小时（`SHANGHAI_OFFSET`），
所以浏览器时区设成什么都不影响高亮（作息本来就是北京时间）。
页面从后台切回前台（`visibilitychange`）会重新校一次时，应对笔记本休眠唤醒后时钟偏移。

**`timetable.json` 里还有 `bells: {week, sat}`** 两份作息（与页面硬编码的 `WEEK_BELL`/`SAT_BELL` 内容一致）。
服务端 `utils/Timetable.kt` 读这份判断"现在是否正课"，页面加载时也用 `adoptBells()` 覆盖自己那份
（JSON 缺失或格式不对就保留硬编码，保证不白屏）——改作息请改 JSON，页面和服务端同时生效。

**正课时段自动关闭服务**：`Server.kt` 起一个 30 秒轮询的协程调 `Control.followTimetable()`，
正课（上午 + 下午 9 节，科目为「体育」「自习」的不算）期间把 `Control.state` 置 false，
课间 / 早自习 / 晚自习 / 周日恢复 true。切换由 `ClassGate` 把门：只有"阶段"变化才动手，
所以手动 `/start`、`/exit` 能一直保持到下一次上下课，不会被轮询覆盖。
`state` 只影响 `auth` 的 `skipWhen`，`control` 白名单 IP 与 `?p=114514` 仍可进入。
