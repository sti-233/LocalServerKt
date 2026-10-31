/**
 * 全站主题切换（日间 / 夜间 / 跟随系统）。
 *
 * 这个偏好是**跨页面共享**的，所以故意用 path=/ 的 Cookie，而不是
 * PageStore 那种按页面路径隔离的 Cookie——在任一页面切了，所有页面一起变。
 *
 * 用法（放在 <head> 里，越靠前越好，避免首屏闪一下浅色）：
 *     <link rel="stylesheet" href="/resources/lib/theme.css">
 *     <script src="/resources/lib/theme.js"></script>
 *
 * API：
 *     Theme.get()       -> 'light' | 'dark' | 'auto'（用户选的偏好）
 *     Theme.current()   -> 'light' | 'dark'（auto 已解析成实际值）
 *     Theme.set('dark') -> 写入并立即生效，同时通知其它标签页
 *     Theme.toggle()    -> 在 light/dark 之间切（auto 时按当前实际值的反面）
 *
 * 脚本在 <head> 里同步执行：此时 <html> 已存在、<body> 还没解析，
 * 设置 data-theme 不会引起任何重排闪烁。
 */
(function (global) {
    'use strict';

    var COOKIE = 'ls_theme';
    var MAX_AGE = 31536000; // 一年
    var MODES = { light: 1, dark: 1, auto: 1 };
    var root = document.documentElement;

    // Chrome 80 支持 matchMedia；某些内嵌 WebView 不支持，取不到就当浅色
    var mq = null;
    try { mq = global.matchMedia ? global.matchMedia('(prefers-color-scheme: dark)') : null; } catch (e) { mq = null; }

    function cookieUsable() {
        return global.location.protocol === 'http:' || global.location.protocol === 'https:';
    }

    function read() {
        if (!cookieUsable()) return null;
        var m = document.cookie.match(new RegExp('(?:^|; )' + COOKIE + '=([^;]*)'));
        if (!m) return null;
        var v = decodeURIComponent(m[1]);
        return MODES[v] ? v : null;
    }

    function write(mode) {
        if (!cookieUsable()) return;
        document.cookie = COOKIE + '=' + encodeURIComponent(mode) + '; path=/; max-age=' + MAX_AGE;
    }

    function systemPrefersDark() {
        return !!(mq && mq.matches);
    }

    // auto 解析成实际值
    function resolve(mode) {
        if (mode === 'dark' || mode === 'light') return mode;
        return systemPrefersDark() ? 'dark' : 'light';
    }

    function apply(mode) {
        var actual = resolve(mode);
        root.setAttribute('data-theme', actual);
        root.setAttribute('data-theme-mode', mode);
        return actual;
    }

    var listeners = [];

    function notify(actual) {
        for (var i = 0; i < listeners.length; i++) {
            try { listeners[i](actual); } catch (e) { /* 单个回调出错不影响其它 */ }
        }
    }

    var currentMode = read() || 'auto';
    apply(currentMode);

    // auto 模式下跟随系统实时变化（用户在系统里切深色，页面不用刷新就变）
    if (mq) {
        var onChange = function () {
            if (currentMode === 'auto') notify(apply('auto'));
        };
        if (mq.addEventListener) mq.addEventListener('change', onChange);
        else if (mq.addListener) mq.addListener(onChange); // 旧实现兜底
    }

    // 另一个标签页切换了主题：同步过来（BroadcastChannel 桌面 Chrome 54+ 都有）
    var channel = null;
    try {
        if (global.BroadcastChannel) {
            channel = new BroadcastChannel('ls-theme');
            channel.onmessage = function (e) {
                var mode = e.data && e.data.mode;
                if (!MODES[mode] || mode === currentMode) return;
                currentMode = mode;
                notify(apply(mode));
            };
        }
    } catch (e) { channel = null; }

    function set(mode) {
        if (!MODES[mode]) return resolve(currentMode);
        currentMode = mode;
        write(mode);
        var actual = apply(mode);
        notify(actual);
        try { if (channel) channel.postMessage({ mode: mode }); } catch (e) { /* 忽略 */ }
        return actual;
    }

    global.Theme = {
        get: function () { return currentMode; },
        current: function () { return resolve(currentMode); },
        set: set,
        toggle: function () { return set(resolve(currentMode) === 'dark' ? 'light' : 'dark'); },
        // 注册回调：主题变化时收到实际值（'light'/'dark'），返回取消函数
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
