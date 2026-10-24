-- V3：面试会话生命周期（阶段 11）——interview_session 加 status 字段。
-- IN_PROGRESS = 进行中（入场默认）；FINISHED = 已结束（用户点「结束面试」）。
-- 结束语义：面试官不再出题（每轮注入已结束提示，只答疑复盘）；评估报告仅结束后可看（快照定稿）。
ALTER TABLE interview_session
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'IN_PROGRESS';
