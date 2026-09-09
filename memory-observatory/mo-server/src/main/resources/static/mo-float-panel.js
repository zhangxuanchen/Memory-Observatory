/* ============================================================================
 * 【模块】MoFloatPanel —— 自包含可拖动悬浮面板组件（无依赖，无框架）
 * 【文件】mo-float-panel.js（放到任意静态目录，如 mo-server 的 static/ 下）
 * 【核心功能】在页面左下角提供一个可折叠、可拖动的悬浮卡片，通用展示型：
 *            数据完全由调用方注入，可展示进度条 + 说明行 + 日志列表任一组合。
 * 【用法】
 *   1) <script src="/mo-float-panel.js"></script>
 *   2) <script>
 *        const fp = MoFloatPanel.init({
 *          title: '记忆状态',
 *          fetchData: async () => ({ percent: 42, metaLine: '已用 1,024 字',
 *                                     logTitle: '日志', logItems: [{status:true,text:'收敛'}] }),
 *          pollMs: 5000,
 *        });
 *        // 或手动注入数据（不配 fetchData 时）
 *        fp.update({ percent: 10, metaLine: '手动数据' });
 *      </script>
 * 【可选配置】
 *   title    卡片标题（默认 '状态'）
 *   fetchData async 函数，返回要展示的数据；打开面板与轮询时调用
 *   pollMs    轮询间隔毫秒，<=0 关闭轮询（默认 5000）
 *   render    可选，(data)=>htmlString 完全自定义渲染；缺省用内置默认渲染
 *   logMax    默认渲染下日志最多展示条数（默认 8）
 * 【返回句柄】
 *   fp.open() / fp.close() / fp.toggle()
 *   fp.update(data) 注入并渲染数据；fp.refresh() 手动触发 fetchData
 * ========================================================================== */
