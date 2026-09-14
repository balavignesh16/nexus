import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Pill } from './Pill';

describe('Pill', () => {
  it('reflects the active state via aria-pressed', () => {
    render(<Pill active onClick={() => {}}>24h</Pill>);

    expect(screen.getByRole('button', { name: '24h' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('calls onClick when clicked', () => {
    const onClick = vi.fn();
    render(<Pill onClick={onClick}>24h</Pill>);

    fireEvent.click(screen.getByRole('button', { name: '24h' }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
