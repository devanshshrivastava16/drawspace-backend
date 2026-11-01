-- =====================================================================
-- DrawSpace initial schema — CONTRACT §11 (PostgreSQL, snake_case)
-- =====================================================================

CREATE TABLE rooms (
    id                    uuid         PRIMARY KEY,
    room_code             varchar(6)   NOT NULL,
    title                 varchar(60),
    owner_participant_id  uuid         NOT NULL,
    max_participants      int          NOT NULL DEFAULT 5,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    expires_at            timestamptz  NOT NULL,
    is_active             boolean      NOT NULL DEFAULT true,
    CONSTRAINT uq_rooms_room_code UNIQUE (room_code)
);

CREATE TABLE drawings (
    id                         uuid         PRIMARY KEY,
    title                      varchar(120) NOT NULL,
    room_id                    uuid         NOT NULL REFERENCES rooms(id),
    created_by_participant_id  uuid         NOT NULL,
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE room_participants (
    id                 uuid         PRIMARY KEY,
    room_id            uuid         NOT NULL REFERENCES rooms(id),
    participant_id     uuid         NOT NULL,
    participant_token  uuid         NOT NULL,
    display_name       varchar(24)  NOT NULL,
    assigned_color     varchar(9)   NOT NULL,
    joined_at          timestamptz  NOT NULL DEFAULT now(),
    last_active_at     timestamptz  NOT NULL DEFAULT now(),
    left_at            timestamptz  NULL,
    CONSTRAINT uq_room_participants_room_participant UNIQUE (room_id, participant_id)
);

CREATE TABLE drawing_contributors (
    id                    uuid         PRIMARY KEY,
    drawing_id            uuid         NOT NULL REFERENCES drawings(id) ON DELETE CASCADE,
    participant_id        uuid         NOT NULL,
    display_name          varchar(24)  NOT NULL,
    assigned_color        varchar(9)   NOT NULL,
    first_contributed_at  timestamptz  NOT NULL,
    last_contributed_at   timestamptz  NOT NULL,
    total_operations      bigint       NOT NULL DEFAULT 0,
    tools_used            jsonb        NOT NULL DEFAULT '[]',
    CONSTRAINT uq_drawing_contributors_drawing_participant UNIQUE (drawing_id, participant_id)
);

CREATE TABLE drawing_events (
    id               uuid         PRIMARY KEY,
    event_id         uuid         NOT NULL,
    drawing_id       uuid         NOT NULL REFERENCES drawings(id) ON DELETE CASCADE,
    room_id          uuid         NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    participant_id   uuid         NOT NULL,
    sequence_number  bigint       NOT NULL,
    event_type       varchar(32)  NOT NULL,
    event_data       jsonb        NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_drawing_events_event_id UNIQUE (event_id),
    CONSTRAINT uq_drawing_events_room_sequence UNIQUE (room_id, sequence_number)
);

CREATE TABLE canvas_snapshots (
    id                       uuid          PRIMARY KEY,
    drawing_id               uuid          NOT NULL REFERENCES drawings(id) ON DELETE CASCADE,
    sequence_number          bigint        NOT NULL,
    snapshot_data            jsonb         NOT NULL,
    preview_image_reference  varchar(512)  NULL,
    created_at               timestamptz   NOT NULL DEFAULT now()
);

-- CONTRACT §11 indexes
CREATE INDEX idx_drawing_events_drawing_sequence ON drawing_events (drawing_id, sequence_number);
CREATE INDEX idx_room_participants_room_left ON room_participants (room_id, left_at);
CREATE INDEX idx_canvas_snapshots_drawing_sequence ON canvas_snapshots (drawing_id, sequence_number DESC);
