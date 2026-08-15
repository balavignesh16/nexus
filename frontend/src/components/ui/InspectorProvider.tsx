import { createContext, useContext, useState, type ReactNode } from 'react';
import type { InspectorItem } from './UniversalInspector';
import { UniversalInspector } from './UniversalInspector';

interface InspectorContextType {
  openInspector: (item: InspectorItem) => void;
  closeInspector: () => void;
}

const InspectorContext = createContext<InspectorContextType | undefined>(undefined);

export function InspectorProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [item, setItem] = useState<InspectorItem | null>(null);

  const openInspector = (newItem: InspectorItem) => {
    setItem(newItem);
    setOpen(true);
  };

  const closeInspector = () => {
    setOpen(false);
    setTimeout(() => setItem(null), 300); // clear after animation
  };

  return (
    <InspectorContext.Provider value={{ openInspector, closeInspector }}>
      {children}
      <UniversalInspector open={open} onClose={closeInspector} item={item} />
    </InspectorContext.Provider>
  );
}

export function useInspector() {
  const context = useContext(InspectorContext);
  if (context === undefined) {
    throw new Error('useInspector must be used within an InspectorProvider');
  }
  return context;
}
