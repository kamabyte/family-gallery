<?php

use App\Gallery\GalleryCatalog;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\File;
use Inertia\Testing\AssertableInertia as Assert;

/**
 * A small library shaped exactly like the indexer's output: .gallery/manifest.json →
 * catalogs/index-<rev>.db with photos/albums/photo_albums, and derivative files.
 */
function makeLibrary(): string
{
    $root = sys_get_temp_dir().'/gallery-'.bin2hex(random_bytes(4));
    File::ensureDirectoryExists("{$root}/.gallery/catalogs");

    $db = new PDO("sqlite:{$root}/.gallery/catalogs/index-000003-abc.db");
    $db->exec('CREATE TABLE photos (id INTEGER PRIMARY KEY, relative_path TEXT, filename TEXT, media_type TEXT, capture_date INTEGER,
        width INTEGER, height INTEGER, size_bytes INTEGER, content_hash TEXT, thumb_path TEXT, preview_path TEXT, video_path TEXT,
        duration_ms INTEGER, gps_lat REAL, gps_lon REAL, place_city TEXT, place_country TEXT, camera_make TEXT, camera_model TEXT)');
    $db->exec('CREATE TABLE albums (id INTEGER PRIMARY KEY, name TEXT, type TEXT, album_key TEXT, cover_photo_id INTEGER, photo_count INTEGER, sort_order INTEGER)');
    $db->exec('CREATE TABLE photo_albums (photo_id INTEGER, album_id INTEGER, capture_date INTEGER)');

    $msk = fn (string $at) => CarbonImmutable::parse($at, 'Europe/Moscow')->getTimestampMs();
    $photos = [
        // id, file, type, taken, city, country, model
        [1, '2025/07/IMG_1.HEIC', 'photo', $msk('2025-07-12 10:00'), 'Казань', 'Россия', 'iPhone 13'],
        [2, '2025/07/IMG_2.MOV', 'video', $msk('2025-07-12 11:00'), 'Казань', 'Россия', 'iPhone 13'],
        [3, '2025/07/IMG_3.jpg', 'photo', $msk('2025-07-31 23:30'), null, null, null],   // late evening Moscow = still July
        [4, '2026/01/IMG_4.jpg', 'photo', $msk('2026-01-05 09:00'), 'Сочи', 'Россия', 'iPhone 15 Pro'],
    ];
    $insert = $db->prepare('INSERT INTO photos VALUES (?, ?, ?, ?, ?, 4032, 3024, 1000, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)');
    foreach ($photos as [$id, $path, $type, $ms, $city, $country, $model]) {
        $hash = str_repeat(dechex($id), 64);
        $base = '.gallery/thumbs/'.substr($hash, 0, 2).'/'.$hash;
        $insert->execute([$id, $path, basename($path), $type, $ms, $hash, "{$base}_t.webp", "{$base}_p.webp",
            $type === 'video' ? "{$base}_v.mp4" : null, $type === 'video' ? 12_400 : null,
            $city, $country, $model ? 'Apple' : null, $model]);
        File::ensureDirectoryExists("{$root}/".dirname($base));
        File::put("{$root}/{$base}_t.webp", "thumb {$id}");
        File::ensureDirectoryExists("{$root}/".dirname($path));
        File::put("{$root}/".$path, "original {$id}");
    }

    $db->exec("INSERT INTO albums VALUES (10, 'Казань', 'place', 'Казань\x1fРоссия', 1, 2, 0),
                                         (11, 'Сочи', 'place', 'Сочи\x1fРоссия', 4, 1, 1),
                                         (12, 'Казань, июль 2025', 'trip', 'trip-2025-07-12', 1, 2, 0),
                                         (13, '2025', 'year', '2025', 1, 3, 0)");
    $db->exec("INSERT INTO photo_albums VALUES (1, 10, {$photos[0][3]}), (2, 10, {$photos[1][3]}), (4, 11, {$photos[3][3]}),
                                               (1, 12, {$photos[0][3]}), (2, 12, {$photos[1][3]}),
                                               (1, 13, {$photos[0][3]}), (2, 13, {$photos[1][3]}), (3, 13, {$photos[2][3]})");
    $db = null;

    File::put("{$root}/.gallery/manifest.json", json_encode([
        'schema_version' => 4, 'revision' => 3, 'catalog' => 'catalogs/index-000003-abc.db', 'generated_at' => '2026-10-04T09:30:00',
    ]));

    return $root;
}

beforeEach(function () {
    $this->root = makeLibrary();
    config(['gallery.driver' => 'catalog', 'gallery.root' => $this->root, 'gallery.accel_redirect' => null]);
    $this->actingAs(User::factory()->create());
});

afterEach(fn () => File::deleteDirectory($this->root));

it('reads buckets and months from the published catalog in the app timezone', function () {
    $catalog = app(GalleryCatalog::class);

    expect($catalog->buckets())->toBe([['month' => '2026-01', 'count' => 1], ['month' => '2025-07', 'count' => 3]])
        ->and(array_map(fn ($p) => $p->id, $catalog->month('2025-07')))->toBe([3, 2, 1]);
});

it('maps catalog rows onto the page shape, with media behind /media', function () {
    $this->getJson('/api/timeline/2025-07')
        ->assertOk()
        ->assertJsonPath('photos.1.type', 'video')
        ->assertJsonPath('photos.1.duration', 12)
        ->assertJsonPath('photos.2.place', ['city' => 'Казань', 'country' => 'Россия'])
        ->assertJsonPath('photos.2.camera', 'Apple iPhone 13')
        ->assertJsonPath('photos.2.thumb', '/media/.gallery/thumbs/11/'.str_repeat('1', 64).'_t.webp');
});

it('lists trips, places and years with country subtitles and smart albums', function () {
    $this->get('/albums')->assertInertia(fn (Assert $page) => $page
        ->where('albums', function ($albums) {
            $byId = collect($albums)->keyBy('id');

            return collect($albums)->pluck('id')->all() === ['favorites', 'videos', 'trip-12', 'place-10', 'place-11', 'year-13']
                && $byId['place-10']['subtitle'] === 'Россия'
                && $byId['videos']['count'] === 1
                && $byId['trip-12']['cover']['id'] === 1;
        }));

    $this->get('/albums/place-10')->assertInertia(fn (Assert $page) => $page
        ->component('album')
        ->where('photos', fn ($photos) => collect($photos)->pluck('id')->all() === [2, 1]));
});

it('steps the viewer through an album and keeps favourites per person', function () {
    $this->get('/photos/1?from=album:place-10')->assertInertia(fn (Assert $page) => $page
        ->where('previous.id', 2)
        ->where('next', null)
        ->where('albums', fn ($albums) => collect($albums)->pluck('id')->sort()->values()->all() === ['place-10', 'trip-12', 'year-13']));

    $this->put('/photos/4/favorite', ['favorite' => true]);
    $this->get('/albums/favorites')->assertInertia(fn (Assert $page) => $page->where('photos.0.id', 4));
});

it('searches Cyrillic case-insensitively, by month name too', function () {
    $this->get('/search?q='.urlencode('КАЗАНЬ июля'))->assertInertia(fn (Assert $page) => $page
        ->where('photos', fn ($photos) => collect($photos)->pluck('id')->all() === [2, 1]));
});

it('serves derivatives only by their exact shape', function () {
    $hash = str_repeat('1', 64);

    $this->get("/media/.gallery/thumbs/11/{$hash}_t.webp")
        ->assertOk()
        ->assertHeader('Content-Type', 'image/webp')
        ->assertHeader('Cache-Control', 'immutable, max-age=31536000, private');

    $this->get('/media/2025/07/IMG_1.HEIC')->assertNotFound();
    $this->get('/media/.gallery/catalogs/index-000003-abc.db')->assertNotFound();
    $this->get("/media/.gallery/thumbs/11/../../../2025/07/{$hash}_t.webp")->assertNotFound();
});

it('hands files to nginx with X-Accel-Redirect when configured', function () {
    config(['gallery.accel_redirect' => '/_gallery_media']);
    $hash = str_repeat('1', 64);

    $this->get("/media/.gallery/thumbs/11/{$hash}_t.webp")
        ->assertOk()
        ->assertHeader('X-Accel-Redirect', "/_gallery_media/.gallery/thumbs/11/{$hash}_t.webp");
});

it('downloads originals for family, never for guests', function () {
    $this->get('/photos/1/download')
        ->assertOk()
        ->assertHeader('Content-Disposition', 'attachment; filename=IMG_1.HEIC');

    $this->actingAs(User::factory()->guest()->create())->get('/photos/1/download')->assertForbidden();
});

it('requires a login for media', function () {
    auth()->logout();
    $this->get('/media/.gallery/thumbs/11/'.str_repeat('1', 64).'_t.webp')->assertRedirect('/login');
});

it('shows an empty library until the indexer publishes a catalog', function () {
    File::delete("{$this->root}/.gallery/manifest.json");

    $this->get('/')->assertOk()->assertInertia(fn (Assert $page) => $page->where('buckets', []));
});
