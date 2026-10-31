-- 一次调用一行。这张表就是这一章的全部产出：能对账、能归因、能算钱。
create table if not exists llm_call_log (
  id                bigint auto_increment primary key,
  trace_id          varchar(32)  not null,   -- 一个请求一个，重试共用
  attempt           int          not null,   -- 第几次尝试
  tenant            varchar(64)  not null,   -- 谁花的钱
  feature           varchar(32),             -- 哪个功能花的
  provider          varchar(32),
  model             varchar(64)  not null,
  stream            boolean      not null,
  prompt_tokens     int          not null,
  cached_tokens     int          not null,
  completion_tokens int          not null,
  tokens_estimated  boolean      not null,   -- 这行的 token 是估的还是厂商给的
  cost_micros       bigint       not null,   -- 花了多少「微元」(1e-6 元)，用整数存钱
  ttft_ms           int,                     -- 首字延迟：用户真正感受到的那个
  total_ms          int,
  status            varchar(16)  not null,   -- OK / RATE_LIMITED / FAILED
  error_code        varchar(64),
  created_at        timestamp    not null
);

create index if not exists idx_call_trace on llm_call_log (trace_id);
create index if not exists idx_call_created on llm_call_log (created_at);