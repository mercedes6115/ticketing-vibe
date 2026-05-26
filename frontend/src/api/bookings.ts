import api from './client';
import type { Booking, BookingAccepted, BookingStatus } from '../types';

export const bookingApi = {
  create: (data: { seatId: number; paymentMethod: string }) =>
    api.post<BookingAccepted>('/api/bookings', data),

  getStatus: (bookingNo: string) =>
    api.get<BookingStatus>(`/api/bookings/status/${bookingNo}`),

  getById: (id: number) =>
    api.get<Booking>(`/api/bookings/${id}`),

  getByNo: (bookingNo: string) =>
    api.get<Booking>(`/api/bookings/no/${bookingNo}`),

  getMyBookings: (page = 0, size = 10) =>
    api.get<{ content: Booking[]; totalPages: number }>('/api/bookings/my', {
      params: { page, size },
    }),

  cancel: (bookingId: number) =>
    api.post<Booking>(`/api/bookings/${bookingId}/cancel`),
};
