<?php

namespace App\Gallery\Fake;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use Carbon\CarbonImmutable;
use Illuminate\Contracts\Session\Session;
use Illuminate\Support\Str;
use Random\Engine\Mt19937;
use Random\Randomizer;

/**
 * A believable family library for the prototype: trips and everyday events from
 * 2019 to today, several phones and a camera, the same calendar day in earlier
 * years for "memories". Deterministic — the same seed and day give the same
 * library — so links and screenshots stay stable.
 *
 * Images come from picsum.photos at each item's real aspect ratio; videos share
 * one CC0 clip. Favourites toggled in the UI live in the session.
 */
final class FakeGalleryCatalog implements GalleryCatalog
{
    private const HOME = ['Москва', 'Россия', 55.7558, 37.6173];

    private const TRIPS = [
        ['Санкт-Петербург', 'Россия', 59.9343, 30.3351],
        ['Сочи', 'Россия', 43.5855, 39.7231],
        ['Казань', 'Россия', 55.7963, 49.1088],
        ['Калининград', 'Россия', 54.7104, 20.4522],
        ['Горно-Алтайск', 'Россия', 51.9581, 85.9603],
        ['Стамбул', 'Турция', 41.0082, 28.9784],
        ['Анталья', 'Турция', 36.8969, 30.7133],
        ['Тбилиси', 'Грузия', 41.7151, 44.8271],
        ['Ереван', 'Армения', 40.1792, 44.4991],
        ['Дубай', 'ОАЭ', 25.2048, 55.2708],
    ];

    /** [make, model, first year in use, last year in use] */
    private const CAMERAS = [
        ['Apple', 'iPhone 11', 2019, 2022],
        ['Samsung', 'Galaxy S21', 2019, 2023],
        ['Apple', 'iPhone 13', 2022, 2026],
        ['Sony', 'ILCE-7M3', 2020, 2026],
        ['Apple', 'iPhone 15 Pro', 2024, 2026],
    ];

    /** Common sensor shapes; portrait phone shots dominate. */
    private const SHAPES = [
        [3024, 4032], [3024, 4032], [3024, 4032],
        [4032, 3024], [4032, 3024],
        [6000, 4000], [1920, 1080], [1080, 1920], [3024, 3024],
    ];

    private const SAMPLE_VIDEO = 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4';

    /** @var array<int, Photo>|null newest first, keyed by id */
    private ?array $photos = null;

    /** @var list<Album>|null */
    private ?array $albums = null;

    public function __construct(
        private readonly Session $session,
        private readonly int $seed,
        private readonly CarbonImmutable $today,
    ) {}

    public function buckets(): array
    {
        $counts = [];
        foreach ($this->all() as $photo) {
            $counts[$photo->month()] = ($counts[$photo->month()] ?? 0) + 1;
        }

        return array_map(
            fn (string $month, int $count) => ['month' => $month, 'count' => $count],
            array_keys($counts),
            $counts,
        );
    }

    public function month(string $month): array
    {
        return array_values(array_filter($this->all(), fn (Photo $p) => $p->month() === $month));
    }

    public function photo(int $id): ?Photo
    {
        return $this->all()[$id] ?? null;
    }

    public function sequence(?string $context): array
    {
        [$kind, $value] = array_pad(explode(':', (string) $context, 2), 2, '');

        $photos = match ($kind) {
            'album' => $this->albumPhotos($value),
            'day' => array_filter($this->all(), fn (Photo $p) => $p->date() === $value),
            'search' => $this->search($value)['photos'],
            default => $this->all(),
        };

        return array_values(array_map(fn (Photo $p) => $p->id, $photos));
    }

    public function albums(): array
    {
        return $this->albums ??= $this->buildAlbums();
    }

