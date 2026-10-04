<?php

namespace App\Gallery\Catalog;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;
use App\Gallery\Favorites;
use App\Gallery\GalleryCatalog;
use Carbon\CarbonImmutable;
use PDO;

/**
 * The real library: the catalog the indexer (workers/indexer.py, schema v4) publishes
 * into <root>/.gallery. manifest.json names the current immutable snapshot
 * (catalogs/index-<rev>-<fp>.db); it is opened read-only, so a new revision is picked
 * up on the next request without any coordination.
 *
 * Album ids are "<type>-<catalog id>" (place-3, trip-7) plus the smart "favorites"
 * and "videos". Photo ids are the catalog's own — stable across incremental runs,
 * which is what favourites are keyed on.
 *
 * Text search runs in PHP: SQLite's lower()/LIKE only fold ASCII, and the library
 * is small (≤ 20 000 items).
 */
final class IndexerCatalog implements GalleryCatalog
{
    private const PHOTO_COLUMNS = 'p.id, p.relative_path, p.filename, p.media_type, p.capture_date, p.width, p.height,
        p.size_bytes, p.content_hash, p.thumb_path, p.preview_path, p.video_path, p.duration_ms,
        p.gps_lat, p.gps_lon, p.place_city, p.place_country, p.camera_make, p.camera_model';

    private ?PDO $db = null;

    /** @var array<string, mixed>|null */
    private ?array $manifest = null;

    /** @var list<Album>|null */
    private ?array $albums = null;

    private ?int $albumsFor = null;

    public function __construct(
        private readonly Favorites $favorites,
        private readonly string $root,
        private readonly string $timezone,
    ) {}

    public function buckets(): array
    {
        $counts = [];
        foreach ($this->column('SELECT capture_date FROM photos ORDER BY capture_date DESC, id DESC') as $ms) {
            $month = $this->time((int) $ms)->format('Y-m');
            $counts[$month] = ($counts[$month] ?? 0) + 1;
        }

        return array_map(
            fn (string $month, int $count) => ['month' => $month, 'count' => $count],
            array_keys($counts),
            $counts,
        );
    }

    public function month(string $month): array
    {
        $start = CarbonImmutable::createFromFormat('!Y-m', $month, $this->timezone);
        if ($start === false) {
            return [];
        }

        return $this->photos(
            'SELECT '.self::PHOTO_COLUMNS.' FROM photos p WHERE p.capture_date >= ? AND p.capture_date < ?
             ORDER BY p.capture_date DESC, p.id DESC',
            [$start->getTimestampMs(), $start->addMonth()->getTimestampMs()],
        );
    }

    public function photo(int $id): ?Photo
    {
        return $this->photos('SELECT '.self::PHOTO_COLUMNS.' FROM photos p WHERE p.id = ?', [$id])[0] ?? null;
    }

    public function sequence(?string $context): array
    {
        [$kind, $value] = array_pad(explode(':', (string) $context, 2), 2, '');

        return match ($kind) {
            'album' => array_map(fn (Photo $p) => $p->id, $this->albumPhotos($value)),
            'day' => array_map(fn (Photo $p) => $p->id, $this->day($value)),
            'search' => array_map(fn (Photo $p) => $p->id, $this->search($value)['photos']),
            default => array_map('intval', $this->column('SELECT id FROM photos ORDER BY capture_date DESC, id DESC')),
        };
    }

