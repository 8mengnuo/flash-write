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
     (bhtml.indexOf('<b') >= 0 || bhtml.indexOf('bold') >= 0)
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
  app.onHostKey('Tab');                        // 没命中码 → 当缩进
  ok('Tab 没命中码时缩进两格[' + JSON.stringify(app.getText()) + ']',
     app.getText() === 'ab\u3000\u3000');
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
  app.applySelSize();
  var szSpan = ed.querySelector('span[style*="font-size"]');
  ok('选中文字字号生效[' + (szSpan ? szSpan.style.fontSize : '-') + ']',
     !!szSpan && szSpan.style.fontSize === '20px' && app.getText() === '甲乙丙');
  app.tbFont = '楷体';
  app.applySelFont();
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
  ok('普通字体名原样保留[' + app.cssFamily('楷体') + ']', app.cssFamily('楷体') === "'楷体'");
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
  ed.innerText = 'ab';
  caretToEnd();
  key('9', { ctrl: true, code: 'Digit9' });
  ok('重绑 Ctrl+9 -> 段首缩进生效[' + app.getText() + ']', app.getText() === 'ab\u3000\u3000');
  app.host('setKey', 'indent', 'Ctrl+1');
  app.cfg.keys.indent = 'Ctrl+1';

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

  /* 6.5) 环绕元素边距：可调；全透明/关闭时归零 */
  app.cfg.cloudMargin = 12;
  app.applyAll();
  var cfgNow2 = JSON.parse(app.host('getConfig'));
  var padNow = document.documentElement.style.getPropertyValue('--bpad');
  ok('边距可调 cloudMargin=12[' + cfgNow2.cloudMargin + ' pad=' + padNow + ']',
     cfgNow2.cloudMargin === 12 && padNow === '12px');
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

    /* 9.2) 气泡两态尺寸：输入态 = 小胶囊，宽度跟着"这一行"的字数长；
       以前内边距少算 20 多像素，一行只塞得下一个字 —— 这里按真实内宽验。
       回看态（光标挪上去）= 展开大窗。 */
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
    var innerFive = wFive - app.bubblePadH() - 2;
    var needFive = app.measureLineWidth('一二三四五', fs0);
    ok('胶囊内宽装得下整行[' + Math.round(innerFive) + ' >= ' + Math.round(needFive) + ']',
       innerFive >= needFive - 1);
    ok('胶囊宽度随字数变长[w=' + wOne + '→' + wFive + ']', wFive > wOne + 10);
    var capW = Math.ceil(app.cfg.bubbleCharsPerLine * fs0) + app.bubblePadH() + 8;
    ok('到"每行字数"就不再变宽[w=' + wLong + ' <= ' + capW + ']', wLong <= capW);
    ok('长行仍是一行高[h=' + app._bh + ']',
       app._bh <= Math.ceil(fs0 * 1.9) + app.bubblePadV() + 4);
    var hTyping = app._bh;
    /* 回看态：光标挪到第一行开头 */
    ed.innerText = '第一行\n第二行内容';
    var rMid = document.createRange();
    rMid.setStart(ed.firstChild, 0);
    rMid.collapse(true);
    var sMid = window.getSelection(); sMid.removeAllRanges(); sMid.addRange(rMid);
    app._bw = 0; app._bh = 0;
    app.applyBubbleSizeNow();
    var hReview = app._bh;
    ok('气泡回看态展开[h=' + hReview + ' > 输入态 ' + hTyping + ']', hReview > hTyping);
    caretToEnd();
    app.applyBubbleSizeNow();
    ok('回到文末缩回小胶囊[h=' + app._bh + ']', app._bh <= hTyping + 2);

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
    ok('关掉手动回到自适应[w=' + app._bw + ' < ' + (500 + sideW) + ']', app._bw < 500 + sideW);

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

      /* 11) 零 JS 异常 */
      ok('零 JS 异常' + (window.__errs && window.__errs.length
         ? '：' + window.__errs.join(' | ') : ''), !window.__errs || window.__errs.length === 0);

      app.cfg.alwaysWords = false;
      app.exitBubble();
      setTimeout(function () { window.__stResult = out.join('\n'); }, 260);
        }, 200);       // D 设置面板
      }, 60);          // C 关查找断言
    }, 60);            // B 打开查找
  }, 420);             // A 气泡块

  return 'PENDING';
})();
