/**
 * 全站图片预览（lightbox）——聊天页与 B 站各页共用的唯一实现。
 *
 * 以前 chat.html 内联一份、video/common.js 各写一份，两份逐行重复；
 * 现统一到这里，任何缩放/手势/交互的修改只改这一处。
 *
 * 用法：
 *     <link rel="stylesheet" href="/resources/lib/lightbox.css">
 *     <script src="/resources/lib/lightbox.js"></script>
 *     Lightbox.mount({ click: 'img[data-lb]' });         // 单击图片预览（B 站各页）
 *     Lightbox.mount({ dblclick: 'img.img-message',      // 双击缩略图预览（聊天页）
 *                      save: false, edit: false });      // 聊天图已在缓存里，不要保存/裁剪
 *
 * 两个选择器可同时给；遮罩与控件 DOM 由脚本动态创建（每页只挂一份）。
 * `save` / `edit` 默认都为 true，传 false 则隐藏右上角对应的按钮（两个独立）。
 *
 * 交互：滚轮 / 双指捏合 / ＋− 按钮缩放（1x~20x，以指针位置为中心）、
 *       双击大图 2.5x 切换、单指或鼠标拖拽平移（夹取在图片范围内）、
 *       ✕ 或 Esc 关闭。
 * 右侧竖排按钮：✕ 关闭、✎ 编辑（框选裁剪后直接保存）、💾 保存（原图入库）。
 *
 * 设置页可改顶部控件显隐（Cookie ls_lightbox，path=/，全站共享）：
 *     Lightbox.prefs.get()                    -> { buttons: true, scale: true }
 *     Lightbox.prefs.set({ buttons: false })  // 合并写入，立即应用到已挂载的遮罩
 */