    public function albums(): array
    {
        $owner = $this->favorites->owner();
        if ($this->albums !== null && $this->albumsFor === $owner) {
            return $this->albums;
        }

        $rows = $this->rows(
            "SELECT a.id, a.name, a.type, a.album_key, a.photo_count, a.sort_order, a.cover_photo_id,
                    MIN(pa.capture_date) AS first_ms, MAX(pa.capture_date) AS last_ms
             FROM albums a LEFT JOIN photo_albums pa ON pa.album_id = a.id
             WHERE a.photo_count > 0
             GROUP BY a.id
             ORDER BY CASE a.type WHEN 'trip' THEN 0 WHEN 'place' THEN 1 WHEN 'camera' THEN 2 WHEN 'year' THEN 3 ELSE 4 END,
                      a.sort_order, a.id",
        );
        $covers = $this->photosById(array_filter(array_column($rows, 'cover_photo_id')));

        $favoriteIds = array_keys($this->favorites->ids());
        $favorites = $favoriteIds === [] ? [] : $this->photosWhereIn($favoriteIds);
        $videos = $this->photos('SELECT '.self::PHOTO_COLUMNS." FROM photos p WHERE p.media_type = 'video' ORDER BY p.capture_date DESC, p.id DESC");

        $albums = [
            $this->smartAlbum('favorites', 'Избранное', $favorites),
            $this->smartAlbum('videos', 'Видео', $videos),
        ];
        foreach ($rows as $row) {
            $albums[] = new Album(
                id: "{$row['type']}-{$row['id']}",
                type: $row['type'],
                name: $row['name'],
                subtitle: $row['type'] === 'place' ? $this->countryOf((string) $row['album_key']) : null,
                count: (int) $row['photo_count'],
                cover: $covers[(int) $row['cover_photo_id']] ?? null,
                from: $row['first_ms'] !== null ? $this->time((int) $row['first_ms']) : null,
                to: $row['last_ms'] !== null ? $this->time((int) $row['last_ms']) : null,
            );
        }

        $this->albumsFor = $owner;

        return $this->albums = $albums;
    }

    public function album(string $id): ?Album
    {
        foreach ($this->albums() as $album) {
            if ($album->id === $id) {
                return $album;
            }
        }

        return null;
    }

    public function albumPhotos(string $id): array
    {
        if ($id === 'favorites') {
            $ids = array_keys($this->favorites->ids());

            return $ids === [] ? [] : $this->photosWhereIn($ids);
        }
        if ($id === 'videos') {
            return $this->photos('SELECT '.self::PHOTO_COLUMNS." FROM photos p WHERE p.media_type = 'video' ORDER BY p.capture_date DESC, p.id DESC");
        }
        if (! preg_match('/^[a-z]+-(\d+)$/', $id, $m)) {
            return [];
        }

        return $this->photos(
            'SELECT '.self::PHOTO_COLUMNS.' FROM photo_albums pa JOIN photos p ON p.id = pa.photo_id
             WHERE pa.album_id = ? ORDER BY pa.capture_date DESC, pa.photo_id DESC',
            [(int) $m[1]],
        );
    }

    public function albumsOf(Photo $photo): array
    {
        $ids = array_map(
            fn (array $r) => "{$r['type']}-{$r['id']}",
            $this->rows('SELECT a.id, a.type FROM photo_albums pa JOIN albums a ON a.id = pa.album_id WHERE pa.photo_id = ?', [$photo->id]),
        );
        if ($photo->favorite) {
            $ids[] = 'favorites';
        }

        return array_values(array_filter($this->albums(), fn (Album $a) => in_array($a->id, $ids, true)));
    }

    public function memories(): array
    {
        $today = CarbonImmutable::now($this->timezone)->startOfDay();
        $memories = [];
        for ($yearsAgo = 1; $yearsAgo <= 30; $yearsAgo++) {
            $day = $today->subYears($yearsAgo);
            if ($day->month !== $today->month) {
                continue; // 29 февраля в невисокосный год
            }
            $photos = $this->day($day->toDateString());
            if ($photos !== []) {
                $memories[] = ['years_ago' => $yearsAgo, 'date' => $day->toDateString(), 'photos' => $photos];
            }
        }

        return $memories;
    }

