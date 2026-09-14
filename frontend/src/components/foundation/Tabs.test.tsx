import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Tabs } from './Tabs';

const items = [
  { value: 'a', label: 'Tab A' },
  { value: 'b', label: 'Tab B' },
];

describe('Tabs', () => {
  it('marks the active tab with aria-selected', () => {
    render(<Tabs items={items} value="a" onChange={() => {}} />);

    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveAttribute('aria-selected', 'false');
  });

  it('calls onChange with the clicked tab value', () => {
    const onChange = vi.fn();
    render(<Tabs items={items} value="a" onChange={onChange} />);

    fireEvent.click(screen.getByRole('tab', { name: 'Tab B' }));

    expect(onChange).toHaveBeenCalledWith('b');
  });
});
