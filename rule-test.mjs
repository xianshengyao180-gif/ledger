// 把 BillParser 的正则规则原样搬到 Node 里，用真实通知文案验证。
// 关键：断言「符号」而不仅是「解析出东西」——上一版就是漏了符号才放过一个真 bug。
//
// 本文件必须与 BillParser.java 逐字对应。任何一边改了规则，两边一起改，
// 否则测试全绿也可能只是「镜像和实现不一致」的假绿。
'use strict';

const IGNORE = /待支付|未支付|还未支付|支付失败|付款失败|扣款失败|交易关闭|自动取消|验证码|登录提醒|配置更新|请勿泄露|请勿告诉|待付款|未付款|交易失敗|付款失敗|已取消|驗證碼|登錄提醒|請勿洩露|請勿告訴|請勿外傳/;
const EXPENSE = /支出|付款|支付|消费|消費|扣款|转出|轉出|提现|提現|买单|買單|代扣|转账|轉賬|轉帳|汇款|匯款/;
const INCOME = /退款|退回|返还|返還|收入|收款|到账|到賬|到帳|入账|入賬|入帳|转入|轉入/;
const INCOMING = /收到转账|收到轉賬|收到轉帳|向你转账|向你轉賬|向你轉帳|给你转账|给你轉賬|转账给你|轉賬給你|轉帳給你|已收钱|已收錢|已收款/;
const TRANSFER_WORD = /转账|轉賬|轉帳/;
const TRANSFER_DONE = /已被接收|已被領取|已被领取|已接收|已領取|已领取/;
const WECHAT_BUBBLE = /\[(?:转账|轉賬|轉帳)\]/;
const REFUND = /退款|退回|返还|返還/;
// 标题白名单：实测新版微信付款通知标题是英文 Weixin Pay，只认「微信支付」会漏
const WX_TITLES = ['微信支付', 'Weixin Pay', '微信收款助手', '收款小账本', '微信轉賬', '微信轉帳'];
const CURRENCY = /(?:(?:￥|¥|人民币|RMB|CNY)\s*([0-9]+(?:,[0-9]{3})*(?:\.\d{1,2})?|[0-9]+(?:\.\d{1,2})?)|([0-9]+(?:,[0-9]{3})*(?:\.\d{1,2})?|[0-9]+(?:\.\d{1,2})?)\s*(?:元|块|圆|塊|圓))/;
const SEMANTIC = /(?:金额|消費|消费|支付|支出|收入|转入|轉入|转出|轉出|扣款|付款|收款|入账|入賬|入帳|提现|提現|退款|转账|轉賬|轉帳|汇款|匯款|金额|金額)[^0-9￥¥]{0,8}(?:￥|¥)?\s*([0-9]+(?:,[0-9]{3})*(?:\.\d{1,2})?|[0-9]+(?:\.\d{1,2})?)/;

function firstGroup(m) { for (let i = 1; i <= m.length - 1; i++) if (m[i]) return m[i]; return ''; }
function collect(text, re) {
  const out = [];
  for (const m of text.matchAll(new RegExp(re.source, 'g'))) {
    const v = firstGroup(m);
    if (!v) continue;
    const d = parseFloat(v.replace(/,/g, ''));
    if (d > 0 && !out.some(x => Math.abs(x - d) < 0.01)) out.push(d);
  }
  return out;
}
function channelOf(pkg) {
  if (pkg === 'com.tencent.mm') return '微信';
  if (pkg === 'com.eg.android.AlipayGphone' || pkg.startsWith('com.alipay.')) return '支付宝';
  return '银行卡';
}
// 返回 {amount, channel, pending} 或 null。pending=true 表示「待用户确认，不得写库」。
function parse(pkg, title, text) {
  const compact = (text || '').replace(/\s+/g, '');
  if (!compact || IGNORE.test(compact)) return null;
  const isAlipay = pkg === 'com.eg.android.AlipayGphone' || pkg.startsWith('com.alipay.');
  const isWechat = pkg === 'com.tencent.mm';
  const kw = compact.replace(/微信支付/g, '').replace(/支付宝/g, '');
  const incomingTransfer = INCOMING.test(kw);
  const transferWord = TRANSFER_WORD.test(kw);
  const transferDone = transferWord && TRANSFER_DONE.test(kw);
  const wechatBubble = WECHAT_BUBBLE.test(kw);
  const hasExpense = EXPENSE.test(kw);
  const hasIncome = INCOME.test(kw);
  const isRefund = REFUND.test(kw);
  const matchesTitle = isWechat
    ? WX_TITLES.some(t => title.includes(t))
    : (isAlipay && title.includes('交易提醒'));

  if (transferDone) {
    const known = collect(text, CURRENCY).concat(collect(text, SEMANTIC));
    const amount = known.length ? Math.abs(known[0]) : 0;
    const who = (title || '').trim();
    return { amount: -amount, channel: channelOf(pkg), pending: true, who: who || '转账' };
  }
  if (wechatBubble) return null;

  if (!hasExpense && !hasIncome && !incomingTransfer && !matchesTitle) return null;
  const amounts = [...collect(text, CURRENCY), ...collect(text, SEMANTIC)];
  if (!amounts.length) return null;
  const amount = Math.abs(amounts[0]);
  const income = incomingTransfer || isRefund || (hasIncome && !hasExpense);
  return { amount: income ? amount : -amount, channel: channelOf(pkg), pending: false };
}

