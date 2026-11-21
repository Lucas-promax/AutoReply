package com.autoreply.mobile;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class WeChatAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = this::updateOverlay;
    private WindowManager manager;
    private TextView overlay;

    @Override
    protected void onServiceConnected() {
        manager = getSystemService(WindowManager.class);
        updateOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        handler.removeCallbacks(update);
        handler.postDelayed(update, 100);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        handler.removeCallbacks(update);
        hideOverlay();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(update);
        hideOverlay();
        super.onDestroy();
    }

    private AccessibilityNodeInfo currentWeChat() {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                int type = window.getType();
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && window.isActive()) return null;
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                    || (!window.isActive() && !window.isFocused())) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence owner = root.getPackageName();
                if (owner != null && MainActivity.WECHAT.contentEquals(owner)) return root;
                root.recycle();
                return null;
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private void updateOverlay() {
        if (manager == null) return;
        AccessibilityNodeInfo root = currentWeChat();
        if (root == null) {
            hideOverlay();
            return;
        }
        root.recycle();
        if (overlay != null) return;

        TextView button = new TextView(this);
        button.setText("读取聊天");
        button.setTextColor(0xffffffff);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff146449);
        background.setCornerRadius(dp(24));
        button.setBackground(background);
        button.setElevation(dp(4));
        button.setOnClickListener(view -> capture());

        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.RIGHT;
        layout.x = dp(10);
        layout.y = dp(110);
        manager.addView(button, layout);
        overlay = button;
    }

    private void hideOverlay() {
        if (overlay == null) return;
        manager.removeViewImmediate(overlay);
        overlay = null;
    }

    private void capture() {
        AccessibilityNodeInfo root = currentWeChat();
        JSONObject result;
        if (root == null) {
            hideOverlay();
            result = failure("微信不在前台，无法读取。请打开目标会话后再试，也可以在这里粘贴聊天。");
        } else {
            try {
                result = readChat(root);
            } catch (JSONException | IllegalStateException error) {
                result = failure("读取微信界面时出错。可以在这里粘贴聊天。");
            } finally {
                root.recycle();
            }
        }
        MainActivity.openCapture(this, result);
    }

    private static JSONObject failure(String message) {
        return new JSONObject(Collections.singletonMap("capture_error", message));
    }

    private JSONObject readChat(AccessibilityNodeInfo root) throws JSONException {
        UiNode screen = UiNode.read(root, 0, new int[] { 0 });
        List<UiNode> nodes = new ArrayList<>();
        screen.flatten(nodes);
        Rect area = screen.bounds;
        if (area.isEmpty()) return failure("系统没有提供微信窗口内容。可以在这里粘贴聊天。");

        int composer = composerTop(nodes, area);
        UiNode list = null;
        int listHeight = 0;
        int headerBottom = area.top + dp(54);
        boolean hasChatHeader = false;
        for (UiNode node : nodes) {
            if (node.visible && node.control && node.isChatHeader()
                && node.bounds.centerY() <= area.top + dp(96)
                && node.bounds.centerX() >= area.left + area.width() * 0.72) {
                hasChatHeader = true;
                headerBottom = Math.max(headerBottom, node.bounds.bottom);
            }
        }
        for (UiNode node : nodes) {
            Rect bounds = node.bounds;
            int height = Math.min(bounds.bottom, composer) - Math.max(bounds.top, headerBottom);
            if (node.visible && node.list && bounds.width() >= area.width() * 0.5
                && height >= dp(40) && !node.children.isEmpty() && height > listHeight) {
                list = node;
                listHeight = height;
            }
        }
        if (composer == Integer.MAX_VALUE && !hasChatHeader) {
            return failure("未识别到微信聊天页的输入栏或聊天信息。请确认已进入单聊或群聊，也可以在这里粘贴聊天。");
        }

        int fallbackTop = Math.max(headerBottom, area.top + dp(80));
        int contentTop = list == null || list.bounds.top < area.top + dp(32)
            ? fallbackTop : Math.max(list.bounds.top, headerBottom);
        int contentBottom = composer == Integer.MAX_VALUE ? area.bottom - dp(54) : composer;
        Rect clip = new Rect(area.left, contentTop, area.right, contentBottom);
        if (list != null) clip.bottom = Math.min(clip.bottom, list.bounds.bottom);

        List<ChatRow> rows = new ArrayList<>();
        if (list != null && !clip.isEmpty()) {
            for (UiNode row : list.children) {
                List<UiNode> parts = new ArrayList<>();
                row.readText(parts, clip);
                if (!parts.isEmpty()) rows.add(new ChatRow(parts));
            }
        }
        if (rows.isEmpty()) {
            // Some WeChat layouts expose individual bubbles without a scrollable list.
            clip.set(area.left, fallbackTop, area.right, contentBottom);
            List<UiNode> parts = new ArrayList<>();
            screen.readText(parts, clip);
            for (UiNode part : parts) rows.add(new ChatRow(Collections.singletonList(part)));
        }
        if (rows.isEmpty()) {
            return failure("已定位聊天页，但微信没有提供可读取的消息文字。可以在这里粘贴聊天。");
        }

        rows.sort(Comparator.comparingInt(row -> row.body.bounds.top));
        JSONArray records = new JSONArray();
        int textLength = 0;
        for (ChatRow row : rows) {
            int maxLength = Math.min(6000, 30000 - textLength);
            if (maxLength <= 0 || records.length() >= 200) break;
            String text = row.text.substring(0, Math.min(row.text.length(), maxLength));
            textLength += text.length();
            int center = row.body.bounds.centerX();
            String direction = "unknown";
            if (center < area.left + area.width() * 0.42) direction = "incoming";
            if (center > area.right - area.width() * 0.42) direction = "outgoing";
            records.put(new JSONObject().put("text", text).put("direction", direction));
        }
        return new JSONObject()
            .put("title", conversationTitle(nodes, area, contentTop))
            .put("records", records);
    }

    private int composerTop(List<UiNode> nodes, Rect area) {
        int editableTop = Integer.MAX_VALUE;
        int controlsTop = Integer.MAX_VALUE;
        List<UiNode> controls = new ArrayList<>();
        for (UiNode node : nodes) {
            Rect bounds = node.bounds;
            if (!node.visible || bounds.centerY() <= area.top + area.height() * 0.4) continue;
            if (node.editable && bounds.width() >= area.width() * 0.14) {
                editableTop = Math.min(editableTop, bounds.top);
            }
            if (bounds.width() > area.width() * 0.8 || bounds.height() > dp(100)) continue;
            String control = node.composerControl();
            if (control.isEmpty()) continue;
            if (control.equals("voice")) controlsTop = Math.min(controlsTop, bounds.top);
            for (UiNode other : controls) {
                if (Math.abs(bounds.centerY() - other.bounds.centerY()) <= dp(32)
                    && !control.equals(other.composerControl())) {
                    controlsTop = Math.min(controlsTop, Math.min(bounds.top, other.bounds.top));
                }
            }
            controls.add(node);
        }
        return editableTop != Integer.MAX_VALUE ? editableTop : controlsTop;
    }

    private String conversationTitle(List<UiNode> nodes, Rect area, int contentTop) {
        String title = "";
        int titleDistance = Integer.MAX_VALUE;
        for (UiNode node : nodes) {
            if (!node.visible || node.text.isEmpty() || node.text.length() > 100
                || node.text.contains("\n") || node.isChatHeader()) continue;
            Rect bounds = node.bounds;
            if (bounds.bottom > Math.min(contentTop, area.top + dp(112))
                || bounds.left < area.left + dp(36) || bounds.right > area.right - dp(36)) continue;
            int distance = Math.abs(bounds.centerX() - area.centerX());
            if (distance < titleDistance) {
                title = node.text;
                titleDistance = distance;
            }
        }
        return title;
    }

    private static final class ChatRow {
        final UiNode body;
        final String text;

        ChatRow(List<UiNode> parts) {
            UiNode longest = parts.get(0);
            StringBuilder joined = new StringBuilder();
            for (UiNode part : parts) {
                if (joined.length() > 0) joined.append('\n');
                joined.append(part.label);
                if (part.label.length() >= longest.label.length()) longest = part;
            }
            body = longest;
            text = joined.toString();
        }
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private static final class UiNode {
        final Rect bounds = new Rect();
        final List<UiNode> children = new ArrayList<>();
        final String text;
        final String label;
        final boolean visible;
        final boolean editable;
        final boolean list;
        final boolean control;

        private UiNode(AccessibilityNodeInfo source) {
            source.getBoundsInScreen(bounds);
            text = plain(source.getText());
            label = text.isEmpty() ? plain(source.getContentDescription()) : text;
            String type = plain(source.getClassName());
            visible = source.isVisibleToUser();
            editable = source.isEditable() || type.endsWith("EditText");
            list = source.isScrollable() || type.endsWith("RecyclerView") || type.endsWith("ListView");
            control = source.isClickable() || type.endsWith("Button") || text.isEmpty();
        }

        static UiNode read(AccessibilityNodeInfo source, int depth, int[] count) {
            UiNode result = new UiNode(source);
            if (++count[0] >= 3000 || depth >= 40) return result;
            for (int i = 0; i < source.getChildCount() && count[0] < 3000; i++) {
                AccessibilityNodeInfo child = source.getChild(i);
                if (child == null) continue;
                try {
                    if (child.isVisibleToUser() || child.getChildCount() > 0) {
                        result.children.add(read(child, depth + 1, count));
                    }
                } finally {
                    child.recycle();
                }
            }
            return result;
        }

        void flatten(List<UiNode> nodes) {
            nodes.add(this);
            for (UiNode child : children) child.flatten(nodes);
        }

        boolean isChatHeader() {
            String name = label.toLowerCase(Locale.ROOT);
            return name.equals("聊天信息") || name.equals("聊天详情") || name.equals("chat info");
        }

        String composerControl() {
            if (!control) return "";
            switch (label.replace(" ", "").toLowerCase(Locale.ROOT)) {
                case "按住说话":
                case "切换到按住说话":
                case "切换到语音":
                case "切换到键盘":
                case "holdtotalk":
                case "switchtovoice":
                case "switchtokeyboard":
                    return "voice";
                case "表情":
                case "emoji":
                case "emoticons":
                    return "emoji";
                case "更多功能":
                case "附件":
                case "morefunctions":
                case "attachments":
                    return "attachment";
                case "发送":
                case "send":
                    return "send";
                default:
                    return "";
            }
        }

        void readText(List<UiNode> result, Rect clip) {
            if (editable || (visible && !Rect.intersects(bounds, clip))) return;
            boolean inChat = visible && clip.contains(bounds.centerX(), bounds.centerY());
            if (inChat && !text.isEmpty()) {
                result.add(this);
                return;
            }
            int size = result.size();
            for (UiNode child : children) child.readText(result, clip);
            if (result.size() == size && inChat && !label.isEmpty() && composerControl().isEmpty()
                && !isChatHeader() && !label.endsWith("头像") && !label.equals("返回")) {
                result.add(this);
            }
        }

        private static String plain(CharSequence text) {
            return text == null ? "" : text.toString().trim();
        }
    }
}
