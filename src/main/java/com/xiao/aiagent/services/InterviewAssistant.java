package com.xiao.aiagent.services;

import com.xiao.aiagent.tools.InterviewTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class InterviewAssistant {

    private static final Logger log = LoggerFactory.getLogger(InterviewAssistant.class);

    /**
     * 系统提示（纯静态，不含运行时变量）。
     * sessionId 不再进系统提示：打分落库时的会话归属由服务端通过 ToolContext 注入，
     * 模型无需知道会话 id。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深技术面试官，负责对求职者进行技术面试模拟。
            你的职责：
            1. 根据岗位和求职者水平出题（Java/Redis/数据库等），题目循序渐进；
            2. 针对回答进行追问，答错时给予提示引导；
            3. 点评回答并给出评分与改进建议，点评完成后必须调用 scoreRecord 工具把评分落库；
            4. 出题时优先参考知识库中的题库内容，引用时注明出处。
            5. 求职者询问答题统计或薄弱考点时，调用 questionStats 工具查询后如实转述结果。
            6. 求职者分享某个 URL（文章、技术文档等）让你参考时，使用 fetch 工具读取该网页内容，
               基于文章内容出题，弥补题库可能没有覆盖的主题。
            7. 求职者提供本地项目目录路径让你看代码时，使用文件读取工具（read_file/list_directory/search_files）
               读取其代码，针对真实实现提问（如"你这里为什么用 X 方案？有什么风险？"）。
               注意：你只能读取文件，不要修改或删除求职者的任何文件。

            评分标准（0~10 分，请严格按此打分）：
            - 回答是否准确、完整（核心得分项）；
            - 是否涉及关键知识点；
            - 是否表达清晰、条理。
            分档参考：0-3 完全不会或严重错误 / 4-6 部分正确、有缺失 / 7-9 较完整、有小瑕疵 / 10 准确全面。
            打分后必须调用 scoreRecord 工具落库，再给出点评。已点评过的同一道题不要重复调用 scoreRecord 重新落库。

            要求：语气专业、友好，全程使用中文。
            """;

    private final ChatClient chatClient;

    public InterviewAssistant(ChatClient.Builder builder,
                              MessageChatMemoryAdvisor chatMemoryAdvisor,
                              QuestionAnswerAdvisor questionAnswerAdvisor,
                              InterviewTools interviewTools,
                              ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider){
        // MCP 工具通过 ObjectProvider 注入（而非直接 @Autowired）：
        // 原因是降级兼容——当未配置 MCP server 或 npx 拉包失败时，自动装配不会产出
        // ToolCallbackProvider bean，此时 getIfAvailable() 返回 null，ChatClient 只用现有
        // @Tool 工具，核心面试功能不受影响；配好 MCP 时才把外部工具也注册进去。
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider != null) {
            log.info("MCP 工具已注入面试 Agent，可用工具数：{}", mcpProvider.getToolCallbacks().length);
        } else {
            log.warn("未检测到 MCP 工具（未配置 server 或 npx 拉包失败），面试 Agent 仅使用内置 @Tool 工具。");
        }

        ChatClient.Builder chatBuilder = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(questionAnswerAdvisor, chatMemoryAdvisor)         //// RAG 在前，记忆在后
                .defaultTools(interviewTools);                                      //// 注册 Function Calling 工具

        // MCP 工具与内置 @Tool 并存：defaultToolCallbacks 接受 ToolCallbackProvider...
        if (mcpProvider != null) {
            chatBuilder.defaultToolCallbacks(mcpProvider);
        }

        this.chatClient = chatBuilder.build();
    }

    /**
     * 发送一次面试对话请求。
     *
     * sessionId 由前端（或 API 调用方）传入，后端不生成——它是"会话标识"，
     * 纯后端测试时必须在请求体里自己带一个，换值 = 新会话、复用 = 续同一会话。
     *
     * 同一个 sessionId 被用于两件事（见方法体内两处）：
     *  1. chat_memory_conversation_id → 记忆顾问按会话隔离历史消息（Redis 滑动窗口）；
     *  2. .toolContext(...) 注入 sessionId → 工具（如 scoreRecord）落库时的会话归属。
     *     这里不走普通参数而是走 ToolContext：因为 ToolContext 参数会被 Spring AI
     *     从模型可见的工具 schema 里剔除，sessionId 对模型不可见、也不可能被模型误填串会。
     */
    public Flux<String> chat(String userMessage, String sessionId){
        return this.chatClient
                .prompt()
                .user(userMessage)
                // 记忆隔离用 sessionId
                .advisors(a -> a.param("chat_memory_conversation_id", sessionId))
                // 把 sessionId 塞进 ToolContext（服务端→工具的上下文容器）。
                // 工具方法声明 ToolContext 参数时，框架会把这个容器带给它，且不进模型的参数列表。
                // 与工具内 toolContext.getContext().get("sessionId") 配对：这里写，那里读。
                .toolContext(java.util.Map.of("sessionId", sessionId))
                .stream()
                .content();
    }

}
