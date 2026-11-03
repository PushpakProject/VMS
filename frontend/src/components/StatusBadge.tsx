const LABELS: Record<string, { label: string; tone: string }> = {
  RECORDING: { label: 'Recording', tone: 'danger' },
  PENDING: { label: 'Queued', tone: 'neutral' },
  WAITING_FOR_NETWORK: { label: 'Waiting for network', tone: 'warning' },
  UPLOADING: { label: 'Uploading', tone: 'info' },
  COMPLETING: { label: 'Finishing upload', tone: 'info' },
  UPLOADED_REMOTE: { label: 'Uploaded', tone: 'info' },
  UPLOADED: { label: 'Uploaded', tone: 'info' },
  PROCESSING: { label: 'Adding watermark', tone: 'info' },
  READY: { label: 'Ready', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  ABANDONED: { label: 'Abandoned', tone: 'neutral' },
  DONE: { label: 'Done', tone: 'success' },
};

export function StatusBadge({ status }: { status: string }) {
  const entry = LABELS[status] ?? { label: status, tone: 'neutral' };
  return <span className={`badge badge-${entry.tone}`}>{entry.label}</span>;
}
