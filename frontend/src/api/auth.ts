import api from './client';
import type { LoginRequest, SignupRequest, TokenResponse } from '../types';

export const authApi = {
  login: (data: LoginRequest) =>
    api.post<TokenResponse>('/api/auth/login', data),

  signup: (data: SignupRequest) =>
    api.post<TokenResponse>('/api/auth/signup', data),

  reissue: (refreshToken: string) =>
    api.post<TokenResponse>('/api/auth/reissue', { refreshToken }),

  logout: () =>
    api.post('/api/auth/logout'),
};
