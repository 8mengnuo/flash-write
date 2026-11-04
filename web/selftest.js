/* ════════════════════════════════════════════════════════════════
   闪写自测脚本（--selftest 时由 Java 注入执行）

   · 同步段先跑完，把结果存进 out[]
   · 气泡布局要等 Vue 把 DOM 更新完，所以放到 setTimeout 里，最后写 window.__stResult
   · Java 端轮询 window.__stResult，拿到就打印退出

   跑在 data/selftest 这套隔离数据里，不会碰到用户的真实草稿。
   ════════════════════════════════════════════════════════════════ */
(function () {
  var out = [];
  function ok(name, cond) { out.push((cond ? 'PASS  ' : 'FAIL  ') + name); }
  var app = window.app;
  if (!app) { window.__stResult = 'FAIL  前端未初始化（window.app 不存在）'; return 'PENDING'; }

  var ed = document.getElementById('editor');
  function caretToEnd() {
    var r = document.createRange();
    r.selectNodeContents(ed);
    r.collapse(false);
    var s = window.getSelection();
    s.removeAllRanges(); s.addRange(r);
    app._range = r.cloneRange();
  }
  function key(k, opts) {
    opts = opts || {};
    var ev = new KeyboardEvent('keydown', {
      key: k, code: opts.code || '', bubbles: true, cancelable: true,
      ctrlKey: !!opts.ctrl, shiftKey: !!opts.shift, altKey: !!opts.alt
    });
    (opts.target || ed).dispatchEvent(ev);
  }

  /* 0) 旧的"保存/询问"逻辑已彻底删掉 */
  ok('旧脏标记已删除', typeof app._dirty === 'undefined');
  ok('旧保存询问框已删除', typeof app.showSaveAsk === 'undefined');

  /* 1) 点词条 -> 落在光标处 */
  ed.innerText = 'abc';
  caretToEnd();
  app.pickWord({ id: 9001, code: '', content: '沈砚之' }, 0);
  ok('词条一键插入（光标处）[' + app.getText() + ']', app.getText() === 'abc沈砚之');

  /* 2) z + 数字 快捷码 */
  app.words = [{ id: 9001, code: '', content: '沈砚之' }];
  ed.innerText = 'abz';
  caretToEnd();
  key('1', { code: 'Digit1' });
  ok('z1 快捷码插入[' + app.getText() + ']', app.getText() === 'ab沈砚之');

  /* 3) 自定义快捷码 + Tab */
  app.words = [{ id: 9002, code: 'sy', content: '苏晚' }];
  ed.innerText = 'ab sy';
  caretToEnd();
  key('Tab', { code: 'Tab' });
  ok('快捷码+Tab 展开[' + app.getText() + ']', app.getText() === 'ab 苏晚');

  /* 3.5) 自定义快捷码直接生效：前缀+码（以前只认 z1/z2 序号，用户的码完全没用） */
  app.cfg.wordPrefix = 'z';
  app.words = [{ id: 9301, code: 'cq', content: '春秋' },
               { id: 9302, code: 'rx', content: '燃霞诀' }];
  ed.innerText = 'abzc';               // 真实输入：zc 已落下，这次按 q 补完码
  caretToEnd();
  key('q', { code: 'KeyQ' });
  ok('前缀+自定义码插入[' + app.getText() + ']', app.getText() === 'ab春秋');
  ed.innerText = 'abz';
  caretToEnd();
  key('1', { code: 'Digit1' });
  ok('序号快捷码仍可用[' + app.getText() + ']', app.getText() === 'ab春秋');
  ok('前缀+码匹配函数', JSON.stringify(app.prefixMatch('zcq', '')) === '{"len":3,"content":"春秋"}');

  /* 3.6) 设置打开时快捷键不能动正文（以前在设置里按 Ctrl+B 会整篇加粗） */
  ed.innerText = '正文';
  caretToEnd();
  app.showSettings = true;
  var lb = app.onHostKey('Ctrl+B');
  ok('设置里按 Ctrl+B 不动正文[' + lb + '/' + app.cfg.bold + ']',
     lb === false && app.cfg.bold === false);
  app.showSettings = false;
  app.onHostKey('Ctrl+B');
  /* 新语义：没选中文字时 Ctrl+B 不再切"整篇加粗"，默认粗细由正文样式决定 */
  ok('未选中时 Ctrl+B 不动整篇[' + app.cfg.bold + ']', app.cfg.bold === false);
  app.cfg.bold = false;
  app.applyFont();

  /* 3.7) 录制快捷键：按键只取值、不执行动作；Esc 取消保留原键 */
  app.startKeyCapture('export');
  var hk = app.onHostKey('Ctrl+Shift+K');
  ok('录制快捷键生效[' + app.cfg.keys.export + ']',
     hk === true && app.cfg.keys.export === 'Ctrl+Shift+K' && app.capturingAction === '');
  app.startKeyCapture('export');
  app.onHostKey('Esc');
  ok('录制中 Esc 取消且保留原键',
     app.capturingAction === '' && app.cfg.keys.export === 'Ctrl+Shift+K');
  app.host('setKey', 'export', 'Ctrl+E');
  app.cfg.keys.export = 'Ctrl+E';

  /* 3.8) 选中加粗只影响选中段（以前不管选没选中都切整篇） */
  ed.innerText = '甲乙丙';
  var rb = document.createRange();
  rb.setStart(ed.firstChild, 1);
  rb.setEnd(ed.firstChild, 2);
  var sb = window.getSelection();
  sb.removeAllRanges(); sb.addRange(rb);
  app._range = rb.cloneRange();
  app.cfg.bold = false;
  app.toggleBold();
  var bhtml = ed.innerHTML.toLowerCase();
  ok('选中加粗只影响选中段[' + ed.innerHTML + ']',
     (bhtml.indexOf('<b') >= 0 || bhtml.indexOf('bold') >= 0 ||
      bhtml.indexOf('font-weight') >= 0)
       && app.cfg.bold === false && app.getText() === '甲乙丙');
  ed.innerText = '';
  app._range = null;

  /* 3.9) 寻回：快照能列出来，确认后正文真的恢复（修复"点了恢复没反应"） */
  app.host('saveNow', '寻回甲');          // 手动保存 = 强制留档点
  app.host('saveNow', '寻回乙');
  var hs = JSON.parse(app.host('listHistory') || '[]');
  ok('寻回快照可列出[' + hs.length + ']', hs.length >= 2);
  var newest = hs.length ? hs[0].id : -1;
  var rtxt = app.host('restoreDocHistory', app.cfg.docName, newest);
  ok('恢复快照返回正文[' + rtxt + ']', String(rtxt) === '寻回乙');
  ed.innerText = '现在写的东西';
  caretToEnd();
  app.showHistFor(app.cfg.docName);
  app.restoreHist(newest);
  var asked = !!app.confirmMsg;
  app.runConfirm();
  ok('寻回：确认后正文真的恢复[' + app.getText() + ']',
     asked && app.getText() === '寻回乙' && app.showHistModal === false);

  /* 3.85) 词库输入兜底：输入法组字落字后（不走 keydown）也能展开自定义码
     （以前 _quickWord 只认"前缀+数字"，用户的码在输入法下完全没用） */
  app.cfg.wordPrefix = 'z';
  app.words = [{ id: 9401, code: 'cq', content: '春秋' }];
  ed.innerText = 'abzcq';
  caretToEnd();
  app._quickWord();
  ok('输入兜底：自定义码展开[' + app.getText() + ']', app.getText() === 'ab春秋');
  app.words = [{ id: 9402, code: '', content: '沈砚之' }];
  ed.innerText = 'abz1';
  caretToEnd();
  app._quickWord();
  ok('输入兜底：序号仍可用[' + app.getText() + ']', app.getText() === 'ab沈砚之');
  app.words = [];
  ed.innerText = '';

  /* 3.87) 右键样式菜单：能开、能认当前级别、套用生效 */
  ed.innerText = '第二回 入门';
  caretToEnd();
  app.ctxOpen({ clientX: 60, clientY: 60 });
  ok('右键菜单打开且识别为正文[' + app.ctxCur + ']', app.ctxShow === true && app.ctxCur === 'p');
  app.ctxApply('h3');
  ok('右键套用标题3', app.ctxShow === false && !!ed.querySelector('h3') &&
     ed.querySelector('h3').textContent === '第二回 入门');
  caretToEnd();
  app.ctxOpen({ clientX: 60, clientY: 60 });
  ok('右键识别当前级别为h3[' + app.ctxCur + ']', app.ctxCur === 'h3');
  app.ctxShow = false;
  app.setBlock('p');

  /* 3.872) 右键直接改标题的字体/字号/加粗 —— 而且只动这一段（正文不受影响） */
  ed.innerHTML = '<h3>标题行</h3><p>正文行</p>';
  var h3el = ed.querySelector('h3');
  var rh3 = document.createRange();
  rh3.setStart(h3el.firstChild, 1); rh3.collapse(true);
  var sh3 = window.getSelection(); sh3.removeAllRanges(); sh3.addRange(rh3);
  app.ctxOpen({ clientX: 60, clientY: 60 });
  ok('右键识别标题3且范围=整段[' + app.ctxCur + '/' + app.ctxScope + ']',
     app.ctxCur === 'h3' && app.ctxScope === '整段');
  app.ctxSetFont('楷体');
  var h3ff = h3el.querySelector('span[style*="font-family"]');
  ok('标题右键改字体[' + (h3ff ? h3ff.style.fontFamily : '-') + ']',
     !!h3ff && h3ff.style.fontFamily.indexOf('楷体') >= 0);
  app.ctxSetSize(30);
  var h3sz = h3el.querySelector('span[style*="font-size"]');
  ok('标题右键改字号[' + (h3sz ? h3sz.style.fontSize : '-') + ']',
     !!h3sz && h3sz.style.fontSize === '30px');
  app.ctxToggle('bold');                       // 标题样式自带粗体 → 这一下取消
  var h3wt = h3el.querySelector('span[style*="font-weight"]');
  ok('标题右键取消加粗[' + (h3wt ? h3wt.style.fontWeight : '-') + ']',
     !!h3wt && h3wt.style.fontWeight === '400');
  var pEl = ed.querySelector('p');
  ok('右键改标题不牵连正文', pEl.innerHTML === '正文行');
  /* 没选中时"整段"生效；选中了只动选中那一截 */
  var rr2 = document.createRange();
  rr2.setStart(pEl.firstChild, 0); rr2.setEnd(pEl.firstChild, 2);
  var ss2 = window.getSelection(); ss2.removeAllRanges(); ss2.addRange(rr2);
  app.ctxOpen({ clientX: 60, clientY: 60 });
  ok('有选区时范围=选中字数[' + app.ctxScope + ']', app.ctxScope === '选中 2 字');
  app.ctxSetSize(22);
  ok('选中局部改字号只包住那两个字',
     pEl.innerHTML.indexOf('font-size: 22px') > 0 && pEl.textContent === '正文行');
  app.ctxShow = false;
  ed.innerHTML = '';

  /* 3.873) 码+Tab：走 Java 转发那条真实通道（onHostKey('Tab')），
     以前 WebView 把 Tab 拿去遍历焦点，DOM 收不到，码+Tab 完全没反应 */
  app.cfg.wordPrefix = 'z';
  app.words = [{ id: 9501, code: 'cq', content: '春秋' }];
  ed.innerText = 'abzcq';
  caretToEnd();
  var tabRet = app.onHostKey('Tab');
  ok('码+Tab（转发通道）展开[' + tabRet + '/' + app.getText() + ']',
     tabRet === true && app.getText() === 'ab春秋');
  ed.innerText = 'abcq';
  caretToEnd();
  app.onHostKey('Tab');
  ok('码+Tab（不带前缀）展开[' + app.getText() + ']', app.getText() === 'ab春秋');
  ed.innerText = 'ab';
  caretToEnd();
  app.cfg.indentFirst = false;               // 全文缩进开着时 Tab 不再插空格
  app.onHostKey('Tab');                        // 没命中码 → 当缩进
  ok('Tab 没命中码时缩进两格[' + JSON.stringify(app.getText()) + ']',
     app.getText() === 'ab\u3000\u3000');
  /* 全文首行缩进开着时 Tab 只提示不插 */
  ed.innerText = 'ab'; caretToEnd();
  app.cfg.indentFirst = true; app.applyVars();
  app.onHostKey('Tab');
  ok('全文缩进开着 Tab 不插空格[' + JSON.stringify(app.getText()) + ']',
     app.getText() === 'ab');
  var inp = document.createElement('input');
  document.body.appendChild(inp); inp.focus();
  var tabRet2 = app.onHostKey('Tab');
  ok('输入框里 Tab 不拦截[' + tabRet2 + ']', tabRet2 === false);
  document.body.removeChild(inp); ed.focus();
  app.words = []; ed.innerText = '';

  /* 3.88) 样式系统：字体/字号/加粗是"样式"，改一处全级生效并落盘 */
  app.cfg.h1Font = '楷体';
  app.cfg.h1Size = 31;
  app.applyStyles();
  var h1v = document.documentElement.style.getPropertyValue('--h1ff');
  var cfgS = JSON.parse(app.host('getConfig'));
  ok('样式字体写入 CSS 变量[' + h1v + ']', h1v.indexOf('楷体') >= 0);
  ok('样式字体落盘 h1Font[' + cfgS.h1Font + ']', cfgS.h1Font === '楷体' && cfgS.h1Size === 31);
  app.cfg.h1Font = ''; app.cfg.h1Size = 28;
  app.applyStyles();
  ok('样式字体清空恢复默认',
     document.documentElement.style.getPropertyValue('--h1ff') === '');

  /* 3.89) 顶部格式只对选中文字生效：字号/字体/加粗 */
  ed.innerText = '甲乙丙';
  var r9 = document.createRange();
  r9.setStart(ed.firstChild, 1);
  r9.setEnd(ed.firstChild, 2);
  var s9 = window.getSelection(); s9.removeAllRanges(); s9.addRange(r9);
  app._range = r9.cloneRange();
  app.tbSize = 20;
  app.applySelSize(20);
  var szSpan = ed.querySelector('span[style*="font-size"]');
  ok('选中文字字号生效[' + (szSpan ? szSpan.style.fontSize : '-') + ']',
     !!szSpan && szSpan.style.fontSize === '20px' && app.getText() === '甲乙丙');
  app.applySelFont('楷体');
  var ffSpan = ed.querySelector('span[style*="font-family"]');
  ok('选中文字字体生效', !!ffSpan && ffSpan.textContent.indexOf('乙') >= 0);
  s9.removeAllRanges();
  app._range = null;
  var htmlBefore = ed.innerHTML;
  app.toggleBold();                                   // 没选中：不动作
  ok('未选中时加粗不生效[' + ed.innerHTML + ']',
     ed.innerHTML === htmlBefore);
  ed.innerText = '';
  app.tbSize = ''; app.tbFont = '';

  /* 3.893) 中文字号（小四/四号…）+ 右键直接改样式模板（全级生效） */
  ok('中文字号 16=小四[' + app.sizeLabel(16) + ']', app.sizeLabel(16) === '16px 小四');
  ok('中文字号 21=三号[' + app.sizeLabel(21) + ']', app.sizeLabel(21) === '21px 三号');
  ok('中文字号 19=四号[' + app.sizeLabel(19) + ']', app.sizeLabel(19) === '19px 四号');
  ok('非中文字号只显示 px[' + app.sizeLabel(17) + ']', app.sizeLabel(17) === '17px');
  ed.innerText = '第三章 试炼';
  caretToEnd();
  app.setBlock('h1');
  caretToEnd();
  app.ctxOpen({ clientX: 40, clientY: 40 });
  app.ctxStyleOpen('h1');
  ok('右键可进入改样式面板[' + app.ctxView + '/' + app.ctxStyleLevel + ']',
     app.ctxView === 'style' && app.ctxStyleLevel === 'h1');
  app.ctxStyleSetSize(21);
  app.ctxStyleSetFont('楷体');
  ok('改样式：H1 字号 21 生效[' +
     document.documentElement.style.getPropertyValue('--h1fs') + ']',
     app.cfg.h1Size === 21 &&
     document.documentElement.style.getPropertyValue('--h1fs') === '21px');
  ok('改样式：H1 字体生效[' +
     document.documentElement.style.getPropertyValue('--h1ff') + ']',
     document.documentElement.style.getPropertyValue('--h1ff').indexOf('楷体') >= 0);
  var cfgSty = JSON.parse(app.host('getConfig'));
  ok('改样式落盘[' + cfgSty.h1Size + '/' + cfgSty.h1Font + ']',
     cfgSty.h1Size === 21 && cfgSty.h1Font === '楷体');
  app.ctxStyleToggleBold();
  ok('改样式：H1 取消加粗', app.cfg.h1Bold === false &&
     document.documentElement.style.getPropertyValue('--h1w') === '400');
  app.cfg.h1Size = 28; app.cfg.h1Font = ''; app.cfg.h1Bold = true;
  app.applyStyles();
  app.ctxShow = false; app.ctxView = 'main';
  /* 导入字体在选择/样式里必须落到 @font-face 的族名上，否则选了等于没选 */
  app.fontFamily = '汉仪旗黑';
  ok('导入字体映射到 @font-face 族名[' + app.cssFamily('汉仪旗黑') + ']',
     app.cssFamily('汉仪旗黑') === "'SnapWriteCustom'");
  ok('普通字体名带英文族名[' + app.cssFamily('楷体') + ']',
     app.cssFamily('楷体') === "'楷体','KaiTi'" || app.cssFamily('楷体') === "'楷体'");
  app.fontFamily = '';
  ed.innerText = '';

  /* 3.95) 多文件寻回：任意文件都能单独列快照，互不串台 */
  ok('其它文件的历史独立', app.host('listDocHistory', '不存在的文件.txt') === '[]');
  var docsJson = JSON.parse(app.host('getDocs') || '[]');
  var hasHistField = docsJson.length > 0 && typeof docsJson[0].hist === 'number';
  ok('文件列表带快照条数', hasHistField);

  /* 3.96) 纯黑主题色预设存在 */
  var hasBlack = false;
  for (var si = 0; si < app.swatchPairs.length; si++)
    if ((app.swatchPairs[si].a || '').toLowerCase() === '#000000') hasBlack = true;
  ok('纯黑主题色预设存在', hasBlack);

  /* 3.965) 标题分级：H2 套用当前段落 / 正文还原 / 环绕图片桥接 */
  ed.innerText = '第一回 试炼';
  caretToEnd();
  app.setBlock('h2');
  ok('H2 套用当前段落', !!ed.querySelector('h2') &&
     ed.querySelector('h2').textContent === '第一回 试炼');
  app.setBlock('p');
  ok('正文还原当前段落', !!ed.querySelector('p') && !ed.querySelector('h2'));
  ok('环绕图片桥接就绪', typeof app.pickDecorImg === 'function' &&
     typeof app.clearDecorImg === 'function' && app.decorImgSize === 100);

  /* 3.97) 文件面板：导出入口 + 已删除文档找回（回收站） */
  ok('文件行有导出按钮入口', typeof app.exportDoc === 'function' &&
     typeof app.restoreDeletedDoc === 'function');
  app.host('newDoc', '回收测试.txt');
  app.host('saveDraft', '回收内容一');
  app.host('deleteDoc', '回收测试.txt');
  var delList = JSON.parse(app.host('listDeleted') || '[]');
  ok('删除的文件进回收站', delList.some(function (d) { return d.name === '回收测试.txt'; }));
  var backName = app.host('restoreDeleted', '回收测试.txt');
  var docsJson2 = JSON.parse(app.host('getDocs') || '[]');
  ok('回收站恢复文档[' + backName + ']', backName === '回收测试.txt' &&
     docsJson2.some(function (d) { return d.name === '回收测试.txt'; }));
  var goneList = JSON.parse(app.host('listDeleted') || '[]');
  ok('恢复后移出回收站', !goneList.some(function (d) { return d.name === '回收测试.txt'; }));

  /* 3.971) 回收站/快照管理挪到设置：列快照文档、永久删除快照、永久删除回收站条目 */
  var hdList = JSON.parse(app.host('listHistDocs') || '[]');
  ok('数据面板能列出带快照的文档[' + hdList.length + ']',
     hdList.length > 0 && typeof hdList[0].hist === 'number' && !!hdList[0].name);
  app.host('newDoc', '彻底删除测试.txt');
  app.host('saveDraft', '临时内容');
  app.host('deleteDoc', '彻底删除测试.txt');
  var delTry = app.host('purgeDeleted', '彻底删除测试.txt');
  var delAfter = JSON.parse(app.host('listDeleted') || '[]');
  ok('回收站条目可永久删除[' + delTry + ']',
     delTry === 'ok' && !delAfter.some(function (d) { return d.name === '彻底删除测试.txt'; }));
  var purgeName = hdList[0].name;
  var purgeRet = app.host('purgeDocHistory', purgeName);
  var hdAfter = JSON.parse(app.host('listHistDocs') || '[]');
  ok('单文档快照可永久删除[' + purgeName + '/' + purgeRet + ']',
     purgeRet === 'ok' && !hdAfter.some(function (d) { return d.name === purgeName; }));

  /* 3.98) 词库编辑：编辑态不拦默认行为（拦了输入框就永远拿不到焦点） */
  var pd1 = false;
  app.editId = 7;
  app.wordDown({ preventDefault: function () { pd1 = true; } }, { id: 7, content: '' });
  ok('编辑态放行输入框焦点', pd1 === false);
  var pd2 = false;
  app.editId = -1;
  app.wordDown({ preventDefault: function () { pd2 = true; } }, { id: 7, content: '' });
  ok('非编辑态仍拦截防误触', pd2 === true);

  /* 3.99) 气泡新尺寸规则：光标在文末一字高、改中间两字高 */
  ok('气泡尺寸辅助函数', typeof app.caretAtEnd === 'function' &&
     typeof app.caretLineText === 'function');
  ed.innerText = '第一行\n第二行';
  caretToEnd();
  ok('文末判定', app.caretAtEnd() === true);
  ok('所在行取文本', app.caretLineText() === '第二行');

  /* 4) 快捷键映射：Ctrl+K 开 / 再按关（常驻关掉后也能再打开） */
  app.cfg.alwaysWords = false;
  app.wordsClosed = false;
  app.showWords = false;
  key('k', { ctrl: true, code: 'KeyK' });
  var opened = app.wordsVisible === true;
  key('k', { ctrl: true, code: 'KeyK' });
  ok('Ctrl+K 映射到词库开关', opened && app.wordsVisible === false);

  /* 4.5) 改键真的生效：把"段首缩进"重绑到 Ctrl+9，按下要真落字
     （真实录制流程 = host('setKey') + 同步 cfg.keys，两步都做） */
  app.host('setKey', 'indent', 'Ctrl+9');
  app.cfg.keys.indent = 'Ctrl+9';
  app.cfg.indentFirst = false;               // 手动缩进开关只在关掉全文缩进时生效
  ed.innerText = 'ab';
  caretToEnd();
  key('9', { ctrl: true, code: 'Digit9' });
  ok('重绑 Ctrl+9 -> 段首缩进生效[' + app.getText() + ']', app.getText() === 'ab\u3000\u3000');
  app.host('setKey', 'indent', 'Ctrl+1');
  app.cfg.keys.indent = 'Ctrl+1';
  app.cfg.indentFirst = true; app.applyVars();

  /* 5) e.key 失效时靠 e.code 兜底（WebKit 的 Unidentified 场景） */
  app.showWords = false;
  app.wordsClosed = false;
  key('Unidentified', { ctrl: true, code: 'KeyK' });
  ok('Unidentified + e.code 兜底', app.wordsVisible === true);
  app.showWords = false;
  app.wordsClosed = true;

  /* 5.5) 词库分类 + 编辑：保存带分类的词条 / 改码改内容 / 删除 */
  app.host('addWord', 'rssw', '燃霞', '功法');
  var ws = JSON.parse(app.host('getWords')), fw = null;
  for (var wi = 0; wi < ws.length; wi++) if (ws[wi].code === 'rssw') fw = ws[wi];
  ok('词库分类保存[cat=' + (fw ? fw.cat : '-') + ']', !!fw && fw.cat === '功法');
  if (fw) {
    app.host('updateWord', fw.id, 'rx', '燃霞诀', '');
    var ws2 = JSON.parse(app.host('getWords')), fw2 = null;
    for (var wj = 0; wj < ws2.length; wj++) if (ws2[wj].id === fw.id) fw2 = ws2[wj];
    ok('词库编辑生效[' + (fw2 ? fw2.code + '/' + fw2.content : '-') + ']',
       !!fw2 && fw2.code === 'rx' && fw2.content === '燃霞诀' && !fw2.cat);
    app.host('deleteWord', fw.id);
  }

  /* 5.7) 跨文本节点的 前缀+数字：z 在上一个文本节点末尾也能触发
     （以前只看光标所在文本节点，光标贴节点开头时 z 就丢了） */
  app.words = [{ id: 9201, code: '', content: '云梦泽' }];
  ed.innerHTML = '<span>abz</span><span>cd</span>';
  var r5 = document.createRange();
  r5.setStart(ed.childNodes[1].firstChild, 0);
  r5.collapse(true);
  var s5 = window.getSelection(); s5.removeAllRanges(); s5.addRange(r5);
  app._range = null;
  key('1', { code: 'Digit1' });
  ok('跨文本节点 z1 插入[' + app.getText() + ']', app.getText() === 'ab云梦泽cd');

  /* 5.8) 自定义前缀：wordPrefix 改成 q 后，z2 不再触发、q2 触发 */
  app.cfg.wordPrefix = 'q';
  app.words = [{ id: 9201, code: '', content: '云梦泽' },
               { id: 9202, code: '', content: '苏晚' }];
  ed.innerText = 'abz';
  caretToEnd();
  key('2', { code: 'Digit2' });
  var t0 = app.getText();
  ed.innerText = 'abq';
  caretToEnd();
  key('2', { code: 'Digit2' });
  ok('自定义前缀 z2不触发/q2生效[' + t0 + '->' + app.getText() + ']',
     t0 === 'abz' && app.getText() === 'ab苏晚');
  app.cfg.wordPrefix = 'z';
  app.words = [];

  /* 5.9) 查找替换：计数 / 定位选中 / 单个替换+跳下一处 / 全部替换 */
  ed.innerText = '甲乙丙丁甲乙丙丁甲';
  caretToEnd();
  app.showFind = false;
  key('f', { ctrl: true, code: 'KeyF' });
  ok('Ctrl+F 打开查找替换', app.showFind === true);
  app.showFind = false;
  app.findText = '甲'; app.replText = '戊'; app.matchCase = false;
  app.countMatches();
  var cnt = app.findCount;
  app.gotoMatch(1);
  var selTxt = window.getSelection().toString();
  app.replaceOne();
  var afterOne = app.getText();
  var selTxt2 = window.getSelection().toString();
  app.replaceAll();
  var afterAll = app.getText();
  ok('查找计数[' + cnt + ']', cnt === 3);
  ok('定位选中匹配[' + selTxt + ']', selTxt === '甲');
  ok('单个替换并跳下一处[' + afterOne + ' sel=' + selTxt2 + ']',
     afterOne === '戊乙丙丁甲乙丙丁甲' && selTxt2 === '甲');
  ok('全部替换[' + afterAll + ']', afterAll === '戊乙丙丁戊乙丙丁戊');
  app.showFind = false;
  app.findText = ''; app.replText = '';

  /* 5.10) 导入「添加到此处」：插到光标位置且保留原有文字（含多行） */
  ed.innerText = '前文';
  caretToEnd();
  app.impText = '导入A\n导入B';
  app.importApply('insert');
  ok('添加到此处（保留原文+多行）[' + app.getText().replace('\n', '\\n') + ']',
     app.getText() === '前文导入A\n导入B');
  app.impText = '';

  /* 5.11) 导入：预览里选中一段 -> 只添加选中的那段（用户的真实反馈） */
  ed.innerText = '前文';
  caretToEnd();
  app.impText = '全文第一段\n我是被选中的';
  app.impSel = '我是被选中的';
  app.importApply('insert');
  ok('导入只添加选中段落[' + app.getText().replace('\n', '\\n') + ']',
     app.getText() === '前文我是被选中的');
  app.impText = ''; app.impSel = '';

  /* 5.12) Java 端转发的快捷键通道（onHostKey）：真机按键走这条，不受 DOM 事件影响 */
  app.showFind = false; app._lastCombo = '';
  var h1 = app.onHostKey('Ctrl+F');
  var h1open = app.showFind === true;
  app._lastEscAt = 0;
  var h2 = app.onHostKey('Esc');
  ok('onHostKey Ctrl+F 打开查找[' + h1 + '/' + h1open + ']', h1 === true && h1open);
  ok('onHostKey Esc 关闭查找[' + h2 + ']', h2 === true && app.showFind === false);

  /* 5.13) 设置面板：标题栏 sticky 的断言放在最后的异步块里（那时面板才真的渲染出来） */

  /* 6) 设置改动即时下发（没有保存按钮） */
  app.cfg.fontSize = 21;
  app.applyAll();
  var cfgNow = JSON.parse(app.host('getConfig'));
  ok('设置改动即时落盘 fontSize=21[' + cfgNow.fontSize + ']', cfgNow.fontSize === 21);

  /* 6.5) 环绕元素边距：可调；全透明/关闭时归零。
     小于 18px 时按 18px 走 —— 再小元素就躲进输入框白底里（等于没元素）。 */
  app.cfg.cloudMargin = 12;
  app.applyAll();
  var cfgNow2 = JSON.parse(app.host('getConfig'));
  var padNow = document.documentElement.style.getPropertyValue('--bpad');
  ok('边距可调 cloudMargin=12[存=' + cfgNow2.cloudMargin + ' 实际=' + padNow + ']',
     cfgNow2.cloudMargin === 12 && padNow === '18px');
  app.cfg.bubbleCloud = false;
  app.applyBubblePad();
  ok('全透明/关闭时边距归零[' + document.documentElement.style.getPropertyValue('--bpad') + ']',
     document.documentElement.style.getPropertyValue('--bpad') === '0px');
  app.cfg.bubbleCloud = true;
  app.cfg.cloudMargin = 26;
  app.applyBubblePad();

  /* 6.6) 跟随音乐开关下发 */
  app.cfg.musicFollow = true;
  app.applyAll();
  ok('跟随音乐开关落盘',
     JSON.parse(app.host('getConfig')).musicFollow === true);
  app.cfg.musicFollow = false;
  app.applyAll();

  /* 7) Esc = 点 ✕；录制老板键时 Esc 只取消捕获
     （_lastEscAt 是双通道防重时间戳，自测里连续按要手动清掉） */
  app.showSettings = true;
  app.capturing = true;
  app._lastEscAt = 0;
  key('Escape', { code: 'Escape' });
  ok('Esc 录制中只取消捕获，设置保持打开',
     app.capturing === false && app.showSettings === true);
  app.showSettings = true;
  app._lastEscAt = 0;
  key('Escape', { code: 'Escape' });
  ok('Esc 关闭设置', app.showSettings === false);
  app.showWords = true;
  app.wordsClosed = false;
  app._lastEscAt = 0;
  key('Escape', { code: 'Escape' });
  ok('Esc 关闭词库', app.wordsVisible === false);
  app.showCloseModal = true;
  app._lastEscAt = 0;
  key('Escape', { code: 'Escape' });
  ok('Esc 关闭退出确认', app.showCloseModal === false);

  /* 8) 文件入口在最左 */
  var pills = document.querySelectorAll('.header .pill');
  ok('标题栏第一个按钮是文件[' + (pills.length ? pills[0].innerText.trim() : '-') + ']',
     pills.length > 0 && pills[0].innerText.trim() === '文件');

  /* 9) 气泡 + 词库常驻：词库必须在输入框外侧（异步等 Vue 更新 DOM） */
  app.cfg.alwaysWords = true;
  app.wordsClosed = false;
  app.enterBubble();
  setTimeout(function () {
    app.syncSidewords();
    app.applyBubbleSizeNow();
    app._lastEscAt = 0;
    var appEl = document.getElementById('app');
    ok('气泡模式类已切换',
       appEl.classList.contains('mode-bubble') && appEl.classList.contains('sidewords'));
    var panel = document.querySelector('.wpop.sideword');
    var edBox = document.getElementById('editor').getBoundingClientRect();
    var pr = panel ? panel.getBoundingClientRect() : null;
    ok('词库面板在输入框右侧之外[ed.right=' + Math.round(edBox.right) +
       ' panel.left=' + (pr ? Math.round(pr.left) : -1) + ' win=' + window.innerWidth + ']',
       !!pr && pr.left >= edBox.right - 2);
    /* 词库面板有关闭 X */
    ok('词库面板有 ✕ 关闭钮', typeof app.closeWords === 'function');
    /* 9.1) 小胶囊时控制钮必须让开（23px 的圆钮压在字上，比气泡还大） */
    app.cfg.alwaysWords = false;
    app.syncSidewords();
    ed.innerText = '字';
    caretToEnd();
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    var bc = document.querySelector('.bctrl-wrap');
    var bcCS = bc ? getComputedStyle(bc) : null;
    /* opacity 有 0.16s 过渡，刚设完读到的还是中间值 —— 用 pointer-events 判"让开了" */
    ok('小胶囊时还原/✕ 钮让开[' + (bc ? bc.className : '-') + ' pe=' +
       (bcCS ? bcCS.pointerEvents : '-') + ']',
       !!bc && bc.classList.contains('tiny') && !!bcCS && bcCS.pointerEvents === 'none');

    /* 9.2) 气泡自适应尺寸：宽 = 光标这一行文字的实测宽度（打字变长、删字变短），
       高 = 这一行的行高。光标在文档哪儿都一样 —— 以前"不在文末就展开成大窗"，
       正文中间打字时气泡根本不动，看着就是"自适应没效果"。 */
    app.cfg.bubbleManualSize = false;         // 上次运行可能存了手动大小，先关掉再量
    app.applyAll();
    function bubbleW(text) {
      ed.innerText = text;
      caretToEnd();
      app._bw = 0; app._bh = 0;
      app.applyBubbleSizeNow();
      return app._bw;
    }
    var fs0 = app.cfg.fontSize;
    var wOne = bubbleW('字');
    var wFive = bubbleW('一二三四五');
    var wLong = bubbleW(new Array(60).join('字'));
    var innerFive = wFive - app.bubblePadH() - 10;
    var needFive = app.measureLineWidth('一二三四五', fs0);
    ok('胶囊内宽装得下整行[' + Math.round(innerFive) + ' >= ' + Math.round(needFive) + ']',
       innerFive >= needFive - 1);
    ok('胶囊宽度随字数变长[w=' + wOne + '→' + wFive + ']', wFive > wOne + 10);
    ok('一个字时宽度≈一个字[' + wOne + ' < ' + (wOne ? Math.round(wFive) : 0) + ']',
       wOne > 0 && wOne < wFive);
    var capW = Math.ceil(app.measureLineWidth(
                 new Array((app.cfg.bubbleCharsPerLine | 0 || 20) + 1).join('字'), fs0))
               + 2 + app.bubblePadH() + 10;
    ok('到"每行字数"就不再变宽[w=' + wLong + ' <= ' + capW + ']', wLong <= capW + 1);
    ok('长行高度按"显示行数"封顶[h=' + app._bh + ']',
       app._bh <= Math.ceil(app.cfg.bubbleLines * (fs0 * 1.9) + 10) + app.bubblePadV());
    var hTyping = app._bh;
    /* 光标停在文档中间（后面还有文字）：第 33 轮起展开"上下文三行" */
    ed.innerText = '第一行文字\n第二行文字内容';
    var fnode = ed.firstChild;
    var midOff = Math.min(5, (fnode.nodeValue || '').length);
    var rMid = document.createRange();
    rMid.setStart(fnode, midOff);
    rMid.collapse(true);
    var sMid = window.getSelection(); sMid.removeAllRanges(); sMid.addRange(rMid);
    app._bw = 0; app._bh = 0;
    app._lastInputAt = Date.now();          // 打字态：不当"翻看"
    app._caretFixPending = false;
    app.applyBubbleSizeNow();
    ok('光标在文档中间=上下文三行[h=' + app._bh + ' > 一行 ' + hTyping +
       ' bub3=' + app._bub3 + ']',
       app._bub3 === true && app._bh > hTyping + 10);
    ok('中间行的宽度跟着这行字[w=' + app._bw + ' <= 满行 ' + capW + ']',
       app._bw > 0 && app._bw <= capW + 1);
    caretToEnd();
    app.applyBubbleSizeNow();
    ok('回到文末还是一行高[h=' + app._bh + ']', app._bh <= hTyping + 2);

    /* ★ 光标停在行中间：框必须包住"整行"（含光标之后的字）。
       以前宽度只算到光标为止，后半截被挤出框外折到下一行，而框只有一行高 ——
       用户看到的就是"输入的字跑到框外"和"画面跳到别的文字那里"。 */
    ok('整行取文本函数存在',
       typeof app.caretLineFull === 'function' && typeof app.caretVisualLine === 'function' &&
       typeof app.caretParaSplit === 'function');
    ed.innerText = '一二三四五六七八九十';
    var tnMid = ed.firstChild;
    if (tnMid && tnMid.nodeType !== 3) tnMid = app._lastTextNode(tnMid);
    var rMid2 = document.createRange();
    rMid2.setStart(tnMid, 3);
    rMid2.collapse(true);
    var sMid2 = window.getSelection(); sMid2.removeAllRanges(); sMid2.addRange(rMid2);
    app._lastInputAt = 0;                    // 不当成"正在打字"
    var capNow = app.measureLineWidth(new Array((app.cfg.bubbleCharsPerLine | 0 || 20) + 1)
                   .join('字'), fs0) + 2;
    var vlm = app.caretVisualLine(capNow);
    ok('整行取文本含光标之后的字[text=' + vlm.text + ']', vlm.text === '一二三四五六七八九十');
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    var iwMid = app._bw - app.bubblePadH() - 10;
    var needAll = app.measureLineWidth('一二三四五六七八九十', fs0);
    ok('光标在行中间：框宽包住整行[内宽=' + Math.round(iwMid) + ' 整行=' +
       Math.round(needAll) + ']', iwMid >= needAll - 1);
    ok('光标在行中间：高度仍是一行[h=' + app._bh + ' 一行=' +
       (Math.ceil(fs0 * 1.9 + 4) + app.bubblePadV()) + ']',
       app._bh <= Math.ceil(fs0 * 1.9 + 4) + app.bubblePadV() + 2);
    /* 跨段落：只取光标所在那一段，别把上一段/下一段粘进来 */
    ed.innerText = '上一段\n一二三四五\n下一段';
    var nodesTxt = [];
    (function collect(n) {
      if (n.nodeType === 3) { nodesTxt.push(n); return; }
      for (var i = 0; i < n.childNodes.length; i++) collect(n.childNodes[i]);
    })(ed);
    var hitNode = null;
    for (var ni = 0; ni < nodesTxt.length; ni++)
      if ((nodesTxt[ni].nodeValue || '').indexOf('一二三四五') >= 0) { hitNode = nodesTxt[ni]; break; }
    if (!hitNode) hitNode = nodesTxt[Math.min(1, nodesTxt.length - 1)];
    var r3 = document.createRange();
    r3.setStart(hitNode, Math.min(2, (hitNode.nodeValue || '').length));
    r3.collapse(true);
    var s3 = window.getSelection(); s3.removeAllRanges(); s3.addRange(r3);
    var vl2 = app.caretVisualLine(capNow);
    ok('跨段落只取光标所在行[text=' + vl2.text + ']', vl2.text === '一二三四五');
    caretToEnd();
    app.applyBubbleSizeNow();

    /* 9.3) 用户手动定气泡大小：开着就完全听用户的，写多写少都不变 */
    app.cfg.bubbleManualSize = true;
    app.cfg.bubbleW = 500;
    app.cfg.bubbleH = 200;
    var sideW = app.wordsVisible ? 214 : 0;
    ed.innerText = '短';
    caretToEnd();
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    var mw1 = app._bw, mh1 = app._bh;
    ed.innerText = new Array(40).join('很长很长的一行字');
    caretToEnd();
    app.applyBubbleSizeNow();
    ok('手动大小：宽=' + mw1 + ' 高=' + mh1 + '（期望 ' + (500 + sideW) + 'x200）',
       mw1 === 500 + sideW && mh1 === 200);
    ok('手动大小：写多了也不变[' + app._bw + 'x' + app._bh + ']',
       app._bw === mw1 && app._bh === mh1);
    app.applyAll();                          // 同步下发一次（平时靠 cfg 的深度 watch 防抖）
    var cfgB = JSON.parse(app.host('getConfig'));
    ok('手动大小落盘[' + cfgB.bubbleW + 'x' + cfgB.bubbleH + ' m=' + cfgB.bubbleManualSize + ']',
       cfgB.bubbleW === 500 && cfgB.bubbleH === 200 && cfgB.bubbleManualSize === true);
    app.cfg.bubbleManualSize = false;
    app.applyAll();                           // 把"关掉手动"也落盘，别污染下次运行
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    ok('关掉手动回到自适应[w=' + app._bw + ' <= ' + capW + ']', app._bw <= capW + 1);

    /* 9.4) 气泡自适应 = 框宽跟着"这一行文字"走：一个字起步，随字变长；
       打满"每行字数"就换行、框缩回；而"每行字数"本身按光标处字号折算，
       14px 的 20 字和 29px 的 20 字都要真装得下。 */
    function bsz(px, text) {
      app.cfg.fontSize = px;
      app.applyVars();
      ed.innerText = text;
      caretToEnd();
      app._bw = 0; app._bh = 0;
      app.applyBubbleSizeNow();
      return { w: app._bw, h: app._bh, need: app.measureLineWidth(text, px) };
    }
    var twenty = new Array(21).join('字');
    var b14 = bsz(14, twenty), b29 = bsz(29, twenty);
    ok('气泡宽度随字号变宽[14px=' + b14.w + ' 29px=' + b29.w + ']', b29.w > b14.w * 1.5);
    ok('气泡高度随字号变高[14px=' + b14.h + ' 29px=' + b29.h + ']',
       b29.h > b14.h && (b29.h - b14.h) >= 20);
    var inner29 = b29.w - app.bubblePadH() - 10;
    ok('29px 一行装得下 20 字[内宽=' + Math.round(inner29) + ' 需要=' +
       Math.round(b29.need) + ']', inner29 >= b29.need - 1);
    var inner14 = b14.w - app.bubblePadH() - 10;
    ok('14px 一行也装得下 20 字[内宽=' + Math.round(inner14) + ' 需要=' +
       Math.round(b14.need) + ']', inner14 >= b14.need - 1);

    /* ★ 自适应模式：框长 = 文字长（一个字的余量），真·动态 */
    function iw(x) { return x - app.bubblePadH() - 10; }
    var w1 = bsz(16, '字').w, w5 = bsz(16, '一二三四五').w,
        w10 = bsz(16, twenty.slice(0, 10)).w;
    ok('框宽跟着字数递增[1字=' + Math.round(iw(w1)) + ' 5字=' + Math.round(iw(w5)) +
       ' 10字=' + Math.round(iw(w10)) + ']',
       iw(w1) < iw(w5) && iw(w5) < iw(w10));
    ok('只有一个字时框≈一个字宽[内宽=' + Math.round(iw(w1)) + ' 字宽=' +
       Math.round(app.measureLineWidth('字', 16)) + ']',
       iw(w1) < app.measureLineWidth('字', 16) * 2.2);
    var tw10 = app.measureLineWidth(twenty.slice(0, 10), 16);
    ok('框宽 = 文字宽 + 一个字以内的余量[内宽=' + Math.round(iw(w10)) + ' 文字=' +
       Math.round(tw10) + ']', iw(w10) >= tw10 && iw(w10) <= tw10 + 18);
    /* 满 20 字换行：框宽 = 光标所在「当前行」的宽度（打字机模型），
       换行后重新从短变长，光标正好贴在框的最右边（用户钦定的行为） */
    var bFull = bsz(16, twenty);
    var bOver = bsz(16, twenty + '多');
    ok('换行后按当前行重新自适应[' + Math.round(iw(bFull.w)) + '→' +
       Math.round(iw(bOver.w)) + ']', iw(bOver.w) < iw(bFull.w) * 0.5);
    var bOver2 = bsz(16, twenty + '多两个字');
    ok('换行后的新行也随字数变宽[' + Math.round(iw(bOver.w)) + '→' +
       Math.round(iw(bOver2.w)) + ']', iw(bOver2.w) > iw(bOver.w) + 10);
    app.cfg.fontSize = 16;
    app.applyVars();

    /* 9.41) 环绕元素要有地方站：输入态（小胶囊）边距不能被压成 8px，
       否则元素整圈躲进输入框白底里 = "自适应模式看不到元素" */
    app.cfg.bubbleCloud = true; app.cfg.cloudOpacity = 60; app.cfg.cloudMargin = 26;
    app.applyBubblePad();
    ed.innerText = '字';
    caretToEnd();
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    var bpadNow = parseFloat(getComputedStyle(document.documentElement)
                             .getPropertyValue('--bpad')) || 0;
    var cv = document.getElementById('cloudcv');
    var cvBox = cv ? cv.getBoundingClientRect() : null;
    ok('小胶囊态保留环绕边距[bpad=' + bpadNow + ']', bpadNow >= 18);
    ok('环绕画布铺满窗口[cv=' + (cvBox ? Math.round(cvBox.width) + 'x' +
       Math.round(cvBox.height) : '-') + ']', !!cvBox && cvBox.width > 0 && cvBox.height > 0);

    /* 9.42) 工具条显示"光标处"的字体/字号：标题里显示标题字号，正文里显示正文字号 */
    ed.innerHTML = '<h2>标题里的字</h2>';
    var h2t = ed.querySelector('h2');
    var rh = document.createRange();
    rh.selectNodeContents(h2t); rh.collapse(true);
    var sh = window.getSelection(); sh.removeAllRanges(); sh.addRange(rh);
    app.syncCaretFormat();
    ok('标题里工具条显示标题字号[显示=' + app.caretSize + ' 应为=' + app.cfg.h2Size + ']',
       app.caretSize === app.cfg.h2Size);
    ed.innerHTML = '正文一段话';
    caretToEnd();
    app.syncCaretFormat();
    ok('正文里工具条默认显示正文字号[显示=' + app.caretSize + ' 应为=' +
       app.cfg.fontSize + ']', app.caretSize === app.cfg.fontSize);
    ok('工具条显示光标处字体[' + app.caretFont + ']', !!app.caretFont);

    /* 9.43) 改选中的文字 ≠ 改模板：只包一层 data-inl 的 span，段落块和样式都不动 */
    ed.innerHTML = '<h2>标题里的字</h2>';
    var h2b = ed.querySelector('h2');
    var h2tx = h2b.firstChild;
    var rsel = document.createRange();
    rsel.setStart(h2tx, 0); rsel.setEnd(h2tx, 2);
    var ss = window.getSelection(); ss.removeAllRanges(); ss.addRange(rsel);
    var h2SizeBefore = app.cfg.h2Size;
    app.applySelPx(30);
    var inl = h2b.querySelector('span[data-inl]');
    ok('选中文字只改这几个字[span=' + (inl ? inl.style.fontSize : '-') + ' 块=' +
       (h2b.style.fontSize || '未动') + ' 模板=' + app.cfg.h2Size + ']',
       !!inl && inl.style.fontSize === '30px' && !h2b.style.fontSize &&
       app.cfg.h2Size === h2SizeBefore);
    ok('改选中文字不改段落模板（标题其余字仍是标题字号）',
       getComputedStyle(h2b).fontSize === (app.cfg.h2Size + 'px'));

    /* 9.44) 对齐：左/中/右/两端只作用本段，不动模板；回车后新段落回到左对齐 */
    ed.innerHTML = '<div>对齐测试段落</div>';
    var dv = ed.firstChild;
    var ra = document.createRange();
    ra.selectNodeContents(dv); ra.collapse(true);
    var sa = window.getSelection(); sa.removeAllRanges(); sa.addRange(ra);
    app._range = ra.cloneRange();
    app.alignSel('center');
    ok('居中对齐[style=' + (dv.style.textAlign || '-') + ']',
       dv.style.textAlign === 'center');
    app.alignSel('right');
    ok('右对齐[style=' + (dv.style.textAlign || '-') + ']', dv.style.textAlign === 'right');
    app.alignSel('justify');
    ok('两端对齐[style=' + (dv.style.textAlign || '-') + ']',
       dv.style.textAlign === 'justify');
    app.syncCaretFormat();
    ok('工具条对齐状态跟随光标[' + app.curAlign + ']', app.curAlign === 'justify');
    ed.innerHTML = '<div style="text-align:center"><div id="__nl">新起的一段</div></div>';
    var nlb = ed.querySelector('#__nl');
    var rnl = document.createRange();
    rnl.selectNodeContents(nlb); rnl.collapse(true);
    var snl = window.getSelection(); snl.removeAllRanges(); snl.addRange(rnl);
    app.resetNewLineAlign();
    ok('回车换行后新段落默认左对齐[' + (nlb.style.textAlign || '继承=' +
       getComputedStyle(nlb).textAlign) + ']', nlb.style.textAlign === 'left');
    nlb.removeAttribute('id');

    /* 9.5) 查找高亮：匹配包 fmark 高亮、关闭后清空（不影响正文文字） */
    app.cfg.alwaysWords = false;
    app.wordsClosed = true;
    ed.innerText = '甲乙丙甲乙丙';
    caretToEnd();
    app.findText = '甲';
    app.replText = '';
    app.showFind = true;
    app.refreshFind();
    var marks = document.querySelectorAll('#editor span.fmark');
    var edTxt = app.getText();
    ok('查找高亮 fmark=2[' + marks.length + ' 文字不变:' + edTxt + ']',
       marks.length === 2 && edTxt === '甲乙丙甲乙丙');
    app.showFind = false;
    app.findText = '';

    /* 9.6) 快捷键冲突提示 */
    app.cfg.keys.save = 'Ctrl+E';               // 和 export 的默认键撞车
    var wmsg = app.keyWarn({ id: 'save', name: '保存' });
    ok('快捷键重名提示[' + wmsg.slice(0, 10) + ']', wmsg.indexOf('同一个键') >= 0);
    app.cfg.keys.save = 'Ctrl+C';
    var wmsg2 = app.keyWarn({ id: 'save', name: '保存' });
    ok('常用组合键提示[' + wmsg2.slice(0, 10) + ']', wmsg2.indexOf('常用组合键') >= 0);
    app.cfg.keys.save = 'Ctrl+S';               // 恢复出厂默认 -> 不提示
    ok('正常键无提示[' + app.keyWarn({ id: 'save', name: '保存', def: 'Ctrl+S' }) + ']',
       app.keyWarn({ id: 'save', name: '保存', def: 'Ctrl+S' }) === '');
    ok('导出动作入口存在', typeof app.doExport === 'function');

    /* 9.7) 关闭查找（watch 异步刷新，开/关要分两个 tick）后高亮清空 */
    app.findText = '甲';
    app.showFind = true;
    setTimeout(function () {
      app.refreshFind();
      app.showFind = false;
      setTimeout(function () {
        var marks3 = document.querySelectorAll('#editor span.fmark');
        var txt3 = app.getText();
        ok('关闭查找后高亮清空（watch 异步）[' + marks3.length + ' 文字:' + txt3 + ']',
           marks3.length === 0 && txt3 === '甲乙丙甲乙丙');
        app.findText = '';

        /* 10) 设置面板：标题栏 sticky（滚到哪 ✕ 都在）+ 可拖动 */
        app.showSettings = true;
        setTimeout(function () {
          var h3 = document.querySelector('.modal h3');
          var cs = h3 ? getComputedStyle(h3) : null;
      ok('设置标题栏冻结 position=' + (cs ? cs.position : '-'),
         !!cs && cs.position === 'sticky');
      ok('设置标题栏可拖动 cursor=' + (cs ? cs.cursor : '-'),
         !!cs && cs.cursor === 'move');
      app.showSettings = false;

      /* 10.5) 导出：buildBlocks 把段落和粗/斜/下划线拆对（PDF/Word 排版靠它） */
      ed.innerHTML = '<div>普通<b>粗体</b>和<i>斜体</i></div><div>第二<u>段</u></div>';
      var bb = app.buildBlocks();
      var blkN = bb.split('\u0002').length;
      ok('导出片段拆分[块数=' + blkN + ']', bb.indexOf('0|普通') === 0 &&
         bb.indexOf('1|粗体') > 0 && bb.indexOf('2|斜体') > 0 &&
         bb.indexOf('4|段') > 0 && blkN >= 2);
      ed.innerHTML = '第一行<br>第二行';
      var bb2 = app.buildBlocks();
      ok('导出 br 分段', bb2.split('\u0002').length === 2 &&
         bb2.indexOf('0|第一行') === 0 && bb2.indexOf('0|第二行') > 0);
      ed.innerText = '甲乙丙';
      ok('导出格式清单', app.exportFormats.length === 6 &&
         app.exportFormats.some(function (f) { return f.id === 'pdf'; }) &&
         app.exportFormats.some(function (f) { return f.id === 'docx'; }));

      /* 11) 右键兜底：mousedown(button=2) 必须能打开右键菜单/样式面板
         （部分 WebView 不派发 contextmenu 事件，右键像被吞了 —— 靠 mousedown 兜底） */
      function rmb(el) {
        var r = el.getBoundingClientRect();
        el.dispatchEvent(new MouseEvent('mousedown', { button: 2, bubbles: true,
          cancelable: true, clientX: r.left + 5, clientY: r.top + 5 }));
      }
      app.ctxShow = false; app.ctxView = 'main';
      app.exitBubble();                    // 工具条只在完整模式渲染（v-if），先退回去
      setTimeout(function () {
        var h1btn = document.querySelector('.toolbar .tb[data-rcstyle="h1"]');
        ok('工具条 H1 有右键编辑入口', !!h1btn);
        if (!h1btn) { window.__stResult = out.join('\n'); return; }
        rmb(h1btn);
      setTimeout(function () {
        ok('工具条右键 H1 → 样式面板[show=' + app.ctxShow + ' view=' + app.ctxView +
           ' level=' + app.ctxStyleLevel + ']',
           app.ctxShow && app.ctxView === 'style' && app.ctxStyleLevel === 'h1');
        app.ctxShow = false; app.ctxView = 'main';
        rmb(ed);
        setTimeout(function () {
          ok('编辑区右键 → WPS 菜单[show=' + app.ctxShow + ' view=' + app.ctxView + ']',
             app.ctxShow && app.ctxView === 'main');
          app.ctxShow = false;

          /* 11.1) 取色面板：右键工具条"纹" → 面板必须渲染出色块（用户报过"空白页面"） */
          var bgbtn = document.querySelector('.toolbar .tb[data-rcmark="bg"]');
          ok('工具条"纹"有右键取色入口', !!bgbtn);
          if (!bgbtn) { window.__stResult = out.join('\n'); return; }
          rmb(bgbtn);
          setTimeout(function () {
            var mp = document.querySelector('.pop.menu[data-popkey="mark"]');
            var dots = mp ? mp.querySelectorAll('i').length : 0;
            var mr = mp ? mp.getBoundingClientRect() : null;
            var errs0 = (window.__errs || []).join(' | ');
            ok('底纹取色面板有内容[el=' + !!mp + ' 色块=' + dots +
               ' box=' + (mr ? Math.round(mr.width) + 'x' + Math.round(mr.height) : '-') +
               ' pop=' + app.markPop + ' errs=' + errs0 + ']',
               !!mp && dots >= 10 && !!mr && mr.width > 100 && mr.height > 60);
            app.markPop = '';
            /* 右键正文里的底纹文字 → WPS 菜单有内容（不是空白） */
            ed.innerHTML = '<span class="hlmark" style="background:#FACC15">底纹文字测试</span>';
            var sp0 = ed.querySelector('.hlmark');
            var r0 = document.createRange(); r0.selectNodeContents(sp0); r0.collapse(true);
            var s0 = window.getSelection(); s0.removeAllRanges(); s0.addRange(r0);
            rmb(sp0);
            setTimeout(function () {
              var cm = document.querySelector('.ctxmenu');
              var items = cm ? cm.querySelectorAll('button').length : 0;
              var cr = cm ? cm.getBoundingClientRect() : null;
              ok('右键底纹文字 → 菜单有内容[按钮=' + items +
                 ' box=' + (cr ? Math.round(cr.width) + 'x' + Math.round(cr.height) : '-') + ']',
                 app.ctxShow && items >= 8 && !!cr && cr.width > 100);
              app.ctxShow = false;
              ed.innerHTML = '';

              /* 11.2) 弹层拖动：多个弹窗同时开时可以拖开避免遮挡 */
              app.navShow = true;
              setTimeout(function () {
                var np = document.querySelector('.navpop[data-popkey="nav"]');
                ok('导航弹层存在[el=' + !!np + ']', !!np);
                if (np) {
                  var nr1 = np.getBoundingClientRect();
                  np.dispatchEvent(new MouseEvent('mousedown', { button: 0, bubbles: true,
                    cancelable: true, clientX: nr1.left + 30, clientY: nr1.top + 10 }));
                  document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true,
                    clientX: nr1.left + 90, clientY: nr1.top + 60 }));
                  document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                  var nr2 = np.getBoundingClientRect();
                  ok('弹层可拖动（避免互相遮挡）[dx=' + Math.round(nr2.left - nr1.left) +
                     ' dy=' + Math.round(nr2.top - nr1.top) + ']',
                     Math.abs(nr2.left - nr1.left) > 40 && Math.abs(nr2.top - nr1.top) > 30);
                }
                app.navShow = false;

              /* 12) 气泡：双击还原开关真的管用；拖动条不再计入三连击；
                 边缘拉伸热区在（默认可拉伸） */
              /* r31：进气泡只留词库 —— 其它弹窗一律关掉 */
              app.navShow = true; app.markPop = 'bg';
              app.showChars = true; app.showMore = true;
              app.enterBubble();
              ok('进气泡除词库外弹窗全关[nav=' + app.navShow + ' mark=' + app.markPop +
                 ' chars=' + app.showChars + ' more=' + app.showMore + ']',
                 !app.navShow && !app.markPop && !app.showChars && !app.showMore);
              setTimeout(function () {
            ok('气泡拉伸热区[n=' + document.querySelectorAll('.rz').length + ']',
               document.querySelectorAll('.rz').length === 5);
            var strip = document.querySelector('.dragstrip');
            /* 真实命中测试：elementFromPoint 走浏览器实际命中逻辑，
               能抓出"被别的元素盖住导致真点击全废"（合成事件测不出来） */
            var sr = strip.getBoundingClientRect();
            var hitEl = document.elementFromPoint(sr.left + sr.width / 2, sr.top + sr.height / 2);
            ok('拖动条真实命中[' + (hitEl ? (hitEl.className || hitEl.id || hitEl.tagName) : 'null') + ']',
               !!hitEl && (hitEl === strip || strip.contains(hitEl)));
            var hitL = document.elementFromPoint(sr.left + 4, sr.top + sr.height / 2);
            ok('拖动条左端真实命中[' + (hitL ? (hitL.className || hitL.id || hitL.tagName) : 'null') + ']',
               !!hitL && (hitL === strip || strip.contains(hitL)));
            app.cfg.bubbleDblRestore = false;
            strip.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
            ok('关闭双击还原：双击拖动条不还原[mode=' + app.mode + ']',
               app.mode === 'bubble');
            for (var i = 0; i < 3; i++)
              strip.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
            ok('拖动条连点不再走三连击还原[mode=' + app.mode + ']', app.mode === 'bubble');
            app.cfg.bubbleDblRestore = true;
            strip.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
            ok('开启双击还原：双击拖动条还原[mode=' + app.mode + ']', app.mode === 'full');

            /* 13) 今日字数：切到更短的文档不得把今日字数倒扣（切文档要重置基准） */
            app.host('newDoc', '统计测试A.txt');
            app.host('saveNow', '一二三四五六七八九十');   // +10 字
            setTimeout(function () {
              var t1 = app.stats.today;
              ok('今日字数记录[t1=' + t1 + ']', t1 >= 10);
              app.host('newDoc', '统计测试B.txt');          // 切到空文档（更短）
              app.host('saveNow', '甲');                    // 只写 1 字
              setTimeout(function () {
                var t2 = app.stats.today;
                ok('切更短文档今日字数不倒扣[' + t1 + '→' + t2 + ']', t2 >= t1);

                /* 13.5) r20：底纹/标签/导航/目录/中西文字体/样式斜体/自定义字号 */
                try {
                var ed = document.getElementById('editor');
                ed.innerHTML = '<p>这是一段用来做标记测试的正文文字内容。</p>'
                             + '<h1>第一章 大风歌</h1><p>大风起兮云飞扬。</p>';
                app.onInput();
                /* 在第 pi 段里选 from..to：跳过被包过的 span，找装得下的文本节点 */
                function selIn(pi, from, to) {
                  var p = ed.querySelectorAll('p')[pi], tn = null;
                  (function find(n) {
                    for (var c = n.firstChild; c; c = c.nextSibling) {
                      if (c.nodeType === 3 && c.nodeValue.length >= to) { tn = c; return; }
                      if (c.nodeType === 1) find(c);
                    }
                  })(p);
                  var r = document.createRange();
                  r.setStart(tn, from); r.setEnd(tn, to);
                  var s = window.getSelection();
                  s.removeAllRanges(); s.addRange(r);
                }
                selIn(0, 4, 8);
                app.setSelShade('#FACC15');
                ok('底纹标记[.hlmark=' + ed.querySelectorAll('.hlmark').length + ']',
                   ed.querySelectorAll('.hlmark').length === 1);
                selIn(1, 2, 4);
                app.applyTag(app.tagDefs[0]);
                ok('标签标记[.tagmark=' + ed.querySelectorAll('.tagmark').length + ']',
                   ed.querySelectorAll('.tagmark').length === 1);
                var tm = ed.querySelector('.tagmark');
                ok('标签名字[data-tag=' + (tm ? tm.getAttribute('data-tag') : '无') + ']',
                   !!tm && tm.getAttribute('data-tag') === app.tagDefs[0].name);
                app.toggleNav();
                ok('导航面板[nShow=' + app.navShow + ' n=' + app.navList.length + ']',
                   app.navShow && app.navList.length >= 3);
                app.toggleNav();
                /* r21：目录功能已删，导航弹出在位 */
                ok('目录功能已删[genToc=' + typeof app.genToc + ']',
                   typeof app.genToc === 'undefined');
                ok('导航弹出桥[popNav=' + typeof app.popNav + ' payload=' +
                   (app.navList.length ? '…' : '空') + ']',
                   typeof app.popNav === 'function' && typeof app.gotoNavIndex === 'function'
                   && typeof app.navPopClose === 'function');
                /* r21：HTML 持久化 —— onInput 自动保存后 getDraftHtml 要有格式 */
                var h2 = document.createElement('h2');
                h2.textContent = '持久化小节';
                ed.appendChild(h2);
                app.onInput();
                var dh = String(app.host('getDraftHtml') || '');
                ok('格式持久化[html含h2=' + (dh.indexOf('<h2') >= 0) + ']',
                   dh.indexOf('<h2') >= 0 && dh.indexOf('持久化小节') >= 0);
                /* r31：重复应用 = 取消（toggle）。
                   再选同一处（这次在 hlmark span 里）套同一个色 → 底纹消失 */
                function selWrap(sel0) {
                  var elw = ed.querySelector(sel0);
                  var rw = document.createRange(); rw.selectNodeContents(elw);
                  var sw = window.getSelection(); sw.removeAllRanges(); sw.addRange(rw);
                }
                selWrap('.hlmark');
                app.setSelShade('#FACC15');
                ok('底纹重复应用即取消[.hlmark=' + ed.querySelectorAll('.hlmark').length + ']',
                   ed.querySelectorAll('.hlmark').length === 0);
                /* 颜色同理：上色 → 再上同色 → 回到系统默认 */
                ed.innerHTML = '<p>颜色切换测试的段落文字。</p>';
                app.onInput();
                selIn(0, 2, 6);
                app.setSelColor('#E11D48');
                ok('文字颜色已上[span=' + ed.querySelectorAll('#editor span[style*="color"]').length + ']',
                   ed.querySelectorAll('#editor span[style*="color"]').length === 1);
                selWrap('#editor span[style*="color"]');
                app.setSelColor('#E11D48');
                ok('颜色重复应用即取消[span=' + ed.querySelectorAll('#editor span[style*="color"]').length + ']',
                   ed.querySelectorAll('#editor span[style*="color"]').length === 0);
                /* r31：清掉样式的旧残留 span（透明底纹）不再进导航 */
                var legacy = document.createElement('span');
                legacy.className = 'hlmark';
                legacy.style.backgroundColor = 'transparent';
                legacy.textContent = '旧残留';
                ed.querySelector('p').appendChild(legacy);
                app.onInput();
                app.toggleNav();
                var hasStale = false;
                for (var ni = 0; ni < app.navList.length; ni++)
                  if (app.navList[ni].type === 'm') hasStale = true;
                ok('透明底纹残留不入导航[nav=' + app.navList.length + ' stale=' + hasStale + ']',
                   !hasStale);
                app.toggleNav();
                /* r21：底纹左键直套上次色（换干净内容再测，选区好定位） */
                ed.innerHTML = '<p>底纹快捷测试段落文字。</p>'
                             + '<h1>第一章 大风歌</h1><p>大风起兮云飞扬。</p>';
                app.onInput();
                selIn(0, 0, 4);
                app.quickShade();
                ok('底纹左键直套[.hlmark=' + ed.querySelectorAll('.hlmark').length
                   + ' lastShade=' + app.lastShade + ']',
                   ed.querySelectorAll('.hlmark').length === 1 && app.lastShade === '#FACC15');
                ok('底纹笔开启[shadePaint=' + app.shadePaint + ']', app.shadePaint === true);
                app.quickShade();
                ok('底纹笔再点关闭[' + app.shadePaint + ']', app.shadePaint === false);
                /* r24：底纹笔模式 = 先点"纹"再选文字也上色（涂刷本体同步测） */
                ed.innerHTML = '<p>底纹笔涂刷测试段落。</p>';
                app.onInput();
                selIn(0, 0, 3);
                app.shadePaint = true;
                app.paintShadeNow();
                ok('底纹笔选中即上色[.hlmark=' + ed.querySelectorAll('.hlmark').length + ']',
                   ed.querySelectorAll('.hlmark').length === 1);
                app.shadePaint = false;
                /* r25：颜色笔 = 先点 A 再选文字也变色；两支笔互斥 */
                ed.innerHTML = '<p>颜色笔涂刷测试段落。</p>';
                app.onInput();
                selIn(0, 0, 3);
                app.lastColor = '#DC2626';
                app.colorPaint = true;
                app.paintColorNow();
                var cSpan = ed.querySelector('p span[style*="color"]');
                ok('颜色笔选中即变色[' + (cSpan ? cSpan.style.color : '-') + ']',
                   !!cSpan && (/rgb\(220,\s*38,\s*38\)/i.test(cSpan.style.color) ||
                               /#dc2626/i.test(cSpan.style.color)));
                app.colorPaint = false;
                app.shadePaint = true;
                app.quickColor();          // 开颜色笔 → 底纹笔自动关
                ok('两支笔互斥[shade=' + app.shadePaint + ' color=' + app.colorPaint + ']',
                   !app.shadePaint && app.colorPaint);
                app.quickColor();          // 再点关掉
                ok('颜色笔再点关闭[' + app.colorPaint + ']', app.colorPaint === false);
                /* r25：字体库有固定位置 + 字体下拉只列常用字体 */
                ok('字体库有固定路径[' + app.fontsDir + ']', !!app.fontsDir);
                ok('字体下拉是常用字体[数量=' + app.fontList.length + ']',
                   app.fontList.length > 0 && app.fontList.length <= 40);
                /* r24：全文首行缩进 = 段落级 CSS，所有段落自动两格（不用套格式） */
                app.cfg.indentFirst = true; app.applyVars();
                ed.innerHTML = '<p>甲段文字。</p><p>乙段文字。</p>';
                var pEls = ed.querySelectorAll('p');
                var ti1 = getComputedStyle(pEls[0]).textIndent;
                var ti2 = getComputedStyle(pEls[1]).textIndent;
                ok('全文首行缩进生效[甲=' + ti1 + ' 乙=' + ti2 + ']',
                   parseFloat(ti1) > 20 && ti1 === ti2);
                app.cfg.indentFirst = false; app.applyVars();
                ti1 = getComputedStyle(pEls[0]).textIndent;
                ok('关闭后归零[' + ti1 + ']', parseFloat(ti1) === 0);
                app.cfg.indentFirst = true; app.applyVars();
                /* r24：字号输入框 = 选区被输入框抢走也能套上（_selRange 缓存） */
                ed.innerHTML = '<p>甲乙丙丁测试。</p>';
                app.onInput();
                selIn(0, 0, 3);
                var savedSel = window.getSelection().getRangeAt(0).cloneRange();
                app._selRange = savedSel;
                app._edDown = false;
                var inpPx = document.createElement('input');
                document.body.appendChild(inpPx); inpPx.focus();   // 焦点被输入框抢走
                app.applySelPx(22);
                var pxSpan = ed.querySelector('span[style*="font-size"]');
                ok('焦点被抢走仍能改选中字号[' + (pxSpan ? pxSpan.style.fontSize : '-') + ']',
                   !!pxSpan && pxSpan.style.fontSize === '22px');
                document.body.removeChild(inpPx);
                /* r24：工具栏 B/I/U 跟随光标/选区显示 */
                app.syncCaretFormat();
                ok('B/I/U 态字段就绪[b=' + app.caretBold + ' i=' + app.caretItalic +
                   ' u=' + app.caretUnder + ']',
                   typeof app.caretBold === 'boolean' &&
                   typeof app.caretItalic === 'boolean' &&
                   typeof app.caretUnder === 'boolean');
                /* r21：贴边态 flat 类（阴影收掉，圆角不发灰） */
                app.cfg.bubbleCloud = false;
                app.applyBubblePad();
                var apEl = document.getElementById('app');
                ok('贴边态去阴影[flat=' + apEl.classList.contains('flat') + ']',
                   apEl.classList.contains('flat'));
                app.cfg.bubbleCloud = true;
                app.applyBubblePad();
                /* 中西文字体栈：西文在最前 */
                app.setGlobalWestFont('Georgia');
                var fv = document.documentElement.style.getPropertyValue('--font')
                           .replace(/['"]/g, '');
                ok('西文字体栈[' + fv.slice(0, 20) + ']', fv.indexOf('Georgia,') === 0);
                app.setGlobalWestFont('');
                /* 样式斜体：H1 开斜体 → --h1it */
                app.cfg.h1Italic = true;
                app.applyStyles();
                ok('样式斜体[--h1it='
                   + document.documentElement.style.getPropertyValue('--h1it') + ']',
                   document.documentElement.style.getPropertyValue('--h1it') === 'italic');
                app.cfg.h1Italic = false;
                app.applyStyles();
                /* 自定义字号（小数也行） */
                selIn(0, 0, 2);
                app.applySelPx(18.5);
                ok('自定义字号18.5px', !!ed.querySelector('span[style*="18.5px"]'));
                /* 字体库桥 */
                var lst = String(app.host('listImportedFonts') || '');
                ok('字体库列表[list=' + (lst.charAt(0) === '[' ? 'ok' : lst) + ']',
                   lst.charAt(0) === '[');
                /* 恢复正文，别污染后面的断言 */
                ed.innerHTML = '';
                app.onInput();
                } catch (e20) { ok('r20 段异常: ' + e20.message, false); }

                /* 14) 零 JS 异常 */
                ok('零 JS 异常' + (window.__errs && window.__errs.length
                   ? '：' + window.__errs.join(' | ') : ''), !window.__errs || window.__errs.length === 0);

                app.cfg.alwaysWords = false;
                app.exitBubble();
                setTimeout(function () {
                  /* 9.9) 工具条下拉真的把"光标处字体/字号"显示出来了（不是只改内部变量） */
                  try {
                    var fsel = document.querySelector('.toolbar .fontsel');
                    var ssel = document.querySelectorAll('.toolbar .tbsel')[1];
                    var numEl = document.querySelector('.toolbar .tbnum');
                    var fshow = (fsel && fsel.selectedOptions[0])
                              ? fsel.selectedOptions[0].textContent.trim() : '';
                    var sshow = (ssel && ssel.selectedOptions[0])
                              ? ssel.selectedOptions[0].textContent.trim() : '';
                    ed.innerHTML = '<p>正文一段话</p>';
                    caretToEnd();
                    app.syncCaretFormat();
                    ok('字体下拉显示光标字体[' + fshow + ' / ' + app.caretFont + ']',
                       !!fsel && !!app.caretFont && fshow === app.caretFont);
                    ok('字号下拉显示光标字号[' + sshow + ' / ' + app.sizeLabel(app.caretSize) + ']',
                       !!ssel && sshow === app.sizeLabel(app.caretSize));
                    ok('自定义字号是输入框[' + (numEl ? numEl.tagName + '/' + numEl.type : '-') + ']',
                       !!numEl && numEl.tagName === 'INPUT' && numEl.type === 'number');
                    /* 输入框填 25 直接生效（不再弹窗），而且只改选中那两个字 */
                    ed.innerHTML = '<p>字号输入框测试</p>';
                    var pn = ed.firstChild, tn = pn.firstChild;
                    var rn = document.createRange();
                    rn.setStart(tn, 0); rn.setEnd(tn, 2);
                    var sn = window.getSelection(); sn.removeAllRanges(); sn.addRange(rn);
                    app._range = rn.cloneRange();
                    app.sizeInput = 25;
                    app.applySizeInput();
                    var spn = ed.querySelector('span[style*="font-size"]');
                    ok('字号输入框直接生效[span=' + (spn ? spn.style.fontSize : '-') + ' 其余='
                       + (pn.textContent.length) + ']',
                       !!spn && spn.style.fontSize === '25px' && pn.textContent === '字号输入框测试');
                    /* 系统字体：主机只报本机装了的，且确实是一份非空列表 */
                    var sysf = String(app.host('listSystemFonts') || '');
                    ok('系统字体清单来自主机[前40=' + sysf.slice(0, 40) + ']',
                       sysf.charAt(0) === '[' && sysf.length > 12);
                    var sysArr = [];
                    try { sysArr = JSON.parse(sysf || '[]'); } catch (e3) {}
                    ok('字体下拉完全来自本机清单[主机=' + sysArr.length +
                       ' 界面=' + app.fontList.length + ']',
                       sysArr.length > 0 && app.fontList.length === sysArr.length);
                    ed.innerHTML = '';
                    app.onInput();
                  } catch (e21) { ok('工具条显示异常: ' + e21.message, false); }

                  /* 23) ★ 第 32 轮：气泡宽=实际行宽 / 导航无"（空）"残影 /
                         标记=选颜色手感 / 两端对齐按钮删除 */
                  (function stR32() {
                    /* 气泡尺寸断言必须在气泡模式里跑（不在就先进气泡再回来） */
                    if (app.mode !== 'bubble') {
                      app.enterBubble();
                      setTimeout(stR32, 150);
                      return;
                    }
                    try {
                      /* 词库常驻会占掉侧栏宽度，这块断言先关掉（编辑区才能钉死排版宽） */
                      app.showWords = false; app.cfg.alwaysWords = false;
                      /* 23.1 换行后框宽 = 光标所在实际视觉行的宽度（不是整段）。
                         光标放第 21 字（第 2 行中间，不放行尾——行尾光标的行盒
                         偶尔挂在上一行，量出的就不是这一行） */
                      ed.innerText = '一二三四五六七八九十一二三四五六七八九十一二';
                      (function() {
                        var tn = ed.firstChild;
                        if (tn && tn.nodeType !== 3) tn = app._lastTextNode(tn);
                        if (!tn || tn.nodeType !== 3) tn = ed.firstChild;
                        var off0 = Math.min(25, (tn && tn.nodeValue) ? tn.nodeValue.length : 0);
                        var rr0 = document.createRange();
                        rr0.setStart(tn, off0); rr0.collapse(true);
                        var ss0 = window.getSelection();
                        ss0.removeAllRanges(); ss0.addRange(rr0);
                      })();
                      app._bw = 0; app._bh = 0;
                      app._lastInputAt = 0;
                      app.applyBubbleSizeNow();
                      var row32 = app.caretRowRect();
                      var diagRows = (function() {
                        try {
                          var rr = document.createRange(); rr.selectNodeContents(ed);
                          var rs = rr.getClientRects(); var a = [];
                          for (var qi = 0; qi < rs.length; qi++)
                            if (rs[qi].height > 0) a.push(Math.round(rs[qi].width) + '@' + Math.round(rs[qi].top));
                          return a.join(',');
                        } catch (qd) { return 'ERR'; }
                      })();
                      var inner32 = app._bw - app.bubblePadH() - 10;
                      ok('框内宽=实际视觉行宽+余量[行=' + Math.round(row32.width) +
                         ' 内=' + Math.round(inner32) + ' rows=' + diagRows + ']',
                         Math.abs(inner32 - (row32.width + 8)) < 3);
                      ok('换行后框缩到当前行（不按整行给宽）[内=' + Math.round(inner32) +
                         ' 整行=' + Math.round(app.measureLineWidth(
                           '一二三四五六七八九十一二三四五六七八九十', 16)) + ']',
                         inner32 < app.measureLineWidth(
                           '一二三四五六七八九十一二三四五六七八九十', 16) - 20);
                      ok('排版宽已钉死[ed.width=' + ed.style.width + ']',
                         /\d+px/.test(ed.style.width || ''));

                      /* 23.2 导航：空壳标记/底纹不出现"（空）""重要"残影 */
                      ed.innerHTML = '<p>有字的重点' +
                        '<span class="hlmark" style="background:#FACC15">重要内容</span>尾</p>' +
                        '<p>空壳<span class="hlmark" style="background:#FACC15"></span>点</p>' +
                        '<p>标记<span class="tagmark" data-tag="重要"></span>尾</p>';
                      var nav32 = app.buildNavList().map(function(x) {
                        return x.tag + ':' + x.text; }).join('|');
                      ok('导航无空条目[' + nav32 + ']',
                         nav32.indexOf('（空）') < 0 && nav32.indexOf('重要:') < 0 &&
                         nav32.indexOf('重要|') < 0);
                      ok('空壳 span 已扫除[剩hlmark=' +
                         ed.querySelectorAll('.hlmark').length + ' tagmark=' +
                         ed.querySelectorAll('.tagmark').length + ']',
                         ed.querySelectorAll('.hlmark').length === 1 &&
                         ed.querySelectorAll('.tagmark').length === 0);

                      /* 23.3 清除样式连"标记"一起拆（以前只拆底纹类，data-tag 留着） */
                      ed.innerHTML = '<p>前段' +
                        '<span class="tagmark" data-tag="重要" ' +
                        'style="--tagc:rgba(245,158,11,.28)">标记文字</span>后段</p>';
                      var tm32 = ed.querySelector('.tagmark');
                      var rt32 = document.createRange(); rt32.selectNodeContents(tm32);
                      var st32b = window.getSelection();
                      st32b.removeAllRanges(); st32b.addRange(rt32);
                      app.markPop = 'bg';
                      app.clearSelMark();
                      ok('清除样式连标记一起拆[剩=' +
                         ed.querySelectorAll('.tagmark,[data-tag]').length + ']',
                         !ed.querySelector('.tagmark') && !ed.querySelector('[data-tag]'));
                      ok('导航里也没有这条标记',
                         app.buildNavList().every(function(x) { return x.type !== 't'; }));

                      /* 23.4 标记 = 选颜色的手感：没选中也能选（固定住），
                         之后选中的文字自动套；再点同一个标记 = 取消并解除固定。
                         用全新名字的标记，避免和落盘的草稿/旧标记撞车。 */
                      var fresh32 = { name: '自测标' + (Date.now() % 1000000), color: '#F59E0B' };
                      app.tagDefs.push(fresh32);
                      var tt32 = fresh32;
                      ed.innerHTML = '<p>一二三四五六</p>';
                      /* 清掉缓存选区：必须真的"没选中"才能验证固定逻辑 */
                      app._selRange = null; app._range = null;
                      var c32 = document.createRange();
                      c32.setStart(ed.firstChild.firstChild, 0); c32.collapse(true);
                      var s32 = window.getSelection();
                      s32.removeAllRanges(); s32.addRange(c32);
                      var diagS = window.getSelection();
                      var diagInfo = 'rc=' + diagS.rangeCount + ' col=' +
                        (diagS.rangeCount ? diagS.isCollapsed : '-') +
                        ' cache=' + !!app._selRange + ' tagDefs=' + app.tagDefs.length;
                      app.applyTag(tt32);
                      ok('没选中也能固定标记[sel=' + diagInfo +
                         ' paint=' + app.tagPaint + ' cur=' + app.curTag + ']',
                         app.tagPaint && app.curTag === tt32.name);
                      var r32b = document.createRange();
                      r32b.setStart(ed.firstChild.firstChild, 0);
                      r32b.setEnd(ed.firstChild.firstChild, 4);
                      s32.removeAllRanges(); s32.addRange(r32b);
                      app.paintTagNow();
                      var mk32 = ed.querySelector('.tagmark');
                      ok('固定后选中的文字自动套标记[data=' +
                         (mk32 ? mk32.getAttribute('data-tag') : '无') + ']',
                         !!mk32 && mk32.getAttribute('data-tag') === tt32.name);
                      app.applyTag(tt32);      // 选区就是刚套的标记文字：再点=取消
                      ok('再点同一个标记=取消并解除固定[剩=' +
                         ed.querySelectorAll('.tagmark').length + ' paint=' +
                         app.tagPaint + ']',
                         !ed.querySelector('.tagmark') && !app.tagPaint);

                      /* 23.6 ★ 第 33 轮：光标不在最后一行 = 上下文三行
                         （上一行、这一行、下一行），在最后一行才是一行贴字 */
                      app._selRange = null; app._range = null;
                      /* 55 字 = 3 个视觉行（20/20/15）：光标放第 2 行中间才是"不在最后一行" */
                      ed.innerText = '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十' +
                                     '一二三四五六七八九十甲乙丙丁戊';
                      var met33 = app.caretMetrics();
                      var caretAt33 = function(off) {
                        var tn = ed.firstChild;
                        if (tn && tn.nodeType !== 3) tn = app._lastTextNode(tn);
                        var rrE = document.createRange();
                        rrE.setStart(tn, Math.min(off, tn.nodeValue.length));
                        rrE.collapse(true);
                        var ssE = window.getSelection();
                        ssE.removeAllRanges(); ssE.addRange(rrE);
                      };
                      caretAt33(999);                     // 末尾 = 最后一行
                      app._bw = 0; app._bh = 0;
                      app._lastInputAt = Date.now();      // 模拟打字态：绝不当"翻看"
                      app._caretFixPending = false;
                      app.applyBubbleSizeNow();
                      ok('光标在最后一行=一行贴字[onLast=' + app.caretOnLastRow() +
                         ' h=' + app._bh + ' 期望≈' + (Math.ceil(met33.lh) + app.bubblePadV()) + ']',
                         app.caretOnLastRow() === true &&
                         Math.abs(app._bh - (Math.ceil(met33.lh) + app.bubblePadV())) < 4);
                      caretAt33(25);                      // 第 2 行中间，不在最后一行
                      app._lastInputAt = Date.now();
                      app.applyBubbleSizeNow();
                      var h33 = app._bh, lo33 = Math.ceil(met33.lh * 2.5) + app.bubblePadV(),
                          hi33 = Math.ceil(met33.lh * 3.7) + app.bubblePadV();
                      ok('光标不在最后一行=上下文三行[onLast=' + app.caretOnLastRow() +
                         ' h=' + h33 + ' 应在' + lo33 + '~' + hi33 +
                         ' w=' + app._bw + ' bub3=' + app._bub3 + ']',
                         app.caretOnLastRow() === false && app._bub3 === true &&
                         h33 >= lo33 && h33 <= hi33);

                      /* 23.7 光标停在底纹里（没选中）也能"清除" */
                      ed.innerHTML = '<p>前后<span class="hlmark" ' +
                        'style="background:#FACC15">底纹文字</span>后</p>';
                      var hm33 = ed.querySelector('.hlmark');
                      var rC33 = document.createRange();
                      rC33.setStart(hm33.firstChild, 2); rC33.collapse(true);
                      var sC33 = window.getSelection();
                      sC33.removeAllRanges(); sC33.addRange(rC33);
                      app._selRange = null; app._range = null;
                      app.markPop = 'bg';
                      app.clearSelMark();
                      ok('光标停在底纹里（没选中）也能清除[剩=' +
                         ed.querySelectorAll('.hlmark').length + ']',
                         !ed.querySelector('.hlmark'));

                      /* 23.8 同一底纹涂两次=恢复无格式（toggle） */
                      ed.innerHTML = '<p>一二三四五六</p>';
                      var rD33 = document.createRange();
                      rD33.setStart(ed.firstChild.firstChild, 0);
                      rD33.setEnd(ed.firstChild.firstChild, 4);
                      var sD33 = window.getSelection();
                      sD33.removeAllRanges(); sD33.addRange(rD33);
                      app.lastShade = '#FACC15';
                      app.paintShadeNow();
                      var once33 = !!ed.querySelector('.hlmark');
                      app.paintShadeNow();
                      ok('底纹笔同一颜色涂两次=恢复无格式[一次=' + once33 +
                         ' 两次后剩=' + ed.querySelectorAll('.hlmark').length + ']',
                         once33 && !ed.querySelector('.hlmark'));

                      /* 23.9 标记笔同一标记涂两次=恢复无格式（toggle） */
                      ed.innerHTML = '<p>一二三四五六</p>';
                      var ft33 = { name: '自测标B' + (Date.now() % 1000000), color: '#EF4444' };
                      app.tagDefs.push(ft33);
                      app.curTag = ft33.name; app.tagPaint = true;
                      var rE33 = document.createRange();
                      rE33.setStart(ed.firstChild.firstChild, 0);
                      rE33.setEnd(ed.firstChild.firstChild, 4);
                      var sE33 = window.getSelection();
                      sE33.removeAllRanges(); sE33.addRange(rE33);
                      app.paintTagNow();
                      var onceT33 = !!ed.querySelector('.tagmark');
                      app.paintTagNow();
                      ok('标记笔同一标记涂两次=恢复无格式[一次=' + onceT33 +
                         ' 两次后剩=' + ed.querySelectorAll('.tagmark').length + ']',
                         onceT33 && !ed.querySelector('.tagmark'));
                      app.tagPaint = false; app.curTag = '';

                      /* 23.10 嵌套壳（tagmark 包 hlmark）：光标停在里头也能一路拆净 */
                      ed.innerHTML = '<p>前后<span class="tagmark" data-tag="重要" ' +
                        'style="--tagc:rgba(245,158,11,.28)">' +
                        '<span class="hlmark" style="background:#FACC15">嵌套文字</span></span>后</p>';
                      var ne33 = ed.querySelector('.hlmark');
                      var rn33 = document.createRange();
                      rn33.setStart(ne33.firstChild, 2); rn33.collapse(true);
                      var sn33 = window.getSelection();
                      sn33.removeAllRanges(); sn33.addRange(rn33);
                      app._selRange = null; app._range = null;
                      app.markPop = 'bg'; app.clearSelMark();
                      ok('嵌套壳从光标位拆净[hlmark=' + ed.querySelectorAll('.hlmark').length +
                         ' tagmark=' + ed.querySelectorAll('.tagmark').length + ']',
                         !ed.querySelector('.hlmark') && !ed.querySelector('.tagmark'));

                      /* 23.11 选中跨多个标记：一次全拆（以前只拆起点那层） */
                      ed.innerHTML = '<p><span class="tagmark" data-tag="甲标" ' +
                        'style="--tagc:#123456">甲</span>和' +
                        '<span class="tagmark" data-tag="乙标" style="--tagc:#654321">乙</span></p>';
                      var p33 = ed.querySelector('p');
                      var rr33 = document.createRange(); rr33.selectNodeContents(p33);
                      var s33b = window.getSelection();
                      s33b.removeAllRanges(); s33b.addRange(rr33);
                      app.markPop = 'bg'; app.clearSelMark();
                      ok('跨多个标记一次全拆[剩=' + ed.querySelectorAll('.tagmark').length + ']',
                         !ed.querySelector('.tagmark'));
                      /* （23.12 右键挪光标断言挪到 23.5 的完整模式回调里跑 ——
                          气泡窗口太小，点击点会落在视口外，caretRangeFromPoint 量不到） */
                    } catch (e32) { ok('第32轮断言异常: ' + e32.message, false); }

                    /* 23.5 两端对齐按钮已删除（工具条只在完整模式渲染） */
                    app.mode = 'full';
                    setTimeout(function() {
                      var jb32 = document.querySelector('.albtn[title="两端对齐"]');
                      var al32 = document.querySelectorAll('.albtn').length;
                      ok('两端对齐按钮已删除[found=' + !!jb32 + ' 对齐钮=' + al32 + ']',
                         !jb32 && al32 === 3);

                      /* 23.12 右键无选中：光标挪到点击处（清除才作用在右键那段字上）。
                         完整模式窗口够大，点击点一定在视口里 */
                      ed.innerHTML = '<p>零散文字有标记' +
                        '<span class="tagmark" data-tag="目标" style="--tagc:#9abcde">目标字</span>尾</p>';
                      var tc33 = ed.querySelector('.tagmark');
                      ed.scrollTop = 0;
                      var tr33 = document.createRange();
                      tr33.setStart(ed.firstChild.firstChild, 1); tr33.collapse(true);
                      var ts33 = window.getSelection();
                      ts33.removeAllRanges(); ts33.addRange(tr33);
                      var trect33 = tc33.getBoundingClientRect();
                      var cx33 = Math.round(trect33.left + trect33.width / 2),
                          cy33 = Math.round(trect33.top + trect33.height / 2);
                      var dbg33 = 'click=' + cx33 + ',' + cy33 + ' vp=' +
                        window.innerWidth + 'x' + window.innerHeight + ' | ';
                      for (var sy = 60; sy <= Math.min(window.innerHeight - 10, 300); sy += 40) {
                        var ea = document.elementFromPoint(200, sy);
                        var qr = null;
                        try { qr = document.caretRangeFromPoint(200, sy); } catch (e9) {}
                        dbg33 += '(200,' + sy + ')=' + (ea ? ea.tagName : 'null') + '/' +
                                 (qr ? 'R' : 'null') + ' ';
                      }
                      app.ctxOpen({ clientX: cx33, clientY: cy33 });
                      var inTag33 = false;
                      try {
                        var sc33 = window.getSelection();
                        inTag33 = sc33.rangeCount > 0 && tc33.contains(sc33.getRangeAt(0).startContainer);
                      } catch (e33b) {}
                      ok('右键无选中时光标挪到点击处[' + dbg33 + ' inTag=' + inTag33 + ']',
                         inTag33);
                      app.markPop = 'bg'; app.clearSelMark();
                      ok('右键挪光标后清除生效[剩=' + ed.querySelectorAll('.tagmark').length + ']',
                         !ed.querySelector('.tagmark'));
                      app.ctxShow = false;
                      app.tagPaint = false; app.curTag = ''; app.markPop = '';
                      ed.innerHTML = ''; app.onInput();
                      stVis();                 // 继续 22) 光标行可见断言
                    }, 120);
                  })();

                  /* 22) ★ 输入后画面不能"跳到别的文字那里"：
                     框尺寸动过、WebView 重排之后，光标所在行必须还在框里。
                     用一段很长的文档 + 光标停在中段 —— 正是用户报的场景。 */
                  function stVis() {
                    if (app.mode !== 'bubble') {
                      app.enterBubble();
                      setTimeout(stVis, 120);
                      return;
                    }
                    var big = [];
                    for (var bi = 0; bi < 30; bi++)
                      big.push('第' + bi + '段：这是比较长的开头文字，用来撑出滚动距离。');
                    big.push('光标停在这一段的中间位置，前后都还有很多字要打。');
                    for (var bj = 0; bj < 30; bj++) big.push('后面第' + bj + '段。');
                    ed.innerText = big.join('\n');
                    var mnode = null;
                    (function walkN(n) {
                      if (mnode) return;
                      if (n.nodeType === 3) {
                        if ((n.nodeValue || '').indexOf('光标停在') >= 0) mnode = n;
                        return;
                      }
                      for (var wi = 0; wi < n.childNodes.length; wi++) walkN(n.childNodes[wi]);
                    })(ed);
                    if (!mnode) { ok('输入后光标行仍在框里[找不到光标节点]', false);
                      window.__stResult = out.join('\n'); return; }
                    var rv = document.createRange();
                    rv.setStart(mnode, Math.min(8, mnode.nodeValue.length));
                    rv.collapse(true);
                    var sv = window.getSelection(); sv.removeAllRanges(); sv.addRange(rv);
                    app._bw = 0; app._bh = 0;
                    app._lastInputAt = 0;
                    app.applyBubbleSizeNow();
                    setTimeout(function () {
                      app.scrollCaretIntoView();
                      setTimeout(function () {
                        var ovv = app.caretOverflowPx();
                        ok('输入后光标行仍在框里[overflow=' +
                           (ovv === null ? 'null' : Math.round(ovv)) + 'px]',
                           ovv !== null && Math.abs(ovv) < 4);
                        ed.innerHTML = '';
                        window.__stResult = out.join('\n');
                      }, 120);
                    }, 200);
                  }
                  /* stVis 由 23) 块收尾时调用（避免两块并发跑） */
                  return;    // __stResult 由 stVis 块写入
                }, 260);
              }, 500);
            }, 500);
          }, 120);
        }, 30);          // 12) 气泡层
        }, 60);          // 11.2 弹层拖动层
        }, 60);          // 11.1 右键底纹文字层
        }, 60);          // 11.1 右键"纹"按钮层
      }, 30);
      }, 30);            // 完整模式包装层（工具条渲染等待）
        }, 200);       // D 设置面板
      }, 60);          // C 关查找断言
    }, 60);            // B 打开查找
  }, 420);             // A 气泡块

  return 'PENDING';
})();
