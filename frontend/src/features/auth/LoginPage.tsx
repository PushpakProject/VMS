import { useCallback, useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { useServices } from '../../app/ServicesContext';
import { ApiError, NetworkError, messageOf } from '../../lib/errors';
import { useAuth } from './AuthContext';
import { GoogleSignInButton } from './GoogleSignInButton';

const OFFLINE_MESSAGE = 'You appear to be offline. Signing in needs a network connection.';

function describePassword(error: unknown): string {
  if (error instanceof NetworkError) {
    return OFFLINE_MESSAGE;
  }
  if (error instanceof ApiError && error.status === 401) {
    return 'Invalid username or password.';
  }
  return messageOf(error);
}

function describeGoogle(error: unknown): string {
  if (error instanceof NetworkError) {
    return OFFLINE_MESSAGE;
  }
  if (error instanceof ApiError && error.status === 401) {
    return 'Google sign-in failed. Please try again or use your password.';
  }
  if (error instanceof ApiError && error.status === 404) {
    return "Google sign-in isn't enabled on this server.";
  }
  return messageOf(error);
}

export function LoginPage() {
  const { status, login, loginWithGoogle } = useAuth();
  const { config } = useServices();
  const [loginName, setLoginName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'password' | 'google' | null>(null);

  const onGoogle = useCallback(
    (idToken: string) => {
      setError(null);
      setBusy('google');
      loginWithGoogle(idToken)
        .catch((e: unknown) => setError(describeGoogle(e)))
        .finally(() => setBusy(null));
    },
    [loginWithGoogle],
  );

  if (status === 'authenticated') {
    return <Navigate to="/" replace />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy('password');
    setError(null);
    try {
      await login(loginName, password);
    } catch (e) {
      setError(describePassword(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <main className="login">
      <div className="card login-card">
        <h1>Parcel Video Recorder</h1>
        <p className="muted">Sign in to record and upload parcel videos.</p>
        {status === 'offline' && (
          <div className="alert alert-warning" role="status">
            You are offline. Recordings already on this device are safe and will upload after you
            sign in.
          </div>
        )}
        {error && (
          <div className="alert alert-error" role="alert">
            {error}
          </div>
        )}

        {config.googleClientId && (
          <section className="login-google" aria-label="Sign in with Google">
            <GoogleSignInButton
              clientId={config.googleClientId}
              onCredential={onGoogle}
              onError={setError}
            />
            {busy === 'google' && (
              <p className="muted small" role="status">
                Signing in with Google…
              </p>
            )}
            <div className="divider" role="separator">
              or
            </div>
          </section>
        )}

        <form className="login-form" onSubmit={onSubmit} noValidate>
          <label>
            Email or username
            <input
              autoComplete="username"
              value={loginName}
              onChange={(e) => setLoginName(e.target.value)}
              required
              maxLength={254}
            />
          </label>
          <label>
            Password
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              maxLength={128}
            />
          </label>
          <button
            className="btn btn-primary"
            type="submit"
            disabled={busy !== null || !loginName || !password}
          >
            {busy === 'password' ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </main>
  );
}
