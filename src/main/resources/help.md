# LocalServerKt 端点一览

> 基础地址：`http://<serverIp>/`。除特别标注外均为 GET；「鉴权」列：`auth` = form 域（白名单 IP 放行），`control` = basic 域（白名单 IP 放行），`—` = 无。

## Chat（聊天）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/chat` | auth | 跳转 `chat.html` 聊天页 |
| `/whoami` | auth | 当前客户端 IP 对应的用户名 |
| `/history` | auth | 群聊记录；`?targetuser=<user>` 私聊记录，`&date=yyyy-MM-dd` 指定日期 |
| `/conversations` | auth | `{today, names, last}`：侧边栏会话列表与各会话最后一条消息；`last` 每条为 `{time, by, at}`，`at` 是该条 @ 到的用户名数组（只回名字、不回正文），供侧边栏把红点升级成「@」胶囊 |
| `/message` | auth | WebSocket：聊天/私聊（`sendTo` 参数），图片 `ImageB64:` 前缀，2 分钟内可撤回；带 `reply:{name,time,text}` 表示回复某条消息（服务端把引用摘要截到 200 字后随消息落盘并广播） |
| `/clients` | — | 当前在线 WS 客户端列表 |
| `/wsSever` | — | 纯文本服务信息（typo 路由，沿用） |
| `/img/{*}` | auth | 聊天图片（按文件名在 `cache/picture/<用户名>/` 各子目录 → `cache/picture/` 根 → 旧 `cache/` 根依次查找） |
| `/pictures` | auth | 我的图片列表（**只列自己目录下的**）：`?limit=`（默认 60，上限 200）→ `{dir,user,total,items:[{name,ref,size,mtime}]}`，按修改时间倒序；聊天页「缓存图片」面板用 |
| `/savePicture`（POST） | auth | 保存图片：JSON `{"data":"data:image/...;base64,..."}` → 去重落盘 `cache/picture/<当前 IP 的用户名>/`（全屏查看器的「保存」与「裁剪并保存」共用此端点；返回 `{ok,ref,name,dir,user,existed}`） |
| `/deletePicture`（POST） | auth | 删除我的图片：JSON `{"name":"<sha256>.<ext>"}` → 只删 `cache/picture/<当前 IP 的用户名>/` 里的那一份（同一内容别人也存过则互不影响）；名字不合法 400、文件不存在 404；返回 `{ok,name,user}`，删失败时带 `error`。聊天页「缓存图片」面板的「删除」用 |
| `/members` | auth | `{me, names}`：`names` 是全部已登记用户名（排序去重），供聊天页 @ 补全与 `@名字` 高亮。**只回名字不回 IP** |
| `/avatars` | auth | `{me, items}`：`me` 是当前 IP 的用户名，`items` 是全站「用户名 → 图片地址」表，供聊天页按 `msg.name` 配头像 |
| `/setAvatar`（POST） | auth | JSON `{"name":"<sha256>.<ext>"}`：把图库里的某张图设为头像（**只存引用不复制字节**）；名字不合法 400，图不在自己目录里 404；**GIF 不受支持**，`.gif` 一律 400 并在错误信息里点明原因（名字合法、图也在库里，只是头像位太小，动图看不清还白解码）；返回 `{ok,user,ref}` |
| `/deleteAvatar`（POST） | auth | 恢复默认头像（首字 + 按名字取色），返回 `{ok,user}` |
| `/migrateImages` | control | 聊天记录图片消息迁移（`ImageB64:` → `ImageRef:`） |
| `/migratePictures` | control | 图片目录迁移（旧 `cache/<sha256>.<ext>` → `cache/picture/`） |

