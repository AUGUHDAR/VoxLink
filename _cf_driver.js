// CF 批量上传驱动 (node_repl): 每次调用从 _cf_state.json 读取未完成列表, 按 _cf_done.json 跳过,
// 每轮处理至多 3 个 jar。分块注入 + 页面 fetch + 轮询。 IAB 必须已过盾。
const browserPluginRoot =
  process.env.ZCODE_PLUGIN_ROOT ?? process.env.CLAUDE_PLUGIN_ROOT;
const { join } = await import("node:path");
const { pathToFileURL } = await import("node:url");
const { setupBrowserRuntime } = await import(pathToFileURL(join(browserPluginRoot, "scripts", "browser-client.mjs")).href);
await setupBrowserRuntime({ globals: globalThis });
const browser = await agent.browsers.get("iab");
const { readFile, writeFile } = await import("node:fs/promises");

const state = JSON.parse(await readFile("D:/桌面/voxlink/tools/publish/_cf_state.json", "utf-8"));
let done = [];
try { done = JSON.parse(await readFile("D:/桌面/voxlink/tools/publish/_cf_done.json", "utf-8")); } catch (e) {}

const BATCH = 3;
const pending = state.tasks.filter(t => !done.includes(t.name)).slice(0, BATCH);
if (pending.length === 0) {
  console.log("ALL_DONE");
} else {
  const tab = await browser.tabs.get("iab-tab:4189810a-bdbd-4b19-9fe2-772feb938ac8");

  // 页面健康检查
  const health = await tab.playwright.evaluate(
    () => ({ href: location.href, ready: document.readyState, title: document.title }),
    undefined, { timeoutMs: 3000 }
  );
  if (!health.href.includes("authors-old.curseforge.com") || health.ready !== "complete") {
    console.log("PAGE_NOT_READY", JSON.stringify(health));
  } else {
    for (const task of pending) {
      const b64 = (await readFile(task.path)).toString("base64");
      const CHUNK = 600000;
      await tab.playwright.evaluate(
        () => { window.__parts = []; window.__cfUp = { done: false }; return "reset"; },
        undefined, { timeoutMs: 3000 }
      );
      for (let i = 0; i < b64.length; i += CHUNK) {
        const piece = b64.slice(i, i + CHUNK);
        await tab.playwright.evaluate(
          (arg) => { window.__parts.push(arg.piece); return window.__parts.length; },
          { piece }, { timeoutMs: 3000 }
        );
      }
      const meta = {
        displayName: task.name, releaseType: "beta",
        gameVersionNames: task.versions, changelog: state.changelog, changelogType: "markdown",
      };
      await tab.playwright.evaluate(
        (arg) => {
          const full = window.__parts.join("");
          window.__parts = [];
          window.__cfUp = { done: false };
          (async () => {
            try {
              const bytes = Uint8Array.from(atob(full), (c) => c.charCodeAt(0));
              const fd = new FormData();
              fd.append("metadata", JSON.stringify(arg.meta));
              fd.append("file", new Blob([bytes], { type: "application/java-archive" }), arg.name);
              const r = await fetch(new URL("/api/projects/" + arg.pid + "/upload-file", location.origin), {
                method: "POST", headers: { "X-Api-Token": arg.token }, body: fd,
              });
              const text = await r.text();
              window.__cfUp = { done: true, status: r.status, body: text.slice(0, 120) };
            } catch (e) { window.__cfUp = { done: true, error: String(e) }; }
          })();
          return "kicked";
        },
        { meta, name: task.name, pid: state.pid, token: state.token },
        { timeoutMs: 3000 }
      );
      // 轮询结果
      let result = { error: "timeout 80s" };
      for (let i = 0; i < 40; i++) {
        await tab.playwright.waitForTimeout(2000);
        const st = await tab.playwright.evaluate(
          () => (window.__cfUp && window.__cfUp.done ? JSON.stringify(window.__cfUp) : "pending"),
          undefined, { timeoutMs: 3000 }
        );
        if (st !== "pending") { result = JSON.parse(st); break; }
      }
      const ok = result.status === 200;
      console.log((ok ? "OK " : "FAIL ") + task.name + " -> " + (result.body || result.error || "").slice(0, 60));
      done.push(task.name);
      await writeFile("D:/桌面/voxlink/tools/publish/_cf_done.json", JSON.stringify(done));
      if (!ok) break;
    }
    console.log("BATCH_END done=" + done.length + "/25");
  }
}
