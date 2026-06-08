# 2026-06 Payment And Booking Improvements

## Summary

- Added ownership checks to payment lookup APIs.
- Removed `idempotencyKey` from `PaymentResponse`.
- Split cancellation and refund orchestration into dedicated application services.

## What Changed

### 1. Payment lookup now validates ownership

Affected code:

- `PaymentController`
- `PaymentService`
- `PaymentResponse`

Changes:

- `GET /api/payments/{paymentId}` now requires `@AuthenticationPrincipal Long userId`.
- `GET /api/payments/bookings/{bookingId}` now requires `@AuthenticationPrincipal Long userId`.
- `PaymentService` validates `payment.booking.user.id == userId` before returning the response.
- `PaymentResponse` no longer exposes `idempotencyKey`.

Reasoning:

- The previous implementation allowed any authenticated user to query another user's payment if they knew or guessed the identifier.
- `idempotencyKey` is an internal payment identifier. It is not needed by clients and should not be exposed.
- Ownership validation belongs in the service layer so the rule is enforced consistently regardless of controller or future call sites.

### 2. Cancellation and refund orchestration were separated

Affected code:

- `BookingController`
- `BookingService`
- `PaymentService`
- `BookingCancellationService`
- `RefundApplicationService`

Changes:

- Booking cancellation now flows through `BookingCancellationService`.
- Payment refund now flows through `RefundApplicationService`.
- `PaymentService` no longer depends on `BookingService`.
- The old `PaymentService -> BookingService` cycle hidden by `@Lazy` was removed.

Reasoning:

- Refund is not only a payment concern. It coordinates payment-state validation, booking cancellation, seat release, WebSocket broadcast, and Kafka event publication.
- The previous `@Lazy` dependency hid a service-boundary problem instead of fixing it.
- Centralizing cancellation side effects in one service keeps the use case coherent and reduces the chance of behavior drifting between booking cancel and refund flows.

## Design Basis

The refactor follows the review criteria derived from the Java-focused Inflearn courses:

- OOP/UML: object relationships and responsibility boundaries should stay explicit and understandable.
- Spring/DI: dependency injection should reduce coupling, not hide circular dependencies.
- Concurrency: shared state transitions should be orchestrated in one place when multiple side effects must stay aligned.

## Follow-Up

- Strengthen booking confirmation flow around Redis hold, Kafka publish, and DB commit with a compensation path or an outbox pattern.
- Decide whether `Event.availableSeats` is a true persisted invariant or a derived value from `Seat.status`.
