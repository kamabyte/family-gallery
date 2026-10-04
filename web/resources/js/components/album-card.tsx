import { Link } from '@inertiajs/react';
import { Camera, CalendarDays, Heart, MapPin, Plane, Video } from 'lucide-react';
import { items, videos, yearRange } from '@/lib/format';
import { cn } from '@/lib/utils';
import type { Album } from '@/types';

const ICONS = { trip: Plane, place: MapPin, camera: Camera, year: CalendarDays, favorites: Heart, videos: Video };

export function albumIcon(album: Pick<Album, 'id' | 'type'>) {
    if (album.type === 'smart') return album.id === 'videos' ? ICONS.videos : ICONS.favorites;
    return ICONS[album.type];
}

export function AlbumCard({ album, className }: { album: Album; className?: string }) {
    const Icon = albumIcon(album);
    const meta = [album.subtitle, album.id === 'videos' ? videos(album.count) : items(album.count), album.type !== 'year' ? yearRange(album.from, album.to) : null].filter(Boolean).join(' · ');

    return (
        <Link href={`/albums/${album.id}`} prefetch="hover" className={cn('group block min-w-0', className)}>
            <div
                className="relative aspect-square overflow-hidden rounded-2xl ring-1 ring-black/5 ring-inset dark:ring-white/5"
                style={{ backgroundColor: album.cover?.color }}
            >
                {album.cover ? (
                    <img
                        src={album.cover.thumb}
                        alt=""
                        loading="lazy"
                        className="size-full object-cover transition-transform duration-500 group-hover:scale-[1.04]"
                    />
                ) : (
                    <span className="flex size-full items-center justify-center bg-muted">
                        <Icon className="size-10 text-muted-foreground" />
                    </span>
                )}
                <span className="absolute top-2 left-2 flex size-7 items-center justify-center rounded-full bg-black/35 text-white backdrop-blur-md">
                    <Icon className="size-3.5" />
                </span>
            </div>
            <div className="mt-2 truncate text-[15px] font-semibold">{album.name}</div>
            <div className="truncate text-[13px] text-muted-foreground">{meta}</div>
        </Link>
    );
}
