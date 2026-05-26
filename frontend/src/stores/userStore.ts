import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User, TokenResponse } from '../types';

interface UserState {
  user: User | null;
  accessToken: string | null;
  refreshToken: string | null;
  setUser: (user: User | null) => void;
  login: (response: TokenResponse) => void;
  logout: () => void;
}

export const useUserStore = create<UserState>()(
  persist(
    (set) => ({
      user: null,
      accessToken: null,
      refreshToken: null,
      setUser: (user) => set({ user }),
      login: (response) => set({
        user: {
          id: response.userId,
          email: response.email,
          nickname: response.nickname,
          role: response.role,
        },
        accessToken: response.accessToken,
        refreshToken: response.refreshToken,
      }),
      logout: () => set({ user: null, accessToken: null, refreshToken: null }),
    }),
    { name: 'user-storage' }
  )
);
