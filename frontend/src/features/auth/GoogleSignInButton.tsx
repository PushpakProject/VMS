import { useEffect, useRef } from 'react';

interface GoogleIdApi {
  initialize(options: {
    client_id: string;
    callback: (response: { credential: string }) => void;
  }): void;
  renderButton(element: HTMLElement, options: Record<string, unknown>): void;
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } };
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

function loadScript(): Promise<void> {
  if (window.google?.accounts?.id) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
    const script = existing ?? document.createElement('script');
    script.addEventListener('load', () => resolve(), { once: true });
    script.addEventListener('error', () => reject(new Error('Google script failed')), {
      once: true,
    });
    if (!existing) {
      script.src = SCRIPT_SRC;
      script.async = true;
      document.head.appendChild(script);
    }
  });
}

/** Google Identity Services button. The ID token is verified on the server, never trusted here. */
export function GoogleSignInButton({
  clientId,
  onCredential,
  onError,
}: {
  clientId: string;
  onCredential: (idToken: string) => void;
  onError: (message: string) => void;
}) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    loadScript()
      .then(() => {
        const google = window.google?.accounts.id;
        if (cancelled || !google || !container.current) {
          return;
        }
        google.initialize({ client_id: clientId, callback: (r) => onCredential(r.credential) });
        google.renderButton(container.current, { theme: 'outline', size: 'large', width: 320 });
      })
      .catch(() => !cancelled && onError('Google sign-in is unavailable right now.'));
    return () => {
      cancelled = true;
    };
  }, [clientId, onCredential, onError]);

  return <div ref={container} className="google-button" />;
}
