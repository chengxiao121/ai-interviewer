package com.xiao.aiagent.entity;

/** 题库文档入库状态 */
public enum KnowledgeDocStatus {

    /** 已成功写入向量库 */
    INGESTED,
    /** 入库失败（分块/向量化异常），下次同步时重试 */
    FAILED

}
