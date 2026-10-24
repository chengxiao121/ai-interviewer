package com.xiao.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.dto.PaperDto;
import com.xiao.aiagent.dto.PlanTopic;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.entity.KnowledgeBase;
import com.xiao.aiagent.entity.QuestionBankItem;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import com.xiao.aiagent.repository.KnowledgeBaseRepository;
import com.xiao.aiagent.repository.QuestionBankRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaperComposeService 单测（阶段 12：组卷的容量校验/幂等/并发兜底/考点词表）。
 * 仓储全 mock（禁止连 PG）；drawRandom 的 SQL 语义已在 PG16 验证库实测。
 * "组卷不调 LLM"由结构保证：本服务构造函数没有任何 ChatModel 依赖。
 */
@DisplayName("PaperComposeService.compose：知识库组卷")
class PaperComposeServiceTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";
    private static final Long KB_ID = 1L;

    private KnowledgeBaseRepository kbRepository;
    private QuestionBankRepository questionRepository;
    private InterviewPlanRepository planRepository;
    private PaperComposeService service;

    @BeforeEach
    void setUp() {
        kbRepository = Mockito.mock(KnowledgeBaseRepository.class);
        questionRepository = Mockito.mock(QuestionBankRepository.class);
        planRepository = Mockito.mock(InterviewPlanRepository.class);
        service = new PaperComposeService(kbRepository, questionRepository, planRepository, new ObjectMapper());

        KnowledgeBase kb = new KnowledgeBase("Java 题库", null, 30, "java.txt", "hash", LocalDateTime.now());
        kb.setId(KB_ID);
        when(kbRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        when(questionRepository.countByKbIdAndStatus(anyLong(), anyString())).thenReturn(30);
        when(questionRepository.drawRandom(anyLong(), anyString(), anyInt()))
                .thenAnswer(inv -> sampleQuestions(inv.getArgument(2, Integer.class)));
        when(planRepository.findBySessionId(anyString())).thenReturn(Optional.empty());
        when(planRepository.save(any(InterviewPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 造 n 道不重复的可用题（模拟 SQL 随机抽题的结果；id 由数据库生成，这里手工回填） */
    private static List<QuestionBankItem> sampleQuestions(int n) {
        List<QuestionBankItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            QuestionBankItem item = new QuestionBankItem(KB_ID, i, "题目" + i + "的内容？", "参考答案" + i,
                    "分类" + (i % 3), QuestionBankItem.STATUS_ACTIVE, LocalDateTime.now());
            item.setId(100L + i);
            items.add(item);
        }
        return items;
    }

    @Nested
    @DisplayName("容量与题数校验")
    class 容量与题数校验 {

        @Test
        @DisplayName("题库只有 5 道可用题请求 20 道 → 明确拒绝，信息含可用题数 5（不静默降级）")
        void 容量不足拒绝() {
            when(questionRepository.countByKbIdAndStatus(KB_ID, QuestionBankItem.STATUS_ACTIVE)).thenReturn(5);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.compose(KB_ID, 20, SESSION_ID, CANDIDATE_ID));

            assertTrue(e.getMessage().contains("5"), "错误信息应含可用题数 5：" + e.getMessage());
            verify(questionRepository, never()).drawRandom(anyLong(), anyString(), anyInt());
            verify(planRepository, never()).save(any());
        }

        @Test
        @DisplayName("题数缺省 → 用默认 20；越界（4 / 51）→ 直接拒绝")
        void 题数归一() {
            service.compose(KB_ID, null, SESSION_ID, CANDIDATE_ID);
            verify(questionRepository).drawRandom(KB_ID, QuestionBankItem.STATUS_ACTIVE,
                    PaperComposeService.DEFAULT_QUESTION_COUNT);

            assertThrows(IllegalArgumentException.class,
                    () -> service.compose(KB_ID, PaperComposeService.MIN_QUESTION_COUNT - 1, SESSION_ID, CANDIDATE_ID));
            assertThrows(IllegalArgumentException.class,
                    () -> service.compose(KB_ID, PaperComposeService.MAX_QUESTION_COUNT + 1, SESSION_ID, CANDIDATE_ID));
        }

        @Test
        @DisplayName("知识库不存在 → 400 提示，不抽题")
        void 知识库不存在() {
            when(kbRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> service.compose(99L, 10, SESSION_ID, CANDIDATE_ID));
            verify(questionRepository, never()).drawRandom(anyLong(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("卷子与考点词表")
    class 卷子与考点词表 {

        @Test
        @DisplayName("抽 N 道 → 卷子题号连续 1..N、questionId 不重复、带参考答案与题库名")
        void 卷子结构() throws Exception {
            InterviewPlan plan = service.compose(KB_ID, 10, SESSION_ID, CANDIDATE_ID);
            PaperDto paper = serviceParsePaper(plan.getPaperJson());

            assertEquals("Java 题库", paper.kbName());
            assertEquals(10, paper.questions().size());
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < paper.questions().size(); i++) {
                PaperDto.PaperQuestion q = paper.questions().get(i);
                assertEquals(i + 1, q.no(), "题号应连续 1..N");
                assertTrue(ids.add(q.questionId()), "questionId 不得重复");
                assertTrue(q.stem() != null && !q.stem().isBlank());
                assertTrue(q.referenceAnswer() != null && !q.referenceAnswer().isBlank());
            }
        }

        @Test
        @DisplayName("考点词表与卷子同批生成：条数一致、落库到 topics_json（source=KNOWLEDGE）")
        void 词表与来源() {
            InterviewPlan plan = service.compose(KB_ID, 10, SESSION_ID, CANDIDATE_ID);

            assertEquals(InterviewPlanService.SOURCE_KNOWLEDGE, plan.getSource());
            ArgumentCaptor<InterviewPlan> captor = ArgumentCaptor.forClass(InterviewPlan.class);
            verify(planRepository).save(captor.capture());
            InterviewPlan saved = captor.getValue();
            assertTrue(saved.getTopicsJson().contains("题目1的内容"), "topics_json 应含第一题的考点短名");
            assertTrue(saved.getPaperJson().contains("参考答案10"), "paper_json 应含卷子参考答案");
        }
    }

    @Nested
    @DisplayName("幂等与并发")
    class 幂等与并发 {

        @Test
        @DisplayName("已有带卷子的计划 → 幂等返回同一张，不重抽不落第二条")
        void 幂等返回() {
            InterviewPlan existing = new InterviewPlan(SESSION_ID, CANDIDATE_ID,
                    "[{\"topic\":\"t\",\"priority\":\"中\",\"difficulty\":\"基础\"}]",
                    InterviewPlanService.SOURCE_KNOWLEDGE, LocalDateTime.now());
            existing.setPaperJson("{\"kbName\":\"x\",\"questions\":[]}");
            when(planRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(existing));

            InterviewPlan plan = service.compose(KB_ID, 20, SESSION_ID, CANDIDATE_ID);

            assertSame(existing, plan);
            verify(questionRepository, never()).drawRandom(anyLong(), anyString(), anyInt());
            verify(planRepository, never()).save(any());
        }

        @Test
        @DisplayName("已有计划但无卷子（资料模式残留）→ 拒绝组卷，不覆盖")
        void 已有计划无卷子拒绝() {
            InterviewPlan existing = new InterviewPlan(SESSION_ID, CANDIDATE_ID,
                    "[{\"topic\":\"t\",\"priority\":\"中\",\"difficulty\":\"基础\"}]",
                    InterviewPlanService.SOURCE_USER, LocalDateTime.now());
            when(planRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(existing));

            assertThrows(IllegalArgumentException.class,
                    () -> service.compose(KB_ID, 20, SESSION_ID, CANDIDATE_ID));
        }

        @Test
        @DisplayName("并发组卷撞唯一约束 → 读回先落库的那张返回")
        void 撞约束读回() {
            InterviewPlan winner = new InterviewPlan(SESSION_ID, CANDIDATE_ID,
                    "[{\"topic\":\"t\",\"priority\":\"中\",\"difficulty\":\"基础\"}]",
                    InterviewPlanService.SOURCE_KNOWLEDGE, LocalDateTime.now());
            winner.setPaperJson("{\"kbName\":\"x\",\"questions\":[]}");
            when(planRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(planRepository.save(any(InterviewPlan.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_plan_session"));

            InterviewPlan plan = service.compose(KB_ID, 10, SESSION_ID, CANDIDATE_ID);

            assertSame(winner, plan);
        }
    }

    @Nested
    @DisplayName("deriveTopic：考点短名规范化（风险 5 的长度与去重约束）")
    class 考点短名 {

        @Test
        @DisplayName("取第一行、去尾部标点与空白")
        void 规范化() {
            Set<String> seen = new HashSet<>();
            assertEquals("HashMap 的底层数据结构是什么",
                    PaperComposeService.deriveTopic("HashMap 的底层数据结构是什么？\n补充行", seen));
            assertEquals("volatile 的作用",
                    PaperComposeService.deriveTopic("volatile 的作用！！！", new HashSet<>()));
        }

        @Test
        @DisplayName("超长题目截断到 64 字符")
        void 长度上限() {
            String longStem = "很长的题目".repeat(30);
            String topic = PaperComposeService.deriveTopic(longStem, new HashSet<>());
            assertTrue(topic.length() <= PaperComposeService.TOPIC_MAX_LEN, "不得超过 64：" + topic.length());
        }

        @Test
        @DisplayName("重复题目撞名 → 序号后缀区分，且总长仍 ≤64")
        void 去重() {
            Set<String> seen = new HashSet<>();
            String stem = "同题干".repeat(30);
            String first = PaperComposeService.deriveTopic(stem, seen);
            String second = PaperComposeService.deriveTopic(stem, seen);
            String third = PaperComposeService.deriveTopic(stem, seen);

            assertTrue(first.length() <= 64 && second.length() <= 64 && third.length() <= 64);
            assertTrue(second.endsWith("-2"));
            assertTrue(third.endsWith("-3"));
        }

        @Test
        @DisplayName("空题干 → 兜底名，不产出空白考点")
        void 空题干兜底() {
            String topic = PaperComposeService.deriveTopic("  ？ \n ", new HashSet<>());
            assertEquals("未命名考点", topic);
        }
    }

    /** 测试内解析 paper_json（不经 Spring，直接 new ObjectMapper） */
    private PaperDto serviceParsePaper(String paperJson) throws Exception {
        return new ObjectMapper().readValue(paperJson, PaperDto.class);
    }
}
