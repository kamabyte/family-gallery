import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { monthTitle } from '@/lib/format';
import { cn } from '@/lib/utils';
import type { Bucket } from '@/types';

interface Props {
    buckets: Bucket[];
    current: string | null;
    onJump: (month: string) => void;
}

/** Подписи лет не ближе этого по вертикали: у редких лет полоса — пара пикселей. */
const LABEL_GAP_PX = 16;

/**
 * Полоса времени справа, как у Immich: высота месяца пропорциональна числу
 * кадров, годы подписаны. Наведение показывает месяц, щелчок — переносит туда.
 */
export function TimelineScrubber({ buckets, current, onJump }: Props) {
    const [hover, setHover] = useState<string | null>(null);
    const total = useMemo(() => buckets.reduce((sum, b) => sum + b.count, 0), [buckets]);
    const navRef = useRef<HTMLElement>(null);
    const [height, setHeight] = useState(0);

    useLayoutEffect(() => {
        const nav = navRef.current;
        if (!nav) return;
        const observer = new ResizeObserver(([entry]) => setHeight(entry.contentRect.height));
        observer.observe(nav);
        return () => observer.disconnect();
    }, []);

    // Какие годы подписать: первый месяц года, если до прошлой подписи есть место.
    const labelled = useMemo(() => {
        const result = new Set<string>();
        let offset = 0;
        let last = -Infinity;
        buckets.forEach((bucket, index) => {
            const firstOfYear = index === 0 || buckets[index - 1].month.slice(0, 4) !== bucket.month.slice(0, 4);
            if (firstOfYear && offset - last >= LABEL_GAP_PX) {
                result.add(bucket.month);
                last = offset;
            }
            offset += (bucket.count / total) * height;
        });
        return result;
    }, [buckets, total, height]);

    return (
        <nav
            ref={navRef}
            aria-label="Перейти к месяцу"
            className="fixed top-20 right-1 bottom-6 z-10 hidden w-14 flex-col select-none md:flex"
            onMouseLeave={() => setHover(null)}
        >
            {buckets.map((bucket, index) => {
                const year = bucket.month.slice(0, 4);
                const firstOfYear = index === 0 || buckets[index - 1].month.slice(0, 4) !== year;
                const active = bucket.month === current;

                return (
                    <button
                        key={bucket.month}
                        type="button"
                        onClick={() => onJump(bucket.month)}
                        onMouseEnter={() => setHover(bucket.month)}
                        onFocus={() => setHover(bucket.month)}
                        aria-label={monthTitle(bucket.month)}
                        className="group relative flex min-h-px w-full items-start justify-end pr-2"
                        style={{ flexGrow: bucket.count / total }}
                    >
                        {labelled.has(bucket.month) && <span className="absolute -top-1 right-4 text-[11px] font-medium text-muted-foreground tabular-nums">{year}</span>}
                        <span
                            className={cn(
                                'mt-px h-px w-1.5 rounded-full bg-muted-foreground/30 transition-all group-hover:w-3 group-hover:bg-foreground',
                                firstOfYear && 'w-2 bg-muted-foreground/60',
                                active && 'h-0.5 w-3 bg-brand',
                            )}
                        />
                        {hover === bucket.month && (
                            <span className="pointer-events-none absolute top-1/2 right-12 -translate-y-1/2 rounded-lg bg-popover px-2.5 py-1 text-xs font-semibold whitespace-nowrap text-popover-foreground shadow-lg ring-1 ring-border">
                                {monthTitle(bucket.month)}
                            </span>
                        )}
                    </button>
                );
            })}
        </nav>
    );
}
