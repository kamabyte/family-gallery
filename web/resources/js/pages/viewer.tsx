import { Head, Link, router, usePage } from '@inertiajs/react';
import { Camera, ChevronLeft, ChevronRight, Download, FileImage, Heart, Info, MapPin, Pause, Play, X } from 'lucide-react';
import { type PointerEvent, type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { albumIcon } from '@/components/album-card';
import { viewerHref } from '@/components/photo-tile';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useStoredToggle } from '@/hooks/use-stored-toggle';
import { fullDateTime, megapixels, timeOnly, dayTitle } from '@/lib/format';
import { cameFromApp } from '@/lib/history';
import { cn } from '@/lib/utils';
import type { AlbumType, Photo } from '@/types';

interface Props {
    photo: Photo;
    previous: Photo | null;
    next: Photo | null;
    position: { index: number; total: number };
    /** Что листают стрелки: null — лента, «album:…», «day:…», «search:…». */
    from: string | null;
    albums: { id: string; type: AlbumType; name: string }[];
}

const SLIDESHOW_MS = 5000;
const SWIPE_PX = 60;

/** Куда вернуться, если просмотрщик открыли по прямой ссылке. */
function closeHref(from: string | null) {
    const [kind, value] = (from ?? '').split(/:(.*)/s);
    if (kind === 'album') return `/albums/${value}`;
    if (kind === 'search') return `/search?q=${encodeURIComponent(value)}`;
    return '/';
}

