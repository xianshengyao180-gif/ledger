// 用 node:sqlite 实跑 Db.java 里的建表/迁移/查询 SQL。
// 目的：确认 v1.5 的数据库层没有语法错误或逻辑回归，好排除「代码本身坏了」。
'use strict';
import { DatabaseSync } from 'node:sqlite';

let pass = 0, fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log(`✅ ${name}`); }
  else { fail++; console.log(`❌ ${name} ${extra}`); }
}

// ---------- 1. 全新安装：onCreate ----------
{
  const db = new DatabaseSync(':memory:');
  db.exec("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL,"
    + " channel TEXT, title TEXT, nkey TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE INDEX idx_bills_ts ON bills(ts DESC)");
  db.exec("CREATE TABLE IF NOT EXISTS raw_noti (id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + " pkg TEXT, title TEXT, body TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE INDEX IF NOT EXISTS idx_raw_ts ON raw_noti(ts DESC)");

  const cols = db.prepare("PRAGMA table_info(bills)").all().map(r => r.name);
  check('onCreate: bills 含 nkey 列', cols.includes('nkey'), JSON.stringify(cols));

  // insert(amount, channel, title, ts, nkey)
  db.prepare("INSERT INTO bills (amount, channel, title, nkey, ts) VALUES (?,?,?,?,?)")
    .run(-1.0, '微信', 'Weixin Pay', '0|com.tencent.mm|100|n|null|1', Date.now());
  const n = db.prepare("SELECT COUNT(*) c FROM bills").get().c;
  check('insert 带 nkey 成功', n === 1, `count=${n}`);

  // seenKey
  const key = '0|com.tencent.mm|100|n|null|1';
  const hit = db.prepare("SELECT 1 FROM bills WHERE nkey=? AND ts>=? LIMIT 1")
    .get(key, Date.now() - 60000);
  check('seenKey: 同一标识查得到（应去重）', !!hit);

  const miss = db.prepare("SELECT 1 FROM bills WHERE nkey=? AND ts>=? LIMIT 1")
    .get('另一个key', Date.now() - 60000);
  check('seenKey: 不同标识查不到（应记账）', !miss);

  // 旧数据的 nkey 为 NULL 时，seenKey 不应误命中
  db.prepare("INSERT INTO bills (amount, channel, title, nkey, ts) VALUES (?,?,?,?,?)")
    .run(-2.0, '微信', 'Weixin Pay', null, Date.now());
  const nullHit = db.prepare("SELECT 1 FROM bills WHERE nkey=? AND ts>=? LIMIT 1")
    .get(null, Date.now() - 60000);
  check('seenKey: nkey 为 NULL 不误命中', !nullHit);

  // insertRaw 的两条裁剪 SQL（含两个占位符）
  const MARK = '★';
  const ins = db.prepare("INSERT INTO raw_noti (pkg, title, body, ts) VALUES (?,?,?,?)");
  ins.run('com.tencent.mm', 'Weixin Pay', '已支付￥1.00', Date.now());
  ins.run(MARK, '心跳', '监听服务仍在运行', Date.now());
  const mark = [MARK, MARK];
  db.prepare("DELETE FROM raw_noti WHERE pkg<>? AND id NOT IN "
    + "(SELECT id FROM raw_noti WHERE pkg<>? ORDER BY ts DESC LIMIT 400)").run(...mark);
  db.prepare("DELETE FROM raw_noti WHERE pkg=? AND id NOT IN "
    + "(SELECT id FROM raw_noti WHERE pkg=? ORDER BY ts DESC LIMIT 1000)").run(...mark);
  const remain = db.prepare("SELECT COUNT(*) c FROM raw_noti").get().c;
  check('insertRaw 裁剪 SQL 可执行且未误删', remain === 2, `remain=${remain}`);

  // lastMarkTs
  const last = db.prepare("SELECT MAX(ts) FROM raw_noti WHERE pkg=?").get(MARK);
  check('lastMarkTs 能取到心跳时间', last && last['MAX(ts)'] > 0);
}

// ---------- 2. 从 v2 升级到 v3：onUpgrade ----------
{
  const db = new DatabaseSync(':memory:');
  // 模拟 v2 的 schema（没有 nkey）
  db.exec("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL,"
    + " channel TEXT, title TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE TABLE IF NOT EXISTS raw_noti (id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + " pkg TEXT, title TEXT, body TEXT, ts INTEGER NOT NULL)");
  db.prepare("INSERT INTO bills (amount, channel, title, ts) VALUES (?,?,?,?)")
    .run(-1.0, '微信', 'Weixin Pay', Date.now());

  // onUpgrade(oldV=2, newV=3)
  db.exec("ALTER TABLE bills ADD COLUMN nkey TEXT");

  const cols = db.prepare("PRAGMA table_info(bills)").all().map(r => r.name);
  check('v2→v3 迁移后含 nkey 列', cols.includes('nkey'), JSON.stringify(cols));
  const old = db.prepare("SELECT amount, nkey FROM bills").all();
  check('v2→v3 迁移保留旧数据', old.length === 1 && old[0].nkey === null);

  db.prepare("INSERT INTO bills (amount, channel, title, nkey, ts) VALUES (?,?,?,?,?)")
    .run(-3.0, '微信', 'Weixin Pay', 'k2', Date.now());
  check('v2→v3 迁移后能插入带 nkey 的新记录',
    db.prepare("SELECT COUNT(*) c FROM bills").get().c === 2);
}

// ---------- 3. 从 v3 升级到 v4：新增用途列 ----------
{
  const db = new DatabaseSync(':memory:');
  // 模拟 v3 的 schema（有 nkey，没有 category）
  db.exec("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL,"
    + " channel TEXT, title TEXT, nkey TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE TABLE IF NOT EXISTS raw_noti (id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + " pkg TEXT, title TEXT, body TEXT, ts INTEGER NOT NULL)");
  db.prepare("INSERT INTO bills (amount, channel, title, nkey, ts) VALUES (?,?,?,?,?)")
    .run(-1.0, '微信', 'Weixin Pay', 'oldkey', Date.now());

  // onUpgrade(oldV=3, newV=4)
  db.exec("ALTER TABLE bills ADD COLUMN category TEXT");

  const cols = db.prepare("PRAGMA table_info(bills)").all().map(r => r.name);
  check('v3→v4 迁移后含 category 列', cols.includes('category'), JSON.stringify(cols));
  const old = db.prepare("SELECT amount, nkey, category FROM bills").all();
  check('v3→v4 迁移保留旧数据且 category 为空',
    old.length === 1 && old[0].category === null, JSON.stringify(old));

  // 迁移后的旧行必须被当成「未分类」，否则那些老账会从饼图里消失
  const uncat = db.prepare("SELECT COUNT(*) c FROM bills WHERE category IS NULL OR category=''")
    .get().c;
  check('迁移后的旧行计入「未分类」', uncat === 1, `uncat=${uncat}`);

  // 定用途后就不再是未分类
  db.prepare("UPDATE bills SET category=? WHERE id=1").run('餐饮');
  check('定用途后不再计入未分类',
    db.prepare("SELECT COUNT(*) c FROM bills WHERE category IS NULL OR category=''")
      .get().c === 0);

  // 清除用途（写空串）要能退回未分类，与 Db.updateCategory(id, "") 对应
  db.prepare("UPDATE bills SET category=? WHERE id=1").run('');
  check('清空用途后退回未分类',
    db.prepare("SELECT COUNT(*) c FROM bills WHERE category IS NULL OR category=''")
      .get().c === 1);
}

// ---------- 4. 用途汇总：饼图的数据源 ----------
{
  const db = new DatabaseSync(':memory:');
  db.exec("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL,"
    + " channel TEXT, title TEXT, nkey TEXT, category TEXT, ts INTEGER NOT NULL)");
  const ins = db.prepare("INSERT INTO bills (amount, channel, title, category, ts) VALUES (?,?,?,?,?)");
  const now = Date.now();
  ins.run(-10.0, '微信', 'a', '餐饮', now);
  ins.run(-5.5, '微信', 'b', '餐饮', now);
  ins.run(-8.0, '支付宝', 'c', '交通', now);
  ins.run(-2.0, '微信', 'd', null, now);   // 未分类
  ins.run(20.0, '微信', 'e', '工资', now);  // 收入，不该进支出饼图

  // 支出汇总（与 Db.categoryTotals 的 SQL 一致）
  const rows = db.prepare(
    "SELECT CASE WHEN category IS NULL OR category='' THEN ? ELSE category END AS c, "
    + "SUM(-amount) s FROM bills WHERE ts>=? AND amount<0 "
    + "GROUP BY c HAVING SUM(-amount) > 0 ORDER BY SUM(-amount) DESC")
    .all('未分类', now - 86400000);
  const map = Object.fromEntries(rows.map(r => [r.c, r.s]));
  check('支出汇总：餐饮合并为 15.5', Math.abs(map['餐饮'] - 15.5) < 0.001, JSON.stringify(map));
  check('支出汇总：交通 8', Math.abs(map['交通'] - 8) < 0.001, JSON.stringify(map));
  check('支出汇总：未分类 2', Math.abs(map['未分类'] - 2) < 0.001, JSON.stringify(map));
  check('支出汇总：不含收入', map['工资'] === undefined, JSON.stringify(map));
  check('支出汇总：按金额从大到小', rows[0].c === '餐饮', JSON.stringify(rows.map(r => r.c)));

  // 收入汇总
  const inc = db.prepare(
    "SELECT CASE WHEN category IS NULL OR category='' THEN ? ELSE category END AS c, "
    + "SUM(amount) s FROM bills WHERE ts>=? AND amount>=0 "
    + "GROUP BY c HAVING SUM(amount) > 0 ORDER BY SUM(amount) DESC")
    .all('未分类', now - 86400000);
  check('收入汇总：只含工资 20',
    inc.length === 1 && Math.abs(inc[0].s - 20) < 0.001, JSON.stringify(inc));

  // 时间窗边界：一个月前的记录不该混进本月
  ins.run(-99.0, '微信', 'old', '餐饮', now - 40 * 86400000);
  const bounded = db.prepare(
    "SELECT SUM(-amount) s FROM bills WHERE ts>=? AND amount<0")
    .get(now - 30 * 86400000);
  check('月份窗口外的不计入', Math.abs(bounded.s - 25.5) < 0.001, `s=${bounded.s}`);

  // 未分类计数（带收支方向）。
  // 上面那条 20 元收入已归为「工资」，不是未分类；这里补一条真未分类的收入，
  // 否则「收入方向」这条查询实际上没被验证到（断言会假绿）。
  ins.run(3.0, '微信', 'f', null, now);
  const uncatExpense = db.prepare(
    "SELECT COUNT(*) c FROM bills WHERE ts>=? AND amount<0 "
    + "AND (category IS NULL OR category='')").get(now - 86400000).c;
  check('未分类计数（支出）为 1', uncatExpense === 1, `c=${uncatExpense}`);
  const uncatIncome = db.prepare(
    "SELECT COUNT(*) c FROM bills WHERE ts>=? AND amount>=0 "
    + "AND (category IS NULL OR category='')").get(now - 86400000).c;
  check('未分类计数（收入）为 1', uncatIncome === 1, `c=${uncatIncome}`);
}

// ---------- 5. 待确认转账表（v4→v5） ----------
{
  const db = new DatabaseSync(':memory:');
  db.exec("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL,"
    + " channel TEXT, title TEXT, nkey TEXT, category TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE TABLE IF NOT EXISTS raw_noti (id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + " pkg TEXT, title TEXT, body TEXT, ts INTEGER NOT NULL)");

  // 迁移前没有 pending_transfer 表
  const before = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='pending_transfer'").all();
  check('v4 时没有 pending_transfer 表', before.length === 0);

  // onUpgrade(4 -> 5)
  db.exec("CREATE TABLE IF NOT EXISTS pending_transfer ("
    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + "nkey TEXT, amount REAL, channel TEXT, title TEXT, ts INTEGER NOT NULL)");
  db.exec("CREATE INDEX IF NOT EXISTS idx_pending_ts ON pending_transfer(ts DESC)");

  check('v4→v5 后建出 pending_transfer',
    db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='pending_transfer'").all().length === 1);

  const ins = db.prepare("INSERT INTO pending_transfer (nkey, amount, channel, title, ts) VALUES (?,?,?,?,?)");
  ins.run('k1', 0, '微信', '老登', Date.now());
  check('待确认表能写入', db.prepare("SELECT COUNT(*) c FROM pending_transfer").get().c === 1);

  // 同一通知重复投递不能变两条提案
  const seen = db.prepare("SELECT 1 FROM pending_transfer WHERE nkey=? LIMIT 1").get('k1');
  check('pendingSeen 能识别重复通知', !!seen);
  check('pendingSeen 对未知标识返回空',
    !db.prepare("SELECT 1 FROM pending_transfer WHERE nkey=? LIMIT 1").get('k9'));

  // 关键不变式：待确认转账绝不能出现在 bills 里（否则就是替用户编了一笔账）
  check('待确认转账不污染 bills 表',
    db.prepare("SELECT COUNT(*) c FROM bills").get().c === 0);

  // 用户确认后：写 bills + 删提案，两步都得到位
  db.prepare("INSERT INTO bills (amount, channel, title, category, ts) VALUES (?,?,?,?,?)")
    .run(-100.0, '微信', '老登', null, Date.now());
  db.prepare("DELETE FROM pending_transfer WHERE id=?").run(1);
  check('确认后 bills 多一笔', db.prepare("SELECT COUNT(*) c FROM bills").get().c === 1);
  check('确认后提案被删干净', db.prepare("SELECT COUNT(*) c FROM pending_transfer").get().c === 0);

  // 待确认的算「未分类」需要在 bills 里；但提案本身不计入任何账目合计
  const inc = db.prepare("SELECT SUM(amount) s FROM bills WHERE amount>=0").get().s;
  const exp = db.prepare("SELECT SUM(-amount) s FROM bills WHERE amount<0").get().s;
  check('提案不影响收支合计（只有确认后才计入）',
    (inc === null || inc === 0) && Math.abs(exp - 100.0) < 0.001, `inc=${inc} exp=${exp}`);
}

console.log(`\n通过 ${pass} / ${pass + fail}`);
process.exit(fail ? 1 : 0);