## Control（控制）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/canControl` | — | 当前 IP 是否在 control 白名单，返回 `true`/`false`（前端据此隐藏控制端按钮） |
| `/log` | — | 最近日志文本 |
| `/userList` | — | 用户列表 JSON |
| `/start`、`/exit` | control | 切换 `Control.state`（只影响 auth 放行逻辑，不停服） |
| `/openVideo`、`/closeVideo` | control | 临时放行 / 收回视频模块，**只对当节课有效**（下节课的上下课切换会自动收回） |
| `/addUser?ip=&username=` | control | 添加白名单用户（用户名不能含 `-` 与控制字符） |
| `/removeUser?ip=` | control | 移除白名单用户 |
| `/resetName?ip=&username=` | control | 改名，并连带迁移旧名下的聊天记录、图片、AI 会话与页面配置（回 `{users, migrated}`）；用户名不能含 `-` 与控制字符，也不能改成别人正在用的名字 |
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
| `/videoStream?bvid=&cid=&qn=&codecid=&kind=&format=` | — | 视频流代理（透传 Range）。语义参数由服务端换成真实签名地址，同一路流 URL 恒定、可长缓存；`kind` = `v` 视频 / `a` 音频 / `m` MP4 单流，`format` = `dash`/`mp4` |
| `/videoStream?url=` | — | 旧式直传上游地址（保留兼容，每次地址都不同、缓存复用不了）。两种入参都只放行 B 站 CDN 域名；非 control 客户端限速 300 KB/s |
| `/biliImg?url=` | — | 图片代理（任意 http(s) 图片地址）；落盘 `cache/bilibili/`，同一 URL 只回源一次，目录每次启动清空 |
| `/videoComments?aid=&page=&sort=` | — | 评论（3 热度/2 时间/1 混合，游标翻页） |
| `/userInfo?mid=` | — | UP 主资料（App 优先，Web 降级） |
| `/userSpace?mid=&cursor=&order=&pageSize=` | — | 空间投稿（App 游标翻页优先） |
| `/userDynamic?mid=&cursor=` | — | 空间动态（软限流自动重试） |
| `/videoStatus` | — | 视频模块是否开放，返回 `{"open":true\|false}`（`no-store`）。正课时段关闭视频模块，前端每 20 秒轮询它，见下方课表段 |

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

## Play（小游戏，谁是卧底 / 狼人杀）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/play` | auth | 跳转 `play/index.html`：游戏选择 + 房间列表 |
| `/play/undercover` | auth | 跳转 `play/undercover.html`：「谁是卧底」房间 |
| `/play/werewolf` | auth | 跳转 `play/werewolf.html`：「狼人杀」房间 |
| `/play/ws` | auth | 实时游戏的唯一 WebSocket；单行 JSON 一帧，房间列表也走这里（`{"type":"lobby"}`） |

房间状态**全在内存里**，进程重启即全部消失 —— 一局游戏本来就只有几十分钟，没有值得落盘的东西。

客户端 → 服务端：`{"type":"create","game":"undercover"\|"werewolf","name"?}`、`{"type":"join","room":"ABCD","name"?}`、
`{"type":"resume","room","seat"}`（断线重连）、`{"type":"lobby","game"?}`、`{"type":"action","action","data":{}}`、
`{"type":"chat","text","scope":"public"\|"wolves"}`。

服务端 → 客户端：`{"type":"joined",...}`、`{"type":"state","room","game","seat","state":{}}`、`{"type":"roomList","rooms":[...]}`、`{"type":"err","text"}`。

要点：

