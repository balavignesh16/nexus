/**
 * Builds a CSV string from real row data and triggers a browser download.
 * No fabricated columns/values - callers pass exactly the data already
 * displayed/fetched from the backend.
 */
export function downloadCsv(filename: string, columns: string[], rows: (string | number)[][]): void {
  const escape = (value: string | number): string => {
    const s = String(value);
    if (s.includes(',') || s.includes('"') || s.includes('\n')) {
      return `"${s.replace(/"/g, '""')}"`;
    }
    return s;
  };

  const lines = [columns.map(escape).join(',')];
  for (const row of rows) {
    lines.push(row.map(escape).join(','));
  }
  const csv = lines.join('\n');

  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
