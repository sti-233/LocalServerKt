/**
 * 页面级配置存储：本地用 Cookie 保存，可选再同步一份到服务端（/api/prefs）。
 *
 * 为什么不用 localStorage：隐私模式/被禁用时 getItem 会直接抛 SecurityError，
 * 而且每个页面各写一套读写代码、键名分散，容易互相污染、留下清不掉的残留。
 *
 * 用法：
 *     <script src="/resources/lib/page-store.js"></script>
 *     const store = PageStore.create('music');                 // 只存 Cookie
 *     const store = PageStore.create('chat', { remote: true }); // Cookie + 服务端
 *     store.set('settings', JSON.stringify(obj));    // 落成 Cookie: ls_music_settings
 *     store.get('settings');                         // 没有则返回 null
 *     store.remove('settings');
 *
 *     store.sync(function (err) { ... });            // 仅 remote：拉服务端并合并
 *     store.push();                                  // 仅 remote：立刻推一次（默认防抖 600ms）
 *
 * Cookie 分配规则（各页面互不混杂）：
 *   1. 名字统一 ls_<页面标识>_<键>，开发者工具里一眼看出属于哪个页面；
 *   2. 路径自动取当前文档的 location.pathname（如 /resources/music.html），
 *      所以该 Cookie 只在访问这个页面时才会收发——别的页面读不到，
 *      /searchMusic 之类的接口请求也不会白白带上一堆配置；
 *   3. max-age 一年，不设 domain（只作用于当前主机）。
 *
 * 单个 Cookie 上限约 4KB（名字 + 值），超出会被浏览器静默丢弃。
 * 开了 remote 时这个上限就不再是硬限制：写 Cookie 失败也会照样排队推给服务端，
 * set() 依然返回 true，调用方不必再自己做截断。
 */
