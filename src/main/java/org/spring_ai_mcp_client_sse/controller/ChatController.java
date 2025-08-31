package org.spring_ai_mcp_client_sse.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@RestController
public class ChatController {

    private final ChatClient chatNoTools;
    private final ChatClient chatWithTools;
    private final ChatMemory memory;

    private static final String SYSTEM_MSG = """
        You are a helpful assistant.
        
        Memory & context rules (important):
        - Always use the conversation history provided by memory to resolve follow-ups, pronouns, confirmations, and edge cases.
        - If the user previously gave facts (e.g., name, choices, a ticker they meant, prior answer you produced), prefer those over guessing.
        - Do NOT ask the user to repeat info already present in memory unless it is contradictory or ambiguous.
        - If context is ambiguous after checking memory, briefly say what’s missing and ask one concise question.
        
        Tool use:
        - Call getStockPrice ONLY when the user explicitly asks for a stock price/quote or gives a clear ticker/company.
        - Call braveSearch ONLY when the user explicitly asks to look something up online (e.g., "search", "find", "look up", "latest on ...").
        - Call getServerDateTime for date/time requests (e.g., "what's the date", "current time").
        - Do NOT call tools for greetings or generic capability questions.
        - Never guess a ticker from unrelated text.
        
        Answering style:
        - Be concise and specific. If prior context answers the question, use it directly.
        - If memory may be stale or uncertain, say so briefly.
        """;

    public ChatController(ChatClient.Builder chatClientBuilder, ToolCallbackProvider tools) {
        this.chatNoTools = chatClientBuilder.build();
        this.chatWithTools = chatClientBuilder.defaultToolCallbacks(tools).build();

        ChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        this.memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(20)
                .build();
    }

    private static final Pattern STOCK_CUES = Pattern.compile(
            "(?i)\\b(price|quote|stock|share|ticker|trading|market|symbol|usd)\\b");
    private static final Pattern TICKERISH = Pattern.compile("(?i)\\b[A-Z]{1,5}(?:\\.[A-Z]{1,3})?\\b");
    private static final Pattern DATE_TIME = Pattern.compile(
            "(?i)\\b(what(?:'s| is)?\\s+the\\s+date|today(?:'s)?\\s+date|current\\s+date|what\\s+day\\s+is\\s+it|time\\s+now|current\\s+time)\\b");
    private static final Pattern PERSONAL  = Pattern.compile(
            "(?i)\\b(my name|who am i|how old am i|my age|where do i live|my email)\\b");
    private static final Pattern SEARCHY   = Pattern.compile(
            "(?i)\\b(search|look\\s*up|find|brave|latest|news|results|articles?)\\b");

    private boolean needsTools(String q) {
        if (q == null) return false;
        String s = q.trim();
        if (s.isEmpty()) return false;

        if (PERSONAL.matcher(s).find()) return false;

        return STOCK_CUES.matcher(s).find()
                || TICKERISH.matcher(s).matches()
                || SEARCHY.matcher(s).find()
                || DATE_TIME.matcher(s).find();
    }

    public record ChatResponse(String cid, String message) {}

    @GetMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestParam String query,
                             @RequestParam(value = "cid", required = false) String cid) {

        String conversationId = (cid == null || cid.isBlank()) ? UUID.randomUUID().toString() : cid;

        boolean useTools = needsTools(query);
        ChatClient client = useTools ? chatWithTools : chatNoTools;

        log.info("Incoming query='{}' | cid={} | usingTools={}", query, conversationId, useTools);

        var memoryAdvisor = MessageChatMemoryAdvisor.builder(memory)
                .conversationId(conversationId)
                .build();

        PromptTemplate promptTemplate = new PromptTemplate(query);
        Prompt prompt = promptTemplate.create();

        ChatClient.CallResponseSpec response = client.prompt(prompt)
                .system(SYSTEM_MSG)
                .advisors(memoryAdvisor)
                .call();

        String answer = response.content();
//        log.info("Response for cid={}: {}", conversationId, answer);

        return new ChatResponse(conversationId, answer);
    }
}