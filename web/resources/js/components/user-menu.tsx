import { Link, router, usePage } from '@inertiajs/react';
import { LogOut, Settings, Users } from 'lucide-react';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { logout } from '@/routes';
import { edit as profile } from '@/routes/profile';
import { index as users } from '@/routes/users';

function initials(name: string) {
    return name
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((part) => part[0]?.toUpperCase())
        .join('');
}

export function UserMenu() {
    const { auth } = usePage().props;

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={`Учётная запись: ${auth.user.name}`}
                    className="ml-1 flex size-9 items-center justify-center rounded-full bg-gradient-to-br from-[#ffc062] to-[#f0644a] text-[13px] font-semibold text-white ring-2 ring-transparent transition hover:ring-ring/40 focus-visible:ring-ring"
                >
                    {initials(auth.user.name)}
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-60">
                <DropdownMenuLabel className="font-normal">
                    <div className="truncate font-semibold">{auth.user.name}</div>
                    <div className="truncate text-xs text-muted-foreground">{auth.user.email}</div>
                    <div className="mt-1 text-xs text-brand">{auth.user.role_label}</div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                    <Link href={profile()}>
                        <Settings /> Настройки
                    </Link>
                </DropdownMenuItem>
                {auth.can.admin && (
                    <DropdownMenuItem asChild>
                        <Link href={users()}>
                            <Users /> Пользователи
                        </Link>
                    </DropdownMenuItem>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => router.post(logout().url, {}, { onSuccess: () => router.flushAll() })}>
                    <LogOut /> Выйти
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
