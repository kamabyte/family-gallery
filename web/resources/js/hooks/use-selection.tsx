import { router, usePage } from '@inertiajs/react';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';

interface Selection {
    /** Гостю выбирать незачем: ни избранного, ни скачивания. */
    enabled: boolean;
    selected: ReadonlySet<number>;
    active: boolean;
    toggle: (id: number) => void;
    setMany: (ids: number[], on: boolean) => void;
    clear: () => void;
}

const SelectionContext = createContext<Selection | null>(null);

/** Выбор нескольких кадров — общий для сетки и панели действий в шапке. */
export function SelectionProvider({ children }: { children: ReactNode }) {
    const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
    const enabled = usePage().props.auth.can.family;

    const toggle = useCallback((id: number) => {
        setSelected((current) => {
            const next = new Set(current);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    }, []);

    const setMany = useCallback((ids: number[], on: boolean) => {
        setSelected((current) => {
            const next = new Set(current);
            for (const id of ids) {
                if (on) next.add(id);
                else next.delete(id);
            }
            return next;
        });
    }, []);

    const clear = useCallback(() => setSelected(new Set()), []);

    // Выбор относится к странице: ушли на другую — сбрасываем.
    useEffect(
        () =>
            router.on('navigate', (event) => {
                if (event.detail.page.url.split('?')[0] !== window.location.pathname) clear();
            }),
        [clear],
    );

    const value = useMemo(
        () => ({ enabled, selected, active: selected.size > 0, toggle, setMany, clear }),
        [enabled, selected, toggle, setMany, clear],
    );

    return <SelectionContext.Provider value={value}>{children}</SelectionContext.Provider>;
}

export function useSelection() {
    const context = useContext(SelectionContext);
    if (!context) throw new Error('useSelection вне SelectionProvider');
    return context;
}
