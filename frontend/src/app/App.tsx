import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '../features/auth/AuthContext';
import { LoginPage } from '../features/auth/LoginPage';
import { useConnectivity } from '../features/connectivity/useConnectivity';
import { RecorderPage } from '../features/recorder/RecorderPage';
import { isActive } from '../features/uploads/uploadJob';
import { useUploadQueue } from '../features/uploads/useUploadQueue';
import { VideosPage } from '../features/videos/VideosPage';
import { ServicesProvider } from './ServicesContext';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('UI error', error, info.componentStack);
  }

  render() {
    if (this.state.failed) {
      return (
        <div className="centered alert alert-error">
          Something went wrong. Your recordings are safe on this device.{' '}
          <button className="btn btn-small" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

function Header() {
  const { user, logout } = useAuth();
  const connectivity = useConnectivity();
  const { jobs } = useUploadQueue();
  const pending = jobs.filter((j) => j.recording || isActive(j)).length;

  const onLogout = () => {
    if (
      pending > 0 &&
      !window.confirm(
        `${pending} recording(s) are not uploaded yet. They stay on this device and continue after you sign in again. Sign out?`,
      )
    ) {
      return;
    }
    void logout();
  };

  return (
    <header className="header">
      <span className="brand">● VMS</span>
      <nav>
        <NavLink to="/" end>
          Recorder
        </NavLink>
        <NavLink to="/videos">Videos</NavLink>
      </nav>
      <div className="header-right">
        <span className={`net net-${connectivity.toLowerCase()}`}>
          {connectivity === 'ONLINE' ? 'Online' : 'Offline — recording continues'}
        </span>
        {pending > 0 && <span className="badge badge-info">{pending} pending</span>}
        <span className="muted small">{user?.displayName}</span>
        <button className="btn btn-small" onClick={onLogout}>
          Sign out
        </button>
      </div>
    </header>
  );
}

function Protected({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') {
    return <div className="centered muted">Loading…</div>;
  }
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace />;
  }
  return (
    <>
      <Header />
      {children}
    </>
  );
}

export function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ServicesProvider>
          <AuthProvider>
            <BrowserRouter>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route
                  path="/"
                  element={
                    <Protected>
                      <RecorderPage />
                    </Protected>
                  }
                />
                <Route
                  path="/videos"
                  element={
                    <Protected>
                      <VideosPage />
                    </Protected>
                  }
                />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </BrowserRouter>
          </AuthProvider>
        </ServicesProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
