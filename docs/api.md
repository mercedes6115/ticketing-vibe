# API 문서

> 주요 사용자 API와 관리자 API의 요청 방식, 인증 여부, 응답 예시를 정리한 문서입니다.

| 빠른 정보 | 내용 |
|-----------|------|
| Base URL | `http://localhost:8080` |
| 인증 방식 | `Authorization: Bearer {accessToken}` |
| 참고 문서 | [접속 정보](access.md), [서비스 플로우](service-flow.md), [시퀀스 다이어그램](sequence-diagram.md) |

## API 맵

| 영역 | 설명 |
|------|------|
| 인증 | 회원가입, 로그인, 토큰 재발급, 로그아웃 |
| 이벤트 | 목록 조회, 상세 조회, 상태별 조회, 관리자 CRUD |
| 좌석 | 좌석 조회, 홀드/해제, 관리자 일괄 생성 |
| 예매 | 생성, 상태 조회, 상세 조회, 취소 |
| 대기열 | 진입, 상태 확인, SSE 스트림, 토큰 발급 |
| 관리자 | 이벤트, 예매, 사용자 관리 |

## 인증 (Auth)

### POST /api/auth/signup — 회원가입

**인증**: 불필요

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | Y | 이메일 형식 |
| password | String | Y | 8자 이상 |
| nickname | String | Y | 2~50자 |

