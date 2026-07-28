import React from 'react';
export const ErrorAlert: React.FC<{ message?: string; onClose: () => void }> = ({ message, onClose }) => {
  if (!message) return null;
  return (
    <div style={{ padding: '10px', backgroundColor: '#fee', color: '#c00', border: '1px solid #c00', marginBottom: '10px' }}>
      {message}
      <button onClick={onClose} style={{ marginLeft: '10px' }}>x</button>
    </div>
  );
};
