/**
 * IndexedDB 统一封装：本项目所有"本地缓存大块数据"的唯一入口。
 *
 * 为什么单独写一层，而不是各处直接 IndexedDB：
 *   1. IndexedDB 的 API 是回调 + 事件式的，每个调用点自己写一遍事务样板，出错必漏；
 *   2. 隐私模式 / 被策略禁用时 indexedDB.open() 可能**既不 success 也不 error**，
 *      只是卡在 onblocked 上。这里统一加 1.5 秒超时，超时即判定不可用；
 *   3. 本项目所有缓存都必须"只当加速层"：拿不到就回源，绝不能因为本地存储坏掉而白屏。
 *      所以本层的所有方法**永不 reject**，失败一律给 null/false/[]。
 *
 * 用法：
 *     <script src="/resources/lib/idb-store.js"></script>
 *
 *     // 1) 通用键值（带 TTL），适合列表快照、歌词、探测报告
 *     await IdbStore.kvSet('video_feed', {items, fresh}, 10 * 60 * 1000);
 *     const snap = await IdbStore.kvGet('video_feed');   // 过期或不存在返回 null
 *
 *     // 2) 聊天历史（按会话+日期整条存，与 /history 的返回结构一一对应）
 *     await IdbStore.set('chatHistory', {k: '张三|2026-11-07', conv, date, items, savedAt});
 *     const row = await IdbStore.get('chatHistory', '张三|2026-11-07');
 *
 *     // 3) 播放进度（多值 + 按更新时间排序，支持"继续观看"列表）
 *     await IdbStore.set('progress', {k: bvid + '|' + cid, t: 83.5, d: 600, updatedAt: Date.now()});
 *     const recent = await IdbStore.getAll('progress', {index: 'updatedAt', direction: 'prev', limit: 10});
 *
 *     // 4) 图片字节（键用内容哈希文件名，同名必然同内容，所以永不过期，只按张数淘汰）
 *     await IdbStore.blobSet('a1b2...e9.png', blob, 6 * 1024 * 1024);
 *     const row = await IdbStore.blobGet('a1b2...e9.png');   // {k, blob, bytes, type, savedAt} 或 null
 *     await IdbStore.blobPrune(240);   // 只留最近 240 张
 *
 *     // 5) 判断是否真的可用（同步，open 之后才有意义）
 *     IdbStore.available()  // true | false
 *
 * 数据布局（一次建好，后续只加不改，改结构必须升 DB_VERSION）：
 *     kv           keyPath 'k'  —— 通用键值，值为 {k, v, savedAt, expiresAt}
 *                                   （expiresAt=0 表示永不过期）
 *     chatHistory  keyPath 'k'  —— k = `会话名|日期`，值带 items 数组
 *     aiConvs      keyPath 'id' —— 每个 AI 会话一条，值带 messages 数组
 *     progress     keyPath 'k'  —— k = `bvid|cid`，index 'updatedAt' 供"继续观看"排序
 *     imgBlobs     keyPath 'k'  —— k = 图片文件名（sha256.ext），值带 blob；index 'savedAt' 供 LRU
 */
