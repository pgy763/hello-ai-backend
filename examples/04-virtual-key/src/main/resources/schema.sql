-- 只保留 4.2 里那两张关键表。日志表见正文 4.6（示例不落库，只打日志）。
-- 与正文 DDL 的差别：去掉 BIGINT UNSIGNED / COMMENT —— H2 不认，MySQL 认。
-- 换成 MySQL 时把这两处补回去即可，列名和索引完全一致。

create table if not exists llm_quota_bucket (
  id           bigint auto_increment primary key,
  key_id       bigint      not null,
  period       varchar(8)  not null,
  period_start date        not null,
  total_tokens bigint      not null,
  reserved     bigint      not null default 0,
  settled      bigint      not null default 0,
  constraint uk_key_period unique (key_id, period, period_start)
);

create table if not exists llm_quota_hold (
  id         bigint auto_increment primary key,
  bucket_id  bigint      not null,
  request_id varchar(32) not null,
  amount     bigint      not null,
  status     tinyint     not null default 0,
  created_at timestamp   not null default current_timestamp,
  expire_at  timestamp   not null,
  constraint uk_request unique (request_id)
);

-- 定时任务扫的是「PENDING 且已过期」，status 放前面能跳过整段已结算的行
create index if not exists idx_expire on llm_quota_hold (status, expire_at);