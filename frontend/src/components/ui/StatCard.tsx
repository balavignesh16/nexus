import { motion } from 'framer-motion';

interface StatCardProps {
  title: string;
  value: string | number;
  subtitle: string;
}

export function StatCard({ title, value, subtitle }: StatCardProps) {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex flex-col justify-between hover:border-blue-500/30 transition-all group shadow-sm h-full">
      <h3 className="text-sm font-semibold text-foreground uppercase tracking-wider group-hover:text-blue-400 transition-colors">{title}</h3>
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
