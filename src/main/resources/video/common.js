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
 * 图片预览（lightbox）：点击带 data-lb 的图片全屏查看，滚轮/双指捏合缩放、拖拽平移、Esc/✕ 关闭。
 * 挂在 document 上委托，任何位置动态渲染的图片（卡片/评论/动态）都能预览；
 * 预览时 stopPropagation，避免误触父级卡片的跳转 onclick。
 * 逻辑与 chat.html 的 lightbox 一致，保持各页预览体验统一。
 */
function initImgLightbox() {
    let lb = null; // 大图状态：{ img, scale, x, y, pinchDist, pinchMid, panning, panStart, panX0, panY0 }
    const lbPointers = new Map(); // 当前按住的指针（双指捏合用）

    function applyLbTransform() {
        if (!lb) return;
        lb.img.style.transform = `translate(${lb.x}px, ${lb.y}px) scale(${lb.scale})`;
    }

    function openLb(src) {
        lb = {
            img: document.querySelector('#biliLightbox img'),
            scale: 1, x: 0, y: 0,
            pinchDist: 0, pinchMid: null,
            panning: false, panStart: null, panX0: 0, panY0: 0
        };
        lb.img.src = src;
        document.getElementById('biliLightbox').classList.add('show');
        document.getElementById('lbScale').textContent = '100%';
    }

    function closeLb() {
        if (!lb) return;
        document.getElementById('biliLightbox').classList.remove('show');
        lb.img.src = ''; // 清掉 src，避免大图驻留内存
        lb = null;
        lbPointers.clear();
    }

    // 以 (cx, cy) 为缩放中心点，该点在视口中的位置保持不变
    function zoomLb(newScale, cx, cy) {
        if (!lb) return;
        newScale = Math.min(20, Math.max(1, newScale));
        const ratio = newScale / lb.scale;
        if (ratio === 1) return;
        lb.x += (cx - window.innerWidth / 2 - lb.x) * (1 - ratio);
        lb.y += (cy - window.innerHeight / 2 - lb.y) * (1 - ratio);
        lb.scale = newScale;
        applyLbTransform();
        clampLbPan();
        document.getElementById('lbScale').textContent = Math.round(lb.scale * 100) + '%';
    }

    // 把平移限制在合理范围，防止图片被拖出视野
    function clampLbPan() {
        if (!lb) return;
        const rect = lb.img.getBoundingClientRect();
        const ox = Math.max(0, (rect.width - window.innerWidth) / 2);
        const oy = Math.max(0, (rect.height - window.innerHeight) / 2);
        lb.x = Math.min(ox, Math.max(-ox, lb.x));
        lb.y = Math.min(oy, Math.max(-oy, lb.y));
    }

    // 遮罩与控件动态创建（4 个页面共用，不必每页写一份 DOM）
    if (!document.getElementById('biliLightbox')) {
        const d = document.createElement('div');
        d.id = 'biliLightbox';
        d.innerHTML = `
            <img alt="全屏查看">
            <button id="lbClose" title="关闭 (Esc)">✕</button>
            <div class="lb-controls">
                <button id="lbZoomIn" class="lb-btn" title="放大">+</button>
                <span id="lbScale">100%</span>
                <button id="lbZoomOut" class="lb-btn" title="缩小">−</button>
            </div>`;
        document.body.appendChild(d);
    }
    const lbEl = document.getElementById('biliLightbox');
    const lbImg = lbEl.querySelector('img');

    // 点击任意 data-lb 图片打开预览（委托，覆盖动态渲染内容）
    document.addEventListener('click', function (e) {
        const img = e.target.closest('img[data-lb]');
        if (!img) return;
        e.stopPropagation();
        openLb(img.currentSrc || img.src);
    }, true);

    // 滚轮缩放（以光标位置为中心）
    lbEl.addEventListener('wheel', function (e) {
        if (!lb || !lb.img.src) return;
        e.preventDefault();
        zoomLb(lb.scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), e.clientX, e.clientY);
    }, { passive: false });

    // 双击大图：放大 2.5 倍 / 已放大则还原
    lbImg.addEventListener('dblclick', function (e) {
        if (!lb) return;
        e.preventDefault();
        if (lb.scale > 1) {
            lb.scale = 1; lb.x = 0; lb.y = 0;
            applyLbTransform();
            document.getElementById('lbScale').textContent = '100%';
        } else {
            zoomLb(2.5, e.clientX, e.clientY);
        }
    });

    // 指针手势：单指/鼠标拖拽平移，双指捏合缩放（Pointer Events 兼容触屏与鼠标）
    lbEl.addEventListener('pointerdown', function (e) {
        // 控制条/关闭按钮上不开始手势，保留其点击行为
        if (!lb || !lb.img.src || e.target.closest('.lb-controls') || e.target.closest('#lbClose')) return;
        lbEl.setPointerCapture(e.pointerId);
        lbPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
        if (lbPointers.size === 1) {
            lb.panning = true;
            lb.panStart = { x: e.clientX, y: e.clientY };
            lb.panX0 = lb.x;
            lb.panY0 = lb.y;
        } else if (lbPointers.size === 2) {
            const [p1, p2] = [...lbPointers.values()];
            lb.panning = false;
            lb.pinchDist = Math.hypot(p1.x - p2.x, p1.y - p2.y);
            lb.pinchMid = { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 };
        }
    });

    lbEl.addEventListener('pointermove', function (e) {
        if (!lb || !lbPointers.has(e.pointerId)) return;
        const cur = { x: e.clientX, y: e.clientY };
        lbPointers.set(e.pointerId, cur);

        if (lbPointers.size === 2) {
            const [p1, p2] = [...lbPointers.values()];
            const newDist = Math.hypot(p1.x - p2.x, p1.y - p2.y);
            if (lb.pinchDist > 0) {
                zoomLb(lb.scale * (newDist / lb.pinchDist), lb.pinchMid.x, lb.pinchMid.y);
            }
            lb.pinchDist = newDist;
        } else if (lb.panning) {
            lb.x = lb.panX0 + (cur.x - lb.panStart.x);
            lb.y = lb.panY0 + (cur.y - lb.panStart.y);
            clampLbPan();
            applyLbTransform();
        }
    });

    function onLbPointerUp(e) {
        if (!lb) return;
        lbPointers.delete(e.pointerId);
        if (lbPointers.size === 1) {
            const [p] = [...lbPointers.values()];
            lb.panning = true;
            lb.panStart = p;
            lb.panX0 = lb.x;
            lb.panY0 = lb.y;
        } else if (lbPointers.size === 0) {
            lb.panning = false;
            lb.pinchDist = 0;
        }
    }
    lbEl.addEventListener('pointerup', onLbPointerUp);
    lbEl.addEventListener('pointercancel', onLbPointerUp);

    document.getElementById('lbZoomIn').addEventListener('click', function () {
        if (lb) zoomLb(lb.scale * 1.25, window.innerWidth / 2, window.innerHeight / 2);
    });
    document.getElementById('lbZoomOut').addEventListener('click', function () {
        if (lb) zoomLb(lb.scale / 1.25, window.innerWidth / 2, window.innerHeight / 2);
    });
    document.getElementById('lbClose').addEventListener('click', closeLb);

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeLb();
    });
}

// 页面加载完即启用（所有 B 站页面都引入 common.js）
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initImgLightbox);
} else {
    initImgLightbox();
}
