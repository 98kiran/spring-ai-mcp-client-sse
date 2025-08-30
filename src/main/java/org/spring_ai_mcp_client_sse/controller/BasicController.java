package org.spring_ai_mcp_client_sse.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BasicController {
    private final ChatClient chatClient;

    public BasicController(ChatClient.Builder chatClientBuilder, ToolCallbackProvider tools) {
        this.chatClient = chatClientBuilder
                .defaultSystem(
                        """
            You are a helpful assistant.
            Decide WHETHER to call tools.
            - Call getStockPrice ONLY when the user explicitly asks for a stock price/quote or gives a clear ticker/company.
            - For greetings, capability questions, or casual chat, DO NOT call any tools; just reply normally.
            - Never guess a ticker from unrelated text.
            """
                        )
                .defaultToolCallbacks(tools)
                .build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String query) {
        PromptTemplate promptTemplate = new PromptTemplate(query);
        Prompt prompt = promptTemplate.create();
        ChatClient.CallResponseSpec response = chatClient.prompt(prompt).call();
        return response.content();
    }
}
