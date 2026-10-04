import { Head, Link } from '@inertiajs/react';
import { ChevronLeft, Play } from 'lucide-react';
import { useRef } from 'react';
import { albumIcon } from '@/components/album-card';
import { DayGroups } from '@/components/day-groups';
import { EmptyState } from '@/components/empty-state';
import { viewerHref } from '@/components/photo-tile';
import { Button } from '@/components/ui/button';
import { rowHeightFor, useWidth } from '@/hooks/use-width';
import { items, yearRange } from '@/lib/format';
import type { Album as AlbumData, Photo } from '@/types';

export default function Album({ album, photos }: { album: AlbumData; photos: Photo[] }) {
    const containerRef = useRef<HTMLDivElement>(null);
    const width = useWidth(containerRef);
    const from = `album:${album.id}`;
    const Icon = albumIcon(album);
    const cover = album.cover;

    return (
        <>
            <Head title={album.name} />

            {/* Обложка размытым фоном — как шапка канала в MyTube. */}
            <div className="relative -mx-4 -mt-4 mb-6 overflow-hidden md:-mx-6 md:-mt-6 lg:-mx-8">
                {cover && (
                    <img src={cover.thumb} alt="" className="absolute inset-0 size-full scale-125 object-cover opacity-40 blur-3xl saturate-150" />
                )}
                <div className="absolute inset-0 bg-gradient-to-b from-transparent to-background" />
                <div className="relative flex items-end gap-5 px-4 pt-6 pb-2 md:px-6 md:pt-10 lg:px-8">
                    {cover && (
                        <img src={cover.thumb} alt="" className="hidden size-36 shrink-0 rounded-2xl object-cover shadow-2xl shadow-black/40 sm:block" />
                    )}
                    <div className="min-w-0 flex-1">
                        <Link href="/albums" className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
                            <ChevronLeft className="size-4" />
                            Альбомы
                        </Link>
                        <h1 className="flex items-center gap-2.5 font-display text-3xl font-semibold md:text-4xl">
                            <Icon className="size-6 shrink-0 text-brand md:size-7" />
                            <span className="truncate">{album.name}</span>
                        </h1>
                        <p className="mt-1 text-sm text-muted-foreground">
                            {[album.subtitle, items(album.count), yearRange(album.from, album.to)].filter(Boolean).join(' · ')}
                        </p>
                    </div>
                    {photos.length > 0 && (
                        <Button asChild className="rounded-full">
                            <Link href={`${viewerHref(photos[0].id, from)}&play=1`}>
                                <Play className="size-4 fill-current" />
                                <span className="hidden sm:inline">Слайд-шоу</span>
                            </Link>
                        </Button>
                    )}
                </div>
            </div>

            <div ref={containerRef}>
                {photos.length === 0 ? (
                    <EmptyState icon={Icon} title="Здесь пока пусто">
                        {album.id === 'favorites' ? 'Отмечайте понравившиеся кадры сердечком — они соберутся здесь.' : null}
                    </EmptyState>
                ) : (
                    width > 0 && <DayGroups photos={photos} width={width} rowHeight={rowHeightFor(width)} from={from} />
                )}
            </div>
        </>
    );
}