    /** @return list<Album> */
    private function buildAlbums(): array
    {
        $groups = [];
        foreach ($this->all() as $photo) {
            if ($photo->city !== null) {
                $groups['place-'.Str::slug($photo->city)][] = $photo;
            }
            if ($photo->cameraModel !== null) {
                $groups['camera-'.Str::slug($photo->camera())][] = $photo;
            }
            $groups['year-'.$photo->takenAt->year][] = $photo;
        }

        $albums = [
            $this->makeAlbum('favorites', 'smart', 'Избранное', array_filter($this->all(), fn (Photo $p) => $p->favorite)),
            $this->makeAlbum('videos', 'smart', 'Видео', array_filter($this->all(), fn (Photo $p) => $p->isVideo)),
        ];
        foreach ($groups as $id => $photos) {
            $first = $photos[0];
            [$type, $name, $subtitle] = match (true) {
                str_starts_with($id, 'place-') => ['place', $first->city, $first->country],
                str_starts_with($id, 'camera-') => ['camera', $first->camera(), null],
                default => ['year', (string) $first->takenAt->year, null],
            };
            $albums[] = $this->makeAlbum($id, $type, $name, $photos, $subtitle);
        }

        // Places and cameras by size, years newest first — the order the app shows them in.
        usort($albums, function (Album $a, Album $b) {
            $order = array_flip(['smart', 'place', 'camera', 'year']);

            return [$order[$a->type], $a->type === 'year' ? -(int) $a->name : -$a->count]
                <=> [$order[$b->type], $b->type === 'year' ? -(int) $b->name : -$b->count];
        });

        return $albums;
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
        return array_values(array_filter($this->all(), fn (Photo $p) => match (true) {
            $id === 'favorites' => $p->favorite,
            $id === 'videos' => $p->isVideo,
            str_starts_with($id, 'place-') => $p->city !== null && 'place-'.Str::slug($p->city) === $id,
            str_starts_with($id, 'camera-') => $p->cameraModel !== null && 'camera-'.Str::slug($p->camera()) === $id,
            str_starts_with($id, 'year-') => 'year-'.$p->takenAt->year === $id,
            default => false,
        }));
    }

    public function albumsOf(Photo $photo): array
    {
        $ids = array_filter([
            $photo->city !== null ? 'place-'.Str::slug($photo->city) : null,
            $photo->cameraModel !== null ? 'camera-'.Str::slug($photo->camera()) : null,
            'year-'.$photo->takenAt->year,
            $photo->favorite ? 'favorites' : null,
        ]);

        return array_values(array_filter($this->albums(), fn (Album $a) => in_array($a->id, $ids, true)));
    }

    public function memories(): array
    {
        $memories = [];
        foreach ([1, 2, 3, 4, 5, 6, 7] as $yearsAgo) {
            $day = $this->today->subYears($yearsAgo)->toDateString();
            $photos = array_values(array_filter($this->all(), fn (Photo $p) => $p->date() === $day));
            if ($photos !== []) {
                $memories[] = ['years_ago' => $yearsAgo, 'date' => $day, 'photos' => $photos];
            }
        }

        return $memories;
    }

    public function search(string $query): array
    {
        $needle = mb_strtolower(trim($query));
        if ($needle === '') {
            return ['albums' => [], 'photos' => []];
        }

        // An album matches any word of the query: "сочи 2024" still offers the Sochi album.
        $words = preg_split('/\s+/u', $needle);
        $albums = array_values(array_filter(
            $this->albums(),
            fn (Album $a) => collect($words)->contains(fn (string $word) => str_contains(mb_strtolower($a->name.' '.$a->subtitle), $word)),
        ));

        // Both forms: «июль» and «июля» («12 июля»).
        $months = ['январь января', 'февраль февраля', 'март марта', 'апрель апреля', 'май мая', 'июнь июня',
            'июль июля', 'август августа', 'сентябрь сентября', 'октябрь октября', 'ноябрь ноября', 'декабрь декабря'];
        $photos = array_values(array_filter($this->all(), function (Photo $p) use ($words, $months) {
            $haystack = mb_strtolower(implode(' ', array_filter([
                $p->city, $p->country, $p->camera(), $p->filename, (string) $p->takenAt->year,
                $months[$p->takenAt->month - 1], $p->isVideo ? 'видео' : 'фото', $p->favorite ? 'избранное' : null,
            ])));

            return collect($words)->every(fn (string $word) => str_contains($haystack, $word));
        }));

        return ['albums' => $albums, 'photos' => $photos];
    }

