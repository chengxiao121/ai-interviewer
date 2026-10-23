package com.xiao.aiagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MaterialInterviewPipeline.detectMaterials 纯函数单测（P1 测试补齐）。
 *
 * detectMaterials 是资料路由的确定性快路径（规则命中 → 免一次 LLM 路由调用），
 * 历史上出过真实 bug：候选人说"JDK 提供的工具类"被 JD(?!K|BC) 修复前的子串包含
 * 误判成"提到了 JD 资料"。这里把该 bug 场景固化为回归测试。
 */
@DisplayName("detectMaterials：消息文本中的资料类型规则检测")
class MaterialInterviewPipelineDetectMaterialsTest {

    @Nested
    @DisplayName("JD 检测（含 JDK/JDBC 误报回归）")
    class JdDetection {
        @Test
        void 明确提到JD() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.JD),
                    MaterialInterviewPipeline.detectMaterials("请根据这个 JD 面试我"));
        }

        @Test
        void 提到职位中文关键词() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.JD),
                    MaterialInterviewPipeline.detectMaterials("我发一个岗位描述给你"));
        }

        @Test
        void 大小写不敏感() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.JD),
                    MaterialInterviewPipeline.detectMaterials("按这个 jd 要求来"));
        }

        @Test
        void JDK不误报为JD_历史bug回归() {
            // 修复前：子串包含导致 "JDK" 命中 "JD"——候选人答"JDK 提供的工具类"被误送资料流水线
            assertTrue(MaterialInterviewPipeline.detectMaterials("JDK 提供的工具类很方便").isEmpty());
        }

        @Test
        void JDBC不误报为JD_历史bug回归() {
            assertTrue(MaterialInterviewPipeline.detectMaterials("JDBC 连接池的原理是什么").isEmpty());
        }

        @Test
        void JDBC大小写混合不误报() {
            assertTrue(MaterialInterviewPipeline.detectMaterials("谈谈 jdbc 事务").isEmpty());
        }

        @Test
        void JD作为单词前缀的其他词不误报() {
            // "JDx" 这类（除 JDK/JDBC 外）也应排除——负向断言排除的是 K/BC 两种
            assertTrue(MaterialInterviewPipeline.detectMaterials("聊聊JDK新特性").isEmpty());
        }
    }

    @Nested
    @DisplayName("简历检测")
    class ResumeDetection {
        @Test
        void 中文关键词简历() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.RESUME),
                    MaterialInterviewPipeline.detectMaterials("这是我的简历，请开始"));
        }

        @Test
        void 英文resume() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.RESUME),
                    MaterialInterviewPipeline.detectMaterials("please review my resume"));
        }

        @Test
        void CV独立词命中() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.RESUME),
                    MaterialInterviewPipeline.detectMaterials("看看我的 CV"));
        }

        @Test
        void CV嵌在单词里不误报() {
            // CV_PATTERN 用词边界：CVS/CVC 这类嵌入不触发
            assertTrue(MaterialInterviewPipeline.detectMaterials("我们用 CVS 管理版本").isEmpty());
        }
    }

    @Nested
    @DisplayName("代码资料检测")
    class CodeDetection {
        @Test
        void 代码意图关键词() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.CODE),
                    MaterialInterviewPipeline.detectMaterials("看看我的代码"));
        }

        @Test
        void 文件扩展名命中() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.CODE),
                    MaterialInterviewPipeline.detectMaterials("评审一下 OrderService.java"));
        }

        @Test
        void windows路径命中() {
            assertEquals(Set.of(MaterialInterviewPipeline.Material.CODE),
                    MaterialInterviewPipeline.detectMaterials("D:\\projects\\demo 这个项目"));
        }

        @Test
        void 代码关键词与简历可叠加() {
            // "拿这份 JD 和我的代码面我"→ 多类同时命中（叠加语义）
            Set<MaterialInterviewPipeline.Material> m =
                    MaterialInterviewPipeline.detectMaterials("针对我的这段代码回答上一题");
            assertEquals(Set.of(MaterialInterviewPipeline.Material.CODE), m);
        }
    }

    @Nested
    @DisplayName("多类型叠加与空输入")
    class CombinationAndEdge {
        @Test
        void JD与简历同时命中() {
            Set<MaterialInterviewPipeline.Material> m =
                    MaterialInterviewPipeline.detectMaterials("结合这个 JD 和我的简历面我");
            assertEquals(Set.of(
                    MaterialInterviewPipeline.Material.JD,
                    MaterialInterviewPipeline.Material.RESUME), m);
        }

        @Test
        void 三类全命中() {
            Set<MaterialInterviewPipeline.Material> m =
                    MaterialInterviewPipeline.detectMaterials(
                            "这个 JD 和我的简历，再看看 D:\\work\\App.java");
            assertEquals(Set.of(MaterialInterviewPipeline.Material.values()), m);
        }

        @Test
        void 普通回答不命中任何类型() {
            // 面试中的正常作答不应触发资料流水线
            assertTrue(MaterialInterviewPipeline.detectMaterials(
                    "线程池有七个参数，核心是 corePoolSize 和 maximumPoolSize").isEmpty());
        }

        @Test
        void null返回空集() {
            assertTrue(MaterialInterviewPipeline.detectMaterials(null).isEmpty());
        }

        @Test
        void 空白字符串返回空集() {
            assertTrue(MaterialInterviewPipeline.detectMaterials("   \n\t ").isEmpty());
        }
    }
}
