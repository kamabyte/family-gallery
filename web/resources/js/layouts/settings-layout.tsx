import { Link, usePage } from '@inertiajs/react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { edit as profile } from '@/routes/profile';
import { edit as security } from '@/routes/security';
import { index as users } from '@/routes/users';

/** Настройки внутри обычной оболочки: вкладки сверху, как сегменты в iOS. */
export default function SettingsLayout({ children }: { children: ReactNode }) {
    const { url, props } = usePage();
    const tabs = [
        { title: 'Профиль', href: profile().url },
        { title: 'Безопасность', href: security().url },
        ...(props.auth.can.admin ? [{ title: 'Пользователи', href: users().url }] : []),
    ];

    return (
        <div className="mx-auto max-w-3xl">
            <h1 className="mb-4 font-display text-3xl font-semibold">Настройки</h1>
            <nav aria-label="Настройки" className="mb-8 inline-flex rounded-full bg-muted p-1">
                {tabs.map((tab) => {
                    const active = url.split('?')[0].startsWith(tab.href);

                    return (
                        <Link
                            key={tab.href}
                            href={tab.href}
                            aria-current={active ? 'page' : undefined}
                            className={cn(
                                'rounded-full px-4 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground',
                                active && 'bg-background text-foreground shadow-sm',
                            )}
                        >
                            {tab.title}
                        </Link>
                    );
                })}
            </nav>
            <section className="space-y-12">{children}</section>
        </div>
    );
}
