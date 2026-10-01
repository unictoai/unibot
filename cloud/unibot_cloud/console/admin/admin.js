/* unibot Cloud — the operator's page. Static, no build step: the admin
   token (X-Admin-Token) is asked for once and kept in sessionStorage, so it
   is gone when the tab closes. Everything comes from /v1/admin/*.

   What it shows is what the relay keeps: counts, tokens, estimated cost,
   sign-ins (device names), devices, and the account timeline. Nobody's
   messages — those were never stored. Identifiers are decrypted one
   account at a time, in the drawer; the tables show the masked hint. */
(() => {
  "use strict";

  const zh = (navigator.language || "").toLowerCase().startsWith("zh");
  const fmt = (n) => Number(n || 0).toLocaleString();
  const T = zh ? {
    title: "unibot Cloud 后台", tokenLabel: "管理口令", tokenHint: "服务器上 /opt/unibot/relay/ADMIN_TOKEN.txt 里的那一行；只留在这个标签页里。",
    enter: "进入", wrong: "口令不对。", offline: "连不上服务器。", refresh: "刷新", lock: "锁定", loading: "加载中…",
    today: "今天", week: "最近 7 天", period: (d) => `最近 ${d} 天`,
    kSamples: "贡献的对话", kSamplesSub: (n) => `${n} 个账号开启了贡献`, exportSamples: "导出 JSONL", exportFailed: (why) => `导出没有成功：${why}。可以再试一次；如果一直这样，看服务器上 docker logs unibot-relay。`, exportCut: "下载中途断开", exportEmpty: "还没有可导出的对话。", contributes: "贡献对话", samples: "贡献的对话（最近）", samplesNote: "只有把「贡献对话」打开的账号才会保存这些内容；导出的文件不带账号 id。", user: "用户", assistant: "回答", more: "查看更多", noSamples: "还没有",
    kAccounts: "账号", kAccountsSub: (c) => `${c.with_password || 0} 个设了密码 · ${c.unlimited || 0} 个成员 · ${c.disabled || 0} 个已停用`,
    kActive: "活跃账号", kActiveSub: (n) => `${n} 个新注册`, kSpent: "花费", kSpentSub: (r, t) => `${fmt(r)} 次 · ${fmt(t)} tokens`,
    kOnline: "在线设备", kOnlineSub: (k, s) => `记住了 ${k} 台 · ${s} 个有效登录`, kSignals: "今天的信号",
    kSignalsSub: (s) => `${s.sign_ins} 次登录 · ${s.sign_in_failures} 次失败 · ${s.budget_refusals} 次超额 · ${s.upstream_errors} 次上游错误 · ${s.calls} 通电话`,
    byKind: "按类型", byModel: "按模型", byDay: (d) => `每日花费 · 最近 ${d} 天`, top: (d) => `花费最多 · 最近 ${d} 天`, events: "最近动态", accounts: "全部账号", config: "当前配置",
    kinds: { chat: "对话", image: "图片", video: "视频", realtime: "实时通话" },
    thWho: "账号", thJoined: "注册", thSpent: "花费 累计 / 今天", thTokens: "tokens 累计 / 今天", thReqs: "请求", thActive: "最近活跃", thDevices: "设备",
    never: "从未", phone: "手机", email: "邮箱", disabled: "已停用", locked: "已锁定", member: "成员", listed: "白名单", password: "密码", noAccounts: "还没有人登录过。", search: "搜索提示 / ID…",
    reqs: (n) => `${fmt(n)} 次`, tokens: (n) => `${fmt(n)} tokens`, seconds: (n) => `${fmt(n)} 秒`, pictures: (n) => `${fmt(n)} 张`, inOut: (i, o) => `输入 ${fmt(i)} · 输出 ${fmt(o)}`,
    all: "全部", signIns: "登录", refusals: "超额", errors: "错误", calls: "通话", passwords: "密码",
    eventName: {
      "account.created": "注册", "sign_in.code": "验证码登录", "sign_in.password": "密码登录", "sign_in.failed": "登录失败", "password.set": "设置密码", "password.changed": "修改密码",
      "password.cleared": "移除密码", "sign_out": "退出", "sign_out.all": "全部退出", "budget.refused": "超出额度被拒", "upstream.error": "上游出错", "call.ended": "通话结束", "contribute.on": "加入共创计划", "contribute.bonus": "共创奖励 +¥10", "contribute.off": "关闭贡献对话", "contribute.deleted": "删除贡献的对话", "invite.accepted": "邀请成功", "invite.used": "通过邀请注册", "invite.unknown": "无效邀请码", "credit.granted": "获得额度奖励",
    },
    // drawer
    spendToday: "今天", spendTotal: "累计", requests: "请求", cap: "总额度", noCap: "无上限", left: "剩余", usageToday: "今天", usagePeriod: (d) => `最近 ${d} 天`, usageTotal: "累计",
    sessions: "登录（含已退出）", revoked: "已退出", via: { code: "验证码", password: "密码" }, lastUsed: "最近使用", devices: "设备", firstSeen: "首次", lastSeen: "最近",
    ledger: "最近请求", timeline: "时间线", none: "—", online: "在线", offline: "离线", version: "版本",
    grant: "加额度", grantPrompt: (who) => `给 ${who} 加多少 tokens？负数扣减。`, credit: "加额度", creditPrompt: (who) => `给 ${who} 加多少元额度？（直接进入总额度，不过期）`, creditNote: "备注（比如 PR #12）", poolLine: (g, l, n, b) => `总额度 ${g}${l === null ? "" : `（剩 ${l}）`} · 邀请了 ${n} 人${b ? " · 已领共创奖励" : ""}`, invitedBy: "邀请人", disable: "停用", enable: "恢复", makeMember: "设为成员", unmakeMember: "取消成员",
    memberConfirm: (who) => `把 ${who} 设为成员？成员不受额度限制，费用由你承担。`, listedNote: "在服务器白名单里，改 ALLOWED_IDENTIFIERS 才能取消",
    disableConfirm: (who) => `停用 ${who}？TA 的所有设备会立刻断开，再登录会被拒。`, remove: "删除账号",
    removeConfirm: (who) => `删除 ${who} 的账号、密钥、用量记录和设备？不可恢复。`, hasPassword: "已设密码", noPassword: "未设密码", identifierNote: "明文只在这里解出来看",
    // config
    allowed: "白名单（不限额）", allowedNone: "（空）", sender: "验证码渠道", models: "模型", prices: "单价（¥）", rate: "汇率", rateLine: (r) => `1 美元 = ${r} 元（仅用于显示）`,
    perMinute: (n) => (n > 0 ? `每分钟 ${n} 次` : "不限频"), capLine: (c, u, b, k) => (c > 0 ? `非成员共 ¥${c}（≈ $${u}）· 邀请 +¥${b} · 共创 +¥${k}` : "不限花费"), signupOpen: "开放注册", signupClosed: "仅白名单可登录",
    realtime: "实时通话", on: "开", off: "关", pwMin: (n) => `密码至少 ${n} 位`,
    priceLine: (p) => [p.per_m_input || p.per_m_output ? `输入 ${p.per_m_input} / 输出 ${p.per_m_output} 每百万 tokens` : null,
      p.per_image ? `每张 ${p.per_image}${p.per_image_2k ? `（2k ${p.per_image_2k}）` : ""}` : null, p.per_second ? `每秒 ${p.per_second}` : null].filter(Boolean).join("；"),
    foot: "手机号 / 邮箱只在打开某个账号时用管理口令解出来看；数据库里存的是加密后的值。谁和模型说了什么，这里没有，从来没存过。请不要把这个页面截图发出去。金额按模型服务商的北京地区标价估算。",
    // trends and the site
    trends: (d) => `账号趋势 · 最近 ${d} 天`, site: (d) => `官网访问与下载 · 最近 ${d} 天`, siteOff: "还没接上访问统计：服务器上装 unibot-traffic（demo/showcase/mirror/traffic.py），relay 设 TRAFFIC_DB 指向它的数据库。",
    siteUpdated: (t) => `更新于 ${t}`, siteNote: "来自 Caddy 的访问日志（保留 7 天）：按天计数，访客用当天的随机盐对地址和浏览器做哈希，不存 IP。",
    sPages: "页面浏览", sVisitors: "访客", sBots: "爬虫 / 监控", sMirror: "镜像下载", sGithub: "GitHub 下载", sStars: "Stars", sPeriod: "本期", sDelta: (n) => (n > 0 ? `+${fmt(n)} 本期` : n < 0 ? `${fmt(n)} 本期` : "本期无变化"),
    sFiles: "下载的文件", sMirrorCol: "镜像", sGithubCol: "GitHub 累计", sRefs: "来源站点", sTop: "页面", sNone: "还没有数据", sSince: (d) => `自 ${d}`,
    mSignIns: "登录", mNew: "新注册", mActive: "活跃账号", mInvites: "通过邀请注册", mContribute: "加入共创", mCalls: "通话", mRefused: "超额被拒", mErrors: "上游错误",
    devices: "设备", devKinds: { phone: "手机", computer: "电脑", web: "网页版" }, invitesTitle: "邀请", invitesLine: (f) => `${fmt(f.with_code)} 人生成了邀请码 · ${fmt(f.inviters)} 人邀请成功 · ${fmt(f.invited)} 人经邀请注册 · ${fmt(f.contribute_bonuses)} 人领了共创奖励`,
    webTitle: "网页版（unibot.cn/web）", webLine: (w) => (w ? `${fmt(w.accounts)} 个账号有自己的 Muse（上限 ${fmt(w.max_accounts)}）· ${fmt(w.running)} 个在运行（上限 ${fmt(w.max_running)}）` : "未接入：relay 设 WEB_INFO_URL 指向 gateway 的 /api/web/info。"), webOff: "网页版未开启",
  } : {
    title: "unibot Cloud admin", tokenLabel: "Admin token", tokenHint: "The line in /opt/unibot/relay/ADMIN_TOKEN.txt on the server; it stays in this tab only.",
    enter: "Open", wrong: "That token is not right.", offline: "Cannot reach the server.", refresh: "Refresh", lock: "Lock", loading: "Loading…",
    today: "Today", week: "Last 7 days", period: (d) => `Last ${d} days`,
    kSamples: "Contributed turns", kSamplesSub: (n) => `${n} accounts contributing`, exportSamples: "Export JSONL", exportFailed: (why) => `The export did not go through: ${why}. Try once more; if it keeps happening, see docker logs unibot-relay on the server.`, exportCut: "the download broke off", exportEmpty: "Nothing to export yet.", contributes: "contributes", samples: "Contributed conversations (recent)", samplesNote: "Kept only for accounts that turned contribution on; the export carries no account ids.", user: "user", assistant: "reply", more: "Show more", noSamples: "None yet",
    kAccounts: "Accounts", kAccountsSub: (c) => `${c.with_password || 0} with a password · ${c.unlimited || 0} members · ${c.disabled || 0} disabled`,
    kActive: "Active accounts", kActiveSub: (n) => `${n} new`, kSpent: "Spent", kSpentSub: (r, t) => `${fmt(r)} requests · ${fmt(t)} tokens`,
    kOnline: "Devices online", kOnlineSub: (k, s) => `${k} remembered · ${s} live sign-ins`, kSignals: "Signals today",
    kSignalsSub: (s) => `${s.sign_ins} sign-ins · ${s.sign_in_failures} failed · ${s.budget_refusals} over budget · ${s.upstream_errors} upstream errors · ${s.calls} calls`,
    byKind: "By kind", byModel: "By model", byDay: (d) => `Spend by day · last ${d} days`, top: (d) => `Top spenders · last ${d} days`, events: "Activity", accounts: "All accounts", config: "Configuration",
    kinds: { chat: "Chat", image: "Pictures", video: "Video", realtime: "Calls" },
    thWho: "Account", thJoined: "Joined", thSpent: "Spent all / today", thTokens: "Tokens all / today", thReqs: "Requests", thActive: "Last active", thDevices: "Devices",
    never: "never", phone: "phone", email: "e-mail", disabled: "disabled", locked: "locked", member: "member", listed: "listed", password: "password", noAccounts: "Nobody has signed in yet.", search: "Search hint / id…",
    reqs: (n) => `${fmt(n)} req`, tokens: (n) => `${fmt(n)} tokens`, seconds: (n) => `${fmt(n)} s`, pictures: (n) => `${fmt(n)} pictures`, inOut: (i, o) => `${fmt(i)} in · ${fmt(o)} out`,
    all: "All", signIns: "Sign-ins", refusals: "Refusals", errors: "Errors", calls: "Calls", passwords: "Passwords",
    eventName: {
      "account.created": "Joined", "sign_in.code": "Signed in with a code", "sign_in.password": "Signed in with the password", "sign_in.failed": "Failed sign-in", "password.set": "Password set", "password.changed": "Password changed",
      "password.cleared": "Password removed", "sign_out": "Signed out", "sign_out.all": "Signed out everywhere", "budget.refused": "Refused: over budget", "upstream.error": "Upstream error", "call.ended": "Call ended", "contribute.on": "Joined co-creation", "contribute.bonus": "Co-creation bonus +¥10", "contribute.off": "Contribution off", "contribute.deleted": "Contributed turns deleted", "invite.accepted": "Invited a friend", "invite.used": "Signed up via invite", "invite.unknown": "Unknown invite code", "credit.granted": "Credit granted",
    },
    spendToday: "Today", spendTotal: "All time", requests: "Requests", cap: "Pool", noCap: "no cap", left: "left", usageToday: "Today", usagePeriod: (d) => `Last ${d} days`, usageTotal: "All time",
    sessions: "Sign-ins (incl. revoked)", revoked: "revoked", via: { code: "code", password: "password" }, lastUsed: "last used", devices: "Devices", firstSeen: "first", lastSeen: "last",
    ledger: "Recent requests", timeline: "Timeline", none: "—", online: "online", offline: "offline", version: "Version",
    grant: "Grant", grantPrompt: (who) => `How many tokens for ${who}? Negative takes away.`, credit: "Add credit", creditPrompt: (who) => `How many yuan for ${who}? (straight into the pool; never expires)`, creditNote: "Note (say, PR #12)", poolLine: (g, l, n, b) => `pool ${g}${l === null ? "" : ` (${l} left)`} · ${n} invited${b ? " · co-creation bonus taken" : ""}`, invitedBy: "invited by", disable: "Disable", enable: "Enable", makeMember: "Make member", unmakeMember: "Unmake member",
    memberConfirm: (who) => `Make ${who} a member? Members have no allowance limit; you pay their bill.`, listedNote: "on the server's list; edit ALLOWED_IDENTIFIERS to remove",
    disableConfirm: (who) => `Disable ${who}? Every device of theirs drops at once and cannot sign in again.`, remove: "Delete account",
    removeConfirm: (who) => `Delete the account, keys, usage and devices of ${who}? This cannot be undone.`, hasPassword: "has a password", noPassword: "no password", identifierNote: "decrypted for this view only",
    allowed: "Members (no cap)", allowedNone: "(none)", sender: "Code sender", models: "Models", prices: "Prices (¥)", rate: "Rate", rateLine: (r) => `1 USD = ${r} CNY (display only)`,
    perMinute: (n) => (n > 0 ? `${n} a minute` : "no rate limit"), capLine: (c, u, b, k) => (c > 0 ? `¥${c} (≈ $${u}) in all for non-members · +¥${b} an invite · +¥${k} for co-creation` : "no spend limit"), signupOpen: "sign-up open", signupClosed: "members only",
    realtime: "Real-time calls", on: "on", off: "off", pwMin: (n) => `passwords ≥ ${n} chars`,
    priceLine: (p) => [p.per_m_input || p.per_m_output ? `${p.per_m_input} in / ${p.per_m_output} out per M tokens` : null,
      p.per_image ? `${p.per_image} a picture${p.per_image_2k ? ` (${p.per_image_2k} at 2k)` : ""}` : null, p.per_second ? `${p.per_second} a second` : null].filter(Boolean).join("; "),
    foot: "A phone number or address is decrypted only when you open that account, with the admin token; the database holds ciphertext. What anyone said to a model is not here — it was never stored. Do not share screenshots of this page. Money is estimated at the provider's Beijing list prices.",
    trends: (d) => `Accounts · last ${d} days`, site: (d) => `The site: visits and downloads · last ${d} days`, siteOff: "No traffic figures yet: install unibot-traffic on the server (demo/showcase/mirror/traffic.py) and point the relay's TRAFFIC_DB at its database.",
    siteUpdated: (t) => `updated ${t}`, siteNote: "From Caddy's access log (kept seven days): counted by day; a visitor is a hash of address and browser under a salt made for that day. No addresses are stored.",
    sPages: "Page views", sVisitors: "Visitors", sBots: "Crawlers / monitors", sMirror: "Mirror downloads", sGithub: "GitHub downloads", sStars: "Stars", sPeriod: "this period", sDelta: (n) => (n > 0 ? `+${fmt(n)} this period` : n < 0 ? `${fmt(n)} this period` : "no change this period"),
    sFiles: "Files downloaded", sMirrorCol: "mirror", sGithubCol: "GitHub, all time", sRefs: "Referring sites", sTop: "Pages", sNone: "Nothing yet", sSince: (d) => `since ${d}`,
    mSignIns: "Sign-ins", mNew: "New accounts", mActive: "Active accounts", mInvites: "Signed up via invite", mContribute: "Joined co-creation", mCalls: "Calls", mRefused: "Refused: over budget", mErrors: "Upstream errors",
    devices: "Devices", devKinds: { phone: "phones", computer: "computers", web: "web" }, invitesTitle: "Invites", invitesLine: (f) => `${fmt(f.with_code)} made an invite code · ${fmt(f.inviters)} brought someone · ${fmt(f.invited)} came through one · ${fmt(f.contribute_bonuses)} took the co-creation bonus`,
    webTitle: "unibot Web (unibot.cn/web)", webLine: (w) => (w ? `${fmt(w.accounts)} accounts with a Muse of their own (cap ${fmt(w.max_accounts)}) · ${fmt(w.running)} running (cap ${fmt(w.max_running)})` : "Not connected: set the relay's WEB_INFO_URL to the gateway's /api/web/info."), webOff: "unibot Web is off",
  };

  const moneyN = (v) => { const c = Number(v || 0); return c >= 100 ? c.toFixed(0) : c >= 1 ? c.toFixed(2) : c > 0 && c < 0.01 ? c.toFixed(4) : c.toFixed(2); };
  const money = (cny) => `¥${moneyN(cny)}`;
  const usd = (cny, rate) => (rate > 0 ? `$${moneyN(Number(cny || 0) / rate)}` : "");
  const when = (ts) => (ts ? new Date(ts * 1000).toLocaleString(zh ? "zh-CN" : undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) : T.never);
  const dateOf = (ts) => (ts ? new Date(ts * 1000).toLocaleDateString(zh ? "zh-CN" : undefined, { year: "numeric", month: "short", day: "numeric" }) : T.never);
  const day = (ts) => new Date(ts * 1000).toLocaleDateString(zh ? "zh-CN" : undefined, { month: "numeric", day: "numeric" });
  const ago = (ts) => {
    if (!ts) return T.never;
    const s = Math.max(0, Math.floor(Date.now() / 1000 - ts));
    if (s < 90) return zh ? "刚刚" : "just now"; if (s < 3600) return zh ? `${Math.floor(s / 60)} 分钟前` : `${Math.floor(s / 60)} min ago`;
    if (s < 86400) return zh ? `${Math.floor(s / 3600)} 小时前` : `${Math.floor(s / 3600)} h ago`; return zh ? `${Math.floor(s / 86400)} 天前` : `${Math.floor(s / 86400)} d ago`;
  };
  const svg = (paths) => `<svg viewBox="0 0 24 24">${paths}</svg>`;
  const ICON = {
    chat: svg('<path d="M4 5.5h16v10H9l-5 4z"/>'),
    image: svg('<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 16l-5-5-9 9"/>'),
    video: svg('<rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10l5-3v10l-5-3"/>'),
    realtime: svg('<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/>'),
    key: svg('<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M15 5l3 3M18 4l2 2"/>'),
    in: svg('<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M9 8l5 4-5 4M14 12H3"/>'),
    out: svg('<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M15 8l5 4-5 4M20 12H9"/>'),
    warn: svg('<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18h.01"/>'),
    person: svg('<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/>'),
    phone: svg('<rect x="6" y="2.5" width="12" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>'),
    computer: svg('<rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 20.5h8M12 17v3.5"/>'),
    web: svg('<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>'),
    close: svg('<path d="M6 6l12 12M18 6L6 18"/>'),
    refresh: svg('<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>'),
    lock: svg('<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>'),
  };
  const EVENT_STYLE = {
    "account.created": ["ok", ICON.person], "sign_in.code": ["", ICON.in], "sign_in.password": ["", ICON.in], "sign_in.failed": ["warn", ICON.warn],
    "password.set": ["violet", ICON.key], "password.changed": ["violet", ICON.key], "password.cleared": ["violet", ICON.key], "sign_out": ["grey", ICON.out], "sign_out.all": ["grey", ICON.out],
    "budget.refused": ["bad", ICON.warn], "upstream.error": ["bad", ICON.warn], "call.ended": ["ok", ICON.realtime],
  };
  const FILTERS = [["", "all"], ["sign_in.code,sign_in.password,sign_in.failed,account.created", "signIns"], ["budget.refused", "refusals"], ["upstream.error", "errors"], ["call.ended", "calls"], ["password.set,password.changed,password.cleared", "passwords"]];

  const SS = window.sessionStorage;
  let token = SS.getItem("nm.admin") || "";
  let ov = null, accounts = null, usage = null, series = null, traffic = null, err = "", days = Number(SS.getItem("nm.admin.days") || 30), filter = "", filtered = null, q = "";
  let detail = null, detailErr = "";

  const app = document.getElementById("app");
  const h = (tag, attrs = {}, ...kids) => {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") el.className = v; else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
      else if (k === "html") el.innerHTML = v; else if (v !== null && v !== undefined) el.setAttribute(k, v);
    }
    for (const kid of kids.flat()) if (kid !== null && kid !== undefined) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return el;
  };

  async function api(method, path, body) {
    const r = await fetch(path, {
      method, headers: { "X-Admin-Token": token, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    }).catch(() => { throw new Error(T.offline); });
    if (r.status === 401) { token = ""; SS.removeItem("nm.admin"); err = T.wrong; draw(); throw new Error("admin"); }
    if (r.status === 204) return null;
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error((j.error && j.error.message) || r.statusText);
    return j;
  }

  async function load() {
    try {
      [ov, accounts, usage] = await Promise.all([api("GET", `/v1/admin/overview?days=${days}`), api("GET", "/v1/admin/accounts"), api("GET", `/v1/admin/usage?days=${Math.min(days, 90)}`)]);
      // the two newer views: a relay from before them, or a hiccup, leaves the panels out
      [series, traffic] = await Promise.all([api("GET", `/v1/admin/series?days=${days}`).catch(() => null), api("GET", `/v1/admin/traffic?days=${days}`).catch(() => null)]);
      err = "";
    } catch (e) {
      if (e.message !== "admin") err = e.message;
    }
    draw();
  }
  async function loadFiltered() {
    if (!filter) { filtered = null; draw(); return; }
    try { filtered = (await api("GET", `/v1/admin/events?limit=80&kind=${encodeURIComponent(filter)}`)).events; } catch (_) { filtered = []; }
    draw();
  }

  // ── gate ───────────────────────────────────────────────────────
  function drawGate() {
    const input = h("input", { type: "password", autocomplete: "off", spellcheck: "false", placeholder: "…" });
    const go = async () => { token = input.value.trim(); if (!token) return; SS.setItem("nm.admin", token); err = ""; await load(); };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
    app.replaceChildren(h("div", { class: "signin admin" },
      h("div", { class: "card rise" },
        h("div", { class: "disc" }, h("img", { src: "../mark.svg", alt: "" })),
        h("h1", {}, T.title),
        h("p", { class: "sub" }, location.host),
        h("div", { class: "field" }, h("label", {}, T.tokenLabel), h("div", { class: "in" }, input)),
        h("button", { class: "btn", onclick: go }, T.enter),
        h("div", { class: "hint" + (err ? " bad" : "") }, err || T.tokenHint),
      )));
    input.focus();
  }

  // ── pieces ─────────────────────────────────────────────────────
  const who = (a) => a.identifier || a.hint || (a.id || "").slice(0, 8);
  const initial = (a) => ((a.hint || a.identifier || "?").replace(/[^0-9a-z]/gi, "").slice(0, 1).toUpperCase() || "?");
  const kindAmount = (r) => {
    const k = r.kind || "chat";
    if (k === "realtime" || k === "video") return T.seconds(r.charged);
    if (k === "image") return T.pictures(r.requests);
    return T.tokens(r.charged);
  };
  function kindRows(rows, rate) {
    if (!rows || !rows.length) return h("div", { class: "empty" }, T.none);
    return rows.map((r) => h("div", { class: "usage-row" },
      h("div", { class: "k" }, h("i", { class: r.kind || "chat" }), T.kinds[r.kind] || r.kind),
      h("span", { class: "n" }, `${T.reqs(r.requests)} · ${kindAmount(r)}${r.kind === "chat" && (r.prompt_tokens || r.completion_tokens) ? ` · ${T.inOut(r.prompt_tokens, r.completion_tokens)}` : ""}`),
      h("span", { class: "c", title: usd(r.cost_cny, rate) }, money(r.cost_cny))));
  }
  function modelRows(rows, rate, kinds) {
    if (!rows || !rows.length) return h("div", { class: "empty" }, T.none);
    return rows.map((r) => h("div", { class: "usage-row" },
      h("div", { class: "k" }, h("i", { class: (kinds && kinds[r.model]) || r.kind || "chat" }), h("code", {}, r.model)),
      h("span", { class: "n" }, `${T.reqs(r.requests)}${r.charged ? " · " + fmt(r.charged) : ""}`),
      h("span", { class: "c", title: usd(r.cost_cny, rate) }, money(r.cost_cny))));
  }
  /** Stacked bars, one column per local day, a segment per kind. `rows` are {day, kind, cost_cny}. */
  function dayBars(rows, n, offsetH, rate) {
    const offset = Number(offsetH || 0) * 3600;
    const byDay = new Map();
    for (const r of rows || []) {
      const d = byDay.get(r.day) || { day: r.day, cost: 0, parts: {}, lines: [] };
      d.cost += r.cost_cny || 0; d.parts[r.kind] = (d.parts[r.kind] || 0) + (r.cost_cny || 0);
      d.lines.push(`${T.kinds[r.kind] || r.kind} ${money(r.cost_cny)} · ${T.reqs(r.requests)}${r.charged ? " · " + fmt(r.charged) : ""}`);
      byDay.set(r.day, d);
    }
    const nowS = Math.floor(Date.now() / 1000);
    const start = nowS - ((nowS + offset) % 86400) - (n - 1) * 86400;
    const cols = [];
    for (let i = 0; i < n; i++) { const k = start + i * 86400; cols.push(byDay.get(k) || { day: k, cost: 0, parts: {}, lines: [] }); }
    const max = Math.max(0.0001, ...cols.map((d) => d.cost));
    const every = n > 45 ? 7 : n > 20 ? 3 : 1;
    return h("div", {}, h("div", { class: "bars" }, ...cols.map((d, i) => h("div", { class: "col", title: `${day(d.day)} · ${money(d.cost)} ${usd(d.cost, rate)}\n${d.lines.join("\n")}` },
      ...["realtime", "video", "image", "chat"].filter((k) => d.parts[k] > 0).map((k) => h("div", { class: "seg-bar " + k, style: `height:${Math.max(1, Math.round(84 * d.parts[k] / max))}px` })),
      d.cost <= 0 ? h("div", { class: "seg-bar", style: "height:1px;background:var(--hairline)" }) : null,
      i % every === 0 ? h("div", { class: "lbl" }, day(d.day)) : null))),
      h("div", { class: "bars-x" }),
      h("div", { class: "legend" }, ...["chat", "image", "video", "realtime"].map((k) => h("span", {}, h("i", { class: k }), T.kinds[k]))));
  }
  /** One metric across the period: its total, then a tiny bar per day (hover for the day and the count). */
  function sparkRow(label, cols, key, tone, dayOf) {
    const values = cols.map((c) => Number(c[key] || 0));
    const max = Math.max(1, ...values), total = values.reduce((a, b) => a + b, 0);
    return h("div", { class: "spark" },
      h("span", { class: "k" }, label), h("b", {}, fmt(total)),
      h("div", { class: "bars-mini" }, ...values.map((v, i) => h("i", { class: v > 0 ? tone : "z", style: `height:${v > 0 ? Math.max(2, Math.round(22 * v / max)) : 1}px`, title: `${dayOf(cols[i])} · ${fmt(v)}` }))));
  }
  const sizeOf = (b) => (b >= 1e9 ? `${(b / 1e9).toFixed(1)} GB` : b >= 1e6 ? `${(b / 1e6).toFixed(0)} MB` : `${Math.round(b / 1e3)} kB`);
  function rankRows(items, unit) {
    if (!items || !items.length) return h("div", { class: "empty" }, T.sNone);
    const max = Math.max(1, ...items.map((x) => x.hits));
    return h("div", { class: "ranks" }, ...items.slice(0, 12).map((x) => h("div", { class: "rank", title: x.bytes ? sizeOf(x.bytes) : "" },
      h("span", { class: "n" }, x.name), h("i", { style: `width:${Math.round(100 * x.hits / max)}%` }), h("b", {}, unit ? unit(x) : fmt(x.hits)))));
  }
  /** The site: what unibot-traffic counted from the access log, next to GitHub's own numbers. */
  function sitePanel() {
    const tr = traffic;
    if (!tr) return null;
    if (!tr.available) return h("div", { class: "panel span" }, h("h2", {}, T.site(days)), h("div", { class: "empty" }, T.siteOff));
    const rows = tr.days || [], gh = tr.github || {}, ghDays = gh.days || [];
    const sum = (k) => rows.reduce((a, r) => a + Number(r[k] || 0), 0);
    const first = ghDays[0], last = ghDays[ghDays.length - 1];
    const delta = (k) => (first && last ? Number(last[k]) - Number(first[k]) : 0);
    const dayOf = (r) => r.day.slice(5).replace("-", "/");
    const kpi = (k, v, sub) => h("div", { class: "kpi flat" }, h("div", { class: "k" }, k), h("div", { class: "v" }, v), sub ? h("div", { class: "s" }, sub) : null);
    const ghAssets = Object.assign({}, ...Object.values(gh.assets || {}));
    return h("div", { class: "panel span" },
      h("h2", {}, T.site(days), h("span", { class: "sp" }), tr.updated_at ? h("span", { class: "fine" }, T.siteUpdated(when(tr.updated_at))) : null),
      h("div", { class: "kpis in-panel" },
        kpi(T.sPages, fmt(sum("pages")), T.sPeriod), kpi(T.sVisitors, fmt(sum("visitors")), T.sPeriod), kpi(T.sMirror, fmt(sum("downloads")), sizeOf(rows.reduce((a, r) => a + Number(r.bytes || 0), 0))),
        kpi(T.sGithub, fmt(gh.downloads || 0), T.sDelta(delta("downloads"))), kpi(T.sStars, fmt(gh.stars || 0), T.sDelta(delta("stars"))), kpi(T.sBots, fmt(sum("bots")), T.sPeriod)),
      rows.length ? h("div", { class: "sparks" },
        sparkRow(T.sPages, rows, "pages", "blue", dayOf), sparkRow(T.sVisitors, rows, "visitors", "cyan", dayOf), sparkRow(T.sMirror, rows, "downloads", "violet", dayOf)) : h("div", { class: "empty" }, T.sNone),
      h("div", { class: "cols3" },
        h("div", {}, h("h3", {}, T.sFiles, h("span", { class: "fine" }, ` · ${T.sMirrorCol} / ${T.sGithubCol}`)), rankRows(tr.downloads, (x) => `${fmt(x.hits)}${ghAssets[x.name] !== undefined ? ` / ${fmt(ghAssets[x.name])}` : ""}`)),
        h("div", {}, h("h3", {}, T.sRefs), rankRows(tr.referrers)),
        h("div", {}, h("h3", {}, T.sTop), rankRows(tr.pages))),
      h("div", { class: "fine", style: "padding:0 16px 12px" }, T.siteNote));
  }
  /** The relay's own series: sign-ins, accounts, invites, co-creation, calls — and the devices and unibot Web as they stand. */
  function trendsPanel() {
    const sr = series;
    if (!sr) return null;
    const rows = sr.days || [];
    const dayOf = (r) => day(r.day);
    const dev = sr.devices || [];
    const byKind = {};
    for (const d of dev) { const k = byKind[d.kind] || (byKind[d.kind] = { n: 0, os: [] }); k.n += d.count; k.os.push(`${d.os || "?"} ${fmt(d.count)}`); }
    return h("div", { class: "panel span" },
      h("h2", {}, T.trends(days)),
      h("div", { class: "sparks" },
        sparkRow(T.mSignIns, rows, "sign_ins", "blue", dayOf), sparkRow(T.mNew, rows, "new_accounts", "ok", dayOf), sparkRow(T.mActive, rows, "active_accounts", "cyan", dayOf),
        sparkRow(T.mInvites, rows, "invites_used", "violet", dayOf), sparkRow(T.mContribute, rows, "contribute_on", "violet", dayOf), sparkRow(T.mCalls, rows, "calls", "ok", dayOf),
        sparkRow(T.mRefused, rows, "budget_refusals", "warn", dayOf), sparkRow(T.mErrors, rows, "upstream_errors", "warn", dayOf)),
      h("div", { class: "kv" },
        h("b", {}, T.devices), h("span", {}, Object.keys(byKind).length ? Object.entries(byKind).map(([k, v]) => h("div", {}, h("span", { class: "pill", style: "margin-right:6px" }, `${T.devKinds[k] || k} ${fmt(v.n)}`), h("span", { class: "fine" }, v.os.join(" · ")))) : T.none),
        h("b", {}, T.invitesTitle), h("span", {}, T.invitesLine({ with_code: 0, inviters: 0, invited: 0, contribute_bonuses: 0, ...(sr.invites || {}) })),
        h("b", {}, T.webTitle), h("span", {}, sr.web && sr.web.enabled === false ? T.webOff : T.webLine(sr.web))));
  }
  function eventRow(e, withWho) {
    const [tone, icon] = EVENT_STYLE[e.kind] || ["grey", ICON.person];
    return h("div", { class: "row" + (withWho && e.account_id ? " tap" : ""), onclick: withWho && e.account_id ? () => openAccount(e.account_id) : null },
      h("span", { class: "tile " + tone, html: icon }),
      h("div", { class: "txt" }, h("div", { class: "t" }, T.eventName[e.kind] || e.kind, withWho && e.hint ? [" · ", h("span", { style: "color:var(--ink-2);font-weight:400" }, e.hint)] : null),
        h("div", { class: "s" }, [when(e.ts), e.detail].filter(Boolean).join(" · "))));
  }
  const deviceIcon = (k) => (k === "phone" ? ICON.phone : k === "web" ? ICON.web : ICON.computer);

  // ── actions ────────────────────────────────────────────────────
  async function doGrant(a) {
    const v = prompt(T.grantPrompt(who(a)), "1000000");
    if (v === null) return;
    const n = parseInt(v.replace(/[\s,_]/g, ""), 10);
    if (!Number.isFinite(n) || n === 0) return;
    try { await api("POST", "/v1/admin/grant", { account_id: a.id, tokens: n }); } catch (e) { alert(e.message); }
    await Promise.all([load(), detail ? openAccount(a.id) : null]);
  }
  async function doCredit(a) {
    const v = prompt(T.creditPrompt(who(a)), "5");
    if (v === null) return;
    const cny = parseFloat(v.replace(/[\s,¥]/g, ""));
    if (!Number.isFinite(cny) || cny <= 0) return;
    const note = prompt(T.creditNote, "") || "";
    try { await api("POST", "/v1/admin/credit", { account_id: a.id, cny, note }); } catch (e) { alert(e.message); }
    await Promise.all([load(), detail ? openAccount(a.id) : null]);
  }
  async function doDisable(a) {
    if (!a.disabled && !confirm(T.disableConfirm(who(a)))) return;
    try { await api("POST", "/v1/admin/disable", { account_id: a.id, disabled: !a.disabled }); } catch (e) { alert(e.message); }
    await Promise.all([load(), detail ? openAccount(a.id) : null]);
  }
  async function doDelete(a) {
    if (!confirm(T.removeConfirm(who(a)))) return;
    try { await api("POST", "/v1/admin/delete", { account_id: a.id }); } catch (e) { alert(e.message); }
    closeDrawer(); await load();
  }
  async function doMember(a) {
    if (!a.unlimited && !confirm(T.memberConfirm(who(a)))) return;
    try { await api("POST", "/v1/admin/unlimited", { account_id: a.id, unlimited: !a.unlimited }); } catch (e) { alert(e.message); }
    await Promise.all([load(), detail ? openAccount(a.id) : null]);
  }

  // ── the drawer: one account ────────────────────────────────────
  let drawerEl = null;
  function closeDrawer() { if (drawerEl) { drawerEl.remove(); drawerEl = null; } detail = null; }
  /** The whole training set as a file: fetched with the admin token (a bare link could not carry it).
      What can go wrong is said in words: the browser's "Failed to fetch" covers a dropped connection,
      a proxy in the way and a token that stopped working alike. */
  async function exportSamples() {
    let r;
    try {
      r = await fetch("/v1/admin/samples/export", { headers: { "X-Admin-Token": token }, cache: "no-store" });
    } catch (_) { alert(T.exportFailed(T.offline)); return; }
    if (r.status === 401) { token = ""; SS.removeItem("nm.admin"); err = T.wrong; draw(); return; }
    if (!r.ok) { alert(T.exportFailed(`HTTP ${r.status}${r.statusText ? " " + r.statusText : ""}`)); return; }
    let blob;
    try { blob = await r.blob(); } catch (_) { alert(T.exportFailed(T.exportCut)); return; }
    if (!blob.size) { alert(T.exportEmpty); return; }
    const url = URL.createObjectURL(blob);
    const a = h("a", { href: url, download: `unibot-samples-${new Date().toISOString().slice(0, 10)}.jsonl` });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  async function openAccount(id) {
    if (!drawerEl) {
      drawerEl = h("div", { class: "drawer-scrim", onclick: (e) => { if (e.target === drawerEl) closeDrawer(); } }, h("div", { class: "drawer" }, h("div", { class: "empty" }, T.loading)));
      document.body.append(drawerEl);
      const onKey = (e) => { if (e.key === "Escape") { closeDrawer(); document.removeEventListener("keydown", onKey); } };
      document.addEventListener("keydown", onKey);
    }
    try { detail = await api("GET", `/v1/admin/accounts/${encodeURIComponent(id)}?days=${days}`); detailErr = ""; }
    catch (e) { detail = null; detailErr = e.message; }
    if (drawerEl) drawDrawer();
  }
  function drawDrawer() {
    const box = drawerEl.firstChild;
    if (!detail) { box.replaceChildren(h("div", { class: "head" }, h("h2", {}, "…"), h("button", { class: "round", html: ICON.close, onclick: closeDrawer })), h("div", { class: "empty" }, detailErr || T.loading)); return; }
    const s = (ov && ov.settings) || {}, rate = Number(s.usd_cny || 0), kinds = s.model_kinds || {};
    const a = detail.account, sp = detail.spend || {}, u = detail.usage || {}, p = u.period || {};
    const tags = [
      h("span", { class: "pill" }, a.channel === "phone" ? T.phone : T.email),
      a.member ? h("span", { class: "pill ok", title: a.listed ? T.listedNote : "" }, a.listed ? T.listed : T.member) : null,
      a.has_password ? h("span", { class: "pill violet" }, T.hasPassword) : h("span", { class: "pill" }, T.noPassword),
      a.disabled ? h("span", { class: "pill bad" }, T.disabled) : null,
      a.locked ? h("span", { class: "pill warn" }, T.locked) : null,
      a.contribute ? h("span", { class: "pill cyan" }, T.contributes) : null,
    ];
    const cap = a.member ? 0 : Number(sp.grant_cny || 0), frac = cap > 0 ? Math.min(1, Number(sp.total_cny || 0) / cap) : 0;
    box.replaceChildren(
      h("div", { class: "head" }, h("h2", {}, a.identifier || a.hint), h("button", { class: "round", html: ICON.close, onclick: closeDrawer })),
      h("div", { class: "card" },
        h("div", { class: "identity" }, h("div", { class: "disc" }, initial(a)),
          h("div", { class: "who" }, h("div", { class: "n" }, a.hint, " ", h("span", { class: "fine" }, `· ${T.identifierNote}`)),
            h("div", { class: "m" }, `${dateOf(a.created_at)} · `, h("code", {}, a.id)), h("div", { class: "tags", style: "margin-top:6px" }, ...tags))),
        h("div", { class: "stats" },
          h("div", {}, h("div", { class: "k" }, T.spendTotal), h("div", { class: "v", title: usd(sp.total_cny, rate) }, money(sp.total_cny)), cap > 0 ? h("div", { class: "meter", style: "margin-top:6px" }, h("i", { class: frac >= 1 ? "bad" : frac >= 0.8 ? "warn" : "", style: `width:${Math.round(frac * 100)}%` })) : null, h("div", { class: "k", style: "margin-top:4px" }, cap > 0 ? `${T.cap} ${money(cap)} · ${T.left} ${money(sp.left_cny || 0)}` : T.noCap)),
          h("div", {}, h("div", { class: "k" }, T.spendToday), h("div", { class: "v", title: usd(sp.today_cny, rate) }, money(sp.today_cny)), h("div", { class: "k", style: "margin-top:4px" }, T.tokens(a.used))),
          h("div", {}, h("div", { class: "k" }, T.requests), h("div", { class: "v" }, fmt(sp.requests_total)), h("div", { class: "k", style: "margin-top:4px" }, s.unlimited ? T.noCap : `${T.grant}: ${fmt(a.granted)}`))),
        h("div", { class: "fine", style: "padding:0 16px 10px" }, T.poolLine(money(a.grant_cny || 0), a.left_cny === null || a.left_cny === undefined ? null : money(a.left_cny), a.invites || 0, !!a.contribute_bonus_at),
          a.invited_by ? [" · ", T.invitedBy, " ", h("code", {}, String(a.invited_by).slice(0, 8))] : null),
        h("div", { class: "acts" },
          s.unlimited ? null : h("button", { class: "btn quiet", onclick: () => doGrant(a) }, T.grant),
          h("button", { class: "btn quiet", onclick: () => doCredit(a) }, T.credit),
          a.listed ? null : h("button", { class: "btn quiet", onclick: () => doMember(a) }, a.unlimited ? T.unmakeMember : T.makeMember),
          h("button", { class: "btn quiet", onclick: () => doDisable(a) }, a.disabled ? T.enable : T.disable),
          h("button", { class: "btn danger", onclick: () => doDelete(a) }, T.remove))),
      h("div", { class: "label" }, T.byKind), h("div", { class: "card" },
        h("div", { class: "label", style: "margin:12px 16px 4px" }, T.usageToday), kindRows(u.today && u.today.by_kind, rate),
        h("div", { class: "label", style: "margin:12px 16px 4px" }, T.usagePeriod(p.days || days)), kindRows(p.by_kind, rate),
        h("div", { class: "label", style: "margin:12px 16px 4px" }, T.usageTotal), kindRows(u.total && u.total.by_kind, rate)),
      h("div", { class: "label" }, T.byDay(p.days || days)), h("div", { class: "card" }, dayBars(p.by_day, p.days || days, s.day_offset_h, rate)),
      h("div", { class: "label" }, T.byModel), h("div", { class: "card" }, modelRows(p.by_model, rate, kinds)),
      h("div", { class: "label" }, T.sessions), h("div", { class: "card" }, (detail.sessions || []).length ? (detail.sessions || []).map((k) => h("div", { class: "row" },
        h("span", { class: "tile" + (k.revoked_at ? " grey" : ""), html: /web|网页|browser/i.test(k.device || "") ? ICON.web : /phone|android|手机|iphone/i.test(k.device || "") ? ICON.phone : ICON.computer }),
        h("div", { class: "txt" }, h("div", { class: "t" }, k.device || "—", k.revoked_at ? [" ", h("span", { class: "pill" }, T.revoked)] : null),
          h("div", { class: "s" }, `${T.via[k.via] || k.via} · ${when(k.created_at)}${k.last_used_at ? ` · ${T.lastUsed} ${ago(k.last_used_at)}` : ""}`)),
        h("code", { class: "fine" }, k.prefix))) : h("div", { class: "empty" }, T.none)),
      h("div", { class: "label" }, T.devices), h("div", { class: "card" }, (detail.devices || []).length ? (detail.devices || []).map((d) => h("div", { class: "row" },
        h("span", { class: "tile" + (d.online ? "" : " grey"), html: deviceIcon(d.kind) }),
        h("div", { class: "txt" }, h("div", { class: "t" }, d.name || d.id, " ", h("span", { class: "pill " + (d.online ? "ok" : "") }, d.online ? T.online : T.offline)),
          h("div", { class: "s" }, [d.kind, d.os, d.version ? "v" + d.version : "", d.last_seen ? `${T.lastSeen} ${ago(d.last_seen)}` : "", d.first_seen ? `${T.firstSeen} ${dateOf(d.first_seen)}` : ""].filter(Boolean).join(" · "))),
        Array.isArray(d.actions) && d.actions.length ? h("span", { class: "pill", title: d.actions.join(", ") }, d.actions.length) : null)) : h("div", { class: "empty" }, T.none)),
      h("div", { class: "label" }, T.ledger), h("div", { class: "card ledger" }, (detail.recent || []).length ? h("table", {}, h("tbody", {}, ...(detail.recent || []).slice(0, 40).map((r) => h("tr", {},
        h("td", {}, when(r.ts)), h("td", {}, h("span", { class: "pill " + ({ chat: "blue", image: "violet", video: "cyan", realtime: "ok" }[r.kind] || "") }, T.kinds[r.kind] || r.kind)), h("td", {}, h("code", {}, r.model || "")),
        h("td", { class: "num" }, r.kind === "chat" ? T.inOut(r.prompt_tokens, r.completion_tokens) : fmt(r.charged)), h("td", { class: "num" }, money(r.cost_cny)))))) : h("div", { class: "empty" }, T.none)),
      h("div", { class: "label" }, T.timeline), h("div", { class: "card feed" }, (detail.events || []).length ? (detail.events || []).map((e) => eventRow(e, false)) : h("div", { class: "empty" }, T.none)),
      ...(a.contribute ? [h("div", { class: "label" }, T.samples, ` · ${fmt(a.samples || 0)}`), h("div", { class: "card" }, h("div", { class: "fine", style: "padding:10px 16px 0" }, T.samplesNote), samplesBox(a.id))] : []),
    );
  }

  /** The turns one account contributed, newest first, loaded on demand; each shows the last user message and the reply. */
  function samplesBox(accountId) {
    const box = h("div", {}, h("div", { class: "empty" }, T.loading));
    let before = 0;
    const draw = (items, total) => {
      const nodes = items.map((smp) => {
        const msgs = Array.isArray(smp.request) ? smp.request : [];
        const lastUser = [...msgs].reverse().find((m) => m && m.role === "user");
        const text = (m) => !m ? "" : typeof m.content === "string" ? m.content : Array.isArray(m.content) ? m.content.map((p) => p && p.type === "text" ? p.text : p && p.omitted ? `[${p.type}]` : "").join(" ") : "";
        return h("div", { class: "row", style: "align-items:flex-start" }, h("div", { class: "txt", style: "white-space:pre-wrap;word-break:break-word" },
          h("div", { class: "s" }, `${when(smp.ts)} · `, h("code", {}, smp.model || ""), ` · ${T.inOut(smp.prompt_tokens, smp.completion_tokens)}`, smp.meta && smp.meta.ua ? ` · ${String(smp.meta.ua).split(" ")[0]}` : ""),
          h("div", { class: "t" }, h("b", {}, `${T.user}: `), text(lastUser).slice(0, 600)),
          h("div", { class: "t", style: "margin-top:4px" }, h("b", {}, `${T.assistant}: `), String(smp.response || "").slice(0, 900))));
      });
      const more = items.length && total > (box.childElementCount + items.length) ? h("button", { class: "btn quiet sm", style: "margin:8px 16px 12px", onclick: () => load() }, T.more) : null;
      if (before === 0) box.replaceChildren(...(nodes.length ? nodes : [h("div", { class: "empty" }, T.noSamples)]), more); else { box.querySelectorAll("button").forEach((b) => b.remove()); box.append(...nodes, more); }
      if (items.length) before = items[items.length - 1].ts;
    };
    const load = async () => {
      try { const r = await api("GET", `/v1/admin/samples?account_id=${encodeURIComponent(accountId)}&limit=20${before ? `&before=${before}` : ""}`); draw(r.samples || [], r.total || 0); }
      catch (e) { box.replaceChildren(h("div", { class: "hint bad" }, e.message)); }
    };
    load();
    return box;
  }

  // ── main ───────────────────────────────────────────────────────
  function drawMain() {
    const s = ov.settings || {}, rate = Number(s.usd_cny || 0), kinds = s.model_kinds || {};
    const c = ov.accounts || {}, today = ov.today || {}, week = ov.week || {}, period = ov.period || {}, sig = ov.signals_today || {};
    const list = (accounts && accounts.accounts) || [];
    const shown = q ? list.filter((a) => (a.hint || "").includes(q) || (a.identifier || "").includes(q) || (a.id || "").startsWith(q)) : list;
    const kpi = (k, v, sub, small) => h("div", { class: "kpi" }, h("div", { class: "k" }, k), h("div", { class: "v" }, v, small ? h("small", {}, small) : null), h("div", { class: "s", title: sub }, sub));
    const spendKpi = (label, t) => kpi(`${T.kSpent} · ${label}`, money(t.cost_cny), T.kSpentSub(t.requests, t.charged), usd(t.cost_cny, rate));

    const rows = shown.map((a) => h("tr", { onclick: () => openAccount(a.id) },
      h("td", {}, h("div", { class: "who" }, h("div", { class: "disc" }, initial(a)),
        h("div", { style: "min-width:0" }, h("div", { class: "n" }, a.hint),
          h("div", { class: "tags" },
            h("span", { class: "pill" }, a.channel === "phone" ? T.phone : T.email),
            a.member ? h("span", { class: "pill ok" }, a.listed ? T.listed : T.member) : null,
            a.has_password ? h("span", { class: "pill violet" }, T.password) : null,
            a.disabled ? h("span", { class: "pill bad" }, T.disabled) : null,
            a.locked ? h("span", { class: "pill warn" }, T.locked) : null)))),
      h("td", { class: "hide-sm" }, dateOf(a.created_at)),
      h("td", { class: "num" }, money(a.spent_cny), h("span", { class: "sub" }, money(a.spent_today_cny))),
      h("td", { class: "num hide-sm" }, fmt(a.used), h("span", { class: "sub" }, fmt(a.used_today))),
      h("td", { class: "num hide-sm" }, fmt(a.requests)),
      h("td", { class: "hide-sm" }, a.last_active_at ? ago(a.last_active_at) : T.never, h("span", { class: "sub" }, `${a.live_keys || 0} ${zh ? "个登录" : "sign-ins"}`)),
      h("td", {}, (a.devices || []).length ? h("div", { class: "dev" }, ...(a.devices || []).map((d) => h("span", { title: `${d.kind || ""} ${d.os || ""} · ${d.online ? T.online : ago(d.last_seen)}` }, h("i", { class: "dot" + (d.online ? " on" : "") }), d.name || d.id))) : T.none),
    ));

    app.replaceChildren(h("div", { class: "admin" },
      h("div", { class: "bar" },
        h("img", { src: "../mark.svg", alt: "" }),
        h("h1", {}, T.title, h("small", {}, location.host, ov.version ? ` · v${ov.version}` : "")),
        h("div", { class: "seg" }, ...[7, 30, 90].map((d) => h("button", { class: d === days ? "on" : "", onclick: () => { days = d; SS.setItem("nm.admin.days", d); load(); } }, T.period(d)))),
        h("button", { class: "round", title: T.refresh, html: ICON.refresh, onclick: load }),
        h("button", { class: "round", title: T.lock, html: ICON.lock, onclick: () => { token = ""; SS.removeItem("nm.admin"); ov = null; draw(); } })),
      err ? h("div", { class: "hint bad", style: "margin:0 0 14px" }, err) : null,
      h("div", { class: "kpis" },
        kpi(T.kAccounts, fmt(c.total), T.kAccountsSub(c)),
        kpi(`${T.kActive} · ${T.today}`, fmt(today.active_accounts), T.kActiveSub(today.new_accounts || 0), `/ ${fmt(period.active_accounts)} ${T.period(days)}`),
        spendKpi(T.today, today), spendKpi(T.week, week), spendKpi(T.period(days), period),
        kpi(T.kOnline, fmt(ov.online_devices), T.kOnlineSub(c.devices || 0, c.live_keys || 0)),
        kpi(T.kSignals, fmt((sig.sign_ins || 0) + (sig.calls || 0)), T.kSignalsSub({ sign_ins: 0, sign_in_failures: 0, budget_refusals: 0, upstream_errors: 0, calls: 0, ...sig })),
        (() => { const ct = ov.contributions || {}; const k = kpi(T.kSamples, fmt(ct.samples || 0), T.kSamplesSub(ct.accounts || 0)); if (ct.samples) k.append(h("button", { class: "btn quiet sm", style: "margin-top:6px", onclick: exportSamples }, T.exportSamples)); return k; })()),
      h("div", { class: "grid" },
        h("div", { class: "panel span" }, h("h2", {}, T.byDay(Math.min(days, 90))), dayBars(usage && usage.days, Math.min(days, 90), s.day_offset_h, rate)),
        trendsPanel(), sitePanel(),
        h("div", { class: "panel" }, h("h2", {}, `${T.byKind} · ${T.today}`), kindRows(today.by_kind, rate), h("h2", {}, `${T.byKind} · ${T.period(days)}`), kindRows(period.by_kind, rate)),
        h("div", { class: "panel" }, h("h2", {}, `${T.byModel} · ${T.period(days)}`), modelRows(period.by_model, rate, kinds)),
        h("div", { class: "panel" }, h("h2", {}, T.top(days)), (ov.top_accounts || []).length ? (ov.top_accounts || []).map((t) => h("div", { class: "row tap", onclick: () => openAccount(t.account_id) },
          h("span", { class: "tile grey", html: ICON.person }), h("div", { class: "txt" }, h("div", { class: "t" }, t.hint), h("div", { class: "s" }, `${T.reqs(t.requests)} · ${T.tokens(t.charged)}`)),
          h("span", { class: "v" }, h("b", {}, money(t.cost_cny))))) : h("div", { class: "empty" }, T.none)),
        h("div", { class: "panel feed" }, h("h2", {}, T.events),
          h("div", { class: "filter" }, ...FILTERS.map(([k, name]) => h("button", { class: k === filter ? "on" : "", onclick: () => { filter = k; loadFiltered(); } }, T[name]))),
          h("div", { class: "list" }, ...((filter ? filtered : ov.events) || []).slice(0, 60).map((e) => eventRow(e, true)),
            !((filter ? filtered : ov.events) || []).length ? h("div", { class: "empty" }, T.none) : null)),
        h("div", { class: "panel span" },
          h("h2", {}, T.accounts, h("span", { class: "pill" }, shown.length), h("span", { class: "sp" }),
            h("span", { class: "search" }, h("input", { type: "search", placeholder: T.search, value: q, oninput: (e) => { q = e.target.value.trim(); drawMain(); const i = app.querySelector(".search input"); if (i) { i.focus(); i.setSelectionRange(q.length, q.length); } } }))),
          rows.length ? h("table", {},
            h("thead", {}, h("tr", {},
              h("th", {}, T.thWho), h("th", { class: "hide-sm" }, T.thJoined), h("th", { class: "num" }, T.thSpent), h("th", { class: "num hide-sm" }, T.thTokens),
              h("th", { class: "num hide-sm" }, T.thReqs), h("th", { class: "hide-sm" }, T.thActive), h("th", {}, T.thDevices))),
            h("tbody", {}, ...rows)) : h("div", { class: "empty" }, T.noAccounts)),
        h("div", { class: "panel span" },
          h("h2", {}, T.config),
          h("div", { class: "kv" },
            h("b", {}, T.kAccounts), h("span", {}, `${s.signup_open ? T.signupOpen : T.signupClosed} · ${T.capLine(s.allowance_cny, s.allowance_usd, s.invite_bonus_cny, s.contribute_bonus_cny)} · ${T.perMinute(s.per_minute_requests)} · ${T.pwMin(s.password_min_len || 8)}`),
            h("b", {}, T.realtime), h("span", {}, s.realtime_enabled ? T.on : T.off),
            h("b", {}, T.allowed), h("code", {}, (s.allowed_identifiers || []).join(", ") || T.allowedNone),
            h("b", {}, T.rate), h("span", {}, T.rateLine(rate)),
            h("b", {}, T.prices), h("span", {}, ...Object.entries(s.prices || {}).map(([id, p]) => h("div", {}, h("span", { class: "pill " + ({ chat: "blue", image: "violet", video: "cyan", realtime: "ok" }[kinds[id]] || ""), style: "margin-right:6px" }, T.kinds[kinds[id]] || kinds[id] || ""), h("code", {}, id), " ", T.priceLine(p)))),
            h("b", {}, T.sender), h("span", {}, s.sender || "log"),
            h("b", {}, T.version), h("span", {}, ov.version || "")))),
      h("p", { class: "foot" }, T.foot),
    ));
  }

  function draw() {
    if (!token || !ov) drawGate(); else drawMain();
    if (drawerEl) drawDrawer();
  }

  document.title = T.title;
  if (token) load(); else draw();
})();
