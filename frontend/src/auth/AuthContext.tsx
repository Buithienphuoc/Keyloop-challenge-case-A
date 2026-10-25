import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { get, getToken, post, setToken, setUnauthorizedHandler } from '../api/client';
import type { Session, User } from '../api/types';

interface AuthState {
  user: User | null;
  loading: boolean;
  signIn: (email: string, password: string) => Promise<User>;
  register: (input: { email: string; password: string; fullName: string; phone?: string }) => Promise<User>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(Boolean(getToken()));
  const queryClient = useQueryClient();

  const clear = useCallback(() => {
    setToken(null);
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    setUnauthorizedHandler(clear);
    if (!getToken()) return;
    get<User>('/api/auth/me')
      .then(setUser)
      .catch(clear)
      .finally(() => setLoading(false));
  }, [clear]);

  const accept = (s: Session) => {
    setToken(s.token);
    setUser(s.user);
    return s.user;
  };

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      signIn: async (email, password) => accept(await post<Session>('/api/auth/login', { email, password })),
      register: async (input) => accept(await post<Session>('/api/auth/register', input)),
      signOut: async () => {
        // Revoke on the server first, so the token stops working even if it was copied.
        await post('/api/auth/logout').catch(() => undefined);
        clear();
      },
    }),
    [user, loading, clear],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