(function (global) {
    'use strict';

    var COOKIE = 'ls_lightbox';
    var MAX_AGE = 31536000; // 一年（秒）

    var MIN_SCALE = 1;
    var MAX_SCALE = 20;
    var WHEEL_RATE = 1.15;  // 滚轮每格
    var BTN_RATE = 1.25;    // ＋ − 按钮每档
    var DBL_RATE = 2.5;     // 双击大图

    var SAVE_URL = '/savePicture';  // 落盘到服务端 cache/picture/<sha256>.<ext>
    var CROP_MIME = 'image/png';    // 裁剪结果统一转 PNG（无损，避免二次有损压缩）

    var DEFAULTS = { buttons: true, scale: true };
    var prefs = readPrefs();
    var instance = null;    // 每页只挂一份

    // ===== 偏好：path=/ 的 Cookie，设置页改一次全站生效 =====
    function cookieUsable() {
        return global.location.protocol === 'http:' || global.location.protocol === 'https:';
    }

    function readPrefs() {
        var out = { buttons: DEFAULTS.buttons, scale: DEFAULTS.scale };
        if (!cookieUsable()) return out;
        var m = document.cookie.match(new RegExp('(?:^|; )' + COOKIE + '=([^;]*)'));
        if (!m) return out;
        var obj = null;
        try { obj = JSON.parse(decodeURIComponent(m[1])); } catch (e) { obj = null; }
        if (obj && typeof obj === 'object') {
            if (typeof obj.buttons === 'boolean') out.buttons = obj.buttons;
            if (typeof obj.scale === 'boolean') out.scale = obj.scale;
        }
        return out;
    }

    function writePrefs() {
        if (!cookieUsable()) return;
        var body = JSON.stringify({ buttons: !!prefs.buttons, scale: !!prefs.scale });
        document.cookie = COOKIE + '=' + encodeURIComponent(body) + '; path=/; max-age=' + MAX_AGE;
    }

    // 把偏好应用到已挂载的遮罩（未挂载时什么都不做）
    function applyPrefs() {
        if (!instance) return;
        var el = instance.el;
        var zoomIn = el.querySelector('#lbZoomIn');
        var zoomOut = el.querySelector('#lbZoomOut');
        var scaleEl = el.querySelector('#lbScale');
        if (zoomIn) zoomIn.style.display = prefs.buttons ? '' : 'none';
        if (zoomOut) zoomOut.style.display = prefs.buttons ? '' : 'none';
        if (scaleEl) scaleEl.style.display = prefs.scale ? '' : 'none';
    }

    /**
     * 挂载预览层。
     * @param options {click: 单击预览的图片选择器, dblclick: 双击预览的图片选择器,
     *                 save: 是否显示保存按钮（默认 true）, edit: 是否显示裁剪按钮（默认 true）}
     */
    function mount(options) {
        if (instance) { applyPrefs(); return instance; }
        var opts = options || {};
        var clickSel = opts.click || null;
        var dblSel = opts.dblclick || null;
        // 聊天页传 false：图片本来就在服务端缓存里，再存一份没有意义
        var showSave = opts.save !== false;
        var showEdit = opts.edit !== false;

        // 遮罩与控件动态创建：聊天页与 4 个 B 站页面共用同一份，不必各页写 DOM
        var el = document.createElement('div');
        el.id = 'imgLightbox';
        el.innerHTML = '<img alt="全屏查看">' +
            '<button id="lbClose" title="关闭 (Esc)">✕</button>' +
            '<button id="lbEdit" title="裁剪图片">✎</button>' +
            '<button id="lbSave" title="保存到 cache/picture">💾</button>' +
            '<div class="lb-controls">' +
            '<button id="lbZoomIn" class="lb-btn" title="放大">+</button>' +
            '<span id="lbScale">100%</span>' +
            '<button id="lbZoomOut" class="lb-btn" title="缩小">−</button>' +
            '</div>' +
            // 裁剪层：默认隐藏，进入裁剪模式后铺在图片上方
            '<div class="lb-crop">' +
            '<img class="lb-crop-img" alt="裁剪预览">' +
            '<div class="lb-crop-sel">' +
            '<span class="lb-crop-h tl"></span><span class="lb-crop-h tr"></span>' +
            '<span class="lb-crop-h bl"></span><span class="lb-crop-h br"></span>' +
            '</div>' +
            '<div class="lb-crop-bar">' +
            '<button id="lbCropReset" class="lb-crop-btn">重置</button>' +
            '<button id="lbCropCancel" class="lb-crop-btn">取消</button>' +
            '<button id="lbCropApply" class="lb-crop-btn lb-crop-primary">裁剪并保存</button>' +
            '</div>' +
            '</div>';
        document.body.appendChild(el);

        var bigImg = el.querySelector('img');
        var pointers = new Map(); // 当前按住的指针（双指捏合用）
        var lb = null;            // 打开状态：{scale,x,y,pinchDist,pinchMid,panning,panStart,panX0,panY0}

        function applyTransform() {
            if (!lb) return;
            bigImg.style.transform = 'translate(' + lb.x + 'px, ' + lb.y + 'px) scale(' + lb.scale + ')';
        }

        function paintScale() {
            document.getElementById('lbScale').textContent = Math.round(lb.scale * 100) + '%';
        }

        function open(src) {
            // 上一张图若还停在裁剪模式，先退出来再换图
            exitCrop();
            lb = {
                scale: 1, x: 0, y: 0,
                pinchDist: 0, pinchMid: null,
                panning: false, panStart: null, panX0: 0, panY0: 0
            };
            pointers.clear();
            // 关键：先清掉上一张图残留的 transform，否则重新打开会沿用旧的缩放/偏移。
            // （旧的两份实现都漏了这一步：放大到 3 倍 → 关闭 → 再打开别的图，会保持放大态直到手动再缩放一次）
            bigImg.style.transform = '';
            bigImg.src = src;
            el.classList.add('show');
            paintScale();
            // 重置保存按钮：上一张图的「已保存 / 保存失败」不该留在新图上
            resetSaveState();
        }

        function close() {
            if (!lb) return;
            exitCrop();
            el.classList.remove('show');
            lb = null;
            pointers.clear();
            // 清 src 与 transform：避免大图（尤其 base64）驻留内存，也避免下次打开带着旧变换
            bigImg.src = '';
            bigImg.style.transform = '';
        }

        // 以 (cx, cy) 为缩放中心点，该点在视口中的位置保持不变
        function zoom(newScale, cx, cy) {
            if (!lb) return;
            newScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, newScale));
            var ratio = newScale / lb.scale;
            if (ratio === 1) return;
            // 图片中心 C = 视口中心 + (x, y)；令缩放中心 P 不动：x' = x + (P - C)(1 - ratio)
            lb.x += (cx - window.innerWidth / 2 - lb.x) * (1 - ratio);
            lb.y += (cy - window.innerHeight / 2 - lb.y) * (1 - ratio);
            lb.scale = newScale;
            // 先落地新缩放，让 clampPan 里的 getBoundingClientRect 反映真实尺寸，
            // 夹取完再写一次（旧的两份实现夹取后没有再写回，缩小/拖回时视觉会停在越界位置）
            applyTransform();
            clampPan();
            applyTransform();
            paintScale();
        }

        // 把平移限制在合理范围，防止图片被拖出视野
        function clampPan() {
            if (!lb) return;
            var rect = bigImg.getBoundingClientRect(); // 已包含当前缩放
            var ox = Math.max(0, (rect.width - window.innerWidth) / 2);
            var oy = Math.max(0, (rect.height - window.innerHeight) / 2);
            lb.x = Math.min(ox, Math.max(-ox, lb.x));
            lb.y = Math.min(oy, Math.max(-oy, lb.y));
        }

        // 滚轮缩放（以光标位置为中心）；裁剪模式下不拦截，允许滚动页面看完整图
        el.addEventListener('wheel', function (e) {
            if (crop.on || !lb || !bigImg.src) return;
            e.preventDefault(); // 需 passive:false 才生效，否则会同时滚动页面
            zoom(lb.scale * (e.deltaY < 0 ? WHEEL_RATE : 1 / WHEEL_RATE), e.clientX, e.clientY);
        }, { passive: false });

        // 双击大图：放大 / 已放大则还原（裁剪模式下双击无意义，交给裁剪层）
        bigImg.addEventListener('dblclick', function (e) {
            if (crop.on || !lb) return;
            e.preventDefault();
            if (lb.scale > MIN_SCALE) {
                lb.scale = MIN_SCALE; lb.x = 0; lb.y = 0;
                applyTransform();
                paintScale();
            } else {
                zoom(DBL_RATE, e.clientX, e.clientY);
            }
        });

        // 光栅手势：单指/鼠标拖拽平移，双指捏合缩放（Pointer Events 兼容触屏与鼠标）
        el.addEventListener('pointerdown', function (e) {
            // 裁剪模式下遮罩上的事件全部由裁剪层处理（图片是独立元素）
            if (crop.on) return;
            // 控制条/关闭/编辑/保存按钮上不开始手势，保留其点击行为
            if (!lb || !bigImg.src || e.target.closest('.lb-controls') ||
                e.target.closest('#lbClose') || e.target.closest('#lbEdit') ||
                e.target.closest('#lbSave')) return;
            el.setPointerCapture(e.pointerId);
            pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (pointers.size === 1) {
                lb.panning = true;
                lb.panStart = { x: e.clientX, y: e.clientY };
                lb.panX0 = lb.x;
                lb.panY0 = lb.y;
            } else if (pointers.size === 2) {
                // 进入捏合模式：记录两指初始距离与中点
                lb.panning = false;
                var p = Array.from(pointers.values());
                lb.pinchDist = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
                lb.pinchMid = { x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 };
            }
        });

        el.addEventListener('pointermove', function (e) {
            if (crop.on || !lb || !pointers.has(e.pointerId)) return;
            var cur = { x: e.clientX, y: e.clientY };
            pointers.set(e.pointerId, cur);

            if (pointers.size === 2) {
                // 捏合缩放：以两指中点为中心，按指距变化比例缩放
                var p = Array.from(pointers.values());
                var newDist = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
                if (lb.pinchDist > 0) {
                    zoom(lb.scale * (newDist / lb.pinchDist), lb.pinchMid.x, lb.pinchMid.y);
                }
                lb.pinchDist = newDist;
            } else if (lb.panning) {
                // 单指/鼠标拖拽平移（相对起点偏移，避免逐帧累加漂移）
                lb.x = lb.panX0 + (cur.x - lb.panStart.x);
                lb.y = lb.panY0 + (cur.y - lb.panStart.y);
                clampPan();
                applyTransform();
            }
        });

        function onPointerUp(e) {
            if (!lb) return;
            pointers.delete(e.pointerId);
            if (pointers.size === 1) {
                // 捏合后剩下一根手指，无缝接续为平移
                var p = Array.from(pointers.values())[0];
                lb.panning = true;
                lb.panStart = p;
                lb.panX0 = lb.x;
                lb.panY0 = lb.y;
            } else if (pointers.size === 0) {
                lb.panning = false;
                lb.pinchDist = 0;
            }
        }
        el.addEventListener('pointerup', onPointerUp);
        el.addEventListener('pointercancel', onPointerUp);

        // 缩放控制按钮（以视口中心为中心缩放）
        document.getElementById('lbZoomIn').addEventListener('click', function () {
            if (lb) zoom(lb.scale * BTN_RATE, window.innerWidth / 2, window.innerHeight / 2);
        });
        document.getElementById('lbZoomOut').addEventListener('click', function () {
            if (lb) zoom(lb.scale / BTN_RATE, window.innerWidth / 2, window.innerHeight / 2);
        });
        document.getElementById('lbClose').addEventListener('click', close);

        // ===== 保存 / 裁剪 =====
        // 保存：把当前图片原样（原始字节，不重新编码）发给服务端落到 cache/picture/。
        // 走原始字节而不是 canvas 重编码，是为了让聊天图「保存」后命中同一份缓存
        // （服务端按 SHA-256 命名，字节一致就不会产生副本）。
        // 裁剪：先在遮罩上框选区域，再按原图像素裁出来（canvas），结果转 PNG 后走同一个端点。
        var saveBtn = document.getElementById('lbSave');
        var editBtn = document.getElementById('lbEdit');
        var saveBusy = false;
        var saveTimer = null;

        // 按钮是 40px 圆形，放不下中文短语，用图标 + title 表达状态
        var ICON_SAVE = '💾';
        var ICON_BUSY = '⏳';
        var ICON_OK = '✓';
        var ICON_FAIL = '!';

        function setSaveState(icon, title) {
            saveBtn.textContent = icon;
            if (title) saveBtn.title = title;
        }

        // 回到初始状态，并取消尚未执行完毕的状态回落定时器
        // （否则上一张图的定时器会在新图上把「已保存」刷回初始态）
        function resetSaveState() {
            if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
            saveBusy = false;
            setSaveState(ICON_SAVE, '保存到 cache/picture');
        }

        function blobToDataUrl(url) {
            return fetch(url, { credentials: 'same-origin' }).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.blob();
            }).then(function (blob) {
                return new Promise(function (resolve, reject) {
                    var fr = new FileReader();
                    fr.onload = function () { resolve(fr.result); };
                    fr.onerror = function () { reject(new Error('读取图片失败')); };
                    fr.readAsDataURL(blob);
                });
            });
        }

        // 上游没给 Content-Type 时 blob.type 为空，dataURL 会变成 application/octet-stream，
        // 服务端只收 image/*；这类极少数情况退化为 canvas 重编码成 PNG
        function toPngDataUrl(url) {
            return new Promise(function (resolve, reject) {
                var tmp = new Image();
                tmp.crossOrigin = 'anonymous';
                tmp.onload = function () {
                    var c = document.createElement('canvas');
                    c.width = tmp.naturalWidth;
                    c.height = tmp.naturalHeight;
                    c.getContext('2d').drawImage(tmp, 0, 0);
                    try { resolve(c.toDataURL('image/png')); } catch (e) { reject(e); }
                };
                tmp.onerror = function () { reject(new Error('图片无法转码')); };
                tmp.src = url;
            });
        }

        function currentDataUrl() {
            var src = bigImg.currentSrc || bigImg.src;
            if (!src) return Promise.reject(new Error('没有图片'));
            if (src.indexOf('data:image/') === 0) return Promise.resolve(src);
            return blobToDataUrl(src).then(function (d) {
                return d.indexOf('data:image/') === 0 ? d : toPngDataUrl(src);
            });
        }

        // 统一的落盘调用：成功 resolve 服务端返回的对象，失败 reject(Error)
        function postPicture(dataUrl) {
            return fetch(SAVE_URL, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ data: dataUrl })
            }).then(function (r) {
                return r.text().then(function (t) {
                    var obj = null;
                    try { obj = JSON.parse(t); } catch (e) { obj = null; }
                    if (!r.ok || !obj || !obj.ok) {
                        throw new Error((obj && obj.error) || ('HTTP ' + r.status));
                    }
                    return obj;
                });
            });
        }

        saveBtn.addEventListener('click', function () {
            if (!lb || crop.on || !bigImg.src || saveBusy) return;
            saveBusy = true;
            setSaveState(ICON_BUSY, '正在写入 cache/picture');
            currentDataUrl().then(postPicture).then(function (obj) {
                setSaveState(ICON_OK, '已保存到 ' + obj.dir + '/' + obj.name);
                saveTimer = setTimeout(resetSaveState, 1500);
            }).catch(function (err) {
                setSaveState(ICON_FAIL, '保存失败：' + (err && err.message ? err.message : err));
                saveTimer = setTimeout(resetSaveState, 2500);
            }).then(function () {
                saveBusy = false;
            });
        });

        // ----- 裁剪 -----
        var cropLayer = el.querySelector('.lb-crop');
        var cropImg = el.querySelector('.lb-crop-img');
        var cropSel = el.querySelector('.lb-crop-sel');
        var cropApplyBtn = document.getElementById('lbCropApply');

        // crop.on: 是否处于裁剪模式；crop.sel: 选区（视口坐标 {x,y,w,h}）；
        // crop.box: 图片当前渲染盒（进裁剪时快照，窗口尺寸变化后重新取）
        var crop = { on: false, sel: null, box: null, drag: null };

        function cropBox() {
            // 每次现算：手机上地址栏收放会改变可视高度，缓存的盒子会偏
            crop.box = cropImg.getBoundingClientRect();
            return crop.box;
        }

        // 把选区夹在图片盒内并保证宽高为正
        function clampSel(sel) {
            var b = cropBox();
            var x1 = Math.min(sel.x, sel.x + sel.w);
            var y1 = Math.min(sel.y, sel.y + sel.h);
            var x2 = Math.max(sel.x, sel.x + sel.w);
            var y2 = Math.max(sel.y, sel.y + sel.h);
            x1 = Math.max(b.left, Math.min(x1, b.right));
            y1 = Math.max(b.top, Math.min(y1, b.bottom));
            x2 = Math.max(b.left, Math.min(x2, b.right));
            y2 = Math.max(b.top, Math.min(y2, b.bottom));
            return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
        }

        function paintCropSel() {
            if (!crop.sel || crop.sel.w < 1 || crop.sel.h < 1) {
                cropSel.style.display = 'none';
                return;
            }
            cropSel.style.display = 'block';
            cropSel.style.left = crop.sel.x + 'px';
            cropSel.style.top = crop.sel.y + 'px';
            cropSel.style.width = crop.sel.w + 'px';
            cropSel.style.height = crop.sel.h + 'px';
        }

        // 进裁剪时默认不选区：什么都不动直接点"裁剪并保存"= 整图（见 applyCrop 里的兜底）。
        // 不能默认选中整图——图片满屏时选区就等于整屏，拖拽会被判成"移动选区"，
        // 用户再也没有画新框的地方，只能拖四个角，很难用。
        function resetCropSel() {
            crop.sel = null;
            paintCropSel();
        }

        function enterCrop() {
            if (!lb || !bigImg.src || crop.on) return;
            crop.on = true;
            crop.sel = null;
            crop.drag = null;
            el.classList.add('cropping');
            paintCropSel();
            cropImg.onload = function () { cropBox(); };
            cropImg.src = bigImg.currentSrc || bigImg.src;
            if (cropImg.complete && cropImg.naturalWidth) cropBox();
        }

        function exitCrop() {
            if (!crop.on) return;
            crop.on = false;
            crop.sel = null;
            crop.drag = null;
            el.classList.remove('cropping');
            cropImg.onload = null;
            cropImg.src = '';
        }

        editBtn.addEventListener('click', function () {
            if (crop.on) exitCrop(); else enterCrop();
        });

        // 窗口尺寸变化（手机上地址栏收放/横竖屏切换）会让图片重新适配，选区坐标随之失效；
        // 重新夹取一次，夹不出来（图变小了、选区整个落在图外）就退回整图选区
        var cropResizeTimer = null;
        window.addEventListener('resize', function () {
            if (!crop.on) return;
            if (cropResizeTimer) clearTimeout(cropResizeTimer);
            cropResizeTimer = setTimeout(function () {
                cropResizeTimer = null;
                if (!crop.on) return;
                var clamped = crop.sel ? clampSel(crop.sel) : null;
                if (!clamped || clamped.w < 4 || clamped.h < 4) resetCropSel();
                else { crop.sel = clamped; paintCropSel(); }
            }, 120);
        });

        // 裁剪层的指针交互：拖图片空白处 → 重新框选；拖选区内部 → 移动；拖四角 → 改大小
        cropLayer.addEventListener('pointerdown', function (e) {
            if (!crop.on) return;
            var t = e.target;
            // 底部按钮交给各自 click
            if (t.closest('.lb-crop-bar')) return;
            e.preventDefault();
            cropLayer.setPointerCapture(e.pointerId);
            var b = cropBox();
            var px = e.clientX, py = e.clientY;

            if (t.closest && t.closest('.lb-crop-h')) {
                // 角柄：记录对角固定点
                var corner = t.classList.contains('tl') || t.classList.contains('tr') ? 'top' : 'bottom';
                var side = t.classList.contains('tl') || t.classList.contains('bl') ? 'left' : 'right';
                var anchorX = side === 'left' ? crop.sel.x + crop.sel.w : crop.sel.x;
                var anchorY = corner === 'top' ? crop.sel.y + crop.sel.h : crop.sel.y;
                crop.drag = { mode: 'resize', ax: anchorX, ay: anchorY };
            } else if (crop.sel && px >= crop.sel.x && px <= crop.sel.x + crop.sel.w &&
                       py >= crop.sel.y && py <= crop.sel.y + crop.sel.h) {
                crop.drag = { mode: 'move', ox: px - crop.sel.x, oy: py - crop.sel.y };
            } else {
                crop.drag = { mode: 'new', ax: px, ay: py };
                crop.sel = { x: px, y: py, w: 0, h: 0 };
                paintCropSel();
            }
            crop.box = b;
        });

        cropLayer.addEventListener('pointermove', function (e) {
            if (!crop.on || !crop.drag) return;
            var b = crop.box || cropBox();
            var d = crop.drag;
            var x, y, w, h;
            if (d.mode === 'move') {
                x = e.clientX - d.ox;
                y = e.clientY - d.oy;
                x = Math.max(b.left, Math.min(x, b.right - crop.sel.w));
                y = Math.max(b.top, Math.min(y, b.bottom - crop.sel.h));
                crop.sel = { x: x, y: y, w: crop.sel.w, h: crop.sel.h };
            } else {
                // new / resize 都等价于"从锚点拉到当前指针"
                crop.sel = clampSel({ x: d.ax, y: d.ay, w: e.clientX - d.ax, h: e.clientY - d.ay });
            }
            paintCropSel();
        });

        function endCropDrag() {
            if (!crop.drag) return;
            var wasNew = crop.drag.mode === 'new';
            crop.drag = null;
            // 在空白处点了一下没拖动：不当成 0×0 选区，直接回到"未选区"
            if (wasNew && (!crop.sel || crop.sel.w < 4 || crop.sel.h < 4)) resetCropSel();
        }
        cropLayer.addEventListener('pointerup', endCropDrag);
        cropLayer.addEventListener('pointercancel', endCropDrag);

        document.getElementById('lbCropReset').addEventListener('click', function () {
            if (crop.on) resetCropSel();
        });
        document.getElementById('lbCropCancel').addEventListener('click', exitCrop);

        // 按原图像素裁切：把选区从"屏幕坐标"换算回"自然像素"；没选区时取整图
        cropApplyBtn.addEventListener('click', function () {
            if (!crop.on || saveBusy) return;
            var natW = cropImg.naturalWidth, natH = cropImg.naturalHeight;
            var b = crop.box || cropBox();
            if (!natW || !natH || !b.width || !b.height) return;
            var sel = crop.sel && crop.sel.w >= 4 && crop.sel.h >= 4
                ? crop.sel
                : { x: b.left, y: b.top, w: b.width, h: b.height };
            var scaleX = natW / b.width;
            var scaleY = natH / b.height;
            var sx = Math.max(0, Math.round((sel.x - b.left) * scaleX));
            var sy = Math.max(0, Math.round((sel.y - b.top) * scaleY));
            var sw = Math.min(natW - sx, Math.round(sel.w * scaleX));
            var sh = Math.min(natH - sy, Math.round(sel.h * scaleY));
            if (sw < 1 || sh < 1) return;

            var canvas = document.createElement('canvas');
            canvas.width = sw;
            canvas.height = sh;
            var ctx = canvas.getContext('2d');
            ctx.drawImage(cropImg, sx, sy, sw, sh, 0, 0, sw, sh);
            var dataUrl;
            try {
                dataUrl = canvas.toDataURL(CROP_MIME);
            } catch (err) {
                // 跨域图片会污染 canvas；本站图片都走同源代理，正常不会到这里
                cropApplyBtn.textContent = '跨域图片无法裁剪';
                setTimeout(function () { cropApplyBtn.textContent = '裁剪并保存'; }, 2000);
                return;
            }

            saveBusy = true;
            cropApplyBtn.textContent = '保存中…';
            postPicture(dataUrl).then(function (obj) {
                cropApplyBtn.textContent = '已保存 ' + sw + '×' + sh;
                setSaveState(ICON_OK, '裁剪结果已保存到 ' + obj.dir + '/' + obj.name);
                saveTimer = setTimeout(resetSaveState, 1500);
                setTimeout(exitCrop, 600);
            }).catch(function (err) {
                cropApplyBtn.textContent = '保存失败';
                cropApplyBtn.title = err && err.message ? err.message : String(err);
                setTimeout(function () { cropApplyBtn.textContent = '裁剪并保存'; }, 2000);
            }).then(function () {
                saveBusy = false;
            });
        });

        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape') return;
            // 裁剪模式下 Esc 只退出裁剪，不关整个查看器（少一次误关）
            if (crop.on) { exitCrop(); return; }
            close();
        });

        // 点击任意匹配的图片打开预览（委托，覆盖动态渲染的内容）；capture 阶段
        // stopPropagation，避免误触父级卡片的跳转 onclick
        if (clickSel) {
            document.addEventListener('click', function (e) {
                var img = e.target.closest ? e.target.closest(clickSel) : null;
                if (!img) return;
                e.stopPropagation();
                open(img.currentSrc || img.src);
            }, true);
        }
        if (dblSel) {
            document.addEventListener('dblclick', function (e) {
                var img = e.target.closest ? e.target.closest(dblSel) : null;
                if (!img) return;
                open(img.currentSrc || img.src);
            }, true);
        }

        instance = {
            el: el,
            open: open,
            close: close,
            applyPrefs: applyPrefs
        };
        applyPrefs();
        // save / edit 由调用方按页面决定（聊天页两个都关掉）
        if (!showSave) saveBtn.style.display = 'none';
        if (!showEdit) editBtn.style.display = 'none';
        // 按钮文案以脚本为准（HTML 里的占位文案只是避免脚本未加载时出现空按钮）
        resetSaveState();
        return instance;
    }

    global.Lightbox = {
        mount: mount,
        /** 用任意图片地址打开查看器（视频截帧等场景用，不必是页面里的 <img>） */
        open: function (src) { if (instance) instance.open(src); },
        close: function () { if (instance) instance.close(); },
        prefs: {
            get: function () { return { buttons: !!prefs.buttons, scale: !!prefs.scale }; },
            /** 合并写入（只认 buttons / scale）并立即生效 */
            set: function (patch) {
                if (patch && typeof patch === 'object') {
                    if (typeof patch.buttons === 'boolean') prefs.buttons = patch.buttons;
                    if (typeof patch.scale === 'boolean') prefs.scale = patch.scale;
                }
                writePrefs();
                applyPrefs();
                return { buttons: !!prefs.buttons, scale: !!prefs.scale };
            }
        }
    };
})(this);
