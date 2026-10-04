import { Head } from '@inertiajs/react';
import { AlbumCard } from '@/components/album-card';
import type { Album, AlbumType } from '@/types';

const SECTIONS: { type: AlbumType; title: string; hint: string }[] = [
    { type: 'place', title: 'Места', hint: 'Собраны по геометкам снимков' },
    { type: 'camera', title: 'Камеры', hint: 'Чем снимали — по данным EXIF' },
    { type: 'year', title: 'Годы', hint: '' },
];

const GRID = 'grid grid-cols-2 gap-x-4 gap-y-6 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6';

export default function Albums({ albums }: { albums: Album[] }) {
    const smart = albums.filter((a) => a.type === 'smart');

    return (
        <>
            <Head title="Альбомы" />
            <h1 className="mb-1 font-display text-3xl font-semibold">Альбомы</h1>
            <p className="mb-6 text-sm text-muted-foreground">Альбомы собираются сами — из места, камеры и даты съёмки.</p>

            <div className={GRID}>
                {smart.map((album) => (
                    <AlbumCard key={album.id} album={album} />
                ))}
            </div>

            {SECTIONS.map((section) => {
                const list = albums.filter((a) => a.type === section.type);
                if (list.length === 0) return null;

                return (
                    <section key={section.type} className="mt-10">
                        <div className="mb-4 flex items-baseline gap-3">
                            <h2 className="font-display text-2xl font-semibold">{section.title}</h2>
                            <span className="text-sm text-muted-foreground">{section.hint}</span>
                        </div>
                        <div className={GRID}>
                            {list.map((album) => (
                                <AlbumCard key={album.id} album={album} />
                            ))}
                        </div>
                    </section>
                );
            })}
        </>
    );
}
