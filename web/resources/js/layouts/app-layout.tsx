import { Link, usePage } from '@inertiajs/react';
import { Heart, Images, LibraryBig, type LucideIcon, MapPin, Search } from 'lucide-react';
import type { ReactNode } from 'react';
import { AddPhotos } from '@/components/add-photos';
import { AppearanceMenu } from '@/components/appearance-menu';
import { LibraryStatus } from '@/components/library-status';
import { Logo, LogoMark } from '@/components/logo';
import { SearchBox } from '@/components/search-box';
import { SelectionBar } from '@/components/selection-bar';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { SelectionProvider, useSelection } from '@/hooks/use-selection';
import { cn } from '@/lib/utils';
import type { SharedProps } from '@/types';

interface NavItem {
    href: string;
    label: string;
    icon: LucideIcon;
    match: RegExp;
    /** Только в нижней панели телефона: на компьютере поиск — в шапке. */
    mobileOnly?: boolean;
}

const NAV: NavItem[] = [
    { href: '/', label: 'Фото', icon: Images, match: /^\/(\?.*)?$/ },
    { href: '/albums', label: 'Альбомы', icon: LibraryBig, match: /^\/albums(?!\/favorites)/ },
    { href: '/albums/favorites', label: 'Избранное', icon: Heart, match: /^\/albums\/favorites/ },
    { href: '/search', label: 'Поиск', icon: Search, match: /^\/search/, mobileOnly: true },
];

function Sidebar() {
    const { url, props } = usePage<SharedProps>();
    const places = props.sidebarPlaces ?? [];

    return (
        <aside className="fixed inset-y-0 left-0 z-30 hidden w-[76px] flex-col border-r border-border/60 bg-background md:flex lg:w-64">
            <div className="flex h-16 shrink-0 items-center justify-center px-5 lg:justify-start">
                <Link href="/" className="rounded-lg focus-visible:ring-2 focus-visible:ring-ring" aria-label="Фотографии — лента">
                    <LogoMark className="lg:hidden" />
                    <Logo className="hidden lg:inline-flex" />
                </Link>
            </div>

            <nav className="flex flex-col gap-0.5 px-3" aria-label="Разделы">
                {NAV.filter((item) => !item.mobileOnly).map((item) => {
                    const active = item.match.test(url);
                    const Icon = item.icon;

                    return (
                        <Tooltip key={item.href}>
                            <TooltipTrigger asChild>
                                <Link
                                    href={item.href}
                                    prefetch
                                    aria-current={active ? 'page' : undefined}
                                    className={cn(
                                        'group flex h-11 items-center justify-center gap-3.5 rounded-xl px-3 text-[15px] font-medium text-muted-foreground transition-colors hover:bg-accent/70 hover:text-foreground lg:justify-start',
                                        active && 'bg-accent text-foreground',
                                    )}
                                >
                                    <Icon className={cn('size-5 shrink-0', active && 'text-brand')} strokeWidth={active ? 2.25 : 1.9} />
                                    <span className="hidden lg:inline">{item.label}</span>
                                </Link>
                            </TooltipTrigger>
                            <TooltipContent side="right" className="lg:hidden">
                                {item.label}
                            </TooltipContent>
                        </Tooltip>
                    );
                })}
            </nav>

            {places.length > 0 && (
                <div className="mt-6 hidden min-h-0 flex-1 flex-col lg:flex">
                    <div className="px-6 pb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Места</div>
                    <div className="scrollbar-none min-h-0 flex-1 overflow-y-auto px-3 pb-4">
                        {places.map((place) => {
                            const href = `/albums/${place.id}`;
                            const active = url.split('?')[0] === href;

                            return (
                                <Link
                                    key={place.id}
                                    href={href}
                                    className={cn(
                                        'flex h-10 items-center gap-3 rounded-xl px-3 text-sm text-muted-foreground transition-colors hover:bg-accent/70 hover:text-foreground',
                                        active && 'bg-accent text-foreground',
                                    )}
                                >
                                    <MapPin className="size-4 shrink-0" />
                                    <span className="truncate">{place.name}</span>
                                    <span className="ml-auto text-xs tabular-nums opacity-70">{place.count.toLocaleString('ru-RU')}</span>
                                </Link>
                            );
                        })}
                    </div>
                </div>
            )}

            <div className="mt-auto hidden lg:block">
                <LibraryStatus />
            </div>
        </aside>
    );
}

function Header() {
    const { active } = useSelection();

    return (
        <header className="sticky top-0 z-20 border-b border-border/60 bg-chrome backdrop-blur-xl backdrop-saturate-150">
            {active ? (
                <SelectionBar />
            ) : (
                <div className="flex h-14 items-center gap-3 px-4 md:h-16 md:px-6 lg:px-8">
                    <Link href="/" className="md:hidden" aria-label="Фотографии — лента">
                        <Logo />
                    </Link>
                    <SearchBox className="hidden max-w-xl md:block" />
                    <div className="ml-auto flex items-center gap-1">
                        <AddPhotos />
                        <AppearanceMenu />
                    </div>
                </div>
            )}
        </header>
    );
}

/** Нижняя панель вкладок на телефоне — как в iOS. */
function TabBar() {
    const { url } = usePage();

    return (
        <nav
            className="pb-safe fixed inset-x-0 bottom-0 z-30 border-t border-border/60 bg-chrome backdrop-blur-xl backdrop-saturate-150 md:hidden"
            aria-label="Разделы"
        >
            <div className="grid h-14 grid-cols-4">
                {NAV.map((item) => {
                    const active = item.match.test(url);
                    const Icon = item.icon;

                    return (
                        <Link
                            key={item.href}
                            href={item.href}
                            aria-current={active ? 'page' : undefined}
                            className={cn(
                                'flex flex-col items-center justify-center gap-0.5 text-[10.5px] font-medium text-muted-foreground',
                                active && 'text-brand',
                            )}
                        >
                            <Icon className="size-[22px]" strokeWidth={active ? 2.2 : 1.8} />
                            {item.label}
                        </Link>
                    );
                })}
            </div>
        </nav>
    );
}

export default function AppLayout({ children }: { children: ReactNode }) {
    return (
        <SelectionProvider>
            <div className="min-h-dvh">
                <Sidebar />
                <div className="md:pl-[76px] lg:pl-64">
                    <Header />
                    <main className="px-4 pt-4 pb-28 md:px-6 md:pt-6 md:pb-12 lg:px-8">{children}</main>
                </div>
                <TabBar />
            </div>
        </SelectionProvider>
    );
}
