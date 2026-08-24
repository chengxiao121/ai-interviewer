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

}