**Response** `201 Created`
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "userId": 1,
  "email": "user@example.com",
  "nickname": "홍길동",
  "role": "USER"
}
```

---

### POST /api/auth/login — 로그인

**인증**: 불필요

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | Y | |
| password | String | Y | |

**Response** `200 OK` — signup과 동일

---

### POST /api/auth/reissue — 토큰 재발급

**인증**: 불필요

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| refreshToken | String | Y | |

**Response** `200 OK` — 새 accessToken + refreshToken

---

### POST /api/auth/logout — 로그아웃

**인증**: 필요

**Response** `200 OK` (body 없음)

---

## 이벤트 (Event)

### GET /api/events — 이벤트 목록 조회

**인증**: 불필요

**Query Parameters**
| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| page | 0 | 페이지 번호 |
| size | 10 | 페이지 크기 |
| sort | openAt,desc | 정렬 |

**Response** `200 OK` — Page\<EventResponse\>

> 현재 구현은 전체 이벤트가 아니라 `SCHEDULED`, `OPEN` 상태 이벤트만 반환한다.

---

### GET /api/events/{id} — 이벤트 상세 조회

**인증**: 불필요

**Response** `200 OK`
```json
{
  "id": 1,
  "title": "콘서트 제목",
  "description": "설명",
  "venue": "올림픽공원",
  "imageUrl": "https://...",
  "startAt": "2025-06-01T19:00:00",
  "openAt": "2025-05-01T10:00:00",
  "totalSeats": 500,
  "availableSeats": 342,
  "status": "OPEN"
}
```

---

### GET /api/events/status/{status} — 상태별 이벤트 조회

**인증**: 필요

**Path**: `status` = `SCHEDULED` | `OPEN` | `CLOSED` | `CANCELLED`

**Response** `200 OK` — Page\<EventResponse\>

---

### POST /api/events — 이벤트 생성

**인증**: 필요 (ADMIN)

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| title | String | Y | 제목 |
| description | String | N | 설명 |
| venue | String | Y | 장소 |
| imageUrl | String | N | 이미지 URL |
| startAt | LocalDateTime | Y | 공연 시작 시간 (미래) |
| openAt | LocalDateTime | Y | 예매 오픈 시간 |
| totalSeats | Integer | Y | 총 좌석 수 (1 이상) |

**Response** `201 Created` — EventResponse

---

### PUT /api/events/{id} — 이벤트 수정

**인증**: 필요 (ADMIN)

**Response** `200 OK` — EventResponse

---

### PATCH /api/events/{id}/status — 이벤트 상태 변경

**인증**: 필요 (ADMIN)

**Query Parameters**: `status` = `SCHEDULED` | `OPEN` | `CLOSED` | `CANCELLED`

**Response** `200 OK` — EventResponse

---

### POST /api/events/{id}/image — 이미지 업로드

**인증**: 필요 (ADMIN)

**Content-Type**: `multipart/form-data`

**Form Data**: `file` (이미지 파일)

**Response** `200 OK` — EventResponse

---

### DELETE /api/events/{id} — 이벤트 삭제

**인증**: 필요 (ADMIN)

**Response** `204 No Content`

---

## 좌석 (Seat)

### GET /api/events/{eventId}/seats — 이벤트별 좌석 목록

**인증**: 필요

**Response** `200 OK`
```json
[
  {
    "id": 101,
    "eventId": 1,
    "section": "A",
    "seatRow": "1",
    "seatNumber": 5,
    "price": 99000,
    "status": "AVAILABLE"
  }
]
```

---

### GET /api/seats/{seatId} — 좌석 상세 조회

**인증**: 필요

**Response** `200 OK` — SeatResponse

---

### POST /api/events/{eventId}/seats/bulk — 좌석 일괄 생성

**인증**: 필요 (ADMIN)

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| section | String | Y | 구역명 (예: A, B, VIP) |
| rowCount | Integer | Y | 열 수 |
| seatsPerRow | Integer | Y | 열당 좌석 수 |
| price | Integer | Y | 가격 (원) |

**Response** `201 Created` — List\<SeatResponse\>

---

### POST /api/seats/{seatId}/hold — 좌석 임시 선점

**인증**: 필요 (USER), 입장 토큰 필요

**Response** `200 OK` — SeatResponse (status: HOLD)

> 입장 토큰(`queue:token:{userId}:{eventId}`)이 없으면 `403 Forbidden`.
> Redisson 분산락으로 동시 요청 직렬화. TTL 5분 후 자동 해제.

---

### DELETE /api/seats/{seatId}/hold — 좌석 선점 해제

**인증**: 필요 (본인만 가능)

**Response** `200 OK` — SeatResponse (status: AVAILABLE)

---

## 대기열 (Queue)

### POST /api/queue/enter — 대기열 진입

**인증**: 필요

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| eventId | Long | Y | |

**Response** `200 OK`
```json
{
  "eventId": 1,
  "userId": 42,
  "position": 87,
  "totalWaiting": 250,
  "canEnter": false
}
```

---

### GET /api/queue/status — 대기열 상태 조회

**인증**: 필요

**Query Parameters**: `eventId`

**Response** `200 OK` — QueueStatusResponse

---

### GET /api/queue/stream — SSE 대기열 실시간 스트림

**인증**: 필요 (`EventSource` 특성상 `?token={accessToken}` 쿼리 파라미터 사용)

**Query Parameters**: `eventId`, `token`

**Response** `text/event-stream`
- `queue-status` 이벤트: 2초마다 현재 순번 전송
- `token-issued` 이벤트: 입장 토큰 발급 완료 시 1회 전송

> 타임아웃: 30분. 토큰 발급 후 서버에서 SSE 연결 종료.

---

### POST /api/queue/exit — 대기열 이탈

**인증**: 필요

**Query Parameters**: `eventId`

**Response** `200 OK` (body 없음)

---

### POST /api/queue/token — 입장 토큰 수동 발급

**인증**: 필요

**Query Parameters**: `eventId`

**Response** `200 OK`
```json
{
  "token": "uuid-string",
  "eventId": 1,
  "userId": 42,
  "expiresInSeconds": 600
}
```

> 순번이 100 이내인 경우에만 발급. 이미 발급된 토큰이 있으면 재사용.

---

### GET /api/queue/size — 대기열 인원 조회

**인증**: 필요

**Query Parameters**: `eventId`

**Response** `200 OK`
```json
{ "eventId": 1, "size": 342 }
```

---

## 예매 (Booking)

### POST /api/bookings — 예매 요청 (비동기)

**인증**: 필요 (USER)

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| seatId | Long | Y | 선점한 좌석 ID |
| paymentMethod | String | Y | `CREDIT_CARD` / `KAKAO_PAY` / `NAVER_PAY` |

**Response** `202 Accepted`
```json
{
  "bookingNo": "BK202506011A2B3C4D5E",
  "status": "PROCESSING"
}
```

> Redis의 seat:hold 검증 후 Kafka로 비동기 처리. 완료 여부는 `/status/{bookingNo}` 폴링으로 확인.

---

### GET /api/bookings/status/{bookingNo} — 예매 처리 상태 폴링

**인증**: 필요

**Response** `200 OK`
```json
{
  "bookingNo": "BK202506011A2B3C4D5E",
  "status": "CONFIRMED"
}
```

| status | 의미 |
|--------|------|
| PROCESSING | Consumer 처리 중 |
| CONFIRMED | 예매 완료 |
| FAILED | 처리 실패가 최종 확정된 상태 |
| UNKNOWN | Redis 키 만료 + DB에도 없음 |

---

### GET /api/bookings/no/{bookingNo} — 예매 번호로 조회

**인증**: 필요

**Response** `200 OK` — BookingResponse (예매 상세)

---

### GET /api/bookings/{bookingId} — 예매 ID로 조회

**인증**: 필요

**Response** `200 OK` — BookingResponse

---

### GET /api/bookings/my — 내 예매 목록

**인증**: 필요 (JWT에서 userId 추출)

**Query Parameters**: `page`, `size`, `sort`

**Response** `200 OK` — Page\<BookingListResponse\>

---

### GET /api/bookings/admin — 전체 예매 목록 (관리자)

**인증**: 필요 (ADMIN)

**Query Parameters**
| 파라미터 | 설명 |
|----------|------|
| eventId | (선택) 특정 이벤트 필터 |
| page, size, sort | 페이지네이션 |

**Response** `200 OK` — Page\<BookingListResponse\>

---

### POST /api/bookings/{bookingId}/cancel — 예매 취소

**인증**: 필요 (본인만 가능, JWT에서 userId 추출)

**Response** `200 OK` — BookingResponse (status: CANCELLED)

---

## 결제 (Payment)

### GET /api/payments/{paymentId} — 결제 상세 조회

**인증**: 필요

**Response** `200 OK`
```json
{
  "id": 1,
  "bookingId": 10,
  "amount": 99000,
  "method": "CREDIT_CARD",
  "status": "SUCCESS",
  "idempotencyKey": "uuid-string"
}
```

> 현재 구현은 인증만 필요하고, `paymentId` 조회에 대한 소유권 검증은 하지 않는다.

---

### GET /api/payments/bookings/{bookingId} — 예매별 결제 조회

**인증**: 필요

**Response** `200 OK` — PaymentResponse

> 현재 구현은 인증만 필요하고, `bookingId` 기준 결제 조회에 대한 소유권 검증은 하지 않는다.

---

### POST /api/payments/{paymentId}/refund — 환불 처리

**인증**: 필요 (본인만 가능)

**Response** `200 OK` — PaymentResponse (status: REFUNDED)

---

## 관리자 (Admin)

### GET /api/admin/users — 전체 사용자 목록

**인증**: 필요 (ADMIN)

**Query Parameters**: `page`, `size`, `sort`

**Response** `200 OK` — Page\<UserAdminResponse\>

---

### PATCH /api/admin/users/{id}/role — 사용자 역할 변경

**인증**: 필요 (ADMIN)

**Query Parameters**: `role` = `USER` | `ADMIN`

**Response** `200 OK` — UserAdminResponse

---

## WebSocket / STOMP

**연결**: `ws://localhost:8080/ws` (SockJS)

**프로토콜**: STOMP

### 구독 토픽

| 토픽 | 설명 | 발생 시점 |
|------|------|-----------|
| `/topic/events/{eventId}/seats` | 좌석 상태 변경 브로드캐스트 | HOLD / AVAILABLE / SOLD |

**메시지 형식**
```json
{
  "seatId": 101,
  "eventId": 1,
  "status": "HOLD",
  "userId": 42
}
```

---

## 공통 에러 응답

| HTTP Status | 의미 |
|-------------|------|
| 400 Bad Request | 요청 파라미터 오류 / 비즈니스 규칙 위반 |
| 401 Unauthorized | JWT 없음 또는 만료 |
| 403 Forbidden | 권한 부족 / 본인 소유 리소스 아님 / 유효한 대기열 토큰 없음 |
| 404 Not Found | 리소스 없음 |
| 409 Conflict | 이미 선점된 좌석 / 이미 취소된 예매 |
| 500 Internal Server Error | 서버 내부 오류 |
