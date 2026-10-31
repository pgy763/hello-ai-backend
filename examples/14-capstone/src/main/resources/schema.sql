create table if not exists ticket (
  id varchar(32) primary key,
  text clob not null,
  status varchar(16) not null,
  attempt int not null default 0,
  category varchar(32),
  answer clob,
  citations varchar(255),
  error varchar(255),
  cost_micros bigint not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists idx_ticket_status on ticket (status);

create table if not exists llm_call (
  id bigint auto_increment primary key,
  ticket_id varchar(32),
  provider varchar(32),
  purpose varchar(16),
  prompt_tokens int,
  completion_tokens int,
  cost_micros bigint,
  latency_ms bigint,
  created_at timestamp not null
);