export interface Sized {
    width: number;
    height: number;
}

export interface Row<T> {
    items: T[];
    height: number;
}

/** Совсем узкие панорамы и высокие скриншоты не должны занимать ряд целиком. */
const ratioOf = (item: Sized) => Math.min(Math.max(item.width / item.height, 0.5), 2.4);

/**
 * Раскладка «ровными рядами», как в Google Photos и Immich: кадры идут в ряд,
 * пока он не заполнит ширину при целевой высоте, затем ряд масштабируется точно
 * по ширине. Последний неполный ряд не растягивается.
 */
export function justify<T extends Sized>(items: T[], width: number, targetHeight: number, gap: number): Row<T>[] {
    if (width <= 0) return [];

    const rows: Row<T>[] = [];
    let row: T[] = [];
    let ratios = 0;

    for (const item of items) {
        row.push(item);
        ratios += ratioOf(item);
        const naturalWidth = ratios * targetHeight + gap * (row.length - 1);
        if (naturalWidth >= width) {
            const height = (width - gap * (row.length - 1)) / ratios;
            rows.push({ items: row, height });
            row = [];
            ratios = 0;
        }
    }
    if (row.length) rows.push({ items: row, height: targetHeight });

    return rows;
}

export function tileWidth(item: Sized, rowHeight: number) {
    return ratioOf(item) * rowHeight;
}

/** Примерная высота ещё не загруженного месяца — чтобы полоса прокрутки не прыгала. */
export function estimateHeight(count: number, width: number, targetHeight: number, gap: number) {
    if (width <= 0) return count * 40;
    const perRow = Math.max(1, Math.round(width / (targetHeight * 0.95)));
    const rows = Math.ceil(count / perRow);
    const dayHeaders = Math.max(1, Math.round(count / 10));
    return rows * (targetHeight + gap) + dayHeaders * 52 + 72;
}
