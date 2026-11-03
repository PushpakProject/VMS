import { useCallback, useEffect, useState } from 'react';

const STORAGE_KEY = 'vms.cameraId';

export interface CameraDevices {
  devices: MediaDeviceInfo[];
  selectedId: string | null;
  select: (deviceId: string) => void;
  refresh: () => Promise<void>;
  supported: boolean;
}

/** Lists cameras, remembers the operator's choice and reacts to plug/unplug. */
export function useCameraDevices(): CameraDevices {
  const supported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.enumerateDevices;
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(() =>
    localStorage.getItem(STORAGE_KEY),
  );

  const refresh = useCallback(async () => {
    if (!supported) {
      return;
    }
    const all = await navigator.mediaDevices.enumerateDevices();
    const cameras = all.filter((d) => d.kind === 'videoinput');
    setDevices(cameras);
    setSelectedId((current) => {
      if (current && cameras.some((c) => c.deviceId === current)) {
        return current;
      }
      return cameras[0]?.deviceId || null;
    });
  }, [supported]);

  useEffect(() => {
    if (!supported) {
      return;
    }
    // Deferred so the state updates happen outside the effect body.
    const initial = setTimeout(() => void refresh(), 0);
    const onChange = () => void refresh();
    navigator.mediaDevices.addEventListener('devicechange', onChange);
    return () => {
      clearTimeout(initial);
      navigator.mediaDevices.removeEventListener('devicechange', onChange);
    };
  }, [refresh, supported]);

  const select = useCallback((deviceId: string) => {
    localStorage.setItem(STORAGE_KEY, deviceId);
    setSelectedId(deviceId);
  }, []);

  return { devices, selectedId, select, refresh, supported };
}
