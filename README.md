# DrawSpace — Backend

Real-time multiplayer **infinite drawing playground**. 2–5 participants share a canvas with
world-coordinate drawing ops, synchronized over STOMP/WebSocket (SockJS) and persisted to
PostgreSQL as an append-only event log with periodic canonical snapshots. This repository is
the Spring Boot 4 backend; a React 18 + Vite 5 frontend connects to it over REST + WebSocket
(the two projects are wired together by their `.env` files, see
[Configuration](#configuration)).

Everything a client needs to converge on the same picture: create/join a room → load the
latest snapshot → replay the event history → subscribe to live topics → draw. The backend is
the canonical state keeper: it validates, dedupes, sequences, persists and broadcasts every
operation, and rebuilds canvas state deterministically from the database at any time.

## Features

- **Real-time STOMP drawing** — SockJS endpoint `/ws-drawspace`, application prefix `/app`,
  broadcast topics `/topic/...`, per-user queues for acks and errors.
- **World coordinates** — ops carry world-space points/positions; pan and zoom are 100%
  client-side (per-user camera), only throttled informational `/viewport` updates are published.
- **Rooms of 2–5** — concurrency-safe capacity: the join transaction takes a
  `PESSIMISTIC_WRITE` lock on the room row *before* counting and inserting, with a
  `UNIQUE(room_id, participant_id)` backstop.
- **Event log + sequence numbers** — every accepted op gets a per-room monotonic
  `sequence_number`; history is paged ascending for client replay.
- **Idempotent dedupe by `eventId`** — clients retry unacked ops with the *same* UUID;
  duplicates return the existing sequence without re-broadcast (also race-safe via a unique
  index + `DataIntegrityViolationException` fallback).
- **Snapshots every `SNAPSHOT_INTERVAL`** accepted events — canonical state
  (`canvasBg`, all layers, live objects, lastSequence) stored in `canvas_snapshots`.
- **Contributor stats** — first/last contribution timestamps, per-participant operation
  counts, tools used.
- **REST API** for room lifecycle and drawing reads (table below).
- **Room-token auth** — `participantToken` (UUID secret) verified server-side on every
  privileged action; WebSocket CONNECT carries it as a Bearer header.
- **Flyway schema** — single `V1__drawspace_init.sql` migration, snake_case, JSONB payloads.
- **Tests** — 5 JUnit 5 + Mockito unit test classes, no database or Docker required
  (`mvn test`).

## Architecture

Monolith, layered:

```
controller/   REST endpoints (RoomController, DrawingController, DrawingDetailsController)
websocket/    STOMP message handlers (DrawingWebSocketController) + session lifecycle listener
service/      business logic: RoomService, DrawingPersistenceService (save pipeline §6),
              CanvasStateService (canonical replay §8), SnapshotService, ParticipantService,
              ContributorService, OperationValidator, PresenceBroadcaster, RoomCleanupScheduler
repository/   Spring Data JPA repositories (6 entities)
config/       WebSocketConfig (broker/endpoint wiring), WebSocketAuthInterceptor (CONNECT auth),
              SecurityConfig (stateless + CORS), DrawSpaceProperties, ParticipantAuthConfig
domain/ dto/  enums, request/response/payload records
util/         RoomCodes, ColorPalette
```

Save pipeline (§6, `@Transactional`): validate room active/unexpired → validate participant
active → validate op (type/tool compat, ranges, payload ≤ 512 KB) → dedupe by `eventId` →
lock room row (`PESSIMISTIC_WRITE`) → `sequence = max+1` → insert `drawing_event` → upsert
`drawing_contributors` → update `drawing.updated_at` → **after commit**: broadcast the
enriched op to `/topic/room/{code}/draw`, ack to `/user/queue/ack`, and every
`SNAPSHOT_INTERVAL` events persist + announce a snapshot. Persistence failures produce no
broadcast and no ack (the client retries the same `eventId`).

> **Single instance only.** The STOMP broker is Spring's *in-memory simple broker*
> (`enableSimpleBroker("/topic", "/queue")`) and room capacity/expiry rely on JVM-level
> scheduling and DB row locks. Running more than one backend instance behind a load
> balancer would split the pub/sub fabric — horizontal scaling requires an external broker
> (e.g. RabbitMQ STOMP relay or Redis pub/sub) and moving the scheduler/locks to the
> database. This is explicitly out of scope.

## Tech stack

| Component | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.0.9 (webmvc, websocket, data-jpa, security, validation, flyway, actuator) |
| Database | PostgreSQL 14+ (JSONB for `event_data` / `snapshot_data` / `tools_used`) |
| Schema migration | Flyway (`V1__drawspace_init.sql`) |
| Real-time | STOMP over WebSocket with SockJS fallback, in-memory simple broker |
| Tests | JUnit 5, Mockito, AssertJ (via `spring-boot-starter-test`), no DB |
| Build | Maven (`spring-boot-starter-parent`) |
| Docker | `docker-compose.yml` providing PostgreSQL 16 |

## Prerequisites

- **Java 21** (JDK)
- **Maven 3.9+**
- **PostgreSQL 14+** *or* **Docker** (for `docker compose up -d`)
- (Optional) the DrawSpace frontend, for the full experience

## Quickstart

```bash
# 1. Start PostgreSQL (drawspace/drawspace on localhost:5432)
docker compose up -d

# 2. Configure the backend (defaults already match docker-compose.yml)
cp .env.example .env

# 3. Run the app (Flyway migrates the schema on boot)
mvn spring-boot:run
```

The backend is up on **http://localhost:8080** (REST under `/api`, WebSocket at
`/ws-drawspace`, health at `/actuator/health`).

## Configuration

Every variable from `.env.example` (all optional — defaults shown are baked into
`application.yml`):

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP + WebSocket listen port. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/drawspace` | JDBC URL; must match `docker-compose.yml`. |
| `DB_USERNAME` | `drawspace` | Database user. |
| `DB_PASSWORD` | `drawspace` | Database password. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated allowed origins for REST CORS **and** the SockJS endpoint origin allow-list. |
| `ROOM_TTL_HOURS` | `24` | `rooms.expires_at = created_at + ROOM_TTL_HOURS`; expired rooms are deactivated by the 60 s scheduler. |
| `SNAPSHOT_INTERVAL` | `500` | Persist a canonical snapshot every N accepted drawing events. |
| `STALE_THRESHOLD_MINUTES` | `10` | Participants without a presence heartbeat for this long are reaped as stale. |
| `MAX_PARTICIPANTS` | `5` | Room capacity (CONTRACT: 2–5). |

**How it connects to the frontend** — the frontend `.env` carries
`VITE_API_BASE_URL=http://localhost:8080/api` and `VITE_WS_URL=http://localhost:8080/ws-drawspace`;
both point at this server's `SERVER_PORT`. `CORS_ALLOWED_ORIGINS` **must include the frontend
origin** (Vite dev server default `http://localhost:5173`) or browsers will fail preflights
and the SockJS handshake with 403. JSON dates cross the wire as ISO-8601 strings (`Instant`).

## REST API

Errors are always JSON `{status, error, message, timestamp}`.

| Method | Path | Body | Response | Errors |
|---|---|---|---|---|
| POST | `/api/rooms` | `{displayName (2–24), roomTitle? (≤60)}` | 201 `{roomCode, roomTitle, drawingId, participantId, participantToken, displayName, color, maxParticipants}` | 400 validation |
| POST | `/api/rooms/{roomCode}/join` | `{displayName (2–24)}` | 200 same as create + `participants: [ParticipantResponse]` | 404 unknown code, 409 full, 410 expired/inactive |
| GET | `/api/rooms/{roomCode}` | — | `{roomCode, title, drawingId, ownerParticipantId, maxParticipants, activeParticipantCount, isActive, createdAt}` | 404 |
| GET | `/api/rooms/{roomCode}/participants` | — | `[{participantId, displayName, color, isOwner, online, joinedAt}]` | 404 |
| POST | `/api/rooms/{roomCode}/leave` | `{participantId, participantToken}` | 204 | 403 bad token/not enrolled, 404 |
| DELETE | `/api/rooms/{roomCode}/canvas` | `{participantId, participantToken}` | 204 (owner only; persists a CLEAR event through the standard pipeline) | 403 non-owner/bad token, 404 |
| GET | `/api/drawings/{drawingId}` | — | `{drawingId, title, roomCode, createdByParticipantId, creatorDisplayName, createdAt, updatedAt, totalOperations, contributorCount}` | 404 |
| GET | `/api/drawings/{drawingId}/contributors` | — | `{drawingId, totalOperations, contributors: [{participantId, displayName, color, firstContributedAt, lastContributedAt, totalOperations, toolsUsed}]}` | 404 |
| GET | `/api/drawings/{drawingId}/history?afterSequence=0&limit=500` | — | `{drawingId, lastSequence, hasMore, events: [{eventId, participantId, displayName, sequenceNumber, eventType, eventData, createdAt}]}` ascending by `sequenceNumber` (limit capped at 1000) | 404 |
| GET | `/api/drawings/{drawingId}/snapshot` | — | 200 `{drawingId, sequenceNumber, snapshotData, createdAt}` | 404 when no snapshot exists yet |

A ready-to-run Postman collection with test scripts that chain `roomCode` /
`participantId` / `participantToken` / `drawingId` lives at
[`postman/DrawSpace.postman_collection.json`](postman/DrawSpace.postman_collection.json).

## WebSocket

- **Endpoint:** `/ws-drawspace` (SockJS enabled; origin allow-list = `CORS_ALLOWED_ORIGINS`).
- **Connect headers (native STOMP headers):** `Authorization: Bearer <participantToken>`
  and `X-Room-Code: <roomCode>`. The interceptor validates room + token, sets the STOMP
  `Principal` name = `participantId`, and pins the session to that room; every handler
  derives identity from the `Principal`, **never** from the payload.

**Client → server** (application prefix `/app`):

| Destination | Payload | Persisted? |
|---|---|---|
| `/app/room/{code}/draw` | `DrawingOperationMessage` (see below) | yes |
| `/app/room/{code}/undo` | `{eventId, targetEventId}` | yes (normalized into a UNDO op) |
| `/app/room/{code}/redo` | `{eventId, targetEventId}` (target = the UNDO event's id) | yes (normalized into a REDO op) |
| `/app/room/{code}/clear` | `{eventId}` (server generates one if blank) | yes (normalized into a CLEAR op) |
| `/app/room/{code}/cursor` | `{x, y}` world coords | **no** — relayed with `displayName`/`color` attached |
| `/app/room/{code}/viewport` | `{x, y, zoom, width, height}` | **no** — minimap indicator relay only |
| `/app/room/{code}/presence` | `{status: "ACTIVE"}` (~25 s heartbeat) | no — refreshes `last_active_at` |

**Server → client:**

| Topic | Payload |
|---|---|
| `/topic/room/{code}/draw` | accepted op enriched with `participantId, displayName, participantColor, sequenceNumber, serverTimestamp` (the op's own `color` keeps its stroke/object meaning) |
| `/topic/room/{code}/cursor` | `{participantId, displayName, color, x, y}` |
| `/topic/room/{code}/participants` | full `[ParticipantResponse]` after any join/leave/disconnect/cleanup |
| `/topic/room/{code}/events` | `{type: "PARTICIPANT_JOINED"\|"PARTICIPANT_LEFT"\|"OWNER_CHANGED"\|"SNAPSHOT_SAVED", participantId?, displayName?, message, timestamp}` |
| `/topic/room/{code}/viewport` | `{participantId, displayName, color, x, y, zoom, width, height}` |
| `/topic/room/{code}/canvas` | `{type: "SNAPSHOT_CREATED", sequenceNumber}` |
| `/user/queue/ack` | `{eventId, sequenceNumber, status: "SAVED"}` to the originating participant |
| `/user/queue/errors` | `{eventId?, message}` via `@MessageExceptionHandler` + `@SendToUser` |

## Event types

`DrawingOperationMessage.type` is one of the 15 `EventType` values below. Send only the
fields relevant to the op type; unknown enums, type/tool mismatches and range violations are
rejected to `/user/queue/errors` (SELECT/MOVE/LASSO are client-side gestures and are never
accepted as ops).

| Type | Key payload fields (beyond `eventId`) | Effect in canonical replay (§8) |
|---|---|---|
| `BRUSH_STROKE` | `objectId, layerId, tool (PENCIL…TEXTURE/ERASER), color, brushSize, opacity, points` | creates a live stroke object |
| `SHAPE` | `objectId, layerId, tool (LINE…SPEECH_BUBBLE), points (exactly 2 endpoints), width/height, sides, fillColor` | creates a live shape object |
| `TEXT` | `objectId, layerId, text (≤2000), font, fontSize (8–200), bold, italic, align, bgColor, x, y` | creates a live text object |
| `STICKER` | `objectId, layerId, sticker (≤64 chars), x, y, scale, rotation` | creates a live sticker object |
| `OBJECT_DUPLICATE` | `objectId, layerId, tool + fields of the copied object` | creates a live object |
| `OBJECT_TRANSFORM` | `objectId, transform: {x, y, scale, rotation}` (absolute) | records prior geometry, applies new geometry |
| `OBJECT_DELETE` | `objectId` | records prior `deleted` flag, sets `deleted = true` |
| `LAYER_CREATE` | `layerId, name (≤60)` | adds a layer (order = count, visible, unlocked) |
| `LAYER_UPDATE` | `layerId, name?, visible?, locked?` | records prior props, merges changes |
| `LAYER_REORDER` | `order: [layerId…]` (full ordered list) | records prior orders, assigns new indexes |
| `LAYER_DELETE` | `layerId` | records prior props, sets `deleted = true` |
| `CANVAS_BG` | `canvasBg: "#RRGGBB"` | records prior background, sets it |
| `UNDO` | `targetEventId` | inverts the target op (add→hide, transform→prior geometry, delete→restore, clear→restore, layers/bg→priors) and stores redo info |
| `REDO` | `targetEventId` = **the UNDO event's id** | re-applies the inversion from the undo ledger |
| `CLEAR` | *(no extra fields)* | records the live object ids, hides them all |

Example — `BRUSH_STROKE` (published to `/app/room/{code}/draw`):

```json
{
  "eventId": "8f0b9c2e-5f1a-4b7e-9c3d-2a1b0c9e8f7a",
  "type": "BRUSH_STROKE",
  "objectId": "stroke-1",
  "layerId": "layer-1",
  "tool": "PENCIL",
  "color": "#1E90FF",
  "brushSize": 4,
  "opacity": 0.9,
  "points": [
    { "x": 102.5, "y": -40.0 },
    { "x": 105.0, "y": -38.25 },
    { "x": 108.75, "y": -35.5 }
  ],
  "clientTimestamp": 1730000000000
}
```

The server persists the full field map as `drawing_events.event_data` (JSONB) and broadcasts
a copy enriched with `participantId`, `displayName`, `participantColor`, `sequenceNumber`
and `serverTimestamp`.

## Data flows

**Save pipeline** (`DrawingPersistenceService.handleOperation`, §6): validate room
active/unexpired → validate participant active → validate op → **dedupe** by `eventId`
(duplicate: return the existing enriched copy without re-broadcast, still ack its sequence)
→ **lock** the room row (`PESSIMISTIC_WRITE`) → **sequence** = max+1 per room → persist
`drawing_event` → upsert contributor stats → touch `drawing.updated_at` → **after commit**:
broadcast to `/draw`, ack `/user/queue/ack`, and on `sequence % SNAPSHOT_INTERVAL == 0`
build + save a snapshot and announce it. A failure anywhere before commit means no broadcast
and no ack — clients retry the same `eventId`, which dedupes idempotently (including a race
on insert, resolved by re-fetching the winner).

**Join / rejoin** (§13): REST create/join returns the identity; the client loads
`GET /snapshot` (404 → empty state with default "Layer 1"), applies `snapshotData`, then
loops `GET /history?afterSequence=lastApplied&limit=500` applying every event ascending until
`hasMore=false`, then connects the WebSocket and dedupes live ops by `eventId` (skipping
`sequenceNumber ≤ lastApplied`, buffering brief out-of-order arrivals). Because replay is
deterministic (§8) every client converges on the same picture. Reconnecting participants
reuse the same `participantId`/`participantToken`, so identity, color and contributor stats
are stable.

**Stale cleanup** (`RoomCleanupScheduler`, every 60 s): participants whose `last_active_at`
is older than `STALE_THRESHOLD_MINUTES` get `left_at` set and a per-room
`/participants` broadcast; expired rooms (`expires_at` passed) are deactivated
(`is_active=false`), after which joins and ops fail with 410.

**Owner transfer**: when the owner leaves (REST leave or disconnect cleanup), ownership moves
to the earliest-joined remaining active participant and an `OWNER_CHANGED` notice is
broadcast on `/topic/room/{code}/events`.

## Database schema (Flyway `V1__drawspace_init.sql`)

Six tables, all snake_case:

- **`rooms`** — `room_code` (6 chars, unique), `title`, `owner_participant_id`,
  `max_participants`, `created_at`, `expires_at`, `is_active`.
- **`drawings`** — one per room: `title`, `room_id`, `created_by_participant_id`,
  `created_at`, `updated_at`.
- **`room_participants`** — identity + seat: `participant_id`, `participant_token` (UUID
  secret), `display_name`, `assigned_color`, `joined_at`, `last_active_at`, `left_at`
  (NULL = enrolled); `UNIQUE(room_id, participant_id)`.
- **`drawing_contributors`** — per-drawing stats: `first/last_contributed_at`,
  `total_operations`, `tools_used` (JSONB array); `UNIQUE(drawing_id, participant_id)`.
- **`drawing_events`** — the append-only log: `event_id` (unique, idempotency key),
  `drawing_id`, `room_id`, `participant_id`, `sequence_number` (`UNIQUE(room_id,
  sequence_number)`), `event_type`, `event_data` (JSONB); indexed `(drawing_id, sequence_number)`.
- **`canvas_snapshots`** — `drawing_id`, `sequence_number`, `snapshot_data` (JSONB, §9),
  optional `preview_image_reference`; indexed `(drawing_id, sequence_number DESC)`.

## Testing

```bash
mvn test
```

No database, Docker or Spring context is needed — every test is a plain JUnit 5 + Mockito
unit test. Test classes and what they prove:

| Test class | Proves |
|---|---|
| `service/CanvasStateServiceTest` | CONTRACT §8 replay determinism (same events → identical state), add/transform/delete/clear semantics with UNDO/REDO inversion, layer create/update/reorder/delete semantics, CANVAS_BG, graceful no-ops for unknown ids, §9 snapshot-data round trip (`toSnapshotData` → fresh state → `applySnapshotData` → same live objects), and `buildState`/`applyLive` snapshot-then-events rebuild incl. the stale-sequence rebuild path. |
| `service/DrawingPersistenceServiceTest` | Save pipeline §6: sequence assignment, idempotent dedupe + insert-race fallback, validation gates (gesture tools, oversized text/points), contributor upsert, snapshot trigger exactly at `SNAPSHOT_INTERVAL`, immediate broadcast/ack fallback when no transaction synchronization is active, and that cursor relay never persists. |
| `service/RoomServiceTest` | Room lifecycle §3 #1–#5 / §10: room-code collision retry, owner color assignment (§2 palette), capacity enforcement, expiry/inactive handling, owner transfer on leave. |
| `websocket/DrawingWebSocketControllerTest` | WS contract §4: null/mismatched Principal → error on `/user/queue/errors` and no persistence; identity from Principal + session attributes; undo/redo/clear normalized into typed ops through the standard pipeline; cursor/viewport relayed with participant identity and never persisted; presence touches the participant service; exception safety net. |
| `dto/ValidationTest` | Bean Validation on request DTOs: `displayName` 2–24 boundaries on create/join, optional `roomTitle ≤ 60`, `@NotBlank` identity/secret fields for leave and clear-canvas. |

**Manual multi-window test** (fast end-to-end check without the frontend project):

1. `docker compose up -d && mvn spring-boot:run`
2. Open two browser windows on any page that can run JavaScript (e.g. the frontend, or a
   scratch HTML page with `sockjs-client` + `stompjs` from a CDN).
3. Window A: `POST /api/rooms` (use Postman or curl) → note `roomCode`/`participantToken`;
   connect to `/ws-drawspace` with `Authorization: Bearer <token>`, `X-Room-Code: <code>`.
4. Window B: `POST /api/rooms/{code}/join` with another display name; connect the same way.
5. Publish a BRUSH_STROKE from A: both windows receive the enriched op on
   `/topic/room/{code}/draw`; A also gets `/user/queue/ack`. Both windows show B's cursor
   when B publishes to `/app/room/{code}/cursor`. Re-sending the identical `eventId`
   must not duplicate the stroke.

## Design decisions & honest limitations

- **Room-token, not full auth.** Identity is a UUID secret issued at create/join and verified
  server-side per action. There are no accounts, no passwords, no sessions; anyone with the
  room code *and* a valid token can act as that participant. Fine for a shared-canvas
  playground, not a replacement for real authentication.
- **Undo/delete preserve audit history.** UNDO, REDO, OBJECT_DELETE and CLEAR are events in
  the log (soft deletes via the `deleted` flag in canonical state), never destructive edits —
  which is what makes replay deterministic and contributor history complete. There is no
  "hard delete" or event compaction yet, so the event log grows unbounded (snapshots bound
  replay *length*, not storage).
- **Operation counts are activity statistics, not creative-effort measures.** A single
  drag publishes one OBJECT_TRANSFORM; one 3-second scribble publishes one BRUSH_STROKE.
  `total_operations` measures participation, not artistic contribution.
- **Snapshot failures are logged and non-blocking.** Snapshot creation runs on the
  after-commit path; if it fails the draw op already succeeded and the log remains the
  source of truth — the next snapshot attempt simply covers the gap.
- **Cursor and viewport are never persisted.** They are relayed for presence/awareness only
  and vanish on disconnect; there is deliberately no cursor history.
- **In-memory STOMP broker (single instance).** See the note under
  [Architecture](#architecture) — scale-out needs an external broker.
- **UNDO across a snapshot boundary degrades to a no-op.** Undo ledgers (prior geometry,
  priors, cleared ids) exist only for events after the latest snapshot, so replaying an UNDO
  whose target predates the snapshot skips it — identical behavior on client and server (§8).
- **Validate-mode schema.** Hibernate runs `ddl-auto: validate`; the Flyway migration is the
  single source of schema truth.

## Troubleshooting

| Symptom | Cause & fix |
|---|---|
| Browser calls fail with CORS 403 / SockJS handshake error | `CORS_ALLOWED_ORIGINS` does not include the frontend origin. Add it (e.g. `http://localhost:5173`) and restart. |
| Boot fails with database auth/connection errors | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` don't match the running PostgreSQL. Check `docker compose ps` and compare with `.env`. |
| WS connect rejected | CONNECT must carry `Authorization: Bearer <participantToken>` **and** `X-Room-Code: <roomCode>` with a token issued by create/join for exactly that room. A token from another room, a left/stale participant, or an expired room is rejected on CONNECT. |
| Join returns 409 | Room is full (active capacity reached, `MAX_PARTICIPANTS` default 5). Capacity counts non-stale enrolled participants — stale seats free up after the 60 s sweep. |
| Join returns 410 | Room expired (`expires_at` passed → `is_active=false`). Create a new room. |
| No ack for a published draw | The op was rejected before commit (see `/user/queue/errors` — invalid type/tool, range violation, payload too large) or persistence failed. In both cases nothing was broadcast; retry with the **same** `eventId` after fixing the payload. |
| History seems empty after drawing | You're querying a different `drawingId` than the room's, or `afterSequence` is already at `lastSequence`. Use `GET /api/rooms/{roomCode}` to confirm the room's `drawingId`. |
