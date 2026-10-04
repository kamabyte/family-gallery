import { Head } from '@inertiajs/react';
import { Images } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { DayGroups } from '@/components/day-groups';
import { EmptyState } from '@/components/empty-state';
import { Memories } from '@/components/memories';
import { TimelineScrubber } from '@/components/timeline-scrubber';
import { Skeleton } from '@/components/ui/skeleton';
import { GAP, rowHeightFor, useWidth } from '@/hooks/use-width';
import { monthTitle } from '@/lib/format';
import { estimateHeight } from '@/lib/justify';
import type { Bucket, Memory, Photo } from '@/types';

interface Props {
    buckets: Bucket[];
    /** Первые месяцы приходят вместе со страницей, остальные — по мере прокрутки. */
    months: Record<string, Photo[]>;
    memories: Memory[];
}

/** Месяц грузится заранее, когда до него остаётся пара экранов. */
const PRELOAD_MARGIN = '1600px 0px';

export default function Timeline({ buckets, months: initial, memories }: Props) {
    const containerRef = useRef<HTMLDivElement>(null);
    const width = useWidth(containerRef);
    const rowHeight = rowHeightFor(width);
    const [months, setMonths] = useState<Record<string, Photo[]>>(initial);
    const [current, setCurrent] = useState<string | null>(buckets[0]?.month ?? null);
    const loading = useRef(new Set<string>());

    const load = useCallback((month: string) => {
        if (loading.current.has(month)) return;
        loading.current.add(month);
        fetch(`/api/timeline/${month}`, { headers: { Accept: 'application/json' } })
            .then((response) => (response.ok ? response.json() : Promise.reject(response.status)))
            .then((data: { photos: Photo[] }) => setMonths((all) => ({ ...all, [month]: data.photos })))
            .catch(() => loading.current.delete(month)); // попробуем снова, когда месяц опять покажется
    }, []);

    // Подгрузка месяцев у края экрана.
    useEffect(() => {
        const observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const month = (entry.target as HTMLElement).dataset.month;
                    if (entry.isIntersecting && month) load(month);
                }
            },
            { rootMargin: PRELOAD_MARGIN },
        );
        document.querySelectorAll<HTMLElement>('[data-month]').forEach((section) => observer.observe(section));
        return () => observer.disconnect();
    }, [load, buckets]);

    // Какой месяц сейчас вверху экрана — для подсветки на полосе справа.
    useEffect(() => {
        let frame = 0;
        const onScroll = () => {
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(() => {
                const sections = document.querySelectorAll<HTMLElement>('[data-month]');
                for (const section of sections) {
                    if (section.getBoundingClientRect().bottom > 96) {
                        setCurrent(section.dataset.month ?? null);
                        break;
                    }
                }
            });
        };
        window.addEventListener('scroll', onScroll, { passive: true });
        return () => {
            window.removeEventListener('scroll', onScroll);
            cancelAnimationFrame(frame);
        };
    }, []);

    const jump = useCallback(
        (month: string) => {
            load(month);
            document.getElementById(`m-${month}`)?.scrollIntoView({ block: 'start' });
        },
        [load],
    );

    if (buckets.length === 0) {
        return (
            <EmptyState icon={Images} title="Пока ни одной фотографии">
                Положите снимки в папку <b>Imports</b> на шаре Photos — индексатор разложит их и покажет здесь.
            </EmptyState>
        );
    }

    return (
        <>
            <Head title="Фото" />
            <div ref={containerRef} className="md:pr-12">
                <Memories memories={memories} />
                {width > 0 &&
                    buckets.map((bucket) => {
                        const photos = months[bucket.month];

                        return (
                            <section
                                key={bucket.month}
                                id={`m-${bucket.month}`}
                                data-month={bucket.month}
                                className="scroll-mt-20 pb-10"
                                style={photos ? undefined : { minHeight: estimateHeight(bucket.count, width, rowHeight, GAP) }}
                            >
                                <h2 className="mb-3 font-display text-2xl font-semibold md:text-[28px]">{monthTitle(bucket.month)}</h2>
                                {photos ? (
                                    <DayGroups photos={photos} width={width} rowHeight={rowHeight} />
                                ) : (
                                    <div className="flex flex-wrap" style={{ gap: GAP }}>
                                        {Array.from({ length: Math.min(bucket.count, 24) }, (_, i) => (
                                            <Skeleton key={i} className="rounded-none" style={{ width: rowHeight * 0.95, height: rowHeight }} />
                                        ))}
                                    </div>
                                )}
                            </section>
                        );
                    })}
            </div>
            <TimelineScrubber buckets={buckets} current={current} onJump={jump} />
        </>
    );
}
