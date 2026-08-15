import { Box } from 'lucide-react';
import { EmptyState } from '../components/ui/EmptyState';

function AboutPage() {
  return (
    <div className="h-full bg-[#060b14] flex flex-col">
      <div className="shrink-0 p-6 border-b border-[#1f1f28]">
        <h1 className="text-2xl font-bold text-white tracking-tight">About NEXUS</h1>
        <p className="text-muted-foreground text-sm mt-1">Platform information and versioning.</p>
      </div>
      <EmptyState 
        icon={Box} 
        title="NEXUS Operations Center" 
        description="Version 1.0.0 (Build 250803.1)"
      />
    </div>
  );
}

export default AboutPage