(function (global) {
    'use strict';

    var PREFIX = 'ls_';
    var MAX_AGE = 31536000; // 一年（秒）
    var MAX_LEN = 3800;     // 留出名字与属性的余量（浏览器上限约 4096）
    var PUSH_DELAY = 600;   // 实时推送的防抖窗口（毫秒）

    // Cookie 名只允许 token 字符：中文用户名等一律百分号编码；
    // encodeURIComponent 不转义 ( ) ' ! * 这几个分隔符，需要再补一遍
    function encodeName(s) {
        return encodeURIComponent(String(s)).replace(/[()'!*]/g, function (c) {
            return '%' + c.charCodeAt(0).toString(16).toUpperCase();
        });
    }

    function escapeReg(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    // file:// 直接打开页面时浏览器不保存 Cookie，此时退化为内存存储，保证当次会话仍可用
    function cookiesUsable() {
        return global.location.protocol === 'http:' || global.location.protocol === 'https:';
    }

    function readRaw(name) {
        var m = document.cookie.match(new RegExp('(?:^|; )' + escapeReg(name) + '=([^;]*)'));
        if (!m) return null;
        var v = decodeURIComponent(m[1]);
        return v === '' ? null : v; // 已删除/空值的 Cookie 一律视为不存在
    }

    function writeRaw(name, value, path) {
        document.cookie = name + '=' + encodeURIComponent(value) + '; path=' + path + '; max-age=' + MAX_AGE;
    }

    function deleteRaw(name, path) {
        document.cookie = name + '=; path=' + path + '; max-age=0';
    }

    // 当前文档路径就是该页面的 Cookie 路径：路径不匹配时浏览器既不发送也无法读取
    function pagePath() {
        var p = global.location.pathname || '/';
        return p.charAt(0) === '/' ? p : '/' + p;
    }

    // 极简 XHR：比 fetch 少一层 Promise，旧浏览器也不会因为缺 polyfill 直接报错
    function request(method, url, body, cb) {
        var xhr = new XMLHttpRequest();
        try { xhr.open(method, url, true); } catch (e) { cb(e); return; }
        if (body != null) xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) return;
            if (xhr.status >= 200 && xhr.status < 300) {
                var data = null;
                try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
                cb(null, data);
            } else {
                cb(new Error('HTTP ' + xhr.status));
            }
        };
        xhr.onerror = function () { cb(new Error('network error')); };
        try { xhr.send(body == null ? null : body); } catch (e) { cb(e); }
    }

    /**
     * @param page    页面标识（chat / music / file…），会进 Cookie 名与 /api/prefs 的 page 字段
     * @param options {remote: 是否同步到服务端}
     */
    function create(page, options) {
        var opts = options || {};
        var remote = !!opts.remote;
        var ns = PREFIX + encodeName(page) + '_';
        var path = pagePath();
        var useCookie = cookiesUsable();
        var mem = {};        // Cookie 不可用时的退化存储（仅当次会话有效）
        var overlay = {};    // 本会话写过的键：Cookie 写失败时 get 仍能读到；也是"待推送"的来源
        var dirty = {};      // 待推送的键 → 值（null 表示删除）
        var timer = null;
        var syncing = false;

        function nameOf(key) {
            return ns + encodeName(key);
        }

        function has(obj, k) { return Object.prototype.hasOwnProperty.call(obj, k); }

        function readCookie(key) {
            if (!useCookie) return has(mem, nameOf(key)) ? mem[nameOf(key)] : null;
            return readRaw(nameOf(key));
        }

        // 写 Cookie，返回是否真的写进去了（超长/被禁 → false）
        function writeCookie(key, text, silent) {
            var name = nameOf(key);
            var size = name.length + encodeURIComponent(text).length;
            if (size > MAX_LEN) {
                if (!silent && !remote) {
                    console.warn('[PageStore] ' + name + ' 内容过长（' + size + ' 字节），未保存');
                }
                return false;
            }
            if (!useCookie) { mem[name] = text; return true; }
            try { writeRaw(name, text, path); return true; }
            catch (e) {
                if (!silent) console.warn('[PageStore] 写入 Cookie 失败：' + e);
                return false;
            }
        }

        function schedule() {
            if (!remote) return;
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () { timer = null; push(); }, PUSH_DELAY);
        }

        // 把 dirty 一次性推给服务端；失败就把 dirty 留着，下次写入或下次 sync 再试
        function push(cb) {
            if (!remote) { if (cb) cb(null); return; }
            if (timer) { clearTimeout(timer); timer = null; }
            var values = {};
            var keys = Object.keys(dirty);
            if (!keys.length) { if (cb) cb(null); return; }
            var sent = {};
            for (var i = 0; i < keys.length; i++) {
                values[keys[i]] = dirty[keys[i]];
                sent[keys[i]] = dirty[keys[i]];
            }
            request('POST', '/api/prefs', JSON.stringify({ page: page, values: values }), function (err) {
                if (err) {
                    // 失败：把这次要写的键放回 dirty（期间又被改过的新值优先）
                    var k;
                    for (k in sent) {
                        if (has(sent, k) && !has(dirty, k)) dirty[k] = sent[k];
                    }
                } else {
                    // 成功：只删掉"值和发出去时一样"的键，期间改过的留到下一轮
                    for (var k2 in sent) {
                        if (has(sent, k2) && dirty[k2] === sent[k2]) delete dirty[k2];
                    }
                }
                if (cb) cb(err || null);
            });
        }

        var store = {
            page: page,
            path: path,
            remote: remote,

            get: function (key) {
                // 本会话写过的值优先（Cookie 可能因为超长没写进去）
                if (has(overlay, key)) return overlay[key];
                return readCookie(key);
            },

            /**
             * 写入，成功返回 true；失败（超长且未开 remote / Cookie 被禁）返回 false。
             * silent=true 时超长不告警（调用方自己会缩短内容重试时用，避免刷屏）
             */
            set: function (key, value, silent) {
                var text = String(value);
                overlay[key] = text;
                var ok = writeCookie(key, text, silent);
                if (remote) { dirty[key] = text; schedule(); return true; }
                return ok;
            },

            remove: function (key) {
                delete overlay[key];
                if (!useCookie) delete mem[nameOf(key)];
                else { try { deleteRaw(nameOf(key), path); } catch (e) { /* 静默失败即可 */ } }
                if (remote) { dirty[key] = null; schedule(); }
            },

            /**
             * 从服务端拉取本页配置并合并；回调 (err)。
             * 合并规则：本会话改过的键（overlay 里的）优先于服务端，其余以服务端为准。
             * 拉到之后会尽力回写 Cookie，让后续的同步读取也能拿到。
             */
            sync: function (cb) {
                if (!remote) { if (cb) cb(null); return; }
                syncing = true;
                request('GET', '/api/prefs?page=' + encodeURIComponent(page), null, function (err, data) {
                    syncing = false;
                    if (err) { if (cb) cb(err); return; }
                    var prefs = (data && data.prefs) || {};
                    for (var k in prefs) {
                        if (!has(prefs, k)) continue;
                        if (has(overlay, k)) continue; // 本会话改过，别被服务端盖掉
                        if (typeof prefs[k] !== 'string') continue;
                        overlay[k] = prefs[k];
                        writeCookie(k, prefs[k], true);
                    }
                    if (cb) cb(null);
                });
            },

            push: push,

            // 方便调试/测试：当前待推送的内容
            pending: function () { return Object.keys(dirty).length; }
        };

        return store;
    }

    global.PageStore = { create: create };
})(this);
