import { CircleCheck, MapPin } from 'lucide-react';
import { useMemo } from 'react';
import { JustifiedGrid } from '@/components/justified-grid';
import { useSelection } from '@/hooks/use-selection';
import { dayTitle, items } from '@/lib/format';
import { cn } from '@/lib/utils';
import type { Photo } from '@/types';

interface Props {
    photos: Photo[];
    width: number;
    rowHeight: number;
    /** Контекст просмотрщика; по умолчанию стрелки листают ленту. */
    from?: string;
}

interface Day {
    date: string;
    photos: Photo[];
    place: string | null;
}

/** Самый частый город дня — подпись к заголовку, как «Сочи» у Immich. */
function placeOf(photos: Photo[]) {
    const counts = new Map<string, number>();
    for (const photo of photos) {
        if (photo.place) counts.set(photo.place.city, (counts.get(photo.place.city) ?? 0) + 1);
    }
    let best: string | null = null;
    let max = 0;
    for (const [city, n] of counts) {
        if (n > max) [best, max] = [city, n];
    }
    return best;
}

export function groupByDay(photos: Photo[]): Day[] {
    const days: Day[] = [];
    for (const photo of photos) {
        const last = days[days.length - 1];
        if (last && last.date === photo.date) last.photos.push(photo);
        else days.push({ date: photo.date, photos: [photo], place: null });
    }
    for (const day of days) day.place = placeOf(day.photos);
    return days;
}

export function DayGroups({ photos, width, rowHeight, from }: Props) {
    const days = useMemo(() => groupByDay(photos), [photos]);
    const { enabled, selected, active, setMany } = useSelection();

    return (
        <div className="flex flex-col gap-6">
            {days.map((day) => {
                const ids = day.photos.map((p) => p.id);
                const all = ids.every((id) => selected.has(id));

                return (
                    <section key={day.date} aria-label={dayTitle(day.date)}>
                        <header className="group/day flex h-10 items-center gap-2 pb-1">
                            {enabled && (
                            <button
                                type="button"
                                onClick={() => setMany(ids, !all)}
                                aria-label={all ? 'Снять выбор с дня' : 'Выбрать весь день'}
                                className={cn(
                                    'text-muted-foreground transition-opacity hover:text-foreground',
                                    all ? 'text-brand opacity-100 hover:text-brand' : 'opacity-0 group-hover/day:opacity-100 focus-visible:opacity-100',
                                    // На телефоне наведения нет — кружок только в режиме выбора.
                                    !active && 'hidden md:block',
                                )}
                            >
                                <CircleCheck className="size-5" />
                            </button>
                            )}
                            <h3 className="text-[15px] font-semibold tracking-tight">{dayTitle(day.date)}</h3>
                            {day.place && (
                                <span className="flex items-center gap-1 truncate text-sm text-muted-foreground">
                                    <MapPin className="size-3.5 shrink-0" />
                                    {day.place}
                                </span>
                            )}
                            <span className="ml-auto text-xs text-muted-foreground tabular-nums">{items(day.photos.length)}</span>
                        </header>
                        <JustifiedGrid photos={day.photos} width={width} rowHeight={rowHeight} from={from} />
                    </section>
                );
            })}
        </div>
    );
}
