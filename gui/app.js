const byId = (id) => document.getElementById(id);
const write = (id, text) => { byId(id).textContent = text; };
const pretty = (value) => JSON.stringify(value, null, 2);
const state = { snapshot: null, lastTitle: undefined, previous: [], busy: false };
let modelProfiles = { active: null, profiles: [] };
let backgroundEnabled = false;
const backgrounds = new Map();

async function api(path, payload) {
  const options = payload === undefined ? {} : {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  };
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok || data.error) {
    throw Object.assign(new Error(data.error || `请求失败（${response.status}）`), { details: data });
  }
  return data;
}

function setBusy(busy) {
  state.busy = busy;
  byId("refresh").disabled = busy;
  byId("generate").disabled = busy;
  byId("retry").disabled = busy || !state.previous.length;
  byId("open-settings").disabled = busy;
  byId("background").disabled = busy || !state.snapshot?.ConversationTitle;
  byId("refresh-background").disabled = busy || !state.snapshot?.ConversationTitle;
}

function showBackground() {
  const title = state.snapshot?.ConversationTitle;
  const saved = backgrounds.get(title);
  byId("background-panel").hidden = !backgroundEnabled;
  byId("background").value = saved?.text ?? "";
  write("background-status", "");
  write("debug-background", saved?.request ? pretty(saved.request) : "尚未请求概括。");
}

function applyPreferences(data) {
  backgroundEnabled = data.background_enabled === true;
  byId("background-enabled").checked = backgroundEnabled;
  showBackground();
}

async function requestBackground(title) {
  write("background-status", "正在概括对话…");
  try {
    const { background, request } = await api("/api/background", { snapshot: state.snapshot });
    backgrounds.set(title, { text: background, request });
    showBackground();
    write("background-status", "概括已更新，可直接编辑。");
  } catch (error) {
    if (error.details?.request) write("debug-background", pretty(error.details.request));
    write("background-status", `概括失败。${error.message.split("\n")[0]}`);
    throw error;
  }
}

function toggleDebug(open) {
  byId("debug").hidden = !open;
  byId("workspace").classList.toggle("with-debug", open);
  byId("open-debug").setAttribute("aria-expanded", String(open));
}

function showError(error) {
  write("status", error.message.split("\n")[0]);
  write("debug-error", error.message);
  byId("debug-error-panel").hidden = false;
}

function renderReplies(replies) {
  const list = byId("replies");
  list.replaceChildren();
  if (!replies.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "生成后的回复会显示在这里。";
    list.append(empty);
    return;
  }
  for (const reply of replies) {
    const card = document.createElement("article");
    card.className = "reply";
    const text = document.createElement("p");
    text.textContent = reply;
    const copy = document.createElement("button");
    copy.textContent = "复制";
    copy.onclick = async () => {
      try {
        await navigator.clipboard.writeText(reply);
        write("status", "已复制到剪贴板。");
      } catch (error) {
        showError(error);
      }
    };
    card.append(text, copy);
    list.append(card);
  }
}

function renderHistory(records) {
  const history = byId("debug-history");
  history.replaceChildren();
  if (!records.length) {
    const empty = document.createElement("p");
    empty.textContent = "当前没有可显示的记录。";
    history.append(empty);
    return;
  }
  for (const record of records) {
    const line = document.createElement("p");
    const sender = document.createElement("strong");
    sender.textContent = record.Kind === "time" ? "时间" : record.From;
    line.append(sender, document.createTextNode(`　${record.Text}`));
    history.append(line);
  }
}

function resetReplies() {
  state.previous = [];
  renderReplies([]);
  write("debug-model", "尚未请求");
  write("debug-request", "生成后显示模型名称和 messages。");
}

