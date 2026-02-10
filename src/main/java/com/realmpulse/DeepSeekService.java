package com.realmpulse;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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

            String responseText = "";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(profile.apiUrl()).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                if (!profile.apiKey().isBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer " + profile.apiKey());
                }
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);

                byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    String responseBody;
                    try (InputStream inputStream = connection.getInputStream()) {
                        responseBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    responseText = parseResponse(responseBody);
                } else {
                    responseText = "";
                }
            } catch (Exception e) {
                responseText = "";
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            String finalResponse = responseText;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(finalResponse));
        });
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
        return message.get("content").getAsString();
    }
}
