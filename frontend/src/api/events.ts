import api from './client';
import type { Event } from '../types';

export const eventApi = {
  getAll: (page = 0, size = 10) =>
    api.get<{ content: Event[]; totalPages: number }>('/api/events', {
      params: { page, size },
    }),

  getById: (id: number) =>
    api.get<Event>(`/api/events/${id}`),

  create: (data: Partial<Event>) =>
    api.post<Event>('/api/events', data),

  update: (id: number, data: Partial<Event>) =>
    api.put<Event>(`/api/events/${id}`, data),

  updateStatus: (id: number, status: Event['status']) =>
    api.patch<Event>(`/api/events/${id}/status`, null, { params: { status } }),

  delete: (id: number) =>
    api.delete(`/api/events/${id}`),
};