- **房号 4 位**，字母表去掉 `0/O/1/I`（念出来不会混）；**座位号 8 位**，一个座位同时只有一条连接，新连接顶掉旧的，所以换设备/换标签页请用 `resume`。
- **房主 = 首个入座者**，只有房主能 `start`／`config`／`startVote`／`resolveVote`／`resolveNight`／`breakTie`／`endGame`。房主出局后**自动转移**给下一个存活玩家（`GameEngine.ensureHost`），否则最容易被针对的位置一出局整局就卡死。
- **`state` 按座位裁剪后再发**：`broadcast` 对每个座位单独调 `engine.view(room, seat)`，不走「生成一份广播给所有人」—— 这是唯一防泄漏的出口。
- 「谁是卧底」：**所有平民共用一个词、所有卧底共用一个词**（每人发不同的词会让「猜谁和大家不一样」失去判据）。**`myRole` 在进行中恒为 `null`**，只有自己出局或 `finished` 才给 —— 一开局就知道自己是卧底的话这游戏没得玩。`players[]` 里**永远没有 `word` 字段**，谁都看不到别人的词，出局的也一样。
- 出局日志形如「小明被放逐，身份：卧底」—— **只公布身份，不公布词**。
- 房主可在 `waiting` 期自定义词组（`config{group}`）：按 `[,，、\s]+` 切分、去重，2..6 个词、每词不超过 16 字符；`group:""` 恢复内置随机词组。自定义词组与卧底人数**跨局保留**在房间上。
- 内置词库**只在服务端**（`PlayUndercover.BUILTIN_GROUPS`）—— 放进 `resources/` 就等于把答案挂到 `/resources` 上。
- 「狼人杀」5 种角色（狼人/预言家/女巫/守卫/平民），**4 ≤ 人数 ≤ 12**：4 人=狼1预1女1民1（无守卫）；5 人各 1；6-8 人=狼2预1女1守1+民（n-5）；9-11 人=狼3；12 人=狼4+民5。
- 夜间**按顺序轮流行动**：守卫 → 狼人 → 女巫 → 预言家，每一步收齐才轮到下一个，最后一步交完**自动结算**（房主的「强制结算夜晚」只是兜底）。顺序不能乱：女巫必须排在狼人之后，她才知道今晚刀口是谁（`state.wolfVictim` 只对女巫、只在夜里下发）；守卫排最前，守护才先于狼刀生效。轮到谁由 `state.nightStep` 给出，没轮到自己时面板会显示「现在轮到 X 行动」。
- 夜间结算顺序：守卫守护 → 狼人袭击（被守护则免刀）→ 女巫解药（未被守护、女巫存活、有解药、目标不是女巫自己）→ 女巫毒药（**无视守护**）→ 天亮**只公布死亡名单、不公布身份** → 清空上一夜守卫目标 → 判胜负。
- 女巫不能自救、同夜不能既救又毒同一人；守卫不能守自己、不能连续两晚守同一个人。
- 胜负：卧底局「卧底全出局 = 平民胜」「存活卧底 ≥ 存活平民 = 卧底胜」；狼人杀「狼人全灭 = 好人胜」「存活狼 ≥ 存活好人 = 狼人胜」。

## Network（网络代理）
| 端点 | 鉴权 | 用法 |
|---|---|---|
| `/`、`/client-lzysso/h5-sso` | auth | 跳转 `browser.html`（SSO 落点） |
| `/download?url=&inline=` | — | 下载代理；`inline=1` 时将 CSS/HTML 相对 URL 改写为绝对 URL |
| `/resources/...` | — | 静态资源（各页面 HTML/CSS/JS） |
| `/environment` | auth | 跳转 `environment.html`：浏览器能力检测页 |
| `/api/assets` | — | 列出 `/resources` 下全部 `.js`/`.css`（`{assets:["lib/theme.js",…]}`），供设置页「强制刷新」逐个回源；`no-store` |

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

**正课时段自动关闭视频模块**：`Server.kt` 起一个 30 秒轮询的协程调 `VideoGate.followTimetable()`，
正课（上午 + 下午 9 节，科目为「体育」「自习」的不算）期间把 `VideoGate.open` 置 false，
课间 / 早自习 / 晚自习 / 周日恢复 true。切换由 `ClassGate` 把门：只有"阶段"变化才动手。
**只关视频，不关整个服务器** —— 聊天、课表、文件、音乐照常可用；`Control.state`（`/start`、`/exit`）
仍是全局手动开关，课表不再碰它。

关闭期间：`/video` 及所有视频接口返回 403 JSON（`{"error":"现在是正课时间，视频模块已关闭"}`），
`/resources/video/*` 整页 302 到课表页。**已经在播的页面**靠前端自救：`common.js` 每 20 秒（以及切回
标签页时）请求 `GET /videoStatus`（永远可达、`no-store`，返回 `{"open":true|false}`），看到 `false`
就 `location.replace('/resources/timetable.html')` 整页跳走，顺带终止媒体加载。

**control 用户不受限制**：`Control.allowed()` 白名单里的 IP（与 `/canControl`、取流限速是同一份）
即使在正课期间也能正常打开视频页与接口，`GET /videoStatus` 对他们恒回 `{"open":true}`，
免得页面被自己的轮询踢走。判据集中在 `VideoGate.availableTo()`，拦截器与状态接口共用它。

**临时给普通用户放行**：control 用户调 `GET /openVideo` 可以把视频模块对所有人放开，
`GET /closeVideo` 立刻收回。放行**只到这节课结束** —— 课表阶段一变（上下课切换）
`VideoGate.followTimetable()` 就自动清掉，避免一次放行忘关、之后一直开着。
`/openVideo`、`/closeVideo` 都走 `control` 鉴权，返回纯文本（如 `Video opened for this period`）。

