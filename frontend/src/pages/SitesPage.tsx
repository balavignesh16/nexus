/* eslint-disable react-hooks/set-state-in-effect */
import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import type { Site, CreateSiteRequest, UpdateSiteRequest } from '../types/models';
import { siteApi } from '../api/siteApi';
import { ApiError } from '../api/apiClient';
import { ErrorAlert } from '../components/ErrorAlert';
import { Modal } from '../components/Modal';

export const SitesPage: React.FC = () => {
  const [sites, setSites] = useState<Site[]>([]);
  const [error, setError] = useState<string>('');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [editingSite, setEditingSite] = useState<Site | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const loadSites = useCallback(async () => {
    try {
      const data = await siteApi.getSites();
      setSites(data);
      setError('');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to load sites');
    }
  }, []);

  useEffect(() => {
    loadSites();
  }, [loadSites]);

  const openCreateModal = () => {
    setName('');
    setDescription('');
    setIsCreateModalOpen(true);
  };

  const openEditModal = (site: Site) => {
    setEditingSite(site);
    setName(site.name);
    setDescription(site.description || '');
    setIsEditModalOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const request: CreateSiteRequest = { name, description };
      await siteApi.createSite(request);
      setIsCreateModalOpen(false);
      loadSites();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to create site');
    }
  };

  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingSite) return;
    try {
      const request: UpdateSiteRequest = { name, description };
      await siteApi.updateSite(editingSite.id, request);
      setIsEditModalOpen(false);
      loadSites();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to update site');
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm('Are you sure you want to delete this site?')) return;
    try {
      await siteApi.deleteSite(id);
      loadSites();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to delete site');
    }
  };

  return (
    <div>
      <h1>Sites</h1>
      <ErrorAlert message={error} onClose={() => setError('')} />
      <button onClick={openCreateModal} style={{ marginBottom: '20px', padding: '10px 20px', cursor: 'pointer' }}>Create New Site</button>
      {sites.length === 0 ? <p>No sites found.</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Name</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Description</th>
              <th style={{ borderBottom: '1px solid #ccc', padding: '10px' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {sites.map(s => (
              <tr key={s.id}>
                <td style={{ borderBottom: '1px solid #eee', padding: '10px' }}><Link to={`/sites/${s.id}`}>{s.name}</Link></td>
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
      <Modal title="Create Site" isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)}>
        <form onSubmit={handleCreate}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Create</button></div>
        </form>
      </Modal>
      <Modal title="Edit Site" isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)}>
        <form onSubmit={handleEdit}>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Name</label><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ marginBottom: '15px' }}><label style={{ display: 'block' }}>Description</label><textarea maxLength={500} value={description} onChange={e => setDescription(e.target.value)} style={{ width: '100%' }} /></div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="submit">Update</button></div>
        </form>
      </Modal>
    </div>
  );
};