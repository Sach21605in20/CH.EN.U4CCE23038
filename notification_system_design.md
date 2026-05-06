# Notification System Design

---

## Stage 1 — API Design and Real-Time Mechanism

### Core Actions the Platform Supports

- Fetch all notifications for a student
- Mark a single notification as read
- Mark all notifications as read
- Delete a notification
- Stream real-time notifications to a connected student

---

### REST API Endpoints

---

#### GET /api/v1/notifications?studentId={id}

Fetch all notifications for a student.

**Headers**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Query Parameters**
```
studentId: integer (required)
```

**Response — 200 OK**
```json
{
  "studentId": 1042,
  "notifications": [
    {
      "id": "d146095a-0d86-4834-9869-3900a14576bc",
      "type": "Placement",
      "message": "CSX Corporation hiring",
      "isRead": false,
      "createdAt": "2026-04-22T17:51:18"
    }
  ]
}
```

---

#### PATCH /api/v1/notifications/{id}/read

Mark a single notification as read.

**Headers**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Path Parameters**
```
id: UUID (required) — notification ID
```

**Request Body**
```json
{
  "studentId": 1042
}
```

**Response — 200 OK**
```json
{
  "id": "d146095a-0d86-4834-9869-3900a14576bc",
  "isRead": true,
  "message": "notification marked as read"
}
```

---

#### PATCH /api/v1/notifications/read-all?studentId={id}

Mark all notifications for a student as read.

**Headers**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Query Parameters**
```
studentId: integer (required)
```

**Response — 200 OK**
```json
{
  "studentId": 1042,
  "updatedCount": 14,
  "message": "all notifications marked as read"
}
```

---

#### DELETE /api/v1/notifications/{id}

Delete a single notification.

**Headers**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Path Parameters**
```
id: UUID (required) — notification ID
```

**Request Body**
```json
{
  "studentId": 1042
}
```

**Response — 200 OK**
```json
{
  "id": "d146095a-0d86-4834-9869-3900a14576bc",
  "message": "notification deleted successfully"
}
```

---

#### GET /api/v1/notifications/stream?studentId={id}

Server-Sent Events (SSE) stream for real-time notifications.

**Headers**
```
Authorization: Bearer <token>
Accept: text/event-stream
Cache-Control: no-cache
```

**Query Parameters**
```
studentId: integer (required)
```

**Response — 200 OK (streaming)**
```
Content-Type: text/event-stream

data: {"id":"d146095a-...","type":"Placement","message":"Infosys hiring","createdAt":"2026-05-06T10:00:00"}

data: {"id":"a1b2c3d4-...","type":"Result","message":"mid-sem results published","createdAt":"2026-05-06T10:05:00"}
```

---

### Real-Time Notification Mechanism — SSE

**Why SSE over WebSockets**

Notifications are unidirectional — the server pushes data to the client. The client never sends data back over the same channel. SSE is purpose-built for this pattern. WebSockets provide bidirectional communication, which adds complexity and overhead for a use case that does not need it. SSE works over plain HTTP/1.1, is automatically reconnected by the browser, and is simpler to implement and proxy.

**How the connection stays open**

When a student opens the notification page, the client makes a GET request to `/api/v1/notifications/stream?studentId={id}` with the `Accept: text/event-stream` header. The server holds this connection open and keeps an `SseEmitter` registered for this student ID. The connection remains open until the student closes the page or a network error occurs. The browser will automatically reconnect using the `Last-Event-ID` header so no events are missed.

**How new notifications are pushed without polling**

When a new notification is created for a student (for example, when HR triggers a bulk send), the server looks up the active `SseEmitter` registered for that student and calls `emitter.send(data)`. This pushes the JSON payload directly to the connected client over the open HTTP connection. The client receives the event without ever having to ask for it.

## Stage 2 — Database Design

### Chosen Database: PostgreSQL

**Justification**

Notification data is relational — each notification belongs to exactly one student, and the student table enforces referential integrity via a foreign key. PostgreSQL offers native enum types (avoiding magic strings), strong composite index support, and JSONB if the message payload ever needs to be extended. It is the most production-appropriate choice for structured, write-heavy relational data at this scale.

---

### Schema

