import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Gauge } from './Gauge';

describe('Gauge', () => {
  it('renders the real value it was given, never a fabricated one', () => {
    render(<Gauge value={22} min={10} max={40} unit="°" />);

    expect(screen.getByText('22')).toBeInTheDocument();
    expect(screen.getByText('°')).toBeInTheDocument();
  });

  it('clamps a value above max to max, rather than overflowing the arc', () => {
    render(<Gauge value={999} min={0} max={100} />);

    expect(screen.getByText('100')).toBeInTheDocument();
  });

  it('clamps a value below min to min', () => {
    render(<Gauge value={-50} min={0} max={100} />);

    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('renders the caller-supplied label, never inventing one', () => {
    render(<Gauge value={5} min={0} max={10} label="Real sensor reading" />);

    expect(screen.getByText('Real sensor reading')).toBeInTheDocument();
  });
});
