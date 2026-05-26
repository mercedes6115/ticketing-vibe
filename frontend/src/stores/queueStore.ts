import { create } from 'zustand';
import type { QueueStatus, QueueToken } from '../types';

interface QueueState {
  status: QueueStatus | null;
  token: QueueToken | null;
  isInQueue: boolean;
  setStatus: (status: QueueStatus | null) => void;
  setToken: (token: QueueToken | null) => void;
  setIsInQueue: (isInQueue: boolean) => void;
  reset: () => void;
}

export const useQueueStore = create<QueueState>((set) => ({
  status: null,
  token: null,
  isInQueue: false,
  setStatus: (status) => set({ status }),
  setToken: (token) => set({ token }),
  setIsInQueue: (isInQueue) => set({ isInQueue }),
  reset: () => set({ status: null, token: null, isInQueue: false }),
}));
