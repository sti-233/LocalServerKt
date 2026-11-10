/**
 * 聊天页显示偏好（跨页面共享）。
 *
 * 为什么不走 PageStore：这份偏好要能被**设置页**改、被**聊天页**读。PageStore 的
 * Cookie 路径是"当前文档路径"（/resources/chat.html），设置页（/resources/setting.html）
 * 既读不到也写不进去。所以照 theme.js / lightbox.js 的做法，用 path=/ 的 Cookie。
 *
 * 用法（chat.html 与 setting.html 都要引，且在页面自己的脚本之前）：
 *     <script src="/resources/lib/chat-prefs.js"></script>
 *     ChatPrefs.get()             -> { showTime: true, split: 50 }
 *     ChatPrefs.set({ showTime: false })   // 合并写入，并通知其它标签页
 *     ChatPrefs.onChange(fn)      // 别的页面/标签页改了 → 回调，返回取消订阅函数
 *
 * 时间戳位置：默认放在气泡**下面**（气泡内的正文之后）。关掉 showTime 就整块不渲染，
 * 不是用 CSS 隐藏 —— 隐藏的节点仍会被读屏软件念出来，也会参与 flex 布局。
 *
 * split：会话侧边栏里**私聊段**占的高度百分比（群聊段拿走剩下的），范围 20~80。
 * 两段都是 flex-basis:0，所以这个数直接当 flex-grow 用，不必换算像素。
 */
(function (global) {
    'use strict';

    var COOKIE = 'ls_chat_view';
    var MAX_AGE = 31536000; // 一年（秒）
    var CHANNEL = 'ls-chat-view';

    // 新增开关往这里加：readPrefs 会按这份默认表逐个校验类型，
    // 老 Cookie 里缺字段、或者存了个字符串，都会退回默认值而不是把页面搞坏
    var DEFAULTS = { showTime: true, split: 50 };

    // 侧边栏高度占比的合法区间：低于 20% 连段落标题都放不下，高于 80% 等于把群聊段挤没。
    // 越界一律夹回来而不是拒绝写入 —— 手改 Cookie 或老版本留下的怪值不该把侧边栏搞坏。
    var SPLIT_MIN = 20, SPLIT_MAX = 80;

    function clampSplit(v) {
        var n = Number(v);
        if (!isFinite(n)) return DEFAULTS.split;
        if (n < SPLIT_MIN) return SPLIT_MIN;
        if (n > SPLIT_MAX) return SPLIT_MAX;
        return Math.round(n);
    }

    var prefs = readPrefs();
    var listeners = [];

    function cookieUsable() {
        return global.location.protocol === 'http:' || global.location.protocol === 'https:';
    }

    function readPrefs() {
        var out = {};
        for (var k in DEFAULTS) if (Object.prototype.hasOwnProperty.call(DEFAULTS, k)) out[k] = DEFAULTS[k];
        if (!cookieUsable()) return out;
        var m = document.cookie.match(new RegExp('(?:^|; )' + COOKIE + '=([^;]*)'));
        if (!m) return out;
        var obj = null;
        try { obj = JSON.parse(decodeURIComponent(m[1])); } catch (e) { obj = null; }
        if (obj && typeof obj === 'object') {
            for (var key in DEFAULTS) {
                if (!Object.prototype.hasOwnProperty.call(DEFAULTS, key)) continue;
                if (typeof obj[key] === typeof DEFAULTS[key]) out[key] = obj[key];
            }
        }
        out.split = clampSplit(out.split);
        return out;
    }

    function writePrefs() {
        if (!cookieUsable()) return;
        var body = {};
        for (var k in prefs) if (Object.prototype.hasOwnProperty.call(prefs, k)) body[k] = prefs[k];
        document.cookie = COOKIE + '=' + encodeURIComponent(JSON.stringify(body)) + '; path=/; max-age=' + MAX_AGE;
    }

    function notify() {
        for (var i = 0; i < listeners.length; i++) {
            try { listeners[i](prefs); } catch (e) { /* 订阅者自己出错不该连累别人 */ }
        }
    }

    // 别的标签页/设置页改了：同步过来并通知本页重画
    var channel = null;
    try {
        if (global.BroadcastChannel) {
            channel = new BroadcastChannel(CHANNEL);
            channel.onmessage = function () {
                var next = readPrefs();
                var changed = false;
                for (var k in next) if (next[k] !== prefs[k]) changed = true;
                if (!changed) return;
                prefs = next;
                notify();
            };
        }
    } catch (e) { channel = null; }

    global.ChatPrefs = {
        /** 当前偏好（返回副本，改它不会影响已保存的值） */
        get: function () {
            var out = {};
            for (var k in prefs) if (Object.prototype.hasOwnProperty.call(prefs, k)) out[k] = prefs[k];
            return out;
        },

        /** 合并写入：只给要改的键；未知键忽略（防止别处拼错键名悄悄存进去） */
        set: function (patch) {
            if (!patch || typeof patch !== 'object') return;
            var touched = false;
            for (var k in patch) {
                if (!Object.prototype.hasOwnProperty.call(DEFAULTS, k)) continue;
                if (typeof patch[k] !== typeof DEFAULTS[k]) continue;
                var val = k === 'split' ? clampSplit(patch[k]) : patch[k];
                if (prefs[k] === val) continue;
                prefs[k] = val;
                touched = true;
            }
            if (!touched) return;
            writePrefs();
            notify();
            try { if (channel) channel.postMessage({ at: Date.now() }); } catch (e) { /* 忽略 */ }
        },

        /** 订阅变化，返回取消订阅函数 */
        onChange: function (fn) {
            if (typeof fn !== 'function') return function () {};
            listeners.push(fn);
            return function () {
                var i = listeners.indexOf(fn);
                if (i >= 0) listeners.splice(i, 1);
            };
        }
    };
})(this);
