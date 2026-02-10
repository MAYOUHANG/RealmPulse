package com.realmpulse;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class DeepSeekService {

    public enum ProfileType {
        QA,
        SUMMARY
    }

    private final JavaPlugin plugin;
    private final PluginConfigService configService;
    private final Gson gson = new Gson();
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);

    public DeepSeekService(JavaPlugin plugin, PluginConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    public void askAI(String userMessage, Consumer<String> callback) {
        askQA(userMessage, callback);
    }

    public void askQA(String userMessage, Consumer<String> callback) {
        ask(userMessage, ProfileType.QA, callback);
    }

    public void askSummary(String userMessage, Consumer<String> callback) {
        ask(userMessage, ProfileType.SUMMARY, callback);
    }

    private void ask(String userMessage, ProfileType profileType, Consumer<String> callback) {
        AiProfile profile = loadProfile(profileType);
        if (!profile.enabled() || profile.apiUrl().isBlank() || profile.model().isBlank()) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(""));
            return;
        }
        int maxInFlight = Math.max(1, configService.getInt("ai.request.max-in-flight", 24));
        if (inFlightRequests.incrementAndGet() > maxInFlight) {
            inFlightRequests.decrementAndGet();
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(""));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String responseText = "";
            try {
                int maxRetries = resolveRetries(profileType);
                long baseBackoffMs = Math.max(50L, configService.getLong("ai.request.retry-backoff-ms", 350L));
                int connectTimeoutMs = Math.max(2000, configService.getInt("ai.request.connect-timeout-ms", 10000));
                int readTimeoutMs = Math.max(connectTimeoutMs, configService.getInt("ai.request.read-timeout-ms", 15000));
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    responseText = requestOnce(userMessage, profile, connectTimeoutMs, readTimeoutMs);
                    if (!responseText.isBlank()) {
                        break;
                    }
                    if (attempt < maxRetries) {
                        long backoffMs = baseBackoffMs * (attempt + 1L);
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } finally {
                inFlightRequests.decrementAndGet();
            }

            String finalResponse = responseText;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(finalResponse));
        });
    }

    private String requestOnce(String userMessage, AiProfile profile, int connectTimeoutMs, int readTimeoutMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", profile.model());
        JsonArray messages = new JsonArray();
        if (!profile.systemPrompt().isBlank()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", profile.systemPrompt());
            messages.add(systemMessage);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);
        payload.add("messages", messages);
        payload.addProperty("max_tokens", profile.maxTokens());

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(profile.apiUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            if (!profile.apiKey().isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + profile.apiKey());
            }
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);

            byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return "";
            }
            try (InputStream inputStream = connection.getInputStream()) {
                String responseBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return parseResponse(responseBody);
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int resolveRetries(ProfileType profileType) {
        int globalRetries = Math.max(0, configService.getInt("ai.request.max-retries", 1));
        if (profileType == ProfileType.QA) {
            return Math.max(0, configService.getInt("ai.qa.request-retries", globalRetries));
        }
        return Math.max(0, configService.getInt("ai.summary.request-retries", globalRetries));
    }

    private AiProfile loadProfile(ProfileType profileType) {
        boolean globalEnabled = configService.getBoolean("ai.enabled", true);
        if (profileType == ProfileType.QA) {
            return new AiProfile(
                globalEnabled && configService.getBoolean("ai.qa.enabled", true),
                configService.getString("ai.qa.api-url", ""),
                configService.getString("ai.qa.api-key", ""),
                configService.getString("ai.qa.model", ""),
                configService.getString("ai.qa.system-prompt", ""),
                Math.max(16, configService.getInt("ai.qa.max-tokens", 64))
            );
        }

        return new AiProfile(
            globalEnabled && configService.getBoolean("ai.summary.enabled", true),
            configService.getString("ai.summary.api-url", ""),
            configService.getString("ai.summary.api-key", ""),
            configService.getString("ai.summary.model", ""),
            configService.getString("ai.summary.system-prompt", ""),
            Math.max(32, configService.getInt("ai.summary.max-tokens", 220))
        );
    }

    private record AiProfile(
        boolean enabled,
        String apiUrl,
        String apiKey,
        String model,
        String systemPrompt,
        int maxTokens
    ) {
    }

    private String parseResponse(String responseBody) {
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);
        if (root == null || !root.has("choices")) {
            return "";
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return "";
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (choice == null || !choice.has("message")) {
            return "";
        }
        JsonObject message = choice.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return "";
        }
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray parts = content.getAsJsonArray();
            for (JsonElement part : parts) {
                if (part == null || part.isJsonNull()) {
                    continue;
                }
                if (part.isJsonPrimitive()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part.getAsString());
                    continue;
                }
                if (part.isJsonObject()) {
                    JsonObject partObject = part.getAsJsonObject();
                    if (partObject.has("text") && !partObject.get("text").isJsonNull()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(partObject.get("text").getAsString());
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }
}
