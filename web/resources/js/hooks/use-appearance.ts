import { useCallback, useEffect, useState } from 'react';

export type Appearance = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'gallery:appearance';
const media = () => window.matchMedia('(prefers-color-scheme: dark)');

function apply(appearance: Appearance) {
    const dark = appearance === 'dark' || (appearance === 'system' && media().matches);
    document.documentElement.classList.toggle('dark', dark);
}

function stored(): Appearance {
    try {
        const value = localStorage.getItem(STORAGE_KEY);
        return value === 'light' || value === 'dark' ? value : 'system';
    } catch {
        return 'system';
    }
}

/** Следит за системной темой, пока выбрана «как в системе». */
export function initializeTheme() {
    apply(stored());
    media().addEventListener('change', () => stored() === 'system' && apply('system'));
}

export function useAppearance() {
    const [appearance, setAppearance] = useState<Appearance>('system');

    useEffect(() => setAppearance(stored()), []);

    const update = useCallback((value: Appearance) => {
        setAppearance(value);
        try {
            localStorage.setItem(STORAGE_KEY, value);
        } catch {
            // не сохранится между визитами — не страшно
        }
        apply(value);
    }, []);

    return { appearance, updateAppearance: update } as const;
}
