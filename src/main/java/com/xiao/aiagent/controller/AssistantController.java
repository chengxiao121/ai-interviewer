package com.xiao.aiagent.controller;

import com.xiao.aiagent.dto.ChatRequest;
import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import com.xiao.aiagent.agent.AgentRouter;
import com.xiao.aiagent.service.MaterialStoreService;
import com.xiao.aiagent.service.PaperComposeService;
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
import java.util.Locale;

/**
 * 面试对话入口（阶段 8 改造：入场绑定强制化；阶段 12 改造：双模式分叉）。
 *
 * 交互约定（入场流程）：
 *   1. 新会话第一条消息必须携带入场信息——candidateName（姓名，必填）
 *      + 模式（mode 缺省 MATERIAL）：
 *      - MATERIAL：materialIds 必传且含一份简历，JD 可选（原规则一字不改）；
 *      - KNOWLEDGE：kbId 必传、题库存在且非空 → 立即组卷（失败 400）→ 落绑定（无资料）。
 *        先组卷后落绑定：组卷失败不会留下"有绑定无卷子"的半截会话；
 *   2. 后端校验通过后落绑定 interview_session，之后该会话所有消息只带 sessionId，
 *      绑定信息由服务端读取——资料/卷子不重复进消息；
 *   3. 已绑定会话：请求带 mode 且与绑定不符 → 400 显式拒绝（不静默忽略——
 *      "换个模式继续聊"是语义陷阱，静默沿用旧模式等于欺骗用户）。
 *
 * candidateId 语义：资料模式下是候选人姓名，知识库模式下是练习者姓名——
 * 同一身份键支撑跨会话薄弱点聚合（SYSTEM_PROMPT 第 9 条），两种模式共用。
 */
@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

    private final AgentRouter agentRouter;
    private final MaterialStoreService materialStoreService;
    private final InterviewSessionRepository interviewSessionRepository;
    /** 知识库模式入场：立即组卷（先卷后绑定，失败不留半截会话） */
    private final PaperComposeService paperComposeService;

    public AssistantController(AgentRouter agentRouter,
                               MaterialStoreService materialStoreService,
                               InterviewSessionRepository interviewSessionRepository,
                               PaperComposeService paperComposeService) {
        this.agentRouter = agentRouter;
        this.materialStoreService = materialStoreService;
        this.interviewSessionRepository = interviewSessionRepository;
        this.paperComposeService = paperComposeService;
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

        // 入场绑定：已有 → 沿用（并校验模式一致）；没有 → 本轮按模式分叉完成入场
        InterviewSession binding = interviewSessionRepository.findBySessionId(sessionId).orElse(null);
        String candidateId;
        List<String> materialIds;
        if (binding != null) {
            // 已绑定：mode 不一致显式拒绝（mode 未传 = 沿用，兼容后续轮次不带 mode）
            String requestedMode = request.mode();
            if (requestedMode != null && !requestedMode.isBlank()
                    && !normalizeMode(requestedMode).equals(binding.getMode())) {
                throw badRequest("本场面试已绑定为" + modeLabel(binding.getMode())
                        + "，如需更换请新建一场面试");
            }
            candidateId = binding.getCandidateId();
            materialIds = binding.materialIds();
        } else {
            String mode = normalizeMode(request.mode());
            candidateId = normalizeCandidateName(request.candidateName());
            if (InterviewSession.MODE_KNOWLEDGE.equals(mode)) {
                // 知识库模式：kbId 必填 → 立即组卷（容量不足/库不存在/空库 → 400，不落绑定）→ 落绑定
                Long kbId = request.kbId();
                if (kbId == null) {
                    throw badRequest("知识库面试必须选择一个知识库（kbId）");
                }
                paperComposeService.compose(kbId, request.questionCount(), sessionId, candidateId);
                binding = interviewSessionRepository.save(
                        InterviewSession.knowledgeSession(sessionId, candidateId, kbId));
                materialIds = List.of();
            } else {
                // 资料模式：原校验一字不改（姓名 + 必含简历）
                materialIds = request.materialIds();
                if (materialIds == null || materialIds.isEmpty()) {
                    throw badRequest("请先完成面试入场：填写候选人姓名，并选择/上传一份简历");
                }
                if (materialIds.stream().noneMatch(id -> id.startsWith("RESUME-"))) {
                    throw badRequest("面试必须基于简历：请选择历史简历或上传新的简历（JD 可选）");
                }
                binding = interviewSessionRepository.save(new InterviewSession(sessionId, candidateId, materialIds));
            }
        }

        // 资料模式前置校验：资料文本可读、id 合法——不合法直接 400 透传原因
        // （知识库模式无资料可读，跳过：materialIds 为空是它的合法状态）
        if (!InterviewSession.MODE_KNOWLEDGE.equals(binding.getMode())) {
            try {
                materialStoreService.loadAsTexts(materialIds);
            } catch (IllegalArgumentException e) {
                throw badRequest(e.getMessage());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "资料读取失败：" + e.getMessage());
            }
        }

        // 路由分叉（阶段 12）：知识库模式确定性直连面试官（不跑三道关口，风险 6）；
        // 资料模式走原资料流水线路径。两者互斥覆盖全部入口。
        if (InterviewSession.MODE_KNOWLEDGE.equals(binding.getMode())) {
            return agentRouter.chatForKnowledge(request.userMessage(), sessionId, candidateId);
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

    /**
     * 面试模式归一（阶段 12）：null/空白 = 资料模式（旧前端不带 mode，行为不变）；
     * 大小写不敏感；其余值 → 400 中文提示。包私有 static 便于同包直测。
     */
    static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return InterviewSession.MODE_MATERIAL;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!InterviewSession.MODE_MATERIAL.equals(normalized)
                && !InterviewSession.MODE_KNOWLEDGE.equals(normalized)) {
            throw badRequest("面试模式仅支持 MATERIAL（资料面试）/ KNOWLEDGE（知识库面试），收到：" + mode);
        }
        return normalized;
    }

    /** 模式中文名（400 提示文案用） */
    private static String modeLabel(String mode) {
        return InterviewSession.MODE_KNOWLEDGE.equals(mode) ? "知识库面试模式" : "资料面试模式";
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

}
