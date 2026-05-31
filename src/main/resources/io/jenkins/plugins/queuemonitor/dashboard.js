(function () {
  'use strict';

  // ── Base URL resolution ──────────────────────────────────────────────────
  var pathParts = window.location.pathname.split('/');
  var qmIdx = pathParts.indexOf('queue-monitor');
  var BASE = qmIdx >= 0 ? pathParts.slice(0, qmIdx + 1).join('/') : '/queue-monitor';

  // ── Helpers ──────────────────────────────────────────────────────────────
  function fmtMs(ms) {
    if (ms < 1000)  return ms + ' ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
    return (ms / 60000).toFixed(1) + ' min';
  }
  function fmtTs(iso) { return new Date(iso).toLocaleTimeString(); }
  function setText(id, v) { var e = document.getElementById(id); if (e) e.textContent = v; }
  function setHtml(id, h) { var e = document.getElementById(id); if (e) e.innerHTML  = h; }
  function val(id)  { var e = document.getElementById(id); return e ? e.value : ''; }
  function iget(id) { return document.getElementById(id); }

  // ── Client-side pagination state ─────────────────────────────────────────
  var labelData   = [];
  var pickupData  = [];
  var scalingData = [];

  var labelPage   = 0;
  var pickupPage  = 0;
  var scalingPage = 0;

  function pageSize(selectId, fallback) {
    var v = parseInt(val(selectId), 10);
    return isNaN(v) ? fallback : v;
  }

  // ── Generic paginator ────────────────────────────────────────────────────
  function paginate(rows, page, ps) {
    var pages = Math.max(1, Math.ceil(rows.length / ps));
    if (page >= pages) page = pages - 1;
    var start = page * ps;
    return { rows: rows.slice(start, start + ps), page: page, pages: pages, total: rows.length };
  }

  function updatePager(prevId, nextId, infoId, page, pages, total) {
    var info = total === 0 ? 'No data'
      : 'Page ' + (page + 1) + ' of ' + pages + '  (' + total + ' total)';
    setText(infoId, info);
    var prev = iget(prevId); if (prev) prev.disabled = page === 0;
    var next = iget(nextId); if (next) next.disabled = page >= pages - 1;
  }

  // ── Label Status ─────────────────────────────────────────────────────────
  function renderLabels() {
    var ps     = pageSize('label-page-size', 15);
    var filter = val('label-filter').toLowerCase();
    var rows   = labelData.filter(function (r) {
      return !filter || r.lbl.toLowerCase().indexOf(filter) >= 0;
    });
    var p = paginate(rows, labelPage, ps);
    labelPage = p.page;

    var html = p.rows.map(function (r) {
      return '<tr>'
        + '<td>' + r.lbl   + '</td>'
        + '<td>' + r.qd    + '</td>'
        + '<td>' + r.busy  + '</td>'
        + '<td>' + r.total + '</td>'
        + '<td style="color:' + (r.sat ? '#c00' : 'inherit') + '">'
        + (r.sat ? 'YES' : 'no') + '</td>'
        + '</tr>';
    });
    setHtml('label-tbody', html.length
      ? html.join('')
      : '<tr><td colspan="5">No label data yet</td></tr>');
    updatePager('label-prev', 'label-next', 'label-page-info', p.page, p.pages, p.total);
  }

  function loadSnapshot() {
    fetch(BASE + '/apiSnapshot')
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d.error) {
          setHtml('label-tbody', '<tr><td colspan="5" style="color:#c00">' + d.error + '</td></tr>');
          return;
        }
        setText('val-queue', d.totalQueueDepth);
        setText('val-util',  d.utilizationPercent.toFixed(1) + '%');
        setText('val-exec',  d.busyExecutors + ' / ' + d.totalExecutors);

        labelData = Object.keys(d.totalByLabel || {}).map(function (lbl) {
          return {
            lbl:   lbl,
            qd:    d.queueByLabel[lbl]  || 0,
            busy:  d.busyByLabel[lbl]   || 0,
            total: d.totalByLabel[lbl]  || 0,
            sat:   !!(d.saturationByLabel && d.saturationByLabel[lbl])
          };
        });
        labelData.sort(function (a, b) {
          if (b.sat !== a.sat) return b.sat ? 1 : -1;
          return b.qd - a.qd;
        });
        renderLabels();
      })
      .catch(function (err) {
        setHtml('label-tbody', '<tr><td colspan="5" style="color:#c00">Failed to load snapshot</td></tr>');
        console.error('[QueueMonitor] loadSnapshot:', err);
      });
  }

  // ── Queue Depth Trend chart ───────────────────────────────────────────────
  function loadHistory() {
    fetch(BASE + '/apiHistory?limit=60')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var canvas = iget('qm-chart');
        if (!canvas || !canvas.getContext) return;
        var ctx = canvas.getContext('2d');
        var W = canvas.width, H = canvas.height, pad = 40;
        ctx.clearRect(0, 0, W, H);
        if (!data.length) return;

        var maxQ = Math.max.apply(null, data.map(function (d) { return d.queueDepth; })) || 1;
        var maxE = Math.max.apply(null, data.map(function (d) { return d.total; }))      || 1;
        var n    = data.length;

        function xOf(i) { return pad + (i / (n - 1 || 1)) * (W - 2 * pad); }
        function yQ(v)  { return H - pad - (v / maxQ) * (H - 2 * pad); }
        function yE(v)  { return H - pad - (v / maxE) * (H - 2 * pad); }

        ctx.strokeStyle = '#eee'; ctx.lineWidth = 1;
        for (var g = 0; g <= 4; g++) {
          var y = pad + (g / 4) * (H - 2 * pad);
          ctx.beginPath(); ctx.moveTo(pad, y); ctx.lineTo(W - pad, y); ctx.stroke();
        }
        ctx.strokeStyle = '#e05'; ctx.lineWidth = 2; ctx.beginPath();
        data.forEach(function (d, i) {
          i === 0 ? ctx.moveTo(xOf(i), yQ(d.queueDepth)) : ctx.lineTo(xOf(i), yQ(d.queueDepth));
        });
        ctx.stroke();
        ctx.strokeStyle = '#05e'; ctx.lineWidth = 2; ctx.beginPath();
        data.forEach(function (d, i) {
          i === 0 ? ctx.moveTo(xOf(i), yE(d.busy)) : ctx.lineTo(xOf(i), yE(d.busy));
        });
        ctx.stroke();
        ctx.font = '12px sans-serif';
        ctx.fillStyle = '#e05'; ctx.fillRect(W - 150, 8,  12, 12);
        ctx.fillStyle = '#333'; ctx.fillText('Queue depth',    W - 134, 19);
        ctx.fillStyle = '#05e'; ctx.fillRect(W - 150, 26, 12, 12);
        ctx.fillStyle = '#333'; ctx.fillText('Busy executors', W - 134, 37);
      })
      .catch(function (err) { console.error('[QueueMonitor] loadHistory:', err); });
  }

  // ── Recent Execution Pickups ──────────────────────────────────────────────
  function renderPickups() {
    var ps     = pageSize('pickup-page-size', 20);
    var fJob   = val('pickup-filter-job').toLowerCase();
    var fLabel = val('pickup-filter-label').toLowerCase();
    var rows   = pickupData.filter(function (e) {
      return (!fJob   || e.job.toLowerCase().indexOf(fJob)     >= 0)
          && (!fLabel || e.label.toLowerCase().indexOf(fLabel) >= 0);
    });
    var p = paginate(rows, pickupPage, ps);
    pickupPage = p.page;

    var html = p.rows.map(function (e) {
      return '<tr>'
        + '<td>' + fmtTs(e.ts)          + '</td>'
        + '<td>' + e.job                + '</td>'
        + '<td>' + e.agent              + '</td>'
        + '<td>' + e.label              + '</td>'
        + '<td>' + fmtMs(e.queueWaitMs) + '</td>'
        + '</tr>';
    });
    setHtml('pickup-tbody', html.length
      ? html.join('')
      : '<tr><td colspan="5">No pickups recorded yet</td></tr>');
    updatePager('pickup-prev', 'pickup-next', 'pickup-page-info', p.page, p.pages, p.total);
  }

  function loadPickups() {
    fetch(BASE + '/apiPickups')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        pickupData = Array.isArray(data) ? data : [];
        renderPickups();
      })
      .catch(function (err) {
        setHtml('pickup-tbody',
          '<tr><td colspan="5" style="color:#c00">Failed to load — check console (F12)</td></tr>');
        console.error('[QueueMonitor] loadPickups:', err);
      });
  }

  // ── Executor Scaling Audit ────────────────────────────────────────────────
  function renderScaling() {
    var ps     = pageSize('scaling-page-size', 20);
    var fAgent = val('scaling-filter-agent').toLowerCase();
    var rows   = scalingData.filter(function (e) {
      return !fAgent || e.agent.toLowerCase().indexOf(fAgent) >= 0;
    });
    var p = paginate(rows, scalingPage, ps);
    scalingPage = p.page;

    var html = p.rows.map(function (e) {
      var dir  = e.direction || (e.to > e.from ? 'Scale Up' : 'Scale Down');
      var dClr = (dir === 'Scale Up') ? '#007a00' : '#b05000';
      return '<tr>'
        + '<td>' + fmtTs(e.ts)                                            + '</td>'
        + '<td>' + e.agent                                                 + '</td>'
        + '<td style="font-weight:600;color:' + dClr + '">' + dir         + '</td>'
        + '<td>' + e.from + ' &rarr; ' + e.to                             + '</td>'
        + '<td>' + e.reason                                                + '</td>'
        + '<td>' + e.freeCpu.toFixed(1) + '%'                              + '</td>'
        + '<td>' + e.freeMem                                               + '</td>'
        + '</tr>';
    });
    setHtml('scaling-tbody', html.length
      ? html.join('')
      : '<tr><td colspan="7">No scaling events yet</td></tr>');
    updatePager('scaling-prev', 'scaling-next', 'scaling-page-info', p.page, p.pages, p.total);
  }

  function loadScaling() {
    fetch(BASE + '/apiScaling')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        scalingData = Array.isArray(data) ? data : [];
        renderScaling();
      })
      .catch(function (err) {
        setHtml('scaling-tbody',
          '<tr><td colspan="7" style="color:#c00">Failed to load — check console (F12)</td></tr>');
        console.error('[QueueMonitor] loadScaling:', err);
      });
  }

  // ── Auto-refresh ─────────────────────────────────────────────────────────
  function refresh() {
    loadSnapshot();
    loadHistory();
    loadPickups();
    loadScaling();
  }

  // ── Wire up controls ─────────────────────────────────────────────────────
  function init() {
    // Label controls
    iget('label-prev').addEventListener('click', function () {
      if (labelPage > 0) { labelPage--; renderLabels(); }
    });
    iget('label-next').addEventListener('click', function () {
      labelPage++; renderLabels();
    });
    iget('label-filter').addEventListener('input', function () {
      labelPage = 0; renderLabels();
    });
    iget('label-page-size').addEventListener('change', function () {
      labelPage = 0; renderLabels();
    });

    // Pickup controls
    iget('pickup-prev').addEventListener('click', function () {
      if (pickupPage > 0) { pickupPage--; renderPickups(); }
    });
    iget('pickup-next').addEventListener('click', function () {
      pickupPage++; renderPickups();
    });
    iget('pickup-filter-job').addEventListener('input', function () {
      pickupPage = 0; renderPickups();
    });
    iget('pickup-filter-label').addEventListener('input', function () {
      pickupPage = 0; renderPickups();
    });
    iget('pickup-page-size').addEventListener('change', function () {
      pickupPage = 0; renderPickups();
    });

    // Scaling controls
    iget('scaling-prev').addEventListener('click', function () {
      if (scalingPage > 0) { scalingPage--; renderScaling(); }
    });
    iget('scaling-next').addEventListener('click', function () {
      scalingPage++; renderScaling();
    });
    iget('scaling-filter-agent').addEventListener('input', function () {
      scalingPage = 0; renderScaling();
    });
    iget('scaling-page-size').addEventListener('change', function () {
      scalingPage = 0; renderScaling();
    });

    refresh();
    setInterval(refresh, 30000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
}());