async function refreshConversation(manual = false) {
  const previousTitle = state.lastTitle;
  state.snapshot = null;
  write("conversation", "正在识别…");
  write("debug-capture", "正在读取微信");
  write("debug-title", "—");
  write("debug-count", "0");
  try {
    const { snapshot, records } = await api("/api/capture", {});
    state.snapshot = snapshot;
    const title = snapshot.ConversationTitle || null;
    const changed = previousTitle !== undefined && previousTitle !== title;
    if (changed || !title) {
      byId("idea").value = "";
      byId("correction").value = "";
    }
    if (manual || changed || !title) resetReplies();
    state.lastTitle = title;
    const caption = title || "会话名称未识别";
    write("conversation", caption);
    write("debug-capture", records.length ? "已读取记录" : "已识别会话，暂无记录");
    write("debug-title", caption);
    write("debug-count", String(records.length));
    write("debug-raw", pretty(snapshot));
    renderHistory(records);
    if (previousTitle !== title) showBackground();
    return { title, previousTitle, changed };
  } catch (error) {
    resetReplies();
    renderHistory([]);
    write("conversation", "尚未识别");
    write("debug-capture", "读取失败");
    write("debug-raw", "尚无采集结果。");
    showBackground();
    throw error;
  }
}

async function run(action) {
  if (state.busy) return;
  setBusy(true);
  byId("debug-error-panel").hidden = true;
  try {
    await preferencesReady;
    await action();
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
}

async function generate(retry) {
  write("status", "正在确认当前微信会话…");
  const { title, previousTitle, changed } = await refreshConversation();
  if (!title) {
    write("status", "无法确认会话名称，未请求模型。请打开目标对话后重试。");
    return;
  }
  if (changed) {
    const previous = previousTitle ? `（原会话：${previousTitle}）` : "";
    write("status", `检测到会话切换到「${title}」${previous}。未请求模型，确认后请再点「生成回复」。`);
    return;
  }
  if (backgroundEnabled && !backgrounds.has(title)) {
    try {
      await requestBackground(title);
    } catch (error) {
      showError(error);
    }
  }
  write("status", "正在生成候选回复…");
  write("debug-model", "正在等待模型");
  write("debug-request", "正在构建请求并等待返回…");
  try {
    const { request, replies } = await api("/api/generate", {
      snapshot: state.snapshot,
      idea: byId("idea").value,
      correction: retry ? byId("correction").value : "",
      previous_replies: retry ? state.previous : [],
      background: backgroundEnabled ? backgrounds.get(title)?.text ?? "" : "",
    });
    state.previous = retry ? [...state.previous, ...replies] : replies;
    write("debug-request", pretty(request));
    write("debug-model", `已返回 ${replies.length} 条候选`);
    renderReplies(replies);
    write("status", `已生成 ${replies.length} 条候选。`);
  } catch (error) {
    write("debug-model", "生成失败");
    write("debug-request", error.details?.request ? pretty(error.details.request) : "未构建模型请求。");
    throw error;
  }
}

byId("open-debug").onclick = () => toggleDebug(byId("debug").hidden);
byId("close-debug").onclick = () => toggleDebug(false);
byId("refresh").onclick = () => run(async () => {
  write("status", "正在刷新微信会话…");
  const { title, changed } = await refreshConversation(true);
  write("status", title ? (changed ? `已切换到「${title}」。` : "会话已刷新。") : "已读取记录，但无法确认会话名称。");
});
byId("generate").onclick = () => run(() => generate(false));
byId("retry").onclick = () => run(() => generate(true));
byId("background").oninput = () => {
  const title = state.snapshot?.ConversationTitle;
  if (title) backgrounds.set(title, { ...backgrounds.get(title), text: byId("background").value });
};
byId("refresh-background").onclick = () => run(async () => {
  write("status", "正在确认当前微信会话…");
  const { title, changed } = await refreshConversation();
  if (!title || changed) {
    write("status", title ? `已切换到「${title}」，请确认后再点击刷新概括。` : "无法确认当前会话。");
    return;
  }
  await requestBackground(title);
  write("status", "对话背景已更新。");
});

function profileFields() {
  const selected = modelProfiles.profiles.find((profile) => profile.id === byId("profile").value);
  byId("profile-alias").value = selected?.alias || "";
  byId("profile-url").value = selected?.base_url || "";
  byId("profile-model").value = selected?.model || "";
  byId("profile-key").value = "";
  byId("profile-key").placeholder = selected?.has_api_key ? "已设置，留空保留现有 Key" : "输入 API Key";
  byId("activate-profile").disabled = !selected || selected.id === modelProfiles.active;
}

function showProfiles(data) {
  modelProfiles = data;
  const select = byId("profile");
  select.replaceChildren();
  for (const profile of data.profiles) {
    const option = document.createElement("option");
    option.value = profile.id;
    option.textContent = profile.alias || profile.model || "未命名模型";
    select.append(option);
  }
  const add = document.createElement("option");
  add.value = "";
  add.textContent = "新增模型配置…";
  select.append(add);
  const active = data.profiles.find((profile) => profile.id === data.active);
  select.value = active?.id || "";
  showActiveModel(data);
  profileFields();
}

function showActiveModel(data) {
  const active = data.profiles.find((profile) => profile.id === data.active);
  write("active-model", active ? `模型：${active.alias || active.model || "未配置"}` : "尚未选择模型");
}

async function updateProfile(payload) {
  byId("save-profile").disabled = true;
  byId("activate-profile").disabled = true;
  byId("profile").disabled = true;
  try {
    const data = await api("/api/profiles", payload);
    showProfiles(data);
    write("profile-status", "已保存，所选配置已启用。");
  } catch (error) {
    write("profile-status", error.message);
  } finally {
    byId("profile").disabled = false;
    byId("save-profile").disabled = false;
    const id = byId("profile").value;
    byId("activate-profile").disabled = !id || id === modelProfiles.active;
  }
}

byId("profile").onchange = () => {
  write("profile-status", "");
  profileFields();
};
byId("profile-form").onsubmit = (event) => {
  event.preventDefault();
  return updateProfile({
    id: byId("profile").value || null,
    alias: byId("profile-alias").value,
    base_url: byId("profile-url").value,
    model: byId("profile-model").value,
    api_key: byId("profile-key").value,
  });
};
byId("activate-profile").onclick = () => updateProfile({
  id: byId("profile").value,
  activate_only: true,
});
api("/api/profiles").then(showActiveModel).catch(() => write("active-model", "模型配置未加载"));
const preferencesReady = api("/api/preferences").then(applyPreferences).catch(() => {});

byId("open-settings").onclick = async () => {
  write("settings-error", "");
  write("profile-status", "");
  byId("settings").showModal();
  byId("save-settings").disabled = true;
  byId("save-profile").disabled = true;
  byId("profile").disabled = true;
  byId("activate-profile").disabled = true;
  const profiles = api("/api/profiles")
    .then(showProfiles)
    .catch((error) => write("profile-status", error.message))
    .finally(() => {
      byId("save-profile").disabled = false;
      byId("profile").disabled = false;
    });
  try {
    const data = await api("/api/preferences");
    applyPreferences(data);
    byId("count").value = data.count;
    byId("personal").value = data.personal_style;
    byId("style").value = data.reply_style;
  } catch (error) {
    write("settings-error", error.message);
  } finally {
    byId("save-settings").disabled = false;
  }
  await profiles;
};
byId("close-settings").onclick = () => byId("settings").close();
byId("settings-form").onsubmit = async (event) => {
  event.preventDefault();
  byId("save-settings").disabled = true;
  try {
    const saved = await api("/api/preferences", {
      count: Number(byId("count").value),
      personal_style: byId("personal").value,
      reply_style: byId("style").value,
      background_enabled: byId("background-enabled").checked,
    });
    applyPreferences(saved);
    byId("settings").close();
  } catch (error) {
    write("settings-error", error.message);
  } finally {
    byId("save-settings").disabled = false;
  }
};
