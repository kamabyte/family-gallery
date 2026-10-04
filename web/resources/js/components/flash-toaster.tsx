import { router } from '@inertiajs/react';
import { useEffect } from 'react';
import { Toaster, toast } from 'sonner';
import type { Toast } from '@/types';

/**
 * Сообщения сервера (Inertia::flash('toast', …)) — всплывашками. Цвета —
 * из токенов темы, чтобы совпадать и в светлой, и в тёмной.
 */
export function FlashToaster() {
    useEffect(
        () =>
            router.on('flash', (event) => {
                const flash = (event.detail.flash ?? {}) as { toast?: Toast };
                const message = flash.toast;
                if (!message) return;
                const options = { description: message.description ?? undefined };
                if (message.type === 'error') toast.error(message.message, options);
                else toast.success(message.message, options);
            }),
        [],
    );

    return (
        <Toaster
            position="bottom-right"
            offset={{ bottom: 24, right: 24 }}
            mobileOffset={{ bottom: 72 }}
            toastOptions={{ classNames: { description: '!text-muted-foreground', title: '!font-semibold' } }}
            style={
                {
                    '--normal-bg': 'var(--popover)',
                    '--normal-text': 'var(--popover-foreground)',
                    '--normal-border': 'var(--border)',
                    '--border-radius': '14px',
                } as React.CSSProperties
            }
        />
    );
}
