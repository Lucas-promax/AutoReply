(() => {
  const native = window.AutoReplyNative;
  const requests = new Map();
  let serial = 0;
  window.autoReplyResponse = (id, json) => {
    const resolve = requests.get(id);
    if (!resolve) return;
    requests.delete(id);
    resolve(JSON.parse(json));
  };

  const roles = { 我: "outgoing", 对方: "incoming", 未知: "unknown", 时间: "time" };
  const rolePrefix = /^(我|对方|未知|时间)\s*[:：]\s*/;
  const timeLabel = /^(?:(?:今天|昨天|前天|(?:星期|周)[一二三四五六日天]|(?:\d{4}年)?\d{1,2}月\d{1,2}日)\s*|\d{4}([-\/])\d{1,2}\1\d{1,2}\s+)?(?:(?:凌晨|早上|上午|中午|下午|晚上)\s*)?(?:[01]?\d|2[0-3])[:：][0-5]\d$/;

  function formatCapture(records) {
    return records.map(({ text, direction }) => {
      let role = { outgoing: "我", incoming: "对方" }[direction] || "未知";
      if (timeLabel.test(text.trim())) role = "时间";
      const escaped = text.split(/\r?\n/).map((line, index) => (
        index && (rolePrefix.test(line) || line.startsWith("\\")) ? "\\" + line : line
      ));
      return role + "：" + escaped.join("\n");
    }).join("\n");
  }

  function importRecords(text, title) {
    const rows = [];
    let previous = null;
    for (const line of text.split(/\r?\n/)) {
      const prefix = line.match(rolePrefix);
      if (prefix) {
        previous = { direction: roles[prefix[1]], text: line.slice(prefix[0].length) };
        rows.push(previous);
      } else if (previous) {
        previous.text += "\n" + (line.startsWith("\\") ? line.slice(1) : line);
      } else if (line.trim()) {
        rows.push({ direction: timeLabel.test(line.trim()) ? "time" : "unknown", text: line });
      }
    }
    const senders = { outgoing: "我", incoming: title || "对方", unknown: "未知", time: "System" };
    return rows.filter((row) => row.text.trim()).map(({ direction, text }) => ({
      Kind: direction === "time" ? "time" : "message",
      Text: text.trim(),
      From: senders[direction],
    }));
  }

  let setBusy = () => {};
  window.autoReplyPlatform = {
    request(path, payload) {
      return new Promise((resolve) => {
        const id = String(++serial);
        requests.set(id, resolve);
        native.request(id, path, payload === undefined ? null : JSON.stringify(payload));
      });
    },
    copy(text) { native.copy(text); },
    setBusy(busy) { setBusy(busy); },
    connect(onImport) {
      const byId = (id) => document.getElementById(id);
      const dialog = byId("import-chat");
      const settings = byId("settings");
      const titleField = byId("import-title");
      const input = byId("import-text");
      const edit = byId("refresh");
      const paste = byId("paste-chat");
      let current = null;
      let source = "manual";
      let busy = false;

      byId("pin-window").hidden = true;
      byId("status").textContent = "粘贴聊天文字，或在微信里点击「读取聊天」。";
      edit.textContent = "编辑记录";
      edit.hidden = true;

      function openImport({ title = "", text = "", from = "manual", hint }) {
        source = from;
        titleField.value = title;
        input.value = text;
        byId("import-hint").textContent = hint;
        byId("import-error").textContent = "";
        dialog.showModal();
      }

      function offerPending() {
        if (busy || dialog.open || settings.open) return;
        const pending = native.takeImport();
        if (!pending) return;
        const { title, text, records, capture_error: captureError } = JSON.parse(pending);
        if (captureError) {
          openImport({ hint: captureError });
          return;
        }
        openImport({
          title,
          text: records ? formatCapture(records) : text,
          from: records ? "wechat" : "share",
          hint: records ? "请核对从微信读取的文字和发言人。" : "请核对从其他应用分享的文字。",
        });
      }

      setBusy = (value) => {
        busy = value;
        paste.disabled = value;
        byId("open-wechat").disabled = value;
        byId("enable-wechat").disabled = value;
        if (!value) offerPending();
      };

      paste.onclick = () => openImport({ hint: "长按输入框粘贴已复制的聊天文字。" });
      edit.onclick = () => {
        if (current) openImport({ ...current, hint: "可在生成前修改聊天记录。" });
      };
      byId("open-wechat").onclick = () => native.openWeChat();
      byId("enable-wechat").onclick = () => native.openAccessibilitySettings();
      byId("cancel-import").onclick = () => dialog.close();
      byId("import-form").onsubmit = (event) => {
        event.preventDefault();
        const title = titleField.value.trim();
        const records = importRecords(input.value, title);
        if (!records.some((record) => record.Kind === "message")) {
          byId("import-error").textContent = "请输入至少一条聊天消息。";
          return;
        }
        current = { title, text: input.value, from: source };
        edit.hidden = false;
        dialog.close();
        onImport({
          Source: source,
          CapturedAtUtc: new Date().toISOString(),
          ConversationTitle: title || (source === "wechat" ? "微信会话" : "导入的聊天"),
          Records: records,
        });
      };

      dialog.addEventListener("close", offerPending);
      settings.addEventListener("close", offerPending);
      window.autoReplyImportAvailable = offerPending;
      window.autoReplyBack = () => {
        if (dialog.open) {
          dialog.close();
          return true;
        }
        if (settings.open) {
          settings.dispatchEvent(new Event("cancel", { cancelable: true }));
          return true;
        }
        return false;
      };
      offerPending();
    },
  };
})();