export default function Viewer({ photo, previous, next, position, from, albums }: Props) {
    const [info, setInfo] = useStoredToggle('gallery:viewer-info', false);
    const [playing, setPlaying] = useState(() => new URLSearchParams(window.location.search).get('play') === '1');
    const [chrome, setChrome] = useState(true);
    const [loaded, setLoaded] = useState<number | null>(null);
    const swipe = useRef<{ x: number; y: number } | null>(null);
    // Гостю — только смотреть: ни избранного, ни скачивания (права проверяет и сервер).
    const family = usePage().props.auth.can.family;

    const go = useCallback(
        (target: Photo | null) => {
            if (!target) return;
            // replace: «назад» из просмотрщика ведёт сразу в ленту, а не по всем пролистанным кадрам.
            router.visit(viewerHref(target.id, from ?? undefined) + (playing ? `${from ? '&' : '?'}play=1` : ''), {
                replace: true,
                preserveState: true,
                preserveScroll: true,
            });
        },
        [from, playing],
    );

    const close = useCallback(() => {
        if (cameFromApp()) window.history.back();
        else router.visit(closeHref(from));
    }, [from]);

    const toggleFavorite = useCallback(() => {
        if (!family) return;
        router.put(`/photos/${photo.id}/favorite`, { favorite: !photo.favorite }, { preserveScroll: true, preserveState: true });
    }, [photo.id, photo.favorite, family]);

    // Клавиатура: стрелки, Esc, i — сведения, f — избранное, пробел — слайд-шоу.
    useEffect(() => {
        const onKey = (event: KeyboardEvent) => {
            if (event.target instanceof HTMLInputElement || event.metaKey || event.ctrlKey) return;
            if (event.key === 'ArrowLeft') go(previous);
            else if (event.key === 'ArrowRight') go(next);
            else if (event.key === 'Escape') close();
            else if (event.key === 'i') setInfo(!info);
            else if (event.key === 'f') toggleFavorite();
            else if (event.key === ' ' && photo.type === 'photo') {
                event.preventDefault();
                setPlaying((value) => !value);
            } else return;
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [go, previous, next, close, info, setInfo, toggleFavorite, photo.type]);

    // Соседние кадры — заранее, чтобы листание было мгновенным.
    useEffect(() => {
        for (const neighbour of [previous, next]) {
            if (neighbour && neighbour.type === 'photo') new Image().src = neighbour.preview;
        }
    }, [previous, next]);

    // Слайд-шоу: на видео ждём, пока оно доиграет само.
    useEffect(() => {
        if (!playing || photo.type === 'video') return;
        if (!next) {
            setPlaying(false);
            return;
        }
        const timer = window.setTimeout(() => go(next), SLIDESHOW_MS);
        return () => window.clearTimeout(timer);
    }, [playing, photo.id, photo.type, next, go]);

    function onPointerDown(event: PointerEvent) {
        if (event.pointerType !== 'mouse') swipe.current = { x: event.clientX, y: event.clientY };
    }

    function onPointerUp(event: PointerEvent) {
        const start = swipe.current;
        swipe.current = null;
        if (!start) return;
        const dx = event.clientX - start.x;
        const dy = event.clientY - start.y;
        if (Math.abs(dx) > SWIPE_PX && Math.abs(dx) > Math.abs(dy)) go(dx > 0 ? previous : next);
        else if (dy > SWIPE_PX * 1.5) close();
        else if (Math.abs(dx) < 8 && Math.abs(dy) < 8) setChrome((value) => !value);
    }

    const title = dayTitle(photo.date);
    const subtitle = [timeOnly(photo.taken_at), photo.place?.city].filter(Boolean).join(' · ');

    return (
        <div className="fixed inset-0 z-50 flex bg-black text-white">
            <Head title={photo.place?.city ? `${photo.place.city} — ${title}` : title} />

            <div
                className="relative min-w-0 flex-1 touch-pan-y select-none"
                onPointerDown={onPointerDown}
                onPointerUp={onPointerUp}
                onDoubleClick={() => setChrome((value) => !value)}
            >
                {/* Кадр: миниатюра (уже в кеше из ленты) и превью поверх — в одной ячейке сетки. */}
                <div className="absolute inset-0 grid grid-cols-[minmax(0,1fr)] grid-rows-[minmax(0,1fr)] p-0 md:px-20 md:py-16">
                    {photo.type === 'video' && photo.video ? (
                        <video
                            key={photo.id}
                            src={photo.video}
                            poster={photo.preview}
                            controls
                            autoPlay
                            playsInline
                            onEnded={() => playing && go(next)}
                            className="size-full object-contain"
                        />
                    ) : (
                        <>
                            <img key={`t${photo.id}`} src={photo.thumb} alt="" className="[grid-area:1/1] size-full object-contain blur-[2px]" />
                            <img
                                key={`p${photo.id}`}
                                src={photo.preview}
                                alt={[title, photo.place?.city].filter(Boolean).join(', ')}
                                onLoad={() => setLoaded(photo.id)}
                                draggable={false}
                                className={cn(
                                    '[grid-area:1/1] size-full object-contain transition-opacity duration-200',
                                    loaded === photo.id ? 'opacity-100' : 'opacity-0',
                                )}
                            />
                        </>
                    )}
                </div>

                {/* Верхняя панель */}
                <div
                    className={cn(
                        'absolute inset-x-0 top-0 flex items-center gap-2 bg-gradient-to-b from-black/70 to-transparent px-2 pt-[max(0.5rem,env(safe-area-inset-top))] pb-8 transition-opacity md:px-4',
                        chrome ? 'opacity-100' : 'pointer-events-none opacity-0',
                    )}
                >
                    <ViewerButton label="Закрыть (Esc)" onClick={close}>
                        <X className="size-5" />
                    </ViewerButton>
                    <div className="min-w-0 flex-1">
                        <div className="truncate text-[15px] font-semibold">{title}</div>
                        <div className="truncate text-xs text-white/65">
                            {subtitle} <span className="tabular-nums">· {position.index} из {position.total.toLocaleString('ru-RU')}</span>
                        </div>
                    </div>
                    {photo.type === 'photo' && (
                        <ViewerButton label={playing ? 'Пауза (пробел)' : 'Слайд-шоу (пробел)'} onClick={() => setPlaying((value) => !value)}>
                            {playing ? <Pause className="size-5 fill-current" /> : <Play className="size-5 fill-current" />}
                        </ViewerButton>
                    )}
                    {family && (
                        <>
                            <ViewerButton label={photo.favorite ? 'Убрать из избранного (F)' : 'В избранное (F)'} onClick={toggleFavorite}>
                                <Heart className={cn('size-5', photo.favorite && 'fill-[#ff5a5f] text-[#ff5a5f]')} />
                            </ViewerButton>
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <Button
                                        asChild
                                        variant="ghost"
                                        size="icon"
                                        className="size-10 rounded-full text-white hover:bg-white/15 hover:text-white"
                                    >
                                        {/* Обычная ссылка, не Inertia: файл скачивает браузер. */}
                                        <a href={`/photos/${photo.id}/download`} download={photo.filename} aria-label="Скачать оригинал">
                                            <Download className="size-5" />
                                        </a>
                                    </Button>
                                </TooltipTrigger>
                                <TooltipContent>Скачать оригинал · {photo.filename}</TooltipContent>
                            </Tooltip>
                        </>
                    )}
                    <ViewerButton label="Сведения (I)" onClick={() => setInfo(!info)} active={info}>
                        <Info className="size-5" />
                    </ViewerButton>
                </div>

                {/* Стрелки — на компьютере; на телефоне листают свайпом. */}
                {previous && (
                    <NavArrow side="left" label="Предыдущий (←)" onClick={() => go(previous)} hidden={!chrome}>
                        <ChevronLeft className="size-6" />
                    </NavArrow>
                )}
                {next && (
                    <NavArrow side="right" label="Следующий (→)" onClick={() => go(next)} hidden={!chrome}>
                        <ChevronRight className="size-6" />
                    </NavArrow>
                )}

                {playing && photo.type === 'photo' && next && (
                    <div className="absolute inset-x-0 bottom-0 h-0.5 bg-white/15">
                        <div key={photo.id} className="h-full origin-left bg-white/80" style={{ animation: `slideshow ${SLIDESHOW_MS}ms linear both` }} />
                    </div>
                )}
            </div>

            {info && <InfoPanel photo={photo} albums={albums} onClose={() => setInfo(false)} />}

            <style>{'@keyframes slideshow { from { transform: scaleX(0) } to { transform: scaleX(1) } }'}</style>
        </div>
    );
}

function ViewerButton({ label, onClick, active, children }: { label: string; onClick: () => void; active?: boolean; children: ReactNode }) {
    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <Button
                    variant="ghost"
                    size="icon"
                    onClick={onClick}
                    aria-label={label}
                    className={cn('size-10 rounded-full text-white hover:bg-white/15 hover:text-white', active && 'bg-white/20')}
                >
                    {children}
                </Button>
            </TooltipTrigger>
            <TooltipContent>{label}</TooltipContent>
        </Tooltip>
    );
}

function NavArrow({ side, label, onClick, hidden, children }: { side: 'left' | 'right'; label: string; onClick: () => void; hidden: boolean; children: ReactNode }) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-label={label}
            className={cn(
                'absolute top-1/2 hidden size-12 -translate-y-1/2 items-center justify-center rounded-full bg-black/35 text-white backdrop-blur-md transition hover:bg-white/20 md:flex',
                side === 'left' ? 'left-4' : 'right-4',
                hidden && 'pointer-events-none opacity-0',
            )}
        >
            {children}
        </button>
    );
}

