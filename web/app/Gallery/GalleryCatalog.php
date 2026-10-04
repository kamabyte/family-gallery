<?php

namespace App\Gallery;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;

/**
 * Everything the web app reads about the library. The prototype binds
 * FakeGalleryCatalog; the real one will read the catalog the indexer publishes
 * (.gallery/manifest.json → catalogs/index-<rev>.db) and serve its derivatives.
 *
 * Every list is newest first, the timeline's order.
 */
interface GalleryCatalog
{
    /**
     * Months that have items, with their counts — enough to lay out the whole
     * timeline and its scrubber before loading a single month.
     *
     * @return list<array{month: string, count: int}>
     */
    public function buckets(): array;

    /** @return list<Photo> items of one month, "YYYY-MM" */
    public function month(string $month): array;

    public function photo(int $id): ?Photo;

    /**
     * Ids in the order a viewer opened from `$context` steps through: the timeline
     * (null), an album ("album:<id>"), one day ("day:YYYY-MM-DD") or a search
     * ("search:<query>").
     *
     * @return list<int>
     */
    public function sequence(?string $context): array;

    /** @return list<Album> */
    public function albums(): array;

    public function album(string $id): ?Album;

    /** @return list<Photo> */
    public function albumPhotos(string $id): array;

    /** @return list<Album> albums a photo belongs to */
    public function albumsOf(Photo $photo): array;

    /**
     * Same calendar day in earlier years, most recent first.
     *
     * @return list<array{years_ago: int, date: string, photos: list<Photo>}>
     */
    public function memories(): array;

    /** @return array{albums: list<Album>, photos: list<Photo>} */
    public function search(string $query): array;

    public function setFavorite(int $id, bool $favorite): void;

    /**
     * What the indexer last published, for the status line in the sidebar.
     *
     * @return array{revision: int, photos: int, videos: int, indexed_at: string, size_bytes: int}
     */
    public function status(): array;
}
