-- 两张表：任务、步骤。状态机就活在这里，不在内存里。

create table if not exists llm_task (
  id           varchar(32)  primary key,
  tenant       varchar(64)  not null,
  kind         varchar(32)  not null,
  input        clob,                          -- 任务输入也要落库，否则重启后续跑没有了原料
  status       varchar(16)  not null,        -- PENDING / RUNNING / SUCCEEDED / FAILED
  current_step varchar(32),
  lease_until  timestamp,                     -- 租约：跑这个任务的进程定期续期
  created_at   timestamp    not null,
  updated_at   timestamp    not null,
  error        varchar(500)
);

create table if not exists llm_task_step (
  id          bigint auto_increment primary key,
  task_id     varchar(32) not null,
  step_name   varchar(32) not null,
  seq         int         not null,
  status      varchar(16) not null,          -- PENDING / RUNNING / SUCCEEDED / FAILED
  attempt     int         not null default 0,
  output      clob,                          -- 产物落库：续跑时直接用，不重算
  started_at  timestamp,
  finished_at timestamp,
  error       varchar(500),
  -- 同一个任务里的同一步骤只有一行：这是幂等的基础
  constraint uk_task_step unique (task_id, step_name)
);