## Environment（浏览器能力检测）

`/environment`（auth）跳转 `environment.html`。这一页用**当前浏览器**真跑一批能力探测，
分组展示「✓ 可用 / ✗ 不可用 / · 信息」，并给出可一键复制的 JSON 结果（`{generatedAt, url, userAgent, summary, groups}`）。

**每行都有一个「是否影响本项目」标签**，这是读结果的关键：`本项目在用` / `约定避开` / `无关`。
红叉多不代表有问题 —— Chrome 80 缺失的 `:has()`、`dvh`、`replaceAll` 之类本项目根本没用到，
恰好是 CLAUDE.md 里写明"不准用"的（如 flex gap），报 ✗ 完全是预期的。真正要处理的只有
顶栏那句「影响本项目且不可用：n 项」，它统计的正是 `本项目在用` 且 ✗ 的项；这些行会额外标红加粗。
JSON 里对应 `summary.depFail`，每项还有 `use` 字段。

检测项分八组，都是本项目实际依赖、或升级浏览器时容易踩到的能力：

- **存储**：Cookie 读写、localStorage、sessionStorage、IndexedDB、BroadcastChannel 收发、
  Cache Storage、存储配额估算。隐私模式下 localStorage / IndexedDB 常常直接抛错，
  这也是 `page-store.js` 不用 localStorage 的原因。IndexedDB 现在**是本项目在用的**
  （`lib/idb-store.js`），不可用时那些缓存全部静默退化为内存、页面功能不受影响，只是刷新后不再保留。
- **缓存与图片**：这一组回答同一个问题 —— 同一张图第二次还要不要重新下载。
  探测图是随包发布的 `/resources/lib/img/probe.png`（96×96、约 25KB，故意做成压不动的噪声，
  体积够大才量得准）。判据是 Resource Timing 的 `transferSize`：**0 表示这次没有发生网络传输**；
  别用 Network 面板的 `fromDiskCache`，CDP 对子资源经常报 false 但服务端计数不涨。
  八项分别是：① 图片二次加载走缓存（先 `fetch(cache:'reload')` 强制回源一次，再用 `<img>` 普通加载
  同一地址，第二次传输 0 字节才算命中）；② 服务端下发的缓存头（读 `Cache-Control`/`Last-Modified`/`ETag`，
  有 `max-age>=1` 且没有 `no-store`/`no-cache` 才算强缓存）；③ 条件请求回 304（拿 `Last-Modified`
  作 `If-Modified-Since` 再请求一次，期望 304 且无响应体）；④ `fetch(cache:'force-cache')`；
  ⑤ `HTMLImageElement.decode()`；⑥ `loading`/`decoding` 属性（聊天图片列表靠它懒加载省内存）；
  ⑦ `caches.match()`；⑧ sessionStorage 能否写下 256KB 快照（video 页用它存标签页 ID，把
  IndexedDB 快照按标签页隔开）。
  这一组在 DevTools 勾了 Disable cache 时，①④ 会如实报 ✗ —— 那不是页面坏了，是浏览器在按
  `no-store` 处理一切请求。`/resources` 下的图片与 JS/CSS 都走 `max-age=604800, must-revalidate`，
  所以 ①②③ 三项在正常情况下都应是 ✓。改完 JS/CSS 若担心浏览器还在用旧的，用设置页的
  「强制刷新 JS/CSS」按钮（`fetch(cache:'reload')` 强制回源，不受 max-age 影响）。
- **网络**：fetch、XMLHttpRequest、WebSocket、EventSource、ReadableStream、AbortController、
  sendBeacon、`performance` 的 `navigation.type`（video 页据此判断是不是后退回来的，决定要不要复用快照）、
  `history.replaceState`、`scrollTo({behavior})`。
- **媒体与图形**：Canvas 2D、`canvas.toDataURL`（真画一个 2×2 红块再导出 PNG）、`playbackRate` 可写、
  MediaSource、H.264 / HEVC / AV1 的 `canPlayType`、Web Audio、FileReader、`URL.createObjectURL`、全屏 API。