```sql
CREATE TYPE notification_type AS ENUM ('Placement', 'Result', 'Event');

CREATE TABLE students (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id          INTEGER      NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    notification_type   notification_type NOT NULL,
    message             TEXT         NOT NULL,
    is_read             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

---

### Problems at Scale (50,000 students, 5,000,000 notifications)

**Problem 1 — Full table scans on unindexed columns**
Filtering by `student_id` and `is_read` without indexes means PostgreSQL reads all 5 million rows for every notification fetch. At 50,000 concurrent students loading pages this becomes catastrophic.

**Solution** — Add a composite index: `(student_id, is_read, created_at DESC)`. This covers the most common query pattern in a single index scan.

**Problem 2 — Table bloat from historical notifications**
5 million rows grows over time. Old, read notifications from previous years still live in the same table and slow every query.

**Solution** — Partition the table by `created_at` (monthly or quarterly). Archive partitions older than 6 months to cold storage. Active queries only touch the current partition.

**Problem 3 — Write amplification during bulk sends**
50,000 inserts in a tight loop locks pages and stalls reads.

**Solution** — Use `INSERT ... VALUES (batch)` with batch sizes of 500–1000 rows instead of single-row inserts. Run inserts asynchronously through a queue worker.

---

### SQL Queries for Stage 1 APIs

**Fetch all notifications for a student**
```sql
SELECT id, notification_type, message, is_read, created_at
FROM notifications
WHERE student_id = $1
ORDER BY created_at DESC;
```

**Mark one notification as read**
```sql
UPDATE notifications
SET is_read = TRUE
WHERE id = $1 AND student_id = $2;
```

**Mark all notifications as read for a student**
```sql
UPDATE notifications
SET is_read = TRUE
WHERE student_id = $1 AND is_read = FALSE;
```

**Delete a notification**
```sql
DELETE FROM notifications
WHERE id = $1 AND student_id = $2;
```

---

## Stage 3 — Slow Query Analysis

### Is this query accurate?

```sql
SELECT FROM notifications
WHERE student_id = 1042 AND isRead = false
ORDER BY createdAt DESC;
```

No. `SELECT FROM` is missing the column list. It should be `SELECT *` (or explicit columns). Additionally, the column names `isRead` and `createdAt` use camelCase which does not match the schema — they should be `is_read` and `created_at`.

**Corrected query:**
```sql
SELECT *
FROM notifications
WHERE student_id = 1042 AND is_read = FALSE
ORDER BY created_at DESC;
```

---

### Why is it slow?

Without a composite index on `(student_id, is_read, created_at)`, PostgreSQL performs a sequential scan across all 5,000,000 rows to find those matching `student_id = 1042`. After filtering, it sorts the result set by `created_at DESC`, which requires a sort pass over all matching rows. At 5 million rows this scan is expensive even before the sort.

---

### What to change and the likely computation cost

Add a composite index:

```sql
CREATE INDEX idx_notifications_student_read_date
ON notifications (student_id, is_read, created_at DESC);
```

**Before index:** Full sequential scan — O(n) where n = 5,000,000 rows.

**After index:** Index range scan on `student_id = 1042 AND is_read = FALSE`, then reads pre-sorted `created_at DESC` entries directly from the index. Cost drops to O(log n) for the lookup plus O(k) to read k matching rows for that student. For a student with 200 unread notifications, the engine reads ~200 rows instead of 5 million.

---

### Is indexing every column a good idea?

No. Every index:
- Slows down every INSERT, UPDATE, and DELETE because each write must update all indexes
- Wastes disk space proportional to the table size multiplied by the number of indexes
- Confuses the query planner — with many indexes it can choose suboptimal execution plans
- Provides no benefit for columns that are never used in WHERE, JOIN, or ORDER BY clauses

Indexes should be added surgically based on actual query patterns, not defensively on every column.

---

### Query to find all students who got a placement notification in the last 7 days

```sql
SELECT DISTINCT s.id, s.name, s.email
FROM students s
JOIN notifications n ON n.student_id = s.id
WHERE n.notification_type = 'Placement'
  AND n.created_at >= NOW() - INTERVAL '7 days';
  ```

---
## Stage 4 — Performance Under Load

### Problem

Every page load fires a direct database query. With 50,000 students potentially loading simultaneously, the database receives 50,000 concurrent read queries. This overwhelms connection pools and causes high latency or timeouts.

---

### Strategy 1 — Redis Caching per Student

Cache the notification list for each student in Redis with a TTL of 60 seconds. On page load, check Redis first. Only query the database on a cache miss. When a new notification is created for a student, invalidate that student's cache key so the next load fetches fresh data.

**Tradeoffs:**
- Dramatically reduces database reads — 95%+ of page loads served from cache
- Adds infrastructure complexity — Redis cluster must be managed and kept available
- Stale data window — a student may see up to 60-second-old data between invalidations
- Cache invalidation logic must be maintained correctly; missed invalidations cause stale reads

---

### Strategy 2 — Pagination (Cursor-Based)

Instead of loading all notifications on each page load, load only the first 20. As the student scrolls, fetch the next page using a cursor (the `created_at` timestamp of the last seen notification).

**Tradeoffs:**
- Each query is bounded — fetching 20 rows is fast even without a cache
- Reduces payload size — client receives only what is visible, not 500 notifications
- Slightly more complex client logic for infinite scroll
- Does not eliminate DB queries but makes each query cheap

---

### Strategy 3 — Background Pre-Computation

A scheduled job (cron) runs every 30 seconds and pre-fetches and caches notifications for all students who have been active in the last 5 minutes. By the time an active student loads the page, their data is already in Redis.

**Tradeoffs:**
- Zero database hit at page load time for active students
- Wasteful for inactive students — pre-computing cache entries for students who won't load the page wastes CPU and Redis memory
- Adds operational complexity — cron job must be monitored and fault-tolerant

---

### Recommendation

Combine all three: use pagination to bound query size, Redis to serve repeated loads without hitting the database, and background pre-computation to warm the cache for students who are actively using the platform. This gives low latency, bounded database load, and near-real-time freshness.

---

## Stage 5 — Reliable Bulk Notification

### Shortcomings of the Proposed Implementation

```
function notify_all(student_ids: array, message: string):
  for student_id in student_ids:
    send_email(student_id, message)
    save_to_db(student_id, message)
    push_to_app(student_id, message)
