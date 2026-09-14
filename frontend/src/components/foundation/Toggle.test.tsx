import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Toggle } from './Toggle';

describe('Toggle', () => {
  it('renders as an accessible switch reflecting the checked state', () => {
    render(<Toggle checked={true} onChange={() => {}} label="Sample" />);
    expect(screen.getByRole('switch', { name: 'Sample' })).toHaveAttribute('aria-checked', 'true');
  });

  it('calls onChange with the inverted value when clicked', () => {
    const onChange = vi.fn();
    render(<Toggle checked={false} onChange={onChange} label="Sample" />);

    fireEvent.click(screen.getByRole('switch'));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('does not fire onChange when disabled', () => {
    const onChange = vi.fn();
    render(<Toggle checked={false} onChange={onChange} disabled label="Sample" />);

    fireEvent.click(screen.getByRole('switch'));

    expect(onChange).not.toHaveBeenCalled();
  });
});
