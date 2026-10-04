import { Copy, FolderInput, ImagePlus } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

const SHARE = 'smb://10.20.1.100/Photos/Imports';

/** Фото добавляются не загрузкой из браузера, а через папку Imports — индексатор разложит их сам. */
export function AddPhotos() {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="rounded-full" aria-label="Как добавить фото">
                    <ImagePlus className="size-[18px]" />
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-80 p-3">
                <DropdownMenuLabel className="px-0 pt-0 font-display text-base">Как добавить фото</DropdownMenuLabel>
                <p className="text-sm text-muted-foreground">
                    Скопируйте снимки в папку <b className="text-foreground">Imports</b> на шаре Photos. Через несколько минут индексатор разложит их по
                    годам и месяцам, и они появятся в ленте.
                </p>
                <DropdownMenuSeparator className="my-3" />
                <div className="flex items-center gap-2 rounded-lg bg-muted px-3 py-2">
                    <FolderInput className="size-4 shrink-0 text-muted-foreground" />
                    <code className="min-w-0 flex-1 truncate text-xs">{SHARE}</code>
                    <Button
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label="Скопировать адрес"
                        onClick={() => navigator.clipboard?.writeText(SHARE).then(() => toast.success('Адрес скопирован'))}
                    >
                        <Copy className="size-3.5" />
                    </Button>
                </div>
                <p className="mt-2 text-xs text-muted-foreground">С iPhone: «Экспорт неизменённого оригинала» — сохраняет место и камеру.</p>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
