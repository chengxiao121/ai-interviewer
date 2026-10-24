package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储（阶段 12 新增）。
 *
 * 方法对应三个使用场景：
 *   - findByName：名称唯一校验（新建时重名 → 明确拒绝，不静默覆盖已有库）；
 *   - findAllByOrderByCreatedAtDesc：列表页/选择器的读取顺序（最新建的在最前）；
 *   - findById：组卷前确认库存在（不存在 → 400，不静默降级到"随便出几题"）。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** 按名称查（名称是知识库的自然键，唯一约束兜底） */
    Optional<KnowledgeBase> findByName(String name);

    /** 全部知识库（按创建时间倒序） */
    List<KnowledgeBase> findAllByOrderByCreatedAtDesc();

}
