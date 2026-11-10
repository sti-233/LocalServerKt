/**
 * Play 游戏模块共用脚本：WebSocket 连接、重连、DOM 小工具、房间列表与玩家列表渲染。
 *
 * 三个页面（index / undercover / werewolf）都只引这一份。
 * **本文件不含任何游戏规则判定**——一切以服务端下发的 state 为准，
 * 前端只负责把 state 画出来、按用户点击发 action。
 *
 * WS 地址必须跟着当前页面的 origin 走（https 用 wss://）：
 * 写死服务器 IP 时用域名/反代进来会对不上，https 页面下还会被当混合内容拦掉。
 *
 * 兼容 Chrome 80：不用可选链之外的 ES2020+ 语法（?. 与 ?? 可用），DOM 用 createElement 拼。
 */
(function (global) {
    'use strict';

    var WS_PATH = '/play/ws';
    var SEAT_PREFIX = 'ls_play_seat_';
    var LAST_ROOM_KEY = 'ls_play_room';
    var BACKOFF_MAX = 15000;

    /* ================= 连接状态 ================= */
    var ws = null;
    var opts = null;
    var reconnectTimer = null;
    var backoff = 1000;
    var lastStatus = '';
    var memSeats = {};   // sessionStorage 不可用时的退化存储（隐私模式等）

    function wsUrl() {
        return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + WS_PATH;
    }

    function setStatus(s) {
        if (s === lastStatus) return;
        lastStatus = s;
        if (opts && opts.onStatus) {
            try { opts.onStatus(s); } catch (e) { /* 回调异常不能拖垮连接 */ }
        }
    }

    function fire(name, arg1, arg2) {
        if (!opts || typeof opts[name] !== 'function') return;
        try { opts[name](arg1, arg2); } catch (e) { reportErr(e); }
    }

    function reportErr(e) {
        if (global.console && console.error) console.error('[play]', e);
    }

    function open() {
        if (ws && (ws.readyState === 0 || ws.readyState === 1)) return; // CONNECTING / OPEN
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }

        setStatus('wait');

        var sock;
        try {
            sock = new WebSocket(wsUrl());
        } catch (e) {
            scheduleReconnect();
            return;
        }
        ws = sock;

        sock.onopen = function () {
            if (ws !== sock) return;
            backoff = 1000;
            setStatus('ok');
            fire('onOpen');
        };

        sock.onmessage = function (ev) {
            if (ws !== sock) return;
            handleFrame(ev.data);
        };

        sock.onclose = function () {
            if (ws !== sock) return;
            ws = null;
            setStatus('down');
            scheduleReconnect();
        };

        // onerror 之后一定会有 onclose，这里不重复排重连
        sock.onerror = function () { /* noop */ };
    }

    function scheduleReconnect() {
        if (reconnectTimer) clearTimeout(reconnectTimer);
        var delay = backoff;
        reconnectTimer = setTimeout(function () {
            reconnectTimer = null;
            open();
        }, delay);
        backoff = Math.min(backoff * 2, BACKOFF_MAX);
    }

    function handleFrame(raw) {
        var msg;
        try { msg = JSON.parse(raw); } catch (e) { return; }
        if (!msg || !msg.type) return;

        if (msg.type === 'joined') {
            fire('onJoined', msg);
        } else if (msg.type === 'state') {
            fire('onState', msg);
        } else if (msg.type === 'roomList') {
            fire('onRoomList', msg.rooms || []);
        } else if (msg.type === 'closed') {
            // 房主解散了房间：先清掉本页存的座位，再让页面决定回哪
            if (msg.room) clearSeat(msg.room);
            fire('onClosed', msg);
        } else if (msg.type === 'err') {
            fire('onError', msg.text || '出错了');
        }
    }

    /**
     * 建立（或复用）连接。
     * opts = { game, onOpen, onState, onJoined, onRoomList, onError, onClosed, onStatus }
     */
    function connect(o) {
        opts = o || {};
        lastStatus = '';
        if (ws && (ws.readyState === 0 || ws.readyState === 1)) {
            // 已经连着就不重开：只把圆点拨回正确状态（CONNECTING 时还得等）
            setStatus(ws.readyState === 1 ? 'ok' : 'wait');
            return;
        }
        open();
    }

    /** 合盖唤醒 / 网络恢复时的补连：已经 OPEN 或 CONNECTING 就什么都不做 */
    function wake() {
        if (document.visibilityState && document.visibilityState !== 'visible') return;
        if (ws && (ws.readyState === 0 || ws.readyState === 1)) return;
        backoff = 1000;
        open();
    }

    function send(obj) {
        if (!ws || ws.readyState !== 1) return false;
        try {
            ws.send(JSON.stringify(obj));
            return true;
        } catch (e) {
            return false;
        }
    }

    function action(name, data) {
        return send({ type: 'action', action: name, data: data || {} });
    }

    function chat(text, scope) {
        return send({ type: 'chat', text: text, scope: scope === 'wolves' ? 'wolves' : 'public' });
    }

    /* ================= sessionStorage（房号 → seatId） ================= */
    function normRoom(room) {
        return String(room || '').toUpperCase();
    }

    function getSeat(room) {
        var key = SEAT_PREFIX + normRoom(room);
        try {
            return sessionStorage.getItem(key) || '';
        } catch (e) {
            return memSeats[normRoom(room)] || '';
        }
    }

    function setSeat(room, seat) {
        var r = normRoom(room);
        if (!r) return;
        try {
            sessionStorage.setItem(SEAT_PREFIX + r, seat);
            sessionStorage.setItem(LAST_ROOM_KEY, r);
        } catch (e) {
            memSeats[r] = seat;
        }
    }

    function clearSeat(room) {
        var r = normRoom(room);
        try { sessionStorage.removeItem(SEAT_PREFIX + r); } catch (e) { /* noop */ }
        delete memSeats[r];
    }

    function lastRoom() {
        try {
            return sessionStorage.getItem(LAST_ROOM_KEY) || '';
        } catch (e) {
            return '';
        }
    }

    /* ================= DOM 小工具 ================= */
    function esc(s) {
        return String(s === null || s === undefined ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /** el('div', 'cls', '文本')：cls 可为空，文本一律走 textContent（天然安全） */
    function el(tag, cls, text) {
        var n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text !== undefined && text !== null) n.textContent = String(text);
        return n;
    }

    function clear(node) {
        if (!node) return;
        while (node.firstChild) node.removeChild(node.firstChild);
    }

    function $(id) {
        return document.getElementById(id);
    }

    /* ================= 文案 ================= */
    var GAME_NAMES = { undercover: '谁是卧底', werewolf: '狼人杀' };

    function gameName(g) {
        return GAME_NAMES[g] || '游戏';
    }

    var PHASE_NAMES = {
        waiting: '等待中',
        playing: '讨论中',
        voting: '投票中',
        night: '夜晚',
        day: '白天讨论',
        vote: '投票中',
        finished: '已结束'
    };

    function phaseName(p) {
        return PHASE_NAMES[p] || (p || '');
    }

    var ROLE_NAMES = {
        undercover: '卧底',
        civilian: '平民',
        blank: '空白',
        wolf: '狼人',
        seer: '预言家',
        witch: '女巫',
        guard: '守卫',
        villager: '平民',
        // 预言家查验结果现在只下发 good / bad，不再是具体角色名
        good: '好人',
        bad: '坏人'
    };

    function roleName(role) {
        if (!role) return '';
        return ROLE_NAMES[role] || role;
    }

    /* ================= 发言 / 投票提示（两个页面共用，别各写一份） ================= */

    /** 我是否还活着：服务端只在 players[].me 上标自己，找不到就按活着算 */
    function iAmAlive(state) {
        var ps = (state && state.players) || [];
        for (var i = 0; i < ps.length; i++) {
            if (ps[i] && ps[i].me) return ps[i].alive !== false;
        }
        return true;
    }

    /** 我的座位 id（找不到返回 ''） */
    function myId(state) {
        var ps = (state && state.players) || [];
        for (var i = 0; i < ps.length; i++) {
            if (ps[i] && ps[i].me) return ps[i].id || '';
        }
        return '';
    }

    /**
     * 本轮讨论还差几个人发言。
     * 优先用服务端下发的 state.speakNeeded；没给就按
     * 「在线 + 存活 + 不在 state.spoken 里」自己数一遍。
     */
    function remainSpeak(state) {
        if (!state) return 0;
        var need = state.speakNeeded;
        if (need && typeof need.length === 'number') return need.length;
        var spoken = {};
        var list = state.spoken || [];
        for (var i = 0; i < list.length; i++) spoken[list[i]] = true;
        var ps = state.players || [];
        var n = 0;
        for (var j = 0; j < ps.length; j++) {
            var p = ps[j];
            if (!p || p.alive === false || p.online === false) continue;
            if (!spoken[p.id]) n++;
        }
        return n;
    }

    /** 我此刻是否还需要发言：优先 state.canSpeak，没给就按 speakNeeded 里有没有我 */
    function needSpeak(state) {
        if (!state) return false;
        if (typeof state.canSpeak === 'boolean') return state.canSpeak;
        var need = state.speakNeeded;
        if (!need || typeof need.length !== 'number') return false;
        var me = state.seat || myId(state);
        for (var i = 0; i < need.length; i++) {
            if (need[i] === me) return true;
        }
        return false;
    }

    /** 讨论阶段的发言提示（卧底 playing / 狼人杀 day）；其他阶段返回 '' */
    function speakHint(state) {
        if (!state) return '';
        if (state.phase !== 'playing' && state.phase !== 'day') return '';
        if (needSpeak(state)) return '请先在下方发言，全员发言后自动开始投票。';
        if (!iAmAlive(state)) return '你已出局，观战中，等待其他玩家发言。';
        var n = remainSpeak(state);
        if (n > 0) return '已发言，等待其他玩家（还差 ' + n + ' 人）。';
        return '已发言，等待其他玩家发言结束…';
    }

    /** 已投票 / 已弃票后的等待提示；还没表态返回 '' */
    function voteWaitHint(state) {
        if (!state || !state.myVoted) return '';
        return '已提交（投票 / 弃票），等待其他玩家…';
    }

    /** 自动结算倒计时文案；秒数不是正数时返回 '' */
    function secsText(sec) {
        var n = Number(sec);
        if (!(n > 0)) return '';
        return Math.floor(n) + ' 秒后自动结算';
    }

    /* ================= 房间列表 ================= */
    /**
     * 把 roomList 画进 node。
     * onPick(room)：点某个房间时回调（由页面决定跳转还是直接 join）。
     */
    function renderRooms(node, rooms, onPick) {
        clear(node);
        if (!rooms || !rooms.length) {
            node.appendChild(el('div', 'empty', '还没有人开房'));
            return;
        }
        rooms.forEach(function (r) {
            var b = el('button', 'room-item');
            b.type = 'button';
            b.appendChild(el('span', 'ri-id', r.id || ''));
            // 房间别名（创建时填的那一栏）：没有就用游戏名，别留一个空位
            if (r.title) b.appendChild(el('span', 'ri-title', r.title));
            b.appendChild(el('span', 'ri-game', gameName(r.game)));
            var meta = (r.players || 0) + ' 人 · ' + phaseName(r.phase);
            b.appendChild(el('span', 'ri-meta', meta));
            b.onclick = function () { if (onPick) onPick(r); };
            node.appendChild(b);
        });
    }

    /* ================= 玩家列表 ================= */
    /**
     * 用 state.players 画玩家列表。role 非 null 时显示身份标签
     * （服务端只在「该我可见」与 finished 时才填 role，前端不自己猜）。
     */
    function renderPlayers(node, state) {
        clear(node);
        var players = (state && state.players) || [];
        if (!players.length) {
            node.appendChild(el('div', 'empty', '还没有玩家'));
            return;
        }
        // 只在开局前给房主「移出」按钮：对局中踢人会让存活名单与人数校验错位，
        // 服务端也拒（只能移出已经离线的玩家）
        var canKick = state && state.isHost && state.phase === 'waiting';
        players.forEach(function (p) {
            var row = el('div', 'player' + (p.me ? ' me' : '') + (p.alive === false ? ' dead' : ''));
            row.appendChild(el('span', 'p-name', p.name || '玩家'));

            if (p.me) row.appendChild(el('span', 'tag tag-me', '我'));
            if (p.role) row.appendChild(el('span', 'tag tag-role', roleName(p.role)));
            if (p.spectating) {
                // 观战者不是「出局」—— 他本来就没进场，标成出局会让人以为他被投掉了
                row.appendChild(el('span', 'tag', '观战'));
            } else {
                row.appendChild(el('span', 'tag ' + (p.alive === false ? 'tag-dead' : 'tag-alive'),
                    p.alive === false ? '出局' : '存活'));
            }
            if (p.online === false) row.appendChild(el('span', 'tag', '离线'));
            if (canKick && p.online === false && !p.me) {
                var kick = el('button', 'tag kick', '移出');
                kick.type = 'button';
                kick.onclick = function () {
                    if (!window.confirm('把 ' + (p.name || '该玩家') + ' 移出房间？')) return;
                    send({
                        type: 'action',
                        action: 'kick',
                        data: { seat: p.id }
                    });
                };
                row.appendChild(kick);
            }

            node.appendChild(row);
        });
    }

    /* ================= 日志 / 聊天合并区 ================= */
    function renderLog(node, log) {
        var stick = node.scrollHeight - node.scrollTop - node.clientHeight < 40;
        clear(node);
        var list = log || [];
        if (!list.length) {
            node.appendChild(el('div', 'log-empty', '还没有消息'));
            return;
        }
        list.forEach(function (item) {
            var row = el('div', 'log-row' + (item.by ? '' : ' sys') +
                (item.scope === 'wolves' ? ' wolf-only' : ''));
            if (item.scope === 'wolves') row.appendChild(el('span', 'tag tag-wolf', '狼'));
            if (item.by) {
                row.appendChild(el('span', 'log-by', String(item.by) + '：'));
            }
            // 日志内容可能含用户输入（聊天），一律走 textContent，绝不拼 HTML
            row.appendChild(document.createTextNode(String(item.text === null || item.text === undefined ? '' : item.text)));
            node.appendChild(row);
        });
        if (stick) node.scrollTop = node.scrollHeight;
    }

    /* ================= 复制房号 ================= */
    /**
     * 优先 navigator.clipboard（需要安全上下文），
     * 退回 document.execCommand('copy')，都失败就 prompt 让用户手抄。
     */
    function copyText(text, done) {
        function fallback() {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            ta.style.top = '0';
            document.body.appendChild(ta);
            var ok = false;
            try {
                ta.select();
                ok = document.execCommand('copy');
            } catch (e) {
                ok = false;
            }
            document.body.removeChild(ta);
            if (ok) {
                if (done) done(true);
            } else {
                // 最后兜底：弹框让人自己抄（prompt 的内容仍可手动选中）
                window.prompt('复制失败，请手动复制房号：', text);
                if (done) done(false);
            }
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(String(text)).then(function () {
                if (done) done(true);
            }, fallback);
        } else {
            fallback();
        }
    }

    /* ================= 浮动提示 ================= */
    var toastEl = null;
    var toastTimer = null;

    function toast(msg) {
        if (!toastEl) {
            toastEl = el('div', 'toast');
            document.body.appendChild(toastEl);
        }
        toastEl.textContent = String(msg);
        toastEl.className = 'toast show';
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            toastEl.className = 'toast';
        }, 1800);
    }

    global.Play = {
        connect: connect,
        send: send,
        action: action,
        chat: chat,
        wake: wake,
        esc: esc,
        el: el,
        clear: clear,
        $: $,
        wsUrl: wsUrl,
        getSeat: getSeat,
        setSeat: setSeat,
        clearSeat: clearSeat,
        lastRoom: lastRoom,
        gameName: gameName,
        phaseName: phaseName,
        roleName: roleName,
        renderRooms: renderRooms,
        renderPlayers: renderPlayers,
        remainSpeak: remainSpeak,
        speakHint: speakHint,
        voteWaitHint: voteWaitHint,
        secsText: secsText,
        renderLog: renderLog,
        copyText: copyText,
        toast: toast
    };

    // 回到前台 / 网络恢复时补一次连接检查（内部会先判 readyState，避免多插一条连接）
    document.addEventListener('visibilitychange', wake);
    global.addEventListener('online', wake);
})(window);
