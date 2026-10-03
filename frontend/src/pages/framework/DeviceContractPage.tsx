import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { deviceContractApi } from '../../api/deviceContractApi';
import type { DeviceContract } from '../../api/frameworkTypes';
import { ContractViewer } from './ContractViewer';
import { FrameworkLayout } from './FrameworkLayout';

export default function DeviceContractPage() {
  const { deviceId } = useParams();
  const [contract, setContract] = useState<DeviceContract | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { if (deviceId) void deviceContractApi.get(deviceId).then(setContract)
    .catch(e => setError(e instanceof Error ? e.message : String(e))); }, [deviceId]);
  return <FrameworkLayout title="Device Contract">
    {error && <p role="alert" className="text-danger">{error}</p>}
    {contract ? <ContractViewer contract={contract} /> : !error && <p>Loading contract from backend…</p>}
  </FrameworkLayout>;
}
