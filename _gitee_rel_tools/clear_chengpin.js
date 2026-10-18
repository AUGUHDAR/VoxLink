// 清空 成品目录（已获用户明确授权："全部清空（含所有备份）"）
// 前置安全措施：
//   - 3 个全盘唯一文件已复制到 _old_builds_archive/20260920/ 并校验一致
//   - 1.1.5 / 1.1.6 / 1.1.7_0918 jar 均可在 GitHub + Gitee Releases 完整取回
//   - Server 两个二进制与 server-go/ 下逐字节相同
// 本脚本先把清单写入 deleted_manifest.txt 留档，再删除。
const fs = require('fs');
const path = require('path');

const BASE = 'D:/桌面/voxlink';
const OUT = path.join(BASE, '成品');
const REPORT = path.join(BASE, '_old_builds_archive', '20260920', 'deleted_manifest.txt');

function listAll(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) listAll(p, acc);
    else acc.push(p);
  }
  return acc;
}

const all = listAll(OUT);
const lines = all.map(p => {
  const st = fs.statSync(p);
  return `${(st.size / 1048576).toFixed(3)}MB\t${path.relative(BASE, p).replace(/\\/g, '/')}`;
});
fs.mkdirSync(path.dirname(REPORT), { recursive: true });
fs.writeFileSync(REPORT, `清空时间: ${new Date().toISOString()}\n共 ${all.length} 个文件\n\n` + lines.join('\n') + '\n', 'utf8');

console.log(`清单已留档: ${REPORT}`);
console.log(`共 ${all.length} 个文件, ${(all.reduce((s, p) => s + fs.statSync(p).size, 0) / 1048576).toFixed(2)}MB`);

// 只删 成品/ 下的五个子目录，保留 成品/ 本身
let del = 0;
for (const e of fs.readdirSync(OUT, { withFileTypes: true })) {
  const p = path.join(OUT, e.name);
  fs.rmSync(p, { recursive: true, force: true });
  del++;
  console.log(`  [已删] 成品/${e.name}${e.isDirectory() ? '/' : ''}`);
}
const left = fs.readdirSync(OUT);
console.log(`\n成品目录已清空: 删除 ${del} 个顶层条目，剩余 ${left.length} 个`);