(function (global) {
    'use strict';

    var DB_NAME = 'localserver';
    // 新增/修改 store 必须加这个数字：onupgradeneeded 只在版本变化时触发，
    // 升级回调内部按"缺什么补什么"处理，所以老库里的数据不会被动。
    var DB_VERSION = 2;
    // open 超过这个时间还没成功就当不可用（隐私模式会卡在 onblocked 上，永不回调）
    var OPEN_TIMEOUT = 1500;

    var STORES = {
        kv: { keyPath: 'k', indexes: [['expiresAt', 'expiresAt']] },
        chatHistory: { keyPath: 'k', indexes: [['savedAt', 'savedAt']] },
        aiConvs: { keyPath: 'id', indexes: [['savedAt', 'savedAt']] },
        progress: { keyPath: 'k', indexes: [['updatedAt', 'updatedAt']] },
        imgBlobs: { keyPath: 'k', indexes: [['savedAt', 'savedAt']] }
    };

    var dbPromise = null;   // null=未尝试 / Promise<IDBDatabase> / Promise<null>(不可用)
    var dbInstance = null;
    var usable = null;      // null=未知，true/false=已定论

    // ---------- 内存降级实现 ----------
    // 不可用时所有读写都落到这里：功能照常（只是不持久），调用方不需要判断分支。
    var mem = {};
    function memStore(name) {
        if (!mem[name]) mem[name] = {};
        return mem[name];
    }
    /** 取写入内存时用的键：aiConvs 的 keyPath 是 id，其余是 k */
    function memKeyOf(store, value) {
        if (!value) return '';
        if (store === 'aiConvs') return value.id;
        return value.k;
    }

    // ---------- 通用工具 ----------

    /** 把一次 IDB 请求包成 Promise；永不 reject（失败给 fallback 值） */
    function reqAsync(req, fallback) {
        return new Promise(function (resolve) {
            if (!req) { resolve(fallback); return; }
            var settled = false;
            function done(v) {
                if (settled) return;
                settled = true;
                resolve(v);
            }
            req.onsuccess = function () { done(req.result); };
            req.onerror = function () {
                // 关键：必须阻止事件继续冒泡，否则会冒到事务上触发 abort
                try { req.transaction && req.transaction.abort(); } catch (e) { /* ignore */ }
                done(fallback);
            };
        });
    }

    /** 等一次事务完成（complete 才算真的写进去了） */
    function txDone(tx) {
        return new Promise(function (resolve) {
            if (!tx) { resolve(false); return; }
            var settled = false;
            function done(ok) {
                if (settled) return;
                settled = true;
                resolve(ok);
            }
            tx.oncomplete = function () { done(true); };
            tx.onerror = function () { done(false); };
            tx.onabort = function () { done(false); };
        });
    }

    function hasIndexedDB() {
        try {
            return !!(global.indexedDB && global.indexedDB.open);
        } catch (e) {
            return false;
        }
    }

    /** 打开数据库（惰性、缓存、带超时） */
    function open() {
        if (dbPromise) return dbPromise;

        dbPromise = new Promise(function (resolve) {
            if (!hasIndexedDB()) {
                usable = false;
                resolve(null);
                return;
            }
            var settled = false;
            var timer = null;

            function finish(db) {
                if (settled) return;
                settled = true;
                if (timer) { clearTimeout(timer); timer = null; }
                dbInstance = db;
                usable = !!db;
                resolve(db);
            }

            timer = setTimeout(function () {
                // 超时：隐私模式下 onblocked 可能永远不来，只能当不可用
                finish(null);
            }, OPEN_TIMEOUT);

            var req;
            try {
                req = global.indexedDB.open(DB_NAME, DB_VERSION);
            } catch (e) {
                finish(null);
                return;
            }

            req.onupgradeneeded = function (ev) {
                var db = ev.target.result;
                for (var name in STORES) {
                    if (!Object.prototype.hasOwnProperty.call(STORES, name)) continue;
                    var def = STORES[name];
                    var store;
                    if (db.objectStoreNames.contains(name)) {
                        store = ev.target.transaction.objectStore(name);
                    } else {
                        store = db.createObjectStore(name, { keyPath: def.keyPath });
                    }
                    // 索引也只补缺失的，已存在的不动（避免老库升级报错）
                    for (var i = 0; i < def.indexes.length; i++) {
                        var idx = def.indexes[i];
                        if (!store.indexNames.contains(idx[0])) {
                            store.createIndex(idx[0], idx[1], { unique: false });
                        }
                    }
                }
            };

            req.onsuccess = function () {
                var db = req.result;
                // 别的标签页要升级版本时会触发这个；关掉自己让它升，然后本页降级用内存
                db.onversionchange = function () {
                    try { db.close(); } catch (e) { /* ignore */ }
                    dbInstance = null;
                    usable = false;
                    dbPromise = Promise.resolve(null);
                };
                finish(db);
            };

            req.onerror = function () { finish(null); };
            req.onblocked = function () { finish(null); };
        });

        return dbPromise;
    }

    /** 在指定 store 上开一个事务执行 fn(store)，返回事务是否完成 */
    function run(name, mode, fn) {
        return open().then(function (db) {
            if (!db || !db.objectStoreNames.contains(name)) return false;
            var tx, store, ret;
            try {
                tx = db.transaction([name], mode);
                store = tx.objectStore(name);
                ret = fn(store, tx);
            } catch (e) {
                return false;
            }
            // fn 可以返回一个 Promise（比如需要先读再写），等它再等事务
            var p = (ret && typeof ret.then === 'function') ? ret : Promise.resolve(ret);
            return p.then(function () { return txDone(tx); }, function () {
                try { tx.abort(); } catch (e) { /* ignore */ }
                return false;
            });
        }).catch(function () {
            return false;
        });
    }

    /** 内部：判断现在是否走内存降级 */
    function degraded() {
        return usable === false;
    }

    // ---------- 对外 API ----------

    var api = {
        /** 预热：页面启动时提前调一次，后面的读写就不用等 open */
        ready: function () {
            return open().then(function (db) { return !!db; });
        },

        /** 同步判断是否可用；open 尚未完成时返回 false（宁可当成不可用） */
        available: function () {
            return usable === true;
        },

        /**
         * 给"本地副本"这种可有可无的读用：最多等 ms 毫秒，超时就当没命中。
         *
         * 为什么要这个：open() 有 1.5 秒超时（隐私模式会卡住），所有读写都排在这个
         * open 后面。对"缓存"来说等 1.5 秒再回源，比直接回源还慢 —— 页面首屏会白等。
         * 用 race 把它们解耦：读不到就当没有，后台那次读照常完成，不影响后续调用。
         *
         * @param promise IdbStore.get/kvGet 之类的 Promise（本层保证不会 reject）
         * @param ms      等待上限，默认 300ms
         */
        soon: function (promise, ms) {
            var wait = ms > 0 ? ms : 300;
            return new Promise(function (resolve) {
                var settled = false;
                var timer = setTimeout(function () {
                    if (settled) return;
                    settled = true;
                    resolve(null);   // 按"没命中"处理，调用方照常回源
                }, wait);
                Promise.resolve(promise).then(function (v) {
                    if (settled) return;
                    settled = true;
                    clearTimeout(timer);
                    resolve(v);
                }, function () {
                    if (settled) return;
                    settled = true;
                    clearTimeout(timer);
                    resolve(null);
                });
            });
        },

        get: function (store, key) {
            if (degraded()) {
                var m = memStore(store);
                return Promise.resolve(m[key] === undefined ? null : m[key]);
            }
            return open().then(function (db) {
                if (!db) {
                    var m2 = memStore(store);
                    return m2[key] === undefined ? null : m2[key];
                }
                var tx, req;
                try {
                    tx = db.transaction([store], 'readonly');
                    req = tx.objectStore(store).get(key);
                } catch (e) { return null; }
                return reqAsync(req, null).then(function (v) {
                    return v === undefined ? null : v;
                });
            }).catch(function () { return null; });
        },

        set: function (store, value) {
            if (degraded()) {
                memStore(store)[memKeyOf(store, value)] = value;
                return Promise.resolve(true);
            }
            return open().then(function (db) {
                if (!db) {
                    memStore(store)[memKeyOf(store, value)] = value;
                    return true;
                }
                return run(store, 'readwrite', function (s) { s.put(value); })
                    .then(function (done) {
                        if (!done) {
                            // 写失败（典型是配额超限）：本次照样落到内存，至少当前页面能用
                            memStore(store)[memKeyOf(store, value)] = value;
                        }
                        return done;
                    });
            }).catch(function () { return false; });
        },

        del: function (store, key) {
            if (degraded()) {
                try { delete memStore(store)[key]; } catch (e) { /* ignore */ }
                return Promise.resolve(true);
            }
            return open().then(function (db) {
                if (!db) {
                    try { delete memStore(store)[key]; } catch (e) { /* ignore */ }
                    return true;
                }
                return run(store, 'readwrite', function (s) { s.delete(key); });
            }).catch(function () { return false; });
        },

        /** 清空一个 store（用户点"清空"或结构迁移时兜底） */
        clear: function (store) {
            if (degraded()) { mem[store] = {}; return Promise.resolve(true); }
            return open().then(function (db) {
                if (!db) { mem[store] = {}; return true; }
                return run(store, 'readwrite', function (s) { s.clear(); });
            }).catch(function () { return false; });
        },

        /**
         * 取多条。
         * opts: { index, range, direction: 'next'|'prev', limit }
         * 没有 index 时走全表游标；返回数组（永不 reject）
         */
        getAll: function (store, opts) {
            opts = opts || {};
            var limit = opts.limit > 0 ? opts.limit : Infinity;
            var dir = opts.direction === 'prev' ? 'prev' : 'next';

            if (degraded()) {
                return Promise.resolve(memAll(store, limit, opts));
            }
            return open().then(function (db) {
                if (!db) return memAll(store, limit, opts);
                var out = [];
                var tx, src;
                try {
                    tx = db.transaction([store], 'readonly');
                    var s = tx.objectStore(store);
                    src = opts.index ? s.index(opts.index) : s;
                } catch (e) { return []; }

                var req;
                try {
                    // 用游标而不是 getAll：limit 生效时不必把整表读出来
                    req = src.openCursor(opts.range || null, dir);
                } catch (e) { return []; }

                return new Promise(function (resolve) {
                    if (!req) { resolve([]); return; }
                    req.onsuccess = function () {
                        var cur = req.result;
                        if (!cur || out.length >= limit) { resolve(out); return; }
                        out.push(cur.value);
                        try { cur.continue(); } catch (e) { resolve(out); }
                    };
                    req.onerror = function () { resolve(out); };
                });
            }).catch(function () { return []; });
        },

        // ---------- 通用键值 + TTL ----------

        /**
         * @param ttlMs 存活毫秒数；0/省略 = 永不过期
         * 过期条目不会立即删除，读取时用 expiresAt 判掉，写入时顺手清一批（见 kvPrune）
         */
        kvSet: function (key, value, ttlMs) {
            var now = Date.now();
            return api.set('kv', {
                k: String(key),
                v: value,
                savedAt: now,
                expiresAt: ttlMs > 0 ? now + ttlMs : 0
            });
        },

        kvGet: function (key) {
            return api.get('kv', String(key)).then(function (row) {
                if (!row) return null;
                if (row.expiresAt && row.expiresAt <= Date.now()) {
                    api.del('kv', String(key)); // 顺手清掉，失败也无所谓
                    return null;
                }
                return row.v === undefined ? null : row.v;
            });
        },

        kvDel: function (key) {
            return api.del('kv', String(key));
        },

        /** 删除所有过期条目；返回删除条数。页面启动时调一次即可 */
        kvPrune: function () {
            var now = Date.now();
            return api.getAll('kv', { limit: 500 }).then(function (rows) {
                var stale = [];
                for (var i = 0; i < rows.length; i++) {
                    if (rows[i].expiresAt && rows[i].expiresAt <= now) stale.push(rows[i].k);
                }
                if (!stale.length) return 0;
                return Promise.all(stale.map(function (k) { return api.del('kv', k); }))
                    .then(function () { return stale.length; });
            });
        },

        /** 清掉某个前缀的所有键（如 'video_search:'），用于列表快照按搜索词批量失效 */
        kvClearPrefix: function (prefix) {
            return api.getAll('kv', { limit: 1000 }).then(function (rows) {
                var hit = [];
                for (var i = 0; i < rows.length; i++) {
                    if (String(rows[i].k).indexOf(prefix) === 0) hit.push(rows[i].k);
                }
                if (!hit.length) return 0;
                return Promise.all(hit.map(function (k) { return api.del('kv', k); }))
                    .then(function () { return hit.length; });
            });
        },

        // ---------- 图片字节 ----------
        // 为什么单独一个 store 而不是塞进 kv：kv 里放的是小 JSON，这里的值可能是几 MB 的
        // Blob，读写代价与淘汰规则都不一样。键用 sha256 文件名，内容与键一一对应，永不过期。

        /** 取一张图：{k, blob, bytes, type, savedAt}，没有则 null */
        blobGet: function (key) {
            return api.get('imgBlobs', String(key)).then(function (row) {
                return row && row.blob ? row : null;
            });
        },

        /**
         * 存一张图的字节。
         * @param limit 单张上限（字节），超过直接放弃 —— 别让一两张大图把配额吃光
         * @return Promise<boolean> 是否真的写进去了
         */
        blobSet: function (key, blob, limit) {
            if (!blob || !blob.size) return Promise.resolve(false);
            if (limit > 0 && blob.size > limit) return Promise.resolve(false);
            return api.set('imgBlobs', {
                k: String(key),
                blob: blob,
                bytes: blob.size,
                type: blob.type || '',
                savedAt: Date.now()
            });
        },

        blobDel: function (key) {
            return api.del('imgBlobs', String(key));
        },

        /**
         * 只留最近 max 张，多的按 savedAt 淘汰；返回删掉的张数。
         *
         * 走 openKeyCursor（只有键没有值）：这里要的是"哪些排在后面"，
         * 用 getAll 会把每张图的 Blob 全读出来，淘汰一次就能把内存撑起来。
         */
        blobPrune: function (max) {
            var cap = max > 0 ? max : 240;
            if (degraded()) {
                var mk = Object.keys(memStore('imgBlobs'));
                for (var j = cap; j < mk.length; j++) { delete memStore('imgBlobs')[mk[j]]; }
                return Promise.resolve(Math.max(0, mk.length - cap));
            }
            return open().then(function (db) {
                if (!db) return 0;
                var stale = [];
                var tx, req;
                try {
                    tx = db.transaction(['imgBlobs'], 'readonly');
                    req = tx.objectStore('imgBlobs').index('savedAt').openKeyCursor(null, 'prev');
                } catch (e) { return 0; }
                return new Promise(function (resolve) {
                    if (!req) { resolve(0); return; }
                    var seen = 0;
                    req.onsuccess = function () {
                        var cur = req.result;
                        if (!cur) { resolve(stale.length); return; }
                        seen++;
                        if (seen > cap) stale.push(cur.primaryKey);
                        try { cur.continue(); } catch (e) { resolve(stale.length); }
                    };
                    req.onerror = function () { resolve(stale.length); };
                }).then(function (n) {
                    if (!stale.length) return 0;
                    return Promise.all(stale.map(function (k) { return api.del('imgBlobs', k); }))
                        .then(function () { return n; });
                });
            }).catch(function () { return 0; });
        },

        /** 当前用量估算，用于设置页展示；不可用时返回 null */
        estimate: function () {
            try {
                if (!global.navigator || !navigator.storage || !navigator.storage.estimate) {
                    return Promise.resolve(null);
                }
                return navigator.storage.estimate().then(function (e) {
                    return {
                        usageMB: Math.round((e.usage || 0) / 1048576 * 10) / 10,
                        quotaMB: Math.round((e.quota || 0) / 1048576)
                    };
                }, function () { return null; });
            } catch (e) {
                return Promise.resolve(null);
            }
        },

        // 调试用：暴露内部状态
        db: function () { return dbInstance; }
    };

    /** 内存降级下的 getAll：按 key 排序后截取（与 IDB 的 next 方向大致一致） */
    function memAll(store, limit, opts) {
        var m = memStore(store);
        var arr = [];
        for (var k in m) {
            if (Object.prototype.hasOwnProperty.call(m, k)) arr.push(m[k]);
        }
        if (opts && opts.index && arr.length) {
            var f = opts.index;
            arr.sort(function (a, b) {
                var x = a && a[f], y = b && b[f];
                if (x === y) return 0;
                return x > y ? 1 : -1;
            });
            if (opts.direction === 'prev') arr.reverse();
        }
        return arr.slice(0, limit);
    }

    global.IdbStore = api;

    // <head> 里同步执行时 document 可能还没就绪，用 setTimeout 0 预热即可
    // （open 是异步的，不会阻塞首屏）
    if (global.indexedDB) {
        setTimeout(function () {
            // 预热 + 清过期条目；两步都吞掉错误，绝不影响页面
            api.ready().then(function () {
                if (usable) api.kvPrune();
            }).catch(function () { /* ignore */ });
        }, 0);
    }
})(window);
