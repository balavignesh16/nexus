/* eslint-disable react-hooks/set-state-in-effect */
import React, { useEffect, useState, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { Building, Space, CreateSpaceRequest, UpdateSpaceRequest } from '../types/models';
import { buildingApi } from '../api/buildingApi';
import { spaceApi } from '../api/spaceApi';
import { ApiError } from '../api/apiClient';
import { ErrorAlert } from '../components/ErrorAlert';
import { Modal } from '../components/Modal';

export const BuildingDetailsPage: React.FC = () => {
  const { buildingId } = useParams<{ buildingId: string }>();
  const [building, setBuilding] = useState<Building | null>(null);
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [error, setError] = useState<string>('');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [editingSpace, setEditingSpace] = useState<Space | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const loadData = useCallback(async () => {
    if (!buildingId) return;
    try {
      const [buildingData, spacesData] = await Promise.all([
        buildingApi.getBuilding(buildingId),
        spaceApi.getSpacesByBuildingId(buildingId)
      ]);
      setBuilding(buildingData);
      setSpaces(spacesData);
      setError('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to load data');
    }
  }, [buildingId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const openCreateModal = () => {
    setName('');
    setDescription('');
    setIsCreateModalOpen(true);
  };

  const openEditModal = (space: Space) => {
    setEditingSpace(space);
    setName(space.name);
    setDescription(space.description || '');
    setIsEditModalOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!buildingId) return;
    try {
      const request: CreateSpaceRequest = { name, description };
      await spaceApi.createSpace(buildingId, request);
      setIsCreateModalOpen(false);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to create space');
    }
  };

  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingSpace) return;
    try {
      const request: UpdateSpaceRequest = { name, description };
      await spaceApi.updateSpace(editingSpace.id, request);
      setIsEditModalOpen(false);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to update space');
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm('Are you sure you want to delete this space?')) return;
    try {
      await spaceApi.deleteSpace(id);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to delete space');
    }
  };

  if (!building) return <div>Loading...</div>;

  return (
    <div>
      <div style={{ marginBottom: '20px' }}><Link to={`/sites/${building.siteId}`}>← Back to Building's Site</Link></div>
      <h1>Building: {building.name}</h1>
      <p>{building.description}</p>
      <h2>Spaces</h2>
      <ErrorAlert message={error} onClose={() => setError('')} />
      <button onClick={openCreateModal} style={{ marginBottom: '20px', padding: '10px 20px', cursor: 'pointer' }}>Create New Space</button>
      {spaces.length === 0 ? <p>No spaces found.</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Name</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Description</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {spaces.map(s => (
              <tr key={s.id}>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}><Link to={`/spaces/${s.id}`}>{s.name}</Link></td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{s.description}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>
                  <button onClick={() => openEditModal(s)} style={{ marginRight: '10px', cursor: 'pointer' }}>Edit</button>
                  <button onClick={() => handleDelete(s.id)} style={{ cursor: 'pointer', color: 'red' }}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Modal title="Create Space" isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)}>
        <form onSubmit={handleCreate}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Create</button></div>
        </form>
      </Modal>
      <Modal title="Edit Space" isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)}>
        <form onSubmit={handleEdit}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Update</button></div>
        </form>
      </Modal>
    </div>
  );
};