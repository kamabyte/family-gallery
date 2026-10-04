import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

export function EmptyState({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
    return (
        <div className="flex flex-col items-center justify-center px-6 py-24 text-center">
            <span className="mb-4 flex size-16 items-center justify-center rounded-2xl bg-muted">
                <Icon className="size-7 text-muted-foreground" />
            </span>
            <h2 className="font-display text-xl font-semibold">{title}</h2>
            {children && <div className="mt-2 max-w-sm text-sm text-muted-foreground">{children}</div>}
        </div>
    );
}
