// 用户实测启动（Forge / Fabric / NeoForge）—— 不设超时、不 taskkill
// 用法: node launch_test_user.js <版本目录名> <被测jar绝对路径> <输出文件>
// 与 launch_test_multi.js 相同的 mods/classpath 逻辑，但 detached 启动，node 立即退出，MC 留给用户测试。
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const VER_NAME = process.argv[2];
const JAR = process.argv[3];
const OUT = process.argv[4];
if (!VER_NAME || !JAR || !OUT) {
  console.error('用法: node launch_test_user.js <版本目录名> <被测jar> <输出文件>');
  process.exit(1);
}

const ROOT = 'D:\\桌面\\voxlink\\客户端测试';
const VER_DIR = path.join(ROOT, 'versions', VER_NAME);
if (!fs.existsSync(VER_DIR)) { console.error('版本目录不存在:', VER_DIR); process.exit(1); }

const LIBS = path.join(ROOT, 'libraries');
const ASSETS = path.join(ROOT, 'assets');
const NATIVES = path.join(VER_DIR, VER_NAME + '-natives');
const MODS = path.join(VER_DIR, 'mods');
const _JDK = { 17: 'jdk-17', 21: 'jdk-21', 25: 'jdk-25.0.2', 8: 'jre1.8.0_501' };

// --- 1. 只留被测 jar ---
fs.mkdirSync(MODS, { recursive: true });
for (const f of fs.readdirSync(MODS)) {
  if (f.toLowerCase().includes('voxlink')) fs.unlinkSync(path.join(MODS, f));
}
const jarName = 'voxlink-test-target.jar';
fs.copyFileSync(JAR, path.join(MODS, jarName));
console.log(`[${VER_NAME}] 被测 jar -> mods/${jarName} (${fs.statSync(JAR).size} bytes)`);

// --- 2. 复现 classpath ---
const json = JSON.parse(fs.readFileSync(path.join(VER_DIR, VER_NAME + '.json'), 'utf8'));
const javaMajor = (json.javaVersion && json.javaVersion.majorVersion) || 21;
const JAVA = `C:\\Program Files\\Java\\${_JDK[javaMajor] || 'jdk-21'}\\bin\\java.exe`;
console.log(`[${VER_NAME}] javaVersion=${javaMajor} -> ${JAVA}`);
const cp = [];
for (const lib of (json.libraries || [])) {
  const a = lib.downloads && lib.downloads.artifact;
  let p = null;
  if (a && a.path) p = path.join(LIBS, a.path);
  else if (lib.name) {
    const [g, n, v] = lib.name.split(':');
    const [nv, cls] = (n + '').split('@');
    p = path.join(LIBS, g.replace(/\./g, '/'), nv, v, `${nv}-${v}${cls ? '.' + cls : '.jar'}`);
  }
  if (p && fs.existsSync(p)) cp.push(p);
}
const mainJar = path.join(VER_DIR, VER_NAME + '.jar');
if (fs.existsSync(mainJar)) cp.push(mainJar);

const vars = {
  auth_player_name: 'AUGUHDAR', version_name: VER_NAME, game_directory: VER_DIR,
  assets_root: ASSETS, assets_index_name: '34',
  auth_uuid: 'f45725fc6a55449f9eaf8625a4a3b7ae', auth_access_token: '0', clientid: '0', auth_xuid: '0',
  user_type: 'msa', version_type: 'PCL',
  natives_directory: NATIVES, launcher_name: 'repro', launcher_version: '1',
  classpath: cp.join(';'), classpath_separator: ';', library_directory: LIBS,
  resolution_width: '1280', resolution_height: '720',
};
const subst = s => String(s).replace(/\$\{([^}]+)\}/g, (m, k) => (k in vars ? vars[k] : m));

const jvmArgs = [];
for (const a of (json.arguments && json.arguments.jvm) || []) {
  if (typeof a === 'object') continue;
  const v = subst(a);
  if (v.includes('${')) continue;
  jvmArgs.push(v);
}
const gameArgs = [];
for (const a of (json.arguments && json.arguments.game) || []) {
  if (typeof a === 'object') continue;
  gameArgs.push(subst(a));
}

// --- 3. detached 启动：node 退出，MC 留给用户 ---
const out = fs.openSync(OUT, 'w');
console.log(`[${VER_NAME}] mainClass=${json.mainClass} classpath=${cp.length} 条，detached 启动（无超时，交给用户测试）`);
const ch = spawn(JAVA, [...jvmArgs, json.mainClass, ...gameArgs], {
  cwd: VER_DIR, detached: true, stdio: ['ignore', out, out]
});
ch.on('error', e => console.error(`[${VER_NAME}] SPAWN ERROR:`, e));
ch.unref();
console.log(`[${VER_NAME}] MC 已启动 pid=${ch.pid}，日志: ${OUT}`);