- **输入与交互**：PointerEvent、`setPointerCapture`、TouchEvent、`pointer: coarse`、WheelEvent。
- **CSS**：自定义属性、**flex 容器的 gap**（真建一个 `display:flex;gap:10px` 的盒子量两子盒间距 ——
  只查 `CSS.supports('gap','1px')` 不可靠，仅支持 grid gap 的浏览器也返回 true）、inset、aspect-ratio、
  accent-color、backdrop-filter、color-scheme、dvh、clamp/min/max、sticky、overscroll-behavior、
  `:is()/:where()`、`:has()`、`:focus-visible`、Grid、scroll-behavior、`env(safe-area-inset-*)`。
- **JavaScript 运行时**：Promise、class、可选链 `?.` 与 `??`（Chrome 80 是第一版支持的稳定版）、
  replaceAll、`Array.prototype.at`、Object.hasOwn、structuredClone、WeakRef、Intl.Segmenter、globalThis、
  queueMicrotask、Intersection/ResizeObserver、TextDecoder/TextEncoder。语法类用 `new Function(src)` 探。
- **运行环境**：协议与主机、`isSecureContext`、`navigator.clipboard`、`document.execCommand('copy')`、
  Service Worker、visibilityState、`prefers-color-scheme`、`prefers-reduced-motion`、`navigator.onLine`、
  `navigator.userAgentData`、`devicePixelRatio`。

页面本身刻意用最保守的写法（ES5、不用 flex gap / inset / `:has()`，CSS 用 `> * + * { margin-left }`）：
它要告诉用户"当前浏览器缺什么"，自己用的新语法越多，越可能压根跑不起来。
异步项有 2.5 秒超时兜底，标为「· 信息」并注明"超时未响应（可能被浏览器限制）"。
复制优先 `navigator.clipboard`，非安全上下文（局域网 http）退回去 `execCommand('copy')`。

**结果会留档**：每轮检测结束把报告写进 IndexedDB（`kv` 表，键 `env_report`），页面顶部随即显示
「上次检测：<时间>（<地址>）」，并逐项对比上一次的结论 —— 只在"明确可用 ↔ 明确不可用"之间比，
「· 信息」这类未知项不参与，避免噪声。新坏的项会用红色列出条数与名称（新的不可用比旧的不可用更值得注意），
修好的项另起一行。想重置基准就点"重新检测"跑两次，或清掉该键。

## 浏览器端缓存（IndexedDB）

前端有一层纯本地的缓存，封装在 `lib/idb-store.js`（挂 `window.IdbStore`），库名 `localserver`、
版本 2，五张表：`kv`（通用键值，带可选过期时间）、`chatHistory`（按会话+日期存聊天记录）、
`aiConvs`（按会话 id 存 AI 对话）、`progress`（按 `bvid|cid` 存播放进度）、
`imgBlobs`（按内容哈希文件名存图片字节）。

**它不是数据权威**：页面一律"先画本地副本、再拉服务端覆盖"，服务端永远赢；接口失败时保留本地副本
而不是白屏，用户至少还能看到上次的内容。所有调用都做了兜底 —— 隐私模式下 `indexedDB.open`
可能既不 success 也不 error（卡在 onblocked），故有 1.5 秒超时；超时或报错就整体退化为内存 Map，
页面照常工作，只是刷新后不保留。**任何业务逻辑都不能依赖"缓存里一定有"**。

