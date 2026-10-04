import { useMemo } from 'react';
import { PhotoTile } from '@/components/photo-tile';
import { GAP } from '@/hooks/use-width';
import { justify, tileWidth } from '@/lib/justify';
import type { Photo } from '@/types';

interface Props {
    photos: Photo[];
    width: number;
    rowHeight: number;
    from?: string;
}

export function JustifiedGrid({ photos, width, rowHeight, from }: Props) {
    const rows = useMemo(() => justify(photos, width, rowHeight, GAP), [photos, width, rowHeight]);

    return (
        <div className="flex flex-col" style={{ gap: GAP }}>
            {rows.map((row) => (
                <div key={row.items[0].id} className="flex" style={{ gap: GAP, height: row.height }}>
                    {row.items.map((photo) => (
                        <PhotoTile key={photo.id} photo={photo} width={tileWidth(photo, row.height)} height={row.height} from={from} />
                    ))}
                </div>
            ))}
        </div>
    );
}
