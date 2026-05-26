// Event
export interface Event {
  id: number;
  title: string;
  description: string;
  venue: string;
  imageUrl: string | null;
  startAt: string;
  openAt: string;
  status: 'SCHEDULED' | 'OPEN' | 'CLOSED' | 'CANCELLED';
  totalSeats: number;
  availableSeats: number;
  createdAt: string;
}

// Seat
export interface Seat {
  id: number;
  eventId: number;
  section: string;
  seatRow: string;
  seatNumber: number;
  price: number;
  status: 'AVAILABLE' | 'HOLD' | 'SOLD';
  holdExpiresInSeconds?: number; // 선점 응답 전용, 일반 조회 시 undefined
}

export interface SeatStatusMessage {
  seatId: number;
  eventId: number;
  status: 'AVAILABLE' | 'HOLD' | 'SOLD';
  holdUserId: number | null;
  message: string;
}

// Queue
export interface QueueStatus {
  eventId: number;
  userId: number;
  position: number;
  totalWaiting: number;
  estimatedWaitSeconds: number;
  canEnter: boolean;
}

export interface QueueToken {
  token: string;
  eventId: number;
  userId: number;
  expiresAt: string;
  ttlSeconds: number;
}

// Booking async flow
export interface BookingAccepted {
  bookingNo: string;
  status: 'PROCESSING';
}

export interface BookingStatus {
  bookingNo: string;
  status: 'PROCESSING' | 'CONFIRMED' | 'FAILED' | 'UNKNOWN';
}

// Booking
export interface Booking {
  id: number;
  bookingNo: string;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED';
  createdAt: string;
  eventId: number;
  eventTitle: string;
  eventStartAt: string;
  seatId: number;
  section: string;
  seatRow: string;
  seatNumber: number;
  price: number;
  paymentId: number | null;
  paymentMethod: 'CARD' | 'BANK_TRANSFER' | 'VIRTUAL_ACCOUNT' | null;
  paymentStatus: 'PENDING' | 'SUCCESS' | 'FAILED' | 'REFUNDED' | null;
  amount: number | null;
  // Admin-only fields
  userId?: number;
  userNickname?: string;
}

// User
export interface User {
  id: number;
  email: string;
  nickname: string;
  role: 'USER' | 'ADMIN';
}

// Admin user management
export interface UserAdmin {
  id: number;
  email: string;
  nickname: string;
  role: 'USER' | 'ADMIN';
  createdAt: string;
}

// Auth
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  userId: number;
  email: string;
  nickname: string;
  role: 'USER' | 'ADMIN';
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface SignupRequest {
  email: string;
  password: string;
  nickname: string;
}

// Payment
export interface Payment {
  id: number;
  bookingId: number;
  bookingNo: string;
  amount: number;
  method: 'CARD' | 'BANK_TRANSFER' | 'VIRTUAL_ACCOUNT';
  status: 'PENDING' | 'SUCCESS' | 'FAILED' | 'REFUNDED';
  idempotencyKey: string;
  paidAt: string | null;
  createdAt: string;
}