    public function search(string $query): array
    {
        $words = preg_split('/\s+/u', mb_strtolower(trim($query)), -1, PREG_SPLIT_NO_EMPTY);
        if ($words === []) {
            return ['albums' => [], 'photos' => []];
        }

        $albums = array_values(array_filter(
            $this->albums(),
            fn (Album $a) => collect($words)->contains(fn (string $w) => str_contains(mb_strtolower($a->name.' '.$a->subtitle), $w)),
        ));

        // Both forms: «июль» and «июля» («12 июля»).
        $months = ['январь января', 'февраль февраля', 'март марта', 'апрель апреля', 'май мая', 'июнь июня',
            'июль июля', 'август августа', 'сентябрь сентября', 'октябрь октября', 'ноябрь ноября', 'декабрь декабря'];
        $favorites = $this->favorites->ids();

        $ids = [];
        foreach ($this->rows('SELECT id, filename, media_type, capture_date, place_city, place_country, camera_make, camera_model
                              FROM photos ORDER BY capture_date DESC, id DESC') as $r) {
            $time = $this->time((int) $r['capture_date']);
            $haystack = mb_strtolower(implode(' ', array_filter([
                $r['place_city'], $r['place_country'], $r['camera_make'], $r['camera_model'], $r['filename'],
                (string) $time->year, $months[$time->month - 1],
                $r['media_type'] === 'video' ? 'видео' : 'фото',
                isset($favorites[(int) $r['id']]) ? 'избранное' : null,
            ])));
            if (collect($words)->every(fn (string $w) => str_contains($haystack, $w))) {
                $ids[] = (int) $r['id'];
            }
        }

        return ['albums' => $albums, 'photos' => $ids === [] ? [] : $this->photosWhereIn($ids)];
    }

    public function setFavorite(int $id, bool $favorite): void
    {
        $this->favorites->set($id, $favorite);
        $this->albums = null;
    }

    public function status(): array
    {
        $manifest = $this->manifest();
        $row = $this->rows(
            "SELECT SUM(media_type = 'video') AS videos, COUNT(*) AS total, COALESCE(SUM(size_bytes), 0) AS size FROM photos",
        )[0] ?? ['videos' => 0, 'total' => 0, 'size' => 0];

        return [
            'revision' => (int) ($manifest['revision'] ?? 0),
            'photos' => (int) $row['total'] - (int) $row['videos'],
            'videos' => (int) $row['videos'],
            'indexed_at' => isset($manifest['generated_at'])
                ? CarbonImmutable::parse($manifest['generated_at'], $this->timezone)->toIso8601String()
                : CarbonImmutable::now($this->timezone)->toIso8601String(),
            'size_bytes' => (int) $row['size'],
        ];
    }

    /** Original file of a photo, relative to the library root — for downloads. */
    public function originalPath(int $id): ?string
    {
        $path = $this->column('SELECT relative_path FROM photos WHERE id = ?', [$id])[0] ?? null;

        return is_string($path) ? $path : null;
    }

    /** @return list<Photo> */
    private function day(string $date): array
    {
        $start = CarbonImmutable::createFromFormat('!Y-m-d', $date, $this->timezone);
        if ($start === false) {
            return [];
        }

        return $this->photos(
            'SELECT '.self::PHOTO_COLUMNS.' FROM photos p WHERE p.capture_date >= ? AND p.capture_date < ?
             ORDER BY p.capture_date DESC, p.id DESC',
            [$start->getTimestampMs(), $start->addDay()->getTimestampMs()],
        );
    }

    /**
     * @param  list<int>  $ids
     * @return list<Photo> newest first
     */
    private function photosWhereIn(array $ids): array
    {
        $photos = [];
        foreach (array_chunk(array_values(array_unique($ids)), 500) as $chunk) {
            $marks = implode(',', array_fill(0, count($chunk), '?'));
            array_push($photos, ...$this->photos('SELECT '.self::PHOTO_COLUMNS." FROM photos p WHERE p.id IN ({$marks})", $chunk));
        }
        usort($photos, fn (Photo $a, Photo $b) => [$b->takenAt, $b->id] <=> [$a->takenAt, $a->id]);

        return $photos;
    }

    /**
     * @param  array<int>  $ids
     * @return array<int, Photo>
     */
    private function photosById(array $ids): array
    {
        $byId = [];
        foreach ($ids === [] ? [] : $this->photosWhereIn(array_map('intval', $ids)) as $photo) {
            $byId[$photo->id] = $photo;
        }

        return $byId;
    }

    /** @param  list<Photo>  $photos */
    private function smartAlbum(string $id, string $name, array $photos): Album
    {
        $cover = collect($photos)->first(fn (Photo $p) => ! $p->isVideo) ?? ($photos[0] ?? null);

        return new Album(
            id: $id,
            type: 'smart',
            name: $name,
            subtitle: null,
            count: count($photos),
            cover: $cover,
            from: $photos !== [] ? end($photos)->takenAt : null,
            to: $photos[0]->takenAt ?? null,
        );
    }

    /** Place keys are "city\x1fcountry" (indexer _KEY_SEP) — the country is the subtitle. */
    private function countryOf(string $key): ?string
    {
        $country = explode("\x1f", $key, 2)[1] ?? '';

        return $country !== '' ? $country : null;
    }

    private function time(int $ms): CarbonImmutable
    {
        return CarbonImmutable::createFromTimestampMs($ms, $this->timezone);
    }

    /**
     * @param  list<mixed>  $params
     * @return list<Photo>
     */
    private function photos(string $sql, array $params = []): array
    {
        $favorites = $this->favorites->ids();

        return array_map(fn (array $r) => $this->toPhoto($r, isset($favorites[(int) $r['id']])), $this->rows($sql, $params));
    }

    /** @param  array<string, mixed>  $r */
    private function toPhoto(array $r, bool $favorite): Photo
    {
        // No dominant colour in the catalog: a muted hue from the content hash keeps
        // placeholders varied but calm.
        $hue = $r['content_hash'] ? hexdec(substr((string) $r['content_hash'], 0, 4)) % 360 : ((int) $r['id'] * 47) % 360;

        return new Photo(
            id: (int) $r['id'],
            filename: (string) $r['filename'],
            isVideo: $r['media_type'] === 'video',
            takenAt: $this->time((int) $r['capture_date']),
            width: max(1, (int) $r['width']),
            height: max(1, (int) $r['height']),
            durationSeconds: $r['duration_ms'] !== null ? (int) round($r['duration_ms'] / 1000) : null,
            city: $r['place_city'],
            country: $r['place_country'],
            lat: $r['gps_lat'] !== null ? (float) $r['gps_lat'] : null,
            lon: $r['gps_lon'] !== null ? (float) $r['gps_lon'] : null,
            cameraMake: $r['camera_make'],
            cameraModel: $r['camera_model'],
            favorite: $favorite,
            color: "hsl({$hue} 18% 26%)",
            thumbUrl: '/media/'.$r['thumb_path'],
            previewUrl: '/media/'.$r['preview_path'],
            videoUrl: $r['video_path'] ? '/media/'.$r['video_path'] : null,
        );
    }

    /**
     * @param  list<mixed>  $params
     * @return list<array<string, mixed>>
     */
    private function rows(string $sql, array $params = []): array
    {
        $db = $this->db();
        if ($db === null) {
            return [];
        }
        $statement = $db->prepare($sql);
        $statement->execute($params);

        return $statement->fetchAll(PDO::FETCH_ASSOC);
    }

    /**
     * @param  list<mixed>  $params
     * @return list<mixed>
     */
    private function column(string $sql, array $params = []): array
    {
        return array_map(fn (array $r) => reset($r), $this->rows($sql, $params));
    }

    /** @return array<string, mixed> */
    private function manifest(): array
    {
        if ($this->manifest === null) {
            $file = $this->root.'/.gallery/manifest.json';
            $this->manifest = is_file($file) ? (json_decode((string) file_get_contents($file), true) ?: []) : [];
        }

        return $this->manifest;
    }

    /** Null until the indexer has published its first catalog: the library is empty. */
    private function db(): ?PDO
    {
        if ($this->db !== null) {
            return $this->db;
        }
        $catalog = $this->manifest()['catalog'] ?? null;
        if (! is_string($catalog) || str_contains($catalog, '..')) {
            return null;
        }
        $path = $this->root.'/.gallery/'.$catalog;
        if (! is_file($path)) {
            return null;
        }

        return $this->db = new PDO('sqlite:'.$path, options: [
            PDO::SQLITE_ATTR_OPEN_FLAGS => PDO::SQLITE_OPEN_READONLY,
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        ]);
    }
}
