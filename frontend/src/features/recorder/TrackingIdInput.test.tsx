import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TrackingIdInput } from './TrackingIdInput';

describe('TrackingIdInput', () => {
  it('submits on Enter (barcode scanner), clears and keeps focus', async () => {
    const onSubmit = vi.fn();
    render(<TrackingIdInput onSubmit={onSubmit} disabled={false} />);
    const input = screen.getByLabelText(/tracking id/i);

    expect(input).toHaveFocus();
    await userEvent.type(input, '  TRK-001  {Enter}');

    expect(onSubmit).toHaveBeenCalledWith('TRK-001');
    expect(input).toHaveValue('');
    expect(input).toHaveFocus();
  });

  it('ignores empty submissions', async () => {
    const onSubmit = vi.fn();
    render(<TrackingIdInput onSubmit={onSubmit} disabled={false} />);
    await userEvent.type(screen.getByLabelText(/tracking id/i), '   {Enter}');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('is disabled without a camera', () => {
    render(<TrackingIdInput onSubmit={vi.fn()} disabled />);
    expect(screen.getByLabelText(/tracking id/i)).toBeDisabled();
  });
});
