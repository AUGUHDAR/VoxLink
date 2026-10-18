# -*- coding: utf-8 -*-
"""工单系统记忆写回：ZCode 新篇+索引 + TRAE 项目记忆"""
import io, os

ZDIR = r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory"
NEW_MEM = os.path.join(ZDIR, "voxlink-ticket-system-20260923.md")
INDEX = os.path.join(ZDIR, "MEMORY.md")
TRAE = r"C:\Users\19430\.trae-cn\memory\projects\-d----voxlink--p2-a7e51d6763a02d303c09\project_memory.md"

BODY = """---
name: voxlink-ticket-system-20260923
description: 2026-09-23 工单系统全链路落地——反馈升级为双向工单（本地存单号身份/启动poll/标题屏未读弹窗/追问带附件10分钟限频/软删+标签/admin聊天式管理），服务端已部署生产+28模板客户端+admin UI全过；1.1.8 内容三件套齐备待发布
metadata:
  node_type: memory
  type: project
  originSessionId: sess_trae_20260923
---

## 工单系统（2026-09-23 全链路落地，用户全部裁决已执行）

**用户裁决集**：本地存单号（无账号体系身份锚点，换机丢历史）；列表→详情两级跳转；
追问限频=10分钟（与新建同窗口共享 IP 限频）；附件单向（玩家→服务端可带，管理员回复纯文字）；
反馈表单升级为工单提交（同一入口）；新旧分开（旧 feedback.go 一字不动）；网页端截图折叠、
客户端卡片形式；"不是微信"——异步问答，管理员不实时盯守。

## 服务端 ticket.go（已部署生产 162.251.94.31，bak-0923，健康验证过）
- 存储 <DataDir>/tickets/<id>/ticket.json + 附件 .gz（复用 feedback 的 gzip 机制）
- 玩家端点：/ticket/submit|reply|poll|detail|viewed|delete（软删打"玩家端已删除"标签）
- admin：/admin/ticket/list|detail|reply|delete(彻底)|attachment + pages.go 表单 action
  （reply_ticket/delete_ticket/download_ticket_att）
- 限频复用 feedbackRL（每 IP 10 分钟）；reply 附件先落 .tmp 临时目录锁内校验后转正（防路径穿越）
- 单工单 200 条消息上限、poll 单次 ≤50 单、保留 90 天
- TicketAdminListTpl() 供 admin 模板（含 Unread/HasReply/Deleted/时间线行）

## 客户端（fabric/26.3 金标准 → 28 模板）
- **新文件**：network/TicketClient.java（HTTP+本地存 gameDir/voxlink_tickets.json，
  零 MC API 时代分叉——gameDirectory 字段通用）；ui/TicketListScreen.java（分页列表+标签）；
  ui/TicketDetailScreen.java（时间线+追问+附件选择器+软删，打开即 markViewed）
- **修改**：TitleScreenMixin（pollOnceIfNeeded 首次 init 触发+poll 回调重刷标题屏弹窗；左上角
  (8,8,100x18) 未读弹窗按钮）——2 簇（22 setScreen 老 / 6 setScreenAndShow 26.x）方法体替换；
  FeedbackScreen（+我的工单按钮）；FeedbackFormScreen（FeedbackUploader.submit→TicketClient.submit，
  成功显示"工单已创建：#id"）
- **时代分叉教训（编译矩阵抓了 2 轮）**：新 UI 文件三种形态——26.x(Extractor+extractRenderState
  +gui.setScreen)/26.1 混合(Extractor+extractRenderState+setScreen)/老19模板(GuiGraphics+render
  +setScreen)。**新 UI 屏只调 VoxLinkScreenBase 基类方法（drawTitle/drawString 等）不直接碰
  graphics API**，则分叉收敛为 5 个替换点（import/方法签名/super 调用/setScreen×2）
- lang：voxlink.ticket.* 27 key × 13 语 × 28 模板 = 364/364（锚 join_no_response 行）
- 编译矩阵：26.x 4 + 26.1 3 + 老时代 5 = 12 目标全 BUILD SUCCESSFUL

## admin 网页端（已部署）
- admin.html feedback 页追加工单卡片网格：未读红标签/已回复·已查看/玩家已删除/待回复 + 创建&最后
  活动时间 + 描述展开 + 附件下载（download_ticket_att）+ 消息时间线（admin/player 分色气泡）+
  回复表单（纯文字 textarea）+ 彻底删除（confirm）
- style.css v11 追加 tk-* 样式（未读卡片高亮/气泡/表单）
- web/lang 13 文件 tk_admin.* 14 key

## 脚本
propagate_ticket_lang_20260923.py / propagate_ticket_client_20260923.py /
verify_ticket_client_20260923.py / fix_ticket_era_20260923.py / fix_ticket_setscreen_20260923.py /
web_lang_ticket_20260923.py / deploy_servergo_20260923.sh（部署模板，上传+cp bak+kill+nohup 拉起）

## 环境坑（本轮）
- mcp.json 模型禁碰（TRAE 安全设计），AnySearch 由用户手填；PowerShell 引号吃 ssh 内嵌命令
  → 部署脚本落 .sh 文件 scp 过去执行
- 服务器 API 端口 1145（不是 8080）；路由走 /?route=xxx query 参数（不是 path）；
  admin IP 白名单不含 127.0.0.1（本机 curl admin 必 403，属预期）
- 老 server(PHP) 目录已改名 server-deprecated 防踩坑；TURN(APK/GO/PHP) 与 server-go 都有用

## 1.1.8 待发布内容（全部就绪，等用户裁决构建发布）
joinfix + ModSync门控可见化 + TRN 双 bug 修复 + 工单系统；App/APK 引擎 TURN 同步在 mod 验证后
"""

