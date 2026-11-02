/**
 * B站视频模块共用工具。
 * 依赖 /resources/config.js 提供的 SERVER_IP。
 */

// 优先用当前页面的 origin：这样无论从 192.168.x.x、127.0.0.1 还是 localhost 访问都能正常请求，
// 避免 config.js 里写死的 SERVER_IP 与实际访问地址不一致时出现跨域失败。
// 仅当页面是通过 file:// 直接打开（无 origin）时，才回退到 config.js 的 SERVER_IP。
const PROXY_SERVER = (location.origin && location.origin.startsWith('http'))
    ? location.origin
    : ('http://' + SERVER_IP);

/**
 * B 站图片(封面/头像)有 Referer 限制，统一走 /biliImg 代理。
 * 与 /download 的区别：这个端点会把图落盘到 cache/bilibili/，同一张图只回源一次，
 * 首页来回滚动时不再重复下载；目录每次启动清空。
 */
function imgUrl(u) {
    if (!u) return '';
    // 接口常返回 //i0.hdslb.com/... 这种协议相对地址
    let full = u.startsWith('//') ? 'https:' + u : u;
    return PROXY_SERVER + '/biliImg?url=' + encodeURIComponent(full);
}

/** 播放地址需经 /videoStream 转发（带 Referer 且支持 Range） */
function streamUrl(u) {
    return PROXY_SERVER + '/videoStream?url=' + encodeURIComponent(u);
}

/**
 * 按语义参数拼取流地址 —— 优先用这个，不要用上面的 streamUrl(上游地址)。
 *
 * 原因：B 站上游地址带签名与 `deadline`（120 分钟），每次 /videoStreamInfo 拿到的
 * 都不一样。把上游地址塞进 URL，浏览器缓存就永远命中不了（换会话/刷新都要重下）。
 * 改用 bvid/cid/qn/codecid/kind/format 后同一路流的 URL 恒定，服务端内部换成真实
 * 地址并在过期前自动换新，响应带 `immutable`，缓存能长期复用。
 *
 * @param kind 'v' 视频流 / 'a' 音频流 / 'm' MP4 单流
 */
function streamUrlOf(bvid, cid, qn, codecid, kind, format) {
    var q = '/videoStream?bvid=' + encodeURIComponent(bvid)
        + '&cid=' + encodeURIComponent(cid)
        + '&qn=' + encodeURIComponent(qn || 0)
        + '&codecid=' + encodeURIComponent(codecid || 0)
        + '&kind=' + encodeURIComponent(kind)
        + '&format=' + encodeURIComponent(format || 'dash');
    return PROXY_SERVER + q;
}

