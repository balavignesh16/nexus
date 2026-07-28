/* eslint-disable react-hooks/set-state-in-effect */
import React, { useEffect, useState, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { Site, Building, CreateBuildingRequest, UpdateBuildingRequest } from '../types/models';
import { siteApi } from '../api/siteApi';
import { buildingApi } from '../api/buildingApi';
import { ApiError } from '../api/apiClient';
import { ErrorAlert } from '../components/ErrorAlert';
import { Modal } from '../components/Modal';

export const SiteDetailsPage: React.FC = () => {
  const { siteId } = useParams<{ siteId: string }>();
  const [site, setSite] = useState<Site | null>(null);
  const [buildings, setBuildings] = useState<Building[]>([]);
  const [error, setError] = useState<string>('');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [editingBuilding, setEditingBuilding] = useState<Building | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const loadData = useCallback(async () => {
    if (!siteId) return;
    try {
      const [siteData, buildingsData] = await Promise.all([
        siteApi.getSite(siteId),
        buildingApi.getBuildingsBySiteId(siteId)
      ]);
      setSite(siteData);
      setBuildings(buildingsData);
      setError('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to load data');
    }
  }, [siteId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const openCreateModal = () => {
    setName('');
    setDescription('');
    setIsCreateModalOpen(true);
  };

  const openEditModal = (building: Building) => {
    setEditingBuilding(building);
    setName(building.name);
    setDescription(building.description || '');
    setIsEditModalOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!siteId) return;
    try {
      const request: CreateBuildingRequest = { name, description };
      await buildingApi.createBuilding(siteId, request);
      setIsCreateModalOpen(false);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to create building');
    }
  };

  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingBuilding) return;
    try {
      const request: UpdateBuildingRequest = { name, description };
      await buildingApi.updateBuilding(editingBuilding.id, request);
      setIsEditModalOpen(false);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to update building');
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm('Are you sure you want to delete this building?')) return;
    try {
      await buildingApi.deleteBuilding(id);
      loadData();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to delete building');
    }
  };

  if (!site) return <div>Loading...</div>;

  return (
    <div>
      <div style={{ marginBottom: '20px' }}><Link to="/sites">← Back to Sites</Link></div>
      <h1>Site: {site.name}</h1>
      <p>{site.description}</p>
      <h2>Buildings</h2>
      <ErrorAlert message={error} onClose={() => setError('')} />
      <button onClick={openCreateModal} style={{ marginBottom: '20px', padding: '10px 20px', cursor: 'pointer' }}>Create New Building</button>
      {buildings.length === 0 ? <p>No buildings found.</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Name</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Description</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {buildings.map(b => (
              <tr key={b.id}>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}><Link to={`/buildings/${b.id}`}>{b.name}</Link></td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>{b.description}</td>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}>
                  <button onClick={() => openEditModal(b)} style={{ marginRight: '10px', cursor: 'pointer' }}>Edit</button>
                  <button onClick={() => handleDelete(b.id)} style={{ cursor: 'pointer', color: 'red' }}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Modal title="Create Building" isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)}>
        <form onSubmit={handleCreate}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Create</button></div>
        </form>
      </Modal>
      <Modal title="Edit Building" isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)}>
        <form onSubmit={handleEdit}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Update</button></div>
        </form>
      </Modal>
    </div>
  );
};