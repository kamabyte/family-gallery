import { type RefObject, useLayoutEffect, useState } from 'react';

/** Ширина элемента, обновляется при изменении размера. */
export function useWidth(ref: RefObject<HTMLElement | null>) {
    const [width, setWidth] = useState(0);

    useLayoutEffect(() => {
        const element = ref.current;
        if (!element) return;
        setWidth(element.clientWidth);
        const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
        observer.observe(element);
        return () => observer.disconnect();
    }, [ref]);

    return width;
}

/** Целевая высота ряда: на телефоне ряды ниже, чтобы влезало 3–4 кадра. */
export function rowHeightFor(width: number) {
    if (width < 500) return 118;
    if (width < 900) return 170;
    return 230;
}

export const GAP = 4;