(function (window) {
  'use strict';
  if (window.MoFloatPanel) { return; }  // 防重复注入

  /* 内置样式（深紫霓虹，完全自包含，不依赖宿主页面 CSS 变量），只注入一次 */
  var STYLE =
    '<style>' +
    '.mofp{position:fixed;left:16px;bottom:16px;z-index:120;display:flex;' +
    '  flex-direction:column;align-items:flex-start;gap:6px;font-family:system-ui,-apple-system,PingFang SC,sans-serif;}' +
    '.mofp-toggle{display:flex;align-items:center;gap:6px;padding:6px 12px;border-radius:20px;' +
    '  border:1px solid rgba(255,255,255,.1);background:rgba(124,92,255,.08);color:#9aa7c7;' +
    '  font-size:12px;cursor:grab;backdrop-filter:blur(10px);transition:border-color .2s,color .2s;user-select:none;}' +
    '.mofp-toggle:hover{border-color:#7c5cff;color:#fff;}' +
    '.mofp-dot{width:8px;height:8px;border-radius:50%;background:#7c5cff;box-shadow:0 0 8px #7c5cff;}' +
    '.mofp-card{width:320px;max-height:60vh;display:flex;flex-direction:column;' +
    '  border:1px solid rgba(255,255,255,.1);border-radius:10px;background:rgba(10,14,26,.92);' +
    '  backdrop-filter:blur(16px);overflow:hidden;box-shadow:0 8px 30px rgba(0,0,0,.5);}' +
    '.mofp-head{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;' +
    '  border-bottom:1px solid rgba(255,255,255,.06);font-size:13px;font-weight:600;color:#9aa7c7;cursor:grab;user-select:none;}' +
    '.mofp-head.dragging,.mofp-toggle.dragging{cursor:grabbing}' +
    '.mofp-actions{display:flex;gap:4px}' +
    '.mofp-ico{border:none;background:transparent;color:#5a6580;cursor:pointer;font-size:12px;line-height:1;padding:2px 5px;border-radius:4px;}' +
    '.mofp-ico:hover{color:#7c5cff;background:rgba(124,92,255,.12)}' +
    '.mofp-body{padding:10px 12px;overflow-y:auto;font-size:12px;color:#9aa7c7;display:flex;flex-direction:column;gap:8px}' +
    '.mofp-row{display:flex;align-items:center;gap:8px}' +
    '.mofp-k{font-size:11px;color:#5a6580;flex:none}' +
    '.mofp-bar{flex:1;height:8px;border-radius:4px;background:rgba(255,255,255,.06);overflow:hidden;border:1px solid rgba(255,255,255,.06)}' +
    '.mofp-fill{height:100%;border-radius:4px;transition:width .3s}' +
    '.mofp-v{font-size:11px;color:#9aa7c7;flex:none;font-family:ui-monospace,monospace}' +
    '.mofp-mut{font-size:11px;color:#5a6580;line-height:1.5}' +
    '.mofp-sec{font-size:11px;font-weight:700;color:#5a6580;text-transform:uppercase;letter-spacing:.04em;' +
    '  margin-top:2px;border-bottom:1px dashed rgba(255,255,255,.06);padding-bottom:2px}' +
    '.mofp-log{font-size:11px;color:#5a6580;line-height:1.4;font-family:ui-monospace,monospace;display:flex;flex-wrap:wrap;gap:4px}' +
    '.mofp-log .ok{color:#4cd68a}.mofp-log .bad{color:#ff6b6b}' +
    '.mofp-err{font-size:11px;color:#5a6580;line-height:1.5}' +
    '</style>';

  /* HTML 转义，避免注入数据里的 HTML 破坏结构 */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* 默认渲染器：按 {percent, metaLine, logTitle, logItems:[{status,text}]} 组合渲染。
   * 可被 opts.render 整体覆盖。 */
  function defaultRender(data, logMax) {
    data = data || {};
    var h = '';
    if (data.percent != null) {
      var pct = Number(data.percent) || 0, barW = Math.min(pct, 100);
      var bc = pct >= 90 ? '#ff6b6b' : pct >= 70 ? '#f5a623' : '#7c5cff';
      h += '<div class="mofp-row"><span class="mofp-k">占用</span>' +
        '<div class="mofp-bar"><div class="mofp-fill" style="width:' + barW + '%;background:' + bc + '"></div></div>' +
        '<span class="mofp-v">' + Math.round(pct) + '%</span></div>';
    }
    if (data.metaLine) { h += '<div class="mofp-mut">' + esc(data.metaLine) + '</div>'; }
    if (data.logTitle) {
      h += '<div class="mofp-sec">' + esc(data.logTitle) + '</div>';
      var items = data.logItems || [];
      if (items.length) {
        h += items.slice(0, logMax).map(function (it) {
          var ok = (it.status === true || it.status === 'ok');
          var bad = (it.status === false || it.status === 'bad');
          var label = ok ? '达标' : (bad ? '未达' : (it.status == null ? '' : String(it.status)));
          return '<div class="mofp-log">' +
            ((ok || bad || label) ? '<span class="' + (ok ? 'ok' : bad ? 'bad' : '') + '">' + esc(label) + '</span>' : '') +
            '<span>' + esc(it.text == null ? '' : it.text) + '</span></div>';
        }).join('');
      } else {
        h += '<div class="mofp-mut">' + esc(data.logEmpty || '暂无记录') + '</div>';
      }
    }
    return h;
  }

  /* 构造一个独立实例 */
  function create(opts) {
    opts = opts || {};
    var title = opts.title || '状态';
    var pollMs = (opts.pollMs == null) ? 5000 : opts.pollMs;
    var logMax = opts.logMax || 8;
    var render = (typeof opts.render === 'function') ? opts.render : defaultRender;

    if (!document.getElementById('mofp-style')) {
      var st = document.createElement('div');
      st.id = 'mofp-style';
      st.innerHTML = STYLE;
      document.head.appendChild(st);
    }

    var root = document.createElement('div');
    root.className = 'mofp';
    root.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:120;display:flex;flex-direction:column;align-items:flex-start;gap:6px;';
    root.innerHTML =
      '<button class="mofp-toggle" title="' + esc(title) + '"><span class="mofp-dot"></span>' + esc(title) + '</button>' +
      '<div class="mofp-card" style="display:none">' +
      '  <div class="mofp-head"><span class="mofp-title">' + esc(title) + '</span>' +
      '    <span class="mofp-actions">' +
      '      <button class="mofp-ico" data-act="refresh" title="刷新">&#8635;</button>' +
      '      <button class="mofp-ico" data-act="close" title="收起">&#10005;</button>' +
      '    </span></div>' +
      '  <div class="mofp-body"></div>' +
      '</div>';
    document.body.appendChild(root);

    var toggle = root.querySelector('.mofp-toggle');
    var card = root.querySelector('.mofp-card');
    var head = root.querySelector('.mofp-head');
    var body = root.querySelector('.mofp-body');

    var state = {
      open: false,
      data: null,
      timer: null,
      drag: null,
      opts: opts
    };

    function renderBody() {
      body.innerHTML = render(state.data || {}, logMax);
    }

    function refresh() {
      if (typeof opts.fetchData !== 'function') { return; }
      Promise.resolve(opts.fetchData())
        .then(function (d) { state.data = d || {}; if (state.open) { renderBody(); } })
        .catch(function () { if (state.open) { body.innerHTML = '<div class="mofp-mut">加载失败</div>'; } });
    }

    function open() {
      state.open = true;
      card.style.display = 'flex';
      toggle.style.display = 'none';
      refresh();
    }
    function close() {
      state.open = false;
      card.style.display = 'none';
      toggle.style.display = 'flex';
    }
    function togglePanel() {
      if (state.open) { close(); } else { open(); }
    }
    function update(data) {
      state.data = data;
      if (state.open) { renderBody(); }
    }

    /* 拖动：句柄设置捕获，移动/抬起监听 window（捕获后事件重定向到容器，window 仍能收到） */
    function dragStart(e) {
      if (e.pointerType === 'mouse' && e.button !== 0) { return; }
      e.preventDefault();
      var r = root.getBoundingClientRect();
      state.drag = { sx: e.clientX, sy: e.clientY, ox: r.left, oy: r.top };
      try { root.setPointerCapture(e.pointerId); } catch (ignored) {}
      e.target.classList.add('dragging');
    }
    function dragMove(e) {
      if (!state.drag) { return; }
      var x = Math.max(4, Math.min(window.innerWidth - root.offsetWidth - 4, state.drag.ox + (e.clientX - state.drag.sx)));
      var y = Math.max(4, Math.min(window.innerHeight - root.offsetHeight - 4, state.drag.oy + (e.clientY - state.drag.sy)));
      root.style.left = x + 'px';
      root.style.top = y + 'px';
      root.style.bottom = 'auto';
    }
    function dragEnd(e) {
      if (!state.drag) { return; }
      state.drag = null;
      var t = e.target;
      if (t && t.classList) { t.classList.remove('dragging'); }
    }

    toggle.addEventListener('pointerdown', dragStart);
    head.addEventListener('pointerdown', dragStart);
    window.addEventListener('pointermove', dragMove);
    window.addEventListener('pointerup', dragEnd);
    window.addEventListener('pointercancel', dragEnd);
    head.addEventListener('dblclick', togglePanel);   // 双击标题收起/展开
    root.querySelector('[data-act="refresh"]').addEventListener('click', function (ev) { ev.stopPropagation(); refresh(); });
    root.querySelector('[data-act="close"]').addEventListener('click', function (ev) { ev.stopPropagation(); close(); });
    toggle.addEventListener('click', togglePanel);

    if (pollMs > 0) {
      state.timer = setInterval(function () { if (state.open) { refresh(); } }, pollMs);
    }

    return {
      open: open,
      close: close,
      toggle: togglePanel,
      update: update,
      refresh: refresh,
      el: root
    };
  }

  window.MoFloatPanel = { init: create };
})(window);