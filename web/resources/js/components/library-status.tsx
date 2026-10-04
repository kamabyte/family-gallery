import { usePage } from '@inertiajs/react';
import { bytes, items, videos } from '@/lib/format';
import type { SharedProps } from '@/types';

/** Что индексатор опубликовал последним — внизу боковой панели. */
export function LibraryStatus() {
    const { library } = usePage<SharedProps>().props;
    const indexed = new Date(library.indexed_at);
    const today = indexed.toDateString() === new Date().toDateString();
    const when = today
        ? `сегодня в ${indexed.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`
        : indexed.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });

    return (
        <div className="mx-3 mb-4 rounded-2xl bg-muted/60 p-3.5 text-xs text-muted-foreground">
            <div className="mb-1 flex items-center gap-2 font-medium text-foreground">
                <span className="size-2 rounded-full bg-emerald-500" />
                Библиотека
            </div>
            <div>
                {items(library.photos)} · {videos(library.videos)}
            </div>
            <div>
                {bytes(library.size_bytes)} · обновлена {when}
            </div>
            {library.fake && <div className="mt-1 text-[11px] opacity-70">Прототип · тестовые данные</div>}
        </div>
    );
}
