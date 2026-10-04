import type { router as Router } from '@inertiajs/react';

let visits = 0;

/** Считает переходы внутри приложения — чтобы «закрыть» просмотрщик шагом назад. */
export function trackVisits(router: typeof Router) {
    router.on('navigate', () => {
        visits += 1;
    });
}

/**
 * Пришли ли на текущую страницу из приложения. Тогда «назад» вернёт ленту
 * ровно туда, где её оставили, вместе с прокруткой.
 */
export function cameFromApp() {
    return visits > 1;
}
