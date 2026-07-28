/* eslint-disable react-hooks/set-state-in-effect */
import React, { useEffect, useState, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { Space } from '../types/models';
import { spaceApi } from '../api/spaceApi';
import type { Device, CreateDeviceRequest, UpdateDeviceRequest } from '../api/deviceApi';
import { deviceApi, DeviceType, DeviceStatus } from '../api/deviceApi';
import { ApiError } from '../api/apiClient';
import { ErrorAlert } from '../components/ErrorAlert';
import { Modal } from '../components/Modal';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { ConfirmDialog } from '../components/ConfirmDialog';

export const SpaceDetailsPage: React.FC = () => {
  const { spaceId } = useParams<{ spaceId: string }>();
  const [space, setSpace] = useState<Space | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [error, setError] = useState<string>('');
  
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [deviceToDelete, setDeviceToDelete] = useState<Device | null>(null);

  const [editingDevice, setEditingDevice] = useState<Device | null>(null);

  // Form states
  const [name, setName] = useState('');
  const [deviceType, setDeviceType] = useState<DeviceType>(DeviceType.TEMPERATURE_SENSOR);
  const [manufacturer, setManufacturer] = useState('');
  const [model, setModel] = useState('');
  const [serialNumber, setSerialNumber] = useState('');
  const [status, setStatus] = useState<DeviceStatus>(DeviceStatus.REGISTERED);
  const [description, setDescription] = useState('');

  const loadData = useCallback(async () => {
    if (!spaceId) return;
    try {
      const [spaceData, devicesData] = await Promise.all([
        spaceApi.getSpace(spaceId),
        deviceApi.getDevicesBySpaceId(spaceId)
      ]);
      setSpace(spaceData);
      setDevices(devicesData);
      setError('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to load data');
    }
  }, [spaceId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const openCreateModal = () => {
    setName('');
    setDeviceType(DeviceType.TEMPERATURE_SENSOR);
    setManufacturer('');
    setModel('');
    setSerialNumber('');
    setDescription('');
    setIsCreateModalOpen(true);
  };

  const openEditModal = (device: Device) => {
    setEditingDevice(device);
    setName(device.name);
    setStatus(device.status);
    setDescription(device.description || '');
    setIsEditModalOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!spaceId) return;
    try {
      const request: CreateDeviceRequest = { 
        name, 
        deviceType, 
        manufacturer, 
        model, 
        serialNumber, 
        description: description || undefined 
      };
      await deviceApi.createDevice(spaceId, request);
      setIsCreateModalOpen(false);
      loadData();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError('A device with this serial number already exists.');
      } else {
        setError(e instanceof ApiError ? e.message : 'Failed to create device');
      }
    }
  };

  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingDevice) return;
    try {
      const request: UpdateDeviceRequest = { 
        name, 
        status, 
        description: description || undefined 
      };
      await deviceApi.updateDevice(editingDevice.id, request);
      setIsEditModalOpen(false);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to update device');
    }
  };

  const handleDelete = async () => {
    if (!deviceToDelete) return;
    try {
      await deviceApi.deleteDevice(deviceToDelete.id);
      setDeviceToDelete(null);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to delete device');
      setDeviceToDelete(null);
    }
  };

  if (!space) return <div>Loading...</div>;

  return (
    <div>
      <div style={{ marginBottom: '20px' }}>
        <Link to={`/buildings/${space.buildingId}`}>← Back to Space's Building</Link>
      </div>
      <h1>Space: {space.name}</h1>
      <p>{space.description}</p>
      
      <h2 style={{ marginTop: '20px' }}>Devices</h2>
      <ErrorAlert message={error} onClose={() => setError('')} />
      <button onClick={openCreateModal} style={{ marginBottom: '20px', padding: '10px 20px', cursor: 'pointer' }}>
        Add Device
      </button>

      {devices.length === 0 ? <p>No devices found in this space.</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Name</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Type</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Manufacturer</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Model</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Serial Number</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Status</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {devices.map(d => (
              <tr key={d.id}>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{d.name}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{d.deviceType}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{d.manufacturer}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{d.model}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{d.serialNumber}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>
                  <DeviceStatusBadge status={d.status} />
                </td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>
                  <button onClick={() => openEditModal(d)} style={{ marginRight: '10px', cursor: 'pointer' }}>Edit</button>
                  <button onClick={() => setDeviceToDelete(d)} style={{ cursor: 'pointer', color: 'red' }}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Create Modal */}
      <Modal title="Add Device" isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)}>
        <form onSubmit={handleCreate}>
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Name</label>
            <input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%', padding: '8px' }} />
          </div>
          
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Device Type</label>
            <select value={deviceType} onChange={e => setDeviceType(e.target.value as DeviceType)} style={{ width: '100%', padding: '8px' }}>
              {Object.values(DeviceType).map(type => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </div>

          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Manufacturer</label>
            <input required maxLength={100} value={manufacturer} onChange={e => setManufacturer(e.target.value)} style={{ width: '100%', padding: '8px' }} />
          </div>

          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Model</label>
            <input required maxLength={100} value={model} onChange={e => setModel(e.target.value)} style={{ width: '100%', padding: '8px' }} />
          </div>

          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Serial Number</label>
            <input required maxLength={100} value={serialNumber} onChange={e => setSerialNumber(e.target.value)} style={{ width: '100%', padding: '8px' }} />
          </div>

          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Description</label>
            <textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%', padding: '8px' }} rows={3} />
          </div>
          
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '20px' }}>
            <button type="button" onClick={() => setIsCreateModalOpen(false)} style={{ marginRight: '10px', padding: '8px 16px' }}>Cancel</button>
            <button type="submit" style={{ padding: '8px 16px', backgroundColor: '#4f46e5', color: 'white', border: 'none', borderRadius: '4px' }}>Add</button>
          </div>
        </form>
      </Modal>

      {/* Edit Modal */}
      <Modal title="Edit Device" isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)}>
        <form onSubmit={handleEdit}>
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Name</label>
            <input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%', padding: '8px' }} />
          </div>
          
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Status</label>
            <select value={status} onChange={e => setStatus(e.target.value as DeviceStatus)} style={{ width: '100%', padding: '8px' }}>
              {Object.values(DeviceStatus).map(s => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>

          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>Description</label>
            <textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%', padding: '8px' }} rows={3} />
          </div>
          
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '20px' }}>
            <button type="button" onClick={() => setIsEditModalOpen(false)} style={{ marginRight: '10px', padding: '8px 16px' }}>Cancel</button>
            <button type="submit" style={{ padding: '8px 16px', backgroundColor: '#4f46e5', color: 'white', border: 'none', borderRadius: '4px' }}>Update</button>
          </div>
        </form>
      </Modal>

      {/* Delete Confirmation */}
      <ConfirmDialog
        isOpen={deviceToDelete !== null}
        title="Delete Device"
        message={`Are you sure you want to delete the device "${deviceToDelete?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        onConfirm={handleDelete}
        onCancel={() => setDeviceToDelete(null)}
        isDestructive={true}
      />
    </div>
  );
};
