-- Users and authentication -------------------------------------------------------------
create table users (
    id                    uuid primary key,
    email                 varchar(254) not null unique,
    username              varchar(50) unique,
    password_hash         varchar(100),
    display_name          varchar(100) not null,
    auth_provider         varchar(20)  not null,
    google_subject        varchar(255) unique,
    role                  varchar(20)  not null,
    enabled               boolean      not null default true,
    failed_login_attempts integer      not null default 0,
    locked_until          timestamptz,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0
);

create table refresh_tokens (
    id            uuid primary key,
    user_id       uuid        not null references users (id) on delete cascade,
    token_hash    varchar(64) not null unique,
    family_id     uuid        not null,
    expires_at    timestamptz not null,
    revoked_at    timestamptz,
    revoke_reason varchar(20),
    created_at    timestamptz not null
);
create index idx_refresh_tokens_family on refresh_tokens (family_id);

-- Videos --------------------------------------------------------------------------------
create table videos (
    id               uuid primary key,
    owner_id         uuid         not null references users (id),
    tracking_id      varchar(64)  not null,
    raw_object_key   varchar(255) not null unique,
    object_key       varchar(255) unique,
    content_type     varchar(100) not null,
    size_bytes       bigint,
    final_size_bytes bigint,
    duration_ms      integer check (duration_ms >= 0),
    status           varchar(20)  not null,
    failure_code     varchar(40),
    processing_run   integer      not null default 0,
    client_upload_id uuid         not null,
    recorded_at      timestamptz,
    uploaded_at      timestamptz,
    processed_at     timestamptz,
    deleted_at       timestamptz,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0,
    constraint uq_videos_owner_client_upload unique (owner_id, client_upload_id)
);
create index idx_videos_owner_created on videos (owner_id, created_at desc);
create index idx_videos_owner_tracking on videos (owner_id, tracking_id);
create index idx_videos_status_created on videos (status, created_at);

create table video_processing_attempts (
    id             uuid primary key,
    video_id       uuid        not null references videos (id) on delete cascade,
    run_number     integer     not null,
    attempt_number integer     not null,
    status         varchar(20) not null,
    error_code     varchar(40),
    error_message  varchar(500),
    started_at     timestamptz not null,
    finished_at    timestamptz,
    constraint uq_attempt unique (video_id, run_number, attempt_number)
);

-- Messaging -----------------------------------------------------------------------------
create table outbox_events (
    id             uuid primary key,
    aggregate_id   uuid         not null,
    event_type     varchar(100) not null,
    topic          varchar(200) not null,
    message_key    varchar(100) not null,
    payload        jsonb        not null,
    correlation_id varchar(64),
    created_at     timestamptz  not null,
    published_at   timestamptz,
    attempts       integer      not null default 0,
    last_error     varchar(500)
);
create index idx_outbox_pending on outbox_events (created_at) where published_at is null;

create table processed_events (
    event_id     uuid primary key,
    consumer     varchar(100) not null,
    processed_at timestamptz  not null
);