INDEX_LINE = "- [2026-09-23 工单系统全链路落地](voxlink-ticket-system-20260923.md) — 反馈→双向工单(本地存单号/启动poll/标题屏未读弹窗/追问带附件共用10分钟限频/软删标签/admin时间线管理)；服务端已部署生产+28模板客户端+admin UI；新UI屏时代分叉=5替换点(Extractor/render/setScreen)；1.1.8四件套齐备待发布\n"

TRAE_ADD = """- 工单系统（1.1.8，已部署服务端+28模板客户端）：本地存单号身份（gameDir/voxlink_tickets.json）、poll 启动一次、TitleScreenMixin 左上角未读弹窗（poll 回调里 setScreen 重刷标题屏）、追问带附件共用 10 分钟 IP 限频、软删打标签；新 UI 屏三时代形态（26.x Extractor+gui.setScreen / 26.1 Extractor+setScreen / 老 GuiGraphics+render），只调 VoxLinkScreenBase 基类方法则分叉收敛为 import/签名/super/setScreen 四类替换点
- 服务器 API 端口 1145；路由走 /?route=xxx（query 非path）；admin 白名单不含 127.0.0.1；部署=脚本落 .sh + scp + bash（PowerShell 引号会吃 ssh 内嵌命令）；老 server(PHP) 已改名 server-deprecated
"""

with io.open(NEW_MEM, "w", encoding="utf-8", newline="\n") as f:
    f.write(BODY)
print("ZCode memory written")

with io.open(INDEX, "r", encoding="utf-8") as f:
    idx = f.read()
if "voxlink-ticket-system-20260923" not in idx:
    with io.open(INDEX, "w", encoding="utf-8", newline="") as f:
        f.write(INDEX_LINE + idx)
    print("ZCode index prepended")
else:
    print("ZCode index already has entry")

with io.open(TRAE, "r", encoding="utf-8") as f:
    tm = f.read()
if "工单系统（1.1.8" not in tm:
    with io.open(TRAE, "a", encoding="utf-8", newline="") as f:
        if not tm.endswith("\n"):
            f.write("\n")
        f.write(TRAE_ADD)
    print("TRAE project memory appended")
else:
    print("TRAE already has entry")
print("DONE")
