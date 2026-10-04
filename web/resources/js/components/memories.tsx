import { Link } from '@inertiajs/react';
import { viewerHref } from '@/components/photo-tile';
import { items, yearsAgo } from '@/lib/format';
import type { Memory } from '@/types';

/** «В этот день» — тот же день в прошлые годы, как в «Воспоминаниях» iOS и Immich. */
export function Memories({ memories }: { memories: Memory[] }) {
    if (memories.length === 0) return null;

    return (
        <section aria-label="В этот день" className="mb-8">
            <h2 className="mb-3 font-display text-xl font-semibold">В этот день</h2>
            <div className="scrollbar-none -mx-4 flex snap-x gap-3 overflow-x-auto px-4 md:-mx-6 md:px-6 lg:-mx-8 lg:px-8">
                {memories.map((memory) => (
                    <Link
                        key={memory.date}
                        href={viewerHref(memory.cover.id, `day:${memory.date}`)}
                        className="group relative aspect-[3/4] w-36 shrink-0 snap-start overflow-hidden rounded-2xl sm:w-44"
                        style={{ backgroundColor: memory.cover.color }}
                    >
                        <img
                            src={memory.cover.thumb}
                            alt=""
                            loading="lazy"
                            className="size-full object-cover transition-transform duration-500 group-hover:scale-105"
                        />
                        <span className="absolute inset-0 bg-gradient-to-t from-black/70 via-black/10 to-transparent" />
                        <span className="absolute inset-x-3 bottom-3 text-white">
                            <span className="block font-display text-[17px] leading-tight font-semibold">{yearsAgo(memory.years_ago)}</span>
                            <span className="block text-xs text-white/75">
                                {memory.cover.place?.city ?? memory.date.slice(0, 4)} · {items(memory.count)}
                            </span>
                        </span>
                    </Link>
                ))}
            </div>
        </section>
    );
}
