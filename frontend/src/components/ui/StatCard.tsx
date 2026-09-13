import { motion } from 'framer-motion';
import { DataSourceBadge, type DataClassification } from './DataSourceBadge';

interface StatCardProps {
  title: string;
  value: string | number;
  subtitle: string;
  /** Optional - omit for cards with no explicit provenance concept (unchanged existing behavior). */
  badge?: DataClassification;
}

export function StatCard({ title, value, subtitle, badge }: StatCardProps) {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex flex-col justify-between hover:border-blue-500/30 transition-all group shadow-sm h-full">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-foreground uppercase tracking-wider group-hover:text-blue-400 transition-colors">{title}</h3>
        {badge && <DataSourceBadge classification={badge} />}
      </div>
      <motion.div 
        key={value}
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        className="text-4xl font-bold text-foreground mt-3 mb-1"
      >
        {value}
      </motion.div>
      <p className="text-xs text-muted-foreground">{subtitle}</p>
    </div>
  );
}
