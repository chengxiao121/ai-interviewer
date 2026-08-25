package com.xiao.aiagent.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 对话模型配置（kimi-k3 兼容性适配，阶段 4.2 课后）。
 *
 * 背景：对话模型从 qwen3.7-plus 切换到 kimi-k3 后，DashScope 的 OpenAI 兼容端点
 * 对 kimi-k3 拒绝 temperature 参数（实测报错：
 *   "Parameter 'temperature'=0.7 is not supported for kimi-k3 model"）。
 * 而 Spring AI 的 OpenAI 自动配置默认给每个请求带 temperature=0.7，
 * 路由节点 / 各 Agent 的首次模型调用第一个撞上它 → 500。
 *
 * 修复：改用手动构建 ChatModel，构造 OpenAiChatOptions 时【不设 temperature】——
 * OpenAiApi.ChatCompletionRequest 带 @JsonInclude(NON_NULL)，temperature=null 时
 * 请求体直接省略该字段：kimi-k3 采用服务端默认值（能用了），qwen 也兼容（同样走服务端默认）。
 *
 * 知识点（换模型 = 换 API 契约）：
 *   - 不同模型的端点对参数要求不同（qwen 接受 temperature，kimi-k3 拒绝）；
 *   - Spring AI 框架默认参数 ≠ 所有模型都接受，换模型后要实测第一个请求。
 *
 * 模型名仍从 spring.ai.openai.chat.options.model 读取，以后换模型只需改配置、不动代码。
 */
@Configuration
public class OpenAiModelConfig {

    @Bean
    public ChatModel chatModel(OpenAiApi openAiApi,
                               ToolCallingManager toolCallingManager,
                               RetryTemplate retryTemplate,
                               ObservationRegistry observationRegistry,
                               @Value("${spring.ai.openai.chat.options.model}") String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                // ⚠️ 不设 temperature（null）：kimi-k3 不接受该参数，省略后走服务端默认
                .build();
        return new OpenAiChatModel(openAiApi, options, toolCallingManager, retryTemplate, observationRegistry);
    }

    /** ObservationRegistry 未被本项目自动装配（Spring AI 重度依赖它做埋点），手动创建空实现 */
    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

}