import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

const mockUseExplorerHierarchy = vi.fn();
vi.mock('../../hooks/useExplorerHierarchy', () => ({
  useExplorerHierarchy: () => mockUseExplorerHierarchy(),
}));

// react-virtuoso measures real layout (ResizeObserver) to decide what to
// render, which jsdom doesn't provide - stubbed with a plain synchronous
// map over the real `data` prop so these tests exercise ExplorerTree's own
// real row markup/handlers, not virtualization mechanics.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({ data, itemContent }: { data: any[]; itemContent: (index: number, item: any) => any }) => (
    <div>{data.map((item, i) => <div key={i}>{itemContent(i, item)}</div>)}</div>
  ),
}));

import { ExplorerTree } from './ExplorerTree';

function tree() {
  return [
    {
      id: 'site-1',
      name: 'HQ Campus',
      type: 'SITE',
      children: [
        { id: 'building-1', name: 'Main Building', type: 'BUILDING' },
      ],
    },
    {
      id: 'device-1',
      name: 'Gas Sensor 1',
      type: 'DEVICE',
    },
  ];
}

function renderTree(onSelect = vi.fn()) {
  render(<ExplorerTree selectedNode={null} onSelect={onSelect} />);
  return { onSelect };
}

describe('ExplorerTree - Step 9C: keyboard-accessible primary navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseExplorerHierarchy.mockReturnValue({ data: { tree: tree() }, isLoading: false });
  });

  it('every real tree row exposes button semantics and is keyboard-focusable', () => {
    const { container } = render(<ExplorerTree selectedNode={null} onSelect={vi.fn()} />);

    // Scoped to the row-level div[role="button"] this fix adds - not the
    // nested chevron <button> (a real native button, focusable by
    // default without needing an explicit tabIndex).
    const rows = container.querySelectorAll('div[role="button"]');
    expect(rows.length).toBeGreaterThan(0);
    rows.forEach((row) => expect(row).toHaveAttribute('tabindex', '0'));
  });

  it('Enter on a real leaf node selects it - the same real action a click performs', () => {
    const { onSelect } = renderTree();

    const leaf = screen.getByText('Gas Sensor 1').closest('[role="button"]')!;
    fireEvent.keyDown(leaf, { key: 'Enter' });

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'device-1', name: 'Gas Sensor 1', type: 'DEVICE' }));
  });

  it('Space on a real leaf node selects it, matching Enter/click behavior', () => {
    const { onSelect } = renderTree();

    const leaf = screen.getByText('Gas Sensor 1').closest('[role="button"]')!;
    fireEvent.keyDown(leaf, { key: ' ' });

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'device-1' }));
  });

  it('Enter on a real parent node toggles its real aria-expanded state, never selecting a non-existent node', () => {
    const { onSelect } = renderTree();

    // The first site auto-expands on load (existing behavior, unchanged),
    // so it starts expanded - Enter toggles it closed.
    const parent = screen.getByText('HQ Campus').closest('[role="button"]')!;
    expect(parent).toHaveAttribute('aria-expanded', 'true');

    fireEvent.keyDown(parent, { key: 'Enter' });

    expect(parent).toHaveAttribute('aria-expanded', 'false');
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('a key other than Enter/Space does not activate the row', () => {
    const { onSelect } = renderTree();

    const leaf = screen.getByText('Gas Sensor 1').closest('[role="button"]')!;
    fireEvent.keyDown(leaf, { key: 'Tab' });

    expect(onSelect).not.toHaveBeenCalled();
  });

  it('clicking still selects a leaf node - no mouse regression from the keyboard fix', () => {
    const { onSelect } = renderTree();

    fireEvent.click(screen.getByText('Gas Sensor 1'));

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'device-1' }));
  });
});
