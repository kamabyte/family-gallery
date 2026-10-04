import { router } from '@inertiajs/react';
import { Download, Heart, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { useSelection } from '@/hooks/use-selection';
import { items } from '@/lib/format';

const MAX_DOWNLOADS = 20;

/** Шапка в режиме выбора: сколько выбрано и что с этим сделать. */
export function SelectionBar() {
    const { selected, clear } = useSelection();
    const ids = [...selected];

    // Оригиналы по одному: браузер один раз спросит разрешения на несколько загрузок.
    function download() {
        if (ids.length > MAX_DOWNLOADS) {
            toast.error(`За раз — не больше ${MAX_DOWNLOADS}`, { description: 'Выберите меньше кадров или скачайте альбом частями.' });
            return;
        }
        ids.forEach((id, index) =>
            window.setTimeout(() => {
                const link = document.createElement('a');
                link.href = `/photos/${id}/download`;
                link.download = '';
                link.click();
            }, index * 400),
        );
        clear();
    }

    function favorite() {
        router.put('/photos/favorite', { ids, favorite: true }, { preserveScroll: true, onSuccess: clear });
    }

    return (
        <div className="flex h-14 items-center gap-2 px-3 md:h-16 md:px-5 lg:px-7">
            <Button variant="ghost" size="icon" className="rounded-full" onClick={clear} aria-label="Отменить выбор">
                <X className="size-5" />
            </Button>
            <span className="font-display text-lg font-semibold tabular-nums">Выбрано: {items(selected.size)}</span>
            <div className="ml-auto flex items-center gap-1">
                <Button variant="ghost" className="rounded-full" onClick={favorite}>
                    <Heart className="size-[18px]" />
                    <span className="hidden sm:inline">В избранное</span>
                </Button>
                <Button variant="ghost" className="rounded-full" onClick={download}>
                    <Download className="size-[18px]" />
                    <span className="hidden sm:inline">Скачать</span>
                </Button>
            </div>
        </div>
    );
}
