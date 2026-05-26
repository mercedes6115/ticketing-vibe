import api from './client';
import type { Seat } from '../types';

export const seatApi = {
  getByEventId: (eventId: number) =>
    api.get<Seat[]>(`/api/events/${eventId}/seats`),

  hold: (seatId: number) =>
    api.post<Seat>(`/api/seats/${seatId}/hold`),

  release: (seatId: number) =>
    api.delete<Seat>(`/api/seats/${seatId}/hold`),

  createBulk: (eventId: number, data: {
    section: string;
    rowCount: number;
    seatsPerRow: number;
    price: number;
  }) => api.post<Seat[]>(`/api/events/${eventId}/seats/bulk`, data),
};
