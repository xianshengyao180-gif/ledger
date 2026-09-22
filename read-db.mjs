// 直接读取从手机拉回来的 ledger.db，看通知到底有没有入库。
import { DatabaseSync } from 'node:sqlite';

const file = process.argv[2];
const db = new DatabaseSync(file);

console.log('=== 表结构 ===');
for (const t of db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all()) {
  console.log('  ' + t.name);
}

console.log('\n=== PRAGMA user_version ===');
console.log('  ' + db.prepare('PRAGMA user_version').get().user_version);

console.log('\n=== bills 表（记账记录）===');
const cols = db.prepare('PRAGMA table_info(bills)').all().map(c => c.name);
console.log('  列: ' + cols.join(', '));
const bills = db.prepare('SELECT * FROM bills ORDER BY ts DESC LIMIT 50').all();
if (bills.length === 0) {
  console.log('  （空）');
} else {
  for (const b of bills) {
    console.log(`  id=${b.id} amount=${b.amount} channel=${b.channel} title=${b.title} nkey=${b.nkey} ts=${b.ts}`);
  }
}
console.log(`  合计 ${bills.length} 条`);

console.log('\n=== raw_noti 表（原始通知）===');
const raws = db.prepare('SELECT * FROM raw_noti ORDER BY ts DESC LIMIT 40').all();
if (raws.length === 0) {
  console.log('  （空）');
} else {
  for (const r of raws) {
    const d = new Date(r.ts);
    const hh = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
    console.log(`  [${hh}] pkg=${r.pkg} | 标题=${r.title} | 正文=${r.body}`);
  }
}
console.log(`  合计 ${raws.length} 条`);
