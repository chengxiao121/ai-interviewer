package com.xiao.aiagent.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AssistantController.normalizeCandidateName 纯函数单测（P1 测试补齐）。
 *
 * candidateId 是评分落库与跨会话薄弱点聚合的聚合键——归一规则（trim/压空白/限长/拒 default）
 * 直接决定"同一个人的多场面试能不能归到一起"。规则坏了没有报错，只会让数据悄悄分裂。
 * 该方法包私有 static，同包直测；非法输入抛 ResponseStatusException(400)。
 */
@DisplayName("normalizeCandidateName：候选人姓名 → candidateId")
class AssistantControllerNormalizeCandidateNameTest {

    private static ResponseStatusException badRequestOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(400, e.getStatusCode().value());
        return e;
    }

    @Nested
    @DisplayName("正常归一")
    class Normalization {
        @Test
        void 普通姓名原样返回() {
            assertEquals("吴承晓", AssistantController.normalizeCandidateName("吴承晓"));
        }

        @Test
        void 去首尾空白() {
            assertEquals("吴承晓", AssistantController.normalizeCandidateName("  吴承晓  "));
        }

        @Test
        void 连续空白压缩为单个空格() {
            assertEquals("Wu Cheng Xiao", AssistantController.normalizeCandidateName("Wu \t Cheng   Xiao"));
        }

        @Test
        void 六十四字符姓名允许() {
            String name = "a".repeat(64);
            assertEquals(name, AssistantController.normalizeCandidateName(name));
        }
    }

    @Nested
    @DisplayName("非法输入（→ 400 中文提示）")
    class Rejections {
        @Test
        void null拒绝() {
            badRequestOf(() -> AssistantController.normalizeCandidateName(null));
        }

        @Test
        void 空串拒绝() {
            badRequestOf(() -> AssistantController.normalizeCandidateName(""));
        }

        @Test
        void 纯空白拒绝() {
            badRequestOf(() -> AssistantController.normalizeCandidateName("   "));
        }

        @Test
        void default拒绝_保留字() {
            ResponseStatusException e =
                    badRequestOf(() -> AssistantController.normalizeCandidateName("default"));
            assertEquals("候选人姓名不能为 default", e.getReason());
        }

        @Test
        void DEFAULT大小写不敏感拒绝() {
            badRequestOf(() -> AssistantController.normalizeCandidateName("DEFAULT"));
        }

        @Test
        void 超过六十四字符拒绝() {
            ResponseStatusException e =
                    badRequestOf(() -> AssistantController.normalizeCandidateName("a".repeat(65)));
            assertEquals("候选人姓名过长（最多 64 字符）", e.getReason());
        }
    }
}