```

1. **Sequential loop** — iterating over 50,000 students one at a time is extremely slow. Email delivery alone can take 100–500ms per call. 50,000 × 300ms = 4+ hours to complete.
2. **No error isolation** — if `send_email` throws for one student, the loop halts and no subsequent students receive any notification.
3. **No retry mechanism** — transient failures (network timeout, SMTP rate limit) silently drop notifications.
4. **Tight coupling** — email, DB save, and in-app push are chained synchronously. A slow email provider blocks the DB write for every student.
5. **No visibility** — there is no way to know which students received the notification and which did not.

---

### When 200 Emails Fail Midway

Without per-student status tracking, there is no way to identify which 200 students did not receive the email. A re-run would resend to all 50,000, causing duplicates for the 49,800 who already received it. The correct response is to check the per-student status log, extract the 200 failed IDs, and retry only those entries.

---

### Should DB Save and Email Happen Together?

No. DB save should happen first and independently. Once the notification is persisted, it is safe regardless of whether the email delivers. Email is a retriable side effect. If DB and email are coupled and the DB write succeeds but email fails, the state is ambiguous. If DB write is decoupled and done first, the notification exists in the system and can be re-sent at any time.

---

### Revised Pseudocode — Queue-Based Reliable Design

```
function notify_all(student_ids: array, message: string):
  # Step 1: Persist all notifications to DB in batch (fast, atomic)
  batch_save_to_db(student_ids, message, status="pending")

  # Step 2: Enqueue one message per student into a message queue (Kafka/RabbitMQ)
  for student_id in student_ids:
    enqueue(queue="notification_jobs", payload={student_id, message})

  # Step 3: Workers consume the queue in parallel
  # Each worker processes one job:

function worker_process(job):
  student_id = job.student_id
  message    = job.message

  try:
    send_email(student_id, message)
    push_to_app(student_id, message)
    update_db_status(student_id, message, status="delivered")
  catch EmailFailure as e:
    update_db_status(student_id, message, status="email_failed")
    enqueue(queue="retry_jobs", payload=job, retry_count=job.retry_count+1)
  catch AppPushFailure as e:
    update_db_status(student_id, message, status="push_failed")

  # Dead Letter Queue: after 3 retries, move to DLQ for manual review
  if job.retry_count >= 3:
    enqueue(queue="dead_letter", payload=job)
    return
```

**Key properties of this design:**
- DB write is decoupled and happens first in batch — fast and safe
- Workers run in parallel — 50,000 notifications dispatched in seconds
- Per-student status tracked — exact failure list available at any time
- Retry logic is isolated — only failed students are retried
- Dead letter queue captures persistent failures for manual review

---

## Stage 6 — Priority Inbox Implementation

### Approach

**Scoring Formula:**

```
priorityScore = (typeWeight × 10) + recencyScore
```

**Type weights:**
- Placement = 3
- Result = 2
- Event = 1

**Why multiply by 10:** This creates non-overlapping score tiers. Placement scores are 30–39, Result scores are 20–29, Event scores are 10–19. A very recent Event (score 19) can never outrank an old Placement (score 30). Type always dominates.

**Recency score:**

```
recencyScore = max(0, 9 - hoursSinceCreation)
```

Clamped to [0, 9] to stay within the type tier. A notification created 0 hours ago scores 9 more points than one created 9+ hours ago within the same type.

---

### Why a Min-Heap

A min-heap of fixed size 10 is the optimal data structure for tracking the top N items from a stream.

- For each incoming notification: push it onto the heap — O(log 10) ≈ O(1)
- If the heap exceeds size 10: pop the minimum — O(log 10) ≈ O(1)
- After processing all N notifications: total time O(N log 10) ≈ O(N)

This is more efficient than sorting all notifications (O(N log N)) or scanning for the maximum repeatedly (O(N × 10)).

---

### Handling New Incoming Notifications

When a new notification arrives after the initial top-10 is built, compare its `priorityScore` against the current minimum in the heap:

```
function handle_new_notification(notification):
  score = compute_priority_score(notification)
  if heap.size() < 10:
    heap.push(notification)
  elif score > heap.peek_min().priorityScore:
    heap.pop()        # remove current lowest
    heap.push(notification)  # insert new higher-priority notification
  # else: new notification does not rank in top 10, ignore
```

This maintains the top-10 at O(log 10) per new notification — effectively constant time regardless of total notification volume.

---

