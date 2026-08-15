import { CheckCircle2, XCircle, AlertCircle } from 'lucide-react';
import type { ActionHistoryResponse } from '../../api/actionApi';

interface ActionFeedProps {
  actions: ActionHistoryResponse[];
}

export function ActionFeed({ actions }: ActionFeedProps) {
  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle2 size={16} className="text-success" />;
      case 'FAILURE': return <XCircle size={16} className="text-danger" />;
      default: return <AlertCircle size={16} className="text-warning" />;
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="overflow-y-auto flex-1 p-4 flex flex-col gap-3">
        {actions.length === 0 ? (
          <div className="text-center text-muted-foreground py-10 text-sm">No actions executed</div>
        ) : (
          actions.map((action) => (
            <div key={action.historyId} className="flex gap-4 p-3 rounded-md bg-background/30 hover:bg-background/80 transition-colors border border-transparent hover:border-border">
              <div className="mt-1">
                {getStatusIcon(action.status)}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex justify-between items-start mb-1">
                  <span className="text-sm font-medium text-foreground">{action.actionType}</span>
                  <span className="text-xs text-muted-foreground font-mono">{new Date(action.timestamp).toLocaleTimeString()}</span>
                </div>
                <div className="text-xs text-muted-foreground mb-1">{action.message}</div>
                <div className="flex justify-between items-center mt-2">
                  <span className="text-[10px] uppercase tracking-wider text-muted-foreground bg-background px-2 py-1 rounded">
                    Device: {action.deviceId ? action.deviceId.substring(0,8) + '...' : 'System'}
                  </span>
                  <span className="text-[10px] text-muted-foreground font-mono truncate max-w-[120px]">
                    {action.correlationId}
                  </span>
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
