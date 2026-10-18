// 清空 成品目录（续）—— 带重试，逐顶层条目处理，单个失败不中断后续
const fs = require('fs');
const path = require('path');

const OUT = 'D:/桌面/voxlink/成品';

function rmRetry(p, tries = 6) {
  for (let i = 1; i <= tries; i++) {
    try { fs.rmSync(p, { recursive: true, force: true, maxRetries: 3, retryDelay: 400 }); return null; }
    catch (e) {
      if (i === tries) return e.code || e.message;
      // 清只读属性后重试（Windows 常见 EPERM 来源）
      try {
        const st = fs.statSync(p);
        if (!st.isDirectory()) fs.chmodSync(p, 0o666);
      } catch { /* ignore */ }
      const until = Date.now() + 600;
      while (Date.now() < until) { /* 同步等待，释放文件锁 */ }
    }
  }
}

let done = 0; const failed = [];
for (const e of fs.readdirSync(OUT, { withFileTypes: true })) {
  const p = path.join(OUT, e.name);
  const err = rmRetry(p);
  if (err) { failed.push(`成品/${e.name}: ${err}`); console.log(`  [失败] 成品/${e.name} -> ${err}`); }
  else { done++; console.log(`  [已删] 成品/${e.name}${e.isDirectory() ? '/' : ''}`); }
}
console.log(`\n本轮删除 ${done} 个，失败 ${failed.length} 个`);
console.log('成品目录剩余:', JSON.stringify(fs.readdirSync(OUT)));