    public function setFavorite(int $id, bool $favorite): void
    {
        $overrides = $this->session->get('gallery.favorites', []);
        $overrides[$id] = $favorite;
        $this->session->put('gallery.favorites', $overrides);

        if ($this->photos !== null && isset($this->photos[$id])) {
            $this->photos[$id] = $this->photos[$id]->withFavorite($favorite);
            $this->albums = null;
        }
    }

    public function status(): array
    {
        $all = $this->all();
        $videos = count(array_filter($all, fn (Photo $p) => $p->isVideo));

        return [
            'revision' => 412,
            'photos' => count($all) - $videos,
            'videos' => $videos,
            'indexed_at' => $this->today->setTime(9, 30)->toIso8601String(),
            'size_bytes' => (count($all) - $videos) * 3_400_000 + $videos * 48_000_000,
        ];
    }

    /** @return array<int, Photo> */
    private function all(): array
    {
        return $this->photos ??= $this->applyFavorites($this->generate());
    }

    /**
     * @param  array<int, Photo>  $photos
     * @return array<int, Photo>
     */
    private function applyFavorites(array $photos): array
    {
        foreach ($this->session->get('gallery.favorites', []) as $id => $favorite) {
            if (isset($photos[$id])) {
                $photos[$id] = $photos[$id]->withFavorite((bool) $favorite);
            }
        }

        return $photos;
    }

    /** @return array<int, Photo> */
    private function generate(): array
    {
        $random = new Randomizer(new Mt19937($this->seed));
        $events = [];

        // Everyday life at home: a few small events a month.
        $start = CarbonImmutable::create(2019, 1, 1, 0, 0, 0, $this->today->timezone);
        for ($day = $start; $day <= $this->today; $day = $day->addDays($random->getInt(4, 13))) {
            $events[] = [$day, self::HOME, $random->getInt(2, 14), 0.4];
        }

        // Two or three trips a year, each several days long.
        for ($year = 2019; $year <= $this->today->year; $year++) {
            foreach (range(1, $random->getInt(2, 3)) as $_) {
                $place = self::TRIPS[$random->getInt(0, count(self::TRIPS) - 1)];
                $first = CarbonImmutable::create($year, $random->getInt(1, 12), $random->getInt(1, 25), 0, 0, 0, $this->today->timezone);
                foreach (range(0, $random->getInt(2, 6)) as $offset) {
                    $events[] = [$first->addDays($offset), $place, $random->getInt(8, 30), 0.95];
                }
            }
        }

        // Memories: the same calendar day in earlier years, and the last few days.
        foreach ([1, 2, 3, 5] as $yearsAgo) {
            $events[] = [$this->today->subYears($yearsAgo), self::TRIPS[$yearsAgo], $random->getInt(6, 16), 0.95];
        }
        foreach ([0, 1, 3] as $daysAgo) {
            $events[] = [$this->today->subDays($daysAgo), self::HOME, $random->getInt(3, 9), 0.5];
        }

        $items = [];
        foreach ($events as [$day, $place, $count, $gpsRate]) {
            if ($day > $this->today) {
                continue;
            }
            $camera = $this->cameraFor($random, $day->year);
            $time = $day->setTime($random->getInt(8, 12), $random->getInt(0, 59));
            foreach (range(1, $count) as $_) {
                $time = $time->addMinutes($random->getInt(1, 40))->addSeconds($random->getInt(0, 59));
                $hasGps = $random->nextFloat() < $gpsRate;
                // Draw the same numbers whatever the time: an early `break` at "now" would
                // shift the sequence for every later event and reshuffle the library.
                if ($time->isSameDay($day) && $time <= $this->today) {
                    $items[] = [$time, $place, $camera, $hasGps];
                }
            }
        }

        usort($items, fn (array $a, array $b) => $b[0] <=> $a[0]);

        $photos = [];
        $id = count($items);
        foreach ($items as [$time, $place, $camera, $hasGps]) {
            // Each item draws from its own stream, keyed by when it was taken: a photo
            // appearing "today" doesn't change any other. Ids count from the oldest, so
            // they stay put as the library grows.
            $own = new Randomizer(new Mt19937(crc32("{$this->seed}:{$time->getTimestamp()}")));
            $photos[$id] = $this->makePhoto($own, $id, $time, $place, $camera, $hasGps);
            $id--;
        }

        return $photos;
    }

