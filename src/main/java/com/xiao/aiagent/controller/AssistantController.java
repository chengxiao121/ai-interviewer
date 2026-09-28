package com.xiao.aiagent.controller;

import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import com.xiao.aiagent.services.AgentRouter;
import com.xiao.aiagent.services.MaterialStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

/**
 * 面试对话入口（阶段 8 改造：入场绑定强制化）。
 *
 * 交互约定（入场流程）：
 *   1. 新会话第一条消息必须携带入场信息——candidateName（候选人姓名，必填）
 *      + materialIds（资料，必须含一份简历，JD 可选）；
 *   2. 后端校验通过后落绑定 interview_session（sessionId → candidateId + materialIds），
 *      之后该会话所有消息只带 sessionId，绑定信息由服务端读取——资料不重复进消息；
 *   3. 会话没有绑定且请求不带入场信息 → 400 引导入场（简历必传由服务端保证，不是前端摆设）。
 *
 * candidateId 语义（替代 4.3 的固定 "default"）：
 *   = 归一化后的候选人姓名。同一姓名 + 同一份简历 = 同一候选人身份，
 *   跨会话薄弱点聚合在其名下；不同姓名 = 完全隔离的两场面试——
 *   "两份不同简历和 JD 的面试互不干扰"由此保证。
 */
@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

    private final AgentRouter agentRouter;
    private final MaterialStoreService materialStoreService;
    private final InterviewSessionRepository interviewSessionRepository;

    public AssistantController(AgentRouter agentRouter,
                               MaterialStoreService materialStoreService,
                               InterviewSessionRepository interviewSessionRepository) {
        this.agentRouter = agentRouter;
        this.materialStoreService = materialStoreService;
        this.interviewSessionRepository = interviewSessionRepository;
    }

    /**
     * 面试对话（SSE 流式）——多 Agent 系统的总入口。
     *
     * 注意：这里【不要】把分片里的 \n 手动转义成 "\ndata:"！
     * Spring 的 ServerSentEventHttpMessageWriter#writeStringData 对 data 字段本身就会
     * 把 \n 转成 "\ndata:" 续行。前端 sse.ts 按 SSE 规范把同帧多条 data: 行用 \n 拼接即可还原换行。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw badRequest("缺少 sessionId");
        }

        // 入场绑定：已有 → 沿用；没有 → 本轮必须带入场信息（姓名 + 资料，且含简历）
        InterviewSession binding = interviewSessionRepository.findBySessionId(sessionId).orElse(null);
        String candidateId;
        List<String> materialIds;
        if (binding != null) {
            candidateId = binding.getCandidateId();
            materialIds = binding.materialIds();
        } else {
            candidateId = normalizeCandidateName(request.candidateName());
            materialIds = request.materialIds();
            if (materialIds == null || materialIds.isEmpty()) {
                throw badRequest("请先完成面试入场：填写候选人姓名，并选择/上传一份简历");
            }
            if (materialIds.stream().noneMatch(id -> id.startsWith("RESUME-"))) {
                throw badRequest("面试必须基于简历：请选择历史简历或上传新的简历（JD 可选）");
            }
            binding = interviewSessionRepository.save(new InterviewSession(sessionId, candidateId, materialIds));
        }

        // 前置校验：资料文本可读、id 合法——不合法直接 400 透传原因
        // （AgentRouter 内部也会再加载一次，这里提前挡是为了拿到干净的 400 而不是 SSE 流错误）
        try {
            materialStoreService.loadAsTexts(materialIds);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "资料读取失败：" + e.getMessage());
        }

        // 统一走资料流水线：首轮跑"分析官 → 面试官"，之后按类型复用快路径直连面试官。
        // 记忆策略不变：资料全文只在本轮图内可见，写回会话记忆的仅用户原话。
        return agentRouter.chatWithMaterials(request.userMessage(), materialIds, sessionId, candidateId);
    }

    /** 候选人姓名 → candidateId：去首尾空白、压缩连续空白、限长 64；拒绝空值与保留字 default */
    static String normalizeCandidateName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("请填写候选人姓名");
        }
        String normalized = name.trim().replaceAll("\\s+", " ");
        if ("default".equalsIgnoreCase(normalized)) {
            throw badRequest("候选人姓名不能为 default");
        }
        if (normalized.length() > 64) {
            throw badRequest("候选人姓名过长（最多 64 字符）");
        }
        return normalized;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * chat 请求体（阶段 8）：
     *   - candidateName：候选人姓名（入场首轮必传；已有绑定的会话可省略）；
     *   - materialIds：上传资料 id（入场首轮必传且须含简历；已有绑定的会话由绑定决定）。
     */
    public record ChatRequest(String userMessage, String sessionId, String candidateName,
                              List<String> materialIds) {
    }

}
