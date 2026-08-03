package com.hydroyura.mcp.client.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OllamaConfig {

    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public OllamaChatModel ollamaChatModel(OllamaApi ollamaApi) {
        return OllamaChatModel.builder()
            .ollamaApi(ollamaApi)
            .options(OllamaChatOptions.builder()
                .model("qwen2.5:1.5b")
                .build())
            .build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(OllamaChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
