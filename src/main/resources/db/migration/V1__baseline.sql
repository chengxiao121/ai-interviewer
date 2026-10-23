-- =====================================================================
-- Flyway 基线脚本：由现有库（ddl-auto=update 时代的最终形态）通过
-- DatabaseMetaData 导出，与 2026-10 的线上结构一致。
-- 已有库：baseline-on-migrate 会跳过本脚本、仅打基线标记；
-- 全新库：从本脚本完整建表，之后所有变更走 V2+ 增量脚本。
-- =====================================================================

CREATE TABLE code_profile (
    id int8 NOT NULL,
    created_at timestamp NOT NULL,
    facts_json text NOT NULL,
    file_path varchar(512) NOT NULL,
    session_id varchar(64) NOT NULL,
    profile_type varchar(16) NOT NULL DEFAULT 'CODE',
    PRIMARY KEY (id)
);

CREATE TABLE interview_plan (
    id int8 NOT NULL,
    candidate_id varchar(64) NOT NULL,
    created_at timestamp NOT NULL,
    session_id varchar(64) NOT NULL,
    source varchar(16) NOT NULL,
    topics_json text NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_session UNIQUE (session_id)
);

CREATE TABLE interview_session (
    id int8 NOT NULL,
    candidate_id varchar(64) NOT NULL,
    created_at timestamp NOT NULL,
    materials text NOT NULL,
    session_id varchar(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ukbo6onld8ix14rllfuohxgs9m5 UNIQUE (session_id)
);

CREATE TABLE knowledge_document (
    id int8 NOT NULL,
    chunk_count int4 NOT NULL,
    content_hash varchar(64) NOT NULL,
    file_name varchar(255) NOT NULL,
    ingested_at timestamp NOT NULL,
    source varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ukglnavxlcu79hnswxg0g0n0ydg UNIQUE (file_name)
);

CREATE TABLE score_record (
    id int8 NOT NULL,
    answer varchar(4000) NOT NULL,
    created_at timestamp NOT NULL,
    feedback varchar(2000) NOT NULL,
    question varchar(2000) NOT NULL,
    score float8 NOT NULL,
    session_id varchar(64) NOT NULL,
    topic varchar(64) NOT NULL,
    candidate_id varchar(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_score_session_question UNIQUE (session_id, question)
);
