package com.xiao.aiagent.repository;
import com.xiao.aiagent.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long>{

    /** 按文件名查元数据（幂等比对用） */
    Optional<KnowledgeDocument> findByFileName(String fileName);

    /** 判断文档是否已入库 */
    boolean existsByFileName(String fileName);

}
