package com.hydroyura.mcp.client.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  SyncMcpToolCallbackProvider toolCallbackProvider) {
        return builder
            .defaultTools(toolCallbackProvider.getToolCallbacks())
            .build();
    }
}