/** 毫秒/秒级时间戳 -> yyyy-MM-dd HH:mm */
function fmtTime(sec) {
    if (!sec) return '-';
    const d = new Date(sec * 1000);
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 秒 -> mm:ss / hh:mm:ss */
function fmtDur(sec) {
    const s = Math.max(0, Math.floor(Number(sec) || 0));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
    const p = n => String(n).padStart(2, '0');
    return h > 0 ? `${h}:${p(m)}:${p(ss)}` : `${m}:${p(ss)}`;
}

/** 大数字 -> 万/亿 */
function fmtNum(n) {
    const v = Number(n) || 0;
    if (v >= 100000000) return (v / 100000000).toFixed(1) + '亿';
    if (v >= 10000) return (v / 10000).toFixed(1) + '万';
    return String(v);
}

/** 去掉接口标题里高亮用的 <em class="keyword"> 标签 */
function stripEm(s) {
    return String(s || '').replace(/<[^>]+>/g, '');
}

function esc(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 统一的 API 请求：把后端的 {error} 转成异常抛出，便于页面统一提示 */
async function api(path, params) {
    const qs = new URLSearchParams(params || {}).toString();
    const res = await fetch(`${PROXY_SERVER}${path}${qs ? '?' + qs : ''}`);
    const text = await res.text();
    let data = null;
    if (text) {
        try { data = JSON.parse(text); } catch (e) { data = null; }
    }
    if (!res.ok) {
        throw new Error((data && data.error) || `HTTP ${res.status}`);
    }
    if (data && data.error) throw new Error(data.error);
    if (data === null) throw new Error('响应不是合法 JSON');
    return data;
}

function showError(el, msg) {
    if (!el) return;
    el.innerHTML = `<div class="error">${esc(msg)}</div>`;
}

function showHint(el, msg) {
    if (!el) return;
    el.innerHTML = `<div class="hint">${esc(msg)}</div>`;
}

function showSpinner(el) {
    if (!el) return;
    el.innerHTML = `<div class="spinner"></div>`;
}

/**
 * 统一搜索：所有页面的搜索框都只接受关键词，一律走 /searchByType。
 * 这样用户不必判断自己输入的是 BV 号、mid 还是关键词，行为始终一致。
 */
function gotoSearch(keyword) {
    const kw = String(keyword || '').trim();
    if (!kw) return false;
    location.href = '/resources/video/search.html?keyword=' + encodeURIComponent(kw);
    return true;
}

/** 给指定输入框与按钮绑定"回车/点击即搜索"的行为 */
function bindSearchBox(inputId, btnId) {
    const input = document.getElementById(inputId);
    const btn = btnId ? document.getElementById(btnId) : null;
    if (!input) return;
    const run = () => gotoSearch(input.value);
    if (btn) btn.onclick = run;
    input.addEventListener('keydown', e => { if (e.key === 'Enter') run(); });
}

/* ------------------------------------------------------------------ *
 * 从播放页返回时保持原样
 *
 * 背景：首页/搜索页点卡片是 location.href 整页跳转，从播放页退回来时浏览器
 * 有两种表现 —— 命中 bfcache（页面不重新执行，最理想）或页面被重建（重新拉
 * 一次接口，列表就换了一批，看起来像"自己刷新了"）。后者取决于扩展、DevTools、
 * 页面用到的 API 等因素，前端控制不了。所以这里不依赖 bfcache：把上一次渲染
 * 用的数据存进会话快照，重建时直接照原样画出来。
 * ------------------------------------------------------------------ */

/**
 * 本次导航是不是"后退/前进"（即页面被重建的情况；命中 bfcache 时这段代码根本不执行）。
 * 只有后退时才恢复快照 —— 用户主动点链接进来应该看到新内容。
 */
function cameBack() {
    try {
        const nav = performance.getEntriesByType('navigation')[0];
        return !!nav && nav.type === 'back_forward';
    } catch (e) {
        return false;
    }
}

/**
 * 会话级快照，只活在这个标签页里，关掉就没了。
 * 不用 PageStore：列表动辄几十条，远超单个 Cookie 的 4KB。
 * 不用 localStorage：隐私模式下 setItem 会抛异常（见 CLAUDE.md 的约定）。
 */
const pageCache = {
    get: function (key) {
        try {
            const raw = sessionStorage.getItem(key);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    },
    set: function (key, value) {
        try {
            sessionStorage.setItem(key, JSON.stringify(value));
        } catch (e) {
            /* 存不下就算了，只是退回来会重新拉一次 */
        }
    }
};

/**
 * 生成一张视频卡片（封面 + 时长 + 标题 + UP + 播放量）。
 * 推荐流 / 搜索 / 空间投稿三处的数据结构不同，用 opts 适配。
 */
function videoCard(v, opts) {
    const o = opts || {};
    const el = document.createElement('div');
    el.className = 'vcard';
    const title = o.rawTitle ? v.title : stripEm(v.title);
    const play = o.play != null ? o.play : (v.stat && v.stat.view) || v.play || 0;
    const dur = o.duration != null ? o.duration : (v.duration != null ? v.duration : v.length);
    const durText = typeof dur === 'number' ? fmtDur(dur) : String(dur || '');
    const name = (v.owner && v.owner.name) || v.author || '';
    // 头像来源按页面不同：推荐/热门 owner.face、搜索 upic、空间投稿用页面传入的 opts.face
    const face = o.face != null ? o.face : ((v.owner && v.owner.face) || v.upic || '');

    el.innerHTML = `
        <div class="thumb">
            <img src="${esc(imgUrl(v.pic))}" alt="" referrerpolicy="no-referrer" loading="lazy">
            <span class="dur">${esc(durText)}</span>
        </div>
        <div class="body">
            <div class="title">${esc(title)}</div>
            <div class="meta">
                <span class="up">${face
                    ? `<img class="upface" src="${esc(imgUrl(face))}" referrerpolicy="no-referrer" alt="">`
                    : '👤'}${esc(name)}</span>
                <span>▶ ${fmtNum(play)}</span>
            </div>
        </div>`;
    el.onclick = () => {
        // 若结果里已带 cid，一并传过去：视频页可据此把
        // /videoInfo 与 /videoStreamInfo 并行发出，省掉一次串行等待。
        const cid = v.cid || v.first_cid;
        location.href = '/resources/video/watch.html?bvid=' + encodeURIComponent(v.bvid)
            + (cid ? '&cid=' + cid : '');
    };
    return el;
}

/**
 * 生成一张用户（UP主）卡片，用于用户搜索结果。
 * 字段来自 search_type=bili_user：uname/fans/videos/level/official_verify/usign/upic。
 */
function userCard(u) {
    const el = document.createElement('div');
    el.className = 'ucard';
    const official = u.official_verify && u.official_verify.type >= 0 ? (u.official_verify.desc || '认证') : '';
    // 认证信息可能很长（如"2025百大UP主、2025年度商业影响力奖UP主…"），截断展示
    const officialShort = official.length > 22 ? official.slice(0, 22) + '…' : official;
    const prev = (u.res || []).slice(0, 4).map(v =>
        `<img data-lb src="${esc(imgUrl(v.pic))}" referrerpolicy="no-referrer" loading="lazy" alt="">`).join('');

    el.innerHTML = `
        <img class="face" data-lb src="${esc(imgUrl(u.upic))}" referrerpolicy="no-referrer" alt="">
        <div class="ubody">
            <div class="uname">
                <span>${esc(u.uname)}</span>
                <span class="badge">Lv${u.level || 0}</span>
                ${u.is_senior_member === 1 ? '<span class="badge">硬核会员</span>' : ''}
                ${u.is_live === 1 ? '<span class="badge pink">直播中</span>' : ''}
                ${officialShort ? `<span class="badge">${esc(officialShort)}</span>` : ''}
            </div>
            <div class="usign">${esc(u.usign || '（这个人很神秘，什么都没有写）')}</div>
            <div class="ustat">
                <span>粉丝 <b>${fmtNum(u.fans)}</b></span>
                <span>投稿 <b>${fmtNum(u.videos)}</b></span>
                <span>UID ${u.mid}</span>
            </div>
            ${prev ? `<div class="uprev">${prev}</div>` : ''}
        </div>`;
    el.onclick = () => {
        location.href = '/resources/video/space.html?mid=' + u.mid;
    };
    return el;
}

/**
 * 可复用的评论区组件。
 * 视频页与搜索结果页都用它，避免逻辑重复两份。
 * 用法：initComments({ mount, aid, sort }) 返回可操作的对象。
 */
function createCommentSection(container, aid) {
    let page = 1;
    let cursorStack = [null];   // cursorStack[i] 是第 i+1 页要用的游标（第1页为 null）
    let hasNext = false;
    let sort = '3';

    const state = { sorting: false };

    /** 渲染单条评论（含楼中楼预览） */
    function commentEl(c, upMid) {
        const m = c.member || {};
        const el = document.createElement('div');
        el.className = 'cmt';
        const lv = (m.level_info && m.level_info.current_level) || 0;
        const isVip = m.vip && m.vip.vipStatus === 1;
        const official = m.official_verify && m.official_verify.type >= 0;
        const upFlag = upMid && String(c.mid) === String(upMid);

        // 图片评论（content.pictures，与 PiliPlus 同一字段）：正文下方显示缩略图，
        // 带 data-lb 交给全局 lightbox 点击预览。img_src 常是 //i0.hdslb.com/... 协议相对
        // 地址且 B 站图片有 Referer 防盗链，统一走 imgUrl() 的 /biliImg 代理。
        // 固定 96px 高度、宽度自适应，不同比例的图片也不把评论区撑乱。
        const pics = ((c.content && c.content.pictures) || [])
            .filter(p => p && p.img_src && p.img_src.length > 0);
        const picsHtml = pics.length
            ? `<div class="cmt-pics">${pics.map(p =>
                `<img data-lb src="${esc(imgUrl(p.img_src))}" referrerpolicy="no-referrer" loading="lazy" alt="" title="点击查看大图">`
              ).join('')}</div>`
            : '';

        const sub = (c.replies || []).map(r => {
            const rm = r.member || {};
            return `<div>
                <div class="chead">
                    <span class="uname">${esc(rm.uname)}</span>
                    <span class="time">${esc(fmtTime(r.ctime))}</span>
                </div>
                <div class="text">${esc((r.content && r.content.message) || '')}</div>
            </div>`;
        }).join('');

        el.innerHTML = `
            <img class="avatar" data-lb src="${esc(imgUrl(m.avatar))}" referrerpolicy="no-referrer" alt="">
            <div class="cbody">
                <div class="chead">
                    <span class="uname">${esc(m.uname)}</span>
                    <span class="badge">Lv${lv}</span>
                    ${isVip ? '<span class="badge pink">大会员</span>' : ''}
                    ${official ? `<span class="badge">${esc(m.official_verify.desc || '认证')}</span>` : ''}
                    ${upFlag ? '<span class="badge up">UP主</span>' : ''}
                    <span class="time">${esc(fmtTime(c.ctime))}</span>
                </div>
                <div class="text">${esc((c.content && c.content.message) || '')}</div>
                ${picsHtml}
                <div class="cfoot">
                    <span>👍 ${fmtNum(c.like)}</span>
                    ${c.count > 0 ? `<span>💬 ${c.count} 条回复</span>` : ''}
                </div>
                ${sub ? `<div class="sub-cmts">${sub}</div>` : ''}
            </div>`;
        return el;
    }

    /**
     * 加载评论。pn 为该页游标（第一页传 null）。
     * 服务端返回 cursor.next 作为下一页游标，cursor.is_end 表示到底。
     */
    async function load(pn, upMid) {
        if (state.sorting) return;
        state.sorting = true;
        showSpinner(container);
        try {
            const params = { aid, sort };
            if (pn != null) params.page = pn;
            const data = await api('/videoComments', params);

            const replies = data.replies || [];
            const hots = data.hots || [];
            const topReplies = data.top_replies || [];
            const cursor = data.cursor || {};
            const isFirst = pn == null;

            container.innerHTML = '';

            if (isFirst && topReplies.length) {
                const box = document.createElement('div');
                box.style.marginBottom = '6px';
                box.innerHTML = '<div class="small muted" style="margin-bottom:4px">📌 置顶</div>';
                topReplies.forEach(c => box.appendChild(commentEl(c, upMid)));
                container.appendChild(box);
            }

            if (isFirst && hots.length) {
                const seen = new Set(topReplies.map(c => c.rpid));
                const uniq = hots.filter(c => !seen.has(c.rpid));
                if (uniq.length) {
                    const box = document.createElement('div');
                    box.style.marginBottom = '6px';
                    box.innerHTML = '<div class="small muted" style="margin-bottom:4px">🔥 热门评论</div>';
                    uniq.forEach(c => box.appendChild(commentEl(c, upMid)));
                    container.appendChild(box);
                }
            }

            if (replies.length && isFirst && (hots.length || topReplies.length)) {
                const hd = document.createElement('div');
                hd.className = 'small muted';
                hd.style.margin = '10px 0 4px';
                hd.textContent = '全部评论';
                container.appendChild(hd);
            }

            if (!replies.length && !hots.length && !topReplies.length) {
                showHint(container, '这个视频还没有评论，或评论区已关闭');
                state.total = '';
                return;
            }

            replies.forEach(c => container.appendChild(commentEl(c, upMid)));

            state.total = cursor.all_count || 0;
            hasNext = !cursor.is_end;
            if (isFirst) { cursorStack = [null]; page = 1; }
            if (hasNext && cursor.next) cursorStack[page] = cursor.next;

            if (state.onCount) state.onCount(state.total);
            if (state.onPage) state.onPage(page, hasNext, page > 1);
        } catch (e) {
            showError(container, '获取评论失败：' + e.message);
        } finally {
            state.sorting = false;
        }
    }

    return {
        /** 从头加载（切换排序或首次进入时用） */
        reset: (upMid) => load(null, upMid),
        /** 第 1 页用 null；其余页用栈里记录的游标 */
        goPage: (p, upMid) => {
            if (p < 1) return;
            page = p;
            load(p === 1 ? null : cursorStack[p - 1], upMid);
        },
        getPage: () => page,
        setSort: (s) => { sort = String(s); },
        getTotal: () => state.total,
        onCount: (fn) => { state.onCount = fn; },
        onPage: (fn) => { state.onPage = fn; }
    };
}

/**
 * 图片预览（lightbox）：点击带 data-lb 的图片全屏查看。
 * 实现统一在 /resources/lib/lightbox.js（与聊天页共用同一份），这里只做挂载：
 * 单击预览、capture 阶段 stopPropagation 避免误触父级卡片的跳转 onclick。
 * 页面需在 head 里引入 lib/lightbox.css + lib/lightbox.js（见各页 <head>）。
 */
function initImgLightbox() {
    if (!window.Lightbox) return; // 未引入 lib/lightbox.js（例如旧页面）时静默跳过
    return Lightbox.mount({ click: 'img[data-lb]' });
}

/**
 * 正课时段视频模块被关闭（服务端 VideoGate），各页轮询 /videoStatus 后自动跳去课表。
 *
 * 为什么要在前端轮询：服务端只能拒绝"新请求"，已经在播的页面（尤其是 watch.html 里
 * 缓冲好的视频）不会自己停。所以每页都挂着这个轮询，一旦看到 open:false 就整页跳走，
 * 顺带把播放停掉（整页跳转本身会终止媒体加载）。
 *
 * 20 秒一次：正课开始最多晚 20 秒被踢出去，对服务器负载可以忽略。
 * 只在 http(s) 下轮询（file:// 打开时没有服务端，会一直失败刷日志）。
 */
const VIDEO_STATUS_URL = '/videoStatus';
const VIDEO_STATUS_INTERVAL = 20000;

function checkVideoGate() {
    if (!(location.origin && location.origin.startsWith('http'))) return;
    fetch(PROXY_SERVER + VIDEO_STATUS_URL, { cache: 'no-store' })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (o) {
            if (o && o.open === false) {
                // replace 而不是 assign：别把"被踢出去"记进历史，用户按后退不会再回来
                location.replace('/resources/timetable.html');
            }
        })
        .catch(function () { /* 网络抖一下不算关闭，下次再查 */ });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', checkVideoGate);
} else {
    checkVideoGate();
}
setInterval(checkVideoGate, VIDEO_STATUS_INTERVAL);

// 切回标签页立刻查一次，别让用户盯着一个已经该关闭的页面
document.addEventListener('visibilitychange', function () {
    if (!document.hidden) checkVideoGate();
});

// 页面加载完即启用（所有 B 站页面都引入 common.js）
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initImgLightbox);
} else {
    initImgLightbox();
}
