package com.example.group.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@Data
@PropertySource("application.properties")
public class BotConfig {

    @Value("${bot.name}")
    String botName;

    @Value("${bot.token}")
    String token;

    @Value("${mainbot.api.base-url}")
    String apiUrl;

    @Value("${mainbot.username}")
    String mainBotUsername;

    @Value("${group.chat-id}")
    Long groupChatId;
}
