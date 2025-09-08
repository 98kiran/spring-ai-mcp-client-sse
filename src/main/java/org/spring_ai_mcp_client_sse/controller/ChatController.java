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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller that exposes a simple /chat endpoint.  It routes user queries to the underlying
 * {@link ChatClient}, optionally enabling tool support when the input contains cues such as
 * stock tickers, date/time requests, search queries or image generation requests.  It also
 * attempts to extract base64‑encoded image data URIs from the assistant’s response and
 * return them separately to the client.
 */
@Slf4j
@RestController
public class ChatController {

    private final ChatClient chatNoTools;
    private final ChatClient chatWithTools;
    private final ChatMemory memory;

    /**
     * System prompt defining the assistant’s personality and how to use available tools.
     */
    private static final String SYSTEM_MSG = """
            You are Violet, an helpful assistant.
            
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
            - Call generateImage ONLY when the user explicitly asks to create an image/picture/illustration.  Examples: “draw me a sunset over the mountains”, “generate an image of a cyberpunk city”, “create a picture of a dragon”.  Pass the descriptive prompt exactly as given by the user.  Do NOT call this tool for generic questions.
            
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

    // Patterns used to decide whether tool use is needed
    private static final Pattern STOCK_CUES = Pattern.compile(
            "(?i)\\b(price|quote|stock|share|ticker|trading|market|symbol|usd)\\b");
    private static final Pattern TICKERISH = Pattern.compile("(?i)\\b[A-Z]{1,5}(?:\\.[A-Z]{1,3})?\\b");
    private static final Pattern DATE_TIME = Pattern.compile(
            "(?i)\\b(what(?:'s| is)?\\s+the\\s+date|today(?:'s)?\\s+date|current\\s+date|what\\s+day\\s+is\\s+it|time\\s+now|current\\s+time)\\b");
    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)\\b(my name|who am i|how old am i|my age|where do i live|my email)\\b");
    private static final Pattern SEARCHY = Pattern.compile(
            "(?i)\\b(search|look\\s*up|find|brave|latest|news|results|articles?)\\b");
    private static final Pattern IMAGEY = Pattern.compile(
            "(?i)\\b(image|picture|photo|draw|create|generate|illustration)\\b");

    /**
     * Regex to capture inline base64 image data URIs.  The assistant is instructed to embed
     * generated images as data URIs within its reply.  We extract these so they can be sent
     * separately to the client UI.
     */
    private static final Pattern IMAGE_DATA_URI = Pattern.compile(
            "data:image/[^;]+;base64,[A-Za-z0-9+/=]+", Pattern.CASE_INSENSITIVE);

    /**
     * Determines whether the user’s query requires tool calls based on simple keyword matching.
     *
     * @param q the user’s raw query
     * @return true if tools should be enabled; false otherwise
     */
    private boolean needsTools(String q) {
        if (q == null) return false;
        String s = q.trim();
        if (s.isEmpty()) return false;

        // Do not use tools for personal questions
        if (PERSONAL.matcher(s).find()) return false;

        // Otherwise enable tools if any category matches
        return STOCK_CUES.matcher(s).find()
                || TICKERISH.matcher(s).matches()
                || SEARCHY.matcher(s).find()
                || DATE_TIME.matcher(s).find()
                || IMAGEY.matcher(s).find();
    }

    /**
     * Response record returned to the client.  Contains the conversation ID, the assistant’s
     * textual message, and optionally a single image or list of images encoded as data URIs.
     * If multiple images are generated, they are provided via the `images` list; `image` is
     * used for a single image convenience case.
     */
    public record ChatResponse(String cid, String message, String image, List<String> images) {
    }

    @GetMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestParam String query,
                             @RequestParam(value = "cid", required = false) String cid) {

        // Derive or generate a conversation ID
        String conversationId = (cid == null || cid.isBlank()) ? UUID.randomUUID().toString() : cid;

        // Decide whether to enable tools based on the query
        boolean useTools = needsTools(query);
        ChatClient client = useTools ? chatWithTools : chatNoTools;

        log.info("Incoming query='{}' | cid={} | usingTools={}", query, conversationId, useTools);

        // Build a memory advisor for this conversation
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(memory)
                .conversationId(conversationId)
                .build();

        // Create a prompt from the user input
        PromptTemplate promptTemplate = new PromptTemplate(query);
        Prompt prompt = promptTemplate.create();

        // Invoke the chat model
        ChatClient.CallResponseSpec response = client.prompt(prompt)
                .system(SYSTEM_MSG)
                .advisors(memoryAdvisor)
                .call();

        String answer = response.content();
        // If the tool returned an image directly (URL or data URI), short-circuit and send it to the UI.
        if (answer != null) {
            String trimmed = answer.trim();
            if (trimmed.startsWith("data:image/") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return new ChatResponse(conversationId, "Here you go!", trimmed, null);
            }
        }

        if (answer == null) {
            return new ChatResponse(conversationId, "[No response]", null, null);
        }

        // Extract images encoded as data URIs from the answer
        List<String> images = new ArrayList<>();
        Matcher m = IMAGE_DATA_URI.matcher(answer);
        while (m.find()) {
            images.add(m.group());
        }
        // Remove the images from the message text
        String cleanedMessage = answer;
        if (!images.isEmpty()) {
            cleanedMessage = IMAGE_DATA_URI.matcher(answer).replaceAll("").trim();
        }

        String singleImage = null;
        List<String> multipleImages = null;
        if (images.size() == 1) {
            singleImage = images.get(0);
        } else if (!images.isEmpty()) {
            multipleImages = images;
        }

        // Construct and return the structured response
        return new ChatResponse(conversationId, cleanedMessage, singleImage, multipleImages);
    }
}
