
/* ══════════════════════════════════════════════════════════
   闪写前端（Vue 3，与 Java 后端通过 window.host 桥接）
   JS -> Java: host('方法名', 参数...)
   Java -> JS: 调 window.app 上的公开方法
   ══════════════════════════════════════════════════════════ */
var app = Vue.createApp({
  data() { return {
    mode: 'full',            // full | bubble
    words: [],
    showWords: false,        // 词库弹层：点击"词库"按钮才出现
    addingWord: false,
    newCode: '', newContent: '',
    showSettings: false,
    showMore: false,
    showChars: false,
    showDocs: false,          // 文件（多文档）浮层
    docs: [],
    docUnsaved: false,        // 关闭自动保存时，正文有改动未落盘
    promptShow: false, promptTitle: '', promptValue: '', promptPlaceholder: '',
    _promptCb: null,
    charsPos: {},
    showImportModal: false,
    impName: '', impText: '',
    showHistModal: false,
    histList: [],
    showCloseModal: false,
    _ready: false,             // 初始化完成标记（初始化期间不下发设置）
    _applyTimer: null,         // 设置下发防抖定时器
    rememberClose: false,
    confirmMsg: '', _confirmCb: null,
    plugins: [],
    pinned: false,
    autoStart: false,
    capturing: false,
    capturingAction: '',
    opacity: 100,
    stats: {today: 0, total: 0},
    cfg: {boss: 'Ctrl+Shift+Z+X', bossEnabled: true, gesture: 'LLRRLL',
          gestureEnabled: true, bubbleMaxChars: 120, bubbleCharsPerLine: 20,
          accent: '#6366F1', accent2: '#22D3EE', themeMode: 'gradient',
          fontSize: 16, bold: false, closeAction: 'ask',
          wallpaperOpacity: 40, wallpaperFit: 'cover', winOpacity: 100, fontName: '',
          bubbleCloud: true, bubbleRhythm: true, bubbleCloudKind: 'cloud',
          bubbleCustomChars: '', bubbleLines: 3, cloudOpacity: 60,
          skin: 'light', bubbleDblRestore: true, bubbleAutoSize: true,
          bubbleIntrude: false, intrudeOpacity: 40, intrudeSize: 100,
          autoSave: true, alwaysWords: false, docName: '未命名.txt',
          keys: {}},
    /* 可在设置里改键的全部功能（id 与 Java 端 DEFAULT_KEYS 对应） */
    actions: [
      {id:'undo', name:'撤销', def:'Ctrl+Z'},
      {id:'redo', name:'重做', def:'Ctrl+Shift+Z'},
      {id:'deleteLine', name:'删除当前行（IDEA 同款）', def:'Ctrl+Y'},
      {id:'indent', name:'段首缩进', def:'Ctrl+1'},
      {id:'quote', name:'中文引号 “ ”', def:'Ctrl+2'},
      {id:'dash', name:'破折号 ——', def:'Ctrl+3'},
      {id:'ellipsis', name:'省略号 ……', def:'Ctrl+4'},
      {id:'book', name:'书名号《》', def:'Ctrl+5'},
      {id:'chars', name:'特殊字符面板', def:'Ctrl+6'},
      {id:'bold', name:'加粗开关', def:'Ctrl+B'},
      {id:'import', name:'导入文档', def:'Ctrl+O'},
      {id:'history', name:'文字寻回', def:'Ctrl+H'},
      {id:'save', name:'立即保存', def:'Ctrl+S'},
      {id:'export', name:'导出 TXT', def:'Ctrl+E'},
      {id:'words', name:'词库面板', def:'Ctrl+K'},
      {id:'bubble', name:'气泡模式开关', def:'Ctrl+Shift+M'},
      {id:'settings', name:'打开设置', def:'Ctrl+,'},
      {id:'pin', name:'窗口置顶', def:'未设置'}
    ],
    iconUrl: '',
    customIcon: '',
    wallpaper: '',
    fontFamily: '',
    bgmUrl: '',
    musicOn: false,
    gestureInput: 'LLRRLL',
    toastMsg: '',
    charList: '★☆✦✧✓✗→←↑↓※§◎○●◇◆□■△▲·～「」『』《》〈〉？！'.split(''),
    swatchPairs: [
      {a:'#6366F1', b:'#22D3EE'}, {a:'#2DD4A7', b:'#22D3EE'},
      {a:'#F472B6', b:'#FB923C'}, {a:'#A78BFA', b:'#6366F1'},
      {a:'#0EA5E9', b:'#2DD4A7'}, {a:'#EF4444', b:'#FB923C'},
      /* 深色主题友善配色 */
      {a:'#8B5CF6', b:'#06B6D4'}, {a:'#F59E0B', b:'#EF4444'},
      {a:'#10B981', b:'#84CC16'}, {a:'#E879F9', b:'#818CF8'}
    ],
    decorPresets: ['☁','★','❀','♪','❤','✦','❄','⚡'],
    screenW: window.screen ? window.screen.width : 1200,
    screenH: window.screen ? window.screen.height : 800,
    _saveTimer: null
    };
  },

  computed: {
    docTitle: function() {
      return String(this.cfg.docName || '未命名').replace(/\.(txt|md)$/i, '');
    }
  },

  watch: {
    /* Vue3 in-DOM 模板不编译容器 div 的 :class，模式切换手动维护类名 */
    mode: function(v) {
      var el = document.getElementById('app');
      if (el) el.classList.toggle('mode-bubble', v === 'bubble');
      this.syncSidewords();
    },
    /* 冻结窗口：设置 / 退出确认时背后界面定格 */
    showSettings: function(v) { this.syncFrozen(); },
    showCloseModal: function(v) { this.syncFrozen(); },
    /* 词库面板显隐 -> 气泡模式要重算窗口宽度（词库外置悬浮层占位置） */
    showWords: function() { this.syncSidewords(); this.applyBubbleSize(); },
    'cfg.alwaysWords': function() { this.syncSidewords(); this.applyBubbleSize(); },
    /* 任何设置项改动 -> 立即生效（140ms 防抖，拖滑块时不狂写磁盘） */
    cfg: {
      deep: true,
      handler: function() { if (this._ready) this.queueApply(); }
    },
    gestureInput: function() { if (this._ready) this.queueApply(); }
  },

  mounted: function() {
    var self = this;
    /* 全局快捷键（不依赖焦点，在设置/词库外层也能用） */
    document.addEventListener('keydown', function(e) { self.onGlobalKey(e); });
    /* 记录编辑区最近一次选区：点词库/工具栏后仍能插到原光标处 */
    document.addEventListener('selectionchange', function() {
      var sel = window.getSelection();
      var ed = document.getElementById('editor');
      if (sel && sel.rangeCount && ed && ed.contains(sel.getRangeAt(0).startContainer)) {
        self._range = sel.getRangeAt(0).cloneRange();
      }
    });
  },

  methods: {
    /* 空操作（配合 .stop 修饰符，避免把 false 字面量当处理器） */
    noop: function() {},

    /* ---------- 桥 ---------- */
    host: function(name, a, b, c, d) {
      if (!window.host || !window.host.js) return undefined;
      function s(v) { return (v === undefined || v === null) ? '' : String(v); }
      return window.host.js(name, s(a), s(b), s(c), s(d));
    },
    /** 某动作当前绑定的键（给按钮 tooltip 用） */
    keyOf: function(id) {
      var k = (this.cfg && this.cfg.keys) ? this.cfg.keys[id] : '';
      return k || '';
    },

    initHost: function() {
      this.cfg = JSON.parse(this.host('getConfig'));
      if (!this.cfg.keys) this.cfg.keys = {};
      this.gestureInput = this.cfg.gesture;
      this.words = JSON.parse(this.host('getWords'));
      this.autoStart = this.host('isAutoStart') === 'true';
      this.iconUrl = this.host('getIcon') || '';
      this.customIcon = this.host('getCustomIcon') || '';
      this.wallpaper = this.host('getWallpaper') || '';
      this.fontFamily = this.cfg.fontName || '';
      this._fontData = this.host('getFontData') || '';
      this.bgmUrl = this.host('getBgm') || '';
      this.plugins = JSON.parse(this.host('listPlugins') || '[]');
      this.docs = JSON.parse(this.host('getDocs') || '[]');
      this.showWords = !!this.cfg.alwaysWords;    // 常驻词库：开机就贴在右侧
      this.applyVars();
      this.initClouds();
      var draft = this.host('getDraft') || '';
      var ed = document.getElementById('editor');
      ed.innerText = draft;
      this.refreshStats();
      try {
        var r = document.createRange();
        r.selectNodeContents(ed); r.collapse(false);
        var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
        this._range = r.cloneRange();
        ed.focus();
      } catch (e) {}
      this._ready = true;
      this.syncSidewords();
    },

    /* ---------- 统计与保存 ---------- */
    getText: function() { return document.getElementById('editor').innerText || ''; },

    setStats: function(today, total) { this.stats = {today: today, total: total}; },

    onCleared: function() {
      document.getElementById('editor').innerText = '';
      this.refreshStats();
      this.toast('已清空');
    },

    setEditorText: function(text) {
      var ed = document.getElementById('editor');
      ed.innerText = text || '';
      this.refreshStats();
      this.showHistModal = false;
      this.toast('已恢复该时刻的全文');
    },

    refreshStats: function() {
      var t = (this.getText() || '').replace(/\s/g, '');
      this.stats.total = t.length;
      if (this.cfg.autoSave) this.host('saveDraft', this.getText());
    },

    /* ---------- 冻结窗口 / 保存 ---------- */
    syncFrozen: function() {
      var el = document.getElementById('app');
      if (!el) return;
      el.classList.toggle('frozen', !!(this.showSettings || this.showCloseModal));
    },
    /** 手动保存（Ctrl+S）：关掉自动保存后靠它 */
    saveNow: function() {
      this.host('saveNow', this.getText());
      this.docUnsaved = false;
      this.toast('已保存：' + this.cfg.docName);
    },

    onInput: function() {
      var self = this;
      this.pulse();
      this.refreshStats();
      if (!this.cfg.autoSave) this.docUnsaved = true;
      if (this.mode === 'bubble') {
        /* 气泡跟着字数长高 / 缩回 */
        if (this.cfg.bubbleAutoSize) this.applyBubbleSize();
        if (this.caretAtEnd()) {
          var ed = document.getElementById('editor');
          ed.scrollTop = ed.scrollHeight;
        }
      }
      clearTimeout(this._saveTimer);
      this._saveTimer = setTimeout(function() { self.refreshStats(); }, 1200);
    },

    caretAtEnd: function() {
      var sel = window.getSelection();
      if (!sel.rangeCount) return false;
      var ed = document.getElementById('editor');
      var r = sel.getRangeAt(0).cloneRange();
      var r2 = document.createRange();
      r2.selectNodeContents(ed);
      try { r2.setStart(r.endContainer, r.endOffset); } catch (e) { return false; }
      return r2.toString().length === 0;
    },

    /* ---------- 窗口拖动 / 控制 ---------- */
    dragStart: function(e) {
      if (e.button !== 0) return;
      var sx = e.screenX, sy = e.screenY, self = this;
      function mv(ev) {
        self.host('moveBy', ev.screenX - sx, ev.screenY - sy);
        sx = ev.screenX; sy = ev.screenY;
      }
      function up() {
        document.removeEventListener('mousemove', mv);
        document.removeEventListener('mouseup', up);
      }
      document.addEventListener('mousemove', mv);
      document.addEventListener('mouseup', up);
    },

    togglePin: function() { this.host('togglePin'); this.pinned = !this.pinned; },
    preset: function(w, h) { this.showMore = false; this.host('setSize', w, h); },

    /* ---------- 关闭：询问 / 托盘 / 退出 ---------- */
    askClose: function() {
      var a = this.cfg.closeAction || 'ask';
      if (a === 'tray') { this.host('hideWindow'); return; }
      if (a === 'exit') { this.host('closeApp'); return; }
      this.rememberClose = false;
      this.showCloseModal = true;
    },
    confirmClose: function(how) {
      if (this.rememberClose) {
        this.cfg.closeAction = how;
        this.host('setCloseAction', how);
      }
      this.showCloseModal = false;
      if (how === 'tray') { this.host('hideWindow'); this.toast('已最小化到托盘'); }
      else this.host('closeApp');
    },

    /* ---------- 气泡模式 ---------- */
    enterBubble: function() {
      this.mode = 'bubble';
      if (!this.cfg.alwaysWords) this.showWords = false;
      this.showDocs = false;
      this.showSettings = false;
      this.host('enterBubble');
      var self = this;
      setTimeout(function() {
        self.applyBubbleSize();
        var ed = document.getElementById('editor');
        ed.scrollTop = ed.scrollHeight;
        ed.focus();
      }, 60);
    },
    exitBubble: function() {
      this.mode = 'full';
      this.host('exitBubble');
      var ed = document.getElementById('editor');
      if (ed) ed.focus();
    },
    /** 气泡尺寸：宽由每行字数决定；高由「显示行数」决定，自适应时按实际字数缩短 */
    applyBubbleSize: function() {
      if (this.mode !== 'bubble') return;
      var perLine = Math.max(10, Math.min(40, this.cfg.bubbleCharsPerLine | 0));
      var maxLines = Math.max(1, Math.min(12, this.cfg.bubbleLines | 0));
      var fs = this.cfg.fontSize;
      var side = (this.mode === 'bubble' && (this.showWords || this.cfg.alwaysWords)) ? 214 : 0;
      var w = Math.max(260, perLine * fs + 36 + 60) + side;
      var hMax = Math.ceil(maxLines * fs * 1.9 + 28 + 48);
      var h = hMax;
      if (this.cfg.bubbleAutoSize) {
        var need = this.bubbleNeedLines(perLine, maxLines);
        h = Math.ceil(need * fs * 1.9 + 28 + 48);
        if (h > hMax) h = hMax;
      }
      this.host('resizeBubble', w, h);
    },
    /** 当前全文按每行字数折算需要几行（封顶 maxLines） */
    bubbleNeedLines: function(perLine, maxLines) {
      var raw = this.getText() || '';
      if (!raw) return 1;
      var segs = raw.split('\n');
      var n = 0;
      for (var i = 0; i < segs.length; i++) {
        n += Math.max(1, Math.ceil(segs[i].length / perLine));
        if (n >= maxLines) return maxLines;
      }
      return Math.max(1, n);
    },
    /** 气泡模式 + 词库常驻：给窗口右侧留出悬浮层位置（词库不压在输入框里） */
    syncSidewords: function() {
      var el = document.getElementById('app');
      if (!el) return;
      el.classList.toggle('sidewords',
          this.mode === 'bubble' && (this.showWords || this.cfg.alwaysWords));
    },
    /** 气泡拖动条双击：按设置决定是否还原完整界面 */
    onDragStripDbl: function() {
      if (this.cfg.bubbleDblRestore) this.exitBubble();
    },

    onShown: function() {
      var ed = document.getElementById('editor');
      if (ed) ed.focus();
      if (this.mode === 'bubble') this.applyBubbleSize();
    },

    /* ---------- 编辑动作 ---------- */
    /**
     * 在光标处插入文本。
     *
     * 不再依赖 document.execCommand('insertText')（WebView 里对失焦后的选区时常静默失败，
     * 表现为"点了词条什么也没发生"），直接操作 DOM Range，成功率高且可控。
     */
    insert: function(txt) {
      var ed = document.getElementById('editor');
      if (!ed) return;
      txt = (txt === undefined || txt === null) ? '' : String(txt);
      this._applyRange(this._caretRange(ed), txt);
      this.onInput();
    },
    /** 取插入点：缓存的选区 > 当前选区 > 文末 */
    _caretRange: function(ed) {
      var sel = window.getSelection();
      if (this._range && ed.contains(this._range.startContainer)) return this._range.cloneRange();
      if (sel && sel.rangeCount && ed.contains(sel.getRangeAt(0).startContainer))
        return sel.getRangeAt(0).cloneRange();
      var r = document.createRange();
      r.selectNodeContents(ed);
      r.collapse(false);
      return r;
    },
    /** 用 txt 替换 range，并把光标放到插入内容之后（唯一真正落字的入口） */
    _applyRange: function(range, txt) {
      var ed = document.getElementById('editor');
      try { ed.focus(); } catch (e) {}
      var sel = window.getSelection();
      try {
        range.deleteContents();
        var node = null;
        if (txt) {
          node = document.createTextNode(txt);
          range.insertNode(node);
        }
        var r2 = document.createRange();
        if (node) r2.setStartAfter(node);
        else r2.setStart(range.startContainer, range.startOffset);
        r2.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r2);
        this._range = r2.cloneRange();
      } catch (e) {
        if (txt) ed.appendChild(document.createTextNode(txt));
        this._range = null;
      }
    },
    /**
     * 光标位置归一化：返回 {node: 文本节点, off: 偏移}。
     *
     * 关键坑：光标经常"贴"在元素边界上（startContainer 是 DIV、offset 是子节点下标），
     * 例如 setText 之后、或点在某一行末尾。以前 replaceBefore 只认 nodeType===3，
     * 这种位置直接 return false —— 表现就是"z1 有时候能插，有时候没反应"。
     * 现在统一往前找最近的文本节点，把光标折算到它的末尾。
     */
    _caretInfo: function() {
      var sel = window.getSelection();
      if (!sel || !sel.rangeCount || !sel.isCollapsed) return null;
      var r = sel.getRangeAt(0);
      var node = r.startContainer, off = r.startOffset;
      if (node && node.nodeType === 3) return {node: node, off: off};
      if (!node || node.nodeType !== 1) return null;
      for (var i = Math.min(off, node.childNodes.length) - 1; i >= 0; i--) {
        var t = this._lastTextNode(node.childNodes[i]);
        if (t) return {node: t, off: t.textContent.length};
      }
      return null;
    },
    _lastTextNode: function(n) {
      if (!n) return null;
      if (n.nodeType === 3) return n;
      for (var i = n.childNodes.length - 1; i >= 0; i--) {
        var t = this._lastTextNode(n.childNodes[i]);
        if (t) return t;
      }
      return null;
    },
    /** 删掉光标前 n 个字符并插入 txt（z1~z9 / 快捷码 + Tab 用，替代 execCommand） */
    replaceBefore: function(n, txt) {
      var info = this._caretInfo();
      if (!info || info.off < n) return false;
      var del = document.createRange();
      del.setStart(info.node, info.off - n);
      del.setEnd(info.node, info.off);
      this._applyRange(del, txt);
      this.onInput();
      return true;
    },
    wrapSel: function(a, b) {
      var sel = window.getSelection().toString();
      this.insert(a + sel + b);
    },
    indent: function() { this.insert('\u3000\u3000'); },
    exec: function(cmd) {
      var ed = document.getElementById('editor');
      if (ed) ed.focus();
      try { document.execCommand(cmd, false, null); } catch (e) {}
      this.onInput();
    },
    /** 删除当前行：用 Selection.modify 以行边界扩选（WebKit 支持，IDEA Ctrl+Y 同款手感） */
    deleteLine: function() {
      var ed = document.getElementById('editor');
      if (!ed) return;
      ed.focus();
      var sel = window.getSelection();
      if (!sel.rangeCount) return;
      try {
        if (!sel.isCollapsed) sel.collapseToEnd();
        sel.modify('move', 'backward', 'lineboundary');
        sel.modify('extend', 'forward', 'lineboundary');
        document.execCommand('delete');
        document.execCommand('delete');   // 再删掉空行，整行消失
      } catch (e) { return; }
      this.onInput();
    },
    toggleBold: function() {
      this.cfg.bold = !this.cfg.bold;
      this.applyFont();
      this.toast(this.cfg.bold ? '已加粗' : '已取消加粗');
    },
    insertChar: function(c) { this.insert(c); this.showChars = false; },

    /* ---------- 词库 ---------- */
    toggleWords: function() {
      if (this.cfg.alwaysWords) { this.toast('词库已设为常驻（可在设置里关闭）'); return; }
      this.showWords = !this.showWords;
      this.showDocs = false;
      this.addingWord = false;
    },
    /** 点词条：一键插入（mousedown 阶段处理，避开失焦吞点击的坑） */
    pickWord: function(w, wi) {
      var content = w && w.content ? w.content : '';
      if (!content) return;
      if (!this.cfg.alwaysWords) this.showWords = false;   // 常驻时保持展开
      this.insert(content);
    },
    toggleAddWord: function() {
      this.addingWord = !this.addingWord;
      if (this.addingWord) { this.newCode = ''; this.newContent = ''; }
    },
    wordCode: function(i) { return 'z' + (i + 1); },
    reloadWords: function() { this.words = JSON.parse(this.host('getWords')); },
    saveWord: function() {
      var content = (this.newContent || '').trim();
      if (!content) { this.toast('请填写词条内容'); return; }
      this.host('addWord', (this.newCode || '').trim(), content);
      this.newContent = ''; this.newCode = '';
      this.addingWord = false;
      this.reloadWords();
      this.toast('词条已保存');
    },
    delWord: function(id) {
      this.host('deleteWord', id);
      this.reloadWords();
      this.toast('已删除词条');
    },
    insertWord: function(id) {
      var w = null;
      for (var i = 0; i < this.words.length; i++) if (this.words[i].id === id) w = this.words[i];
      if (!w) return;
      this.pickWord(w, 0);
    },

    /* ---------- 文件（多文档，Typora 式） ---------- */
    toggleDocs: function() {
      this.showDocs = !this.showDocs;
      this.showWords = false;
      if (this.showDocs) this.refreshDocs();
    },
    refreshDocs: function() {
      this.docs = JSON.parse(this.host('getDocs') || '[]');
    },
    fmtTime: function(ms) {
      var d = new Date(ms), p = function(n) { return (n < 10 ? '0' : '') + n; };
      return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    },
    setEditorRaw: function(text) {
      var ed = document.getElementById('editor');
      ed.innerText = text || '';
      try {
        var r = document.createRange();
        r.selectNodeContents(ed); r.collapse(false);
        var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
        this._range = r.cloneRange();
      } catch (e) {}
    },
    openDoc: function(name) {
      if (name === this.cfg.docName) { this.showDocs = false; return; }
      var txt = this.host('switchDoc', name);
      if (txt === '__ERR__' || txt === undefined) { this.toast('打开失败：' + name); return; }
      this.cfg.docName = name;
      this.setEditorRaw(txt);
      this.showDocs = false;
      this.docUnsaved = false;
      this.refreshStats();
      this.refreshDocs();
      this.toast('已打开 ' + name);
    },
    askNewDoc: function() {
      var self = this;
      this.askPrompt('新建文档', '新文档', '文件名，如：第一章', function(v) {
        var n = self.host('newDoc', v);
        if (!n) { self.toast('新建失败'); return; }
        self.cfg.docName = n;
        self.setEditorRaw('');
        self.showDocs = false;
        self.docUnsaved = false;
        self.refreshStats();
        self.refreshDocs();
        self.toast('已新建 ' + n);
      });
    },
    askRenameDoc: function(name) {
      var self = this;
      this.askPrompt('重命名', name.replace(/\.(txt|md)$/i, ''), '新文件名', function(v) {
        var n = self.host('renameDoc', name, v);
        if (!n || n === '__ERR__') { self.toast('重命名失败'); return; }
        if (self.cfg.docName === name) self.cfg.docName = n;
        self.refreshDocs();
        self.toast('已重命名为 ' + n);
      });
    },
    askDeleteDoc: function(name) {
      var self = this;
      this.askConfirm('删除文档「' + name + '」？删除后不可恢复（正文快照仍可在"寻回"里找到）。', function() {
        self.host('deleteDoc', name);
        var now = self.host('getDocName') || '';
        self.cfg.docName = now;
        self.setEditorRaw(self.host('getDraft') || '');
        self.docUnsaved = false;
        self.refreshStats();
        self.refreshDocs();
        self.toast('已删除 ' + name);
      });
    },
    importFile: function() {
      var n = this.host('importDocFile');
      if (!n) return;
      this.cfg.docName = n;
      this.setEditorRaw(this.host('getDraft') || '');
      this.showDocs = false;
      this.docUnsaved = false;
      this.refreshStats();
      this.refreshDocs();
      this.toast('已打开 ' + n);
    },

    /* ---------- 通用输入框（替代 prompt） ---------- */
    askPrompt: function(title, value, ph, cb) {
      var self = this;
      this.promptTitle = title;
      this.promptValue = value || '';
      this.promptPlaceholder = ph || '';
      this._promptCb = cb;
      this.promptShow = true;
      setTimeout(function() {
        var el = document.querySelector('.prompt-input');
        if (el) { el.focus(); el.select(); }
      }, 60);
    },
    promptOk: function() {
      var v = (this.promptValue || '').trim();
      var cb = this._promptCb;
      this.promptShow = false;
      this._promptCb = null;
      if (v && cb) cb(v);
    },

    /* ---------- 通用确认框（替代 confirm） ---------- */
    askConfirm: function(msg, cb) { this.confirmMsg = msg; this._confirmCb = cb; },
    runConfirm: function() {
      var cb = this._confirmCb;
      this.confirmMsg = ''; this._confirmCb = null;
      if (cb) cb();
    },
    askClearAll: function() {
      var self = this;
      this.showMore = false;
      this.askConfirm('确定清空全部文本？此操作不可恢复（可在"寻回"里找回快照）。', function() {
        self.host('clearAll');
      });
    },

    /* ---------- 全局快捷键 ---------- */
    /** 事件 -> "Ctrl+Shift+Z" 形式 */
    comboOf: function(e) {
      var k = e.key;
      /* WebKit 在部分输入法/布局下 e.key 给 'Unidentified'，这时退回 e.code，
         否则设置里录的键"看起来存了，实际永远匹配不上" */
      if (!k || k === 'Unidentified' || k === 'Process') k = this.keyFromCode(e.code);
      if (!k) return null;
      if (k === 'Control' || k === 'Shift' || k === 'Alt' || k === 'Meta') return null;
      var map = {' ':'Space', 'Escape':'Esc', 'ArrowUp':'Up', 'ArrowDown':'Down',
                 'ArrowLeft':'Left', 'ArrowRight':'Right'};
      if (map[k]) k = map[k];
      else if (k.length === 1 && k !== '+') k = k.toUpperCase();
      var p = [];
      if (e.ctrlKey) p.push('Ctrl');
      if (e.shiftKey) p.push('Shift');
      if (e.altKey) p.push('Alt');
      if (e.metaKey) p.push('Win');
      p.push(k);
      return p.join('+');
    },
    /** e.code -> 统一的键名（KeyZ / Digit1 / Comma ...） */
    keyFromCode: function(code) {
      if (!code) return null;
      var m = /^Key([A-Z])$/.exec(code);            if (m) return m[1];
      m = /^Digit([0-9])$/.exec(code);              if (m) return m[1];
      m = /^Numpad([0-9])$/.exec(code);             if (m) return m[1];
      m = /^F([0-9]{1,2})$/.exec(code);             if (m) return 'F' + m[1];
      var map = {Comma:',', Period:'.', Slash:'/', Semicolon:';', Quote:"'",
                 BracketLeft:'[', BracketRight:']', Backslash:'\\', Minus:'-', Equal:'=',
                 Backquote:'`', Space:'Space', Escape:'Esc', Enter:'Enter', Tab:'Tab',
                 Backspace:'Backspace', Delete:'Delete', Insert:'Insert',
                 ArrowUp:'Up', ArrowDown:'Down', ArrowLeft:'Left', ArrowRight:'Right',
                 Home:'Home', End:'End', PageUp:'PageUp', PageDown:'PageDown'};
      return map[code] || null;
    },
    startKeyCapture: function(id) { this.capturingAction = id; },
    onGlobalKey: function(e) {
      var self = this;

      /* 1) 设置里正在录制快捷键 */
      if (this.capturingAction) {
        e.preventDefault(); e.stopPropagation();
        if (e.key === 'Escape') { this.capturingAction = ''; return; }
        var c = this.comboOf(e);
        if (!c) return;
        this.host('setKey', this.capturingAction, c);
        this.cfg.keys[this.capturingAction] = c;
        this.capturingAction = '';
        return;
      }

      /* 2) Esc = 点掉所有弹层的 ✕（防止 ✕ 点不到就卡死在里面）
         顺序：先判断当前有没有弹层 → 有就全关；没有且处于气泡模式 → 退出气泡 */
      if (e.key === 'Escape') {
        e.preventDefault();
        var anyLayer = this.showSettings || this.showCloseModal || this.confirmMsg ||
                       this.showImportModal || this.showHistModal || this.showChars ||
                       this.showDocs || this.showWords || this.promptShow || this.showMore;
        this.showCloseModal = false;
        this.confirmMsg = ''; this._confirmCb = null;
        this.showImportModal = false;
        this.showHistModal = false;
        this.showChars = false;
        this.showDocs = false;
        this.showMore = false;
        this.promptShow = false; this._promptCb = null;
        if (!this.cfg.alwaysWords) { this.showWords = false; this.addingWord = false; }
        this.showSettings = false;
        this.syncFrozen();
        if (!anyLayer && this.mode === 'bubble') this.exitBubble();
        return;
      }

      var tag = (e.target && e.target.tagName || '').toUpperCase();
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      /* 3) Tab：词库快捷码展开（仅编辑区） */
      if (e.key === 'Tab' && e.target && e.target.id === 'editor') {
        e.preventDefault();
        this.expandShortcut();
        return;
      }

      /* 4) z + 数字：插入第 N 条词库（不按修饰键、仅编辑区） */
      if (/^[1-9]$/.test(e.key) && !e.ctrlKey && !e.altKey && !e.metaKey
          && e.target && e.target.id === 'editor') {
        var info = this._caretInfo();
        if (info) {
          /* 只要求光标前紧挨着一个 z（以前还要求 z 前不是字母数字，导致时灵时不灵） */
          var before = (info.node.textContent || '').substring(0, info.off);
          if (/z$/i.test(before)) {
            var idx = parseInt(e.key, 10) - 1;
            if (idx < this.words.length) {
              e.preventDefault();
              this.replaceBefore(1, this.words[idx].content);
              return;
            }
          }
        }
      }

      /* 5) 自定义快捷键匹配 */
      var combo = this.comboOf(e);
      if (!combo) return;
      var keys = this.cfg.keys || {};
      for (var id in keys) {
        if (!keys.hasOwnProperty(id)) continue;
        if (keys[id] !== combo) continue;
        if (this.runAction(id)) { e.preventDefault(); return; }
      }
    },
    /** 执行动作；返回 true 表示已处理 */
    runAction: function(id) {
      switch (id) {
        case 'undo': this.exec('undo'); return true;
        case 'redo': this.exec('redo'); return true;
        case 'deleteLine': this.deleteLine(); return true;
        case 'indent': this.indent(); return true;
        case 'quote': this.wrapSel('“', '”'); return true;
        case 'dash': this.insert('——'); return true;
        case 'ellipsis': this.insert('……'); return true;
        case 'book': this.wrapSel('《', '》'); return true;
        case 'chars': this.showCharsAt(null); return true;
        case 'bold': this.toggleBold(); return true;
        case 'import': this.importDoc(); return true;
        case 'history': this.showHist(); return true;
        case 'save': this.saveNow(); return true;
        case 'export': this.host('exportTxt'); return true;
        case 'words': this.toggleWords(); return true;
        case 'bubble': this.mode === 'bubble' ? this.exitBubble() : this.enterBubble(); return true;
        case 'settings': if (this.showSettings) this.closeSettings(); else this.openSettings(); return true;
        case 'pin': this.togglePin(); return true;
      }
      return false;
    },

    /** 快捷码 + Tab 展开（最长匹配） */
    expandShortcut: function() {
      var info = this._caretInfo();
      if (!info) return;
      var before = (info.node.textContent || '').substring(0, info.off).toLowerCase();
      var best = null, bestLen = 0;
      for (var i = 0; i < this.words.length; i++) {
        var c = (this.words[i].code || '').trim().toLowerCase();
        if (c && before.slice(-c.length) === c && c.length > bestLen) {
          best = this.words[i]; bestLen = c.length;
        }
      }
      if (!best) return;
      this.replaceBefore(bestLen, best.content);
    },

    /* ---------- 特殊字符 / 更多 ---------- */
    showCharsAt: function(e) {
      this.charsPos = e ? {left: Math.min(e.clientX, window.innerWidth - 330) + 'px',
                           top: (e.clientY + 12) + 'px'}
                        : {left: '40px', top: '110px'};
      this.showChars = !this.showChars;
    },

    /* ---------- 文档导入 ---------- */
    importDoc: function() { this.showMore = false; this.host('importDoc'); },
    showImport: function(name, text) {
      this.impName = name;
      this.impText = text;
      this.showImportModal = true;
    },
    importApply: function(how) {
      var ed = document.getElementById('editor');
      if (how === 'copy') {
        var ta = document.createElement('textarea');
        ta.value = this.impText;
        ta.style.position = 'fixed'; ta.style.left = '-9999px';
        document.body.appendChild(ta); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
        this.toast('已复制到剪贴板');
      } else if (how === 'append') {
        ed.innerText = this.getText() + '\n\n' + this.impText;
        this.refreshStats(); this.showImportModal = false; this.toast('已追加到正文');
      } else {
        ed.innerText = this.impText;
        this.refreshStats(); this.showImportModal = false; this.toast('已替换正文');
      }
    },

    /* ---------- 文字寻回 ---------- */
    showHist: function() {
      this.showMore = false;
      this.histList = JSON.parse(this.host('listHistory') || '[]');
      this.showHistModal = true;
    },
    restoreHist: function(id) {
      var self = this;
      this.askConfirm('恢复该快照？当前内容会先自动存成一条新快照，可再寻回。', function() {
        self.refreshStats();
        self.host('restoreHistory', id);
      });
    },

    /* ---------- 设置 ---------- */
    startCapture: function() { this.capturing = true; this.host('beginCapture'); },
    onCapture: function(combo) {
      this.capturing = false;
      if (!combo) { this.toast('已取消捕获'); return; }
      var r = this.host('setBoss', combo);
      if (r === 'invalid') { this.toast('组合无效'); return; }
      this.cfg.boss = combo;
      this.cfg.bossEnabled = true;
      /* Ctrl+C 这类常用组合会让"复制文字"变成"隐藏窗口"，必须提醒（但不拦着） */
      var risky = /^(Ctrl|Alt|Win)\+(C|V|X|A|S|Z|Y|F|W|T|N|P)$/i.test(combo);
      this.toast(risky ? '已设为 ' + combo + '。注意：这是常用快捷键，极易误触，建议换个组合'
                       : '老板键已设为 ' + combo);
    },
    resetKeys: function() {
      this.host('resetKeys');
      var o = JSON.parse(this.host('getConfig'));
      this.cfg.keys = o.keys || {};
      this.toast('快捷键已恢复默认');
    },

    /* ---------- 主题（单色 / 双拼渐变） ---------- */
    toggleThemeMode: function() {
      this.cfg.themeMode = this.cfg.themeMode === 'gradient' ? 'solid' : 'gradient';
      this.applyTheme();
    },
    setPair: function(p) { this.cfg.accent = p.a; this.cfg.accent2 = p.b; this.applyTheme(); },
    applyTheme: function() {
      this.applyVars();
      this.host('setTheme', this.cfg.themeMode, this.cfg.accent, this.cfg.accent2);
    },
    applyVars: function() {
      var st = document.documentElement.style;
      /* 深色 / 浅色皮肤：整体切换 CSS 令牌 */
      var html = document.documentElement;
      if (this.cfg.skin === 'dark') html.classList.add('dark');
      else html.classList.remove('dark');
      var a = this.cfg.accent || '#6366F1';
      var b = this.cfg.accent2 || '#22D3EE';
      st.setProperty('--accent', a);
      st.setProperty('--accent2', b);
      st.setProperty('--header-bg', this.cfg.themeMode === 'solid'
          ? a : 'linear-gradient(135deg,' + a + ',' + b + ')');
      st.setProperty('--fs', this.cfg.fontSize + 'px');
      if (this._fontData) {
        var fs = document.getElementById('dynfont');
        if (!fs) {
          fs = document.createElement('style');
          fs.id = 'dynfont';
          document.head.appendChild(fs);
        }
        if (fs.textContent.indexOf('SnapWriteCustom') < 0)
          fs.textContent = "@font-face{font-family:'SnapWriteCustom';src:url(" + this._fontData + ");}";
        st.setProperty('--font', "'SnapWriteCustom','Segoe UI','Microsoft YaHei',sans-serif");
      } else {
        st.setProperty('--font', "'Segoe UI','Microsoft YaHei',sans-serif");
      }
    },
    applyFont: function() {
      this.applyVars();
      this.host('setAppearance', this.cfg.accent, this.cfg.fontSize, this.cfg.bold);
      this.applyBubbleSize();
    },
    toggleAuto: function() {
      this.autoStart = !this.autoStart;
      this.host('setAutoStart', this.autoStart);
      this.toast(this.autoStart ? '已开启开机自启动' : '已关闭开机自启动');
    },
    /** 改一下就生效：防抖 140ms 后统一把全部设置下发给 Java 并落盘 */
    queueApply: function() {
      var self = this;
      clearTimeout(this._applyTimer);
      this._applyTimer = setTimeout(function() { self.applyAll(); }, 140);
    },
    /**
     * 立即下发全部设置（不需要"保存"按钮：改完就走这条路）。
     * 所有 setter 都是幂等的，重复调用没有副作用。
     */
    applyAll: function() {
      var g = this.host('setGesture', this.gestureInput, this.cfg.gestureEnabled);
      if (g === 'invalid') {
        /* 手势还没输完（比如只打了个 L），先不下发，等输全 */
        this.gestureInput = this.cfg.gesture || 'LLRRLL';
      } else {
        this.cfg.gesture = this.gestureInput;
      }
      this.host('setBubbleConfig', this.cfg.bubbleMaxChars, this.cfg.bubbleCharsPerLine);
      this.host('setAppearance', this.cfg.accent, this.cfg.fontSize, this.cfg.bold);
      this.host('setTheme', this.cfg.themeMode, this.cfg.accent, this.cfg.accent2);
      this.host('setSkin', this.cfg.skin || 'light');
      this.host('setWallpaperOpacity', this.cfg.wallpaperOpacity);
      this.host('setWallpaperFit', this.cfg.wallpaperFit);
      this.host('setBubbleLines', this.cfg.bubbleLines);
      this.host('setBubbleCustom', this.cfg.bubbleCustomChars);
      this.host('setBubbleCloudConfig', this.cfg.bubbleCloud, this.cfg.bubbleRhythm,
                this.cfg.bubbleCloudKind, this.cfg.cloudOpacity);
      this.host('setBubbleFlags', this.cfg.bubbleDblRestore, this.cfg.bubbleAutoSize);
      this.host('setBubbleIntrude', this.cfg.bubbleIntrude,
                this.cfg.intrudeOpacity, this.cfg.intrudeSize);
      this.host('setAutoSave', this.cfg.autoSave);
      this.host('setWordsAlways', this.cfg.alwaysWords);
      this.host('setCloseAction', this.cfg.closeAction);
      this.applyVars();
      this.syncSidewords();
      this.applyBubbleSize();
    },
    /** 关闭设置（✕ / Esc 同一条路） */
    closeSettings: function() {
      this.showSettings = false;
      this.syncFrozen();
      var ed = document.getElementById('editor');
      if (ed) ed.focus();
    },
    /** 打开设置面板 */
    openSettings: function() { this.showSettings = true; },
    /** 深色主题开关 */
    toggleSkin: function() {
      this.cfg.skin = (this.cfg.skin === 'dark') ? 'light' : 'dark';
      this.applyVars();
      this.host('setSkin', this.cfg.skin);
      this.toast(this.cfg.skin === 'dark' ? '已切换深色主题' : '已切换浅色主题');
    },

    /* ---------- 外观：背景图 / 字体 / 音乐 ---------- */
    wpStyle: function() {
      var f = this.cfg.wallpaperFit || 'cover';
      var size = (f === 'cover' || f === 'contain') ? f : 'auto';
      return {
        backgroundImage: 'url(' + this.wallpaper + ')',
        backgroundSize: size,
        backgroundRepeat: f === 'tile' ? 'repeat' : 'no-repeat',
        backgroundPosition: 'center',
        opacity: (this.cfg.wallpaperOpacity || 40) / 100
      };
    },
    pickWallpaper: function() {
      var d = this.host('pickWallpaper');
      if (d) { this.wallpaper = d; this.toast('背景图片已设置（显示在写作区）'); }
    },
    clearWallpaper: function() {
      this.host('clearWallpaper');
      this.wallpaper = '';
      this.toast('已清除背景图片');
    },
    pickFont: function() {
      var name = this.host('pickFont');
      if (name) {
        this.fontFamily = name;
        this._fontData = this.host('getFontData');
        this.applyVars();
        this.toast('字体已应用：' + name);
      } else this.toast('未选择字体');
    },
    clearFont: function() {
      this.host('clearFont');
      this.fontFamily = '';
      this._fontData = '';
      this.applyVars();
      this.toast('已恢复默认字体');
    },
    pickCustomIcon: function() {
      var d = this.host('pickCustomIcon');
      if (d) { this.customIcon = d; this.iconUrl = d; this.toast('图标已更换（窗口/任务栏/托盘）'); }
    },
    resetCustomIcon: function() {
      this.host('resetCustomIcon');
      this.customIcon = '';
      this.iconUrl = this.host('getIcon') || '';
      this.toast('已恢复默认图标');
    },

    /* ---------- 插件 ---------- */
    reloadPlugins: function() {
      this.plugins = JSON.parse(this.host('listPlugins') || '[]');
      this.toast(this.plugins.length ? '已装 ' + this.plugins.length + ' 个插件' : '暂无插件');
    },

    pickMusic: function() {
      var d = this.host('pickMusic');
      if (d) { this.bgmUrl = d; this.playMusic(); }
    },
    clearMusic: function() {
      this.host('clearMusic');
      this.bgmUrl = '';
      this.stopMusic();
    },
    toggleMusic: function() { this.musicOn ? this.stopMusic() : this.playMusic(); },
    playMusic: function() {
      var self = this;
      if (!this.bgmUrl) {
        var d = this.host('getBgm');
        if (d) this.bgmUrl = d;
      }
      if (!this.bgmUrl) { this.pickMusic(); return; }
      var a = this._audio;
      if (!a) { a = this._audio = new Audio(); a.loop = true; }
      if (a.src !== this.bgmUrl) a.src = this.bgmUrl;
      var p = a.play();
      if (p && p.then) p.then(function() {
        try {
          var ctx = self._actx ||
                (self._actx = new (window.AudioContext || window.webkitAudioContext)());
          if (!self._anl) {
            var an = self._anl = ctx.createAnalyser();
            an.fftSize = 256;
            ctx.createMediaElementSource(a).connect(an);
            an.connect(ctx.destination);
          }
          self._freq = new Uint8Array(self._anl.frequencyBinCount);
          self.musicOn = true;
          self.toast('背景音乐播放中');
        } catch (e) { self.musicOn = true; self.toast('背景音乐播放中'); }
      }).catch(function() { self.toast('音乐播放失败'); });
      else { this.musicOn = true; }
    },
    stopMusic: function() {
      if (this._audio) this._audio.pause();
      this.musicOn = false;
      this._musicE = 0;
    },

    /* ---------- 气泡环绕元素 + 节奏律动 ---------- */
    addDecor: function(c) { this.cfg.bubbleCustomChars = (this.cfg.bubbleCustomChars || '') + c; this.applyBubbleDecor(); },
    applyBubbleDecor: function() {
      this.host('setBubbleCustom', this.cfg.bubbleCustomChars);
      if (this.cfg.bubbleCloudKind !== 'custom') {
        this.cfg.bubbleCloudKind = 'custom';
      }
      this.host('setBubbleCloudConfig', this.cfg.bubbleCloud, this.cfg.bubbleRhythm,
                this.cfg.bubbleCloudKind, this.cfg.cloudOpacity);
    },
    initClouds: function() {
      var cv = document.getElementById('cloudcv');
      if (!cv) return;
      this._cv = cv;
      this._cx = cv.getContext('2d');
      this._energy = 0;
      this._musicE = 0;
      this._puffs = [];
      for (var i = 0; i < 18; i++) {
        this._puffs.push({
          t: i / 18,
          s: 0.7 + Math.random() * 0.9,
          ph: Math.random() * 6.28,
          sp: 0.25 + Math.random() * 0.4
        });
      }
      var self = this;
      requestAnimationFrame(function loop(ts) {
        requestAnimationFrame(loop);
        self.cloudLoop(ts);
      });
    },
    pulse: function() {
      if (this.cfg && this.cfg.bubbleRhythm) this._energy = Math.min(1, (this._energy || 0) + 0.4);
    },
    cloudLoop: function(ts) {
      var cv = this._cv, cx = this._cx;
      if (!cv || !cx) return;
      if (this.mode !== 'bubble' || !this.cfg.bubbleCloud) {
        if (cv.__dirty) { cx.clearRect(0, 0, cv.width, cv.height); cv.__dirty = false; }
        return;
      }
      var dpr = window.devicePixelRatio || 1;
      var w = cv.clientWidth, h = cv.clientHeight;
      if (!w || !h || w > 5000 || h > 5000) return;   // 尺寸异常时不画（防画到屏幕外）
      if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
      cv.__dirty = true;
      cx.setTransform(dpr, 0, 0, dpr, 0, 0);
      cx.clearRect(0, 0, w, h);

      this._energy = (this._energy || 0) * 0.95;
      if (this._anl && this._freq && this.musicOn) {
        this._anl.getByteFrequencyData(this._freq);
        var sum = 0;
        for (var k = 0; k < this._freq.length; k++) sum += this._freq[k];
        this._musicE = this._musicE * 0.8 + (sum / this._freq.length / 255) * 0.2;
      } else this._musicE = 0;
      var rhythmOn = !!this.cfg.bubbleRhythm;
      var en = rhythmOn ? Math.max(this._energy, this._musicE * 1.6) : 0;

      var base = rhythmOn ? 0.78 + 0.14 * Math.sin(ts / 1100) : 0.9;
      /* 整体压暗：环绕元素是氛围，不该抢正文的注意力 */
      var alpha = (this.cfg.cloudOpacity / 100) * base * (1 + en * 0.4) * 0.62;
      if (alpha > 1) alpha = 1;

      /* 染色：主题色向白靠，白底和深色桌面都看得见 */
      var hex = (this.cfg.accent || '#6366F1').replace('#', '');
      var r = parseInt(hex.substr(0, 2), 16) || 99,
          g = parseInt(hex.substr(2, 2), 16) || 102,
          b = parseInt(hex.substr(4, 2), 16) || 241;
      var fill = 'rgba(' + Math.round(r * 0.55 + 110) + ',' +
                 Math.round(g * 0.55 + 110) + ',' +
                 Math.round(b * 0.55 + 110) + ',';

      var m = 15;
      var per = 2 * (w - 2 * m) + 2 * (h - 2 * m);
      var drift = (ts / 1000) * 12;
      var kind = this.cfg.bubbleCloudKind || 'cloud';
      var chars = (this.cfg.bubbleCustomChars || '').split('');
      if (kind === 'custom' && !chars.length) kind = 'cloud';   // 自定义为空时兜底显示云朵

      for (var i = 0; i < this._puffs.length; i++) {
        var p = this._puffs[i];
        var d = ((p.t * per + drift * p.sp + p.ph * 30) % per + per) % per;
        var x, y;
        if (d < w - 2 * m) { x = m + d; y = m; }
        else if (d < w - 2 * m + h - 2 * m) { x = w - m; y = m + (d - (w - 2 * m)); }
        else if (d < 2 * (w - 2 * m) + h - 2 * m) {
          x = w - m - (d - (w - 2 * m) - (h - 2 * m)); y = h - m;
        } else { x = m; y = h - m - (d - 2 * (w - 2 * m) - (h - 2 * m)); }
        var bob = Math.sin(ts / 700 * p.sp + p.ph) * 3 + en * Math.sin(ts / 130 + p.ph) * 4;
        var sc = p.s * (1 + en * 0.3);
        if (kind === 'custom') this.drawChar(cx, x, y + bob, sc, alpha, chars, p, i);
        else this.drawPuff(cx, x, y + bob, sc, alpha, kind, ts, p.ph, fill);
      }

      /* 侵入文本框：挑三分之一的元素飘进输入区内部，透明度与大小可调 */
      var appEl = document.getElementById('app');
      if (appEl) appEl.classList.toggle('intrude', !!this.cfg.bubbleIntrude);
      if (this.cfg.bubbleIntrude) {
        var iA = (this.cfg.intrudeOpacity || 40) / 100 *
                 (rhythmOn ? 0.82 + 0.18 * Math.sin(ts / 1300) : 1);
        var iS = (this.cfg.intrudeSize || 100) / 100;
        for (var j = 0; j < this._puffs.length; j += 3) {
          var q = this._puffs[j];
          /* 用相位做稳定的伪随机落点，再叠一层缓慢漂移，不抖动 */
          var fx = 0.12 + 0.76 * ((Math.sin(q.ph * 3.1) + 1) / 2);
          var fy = 0.16 + 0.68 * ((Math.cos(q.ph * 2.7) + 1) / 2);
          var ix = w * fx + Math.sin(ts / 2600 + q.ph) * 10;
          var iy = h * fy + Math.cos(ts / 3100 + q.ph * 1.4) * 8;
          var isc = q.s * iS * 0.8;
          if (kind === 'custom') this.drawChar(cx, ix, iy, isc, iA, chars, q, j);
          else this.drawPuff(cx, ix, iy, isc, iA, kind, ts, q.ph, fill);
        }
      }
    },
    drawChar: function(cx, x, y, sc, alpha, chars, p, i) {
      if (!chars.length) return;
      cx.save();
      cx.translate(x, y + 6 * sc);
      cx.globalAlpha = alpha;
      cx.fillStyle = (this.cfg.accent || '#6366F1');
      cx.font = Math.round(15 * sc) + 'px "Segoe UI Symbol",serif';
      cx.textAlign = 'center';
      cx.fillText(chars[i % chars.length], 0, 0);
      cx.restore();
    },
    drawPuff: function(cx, x, y, sc, alpha, kind, ts, ph, fill) {
      cx.save();
      cx.translate(x, y);
      cx.globalAlpha = alpha;
      if (kind === 'star') {
        cx.fillStyle = fill + '0.95)';
        cx.strokeStyle = 'rgba(255,255,255,.85)';
        cx.lineWidth = 1;
        var r = 8 * sc, r2 = 3 * sc;
        cx.beginPath();
        for (var i = 0; i < 8; i++) {
          var ang = i * Math.PI / 4 - Math.PI / 2;
          var rad = (i % 2 === 0) ? r : r2;
          var px = Math.cos(ang) * rad, py = Math.sin(ang) * rad;
          if (i === 0) cx.moveTo(px, py); else cx.lineTo(px, py);
        }
        cx.closePath();
        cx.fill(); cx.stroke();
      } else if (kind === 'petal') {
        cx.fillStyle = fill + '0.9)';
        cx.rotate(ts / 1400 + ph);
        cx.beginPath();
        cx.ellipse(0, 0, 9 * sc, 4 * sc, 0, 0, 6.29);
        cx.fill();
      } else if (kind === 'note') {
        cx.fillStyle = fill + '0.95)';
        cx.font = Math.round(15 * sc) + 'px serif';
        cx.textAlign = 'center';
        cx.fillText(ph > 3.14 ? '♪' : '♫', 0, 0);
      } else {
        /* 云朵：几个圆叠成蓬松小云 + 柔光边 */
        cx.shadowColor = 'rgba(255,255,255,.85)';
        cx.shadowBlur = 6 * sc;
        cx.fillStyle = fill + '0.92)';
        cx.strokeStyle = 'rgba(255,255,255,.9)';
        cx.lineWidth = 1.1;
        cx.beginPath();
        cx.arc(-7 * sc, 2 * sc, 5.2 * sc, 0, 6.29);
        cx.arc(0, -3 * sc, 6.8 * sc, 0, 6.29);
        cx.arc(7.5 * sc, 1.6 * sc, 4.8 * sc, 0, 6.29);
        cx.arc(0.5 * sc, 3.6 * sc, 5.6 * sc, 0, 6.29);
        cx.fill(); cx.stroke();
      }
      cx.restore();
    },

    toast: function(msg) {
      var self = this;
      this.toastMsg = msg;
      clearTimeout(this._toastTimer);
      this._toastTimer = setTimeout(function() { self.toastMsg = ''; }, 1800);
    },

    /** 截图测试：填充示例内容 */
    testFill: function() {
      var ed = document.getElementById('editor');
      ed.innerText = '第一章 青云起\n\n山门外的雨下了整整三日，沈砚之站在石阶尽头，\n望着云雾里的九重飞檐，握紧了掌心那半枚残玉。\n\n“今日之后，再无退路。”';
      this.stats = {today: 128, total: 128};
    }
  }
});

