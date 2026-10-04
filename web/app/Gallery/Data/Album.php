<?php

namespace App\Gallery\Data;

use Carbon\CarbonImmutable;

/**
 * An album. Like the indexer's `albums` table, albums are built from metadata —
 * place, camera, year — never by hand; `favorites` and `videos` are smart albums
 * the app derives on the fly.
 */
final readonly class Album
{
    public const TYPES = ['place', 'camera', 'year', 'smart'];

    public function __construct(
        public string $id,
        public string $type,
        public string $name,
        public ?string $subtitle,
        public int $count,
        public ?Photo $cover,
        public ?CarbonImmutable $from,
        public ?CarbonImmutable $to,
    ) {}

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'type' => $this->type,
            'name' => $this->name,
            'subtitle' => $this->subtitle,
            'count' => $this->count,
            'cover' => $this->cover?->toArray(),
            'from' => $this->from?->toDateString(),
            'to' => $this->to?->toDateString(),
        ];
    }
}