const WX = 'com.tencent.mm', ALI = 'com.eg.android.AlipayGphone', BANK = 'com.icbc.mobile';
// [名称, 包名, 标题, 正文, 期望金额(正=收入/负=支出, null=忽略), 期望pending]
const cases = [
  ['微信付款', WX, '微信支付', '微信支付 已支付￥23.50', -23.50, false],
  ['微信付款(元)', WX, '微信支付', '你已支付 88.00元', -88.00, false],
  ['微信收款', WX, '微信支付', '微信支付 收款￥100.00 已到账', 100.00, false],
  ['微信收款(单字)', WX, '微信支付', '收款￥66.00', 66.00, false],
  ['微信退款', WX, '微信支付', '退款￥50.00 已退回', 50.00, false],
  ['支付宝付款', ALI, '交易提醒', '你已成功付款 35.00元', -35.00, false],
  ['支付宝付款(符号)', ALI, '交易提醒', '支付成功 ¥128.8', -128.80, false],
  ['支付宝收款', ALI, '交易提醒', '支付宝 收款 200.00元 已到账', 200.00, false],
  ['银行扣款', BANK, '账户变动', '您尾号1234的储蓄卡支出人民币 128.00 元', -128.00, false],
  ['银行入账', BANK, '账户变动', '您尾号1234账户收入人民币 5000.00 元', 5000.00, false],
  ['千分位', WX, '微信支付', '已支付￥1,234.56', -1234.56, false],
  ['微信转账(支出)', WX, '微信支付', '你已向张三转账￥100.00', -100.00, false],
  ['微信转账(待确认)', WX, '微信支付', '微信转账 100.00元 等待对方确认收款', -100.00, false],
  ['微信收到转账', WX, '微信支付', '张三向你转账￥100.00', 100.00, false],
  ['微信收到转账2', WX, '微信支付', '收到转账￥50.00', 50.00, false],
  ['实测:WeixinPay标题', WX, 'Weixin Pay', '已支付￥1.00', -1.00, false],
  ['WeixinPay收款式', WX, 'Weixin Pay', '收款￥20.00', 20.00, false],
  ['WeixinPay无金额→忽略', WX, 'Weixin Pay', '已支付', null, false],
  ['仅标题(无正文)', WX, '微信支付', '微信支付', null, false],
  ['待支付→忽略', WX, '微信支付', '待支付￥23.50', null, false],
  ['付款失败→忽略', ALI, '交易提醒', '支付失败 ￥23.50', null, false],
  ['验证码→忽略', BANK, '短信', '验证码 123456，请勿告诉他人', null, false],
  ['非支付通知→忽略', WX, '张三', '在吗？', null, false],

  // ↓↓↓ 本次新增：转账「已被接收」。实测真机文案是繁体，且没有金额。
  //     金额未知 → 一律不自动记账，转成待确认提案（pending=true）。
  //     注意：银行通知里的「已被接收」不带转账字样，必须不误伤（见下）。
  ['实测:繁体转账已被接收', WX, '老登', '[轉賬] 已被接收', 0, true],
  ['简体转账已被接收', WX, '张三', '[转账] 已被接收', 0, true],
  ['繁体转账待领取', WX, '张三', '[轉賬] 已被領取', 0, true],
  ['转账已被接收(带金额)', WX, '张三', '[转账]￥88.00 已被接收', -88.00, true],

  // ↓↓↓ 我发出的转账气泡：此刻不记账（对方可能 24h 后退回）。
  //     等「已被接收」那一刻再由用户确认。真实支出绝不漏、猜测的钱绝不记。
  ['聊天式转账气泡(简)', WX, '张三', '[转账]100.00', null, false],
  ['聊天式转账气泡(繁)', WX, '张三', '[轉賬]100.00', null, false],
  ['聊天式转账气泡(群)', WX, '项目群', '张三: [转账]￥88.00', null, false],

  // ↓↓↓ 银行「转账支出」是即时到账的真实支出，必须照常记（不带方括号）。
  ['银行转账支出', BANK, '账户变动', '您尾号1234账户转账支出 500.00 元', -500.00, false],
  ['银行繁体转账支出', BANK, '帳戶變動', '您尾號1234帳戶轉賬支出 500.00 元', -500.00, false],

  // ↓↓↓ 「接收」字样的误伤防线：普通通知不能被当成转账。
  ['普通文件已接收→忽略', WX, '张三', '文件已被接收', null, false],
  ['群公告→忽略', WX, '项目群', '[公告] 会议纪要已被接收', null, false],
];

let pass = 0, fail = 0;
for (const [name, pkg, title, text, expect, expectPending] of cases) {
  const r = parse(pkg, title, text);
  const got = r === null ? null : Number(r.amount.toFixed(2));
  const gotPending = r === null ? null : !!r.pending;
  const ok = got === expect && gotPending === (expect === null ? null : expectPending);
  if (ok) pass++; else fail++;
  const show = v => v === null ? 'null(忽略)' : (v >= 0 ? '+' : '') + v.toFixed(2);
  console.log(`${ok ? '✅' : '❌'} ${name.padEnd(26)} 期望 ${show(expect).padStart(10)}${expect === null ? '' : (expectPending ? ' 待确认' : ' 直接记')}`
    + `  实际 ${show(got).padStart(10)}${got === null ? '' : (gotPending ? ' 待确认' : ' 直接记')}`);
}
console.log(`\n通过 ${pass} / ${pass + fail}`);
process.exit(fail ? 1 : 0);
