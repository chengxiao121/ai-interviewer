package com.xiao.aiagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnswerEvaluatorService.resolveTopic 纯函数单测（P1 测试补齐）。
 *
 * resolveTopic 是评估报告"覆盖矩阵"能做精确 join 的前提——LLM 输出的 topic
 * 必须经三级校验归一到封闭词表：精确匹配 → 包含匹配 → 兜底"计划外：<原文>"。
 * 该方法不用实例状态，已改为包私有 static 以便同包直测（避免反射）。
 */
@DisplayName("resolveTopic：考点三级校验归一")
class AnswerEvaluatorServiceResolveTopicTest {

    /** 构造词表：与 InterviewPlanService.PlanTopic 对齐（topic, priority, difficulty） */
    private static List<InterviewPlanService.PlanTopic> vocab(String... topics) {
        return java.util.Arrays.stream(topics)
                .map(t -> new InterviewPlanService.PlanTopic(t, "P0", "中"))
                .toList();
    }

    @Nested
    @DisplayName("词表为空（无计划的自由归类）")
    class EmptyVocabulary {
        @Test
        void 自由归类返回原文() {
            assertEquals("并发编程", AnswerEvaluatorService.resolveTopic("并发编程", List.of()));
        }

        @Test
        void 超长归一截断到64字符() {
            String longTopic = "长".repeat(100);
            String result = AnswerEvaluatorService.resolveTopic(longTopic, List.of());
            assertEquals(64, result.length());
        }
    }

    @Nested
    @DisplayName("第一级：精确匹配")
    class ExactMatch {
        @Test
        void 精确命中返回词表项() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池", "JVM", "集合");
            assertEquals("线程池", AnswerEvaluatorService.resolveTopic("线程池", v));
        }

        @Test
        void 首尾空白被trim后精确命中() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池");
            assertEquals("线程池", AnswerEvaluatorService.resolveTopic(" 线程池 ", v));
        }
    }

    @Nested
    @DisplayName("第二级：包含匹配（归一到词表措辞）")
    class ContainsMatch {
        @Test
        void LLM输出比词表项长_输出包含词表项() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池参数");
            assertEquals("线程池参数",
                    AnswerEvaluatorService.resolveTopic("线程池参数的配置", v));
        }

        @Test
        void LLM输出比词表项短_词表项包含输出() {
            List<InterviewPlanService.PlanTopic> v = vocab("Java 线程池");
            assertEquals("Java 线程池",
                    AnswerEvaluatorService.resolveTopic("线程池", v));
        }

        @Test
        void 多项包含时按词表顺序取第一个() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池", "线程池参数");
            assertEquals("线程池",
                    AnswerEvaluatorService.resolveTopic("线程池参数配置", v));
        }
    }

    @Nested
    @DisplayName("第三级：兜底计划外")
    class OutOfPlanFallback {
        @Test
        void 完全不匹配时加计划外前缀() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池", "JVM");
            assertEquals("计划外：TCP握手",
                    AnswerEvaluatorService.resolveTopic("TCP握手", v));
        }

        @Test
        void LLM自己已输出计划外时不重复加前缀() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池");
            assertEquals("计划外",
                    AnswerEvaluatorService.resolveTopic("计划外", v));
        }

        @Test
        void 计划外超长截断到64字符() {
            List<InterviewPlanService.PlanTopic> v = vocab("线程池");
            String result = AnswerEvaluatorService.resolveTopic("长".repeat(100), v);
            assertEquals(64, result.length());
            assertTrue(result.startsWith("计划外"));
        }
    }
}
