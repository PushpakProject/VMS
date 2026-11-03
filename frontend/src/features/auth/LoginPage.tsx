import { useCallback, useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { useServices } from '../../app/ServicesContext';
import { ApiError, NetworkError, messageOf } from '../../lib/errors';
import { useAuth } from './AuthContext';
import { GoogleSignInButton } from './GoogleSignInButton';

function describe(error: unknown): string {
  if (error instanceof NetworkError) {
    return 'You appear to be offline. Signing in needs a network connection.';
  }
  if (error instanceof ApiError && error.status === 401) {
    return 'Invalid username or password.';
  }
  return messageOf(error);
}

export function LoginPage() {
  const { status, login, loginWithGoogle } = useAuth();
  const { config } = useServices();
  const [loginName, setLoginName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onGoogle = useCallback(
    (idToken: string) => {
      setError(null);
      loginWithGoogle(idToken).catch((e: unknown) => setError(describe(e)));
    },
    [loginWithGoogle],
  );

  if (status === 'authenticated') {
    return <Navigate to="/" replace />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(loginName, password);
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login">
      <form className="card login-card" onSubmit={onSubmit} noValidate>
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
          disabled={busy || !loginName || !password}
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        {config.googleClientId && (
          <>
            <div className="divider">or</div>
            <GoogleSignInButton
              clientId={config.googleClientId}
              onCredential={onGoogle}
              onError={setError}
            />
          </>
        )}
      </form>
    </main>
  );
}
