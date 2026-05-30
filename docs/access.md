# 접속 정보

> 로컬 실행 후 어떤 주소로 접근하는지, 기본 계정은 무엇인지, 개발 중 바로 확인해야 하는 정보를 모아둔 문서입니다.

| 빠른 정보 | 내용 |
|-----------|------|
| 프론트엔드 | `http://localhost:5173` |
| 백엔드 API | `http://localhost:8080` |
| Swagger | `http://localhost:8080/swagger-ui.html` |
| Grafana | `http://localhost:3000` / `admin / admin` |

## 문서 구성

- [애플리케이션](#애플리케이션)
- [모니터링](#모니터링)
- [인프라 직접 접근](#인프라-로컬-직접-접근)
- [서비스 계정](#서비스-계정-사용자)
- [프로덕션 배포 시 필수 환경변수](#프로덕션-배포-시-필수-환경변수)

## 애플리케이션

| 서비스 | URL | 비고 |
|--------|-----|------|
| Frontend | http://localhost:5173 | React |
| Backend API | http://localhost:8080 | Spring Boot |
| Swagger UI | http://localhost:8080/swagger-ui.html | API 문서 |
| WebSocket | ws://localhost:8080/ws | 좌석 실시간 상태 |

## 모니터링

| 서비스 | URL | 계정 |
|--------|-----|------|
| Prometheus | http://localhost:9090 | 없음 |
| Grafana | http://localhost:3000 | admin / admin |

> Grafana 대시보드 임포트 ID: `4701` (JVM), `12900` (Spring Boot), `7589` (Kafka), `11835` (Redis)

## 인프라 (로컬 직접 접근)

| 서비스 | Host | 계정 |
|--------|------|------|
| MySQL | localhost:3307 | ticketing / ticketing1234 |
| MySQL root | localhost:3307 | root / root1234 |
| Redis | localhost:6379 | 없음 |
| Redis Sentinel | localhost:26379 | 없음 |
| Kafka | localhost:9092 | 없음 |
| DB명 | ticketing | |

---

## 서비스 계정 (사용자)

### 사전 생성된 계정 없음

시드 데이터가 없으므로 직접 회원가입해야 합니다.

**회원가입**
```
POST /api/auth/signup
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "홍길동"
}
```

**로그인 → accessToken 발급**
```
POST /api/auth/login
{
  "email": "user@example.com",
  "password": "password123"
}
```
응답의 `accessToken`을 이후 요청에 `Authorization: Bearer <token>` 헤더로 사용합니다.

### ADMIN 계정 만들기

회원가입은 항상 `USER` 권한으로 생성됩니다. ADMIN 권한이 필요하면:

1. 회원가입 후 MySQL에서 직접 권한 변경
```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'admin@example.com';
```

2. 이후 `PATCH /api/admin/users/{id}/role` API로 다른 계정 권한 변경 가능 (ADMIN 계정 필요)

---

## 프로덕션 배포 시 필수 환경변수

```bash
JWT_SECRET=<256bit 이상 랜덤 문자열>
ALLOWED_ORIGINS=https://your-domain.com
GRAFANA_PASSWORD=<안전한 비밀번호>
```
