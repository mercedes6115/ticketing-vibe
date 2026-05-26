import api from './client';
import type { Booking, Event, UserAdmin } from '../types';

export interface EventCreateRequest {
  title: string;
  description: string;
  venue: string;
  startAt: string;
  openAt: string;
  totalSeats: number;
}

export interface SeatBulkCreateRequest {
  section: string;
  rowCount: number;
  seatsPerRow: number;
  price: number;
}

export const adminApi = {
  // 이벤트 관리
  createEvent: (data: EventCreateRequest) =>
    api.post<Event>('/api/events', data),

  updateEvent: (id: number, data: Partial<EventCreateRequest>) =>
    api.put<Event>(`/api/events/${id}`, data),

  updateEventStatus: (id: number, status: string) =>
    api.patch<Event>(`/api/events/${id}/status`, null, { params: { status } }),

  deleteEvent: (id: number) =>
    api.delete(`/api/events/${id}`),

  // 좌석 일괄 생성
  createSeats: (eventId: number, data: SeatBulkCreateRequest) =>
    api.post(`/api/events/${eventId}/seats/bulk`, data),

  // 이벤트 이미지 업로드
  uploadEventImage: (eventId: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<Event>(`/api/events/${eventId}/image`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  // 사용자 관리
  getUsers: (page = 0, size = 20) =>
    api.get<{ content: UserAdmin[]; totalPages: number; totalElements: number }>('/api/admin/users', {
      params: { page, size, sort: 'createdAt,desc' },
    }),

  updateUserRole: (userId: number, role: 'USER' | 'ADMIN') =>
    api.patch<UserAdmin>(`/api/admin/users/${userId}/role`, null, { params: { role } }),

  // 예매 관리
  getAllBookings: (page = 0, size = 20, eventId?: number) =>
    api.get<{ content: Booking[]; totalPages: number; totalElements: number }>('/api/bookings/admin', {
      params: { page, size, sort: 'createdAt,desc', ...(eventId ? { eventId } : {}) },
    }),
};
