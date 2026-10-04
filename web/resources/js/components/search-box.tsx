import { router, usePage } from '@inertiajs/react';
import { Search, X } from 'lucide-react';
import { type FormEvent, useEffect, useState } from 'react';
import { cn } from '@/lib/utils';

/** Поиск по местам, камерам, годам и месяцам: «Сочи 2024», «июль видео». */
export function SearchBox({ className, autoFocus }: { className?: string; autoFocus?: boolean }) {
    const { url } = usePage();
    const onSearchPage = url.startsWith('/search');
    const initial = onSearchPage ? (new URLSearchParams(url.split('?')[1] ?? '').get('q') ?? '') : '';
    const [q, setQ] = useState(initial);

    useEffect(() => setQ(initial), [initial]);

    function submit(event: FormEvent) {
        event.preventDefault();
        router.get('/search', q.trim() ? { q: q.trim() } : {}, { preserveState: onSearchPage });
    }

    return (
        <form role="search" onSubmit={submit} className={cn('relative w-full', className)}>
            <Search className="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
                type="search"
                value={q}
                onChange={(event) => setQ(event.target.value)}
                placeholder="Места, камеры, годы, месяцы"
                autoFocus={autoFocus}
                aria-label="Поиск по фотографиям"
                className="h-10 w-full rounded-full border border-transparent bg-muted pr-10 pl-10 text-[15px] outline-none placeholder:text-muted-foreground focus:border-ring/60 focus:bg-background [&::-webkit-search-cancel-button]:hidden"
            />
            {q && (
                <button
                    type="button"
                    onClick={() => setQ('')}
                    aria-label="Очистить"
                    className="absolute top-1/2 right-3 -translate-y-1/2 rounded-full p-0.5 text-muted-foreground hover:text-foreground"
                >
                    <X className="size-4" />
                </button>
            )}
        </form>
    );
}
