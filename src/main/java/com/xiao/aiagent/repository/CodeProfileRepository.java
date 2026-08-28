package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.CodeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 代码分析事实清单仓储（阶段 4.1 新增）。
 *
 * 这里定义的方法 = 数据契约的访问接口，编码了两个业务规则：
 *   1. 面试官取清单：按时间正序合并同会话所有文件的分析结果（一个会话可能分析多个文件）；
 *   2. 是否已分析：existsBySessionId 决定要不要重跑分析 Agent——
 *      分析过的直接跳过，省一次 LLM 调用，这就是"落库复用"的红利。
 */
public interface CodeProfileRepository extends JpaRepository<CodeProfile, Long> {

    /** 查某会话所有事实清单（按分析时间正序，面试官据此合并出题） */
    List<CodeProfile> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 该会话是否已分析过代码（用于判断是否跳过分析 Agent，走"复用已有清单"路径） */
    boolean existsBySessionId(String sessionId);

    /**
     * 该会话是否已分析过【指定类型】的资料（阶段 5.5 新增）。
     * 流水线收敛后按资料类型逐类判断复用：用户提到了 JD/简历/代码时，
     * 只重跑库里还没有对应 profileType 清单的分析官，已分析过的类型跳过（复用红利按类生效）。
     * 也是 InterviewPlanService"USER 计划升级"的触发探测：该会话出现 JD 画像 → 临时计划重生成。
     */
    boolean existsBySessionIdAndProfileType(String sessionId, String profileType);

}
