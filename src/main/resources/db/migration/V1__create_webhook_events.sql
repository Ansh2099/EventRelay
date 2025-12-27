create table if not exists webhook_events (
    id uuid primary key,
    source varchar(255) not null,
    external_event_id varchar(255) not null,
    payload jsonb not null,
    state varchar(32) not null,
    retry_count int not null default 0,
    next_retry_at timestamptz null,
    failure_reason text null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint uk_webhook_events_source_external_event_id unique (source, external_event_id)
);

create index if not exists idx_webhook_events_state_next_retry_at
    on webhook_events (state, next_retry_at);

create index if not exists idx_webhook_events_source
    on webhook_events (source);
