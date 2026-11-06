import { useEffect, useRef } from 'react';

interface GoogleIdApi {
  initialize(options: {
    client_id: string;
    callback: (response: { credential: string }) => void;
    cancel_on_tap_outside?: boolean;
  }): void;
  renderButton(element: HTMLElement, options: Record<string, unknown>): void;
  cancel(): void;
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } };
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';
const MAX_BUTTON_WIDTH = 400;

function loadScript(): Promise<void> {
  if (window.google?.accounts?.id) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
    if (existing?.dataset.loaded === 'true') {
      // Script tag is there and finished loading, but `google` isn't ready: treat as failure.
      reject(new Error('Google script unavailable'));
      return;
    }
    const script = existing ?? document.createElement('script');
    script.addEventListener(
      'load',
      () => {
        script.dataset.loaded = 'true';
        resolve();
      },
      { once: true },
    );
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

/**
 * Official "Sign in with Google" button (Google Identity Services). The returned ID token is only
 * a claim: the API verifies its signature, issuer, audience and expiry before trusting it.
 */
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
  // Refs keep the latest callbacks without re-initialising Google (which would flicker the button).
  const credentialRef = useRef(onCredential);
  const errorRef = useRef(onError);
  useEffect(() => {
    credentialRef.current = onCredential;
    errorRef.current = onError;
  }, [onCredential, onError]);

  useEffect(() => {
    let cancelled = false;
    loadScript()
      .then(() => {
        const google = window.google?.accounts.id;
        const element = container.current;
        if (cancelled || !google || !element) {
          return;
        }
        google.initialize({
          client_id: clientId,
          callback: (response) => credentialRef.current(response.credential),
          cancel_on_tap_outside: true,
        });
        google.renderButton(element, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'signin_with',
          shape: 'rectangular',
          logo_alignment: 'left',
          width: Math.min(MAX_BUTTON_WIDTH, element.clientWidth || MAX_BUTTON_WIDTH),
        });
      })
      .catch(() => {
        if (!cancelled) {
          errorRef.current('Google sign-in is unavailable right now. Use your password instead.');
        }
      });
    return () => {
      cancelled = true;
      window.google?.accounts?.id?.cancel();
    };
  }, [clientId]);

  return <div ref={container} className="google-button" data-testid="google-signin" />;
}
