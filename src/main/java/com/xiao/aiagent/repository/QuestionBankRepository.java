package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.QuestionBankItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 题目仓储（阶段 12 新增）。
 *
 * 关键设计：随机抽题走 SQL 的 ORDER BY random()（决策 1）——
 * 背题自检的诉求是"从集合里取样"，选哪道题与语义相似度无关，
 * 所以不碰向量库；一句 SQL 比"检索 + 重排"更简单也更可预期。
 *
 * capacity 与 draw 分开：容量校验要先拿到"可用题数"再决定能不能开这场，
 * 抽题失败要在开始前就拒绝（不静默降级为"有多少出多少"）。
 */
public interface QuestionBankRepository extends JpaRepository<QuestionBankItem, Long> {

    /** 该库的可用题数（组卷容量校验用） */
    int countByKbIdAndStatus(Long kbId, String status);

    /** 该库全部题目（按原序，预览与解析校验用） */
    List<QuestionBankItem> findByKbIdOrderBySeq(Long kbId);

    /**
     * 随机抽 N 道可用题（组卷用）。
     * 为什么写原生 SQL：random() 不是 JPQL 标准函数，原生写法直白且确定能跑在 PG 上；
     * 代价是绑定具体数据库方言——本项目固定 PostgreSQL（单机自用），可接受。
     */
    @Query(value = "select * from question_bank where kb_id = :kbId and status = :status "
            + "order by random() limit :limit", nativeQuery = true)
    List<QuestionBankItem> drawRandom(@Param("kbId") Long kbId,
                                      @Param("status") String status,
                                      @Param("limit") int limit);

    /** 删除该库的全部题目（删除知识库时连带执行；调用方需带事务） */
    @Modifying
    @Query("delete from QuestionBankItem q where q.kbId = :kbId")
    int deleteByKbId(@Param("kbId") Long kbId);

}