function InfoPanel({ photo, albums, onClose }: { photo: Photo; albums: Props['albums']; onClose: () => void }) {
    return (
        <aside
            aria-label="Сведения"
            className="absolute inset-x-0 bottom-0 z-10 max-h-[60dvh] overflow-y-auto rounded-t-3xl bg-neutral-900 p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] md:static md:max-h-none md:w-[360px] md:shrink-0 md:rounded-none md:border-l md:border-white/10"
        >
            <div className="mb-5 flex items-center justify-between">
                <h2 className="font-display text-lg font-semibold">Сведения</h2>
                <Button variant="ghost" size="icon" onClick={onClose} aria-label="Закрыть сведения" className="rounded-full text-white hover:bg-white/15 hover:text-white">
                    <X className="size-5" />
                </Button>
            </div>

            <div className="mb-6 text-[15px] font-medium">{fullDateTime(photo.taken_at)}</div>

            <dl className="space-y-4 text-sm">
                <InfoRow icon={FileImage} title={photo.filename}>
                    {photo.width} × {photo.height} · {megapixels(photo.width, photo.height)}
                    {photo.type === 'video' && photo.duration !== null && ` · ${Math.round(photo.duration)} с`}
                </InfoRow>
                {photo.camera && <InfoRow icon={Camera} title={photo.camera} />}
                {photo.place ? (
                    <InfoRow icon={MapPin} title={[photo.place.city, photo.place.country].filter(Boolean).join(', ')}>
                        {photo.gps && (
                            <a
                                href={`https://www.openstreetmap.org/?mlat=${photo.gps.lat}&mlon=${photo.gps.lon}#map=14/${photo.gps.lat}/${photo.gps.lon}`}
                                target="_blank"
                                rel="noreferrer"
                                className="underline decoration-white/30 underline-offset-2 hover:decoration-white"
                            >
                                {photo.gps.lat.toFixed(4)}, {photo.gps.lon.toFixed(4)} — на карте
                            </a>
                        )}
                    </InfoRow>
                ) : (
                    <InfoRow icon={MapPin} title="Без геометки">
                        Место не записано — при экспорте выбирайте «неизменённый оригинал».
                    </InfoRow>
                )}
            </dl>

            {albums.length > 0 && (
                <>
                    <h3 className="mt-7 mb-3 text-xs font-semibold tracking-wide text-white/50 uppercase">В альбомах</h3>
                    <div className="flex flex-wrap gap-2">
                        {albums.map((album) => {
                            const Icon = albumIcon(album);
                            return (
                                <Link
                                    key={album.id}
                                    href={`/albums/${album.id}`}
                                    className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-3 py-1.5 text-sm hover:bg-white/20"
                                >
                                    <Icon className="size-3.5 text-[#f5a442]" />
                                    {album.name}
                                </Link>
                            );
                        })}
                    </div>
                </>
            )}
        </aside>
    );
}

function InfoRow({ icon: Icon, title, children }: { icon: typeof Camera; title: string; children?: ReactNode }) {
    return (
        <div className="flex gap-3">
            <Icon className="mt-0.5 size-[18px] shrink-0 text-white/50" />
            <div className="min-w-0">
                <dt className="truncate font-medium">{title}</dt>
                {children && <dd className="mt-0.5 text-white/60">{children}</dd>}
            </div>
        </div>
    );
}
