import { Head, Link } from '@inertiajs/react';
import { SearchX } from 'lucide-react';
import { useRef } from 'react';
import { AlbumCard, albumIcon } from '@/components/album-card';
import { DayGroups } from '@/components/day-groups';
import { EmptyState } from '@/components/empty-state';
import { SearchBox } from '@/components/search-box';
import { rowHeightFor, useWidth } from '@/hooks/use-width';
import { items } from '@/lib/format';
import type { Album, Photo } from '@/types';

interface Props {
    query: string;
    albums: Album[];
    photos: Photo[];
    total: number;
    suggestions: Album[];
}

const EXAMPLES = ['Сочи', 'видео 2024', 'июль', 'iPhone 15 Pro', 'избранное'];

export default function Search({ query, albums, photos, total, suggestions }: Props) {
    const containerRef = useRef<HTMLDivElement>(null);
    const width = useWidth(containerRef);

    return (
        <>
            <Head title={query ? `${query} — поиск` : 'Поиск'} />
            <SearchBox className="mb-6 md:hidden" autoFocus={!query} />
            {/* Ширину меряем у постоянного блока: сетка появляется только с запросом. */}
            <div ref={containerRef}>

            {!query ? (
                <>
                    <h1 className="mb-4 font-display text-3xl font-semibold">Поиск</h1>
                    <div className="mb-8 flex flex-wrap gap-2">
                        {EXAMPLES.map((example) => (
                            <Link
                                key={example}
                                href={`/search?q=${encodeURIComponent(example)}`}
                                className="rounded-full bg-muted px-3.5 py-1.5 text-sm font-medium hover:bg-accent"
                            >
                                {example}
                            </Link>
                        ))}
                    </div>
                    <h2 className="mb-3 font-display text-xl font-semibold">Места и камеры</h2>
                    <div className="grid grid-cols-2 gap-x-4 gap-y-6 sm:grid-cols-3 lg:grid-cols-5">
                        {suggestions.map((album) => (
                            <AlbumCard key={album.id} album={album} />
                        ))}
                    </div>
                </>
            ) : (
                <>
                    <h1 className="mb-1 font-display text-3xl font-semibold">«{query}»</h1>
                    <p className="mb-6 text-sm text-muted-foreground">
                        {total > 0 ? `Нашлось ${items(total)}` : 'Ничего не нашлось'}
                        {total > photos.length && ` — показаны первые ${photos.length.toLocaleString('ru-RU')}`}
                    </p>

                    {albums.length > 0 && (
                        <div className="mb-8 flex flex-wrap gap-2">
                            {albums.map((album) => {
                                const Icon = albumIcon(album);
                                return (
                                    <Link
                                        key={album.id}
                                        href={`/albums/${album.id}`}
                                        className="inline-flex items-center gap-2 rounded-full bg-muted px-3.5 py-1.5 text-sm font-medium hover:bg-accent"
                                    >
                                        <Icon className="size-4 text-brand" />
                                        {album.name}
                                        <span className="text-muted-foreground tabular-nums">{album.count.toLocaleString('ru-RU')}</span>
                                    </Link>
                                );
                            })}
                        </div>
                    )}

                    <div>
                        {photos.length === 0 ? (
                            <EmptyState icon={SearchX} title="Ничего не нашлось">
                                Ищите по городу, стране, камере, году или месяцу: «Казань 2023», «июль видео».
                            </EmptyState>
                        ) : (
                            width > 0 && <DayGroups photos={photos} width={width} rowHeight={rowHeightFor(width)} from={`search:${query}`} />
                        )}
                    </div>
                </>
            )}
            </div>
        </>
    );
}
