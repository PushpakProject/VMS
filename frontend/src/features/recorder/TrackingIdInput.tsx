import { useEffect, useRef, useState, type FormEvent } from 'react';

/**
 * Tracking ID entry for keyboard or barcode scanner (scanners type the code and press Enter).
 * Stays focused so the operator never has to click before scanning.
 */
export function TrackingIdInput({
  onSubmit,
  disabled,
}: {
  onSubmit: (trackingId: string) => void;
  disabled: boolean;
}) {
  const [value, setValue] = useState('');
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!disabled) {
      input.current?.focus();
    }
  }, [disabled]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed) {
      onSubmit(trimmed);
    }
    setValue('');
    input.current?.focus();
  };

  return (
    <form className="tracking-form" onSubmit={submit}>
      <label htmlFor="tracking-id">Scan or type Tracking ID</label>
      <div className="tracking-row">
        <input
          id="tracking-id"
          ref={input}
          className="tracking-input mono"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="e.g. TRK-000123"
          autoComplete="off"
          spellCheck={false}
          maxLength={64}
          disabled={disabled}
        />
        <button className="btn btn-primary" type="submit" disabled={disabled || !value.trim()}>
          Go
        </button>
      </div>
      <p className="muted small">
        Recording starts automatically. Scanning the next ID saves this clip and starts the next.
      </p>
    </form>
  );
}
