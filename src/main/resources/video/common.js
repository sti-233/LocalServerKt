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

/** B 站图片(封面/头像)有 Referer 限制，统一走 /download 代理 */
function imgUrl(u) {
    if (!u) return '';
    // 接口常返回 //i0.hdslb.com/... 这种协议相对地址
    let full = u.startsWith('//') ? 'https:' + u : u;
    return PROXY_SERVER + '/download?url=' + encodeURIComponent(full);
}

/** 播放地址需经 /videoStream 转发（带 Referer 且支持 Range） */
function streamUrl(u) {
    return PROXY_SERVER + '/videoStream?url=' + encodeURIComponent(u);
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
        `<img src="${esc(imgUrl(v.pic))}" referrerpolicy="no-referrer" loading="lazy" alt="">`).join('');

    el.innerHTML = `
        <img class="face" src="${esc(imgUrl(u.upic))}" referrerpolicy="no-referrer" alt="">
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
            <img class="avatar" src="${esc(imgUrl(m.avatar))}" referrerpolicy="no-referrer" alt="">
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
