import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from './Button';
import { IconButton } from './IconButton';

describe('Button', () => {
  it('fires onClick when clicked', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Click me</Button>);

    fireEvent.click(screen.getByRole('button', { name: 'Click me' }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick} disabled>Click me</Button>);

    fireEvent.click(screen.getByRole('button', { name: 'Click me' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('the default "primary" variant uses the new accent token, never the legacy blue --primary (regression)', () => {
    render(<Button variant="primary">Primary</Button>);

    const className = screen.getByRole('button', { name: 'Primary' }).className;
    expect(className).toContain('var(--accent)');
    expect(className).not.toContain('bg-primary');
  });
});

describe('IconButton', () => {
  it('requires and renders an accessible label', () => {
    render(<IconButton aria-label="Refresh"><span>icon</span></IconButton>);

    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });
});
