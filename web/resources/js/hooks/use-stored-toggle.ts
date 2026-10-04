import { useCallback, useState } from 'react';

/** Булев переключатель, запоминаемый в localStorage. */
export function useStoredToggle(key: string, initial: boolean) {
    const [value, setValue] = useState(() => {
        try {
            const stored = localStorage.getItem(key);
            return stored === null ? initial : stored === '1';
        } catch {
            return initial;
        }
    });

    const update = useCallback(
        (next: boolean) => {
            setValue(next);
            try {
                localStorage.setItem(key, next ? '1' : '0');
            } catch {
                // только на эту сессию
            }
        },
        [key],
    );

    return [value, update] as const;
}
