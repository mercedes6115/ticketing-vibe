import api from './client';
import type { QueueStatus, QueueToken } from '../types';

export const queueApi = {
  enter: (eventId: number) =>
    api.post<QueueStatus>('/api/queue/enter', { eventId }),

  getStatus: (eventId: number) =>
    api.get<QueueStatus>('/api/queue/status', { params: { eventId } }),

  exit: (eventId: number) =>
    api.post('/api/queue/exit', null, { params: { eventId } }),

  issueToken: (eventId: number) =>
    api.post<QueueToken>('/api/queue/token', null, { params: { eventId } }),

  getSize: (eventId: number) =>
    api.get<{ eventId: number; size: number }>('/api/queue/size', { params: { eventId } }),
};

// SSE 스트림 URL (EventSource는 커스텀 헤더 미지원 — JWT를 ?token= 쿼리 파라미터로 전달)
export const getQueueStreamUrl = (eventId: number, token: string) =>
  `${import.meta.env.VITE_API_URL || 'http://localhost:8080'}/api/queue/stream?eventId=${eventId}&token=${encodeURIComponent(token)}`;
