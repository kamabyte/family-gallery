import type { ReactNode } from 'react';
import { LogoMark } from '@/components/logo';

/**
 * Вход и подтверждение пароля — карточка по центру. Заголовок и подпись страница
 * задаёт через `Page.layout = { title, description }` (как в awgkeys).
 */
export default function AuthLayout({ title = '', description = '', children }: { title?: string; description?: string; children: ReactNode }) {
    return (
        <div className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4 py-10">
            {/* Тёплое пятно за карточкой — в цвет акцента галереи. */}
            <div
                aria-hidden
                className="pointer-events-none absolute top-1/2 left-1/2 size-[640px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-[radial-gradient(closest-side,color-mix(in_oklch,var(--brand)_22%,transparent),transparent)] blur-2xl"
            />
            <div className="relative w-full max-w-sm">
                <div className="mb-8 flex flex-col items-center gap-4 text-center">
                    <LogoMark className="size-14 rounded-2xl [&>svg]:size-8" />
                    <div className="space-y-1.5">
                        <h1 className="font-display text-2xl font-semibold">{title}</h1>
                        {description && <p className="text-sm text-muted-foreground">{description}</p>}
                    </div>
                </div>
                <div className="rounded-3xl border border-border/60 bg-card/80 p-6 shadow-xl shadow-black/10 backdrop-blur-xl">{children}</div>
            </div>
        </div>
    );
}
