package com.xiao.aiagent.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InterviewSession.materialIds 的判空单测（阶段 12 风险 2：materials 改可空后防 NPE）。
 *
 * 知识库模式的会话不绑定任何资料，materials 可能是 null（历史数据/手工置空）
 * 或空串（构造时传入空列表）。会话列表与入场校验都会遍历 materialIds()，
 * "没有资料"是合法状态不是异常——必须返回空列表，绝不能抛 NPE。
 */
@DisplayName("InterviewSession.materialIds：无资料会话的判空处理")
class InterviewSessionMaterialIdsTest {

    @Nested
    @DisplayName("无资料的合法状态")
    class 无资料 {

        @Test
        @DisplayName("materials 为 null → 返回空列表，不抛 NPE")
        void null返回空列表() {
            InterviewSession session = new InterviewSession();
            session.setMaterials(null);

            assertTrue(session.materialIds().isEmpty(), "null 应返回空列表而不是抛 NPE");
        }

        @Test
        @DisplayName("materials 为空串 → 返回空列表")
        void 空串返回空列表() {
            InterviewSession session = new InterviewSession();
            session.setMaterials("");

            assertTrue(session.materialIds().isEmpty());
        }

        @Test
        @DisplayName("materials 为纯空白/逗号 → 同样返回空列表")
        void 空白逗号返回空列表() {
            InterviewSession session = new InterviewSession();
            session.setMaterials(" , , ");

            assertTrue(session.materialIds().isEmpty());
        }

        @Test
        @DisplayName("全参构造传空资料列表 → materials 落空串，materialIds 还原为空（知识库模式入场路径）")
        void 构造时空列表落空串() {
            InterviewSession session = new InterviewSession("s1", "c1", List.of());

            assertEquals("", session.getMaterials(), "空列表应落空串而不是 null");
            assertTrue(session.materialIds().isEmpty());
        }
    }

    @Nested
    @DisplayName("有资料的原行为不变")
    class 有资料 {

        @Test
        @DisplayName("逗号分隔 + 多余空格 → 去空格后还原（原行为回归锁定）")
        void 原行为不变() {
            InterviewSession session = new InterviewSession("s1", "c1", List.of("RESUME-abc", "JD-def"));

            assertEquals(List.of("RESUME-abc", "JD-def"), session.materialIds());
            assertEquals("RESUME-abc,JD-def", session.getMaterials());
        }
    }
}
