package com.xiao.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.dto.PaperDto;
import com.xiao.aiagent.dto.PlanTopic;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import com.xiao.aiagent.repository.MaterialProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InterviewPlanService.renderPaper / parsePaper 单测（阶段 12，风险 4：卷子独立注入文本）。
 *
 * 关键回归点：卷子措辞必须与 renderPlan 的保密红线【相反】——
 * 题目是要念给候选人听的，绝不能复用"计划是内部备课材料、不要罗列"的措辞，
 * 否则模型会拒绝出题或只给暗示（这正是把 renderPaper 独立出来的原因）。
 */
@DisplayName("InterviewPlanService.renderPaper：知识库模式的卷子注入文本")
class InterviewPlanServiceRenderPaperTest {

    private InterviewPlanService service;

    @BeforeEach
    void setUp() {
        service = new InterviewPlanService(Mockito.mock(ChatModel.class),
                Mockito.mock(MaterialProfileRepository.class),
                Mockito.mock(InterviewPlanRepository.class),
                new ObjectMapper());
    }

    /** 造一份带卷子的计划（paper_json 由本测试序列化，模拟 PaperComposeService 的产物） */
    private InterviewPlan planWithPaper() throws Exception {
        PaperDto paper = new PaperDto("Java 题库", List.of(
                new PaperDto.PaperQuestion(1, "HashMap 的底层数据结构是什么？", "数组 + 链表 + 红黑树", "集合", 101L),
                new PaperDto.PaperQuestion(2, "volatile 能保证原子性吗？", "不能，只保证可见性", "并发", 102L)));
        InterviewPlan plan = new InterviewPlan("s1", "c1",
                "[{\"topic\":\"HashMap 的底层数据结构是什么\",\"priority\":\"中\",\"difficulty\":\"基础\"}]",
                InterviewPlanService.SOURCE_KNOWLEDGE, java.time.LocalDateTime.now());
        plan.setPaperJson(new ObjectMapper().writeValueAsString(paper));
        return plan;
    }

    @Nested
    @DisplayName("正常渲染")
    class 正常渲染 {

        @Test
        @DisplayName("注入文本含卷子全文（题库名/题号/题目/参考答案）与出题纪律")
        void 含卷子全文与纪律() throws Exception {
            String text = service.renderPaper(planWithPaper());

            assertTrue(text.contains("【本场卷子】"));
            assertTrue(text.contains("Java 题库"));
            assertTrue(text.contains("第 1 题：HashMap 的底层数据结构是什么？"));
            assertTrue(text.contains("第 2 题：volatile 能保证原子性吗？"));
            assertTrue(text.contains("数组 + 链表 + 红黑树"));
            assertTrue(text.contains("按卷子顺序"));
            assertTrue(text.contains("不追问"));
        }

        @Test
        @DisplayName("措辞与 renderPlan 保密红线相反：不含『备课材料』，含『参考答案在候选人作答后才用』")
        void 措辞独立于保密红线() throws Exception {
            String text = service.renderPaper(planWithPaper());

            assertFalse(text.contains("备课材料"), "卷子不是内部材料，保密措辞会让模型拒绝出题");
            assertFalse(text.contains("不要罗列"));
            assertTrue(text.contains("不要把参考答案念出来"), "出题时必须防答案提前泄露");
        }
    }

    @Nested
    @DisplayName("降级与异常输入")
    class 降级与异常输入 {

        @Test
        @DisplayName("null 计划 / 无卷子 / 坏 JSON → 空串，调用方跳过注入，不抛异常")
        void 降级为空串() {
            assertTrue(service.renderPaper(null).isEmpty());

            InterviewPlan noPaper = new InterviewPlan("s1", "c1", "[]",
                    InterviewPlanService.SOURCE_USER, java.time.LocalDateTime.now());
            assertTrue(service.renderPaper(noPaper).isEmpty(), "资料模式计划（无 paperJson）应返回空串");

            InterviewPlan badJson = planWithBadPaper();
            assertTrue(service.renderPaper(badJson).isEmpty());
        }

        @Test
        @DisplayName("parsePaper：空串/坏 JSON → empty；合法 JSON → PaperDto")
        void parsePaper分支() throws Exception {
            assertTrue(service.parsePaper(null).isEmpty());
            assertTrue(service.parsePaper("  ").isEmpty());
            assertTrue(service.parsePaper("not-json{{{").isEmpty());

            Optional<PaperDto> parsed = service.parsePaper(
                    new ObjectMapper().writeValueAsString(new PaperDto("k", List.of())));
            assertTrue(parsed.isPresent());
            assertEquals(0, parsed.get().questions().size());
        }
    }

    private InterviewPlan planWithBadPaper() {
        InterviewPlan plan = new InterviewPlan("s1", "c1", PlanTopicJsonFixture,
                InterviewPlanService.SOURCE_KNOWLEDGE, java.time.LocalDateTime.now());
        plan.setPaperJson("not-json{{{");
        return plan;
    }

    private static final String PlanTopicJsonFixture =
            "[{\"topic\":\"t\",\"priority\":\"中\",\"difficulty\":\"基础\"}]";
}
