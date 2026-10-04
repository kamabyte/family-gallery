import inertia from '@inertiajs/vite';
import { wayfinder } from '@laravel/vite-plugin-wayfinder';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import laravel from 'laravel-vite-plugin';
import { defineConfig } from 'vite';

export default defineConfig({
    plugins: [
        laravel({
            input: ['resources/css/app.css', 'resources/js/app.tsx'],
            refresh: true,
        }),
        inertia(),
        react(),
        tailwindcss(),
        // Маршруты Laravel как функции TypeScript (resources/js/{routes,actions}) — как в awgkeys.
        // Плагин зовёт `php artisan wayfinder:generate`, поэтому сборка образа идёт в PHP-образе.
        wayfinder({
            formVariants: true,
        }),
    ],
    server: {
        watch: {
            ignored: ['**/.claude/**', '**/vendor/**', '**/storage/**', 'resources/js/wayfinder/**'],
        },
    },
});
