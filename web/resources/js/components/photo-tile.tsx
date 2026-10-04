import { Link } from '@inertiajs/react';
import { Check, Heart, Play } from 'lucide-react';
import { useRef, useState } from 'react';
import { useSelection } from '@/hooks/use-selection';
import { duration } from '@/lib/format';
import { cn } from '@/lib/utils';
import type { Photo } from '@/types';

interface Props {
    photo: Photo;
    width: number;
    height: number;
    /** Что листают стрелки в просмотрщике: альбом, день, поиск. */
    from?: string;
}

export function viewerHref(id: number, from?: string) {
    return from ? `/photos/${id}?from=${encodeURIComponent(from)}` : `/photos/${id}`;
}

export function PhotoTile({ photo, width, height, from }: Props) {
    const [loaded, setLoaded] = useState(false);
    const { enabled, selected, active, toggle } = useSelection();
    const isSelected = selected.has(photo.id);
    // Касание с удержанием — выбор, как в Google Photos: наведения на телефоне нет.
    const press = useRef<{ timer: number; fired: boolean } | null>(null);

    function startPress(pointerType: string) {
        if (pointerType === 'mouse' || !enabled) return;
        const state = { fired: false, timer: 0 };
        state.timer = window.setTimeout(() => {
            state.fired = true;
            navigator.vibrate?.(10);
            toggle(photo.id);
        }, 450);
        press.current = state;
    }

    function cancelPress() {
        if (press.current) window.clearTimeout(press.current.timer);
    }
    const label = [photo.type === 'video' ? 'Видео' : 'Фото', photo.place?.city, photo.filename].filter(Boolean).join(' · ');

    return (
        <div className="group/tile relative shrink-0 overflow-hidden" style={{ width, height, backgroundColor: photo.color }}>
            <Link
                href={viewerHref(photo.id, from)}
                prefetch="hover"
                aria-label={label}
                onPointerDown={(event) => startPress(event.pointerType)}
                onPointerUp={cancelPress}
                onPointerLeave={cancelPress}
                onPointerCancel={cancelPress}
                onContextMenu={(event) => press.current && event.preventDefault()}
                onClick={(event) => {
                    if (press.current?.fired) {
                        event.preventDefault();
                        press.current = null;
                        return;
                    }
                    // В режиме выбора щелчок выбирает, а не открывает.
                    if (active) {
                        event.preventDefault();
                        toggle(photo.id);
                    }
                }}
                className="block size-full focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring"
            >
                <img
                    src={photo.thumb}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    draggable={false}
                    onLoad={() => setLoaded(true)}
                    className={cn(
                        'size-full object-cover transition-transform duration-300',
                        loaded ? 'fade-in-image' : 'opacity-0',
                        isSelected && 'scale-[0.86] rounded-lg',
                    )}
                />
                {/* Затемнение сверху — чтобы читались значки на светлых кадрах. */}
                <span className="pointer-events-none absolute inset-x-0 top-0 h-14 bg-gradient-to-b from-black/45 to-transparent opacity-0 transition-opacity group-hover/tile:opacity-100" />
                {photo.type === 'video' && photo.duration !== null && (
                    <span className="pointer-events-none absolute top-1.5 right-1.5 inline-flex items-center gap-1 rounded-full bg-black/45 px-1.5 py-0.5 text-[11px] font-semibold text-white tabular-nums backdrop-blur-sm">
                        {duration(photo.duration)}
                        <Play className="size-2.5 fill-current" />
                    </span>
                )}
                {photo.favorite && (
                    <Heart className="pointer-events-none absolute bottom-1.5 left-1.5 size-4 fill-white text-white drop-shadow" />
                )}
            </Link>
            {enabled && (
            <button
                type="button"
                onClick={() => toggle(photo.id)}
                aria-label={isSelected ? 'Снять выбор' : 'Выбрать'}
                aria-pressed={isSelected}
                className={cn(
                    'absolute top-1.5 left-1.5 flex size-6 items-center justify-center rounded-full border-2 transition-all',
                    isSelected
                        ? 'border-brand bg-brand text-brand-foreground opacity-100'
                        : 'border-white/90 bg-black/10 text-transparent opacity-0 group-hover/tile:opacity-100 hover:bg-white/30 focus-visible:opacity-100',
                    active && !isSelected && 'opacity-100',
                )}
            >
                <Check className="size-3.5" strokeWidth={3} />
            </button>
            )}
        </div>
    );
}