| 页面 | 缓存内容 | 键 | 过期 / 上限 |
|---|---|---|---|
| `chat.html` | 每个会话（群聊某天 / 某个私聊对象）的消息列表，**含图片消息**（存的是 `ImageRef:/img/<sha256>.<ext>` 这行引用本身，图片字节在 `imgBlobs`） | `chatHistory`，`<targetuser 或空>|<yyyy-MM-dd>` | 按 `savedAt` 最多留 60 个会话 |
| `chat.html` | 「缓存图片」面板的列表 + 所有聊天图/缩略图/**头像**的字节 | `kv:pictures`、`imgBlobs`，键就是 `<sha256>.<ext>` | 列表无 TTL（每次打开都用服务端那份覆盖）；字节永不过期（名字即内容），最多留 240 张，单张 >6MB 不存。头像与消息图共用这一份：`avatarNode()` 走同一个 `paintImg()`，命中时连 `/img` 请求都不发，没命中才回源并补存 |
| `ai.html` | 会话列表、每个会话的消息 | `kv:ai_conv_list`、`aiConvs:<convId>` | 列表 24 小时；消息最多留 40 个会话；**匿名会话不缓存** |
| `video/watch.html` | 播放进度（`t`/总时长/标题/封面/分P） | `progress`，`<bvid>|<cid>` | 看完（距结尾 <30 秒）或不足 5 秒不记；进页面若有记录且在 5 秒~结尾 30 秒之间，播放器左下浮出「上次看到 xx:xx / 继续播放 / 从头开始」 |
| `video/index.html`、`search.html` | 推荐流 / 搜索结果快照 | `kv:video_feed@<tabId>`、`kv:video_search@<tabId>` | 30 分钟；按标签页隔离，后退回来直接照原样重画、不重新请求 |
| `music.html` | 歌词 | `kv:lyrics:<songId>` | 30 天（歌词基本不变）；**歌曲直链不缓存**，签名会过期 |
| `environment.html` | 上一次的检测报告 | `kv:env_report` | 无（只留一份，用来对比） |

几个容易踩的点：

- **video 快照的 `@<tabId>`**：早期用 sessionStorage，天然按标签页隔离。换成 IndexedDB 后是
  全源共享的，两个标签页先后进视频页会互相覆盖对方的后退快照，故把标签页 ID 拼进键里
  （标签页 ID 本身仍存 sessionStorage —— 它天生就是"这个标签页"的语义）。列表快照只用于后退恢复，
  所以设了 30 分钟 TTL，过期就重新请求。
- **`kv` 的过期是惰性的**：`kvGet` 读到 `expiresAt` 已过就顺手删掉并返回 null，没有定时清理线程；
  页面加载时 `IdbStore.kvPrune()` 会扫一遍清掉过期项。
- **配额**：Chrome 的清理是整库而不是按表，所以每张表都有条数上限，写入后按 `savedAt` 倒序裁掉尾巴
  （聊天 60 个会话、AI 40 个、图片 240 张）。`IdbStore.estimate()` 能读 `navigator.storage.estimate()`。
- **消息里存引用、`imgBlobs` 里存字节**：存档里的图片消息早已只是 `ImageRef:/img/<sha256>.<ext>`
  这一行短字符串（服务端把 base64 落盘并改写），所以 `chatHistory` 不会因为图片变胖。
  图片本身另存一份到 `imgBlobs`——**键就是那个哈希文件名**，同名必然同字节，于是永远不用判断过期，
  只按张数淘汰。命中时直接给 `URL.createObjectURL()`，连 HTTP 请求都不发；没命中才走 `/img/`，
  并在 `load` 事件里补存一份（此时字节已在浏览器 HTTP 缓存里，补存基本不花流量）。
  上限 240 张 / 单张 6MB：再大就不值得占配额，宁可每次都走 `/img/` 的 immutable 缓存。
- **`imgBlobs` 与 HTTP 缓存是两层，互补而非重复**：`/img` 的 `immutable` 只管"这次浏览别重下"，
  清站点数据、浏览器配额回收、或换个浏览器就没了；`imgBlobs` 是应用自己的持久层，能一起带走的是
  同一套降级语义——不可用时全部退回 HTTP 缓存，页面表现完全一致，只是首屏多几个请求。
- **删图要三处一起动**：`POST /deletePicture` 成功后，前端同时删 `imgBlobs` 的字节、内存里的
  blob URL、以及 `kv:pictures` 列表里的那一条。少删任何一处都会导致"下次打开面板先闪出已删的图"。
- **老存档的 `ImageB64:` 不写缓存**：没跑过 `/migrateImages` 的会话里，图片是内联的 data URL、
  一条就能有几 MB，这种会话由 `hasLegacyImage()` 判定后整个跳过 —— IDB 配额整库共用，
  为一份静态旧存档冒被清库的风险不值得。
- **多标签页**：库是全源共享的，`aiConvs` / `progress` 这类按 id 存的表天然不冲突；`chatHistory`
  同键写入是后写覆盖，各标签页看到的是最后一次保存的版本，功能上可接受（拉到的服务端数据才是准的）。
- 主题（`ls_theme`）与 `page-store.js` 的页面配置**没有**走 IndexedDB：前者必须首屏同步读，
  后者是几百字节的小对象，用 Cookie 反而更简单可靠。