    /** @return array{0: string, 1: string}|null */
    private function cameraFor(Randomizer $random, int $year): ?array
    {
        $candidates = array_values(array_filter(self::CAMERAS, fn (array $c) => $c[2] <= $year && $year <= $c[3]));
        if ($candidates === [] || $random->nextFloat() < 0.04) {
            return null; // messenger downloads carry no EXIF
        }

        $camera = $candidates[$random->getInt(0, count($candidates) - 1)];

        return [$camera[0], $camera[1]];
    }

    /**
     * @param  array{0: string, 1: string, 2: float, 3: float}  $place
     * @param  array{0: string, 1: string}|null  $camera
     */
    private function makePhoto(Randomizer $random, int $id, CarbonImmutable $time, array $place, ?array $camera, bool $hasGps): Photo
    {
        $isVideo = $random->nextFloat() < 0.08;
        [$width, $height] = $isVideo
            ? ($random->nextFloat() < 0.6 ? [1080, 1920] : [1920, 1080])
            : self::SHAPES[$random->getInt(0, count(self::SHAPES) - 1)];

        $seed = "fg-{$this->seed}-{$id}";
        [$tw, $th] = $this->fit($width, $height, 480);
        [$pw, $ph] = $this->fit($width, $height, 1600);
        $number = str_pad((string) ($id % 10000), 4, '0', STR_PAD_LEFT);
        $extension = $isVideo ? 'MOV' : ($camera !== null && $camera[0] === 'Apple' ? 'HEIC' : 'JPG');

        return new Photo(
            id: $id,
            filename: ($camera !== null && $camera[0] === 'Sony' ? 'DSC0' : 'IMG_').$number.'.'.$extension,
            isVideo: $isVideo,
            takenAt: $time,
            width: $width,
            height: $height,
            durationSeconds: $isVideo ? $random->getInt(4, 95) : null,
            city: $hasGps ? $place[0] : null,
            country: $hasGps ? $place[1] : null,
            lat: $hasGps ? round($place[2] + ($random->nextFloat() - 0.5) * 0.08, 5) : null,
            lon: $hasGps ? round($place[3] + ($random->nextFloat() - 0.5) * 0.08, 5) : null,
            cameraMake: $camera[0] ?? null,
            cameraModel: $camera[1] ?? null,
            favorite: $random->nextFloat() < 0.05,
            color: sprintf('hsl(%d %d%% %d%%)', $random->getInt(0, 359), $random->getInt(15, 40), $random->getInt(22, 42)),
            thumbUrl: "https://picsum.photos/seed/{$seed}/{$tw}/{$th}",
            previewUrl: "https://picsum.photos/seed/{$seed}/{$pw}/{$ph}",
            videoUrl: $isVideo ? self::SAMPLE_VIDEO : null,
        );
    }

    /** @return array{0: int, 1: int} the size that fits `$edge` on the long side */
    private function fit(int $width, int $height, int $edge): array
    {
        $scale = $edge / max($width, $height);

        return [(int) round($width * $scale), (int) round($height * $scale)];
    }

    /** @param  iterable<Photo>  $photos */
    private function makeAlbum(string $id, string $type, string $name, iterable $photos, ?string $subtitle = null): Album
    {
        $photos = array_values(is_array($photos) ? $photos : iterator_to_array($photos));
        $cover = collect($photos)->first(fn (Photo $p) => $p->favorite && ! $p->isVideo)
            ?? collect($photos)->first(fn (Photo $p) => ! $p->isVideo)
            ?? ($photos[0] ?? null);

        return new Album(
            id: $id,
            type: $type,
            name: $name,
            subtitle: $subtitle,
            count: count($photos),
            cover: $cover,
            from: $photos !== [] ? end($photos)->takenAt : null,
            to: $photos[0]->takenAt ?? null,
        );
    }
}
