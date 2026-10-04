import '../css/app.css';

import { createInertiaApp, router } from '@inertiajs/react';
import { FlashToaster } from '@/components/flash-toaster';
import { TooltipProvider } from '@/components/ui/tooltip';
import { initializeTheme } from '@/hooks/use-appearance';
import AppLayout from '@/layouts/app-layout';
import AuthLayout from '@/layouts/auth-layout';
import SettingsLayout from '@/layouts/settings-layout';
import { trackVisits } from '@/lib/history';

void createInertiaApp({
    title: (title) => (title ? `${title} · Фотографии` : 'Фотографии'),
    layout: (name) => {
        switch (true) {
            // Просмотрщик — на весь экран, без боковой панели и шапки.
            case name === 'viewer':
                return null;
            case name.startsWith('auth/'):
                return AuthLayout;
            case name.startsWith('settings/'):
                return [AppLayout, SettingsLayout];
            default:
                return AppLayout;
        }
    },
    strictMode: true,
    withApp(app) {
        return (
            <TooltipProvider delayDuration={300}>
                {app}
                <FlashToaster />
            </TooltipProvider>
        );
    },
    progress: {
        color: '#f5a442',
        delay: 150,
    },
});

initializeTheme();
trackVisits(router);
