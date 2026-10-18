package main

// name_moderation.go — 房主名的审核结果，逐语句对位模组 RoomManager：
//   信令分发：RoomManager.java:1966-1987（room_name_approved / rejected / unavailable）
//   心跳分发：RoomManager.java:1594-1607（心跳响应带 nameModerationStatus 时走同一条路）
//   处理本体：RoomManager.java:1142-1186 handleNameModerationUpdate
//
// 两处按裁定允许的等价替换（不是省略）：
//   - 模组写 MC 聊天栏 sendSystemMessage；本端发稳定键 room:notice + 参数，渲染交给界面/启动器。
//     模组 approved 时"名字另起一行"的裸文本，这里就是 name_approved 那条的唯一参数。
//   - 模组的 currentRoom!=null / mc.player!=null 判空，本端用"是否在房里"（a.room）代替。

// 模组侧那几个聊天键名，本端一字不改地复用（13 语言文件里已有）
const (
	chatNameApproved       = "voxlink.chat.name_approved"           // :1163
	chatNameRejectedHint   = "voxlink.chat.name_rejected_with_hint" // :1172
	chatReasonLabel        = "voxlink.chat.reason_label"            // :1173
	chatNameUnavailable    = "voxlink.chat.name_unavailable"        // :1176
	chatPleaseRetry        = "voxlink.chat.please_retry"            // :1177
	chatUnknownReason      = "voxlink.chat.unknown_reason"          // :1171
	namePendingReviewValue = "name_pending_review"                  // :1154 的占位名
)

// handleRoomNameSignal 房间级信令入口（模组同一分发点）。返回 true 表示这条已被消费。
func (a *App) handleRoomNameSignal(typ string, data map[string]any) bool {
	var status string
	var approved bool
	switch typ {
	case "room_name_approved":
		status, approved = "approved", true // :1970
	case "room_name_rejected":
		status = "rejected" // :1978
	case "room_name_unavailable":
		status = "unavailable" // :1985
	default:
		return false
	}
	reason := ""
	if typ == "room_name_rejected" {
		reason = roomStr(data, "reason") // :1977 只有驳回型带原因
	}
	// :1969/1976/1984 —— data.has("name") 才有值，缺省即 null，两种情况行为不同
	return a.handleNameModerationUpdate(status, reason, roomOptStr(data, "name"), approved)
}

// handleNameModerationUpdate 对位 :1142-1186。newName 用指针保住"没带 name"与"带了空名"的区别。
func (a *App) handleNameModerationUpdate(status, reason string, newName *string, approved bool) bool {
	if status == "" {
		return true // :1143 空状态整条丢弃
	}
	var out []map[string]any
	mutated := false
	a.mu.Lock()
	if a.room != nil { // :1968 不在房里不处理
		statusChanged := status != a.lastModerationStatus
		sameName := newName != nil && *newName == a.lastModeratedName
		// :1144 外门：状态变了、或没带名字、或名字变了；:1145 内门：状态变了、或这次就是通过。
		// 两条同时成立才播报——所以"同一驳回原因重复推"只播一次，而"通过但改了名"每次都播。
		if (statusChanged || newName == nil || !sameName) && (statusChanged || approved) {
			a.lastModerationStatus = status
			if newName != nil {
				a.lastModeratedName = *newName
			}
			a.room["nameApproved"] = approved // :1153 setNameApproved
			if approved && newName != nil && *newName != "" && *newName != namePendingReviewValue {
				a.room["name"] = *newName // :1154-1155 setName
			}
			// 模组的 roomInfo 是界面每帧读的对象，改完立刻可见；本端要自己广播。
			// pending 这类"没有聊天栏输出但改了标志"的状态也得跟着播一次。
			mutated = true
			out = moderationNotices(status, reason, newName)
		}
	}
	state := a.state
	var room map[string]any
	if a.room != nil {
		room = copyMap(a.room)
	}
	a.mu.Unlock()

	// 播报必须离开锁：netLog/emit 会回头抢同一把 a.mu（本会话已因此自锁两次）。
	for _, n := range out {
		a.netLog(moderationLevel(n), "名字审核结果: "+roomStr(n, "key"))
		a.emit("room:notice", n)
	}
	if mutated && room != nil {
		a.emit("session:state", map[string]any{"state": state, "room": room})
	}
	return true
}

// moderationNotices 把 :1161-1178 的聊天栏输出翻成稳定键序列。
func moderationNotices(status, reason string, newName *string) []map[string]any {
	switch status {
	case "approved":
		name := ""
		if newName != nil {
			name = *newName
		}
		// :1163-1166 一行键文 + 一行名字；名字为空则只发一行
		return []map[string]any{notice(status, chatNameApproved, argList(name))}
	case "rejected":
		// :1169-1173 原因缺失也要给一条"未知原因"，绝不静默
		text := reason
		if text == "" {
			text = chatUnknownReason
		}
		return []map[string]any{
			notice(status, chatNameRejectedHint, nil),
			notice(status, chatReasonLabel, argList(text)),
		}
	case "unavailable":
		return []map[string]any{
			notice(status, chatNameUnavailable, nil),
			notice(status, chatPleaseRetry, nil),
		}
	}
	return nil
}

func moderationLevel(n map[string]any) string {
	if roomStr(n, "key") == chatNameApproved {
		return "info"
	}
	return "warn"
}

func notice(status, key string, args []string) map[string]any {
	m := map[string]any{"key": key}
	if status != "" {
		m["status"] = status
	}
	if len(args) > 0 {
		m["args"] = args
	}
	return m
}

func argList(s ...string) []string {
	out := make([]string, 0, len(s))
	for _, v := range s {
		if v != "" {
			out = append(out, v)
		}
	}
	return out
}

// roomOptStr 区分"键不存在"（返回 nil，对应 Gson 的 null）与"键存在但为空串"。
func roomOptStr(m map[string]any, key string) *string {
	v, ok := m[key].(string)
	if !ok {
		return nil
	}
	return &v
}

// heartbeatDisplayName 心跳响应里的房名只用于同步"别人房改名"：
// 模组的 roomInfo.name 只由审核流程改写，且 :1154 明确排除占位名，
// 所以审核中的 name_pending_review 不得覆盖本地已有好名。
func heartbeatDisplayName(name string) string {
	if name == namePendingReviewValue {
		return ""
	}
	return name
}
