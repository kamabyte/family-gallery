import { cn } from '@/lib/utils';

export function LogoMark({ className }: { className?: string }) {
    return (
        <span
            className={cn(
                'inline-flex size-8 items-center justify-center rounded-[10px] bg-gradient-to-br from-[#ffc062] to-[#f0644a] shadow-sm shadow-orange-900/30',
                className,
            )}
            aria-hidden
        >
            <svg viewBox="0 0 24 24" className="size-[18px] fill-white">
                <circle cx="16.5" cy="7.5" r="2.25" />
                <path d="M2.5 19.5 9 10.5l4.5 6 2.6-3 5.4 6z" />
            </svg>
        </span>
    );
}

export function Logo({ className }: { className?: string }) {
    return (
        <span className={cn('inline-flex items-center gap-2.5', className)}>
            <LogoMark />
            <span className="font-display text-[19px] font-semibold tracking-tight">Фотографии</span>
        </span>
    );
}
