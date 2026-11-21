package com.autoreply.mobile;

import android.content.SharedPreferences;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ReplyApi {
    private final SharedPreferences store;
    private final AssetManager assets;

    ReplyApi(SharedPreferences store, AssetManager assets) {
        this.store = store;
        this.assets = assets;
    }

    JSONObject request(String path, String body) {
        JSONObject result = new JSONObject();
        try {
            JSONObject input = body == null ? null : new JSONObject(body);
            switch (path) {
                case "/api/preferences":
                    return preferences(input);
                case "/api/profiles":
                    return profiles(input);
                case "/api/background":
                    generate(input, result, true);
                    break;
                case "/api/generate":
                    generate(input, result, false);
                    break;
                default:
                    throw new IllegalArgumentException("接口不存在。");
            }
        } catch (IllegalArgumentException error) {
            putError(result, error.getMessage());
        } catch (JSONException error) {
            putError(result, "无法处理 AutoReply 数据。");
        } catch (IOException error) {
            putError(result, "无法连接模型接口，或请求超时。");
        }
        return result;
    }

    private JSONObject preferences(JSONObject input) throws JSONException {
        if (input != null) {
            int count = input.getInt("count");
            if (count < 1 || count > 5) {
                throw new IllegalArgumentException("候选回复条数必须在 1 到 5 之间。");
            }
            save("preferences", new JSONObject()
                .put("count", count)
                .put("personal_style", input.getString("personal_style"))
                .put("reply_style", input.getString("reply_style"))
                .put("background_enabled", input.getBoolean("background_enabled")));
        }
        return new JSONObject(store.getString("preferences", "{\"count\":3,\"personal_style\":\"\","
            + "\"reply_style\":\"\",\"background_enabled\":false}"));
    }

    private JSONObject profiles(JSONObject input) throws JSONException {
        JSONObject saved = new JSONObject(store.getString("profiles", "{\"profiles\":[],\"active\":null}"));
        JSONArray list = saved.getJSONArray("profiles");
        if (input != null) {
            String id = input.isNull("id") ? null : input.getString("id");
            JSONObject item = find(list, id);
            if (id != null && item == null) {
                throw new IllegalArgumentException("所选模型配置已不存在。");
            }
            if (input.optBoolean("activate_only")) {
                if (item == null) throw new IllegalArgumentException("请选择已有的模型配置。");
            } else {
                String alias = input.getString("alias").trim();
                String base = input.getString("base_url").trim();
                String model = input.getString("model").trim();
                String key = input.getString("api_key").trim();
                if (key.isEmpty() && item != null) key = item.getString("api_key");
                endpoint(base);
                if (model.isEmpty() || key.isEmpty() || key.contains("\r") || key.contains("\n")) {
                    throw new IllegalArgumentException("请输入有效的模型 ID 和 API Key。");
                }
                if (item == null) {
                    item = new JSONObject().put("id", UUID.randomUUID().toString());
                    list.put(item);
                }
                item.put("alias", alias.isEmpty() ? model : alias)
                    .put("base_url", base).put("model", model).put("api_key", key);
            }
            saved.put("active", item.getString("id"));
            save("profiles", saved);
        }
        JSONArray visible = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            visible.put(new JSONObject()
                .put("id", item.getString("id")).put("alias", item.getString("alias"))
                .put("base_url", item.getString("base_url")).put("model", item.getString("model"))
                .put("has_api_key", !item.getString("api_key").isEmpty()));
        }
        return new JSONObject().put("active", saved.get("active")).put("profiles", visible);
    }

    private void generate(JSONObject input, JSONObject result, boolean background)
        throws JSONException, IOException {
        JSONObject preferences = preferences(null);
        boolean backgroundEnabled = preferences.getBoolean("background_enabled");
        if (background && !backgroundEnabled) throw new IllegalArgumentException("请先开启对话背景。");
        JSONArray records = input.getJSONObject("snapshot").getJSONArray("Records");
        boolean hasMessage = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if ("message".equals(record.getString("Kind")) && !record.getString("Text").trim().isEmpty()) {
                hasMessage = true;
                break;
            }
        }
        if (!hasMessage) throw new IllegalArgumentException("请先导入有文字消息的聊天记录。");

        JSONObject saved = new JSONObject(store.getString("profiles", "{\"profiles\":[],\"active\":null}"));
        JSONObject profile = find(saved.getJSONArray("profiles"), saved.optString("active", null));
        if (profile == null) throw new IllegalArgumentException("请先在设置中配置并启用模型。");

        JSONObject data = new JSONObject().put("records", records);
        JSONArray previous = input.optJSONArray("previous_replies");
        int count = preferences.getInt("count");
        if (!background) {
            data.put("reply_count", count);
            addText(data, "personal_style", preferences);
            addText(data, "reply_style", preferences);
            addText(data, "idea", input);
            addText(data, "correction", input);
            if (previous != null && previous.length() > 0) data.put("previous_replies", previous);
            if (backgroundEnabled) {
                String text = input.optString("background").trim();
                if (!text.isEmpty()) data.put("conversation_background", text);
            }
        }
        String prompt;
        try (InputStream stream = assets.open("prompts.json")) {
            prompt = new JSONObject(readText(stream)).getString(background ? "background" : "reply");
        }
        JSONArray messages = new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", prompt))
            .put(new JSONObject().put("role", "user").put("content", data.toString()));
        JSONObject request = new JSONObject().put("messages", messages).put("model", profile.getString("model"));
        result.put("request", request);
        String response = complete(profile, request);
        if (background) {
            if (response.isEmpty()) throw new IllegalArgumentException("模型没有返回可用的对话背景。");
            result.put("background", response);
        } else {
            result.put("replies", parseReplies(response, previous, count));
        }
    }

    private static String complete(JSONObject profile, JSONObject request) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) endpoint(profile.getString("base_url"))
            .toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + profile.getString("api_key"));
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("模型 API 请求失败（HTTP " + status + "）。");
            }
            try (InputStream stream = connection.getInputStream()) {
                Object content = new JSONObject(readText(stream)).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").get("content");
                if (!(content instanceof String)) throw new JSONException("Expected text content");
                return ((String) content).trim();
            } catch (JSONException error) {
                throw new IllegalArgumentException("模型返回格式有误，无法提取正文。");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray parseReplies(String response, JSONArray previous, int count) {
        Set<String> seen = new HashSet<>();
        JSONArray cleaned = new JSONArray();
        try {
            String[] lines = response.split("\\r?\\n");
            if (lines.length >= 3 && (lines[0].equalsIgnoreCase("```json") || lines[0].equals("```"))
                && lines[lines.length - 1].equals("```")) {
                response = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length - 1));
            }
            if (previous != null) {
                for (int i = 0; i < previous.length(); i++) seen.add(replyKey(previous.getString(i)));
            }
            JSONArray replies = new JSONObject(response).getJSONArray("replies");
            for (int i = 0; i < replies.length(); i++) {
                Object item = replies.get(i);
                if (!(item instanceof String)) throw new JSONException("Expected reply text");
                String text = ((String) item).trim();
                String key = replyKey(text);
                if (cleaned.length() < count && !key.isEmpty() && seen.add(key)) cleaned.put(text);
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("模型返回格式有误，无法提取候选回复。");
        }
        if (cleaned.length() == 0) throw new IllegalArgumentException("模型没有返回可用的候选回复。");
        return cleaned;
    }

    private static String replyKey(String text) {
        return text.replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }

    private static URI endpoint(String base) {
        try {
            URI uri = new URI(base);
            String scheme = uri.getScheme();
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new URISyntaxException("", "Invalid base URL");
            }
            String path = uri.getRawPath().replaceFirst("/+$", "");
            if (!path.endsWith("/chat/completions")) path += "/chat/completions";
            return new URI(scheme + "://" + uri.getRawAuthority() + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS Base URL。");
        }
    }

    private static String readText(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 2_000_000) {
                throw new IllegalArgumentException("模型返回内容过大。");
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }

    private static JSONObject find(JSONArray list, String id) throws JSONException {
        if (id == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (id.equals(item.getString("id"))) return item;
        }
        return null;
    }

    private static void addText(JSONObject target, String name, JSONObject source) throws JSONException {
        String text = source.optString(name).trim();
        if (!text.isEmpty()) target.put(name, text);
    }

    private void save(String key, JSONObject value) {
        if (!store.edit().putString(key, value.toString()).commit()) {
            throw new IllegalArgumentException("无法保存配置，请检查设备存储。");
        }
    }

    private static void putError(JSONObject result, String text) {
        try {
            result.put("error", text);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