/* 挂载；mount 返回组件代理实例，Java 端 window.app.* 调用方式保持不变 */
try {
  var vm = app.mount('#app');
  window.app = vm;
} catch (e) {
  window.__errs.push('MOUNT: ' + (e && e.message ? e.message : e));
  if (e && e.stack) window.__errs.push('STACK: ' + String(e.stack).substring(0, 500));
  window.app = null;
}

/* 插件 API（插件 .js 在 Java 端于 initHost 之后执行，此对象必须先就绪） */
window.snapwrite = {
  version: 1,
  insert: function(t) { app.insert(String(t)); },
  getText: function() { return app.getText(); },
  setText: function(t) {
    document.getElementById('editor').innerText = String(t);
    app.onInput();
  },
  toast: function(m) { app.toast(String(m)); },
  host: function(n, a, b, c, d) { return app.host(n, a, b, c, d); }
};

/* 等待 Java 桥就绪后初始化（轮询，host 挂载在页面 load 完成之后） */
var hostWait = setInterval(function() {
  if (window.__hostReady && window.host && window.app) {
    clearInterval(hostWait);
    app.initHost();
  }
}, 120);

/* 点击空白处收起弹出层 */
document.addEventListener('mousedown', function(e) {
  var el = e.target;
  if (!el || !el.closest) return;
  if (!el.closest('.pop') && !el.closest('.toolbar')) {
    app.showMore = false;
    app.showChars = false;
    if (!el.closest('.pill') && !el.closest('.wpop')) {
      app.showWords = false;
      app.addingWord = false;
    }
  }
});
